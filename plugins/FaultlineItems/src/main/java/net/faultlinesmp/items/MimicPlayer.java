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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import org.bukkit.Tag;
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
        UUID tabId; // its fake line in the tab list (an offline player 'joined')
        PlayerProfile skin;
        Skill skill;
        LivingEntity target;
        int t, strafeDir = 1, strafeFlip, lastHit = -100, lastHurt = -100, reactAt;
        int webTicks, chaseStuck, flyTicks, nextRocket, flyCooldown, idleAt;
        int tnt, fireCharges;
        ItemStack trident; // its trident (null while thrown away without Loyalty)
        int boomStep, boomWait, boomReady, spearReady, lungeUntil, tridentReady, riptideUntil, crossUntil, crossReady;
        int potRunUntil, rushUntil, digTicks, digNeed, detourDeg, detourUntil;
        int comboTaken, comboGiven, breakUntil, followT, baitReady, dodgeReady;
        Location followAt, tgPrev;
        Vector tgVel = new Vector();
        boolean totemHand;
        int poseUntil, swingId, doorUntil, xpPool, xpBottles, mendReady, catchAt, catchReady, tickerResetAt;
        Vector catchVel;
        final ItemStack[] wear = new ItemStack[4]; // ITS armour (own copies: they wear down, break, get mended)
        Block doorGoal;
        String lastHitInfo; // for /mimicplayer debug: how its last strike's number was made
        boolean busyNow() { return eatUntil > t || drinkUntil > t || drawUntil > t || potUntil > t || crossUntil > t; }
        // the walker (MimicNav): its path, where it was planned to, and how it's getting on
        List<MimicNav.Step> path; int wallTicks, pathIdx, pathAt = -100, navStuck, navProgressAt, navVanillaUntil; double navBest = 1e9;
        Block pathGoal; boolean navReach; final Map<Long, Integer> navBad = new HashMap<>(); Block pillarFrom;
        boolean riptideHit, tridentOut;
        String boomKind;
        Block boomAt, digBlock;
        float aimJx, aimJy, idleYaw;
        boolean idleWalk;
        boolean flying;
        int blockUntil, shieldOffUntil, eatUntil, drinkUntil, drawUntil, pearlReady, bowReady, wtapUntil, fleeUntil, axeUntil;
        int jukeReady, fleeWebReady; double digProgress;
        // 1.2.94 MIMIC FIGHTS: its team in a fight (0 = not in one; mimics fight every mimic of another team), who hit it last
        int team; String fightId; Mimic lastHitBy;
        // 1.2.95 its body as a real player entity on viewers' screens (MimicBody): its own hidden player entry, who sees it
        UUID bodyId = UUID.randomUUID(); boolean bodyOwnInfo = true; final Set<UUID> bodyViewers = new HashSet<>(); int bodyLookSig; String bodyEquipSig = "";
        // 1.2.95 elytra flight like a player's: where it looks, the rocket burning, the speed it carries
        float flyPitch, flyYaw; int boostTicks, takeoffAt, landT; Vector glide, glideAt;
        boolean surfacing;
        int leapTryAt, legAt, pearlSolveAt; Block legCached; // 1.2.95: a failed pearl hop isn't re-solved every tick, the leg goal is reused for 10 ticks
        double swimToY = Double.NaN; int swimToT = -100;
        int eyesLeft, rods, craftReady, reachTechReady, climbTicks, hopUntil, stageUntil, gapUntil, farShotReady, fleePlanAt; Block climbWater, stageGoal, fleeGoal; MimicNav.Step gapStep;
        int apples, gapples, pots, pearlsLeft, totems = 1, wind, lava, water, webs, blocks, rockets, arrows, throwables;
        final List<ItemStack> buffs = new ArrayList<>(), debuffs = new ArrayList<>();
        int buffReady, debuffReady, throwReady, jumpReset = -1, potUntil, lookDownUntil;
        ItemStack potting;
        String style = "sword";
        Gear gear;
        boolean sprintHit, eatAfterPearl, eatingGod, slam, dive, stunSlam, sweepHit;
        // HUNT (`/mimicplayer hunt`): its quarry (null = wherever players hang out), chunks it keeps loaded, travel state
        boolean hunting, travelFly; UUID huntId; final Set<Long> tickets = new HashSet<>(); World ticketWorld; int ticketAt, portalTicks, frameSearchAt, lastDigY = Integer.MIN_VALUE;
        Location endFrame; Block travelGoal;
        // 1.2.93 travel: the Nether shortcut, the leg it's on, how it's getting on, what it's doing (shown to spectators)
        boolean viaNether; World netherWorld; Location netherDest; int routeAt = -1000, travelBestAt, legBias, legTries; double travelBest = 1e18;
        String travelNote; Location landSpot;
        int escapeAt = -1, escapeReady, coverIdx, coverUntil, coverReady, trailReady, windRunReady; boolean covering, covered, healRun;
        List<Block> coverPlan = new ArrayList<>(), coverPlaced = new ArrayList<>();
        int windBreakReady, cancelReady, grappleReady, dtapReady, stunReady, scritAt = -1, bombPhase, bombUntil, dashReady;
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
        int gaps, egaps, pearls, pots, totems, wind, lava, water, webs, blocks, rockets, rods, eyes;
        boolean rod, flint, elytra;
        int arrows, throwables;
        int tnt, fireCharges, xpPoints, xpBottles;
        ItemStack spear, trident, crossbow, pickaxe, shovel;
        ItemStack breachMace; // a second mace with Breach (the main one is the best for slams: Density / Wind Burst)
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
                case MACE -> {
                    if (level(it, "breach") > 0 && (g.breachMace == null || level(it, "breach") > level(g.breachMace, "breach"))) g.breachMace = it.clone();
                    if (g.mace == null || slamScore(it) > slamScore(g.mace)) g.mace = it.clone();
                }
                case WIND_CHARGE -> g.wind += it.getAmount();
                case BREEZE_ROD -> g.rods += it.getAmount();
                case ENDER_EYE -> g.eyes += it.getAmount();
                case LAVA_BUCKET -> g.lava++;
                case WATER_BUCKET -> g.water++;
                case COBWEB -> g.webs += it.getAmount();
                case FISHING_ROD -> g.rod = true;
                case FLINT_AND_STEEL -> g.flint = true;
                case FIREWORK_ROCKET -> g.rockets += it.getAmount();
                case ELYTRA -> g.elytra = true;
                case EXPERIENCE_BOTTLE -> g.xpBottles += it.getAmount();
                case BOW -> { if (g.bow == null || level(it, "power") > level(g.bow, "power")) g.bow = it.clone(); }
                case SPLASH_POTION, POTION, LINGERING_POTION -> {
                    if (m == Material.SPLASH_POTION && healing(it)) { g.pots += it.getAmount(); break; }
                    List<PotionEffect> fx = effects(it);
                    boolean bad = fx.stream().anyMatch(e -> BAD.contains(e.getType())), good = fx.stream().anyMatch(e -> GOOD.contains(e.getType()));
                    for (int i = 0; i < it.getAmount(); i++) {
                        ItemStack one = it.clone(); one.setAmount(1);
                        if (m == Material.POTION) { if (good || !bad) { if (!fx.isEmpty()) g.buffs.add(one); } } // anything drinkable is for itself
                        else if (good && m == Material.SPLASH_POTION) g.buffs.add(one); else if (bad) g.debuffs.add(one); // lingering = short pulses: never its own buff
                    }
                }
                case ARROW, SPECTRAL_ARROW, TIPPED_ARROW -> g.arrows += it.getAmount();
                case TNT -> g.tnt += it.getAmount();
                case FIRE_CHARGE -> g.fireCharges += it.getAmount();
                case TRIDENT -> { if (g.trident == null || score(it) + level(it, "impaling") + 2 * level(it, "loyalty") > score(g.trident) + level(g.trident, "impaling") + 2 * level(g.trident, "loyalty")) g.trident = it.clone(); }
                case CROSSBOW -> { if (g.crossbow == null || level(it, "quick_charge") + level(it, "multishot") > level(g.crossbow, "quick_charge") + level(g.crossbow, "multishot")) g.crossbow = it.clone(); }
                case SNOWBALL, EGG -> g.throwables += it.getAmount();
                default -> {
                    if (m.name().endsWith("_SPEAR")) { if (score(it) > score(g.spear)) g.spear = it.clone(); }
                    else if (m.name().endsWith("_PICKAXE")) { if (toolSpeed(it) > toolSpeed(g.pickaxe)) g.pickaxe = it.clone(); }
                    else if (m.name().endsWith("_SHOVEL")) { if (toolSpeed(it) > toolSpeed(g.shovel)) g.shovel = it.clone(); }
                    else if (m.name().endsWith("_AXE")) { if (score(it) > score(g.axe)) g.axe = it.clone(); }
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
        if (held != null && weapon(held.getType()) && !held.getType().name().endsWith("_AXE") && !held.getType().name().endsWith("_SPEAR") && held.getType() != Material.TRIDENT) g.weapon = held.clone(); // what they're holding right now wins
        if (g.weapon == null) g.weapon = g.axe != null ? g.axe : g.trident != null ? g.trident : g.spear;
        if (g.chest != null && g.chest.getType() == Material.ELYTRA) g.elytra = true;
        if (g.breachMace != null && g.mace != null && g.breachMace.isSimilar(g.mace) && level(g.mace, "density") + level(g.mace, "wind_burst") == 0) g.breachMace = null; // one breach mace = the main one
        if (g.offhand == null) for (ItemStack it : inv.getStorageContents()) if (it != null && it.getType() == Material.SHIELD) { g.offhand = it.clone(); break; } // they'd swap it on
        try { g.maxHealth = p.getAttribute(Attribute.MAX_HEALTH).getValue(); } catch (RuntimeException e) { g.maxHealth = 20; }
        try { g.xpPoints = p.getTotalExperience(); } catch (RuntimeException ignored) { }
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

    static double armourPoints(LivingEntity e) {
        try { return e.getAttribute(Attribute.ARMOR).getValue(); } catch (RuntimeException ex) { return 0; }
    }

    /** How much more a hit lands when their armour works 15% less a Breach level (vanilla's armour formula, both ways). */
    static double breachMult(LivingEntity tg, double dmg, int breach) {
        if (breach <= 0) return 1;
        double a = armourPoints(tg), tough;
        try { tough = tg.getAttribute(Attribute.ARMOR_TOUGHNESS).getValue(); } catch (RuntimeException ex) { tough = 0; }
        double t = tough;
        java.util.function.DoubleUnaryOperator red = arm -> Math.min(20, Math.max(arm / 5, arm - 4 * dmg / (t + 8))) / 25;
        double before = 1 - red.applyAsDouble(a), after = 1 - red.applyAsDouble(a * Math.max(0, 1 - 0.15 * breach));
        return before <= 0.01 ? 1 : after / before;
    }

    static int slamScore(ItemStack it) { return 2 * level(it, "density") + 2 * level(it, "wind_burst") + level(it, "breach"); }

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
        try { loadMemory(); } catch (RuntimeException ignored) { }
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
                g.mace = c.getItemStack("mace"); g.breachMace = c.getItemStack("breach-mace");
                g.wind = c.getInt("wind"); g.rods = c.getInt("rods"); g.eyes = c.getInt("eyes"); g.lava = c.getInt("lava"); g.water = c.getInt("water"); g.webs = c.getInt("webs");
                g.blocks = c.getInt("blocks"); g.rockets = c.getInt("rockets");
                g.rod = c.getBoolean("rod"); g.flint = c.getBoolean("flint"); g.elytra = c.getBoolean("elytra");
                g.arrows = c.getInt("arrows"); g.throwables = c.getInt("throwables");
                g.tnt = c.getInt("tnt"); g.fireCharges = c.getInt("fire-charges");
                g.xpPoints = c.getInt("xp-points"); g.xpBottles = c.getInt("xp-bottles");
                g.spear = c.getItemStack("spear"); g.trident = c.getItemStack("trident"); g.crossbow = c.getItemStack("crossbow");
                g.pickaxe = c.getItemStack("pickaxe"); g.shovel = c.getItemStack("shovel");
                for (Object o : c.getList("buffs", List.of())) if (o instanceof ItemStack is) g.buffs.add(is);
                for (Object o : c.getList("debuffs", List.of())) if (o instanceof ItemStack is) g.debuffs.add(is);
                Material bm = Material.matchMaterial(c.getString("block", "COBBLESTONE"));
                if (bm != null) g.block = bm;
                snapshots.put(UUID.fromString(k), g);
            } catch (RuntimeException ignored) { }
        }
    }

    void saveGear() {
        try { saveMemory(); } catch (RuntimeException ignored) { }
        if (gearFile == null) return;
        var y = new org.bukkit.configuration.file.YamlConfiguration();
        for (var e : snapshots.entrySet()) {
            String k = e.getKey().toString() + ".";
            Gear g = e.getValue();
            y.set(k + "helmet", g.helmet); y.set(k + "chest", g.chest); y.set(k + "legs", g.legs); y.set(k + "boots", g.boots);
            y.set(k + "weapon", g.weapon); y.set(k + "axe", g.axe); y.set(k + "bow", g.bow); y.set(k + "offhand", g.offhand);
            y.set(k + "gaps", g.gaps); y.set(k + "egaps", g.egaps); y.set(k + "pearls", g.pearls); y.set(k + "pots", g.pots); y.set(k + "totems", g.totems);
            y.set(k + "max-health", g.maxHealth);
            y.set(k + "mace", g.mace); y.set(k + "breach-mace", g.breachMace);
            y.set(k + "wind", g.wind); y.set(k + "rods", g.rods); y.set(k + "eyes", g.eyes); y.set(k + "lava", g.lava); y.set(k + "water", g.water); y.set(k + "webs", g.webs);
            y.set(k + "blocks", g.blocks); y.set(k + "rockets", g.rockets);
            y.set(k + "arrows", g.arrows); y.set(k + "throwables", g.throwables);
            y.set(k + "tnt", g.tnt); y.set(k + "fire-charges", g.fireCharges);
            y.set(k + "xp-points", g.xpPoints); y.set(k + "xp-bottles", g.xpBottles);
            y.set(k + "spear", g.spear); y.set(k + "trident", g.trident); y.set(k + "crossbow", g.crossbow); y.set(k + "pickaxe", g.pickaxe); y.set(k + "shovel", g.shovel);
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

    ItemStack[] armour(Mimic m) { return m.wear; }

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

    MimicHunt hunt;
    MimicWatch watch;

    MimicPlayer(FaultlineItems plugin) {
        this.plugin = plugin;
        watch = new MimicWatch(plugin, this);
        try { hunt = new MimicHunt(plugin); } catch (RuntimeException | LinkageError e) { plugin.getLogger().warning("[Mimic] hunt tracking off: " + e); }
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
        Gear arm = m.gear;
        if (plugin.getConfig().getBoolean("mimic-player.kit-when-bare", true) && arm.helmet == null && arm.chest == null && arm.legs == null && arm.boots == null) arm = kitGear(); // no armour at all: the kit's
        ItemStack[] src = {arm.helmet, arm.chest, arm.legs, arm.boots};
        for (int i = 0; i < 4; i++) m.wear[i] = src[i] == null ? null : src[i].clone();
        m.xpPool = m.gear.xpPoints; m.xpBottles = m.gear.xpBottles;
        m.apples = m.gear.gaps; m.gapples = m.gear.egaps; m.pots = m.gear.pots; m.pearlsLeft = m.gear.pearls; m.totems = m.gear.totems;
        m.arrows = m.gear.arrows; m.throwables = m.gear.throwables;
        m.tnt = m.gear.tnt; m.fireCharges = m.gear.fireCharges;
        m.trident = m.gear.trident == null ? null : m.gear.trident.clone();
        m.buffs.addAll(m.gear.buffs); m.debuffs.addAll(m.gear.debuffs);
        m.style = style(m.gear);
        m.rods = m.gear.rods; m.eyesLeft = m.gear.eyes;
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
                m.skin = pp;
                b.setProfile(ResolvableProfile.resolvableProfile(pp));
                if (!pp.hasTextures()) pp.update().thenAccept(full -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!b.isValid()) return;
                    Emotes.q(() -> b.setProfile(ResolvableProfile.resolvableProfile(full)));
                    m.skin = full;
                    MimicBody.remove(plugin, m); // its player body comes back next tick with the skin
                    if (m.tabId != null) { // the skin wasn't cached when it "joined": the tab line again, with it
                        MimicTab.remove(Bukkit.getOnlinePlayers(), m.tabId);
                        MimicTab.add(Bukkit.getOnlinePlayers(), m.tabId, m.name, m.skin, tabName(m));
                    }
                }));
            } catch (RuntimeException | LinkageError ignored) { }
        }
        m.brain = v; m.body = b;
        m.yaw = at.getYaw(); m.idleYaw = at.getYaw();
        if (plugin.getConfig().getBoolean("mimic-player.kingdom-tag", true)) m.tag = kingdomTag(m, at);
        byBrain.put(v.getUniqueId(), m);
        byBody.put(b.getUniqueId(), m);
        joinGame(m, who);
        return m;
    }

    static void attr(LivingEntity e, Attribute a, double v) {
        AttributeInstance i = e.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    private void dress(Mimic m, EntityEquipment eq) {
        Gear g = m.gear;
        eq.setHelmet(m.wear[0] == null ? null : m.wear[0].clone());
        eq.setChestplate(m.wear[1] == null ? null : m.wear[1].clone());
        eq.setLeggings(m.wear[2] == null ? null : m.wear[2].clone());
        eq.setBoots(m.wear[3] == null ? null : m.wear[3].clone());
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
            case "breach" -> g.breachMace != null ? g.breachMace : g.mace;
            case "wind" -> new ItemStack(Material.WIND_CHARGE);
            case "lava" -> new ItemStack(Material.LAVA_BUCKET);
            case "water" -> new ItemStack(Material.WATER_BUCKET);
            case "web" -> new ItemStack(Material.COBWEB);
            case "rod" -> new ItemStack(Material.FISHING_ROD);
            case "flint" -> new ItemStack(Material.FLINT_AND_STEEL);
            case "block" -> new ItemStack(g.block);
            case "rocket" -> new ItemStack(Material.FIREWORK_ROCKET);
            case "tnt" -> new ItemStack(Material.TNT);
            case "firecharge" -> new ItemStack(Material.FIRE_CHARGE);
            case "spear" -> g.spear;
            case "trident" -> m.trident;
            case "crossbow" -> g.crossbow;
            case "pickaxe" -> g.pickaxe;
            case "shovel" -> g.shovel;
            default -> g.weapon;
        };
        return it == null ? null : it.clone();
    }

    ItemStack weaponHeld(Mimic m) {
        return switch (m.held) { case "axe" -> m.gear.axe; case "mace" -> m.gear.mace; case "breach" -> m.gear.breachMace != null ? m.gear.breachMace : m.gear.mace; case "spear" -> m.gear.spear; case "trident" -> m.trident; default -> m.gear.weapon; };
    }

    /** What kind of fighter their inventory says they are (mace / nethop / diamond / uhc / axe / sword). */
    static String style(Gear g) {
        if (g.mace != null && g.spear != null) return "spearmace";
        if (g.mace != null) return "mace";
        boolean neth = g.chest != null && g.chest.getType().name().startsWith("NETHERITE");
        boolean potions = g.buffs.size() + g.pots + g.debuffs.size() >= 3;
        if (potions && g.pots >= 3) return neth ? "nethpot" : "pot"; // healing-pot kits
        if (potions || neth && g.gaps + g.egaps > 0 && g.totems > 0) return neth ? "nethop" : "diamond";
        if (g.trident != null && (g.weapon == null || g.weapon.getType() == Material.TRIDENT)) return "trident";
        if (g.spear != null && (g.weapon == null || g.weapon.getType().name().endsWith("_SPEAR"))) return "spear";
        if ((g.bow != null || g.crossbow != null) && g.arrows >= 32 && score(g.weapon) < 6) return "bow";
        if (g.lava > 0 || g.webs > 0 || g.rod || g.blocks >= 16) return "uhc";
        if (g.axe != null && g.offhand != null && g.offhand.getType() == Material.SHIELD) return "axe";
        return "sword";
    }

    boolean hasShield(Mimic m) { return !m.totemHand && m.gear.offhand != null && m.gear.offhand.getType() == Material.SHIELD; }

    static ItemStack healingPotion() {
        ItemStack it = new ItemStack(Material.SPLASH_POTION);
        if (it.getItemMeta() instanceof PotionMeta pm) { try { pm.setBasePotionType(PotionType.STRONG_HEALING); } catch (RuntimeException ignored) { } it.setItemMeta(pm); }
        return it;
    }

    void hold(Mimic m, String what) {
        if (what.equals(m.held)) return;
        ItemStack before = hotbar(m, m.held), after = hotbar(m, what);
        m.held = what;
        m.body.getEquipment().setItemInMainHand(after);
        if (before == null ? after != null : !before.isSimilar(after)) m.tickerResetAt = m.t + 1; // a slot change resets the swing meter
    }

    // ---- the human bits: aim shake, misses, swap time, how fast it turns its head
    double missChance(Mimic m) { return switch (m.skill.id()) { case "easy" -> 0.25; case "hard" -> 0.07; case "godlike" -> 0.03; default -> 0.14; }; }
    int swapTicks(Mimic m) { return switch (m.skill.id()) { case "easy" -> 4; case "hard" -> 2; case "godlike" -> 1; default -> 3; }; }
    float turnRate(Mimic m) { return switch (m.skill.id()) { case "easy" -> 22f; case "hard" -> 50f; case "godlike" -> 65f; default -> 35f; }; }

    /** A swing that misses (players miss): the arm swings, the cooldown resets, nothing lands. */
    private boolean whiff(Mimic m, LivingEntity tg) {
        if (random.nextDouble() >= missChance(m)) return false;
        swing(m);
        m.brain.getWorld().playSound(m.brain.getLocation(), Sound.ENTITY_PLAYER_ATTACK_NODAMAGE, 0.8f, 1);
        m.lastHit = m.t;
        m.sprintHit = false;
        return true;
    }

    /** Nobody to fight: looks around, now and then wanders a few steps (it never just stands frozen). */
    private void idle(Mimic m) {
        Vindicator v = m.brain;
        if (m.t >= m.idleAt) {
            m.idleAt = m.t + 40 + random.nextInt(100);
            m.idleYaw = m.yaw + (random.nextFloat() - 0.5f) * 160f;
            m.idleWalk = random.nextDouble() < 0.35;
        }
        if (m.idleWalk && m.idleAt - m.t > 20) {
            double r = Math.toRadians(m.idleYaw);
            move(m, new Vector(-Math.sin(r), 0, Math.cos(r)), 0.1, false, false);
        }
    }

    /** Their kingdom line over the name, like FaultlineKingdoms' head tags (read by reflection). */
    private TextDisplay kingdomTag(Mimic m, Location at) {
        String line = kingdomLine(m.who);
        if (line == null) {
            if (!tagWarned && Bukkit.getPluginManager().getPlugin("FaultlineKingdoms") == null) { tagWarned = true; Bukkit.getLogger().warning("[Mimic] no kingdom tag: FaultlineKingdoms isn't loaded"); }
            return null;
        }
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

    /** Its tab list name the way FaultlineKingdoms shows real players: "[Kingdom 1] Cloud" in the kingdom's colour, or "Outlaw Cloud". */
    static Component tabName(Mimic m) {
        try {
            org.bukkit.plugin.Plugin k = Bukkit.getPluginManager().getPlugin("FaultlineKingdoms");
            if (k == null || !k.isEnabled() || !k.getConfig().getBoolean("display.tab-prefix", true)) return null;
            int n = m.who == null ? 0 : (int) k.getClass().getMethod("kingdomOf", UUID.class).invoke(null, m.who);
            var c = k.getConfig();
            String line;
            if (n > 0) {
                ChatColor col;
                try { col = ChatColor.valueOf(c.getString("kingdoms." + n + ".color", "WHITE").toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { col = ChatColor.WHITE; }
                line = col + "[" + c.getString("kingdoms." + n + ".name", "Kingdom " + n) + "] " + ChatColor.RESET + m.name;
            } else if (c.getBoolean("display.tab-outlaw", true)) line = kingdomLine(m.who) + " " + ChatColor.RESET + m.name;
            else return null;
            return LegacyComponentSerializer.legacySection().deserialize(line);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return null; }
    }

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
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (!tagWarned) { tagWarned = true; Bukkit.getLogger().warning("[Mimic] couldn't read FaultlineKingdoms for the kingdom tag: " + e); }
            return null;
        }
    }
    static boolean tagWarned;

    // ---- an OFFLINE player mimicked "joins the game": the yellow join line and a line in the tab list, like a real login;
    // gone = "left the game" and out of the tab list
    void joinGame(Mimic m, OfflinePlayer who) {
        if (who != null && who.isOnline()) return; // they're really here: already in the tab list
        if (plugin.getConfig().getBoolean("mimic-player.fake-join", true))
            announce(Component.translatable("multiplayer.player.joined", net.kyori.adventure.text.format.NamedTextColor.YELLOW, Component.text(m.name)));
        if (plugin.getConfig().getBoolean("mimic-player.tab-list", true)) {
            m.tabId = UUID.randomUUID();
            MimicTab.add(Bukkit.getOnlinePlayers(), m.tabId, m.name, m.skin, tabName(m));
        }
    }

    static void announce(Component c) {
        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(c);
        try { Bukkit.getConsoleSender().sendMessage(c); } catch (RuntimeException ignored) { }
    }

    void leaveGame(Mimic m) {
        if (m.tabId != null) { MimicTab.remove(Bukkit.getOnlinePlayers(), m.tabId); m.tabId = null; }
        else if (m.who != null && Bukkit.getPlayer(m.who) != null) return;
        if (plugin.getConfig().getBoolean("mimic-player.fake-join", true) && (m.who == null || Bukkit.getPlayer(m.who) == null))
            announce(Component.translatable("multiplayer.player.left", net.kyori.adventure.text.format.NamedTextColor.YELLOW, Component.text(m.name)));
    }

    @EventHandler
    public void onRealJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        Player p = e.getPlayer();
        for (Mimic m : byBrain.values()) {
            if (m.tabId == null) continue;
            if (p.getUniqueId().equals(m.who)) { MimicTab.remove(Bukkit.getOnlinePlayers(), m.tabId); m.tabId = null; continue; } // the real one logged in: one line, theirs
            MimicTab.add(List.of(p), m.tabId, m.name, m.skin, tabName(m));
        }
    }

    void remove(Mimic m) {
        MimicBody.remove(plugin, m);
        releaseTickets(m);
        leaveGame(m);
        byBrain.remove(m.brain.getUniqueId());
        byBody.remove(m.body.getUniqueId());
        try { m.brain.remove(); } catch (RuntimeException ignored) { }
        try { m.body.remove(); } catch (RuntimeException ignored) { }
        if (m.tag != null && m.tag.isValid()) m.tag.remove();
    }

    void removeAll() {
        if (hunt != null) try { hunt.save(); } catch (RuntimeException ignored) { }
        for (Mimic m : new ArrayList<>(byBrain.values())) remove(m);
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntitiesByClass(org.bukkit.entity.Projectile.class)) // pearls still in the air (a one-off, on the command)
            if (e.getScoreboardTags().contains(TAG)) e.remove();
        restoreAll(); try { saveGear(); } catch (RuntimeException ignored) { }
        if (watch != null) watch.stopAll(); }

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
    private Player nearest(Mob z, LivingEntity keep) {
        if (keep instanceof Player kp && kp.isValid() && !keep.isDead() && keep.getWorld() == z.getWorld()
                && (kp.getGameMode() == GameMode.SURVIVAL || kp.getGameMode() == GameMode.ADVENTURE) && !kp.hasMetadata("vanished")
                && keep.getLocation().distanceSquared(z.getLocation()) < Math.pow(range() * 1.5, 2)) {
            // stays on its target unless someone else is right on top of it
            Player other = nearest(z, null);
            if (other == null || other == keep || other.getLocation().distanceSquared(z.getLocation()) > 9) return (Player) keep;
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

    // ---- its opponent can be a player OR another mimic (1.2.94): what it reads off them, either way

    Mimic mimicOf(Entity e) { return e == null ? null : byBrain.get(e.getUniqueId()); }

    boolean blocking(LivingEntity e) {
        if (e instanceof HumanEntity h) return h.isBlocking();
        Mimic o = mimicOf(e);
        return o != null && o.blockUntil > o.t && o.shieldOffUntil <= o.t;
    }

    static boolean sprinting(LivingEntity e) {
        if (e instanceof Player p) return p.isSprinting();
        Vector v = e.getVelocity();
        return v.getX() * v.getX() + v.getZ() * v.getZ() > 0.04;
    }

    ItemStack mainHand(LivingEntity e) {
        if (e instanceof Player p) return p.getInventory().getItemInMainHand();
        Mimic o = mimicOf(e);
        var eq = o != null ? o.body.getEquipment() : e.getEquipment();
        ItemStack it = eq == null ? null : eq.getItemInMainHand();
        return it == null ? new ItemStack(Material.AIR) : it;
    }

    ItemStack offHand(LivingEntity e) {
        if (e instanceof Player p) return p.getInventory().getItemInOffHand();
        Mimic o = mimicOf(e);
        var eq = o != null ? o.body.getEquipment() : e.getEquipment();
        ItemStack it = eq == null ? null : eq.getItemInOffHand();
        return it == null ? new ItemStack(Material.AIR) : it;
    }

    float attackCharge(LivingEntity e) {
        if (e instanceof Player p) return p.getAttackCooldown();
        return 1f;
    }

    /** What they're using right now (eating, drawing a bow, drinking...), or null. */
    ItemStack usingNow(LivingEntity e) {
        Mimic o = mimicOf(e);
        if (o != null) {
            if (o.eatUntil > o.t) return new ItemStack(o.eatingGod ? Material.ENCHANTED_GOLDEN_APPLE : Material.GOLDEN_APPLE);
            if (o.drinkUntil > o.t) return new ItemStack(Material.POTION);
            if (o.drawUntil > o.t) return new ItemStack(Material.BOW);
            if (o.crossUntil > o.t) return new ItemStack(Material.CROSSBOW);
            return null;
        }
        return e.isHandRaised() ? e.getActiveItem() : null;
    }

    /** An axe knocked their shield out. */
    void breakShield(LivingEntity e, int ticks) {
        if (e instanceof Player p) { Emotes.q(() -> p.setCooldown(Material.SHIELD, ticks)); return; }
        Mimic o = mimicOf(e);
        if (o == null) return;
        o.shieldOffUntil = o.t + ticks; o.blockUntil = 0;
        Emotes.q(() -> o.body.clearActiveItem());
    }

    void combat(LivingEntity e) { if (e instanceof Player p) combat(p); }

    String nameOf(LivingEntity e) { Mimic o = mimicOf(e); return o != null ? o.name : e.getName(); }

    // ================================================================ mimic fights (1.2.94)

    /** In a fight: the nearest living mimic of another team (players are left alone); else the nearest player. */
    LivingEntity foeOrNearest(Mimic m) {
        if (m.team != 0) {
            LivingEntity best = null; double bd = Double.MAX_VALUE;
            for (Mimic o : byBrain.values()) {
                if (o == m || o.team == 0 || o.team == m.team || !java.util.Objects.equals(o.fightId, m.fightId) || !o.brain.isValid() || o.brain.isDead() || o.brain.getWorld() != m.brain.getWorld()) continue;
                double d = o.brain.getLocation().distanceSquared(m.brain.getLocation());
                if (m.target == o.brain) d *= 0.6; // sticks with who it's on
                if (d < bd) { bd = d; best = o.brain; }
            }
            if (best != null) return best;
            endFight(m.fightId, m); // nobody left on another team: it won
        }
        return nearest(m.brain, m.target);
    }

    /** Starts a fight between these mimics: one per team for "ffa", else the given teams. */
    void startFight(List<List<Mimic>> teams) {
        String id = Long.toString(System.nanoTime(), 36);
        int t = 1;
        for (List<Mimic> team : teams) {
            for (Mimic m : team) { m.team = t; m.fightId = id; m.target = null; m.path = null; m.hunting = false; m.fleeUntil = 0; m.reactAt = m.t + m.skill.reaction(); }
            t++;
        }
    }

    /** A fighter died: the last team standing wins (announced to admins near / everyone in the fight's world). */
    void fightDeath(Mimic dead) {
        if (dead.team == 0) return;
        String id = dead.fightId;
        dead.team = 0;
        Set<Integer> alive = new HashSet<>();
        for (Mimic o : byBrain.values()) if (o != dead && o.team != 0 && id.equals(o.fightId) && o.brain.isValid() && !o.brain.isDead()) alive.add(o.team);
        if (alive.size() <= 1) endFight(id, null);
    }

    void endFight(String id, Mimic hint) {
        if (id == null) return;
        List<Mimic> winners = new ArrayList<>();
        for (Mimic o : byBrain.values()) if (id.equals(o.fightId) && o.brain.isValid() && !o.brain.isDead()) winners.add(o);
        for (Mimic o : winners) { o.team = 0; o.fightId = null; o.target = null; }
        if (winners.isEmpty()) return;
        StringBuilder names = new StringBuilder();
        for (Mimic o : winners) { if (names.length() > 0) names.append(", "); names.append(o.name).append(" (").append(Math.round(o.brain.getHealth())).append(" hp)"); }
        String line = ChatColor.GOLD + "[Mimic fight] " + ChatColor.WHITE + names + ChatColor.GOLD + (winners.size() == 1 ? " wins!" : " win!");
        for (Player p : winners.get(0).brain.getWorld().getPlayers()) if (p.hasPermission("items.mimicplayer") || p.getLocation().distanceSquared(winners.get(0).brain.getLocation()) < 96 * 96) p.sendMessage(line);
        plugin.getLogger().info(ChatColor.stripColor(line));
    }

    /** Attack cooldown in ticks of what it holds (sword 1.6 attacks/s, axe 1.0). */
    /** Ticks a full swing takes = 20 / the item's attack speed (vanilla). */
    static double cooldown(ItemStack w) {
        if (w == null) return 5; // fist: 4 / s
        String n = w.getType().name();
        if (n.endsWith("_SWORD")) return 12.5; // 1.6
        if (n.endsWith("_AXE")) return n.startsWith("WOODEN") || n.startsWith("STONE") ? 25 : n.startsWith("IRON") ? 20 / 0.9 : 20; // 0.8 / 0.9 / 1.0
        if (n.equals("TRIDENT")) return 20 / 1.1;
        if (n.equals("MACE")) return 20 / 0.6;
        if (n.endsWith("_SPEAR")) return 20 / 1.1;
        if (n.endsWith("_PICKAXE")) return 20 / 1.2;
        if (n.endsWith("_SHOVEL")) return 20.0;
        if (n.endsWith("_HOE")) return 20 / 1.0;
        return 5;
    }

    /** Vanilla resets the attack ticker the tick AFTER the item in hand changes (a hit in the same tick keeps the old charge). */
    void ticker(Mimic m) {
        if (m.tickerResetAt > 0 && m.t >= m.tickerResetAt) { m.lastHit = Math.max(m.lastHit, m.tickerResetAt); m.tickerResetAt = 0; }
    }

    /** Vanilla's attack strength: (ticks since the last swing + 0.5) / delay. */
    double charge(Mimic m, ItemStack w) { ticker(m); return Math.max(0, Math.min(1, (m.t - m.lastHit + 0.5) / cooldown(w))); }

    double charge(Mimic m) { return charge(m, weaponHeld(m)); }

    /** Its reach as the victim sees it: from the brain AND the body they're looking at, whichever is further. */
    static double reachOf(Mimic m, LivingEntity tg) {
        double a = reachTo(m.brain, tg);
        try { if (m.body.isValid() && m.body.getWorld() == tg.getWorld()) a = Math.max(a, reachTo(m.body, tg)); } catch (RuntimeException ignored) { }
        return a;
    }

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
        // stuck inside a block (knocked into a wall, a block placed on it): out to the nearest cell it fits, never frozen there
        if (inWall(v)) { if (++m.wallTicks > 8) { unwall(m); m.wallTicks = 0; m.path = null; } } else m.wallTicks = 0;
        // its kingdom line over its head: put back if anything removed it, the text kept up to date (kingdom changes)
        if (m.t % 40 == 0 && plugin.getConfig().getBoolean("mimic-player.kingdom-tag", true)) {
            if (m.tag == null || !m.tag.isValid()) m.tag = kingdomTag(m, v.getLocation());
            else { String line = kingdomLine(m.who); if (line != null) m.tag.text(LegacyComponentSerializer.legacySection().deserialize(line)); }
        }
        // out of wind charges with breeze rods on it: crafts them in its inventory (1 rod = 4 charges), a second's fiddle
        if (m.wind == 0 && m.rods > 0 && m.t >= m.craftReady) { m.rods--; m.wind += 4; m.craftReady = m.t + 20; v.getWorld().playSound(v.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.4f); }
        if (m.climbWater != null) { waterClimb(m, m.target); follow(m); return; }
        if (m.hunting) { // HUNTING: its quarry anywhere (any range, any world); close = the normal fight, else it travels
            keepLoaded(m);
            Player q = m.huntId != null ? Bukkit.getPlayer(m.huntId) : null;
            boolean ok = q != null && !q.isDead() && (q.getGameMode() == GameMode.SURVIVAL || q.getGameMode() == GameMode.ADVENTURE) && !q.hasMetadata("vanished");
            LivingEntity near = ok ? q : nearest(v, m.target);
            if (near != null && near.getWorld() == v.getWorld() && near.getLocation().distance(v.getLocation()) < 64) {
                if (m.target != near) { m.reactAt = m.t + m.skill.reaction(); m.target = near; v.setTarget(near); }
                if (m.travelFly && m.brain.isOnGround()) { m.travelFly = false; land(m); }
            } else {
                m.target = null;
                if (m.busyNow()) { busyTick(m, null); follow(m); return; }
                if (m.digBlock != null && digTick(m)) { follow(m); return; } // mining its way (down to a portal, through a wall)
                travel(m, ok ? q : null);
                follow(m);
                return;
            }
        }
        if (!m.hunting && (m.t % 10 == 0 || m.target == null || !m.target.isValid() || m.target.isDead() || m.target.getWorld() != v.getWorld())) {
            LivingEntity n = foeOrNearest(m);
            if (n != m.target) m.reactAt = m.t + m.skill.reaction();
            m.target = n;
            if (n != null && v.getTarget() != n) v.setTarget(n);
        }
        LivingEntity tg = m.target;
        double hp = v.getHealth() / maxHp(v);
        if (tg != null) { // its own read of where they're heading (a player's server velocity is mostly 0)
            Location now = tg.getLocation();
            if (m.tgPrev != null && m.tgPrev.getWorld() == now.getWorld() && m.tgPrev.distanceSquared(now) < 25) {
                Vector step = now.toVector().subtract(m.tgPrev.toVector()).setY(0);
                m.tgVel = m.tgVel.multiply(0.6).add(step.multiply(0.4));
            } else m.tgVel = new Vector();
            m.tgPrev = now.clone();
            learn(m, tg);
        }
        boolean busy = m.eatUntil > m.t || m.drinkUntil > m.t || m.drawUntil > m.t || m.potUntil > m.t || m.crossUntil > m.t;
        // natural regen like a fed player, out of combat
        if (m.t - m.lastHurt > 120 && m.t % 10 == 0 && v.getHealth() < maxHp(v)) v.setHealth(Math.min(maxHp(v), v.getHealth() + 1));

        clutch(m); // water MLG on a long fall, any time
        if (windAir(m, tg)) { follow(m); return; }
        if (m.scritAt == m.t && v.isOnGround() && tg != null) { // S-CRIT: hit, so it hops back out of their reach and crits them as they follow
            Vector back = v.getLocation().toVector().subtract(tg.getLocation().toVector()).setY(0);
            if (back.lengthSquared() > 1e-4 && safePath(v, back.clone().normalize(), 1.8)) Safe.vel(v, back.normalize().multiply(0.22).setY(0.42));
        }
        if (m.bombPhase > 0 && tg != null && bombTick(m, tg)) { follow(m); return; }
        if (m.escapeAt >= 0 && m.t >= m.escapeAt) { // trapped and being hit: fights back if they're in reach, else gets out
            m.escapeAt = -1;
            if (tg == null || reachOf(m, tg) > cfg("reach", 3.0) || !sees(v, tg)) { if (escape(m, tg)) { follow(m); return; } }
            else m.escapeAt = m.t + 20; // in reach: the fight goes on, it tries again in a second
        }
        if (airCatch(m, tg)) { follow(m); return; }
        if (m.jumpAt == m.t && v.isOnGround() && tg != null) { // crit chain: back up into the air right after a crit
            Vector to = tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
            if (to.lengthSquared() > 1e-4 && safePath(v, to.clone().normalize(), 2.5)) Safe.vel(v, to.normalize().multiply(0.25).setY(0.42));
        }
        if (m.jumpReset == m.t && v.isOnGround()) { // JUMP RESET: jumping as the hit lands takes the knockback down
            Vector cur = v.getVelocity();
            Safe.vel(v, new Vector(cur.getX() * 0.45, 0.42, cur.getZ() * 0.45));
        }
        if (busy) { busyTick(m, tg); follow(m); return; }
        if (tg != null && followPearl(m, tg)) { follow(m); return; } // they pearled: after them first, before anything else
        totemSwap(m, hp);
        if (webEscape(m, tg)) { follow(m); return; }
        if (tg != null && boomTick(m, tg)) { follow(m); return; }
        if (tg != null && riptideTick(m, tg)) { follow(m); return; }
        if (tg != null && m.digBlock != null && digTick(m)) { follow(m); return; }
        if (tg == null) { hold(m, "sword"); idle(m); follow(m); return; }
        double dist = tg.getLocation().distance(v.getLocation());
        boolean sees = sees(v, tg);

        // ---- POT PvP: keep its buffs up (Strength, Speed, Fire Res... whatever it carries), debuff you, snowball you
        if (selfExtinguish(m)) { follow(m); return; }
        if (m.potRunUntil > m.t) { potRun(m, tg); follow(m); return; }
        // re-pots only with SPACE (a player never pops a Strength right in your face): 6+ blocks, or (hard+) the moment after
        // its hit knocked them back; drinkables (1.6 s) want 10+
        boolean space = dist >= 6 || tier(m) >= 3 && m.t - m.lastHit <= 4 && dist >= 4;
        if (m.rushUntil <= m.t && dist < 24 && m.t >= m.buffReady && space && buff(m, dist)) { follow(m); return; }
        if (tier(m) >= 2 && m.rushUntil <= m.t && dist > 2.5 && dist < 8 && sees && m.t >= m.debuffReady && debuff(m, tg)) { follow(m); return; }
        if (tier(m) >= 2 && m.throwables > 0 && dist > 3.5 && dist < 10 && sees && m.t >= m.throwReady && sprinting(tg) && random.nextDouble() < 0.15) { snowball(m, tg); follow(m); return; }
        // ---- heal up
        if (hp < cfg("potion-below", 0.45) && m.pots > 0 && m.t - m.lastHit > 4) {
            // POT RUN (normal+): sprint off, throw the pot down on the run, turn and come straight back in. Easy just pots.
            if (tier(m) >= 2 && dist < 8) { m.potRunUntil = m.t + switch (tier(m)) { case 4 -> 6; case 3 -> 8; default -> 11; }; hold(m, "potion"); potRun(m, tg); }
            else drink(m);
            follow(m); return;
        }
        boolean food = m.apples > 0 || m.gapples > 0;
        // ---- boxed up to heal (or opening back up once healed)
        if (m.covering || m.covered) { coverTick(m, tg, hp, food); follow(m); return; }
        boolean needHeal = hp < cfg("eat-below", 0.4) && food;
        // 1.2.93: eats only with real ROOM (a sprinting player closes 5 blocks before a 1.6 s eat is done): 9+ off, or 6+ out
        // of their sight, and not hit in the last second
        boolean room = (dist > 9 || dist > 6 && !sees) && (m.t - m.lastHurt > 20 || dist > 14);
        if (needHeal && m.healRun && room) { m.healRun = false; m.fleeUntil = 0; } // got its room: eats now
        if (needHeal && (room || m.eatAfterPearl && dist > 7 && m.t - m.lastHurt > 10)) { m.eatAfterPearl = false; eat(m); follow(m); return; }
        // COVER UP (normal+): close, low, food and blocks on it: boxes itself in (their side first), eats inside
        if (needHeal && dist <= 6 && tier(m) >= 2 && m.t >= m.coverReady && startCover(m, tg)) { follow(m); return; }
        // low, close, with gaps: pearl away to get room to eat (like a real player)
        if (hp < cfg("eat-below", 0.4) && food && dist <= 9 && m.pearlsLeft > 0 && m.t >= m.pearlReady && m.pots == 0) {
            m.eatAfterPearl = true; pearlOut(m, tg); m.fleeUntil = m.t + 80; m.healRun = true; follow(m); return;
        }
        // HEAL RUN: food but no blocks / pearls to make room: runs (trail of blocks, wind dash) until it has room to eat
        if (needHeal && dist <= 5 && m.fleeUntil <= m.t) { m.fleeUntil = m.t + 80; m.healRun = true; }
        // ---- escape: low with nothing left
        if (hp < 0.3 && !food && m.pots == 0 && m.fleeUntil <= m.t) {
            m.fleeUntil = m.t + 160;
            if (m.pearlsLeft > 0 && m.t >= m.pearlReady) pearlAway(m, tg);
        }
        if (m.fleeUntil > m.t) { if (!elytraEscape(m, tg) && !pillar(m)) flee(m, tg); follow(m); return; }
        // ---- far: pearl in, or chase
        // 1.2.93: its elytra to get to them (a void / a big gap between, or 32+ away): flies over and glides down beside them
        if (m.travelFly || dist > 12 && (dist > 32 || voidBetween(v.getLocation(), tg.getLocation())) && v.isOnGround() && canFly(m)) {
            if (flyTo(m, tg.getLocation())) { m.travelNote = "flying to " + tg.getName(); follow(m); return; }
        }
        if (elytraPvp(m, tg, dist, sees)) { follow(m); return; }
        if (dist > 14 && dist < 60 && m.pearlsLeft > 0 && m.t >= m.pearlReady && sees && m.t >= m.reactAt) { pearl(m, tg); follow(m); return; }
        // ---- mid: trident, crossbow
        if (ranged(m, tg, dist, sees)) { follow(m); return; }
        // ---- mid: bow
        if (m.gear.bow != null && (m.arrows > 0 || level(m.gear.bow, "infinity") > 0) && dist > 8 && dist < 22 && sees && m.t >= m.bowReady && random.nextDouble() < m.skill.bow() * 0.05) {
            m.bowReady = m.t + 80 + random.nextInt(60);
            m.drawUntil = m.t + 20 + random.nextInt(8);
            hold(m, "bow");
            Emotes.q(() -> m.body.startUsingItem(EquipmentSlot.HAND));
            follow(m);
            return;
        }
        // ---- mending its armour from its XP (backs off to do it)
        if (mend(m, dist)) { follow(m); return; }
        // ---- reading them (the advanced bits)
        if (dodge(m)) { follow(m); return; }
        if (read(m, tg, dist, sees, hp)) { follow(m); return; }
        // ---- they pillared up: it builds up after them (normal+)
        if ((m.path == null || !m.navReach) && towerUp(m, tg)) { follow(m); return; }
        if (startBomb(m, tg, dist)) { follow(m); return; }
        // ---- close: the fight
        if (m.t >= m.reactAt) melee(m, tg, dist, sees);
        follow(m);
    }

    private void busyTick(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        // 1.2.93: hit while eating (or they're right on it): stops eating (keeps the apple, like letting go of right-click)
        // and gets out: a pearl to somewhere safe, else runs
        if (m.eatUntil > m.t + 1 && tg != null && tg.getWorld() == v.getWorld()) {
            double d0 = tg.getLocation().distance(v.getLocation());
            if (m.t - m.lastHurt <= 1 && d0 < 5 || tier(m) >= 2 && d0 < 2.8) {
                if (m.eatingGod) m.gapples++; else m.apples++;
                m.eatUntil = m.t;
                Emotes.q(() -> m.body.clearActiveItem());
                hold(m, "sword");
                if (m.pearlsLeft > 0 && m.t >= m.pearlReady) { m.eatAfterPearl = true; pearlOut(m, tg); }
                m.fleeUntil = m.t + 80; m.healRun = true;
                return;
            }
        }
        if (tg != null && tg.getWorld() == v.getWorld() && (m.eatUntil > m.t || m.drawUntil > m.t || m.potUntil > m.t || m.crossUntil > m.t)) {
            // using an item slows a player to a crawl, but they keep moving: backs off and strafes while eating / drawing
            Vector away = v.getLocation().toVector().subtract(tg.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
            away.normalize();
            if (--m.strafeFlip <= 0) { m.strafeDir = -m.strafeDir; m.strafeFlip = 10 + random.nextInt(25); }
            Vector side = new Vector(-away.getZ(), 0, away.getX()).multiply(m.strafeDir);
            double d = tg.getLocation().distance(v.getLocation());
            Vector dir = (m.drawUntil > m.t && d > 10 ? side : away.multiply(d < 8 ? 0.8 : 0.2).add(side)).normalize();
            move(m, dir, 0.075, false, false);
        } else Safe.vel(v, new Vector(v.getVelocity().getX() * 0.5, v.getVelocity().getY(), v.getVelocity().getZ() * 0.5));
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
        } else if (m.crossUntil > m.t) {
            if (m.crossUntil - m.t == 1) { if (tg != null) fireCrossbow(m, tg); Emotes.q(() -> m.body.clearActiveItem()); hold(m, "sword"); }
        } else if (m.drawUntil > m.t) {
            if (m.drawUntil - m.t == 1) { if (tg != null) shoot(m, tg); Emotes.q(() -> m.body.clearActiveItem()); hold(m, "sword"); }
        }
    }

    private void eat(Mimic m) {
        boolean god = m.gapples > 0 && (m.apples == 0 || m.brain.getHealth() / maxHp(m.brain) < 0.25);
        if (god) m.gapples--; else m.apples--;
        m.eatingGod = god;
        m.eatUntil = m.t + 32 + swapTicks(m);
        hold(m, god ? "gapple" : "apple");
        Emotes.q(() -> m.body.clearActiveItem());
        Emotes.q(() -> m.body.startUsingItem(EquipmentSlot.HAND));
    }

    private void drink(Mimic m) {
        m.pots--;
        m.drinkUntil = m.t + 8 + swapTicks(m);
        hold(m, "potion");
    }

    /** Throws Healing II straight down (looks down for it: `follow` points the head at the feet). */
    private void splashSelf(Mimic m) {
        Vindicator v = m.brain;
        Location at = v.getLocation().add(0, 1.2, 0);
        ThrownPotion p = v.getWorld().spawn(at, ThrownPotion.class, e -> { e.setItem(healingPotion()); e.setShooter(v); });
        Safe.vel(p, new Vector(0, -0.5, 0));
        potions.add(p.getUniqueId());
        swing(m);
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

    boolean elytraOn() { return plugin.getConfig().getBoolean("mimic-player.elytra-pvp", false); }

    /**
     * ELYTRA PvP (it needs an elytra + rockets in their gear): takes off with a rocket to chase anyone far off or anyone
     * flying, swaps its chestplate for the elytra in the air (and back on landing: the elytra swap), steers its glide at
     * you with lead, boosts with rockets when it slows, stays above you and dives in, sprint/crit hits you as it passes
     * (a mace lands as a smash), follows you up into the air, and lands to fight on the ground once it's close.
     */
    private boolean elytraPvp(Mimic m, LivingEntity tg, double dist, boolean sees) {
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

    private void takeOff(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        m.flying = true; m.flyTicks = 0;
        m.body.getEquipment().setChestplate(new ItemStack(Material.ELYTRA)); // the elytra swap
        armourFor(m, true);
        Emotes.q(() -> v.getPathfinder().stopPathfinding());
        Safe.vel(v, new Vector(0, 0.75, 0));
        rocket(m, tg, 0.6);
    }

    private void rocket(Mimic m, LivingEntity tg, double up) {
        Vindicator v = m.brain;
        if (m.rockets <= 0) return;
        m.rockets--;
        hold(m, "rocket");
        swing(m);
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
    private void glide(Mimic m, LivingEntity tg, double dist) {
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
        if (reachOf(m, tg) <= cfg("reach", 3.0) && charge(m) >= m.skill.hitSelect() && sees(v, tg)) {
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
        m.glide = null; m.glideAt = null; m.boostTicks = 0;
        m.noFallUntil = m.t + 30;
        m.flyCooldown = m.t + 60;
        m.body.getEquipment().setChestplate(m.wear[1] == null || m.wear[1].getType() == Material.ELYTRA ? null : m.wear[1].clone()); // back to the chestplate
        armourFor(m, false);
        hold(m, "sword");
    }

    /** Armour points while flying: the chestplate is off (the elytra's on). */
    private void armourFor(Mimic m, boolean elytra) {
        double ar = 0, tough = 0, kb = 0;
        for (ItemStack it : armour(m)) {
            if (elytra && it == m.wear[1]) continue;
            double[] a = armorOf(it); ar += a[0]; tough += a[1]; kb += a[2];
        }
        attr(m.brain, Attribute.ARMOR, ar);
        attr(m.brain, Attribute.ARMOR_TOUGHNESS, tough);
        attr(m.brain, Attribute.KNOCKBACK_RESISTANCE, kb);
    }

    /** Losing with nothing left: rockets away into the sky instead of running. */
    private boolean elytraEscape(Mimic m, LivingEntity tg) {
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

    // ================================================================ 1.2.82 techs

    /** How far up the tech ladder it goes: easy 1 (the basics), normal 2, hard 3, godlike 4 (everything). */
    int tier(Mimic m) { return switch (m.skill.id()) { case "easy" -> 1; case "hard" -> 3; case "godlike" -> 4; default -> 2; }; }

    /** Wet for Impaling: in water or out in the rain. */
    static boolean wet(Entity e) {
        try { if (e.isInWaterOrRain()) return true; } catch (Throwable ignored) { }
        return e.getLocation().getBlock().getType() == Material.WATER;
    }

    // ---- POT RUN: sprint away, throw the Healing pot down on the run (it lands where it's going), then rush straight back in

    private void potRun(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        Vector away = tg == null ? v.getLocation().getDirection().setY(0) : v.getLocation().toVector().subtract(tg.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
        away.normalize().add(new Vector(-away.getZ(), 0, away.getX()).multiply(0.25 * m.strafeDir)).normalize();
        move(m, away, m.skill.speed() * 0.95, false, true);
        if (m.potRunUntil - m.t == 1) {
            m.pots--;
            m.lookDownUntil = m.t + 4;
            Location at = v.getLocation().add(0, 1.4, 0);
            ThrownPotion tp = v.getWorld().spawn(at, ThrownPotion.class, e -> { e.setItem(healingPotion()); e.setShooter(v); });
            Vector cur = v.getVelocity();
            Safe.vel(tp, new Vector(cur.getX() * 1.1, -0.45, cur.getZ() * 1.1)); // thrown down ahead of its own run: it runs into the splash
            potions.add(tp.getUniqueId());
            swing(m);
            v.getWorld().playSound(at, Sound.ENTITY_SPLASH_POTION_THROW, 1, 1);
            m.rushUntil = m.t + 30; // then straight back in, fast, no zig-zag
            m.reactAt = m.t;
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 3L);
        }
    }

    // ---- BOOM: TNT (place + light) and fire charges. NO crystal, anchor or cart PvP on Faultline: never used.

    /** A cell beside them to blow up: air with solid ground, room above, close to them, and far enough from itself. */
    private Block boomSpot(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        Block best = null; double bestScore = -1;
        Block feet = tg.getLocation().getBlock();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = -1; dy <= 0; dy++) {
            if (dx == 0 && dz == 0) continue;
            Block c = feet.getRelative(dx, dy, dz);
            if (!c.getType().isAir() || !c.getRelative(org.bukkit.block.BlockFace.UP).getType().isAir()) continue;
            if (!c.getRelative(org.bukkit.block.BlockFace.DOWN).getType().isSolid()) continue;
            Location center = c.getLocation().clone().add(0.5, 0.5, 0.5);
            double toThem = center.distance(tg.getLocation()), toMe = center.distance(v.getLocation());
            if (toThem > 2.6 || toMe < 3.6) continue; // never blows itself up
            if (toMe - toThem > bestScore) { bestScore = toMe - toThem; best = c; }
        }
        return best;
    }

    private boolean boomStart(Mimic m, LivingEntity tg, double dist, boolean sees) {
        Vindicator v = m.brain;
        if (!worldTechs() || m.boomStep > 0 || m.t < m.boomReady || !sees || dist < 3 || dist > 6.5 || !plugin.getConfig().getBoolean("mimic-player.explosives", true)) return false;
        int tr = tier(m);
        double roll = random.nextDouble();
        // TNT (normal+), lit with flint and steel or a fire charge
        if (tr >= 2 && m.tnt > 0 && (m.gear.flint || m.fireCharges > 0) && roll < 0.7 && tg.isOnGround()) {
            Block spot = boomSpot(m, tg);
            if (spot != null && place(spot, Material.TNT, 80)) {
                m.tnt--;
                hold(m, "tnt");
                swing(m);
                m.boomKind = "tnt"; m.boomAt = spot; m.boomStep = 1; m.boomWait = m.t + boomGap(m);
                return true;
            }
        }
        // fire charge at their feet (normal+)
        if (tr >= 2 && m.fireCharges > 0 && dist < 5 && tg.isOnGround() && !tg.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE) && roll < 0.8) {
            Block feet = tg.getLocation().getBlock();
            if (feet.getRelative(org.bukkit.block.BlockFace.DOWN).getType().isSolid() && place(feet, Material.FIRE, 60)) {
                m.fireCharges--;
                hold(m, "firecharge");
                swing(m);
                v.getWorld().playSound(feet.getLocation(), Sound.ITEM_FIRECHARGE_USE, 1, 1);
                m.boomReady = m.t + 60;
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 4L);
                return true;
            }
        }
        return false;
    }

    /** Ticks between placing and lighting: a godlike player does it in a blink. */
    int boomGap(Mimic m) { return switch (tier(m)) { case 4 -> 1; case 3 -> 2; default -> 4; }; }

    private boolean boomTick(Mimic m, LivingEntity tg) {
        if (m.boomStep == 0) return false;
        Vindicator v = m.brain;
        if (m.boomAt == null || m.boomAt.getWorld() != v.getWorld()) { m.boomStep = 0; return false; }
        if (m.t < m.boomWait) return true;
        Location top = m.boomAt.getLocation().clone().add(0.5, 1, 0.5), mid = m.boomAt.getLocation().clone().add(0.5, 0.5, 0.5);
        switch (m.boomKind) {
            default -> { // tnt
                if (m.boomAt.getType() != Material.TNT) { m.boomStep = 0; return false; }
                boolean charge = !m.gear.flint;
                if (charge) m.fireCharges--;
                hold(m, charge ? "firecharge" : "flint");
                swing(m);
                temp.remove(m.boomAt);
                m.boomAt.setType(Material.AIR, false);
                TNTPrimed tnt = v.getWorld().spawn(mid.clone().add(0, -0.5, 0), TNTPrimed.class, e -> { e.setFuseTicks(30); e.setSource(v); e.addScoreboardTag(TAG); });
                v.getWorld().playSound(mid, Sound.ENTITY_TNT_PRIMED, 1, 1);
                m.boomStep = 0; m.boomReady = m.t + 80;
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 4L);
            }
        }
        return true;
    }

    /** Its TNT never breaks the world (the blast still hurts). */
    @EventHandler(ignoreCancelled = true)
    public void onOurBoom(EntityExplodeEvent e) {
        if (e.getEntity().getScoreboardTags().contains(TAG)) e.blockList().clear();
    }

    // ---- SPEAR: lunge in and jab (normal+); SPEAR MACE (hard+): lunge UP, swap to the mace, smash on the way down

    private boolean spearTech(Mimic m, LivingEntity tg, double dist, boolean sees) {
        Vindicator v = m.brain;
        if (m.gear.spear == null || tier(m) < 2 || !v.isOnGround() || !sees || m.t < m.spearReady) return false;
        Vector to = tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
        if (to.lengthSquared() < 1e-4) return false;
        to.normalize();
        int lunge = Math.max(1, level(m.gear.spear, "lunge"));
        if (m.gear.mace != null && tier(m) >= 3 && dist > 2.5 && dist < 6.5 && m.t >= m.maceReady && random.nextDouble() < 0.12) { // SPEAR MACE
            hold(m, "spear");
            swing(m);
            v.getWorld().playSound(v.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 1, 1.3f);
            Safe.vel(v, to.clone().multiply(0.45 + 0.1 * lunge).setY(1.0 + 0.08 * lunge));
            m.slam = true; m.dive = false; m.stunSlam = blocking(tg) && m.gear.axe != null;
            m.maceReady = m.t + 60; m.spearReady = m.t + lungeTicks(m);
            return true;
        }
        if (dist > 3.4 && dist < 4.5 + lunge * 1.5 && charge(m) >= m.skill.hitSelect() && safePath(v, to, 4) && random.nextDouble() < 0.25) { // LUNGE
            hold(m, "spear");
            swing(m);
            v.getWorld().playSound(v.getLocation(), Sound.ITEM_TRIDENT_THROW, 0.8f, 1.4f);
            Safe.vel(v, to.clone().multiply(0.75 + 0.2 * lunge).setY(0.12));
            m.lungeUntil = m.t + 8; m.spearReady = m.t + lungeTicks(m);
            return true;
        }
        return false;
    }

    private boolean spearLungeHit(Mimic m, LivingEntity tg) {
        if (reachOf(m, tg) > spearReach()) return false; // a spear reaches further than a sword
        m.lungeUntil = 0;
        m.sprintHit = true;
        if (!whiff(m, tg)) strike(m, tg, Math.max(0.9, charge(m)));
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 4L);
        return true;
    }

    // ---- TRIDENT (throws with lead, Loyalty brings it back, Riptide launches it at you in water / rain, Channeling in storms)
    // ---- CROSSBOW (Quick Charge, Multishot, Piercing)

    private boolean ranged(Mimic m, LivingEntity tg, double dist, boolean sees) {
        Vindicator v = m.brain;
        int tr = tier(m);
        if (m.trident != null && !m.tridentOut && sees && m.t >= m.tridentReady) {
            int riptide = level(m.trident, "riptide");
            if (riptide > 0 && tr >= 3 && wet(v) && dist > 4 && dist < 22) { // RIPTIDE
                Vector to = tg.getEyeLocation().toVector().subtract(v.getLocation().toVector());
                Safe.vel(v, to.normalize().multiply(1.1 + 0.6 * riptide).add(new Vector(0, 0.2, 0)));
                hold(m, "trident");
                m.riptideUntil = m.t + 14; m.riptideHit = false; m.tridentReady = m.t + 50;
                v.getWorld().playSound(v.getLocation(), riptide >= 3 ? Sound.ITEM_TRIDENT_RIPTIDE_3 : Sound.ITEM_TRIDENT_RIPTIDE_1, 1, 1);
                return true;
            }
            if (riptide == 0 && tr >= 2 && dist > 6 && dist < 26 && random.nextDouble() < 0.05 + 0.03 * tr) { // THROW
                Location eye = eye(m);
                double d = eye.distance(tg.getLocation());
                Vector aim = tg.getLocation().add(0, 1.1, 0).toVector().add(tg.getVelocity().clone().setY(0).multiply(d / 2.5)).subtract(eye.toVector());
                aim.setY(aim.getY() + d * 0.04);
                ItemStack tri = m.trident.clone();
                hold(m, "trident");
                swing(m);
                Trident t = v.getWorld().spawn(eye.clone().add(aim.clone().normalize().multiply(0.8)), Trident.class, e -> {
                    e.setShooter(v);
                    Emotes.q(() -> e.setItemStack(tri));
                    e.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                    e.setDamage(8 + (wet(tg) ? 2.5 * level(tri, "impaling") : 0));
                    e.addScoreboardTag(TAG);
                });
                Safe.vel(t, aim.normalize().multiply(2.5));
                v.getWorld().playSound(eye, Sound.ITEM_TRIDENT_THROW, 1, 1);
                m.tridentOut = true; m.tridentReady = m.t + 40;
                int loyalty = level(tri, "loyalty");
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (t.isValid()) t.remove();
                    if (!byBrain.containsKey(v.getUniqueId())) return;
                    if (loyalty > 0) { m.tridentOut = false; v.getWorld().playSound(v.getLocation(), Sound.ITEM_TRIDENT_RETURN, 1, 1); }
                    else { m.trident = null; m.tridentOut = false; } // no Loyalty: that one's gone
                    hold(m, "sword");
                }, loyalty > 0 ? Math.max(14, 34 - 6 * loyalty) : 30L);
                return true;
            }
        }
        if (m.gear.crossbow != null && tr >= 2 && m.arrows > 0 && dist > 6 && dist < 24 && sees && m.t >= m.crossReady && random.nextDouble() < 0.04 + 0.02 * tr) {
            m.crossUntil = m.t + Math.max(5, 25 - 5 * level(m.gear.crossbow, "quick_charge"));
            m.crossReady = m.t + 60 + random.nextInt(40);
            hold(m, "crossbow");
            Emotes.q(() -> m.body.startUsingItem(EquipmentSlot.HAND));
            return true;
        }
        return false;
    }

    private void fireCrossbow(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        ItemStack cb = m.gear.crossbow;
        Location eye = eye(m);
        double d = eye.distance(tg.getLocation());
        Vector aim = tg.getLocation().add(0, 1.1, 0).toVector().add(tg.getVelocity().clone().setY(0).multiply(d / 3.1)).subtract(eye.toVector());
        aim.setY(aim.getY() + d * 0.04);
        int multi = level(cb, "multishot") > 0 ? 3 : 1, pierce = level(cb, "piercing");
        m.arrows--;
        for (int i = 0; i < multi; i++) {
            double ang = Math.toRadians((i - (multi - 1) / 2.0) * 10), c = Math.cos(ang), sn = Math.sin(ang);
            Vector dir = new Vector(aim.getX() * c - aim.getZ() * sn, aim.getY(), aim.getX() * sn + aim.getZ() * c).normalize();
            Arrow a = v.getWorld().spawnArrow(eye.clone().add(dir.clone().multiply(0.8)), dir, 3.15f, (float) m.skill.spread());
            a.setShooter(v);
            a.setDamage(2.5);
            a.setPierceLevel(pierce);
            if (i != (multi - 1) / 2) a.setPickupStatus(AbstractArrow.PickupStatus.CREATIVE_ONLY);
        }
        v.getWorld().playSound(eye, Sound.ITEM_CROSSBOW_SHOOT, 1, 1);
    }

    private boolean riptideTick(Mimic m, LivingEntity tg) {
        if (m.riptideUntil <= m.t) return false;
        Vindicator v = m.brain;
        if (!m.riptideHit && v.getLocation().distance(tg.getLocation()) < 2.2) {
            m.riptideHit = true;
            double dmg = (8 + (wet(tg) ? 2.5 * level(m.trident, "impaling") : 0)) * cfg("damage-multiplier", 1.0);
            striking = true;
            try { tg.damage(dmg, v); } finally { striking = false; }
        combat(tg);
            Vector push = tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
            if (push.lengthSquared() > 1e-4) Safe.vel(tg, tg.getVelocity().add(push.normalize().multiply(0.6).setY(0.2)));
            if (level(m.trident, "channeling") > 0 && v.getWorld().isThundering()) {
                Emotes.q(() -> tg.getWorld().strikeLightningEffect(tg.getLocation()));
                striking = true;
                try { tg.damage(5, v); } finally { striking = false; }
                combat(tg);
            }
        }
        return true;
    }

    // ---- DIGGING: walled off from you, it mines through (pickaxe for stone, axe for wood, shovel for dirt), at real speed

    static double toolSpeed(ItemStack it) {
        if (it == null) return 0;
        String n = it.getType().name();
        double s = n.startsWith("NETHERITE") ? 9 : n.startsWith("DIAMOND") ? 8 : n.startsWith("IRON") ? 6 : n.startsWith("GOLDEN") ? 12 : n.startsWith("STONE") ? 4 : n.startsWith("WOODEN") ? 2 : 4;
        int eff = level(it, "efficiency");
        return s + (eff > 0 ? eff * eff + 1 : 0);
    }

    /**
     * 1.2.93: ticks to break this block RIGHT NOW, by vanilla's rule: speed = the right tool's (with Efficiency) else 1,
     * x Haste / Mining Fatigue, /5 in water without Aqua Affinity, /5 off the ground; damage a tick = speed / hardness / 30
     * (or /100 when the block needs a tool it hasn't got); -1 = it can't (no tool for stone, too hard).
     */
    int breakTicks(Mimic m, Block b) { return breakTicks(m, b, false); }

    /** `planning` = as if standing on the ground (the planner's view of a dig later on). */
    int breakTicks(Mimic m, Block b, boolean planning) {
        float h = b.getType().getHardness();
        if (h < 0) return -1;
        if (h == 0) return 1;
        ItemStack tool = toolFor(m, b);
        boolean needsTool = Tag.MINEABLE_PICKAXE.isTagged(b.getType()) || b.getType() == Material.COBWEB;
        if (tool == null && needsTool) return -1;
        double speed = tool != null ? toolSpeed(tool) : 1;
        Vindicator v = m.brain;
        PotionEffect haste = v.getPotionEffect(PotionEffectType.HASTE), fatigue = v.getPotionEffect(PotionEffectType.MINING_FATIGUE);
        if (haste != null) speed *= 1 + 0.2 * (haste.getAmplifier() + 1);
        if (fatigue != null) speed *= Math.pow(0.3, Math.min(4, fatigue.getAmplifier() + 1));
        if (v.isInWater() && level(m.wear[0], "aqua_affinity") == 0) speed /= 5;
        if (!planning && !v.isOnGround() && !v.isInWater() && !onClimbable(v)) speed /= 5;
        double perTick = speed / h / 30.0;
        if (perTick >= 1) return 1;
        return (int) Math.ceil(1 / perTick);
    }

    static boolean onClimbable(LivingEntity v) { return Tag.CLIMBABLE.isTagged(v.getLocation().getBlock().getType()); }

    /** Lava or water touching this block (mining it would let it in). */
    static boolean liquidNext(Block b) {
        for (org.bukkit.block.BlockFace f : new org.bukkit.block.BlockFace[]{org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST, org.bukkit.block.BlockFace.DOWN}) {
            int y = b.getY() + f.getModY();
            if (y < b.getWorld().getMinHeight() || y >= b.getWorld().getMaxHeight()) continue;
            Material t = b.getRelative(f).getType();
            if (t == Material.LAVA || t == Material.WATER) return true;
        }
        return false;
    }

    /** The right tool it carries for this block, or null. */
    ItemStack toolFor(Mimic m, Block b) {
        if (Tag.MINEABLE_PICKAXE.isTagged(b.getType())) return m.gear.pickaxe;
        if (Tag.MINEABLE_AXE.isTagged(b.getType())) return m.gear.axe;
        if (Tag.MINEABLE_SHOVEL.isTagged(b.getType())) return m.gear.shovel;
        return null;
    }

    private boolean startDig(Mimic m, LivingEntity tg, Block ahead) {
        if (!worldTechs() || !plugin.getConfig().getBoolean("mimic-player.dig", true)) return false;
        Block head = ahead.getRelative(org.bukkit.block.BlockFace.UP);
        Block b = head.getType().isSolid() ? head : ahead.getType().isSolid() ? ahead : null;
        if (b == null || !diggable(b)) return false;
        ItemStack tool = toolFor(m, b);
        if (tool == null || liquidNext(b) || breakTicks(m, b) < 0) return false;
        m.digBlock = b;
        m.digTicks = 0; m.digProgress = 0;
        m.digNeed = breakTicks(m, b);
        hold(m, tool == m.gear.pickaxe ? "pickaxe" : tool == m.gear.axe ? "axe" : "shovel");
        return true;
    }

    static boolean diggable(Block b) {
        Material t = b.getType();
        float h = t.getHardness();
        if (h < 0 || h > 10) return false; // bedrock, obsidian, reinforced deepslate...
        if (b.getState() instanceof org.bukkit.block.TileState) return false; // never a chest, furnace, sign, spawner...
        if (b.getBlockData() instanceof org.bukkit.block.data.Openable) return false; // doors / trapdoors / gates get OPENED
        return t.isSolid();
    }

    private boolean digTick(Mimic m) {
        Block b = m.digBlock;
        Vindicator v = m.brain;
        // 1.2.93: a player's reach (4.5 from the eyes, not 3.5 from the feet: it kept giving up on the block over its head)
        if (!b.getType().isSolid() || b.getWorld() != v.getWorld() || b.getLocation().clone().add(0.5, 0.5, 0.5).distance(v.getEyeLocation()) > 4.6) {
            crack(m, b, 0); m.digBlock = null; return false;
        }
        if (liquidNext(b)) { crack(m, b, 0); m.digBlock = null; m.path = null; return false; } // lava / water showed up beside it: leaves it
        m.digTicks++;
        m.navProgressAt = m.t;
        int now = breakTicks(m, b); // its speed this tick (it fell, got in water, got Haste...)
        if (now < 0) { crack(m, b, 0); m.digBlock = null; return false; }
        m.digProgress += 1.0 / now;
        Safe.vel(v, new Vector(0, v.getVelocity().getY(), 0)); // stands still to mine, like a player holding the button
        if (m.digTicks % 4 == 1) {
            swing(m);
            Emotes.q(() -> b.getWorld().playSound(b.getLocation(), b.getBlockData().getSoundGroup().getHitSound(), 0.6f, 0.8f));
            b.getWorld().spawnParticle(Particle.BLOCK, b.getLocation().clone().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3, b.getBlockData());
        }
        crack(m, b, (float) Math.min(0.99, m.digProgress));
        if (m.digProgress >= 1) {
            crack(m, b, 0);
            mine(b);
            m.digBlock = null;
            m.chaseStuck = 0;
            // the feet block too, if that's in the way
            Block feet = b.getRelative(org.bukkit.block.BlockFace.DOWN);
            Block at = v.getLocation().getBlock();
            if (feet.getY() == at.getY() && feet.getType().isSolid() && diggable(feet) && !liquidNext(feet) && breakTicks(m, feet) > 0) {
                m.digBlock = feet; m.digTicks = 0; m.digProgress = 0; m.digNeed = breakTicks(m, feet);
            } else hold(m, "sword");
        }
        return true;
    }

    /** The crack on a block it's mining, shown to everyone near (0 = none). */
    private void crack(Mimic m, Block b, float progress) {
        if (b == null) return;
        Location l = b.getLocation();
        for (Player p : b.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(l) > 48 * 48) continue;
            try { p.sendBlockDamage(l, progress, m.brain.getEntityId()); } catch (RuntimeException | LinkageError ignored) { }
        }
    }

    /** Mined: gone like a player broke it (no drops), put back after `dig-restore-seconds` (60; 0 = left mined). */
    void mine(Block b) {
        MimicNav.dirty(b.getWorld());
        Emotes.q(() -> b.getWorld().playSound(b.getLocation(), b.getBlockData().getSoundGroup().getBreakSound(), 1, 1));
        b.getWorld().spawnParticle(Particle.BLOCK, b.getLocation().clone().add(0.5, 0.5, 0.5), 20, 0.3, 0.3, 0.3, b.getBlockData());
        int secs = plugin.getConfig().getInt("mimic-player.dig-restore-seconds", 60);
        if (secs > 0 && !temp.containsKey(b)) {
            TempBlock t = new TempBlock();
            t.block = b; t.before = b.getBlockData().clone(); t.placed = Material.AIR; t.until = clock + secs * 20;
            temp.put(b, t);
        } else temp.remove(b);
        b.setType(Material.AIR, false);
    }

    // ---- reading the other player

    /**
     * What a good player reads off their opponent: EATING / drinking = rush in and punish it (normal+); DRAWING A BOW at it =
     * shield up (hard+, with a shield) or a zig-zag in; they're LOW and it isn't = all in (no spacing); PRE-GAP (hard+): its
     * absorption's gone, it's hurt and they're out of reach = a gap now, before it needs one; PEARL FOLLOW (hard+): they
     * pearled away = it pearls after them; being COMBOED hard (godlike) = pearls out.
     */
    private boolean read(Mimic m, LivingEntity tg, double dist, boolean sees, double hp) {
        Vindicator v = m.brain;
        int tr = tier(m);
        ItemStack using = null;
        try { using = usingNow(tg); } catch (Throwable ignored) { }
        Material u = using == null ? Material.AIR : using.getType();
        // they're eating / drinking: punish it
        if (tr >= 2 && (u.isEdible() || u == Material.POTION) && dist < 14) { m.rushUntil = Math.max(m.rushUntil, m.t + 15); m.reactAt = Math.min(m.reactAt, m.t); }
        // they're drawing a bow / crossbow at it
        if (tr >= 2 && (u == Material.BOW || u == Material.CROSSBOW) && dist > 4 && sees) {
            if (tr >= 3 && hasShield(m) && m.shieldOffUntil <= m.t && m.blockUntil <= m.t) {
                m.blockUntil = m.t + 12;
                Emotes.q(() -> m.body.startUsingItem(EquipmentSlot.OFF_HAND));
            }
        }
        // they're low and it isn't: all in
        double theirs = tg.getHealth() / Math.max(1, tg.getAttribute(Attribute.MAX_HEALTH).getValue());
        if (tr >= 2 && theirs < 0.35 && hp > 0.5) m.rushUntil = Math.max(m.rushUntil, m.t + 10);
        // pre-gap
        if (tr >= 3 && m.apples > 0 && hp < 0.75 && v.getAbsorptionAmount() <= 0 && dist > 5 && m.t - m.lastHurt > 10 && m.rushUntil <= m.t) { eat(m); return true; }
        // pearl after them
        if (followPearl(m, tg)) return true;
        // comboed hard and hurt: pearl out (godlike)
        if (tr >= 4 && m.comboTaken >= 4 && hp < 0.5 && m.pearlsLeft > 0 && m.t >= m.pearlReady) { m.comboTaken = 0; pearlAway(m, tg); return true; }
        return false;
    }

    /**
     * CUT THEM OFF (normal+): it runs to where they're GOING (their tracked movement x the time to get there), not where they
     * are, so a player running away gets caught and one running sideways gets met. Half the lead on normal.
     */
    private Vector intercept(Mimic m, LivingEntity tg, Vector to, double dist) {
        int tr = tier(m);
        if (tr < 2 || m.tgVel.lengthSquared() < 0.0025) return to;
        double ticks = Math.min(20, dist / Math.max(0.2, m.skill.speed() * 0.9)) * (tr == 2 ? 0.5 : 1);
        Vector aim = tg.getLocation().toVector().add(m.tgVel.clone().multiply(ticks)).subtract(m.brain.getLocation().toVector()).setY(0);
        return aim.lengthSquared() > 1e-4 ? aim.normalize() : to;
    }

    // ---- CRITS: only when a player could crit (falling; NOT in a web, in water, on a ladder / vine, blind, riding, sprinting)

    boolean inWeb(Mimic m) {
        for (Block b : underNow(m.brain.getLocation()))
            if (b.getType() == Material.COBWEB || b.getRelative(org.bukkit.block.BlockFace.UP).getType() == Material.COBWEB) return true;
        return false;
    }

    boolean canCrit(Mimic m) {
        Vindicator v = m.brain;
        if (v.isOnGround() || v.getVelocity().getY() >= -0.05) return false;
        if (inWeb(m) || v.isInWater() || v.isInsideVehicle() || v.hasPotionEffect(PotionEffectType.BLINDNESS)) return false;
        return !Tag.CLIMBABLE.isTagged(v.getLocation().getBlock().getType());
    }

    // ---- ARMOUR WEAR: its armour loses durability like a player's (damage / 4 a piece, Unbreaking), breaks at 0;
    // MENDING pieces repair from the copied player's XP points (2 durability a point) and XP bottles (thrown at its feet)

    static final Set<String> WEARS = Set.of("ENTITY_ATTACK", "ENTITY_SWEEP_ATTACK", "PROJECTILE", "BLOCK_EXPLOSION", "ENTITY_EXPLOSION",
            "CONTACT", "FIRE", "LAVA", "HOT_FLOOR", "THORNS", "FALLING_BLOCK", "LIGHTNING", "CAMPFIRE");

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWear(EntityDamageEvent e) {
        Mimic m = byBrain.get(e.getEntity().getUniqueId());
        if (m == null || !WEARS.contains(e.getCause().name())) return;
        int dmg = Math.max(1, (int) (e.getDamage() / 4));
        for (int i = 0; i < 4; i++) wearDown(m, i, dmg);
    }

    void wearDown(Mimic m, int slot, int amount) {
        ItemStack it = m.wear[slot];
        if (it == null || it.getType().getMaxDurability() <= 0 || !(it.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dm) || dm.isUnbreakable()) return;
        int unb = level(it, "unbreaking"), taken = 0;
        for (int k = 0; k < amount; k++) if (random.nextDouble() < 0.6 + 0.4 / (unb + 1)) taken++;
        if (taken == 0) return;
        int now = dm.getDamage() + taken;
        if (now >= it.getType().getMaxDurability()) { // broken
            m.wear[slot] = null;
            setArmourSlot(m, slot, null);
            m.brain.getWorld().playSound(m.brain.getLocation(), Sound.ENTITY_ITEM_BREAK, 1, 1);
            m.brain.getWorld().spawnParticle(Particle.ITEM, m.brain.getLocation().add(0, 1.2, 0), 12, 0.3, 0.4, 0.3, 0.05, it);
            armourFor(m, m.flying);
            return;
        }
        dm.setDamage(now);
        it.setItemMeta(dm);
        setArmourSlot(m, slot, it);
    }

    void setArmourSlot(Mimic m, int slot, ItemStack it) {
        EntityEquipment eq = m.body.getEquipment();
        ItemStack c = it == null ? null : it.clone();
        switch (slot) {
            case 0 -> eq.setHelmet(c);
            case 1 -> { if (!m.flying) eq.setChestplate(c); } // the elytra's showing while it flies
            case 2 -> eq.setLeggings(c);
            default -> eq.setBoots(c);
        }
    }

    /** Every 10 ticks: Mending pieces that are worn get repaired from its XP (out of melee range: it backs off to mend). */
    private boolean mend(Mimic m, double dist) {
        if (m.t < m.mendReady || (m.xpPool <= 0 && m.xpBottles <= 0) || dist < 5) return false;
        List<Integer> hurt = new ArrayList<>();
        int missing = 0;
        for (int i = 0; i < 4; i++) {
            ItemStack it = m.wear[i];
            if (it == null || level(it, "mending") <= 0 || !(it.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dm) || dm.getDamage() <= 0) continue;
            hurt.add(i); missing += dm.getDamage();
        }
        if (hurt.isEmpty()) return false;
        m.mendReady = m.t + 10;
        Vindicator v = m.brain;
        int points;
        if (m.xpPool > 0) { // its XP points
            points = Math.min(m.xpPool, Math.min(6, (missing + 1) / 2));
            m.xpPool -= points;
            v.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, v.getLocation().add(0, 1, 0), 4, 0.3, 0.5, 0.3, 0);
        } else { // an XP bottle at its feet
            m.xpBottles--;
            points = 3 + random.nextInt(9);
            m.lookDownUntil = m.t + 5;
            m.body.getEquipment().setItemInMainHand(new ItemStack(Material.EXPERIENCE_BOTTLE)); m.held = "?";
            swing(m);
            v.getWorld().playSound(v.getLocation(), Sound.ENTITY_EXPERIENCE_BOTTLE_THROW, 1, 1);
            v.getWorld().spawnParticle(Particle.EFFECT, v.getLocation().add(0, 0.2, 0), 20, 0.5, 0.1, 0.5, 0);
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 5L);
        }
        v.getWorld().playSound(v.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
        int repair = points * 2;
        while (repair > 0 && !hurt.isEmpty()) { // like Mending: a random damaged mending piece each time
            int slot = hurt.get(random.nextInt(hurt.size()));
            ItemStack it = m.wear[slot];
            var dm = (org.bukkit.inventory.meta.Damageable) it.getItemMeta();
            int fix = Math.min(repair, dm.getDamage());
            dm.setDamage(dm.getDamage() - fix);
            it.setItemMeta(dm);
            setArmourSlot(m, slot, it);
            repair -= fix;
            if (dm.getDamage() <= 0) hurt.remove((Integer) slot);
        }
        return true;
    }

    /** In combat (DonutCombat's tag when it's there, else the built-in one), every time it hurts you or you hurt it. */
    void combat(Player p) { try { if (plugin.combatTag != null) plugin.combatTag.tag(p); } catch (RuntimeException ignored) { } }

    // ---- ARM SWINGS: a mannequin never plays the arm-swing animation (the game only plays it back for mobs and players), so
    // every M1 / mining chop is drawn with item-use poses on a copy of what it holds: arm UP (trident pose) for 2 ticks, then
    // THRUST forward (spear pose) for 2, then back. The swing packet is still sent too.

    static final NamespacedKey POSE_KEY = new NamespacedKey("faultlineitems", "mimic_pose");

    void swing(Mimic m) {
        Emotes.q(() -> m.body.swingMainHand());
        if (MimicBody.active(plugin)) { MimicBody.swing(plugin, m, false); return; } // a real player swing (1.2.95), no pose tricks
        if (!plugin.getConfig().getBoolean("mimic-player.arm-animations", true)) return;
        if (usingSomething(m)) return;
        ItemStack held = m.body.getEquipment().getItemInMainHand();
        if (held != null && held.getPersistentDataContainer().has(POSE_KEY)) held = unposed(m);
        ItemStack base = held == null || held.getType().isAir() ? null : held.clone();
        int id = ++m.swingId;
        m.poseUntil = m.t + 9;
        // the pose item goes in hand first; it starts "using" it a tick later, once the client has the item (the use flag
        // arriving before the item = the client uses the OLD item, no pose: why the swings didn't show)
        if (!pose(m, base, io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation.TRIDENT)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (m.swingId != id || !m.body.isValid() || usingSomething(m)) return;
            Emotes.q(() -> m.body.startUsingItem(EquipmentSlot.HAND)); // arm up
        }, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (m.swingId != id || !m.body.isValid() || usingSomething(m)) return;
            Emotes.q(() -> m.body.clearActiveItem());
            pose(m, base, io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation.SPEAR);
        }, 3L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (m.swingId != id || !m.body.isValid() || usingSomething(m)) return;
            Emotes.q(() -> m.body.startUsingItem(EquipmentSlot.HAND)); // and the thrust down
        }, 4L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (m.swingId != id || !m.body.isValid() || usingSomething(m)) return;
            Emotes.q(() -> m.body.clearActiveItem());
            ItemStack now = m.body.getEquipment().getItemInMainHand();
            if (now != null && now.getPersistentDataContainer().has(POSE_KEY)) m.body.getEquipment().setItemInMainHand(base);
        }, 7L);
    }

    /** Eating, drinking, drawing, loading or shielding: those are their own arm poses, a swing pose would cut them off. */
    boolean usingSomething(Mimic m) {
        return m.eatUntil > m.t || m.drinkUntil > m.t || m.potUntil > m.t || m.drawUntil > m.t || m.crossUntil > m.t
                || m.blockUntil > m.t && m.shieldOffUntil <= m.t;
    }

    /** What it held before a swing pose (the pose copy minus its marker), or what hold() says it holds. */
    ItemStack unposed(Mimic m) { return hotbar(m, m.held); }

    private boolean pose(Mimic m, ItemStack base, io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation anim) {
        try {
            ItemStack it;
            if (base == null) { // empty hand: an invisible item
                it = new ItemStack(Material.PAPER);
                ItemMeta im = it.getItemMeta();
                im.setItemModel(new NamespacedKey("faultline", "gui/blank"));
                it.setItemMeta(im);
            } else it = base.clone();
            it.editPersistentDataContainer(pdc -> pdc.set(POSE_KEY, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1));
            it.setData(io.papermc.paper.datacomponent.DataComponentTypes.CONSUMABLE, io.papermc.paper.datacomponent.item.Consumable.consumable()
                    .consumeSeconds(3600).animation(anim).sound(net.kyori.adventure.key.Key.key("minecraft:intentionally_empty")).hasConsumeParticles(false).build());
            m.body.getEquipment().setItemInMainHand(it);
            return true;
        } catch (RuntimeException | LinkageError e) { return false; }
    }

    // ---- DOORS: wooden doors, trapdoors and fence gates in its way are opened like a player would (right-click, arm swing,
    // the sound), and shut again behind it a couple of seconds later. Iron ones need redstone: left alone.

    final Map<Block, Integer> opened = new HashMap<>();

    /** The nearest door / fence gate within 8 blocks it can use to get at them (wooden: iron ones need redstone). */
    Block findDoor(Mimic m, LivingEntity tg) {
        if (!plugin.getConfig().getBoolean("mimic-player.doors", true)) return null;
        Location at = m.brain.getLocation();
        Block feet = at.getBlock(), best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dx = -8; dx <= 8; dx++) for (int dz = -8; dz <= 8; dz++) for (int dy = -1; dy <= 1; dy++) {
            Block b = feet.getRelative(dx, dy, dz);
            Material t = b.getType();
            String n = t.name();
            if (!(n.endsWith("_DOOR") || n.endsWith("_FENCE_GATE")) || t == Material.IRON_DOOR) continue;
            if (b.getBlockData() instanceof org.bukkit.block.data.type.Door d && d.getHalf() == org.bukkit.block.data.Bisected.Half.TOP) continue;
            double sc = b.getLocation().clone().add(0.5, 0, 0.5).distance(at) + b.getLocation().clone().add(0.5, 0, 0.5).distance(tg.getLocation());
            if (sc < bestScore) { bestScore = sc; best = b; }
        }
        return best;
    }

    /** Solid in the way (an OPEN door / trapdoor / gate isn't: you walk through). */
    static boolean blocks(Block b) {
        if (b.getBlockData() instanceof org.bukkit.block.data.Openable o && o.isOpen()) return false;
        return b.getType().isSolid();
    }

    static boolean openable(Block b) {
        if (!(b.getBlockData() instanceof org.bukkit.block.data.Openable o) || o.isOpen()) return false;
        Material t = b.getType();
        return t != Material.IRON_DOOR && t != Material.IRON_TRAPDOOR;
    }

    private boolean openAhead(Mimic m, Vector dir) {
        if (!plugin.getConfig().getBoolean("mimic-player.doors", true)) return false;
        Vindicator v = m.brain;
        Vector h = new Vector(dir.getX(), 0, dir.getZ());
        if (h.lengthSquared() < 1e-4) return false;
        h.normalize();
        for (double d : new double[]{0.6, 1.2}) {
            Block feet = v.getLocation().clone().add(h.getX() * d, 0.01, h.getZ() * d).getBlock();
            for (Block b : new Block[]{feet, feet.getRelative(org.bukkit.block.BlockFace.UP), feet.getRelative(org.bukkit.block.BlockFace.DOWN)}) {
                if (b == feet.getRelative(org.bukkit.block.BlockFace.DOWN) && !(b.getBlockData() instanceof org.bukkit.block.data.type.TrapDoor)) continue;
                if (!openable(b)) continue;
                setOpen(b, true);
                swing(m);
                Vector look = b.getLocation().clone().add(0.5, 0.5, 0.5).toVector().subtract(v.getEyeLocation().toVector());
                m.yaw = (float) Math.toDegrees(Math.atan2(-look.getX(), look.getZ()));
                opened.put(b, clock + 50);
                Safe.vel(v, new Vector(0, v.getVelocity().getY(), 0)); // a beat to open it
                return true;
            }
        }
        return false;
    }

    void setOpen(Block b, boolean open) {
        if (!(b.getBlockData() instanceof org.bukkit.block.data.Openable o)) return;
        o.setOpen(open);
        b.setBlockData(o, false);
        MimicNav.dirty(b.getWorld());
        if (o instanceof org.bukkit.block.data.type.Door d) { // the other half of a door too
            Block other = d.getHalf() == org.bukkit.block.data.Bisected.Half.BOTTOM ? b.getRelative(org.bukkit.block.BlockFace.UP) : b.getRelative(org.bukkit.block.BlockFace.DOWN);
            if (other.getBlockData() instanceof org.bukkit.block.data.type.Door od) { od.setOpen(open); other.setBlockData(od, false); }
        }
        String n = b.getType().name();
        Sound snd = n.endsWith("_TRAPDOOR") ? (open ? Sound.BLOCK_WOODEN_TRAPDOOR_OPEN : Sound.BLOCK_WOODEN_TRAPDOOR_CLOSE)
                : n.endsWith("_FENCE_GATE") ? (open ? Sound.BLOCK_FENCE_GATE_OPEN : Sound.BLOCK_FENCE_GATE_CLOSE)
                : (open ? Sound.BLOCK_WOODEN_DOOR_OPEN : Sound.BLOCK_WOODEN_DOOR_CLOSE);
        b.getWorld().playSound(b.getLocation().clone().add(0.5, 0.5, 0.5), snd, 1, 1);
    }

    /** Shuts the doors it opened once it's through (nobody standing in them). */
    void tickDoors() {
        if (opened.isEmpty()) return;
        for (Iterator<Map.Entry<Block, Integer>> it = opened.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (clock < e.getValue()) continue;
            Block b = e.getKey();
            it.remove();
            if (!(b.getBlockData() instanceof org.bukkit.block.data.Openable o) || !o.isOpen()) continue;
            boolean busy = false;
            for (Entity en : b.getWorld().getNearbyEntities(b.getLocation().clone().add(0.5, 0.5, 0.5), 0.9, 1.2, 0.9)) if (en instanceof LivingEntity) { busy = true; break; }
            if (busy) { opened.put(b, clock + 20); return; } // someone's in it: later
            setOpen(b, false);
        }
    }

    // ---- PROJECTILE DODGING (hard+; with a shield normal+): arrows / tridents / pearls... their shots coming at it

    final Map<UUID, Projectile> incoming = new HashMap<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShot(org.bukkit.event.entity.ProjectileLaunchEvent e) {
        if (byBrain.isEmpty() || !(e.getEntity().getShooter() instanceof LivingEntity p) || !(p instanceof Player || byBrain.containsKey(p.getUniqueId()))) return;
        if (e.getEntity() instanceof EnderPearl) return;
        for (Mimic m : byBrain.values()) if (m.target == p) { incoming.put(e.getEntity().getUniqueId(), e.getEntity()); return; }
    }

    private boolean dodge(Mimic m) {
        if (incoming.isEmpty() || tier(m) < 2 || m.t < m.dodgeReady) return false;
        Vindicator v = m.brain;
        incoming.values().removeIf(pr -> !pr.isValid() || pr.isOnGround() || pr.getTicksLived() > 100);
        Vector me = v.getLocation().toVector().add(new Vector(0, 1, 0));
        for (Projectile pr : incoming.values()) {
            if (pr.getWorld() != v.getWorld()) continue;
            Vector p = pr.getLocation().toVector().subtract(me), u = pr.getVelocity();
            double uu = u.lengthSquared();
            if (uu < 0.04) continue;
            double tc = -p.dot(u) / uu;
            if (tc < 0 || tc > 14) continue;
            Vector miss = p.clone().add(u.clone().multiply(tc));
            if (miss.length() > 1.4) continue; // it'll miss anyway
            m.dodgeReady = m.t + 10;
            if (m.wind > 0 && tier(m) >= 3 && tc > 2 && m.t >= m.cancelReady) { // WIND CHARGE BLOCK: a charge into its path knocks it away
                m.wind--; m.cancelReady = m.t + 30;
                hold(m, "wind"); swing(m);
                Safe.vel(pr, u.clone().multiply(-0.5).add(new Vector(0, 0.3, 0)));
                pr.getWorld().spawnParticle(Particle.GUST_EMITTER_SMALL, pr.getLocation(), 1);
                pr.getWorld().playSound(pr.getLocation(), Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1, 1);
                incoming.values().remove(pr);
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 4L);
                return true;
            }
            if (hasShield(m) && tc > 2 && m.shieldOffUntil <= m.t) { // shield it
                m.blockUntil = m.t + 10;
                Emotes.q(() -> m.body.startUsingItem(EquipmentSlot.OFF_HAND));
                return false; // keeps doing whatever it was, shield up
            }
            if (tier(m) < 3) return false;
            Vector side = new Vector(miss.getX(), 0, miss.getZ());
            if (side.lengthSquared() < 0.01) side = new Vector(-u.getZ(), 0, u.getX()); // dead on: either side
            side.normalize();
            if (!safeStep(v, side)) side.multiply(-1);
            if (!safeStep(v, side)) return false;
            if (!v.isOnGround()) { // mid-jump: air-strafe out of the line (a player's air control)
                Vector cur = v.getVelocity();
                Safe.vel(v, new Vector(cur.getX() * 0.6 + side.getX() * 0.25, cur.getY(), cur.getZ() * 0.6 + side.getZ() * 0.25));
                return true;
            }
            walk(v, side.multiply(0.42).setY(v.getVelocity().getY()), 3); // a sidestep out of its line
            return true;
        }
        return false;
    }

    // ---- TOTEM SWAP (hard+): low with a shield in the off hand and a totem to spare = totem in the off hand (no shield then)

    private void totemSwap(Mimic m, double hp) {
        if (tier(m) < 3 || m.totems <= 0 || m.gear.offhand == null || m.gear.offhand.getType() != Material.SHIELD) return;
        if (!m.totemHand && hp < 0.35) {
            m.totemHand = true; m.blockUntil = 0;
            Emotes.q(() -> m.body.clearActiveItem());
            m.body.getEquipment().setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
        } else if (m.totemHand && hp > 0.65) {
            m.totemHand = false;
            m.body.getEquipment().setItemInOffHand(m.gear.offhand.clone());
        }
    }

    // ---- MEMORY: what each player tends to do (shield, bow, eat), kept across fights (mimic_memory.yml)

    static final class Profile {
        long ticks, shield, bow, eat;
        double shieldRate() { return ticks < 100 ? 0 : shield / (double) ticks; }
        double bowRate() { return ticks < 100 ? 0 : bow / (double) ticks; }
    }

    final Map<UUID, Profile> memory = new HashMap<>();

    Profile profile(LivingEntity p) { return memory.computeIfAbsent(p.getUniqueId(), k -> new Profile()); }

    private void learn(Mimic m, LivingEntity tg) {
        if (!(tg instanceof Player) || tg.getLocation().distanceSquared(m.brain.getLocation()) > 30 * 30) return; // only real players are remembered
        Profile pr = profile(tg);
        pr.ticks++;
        if (blocking(tg)) pr.shield++;
        try {
            if (tg.isHandRaised()) {
                Material u = tg.getActiveItem().getType();
                if (u == Material.BOW || u == Material.CROSSBOW) pr.bow++;
                else if (u.isEdible()) pr.eat++;
            }
        } catch (Throwable ignored) { }
    }

    void saveMemory() {
        var y = new org.bukkit.configuration.file.YamlConfiguration();
        for (var e : memory.entrySet()) {
            String k = e.getKey() + ".";
            y.set(k + "ticks", e.getValue().ticks); y.set(k + "shield", e.getValue().shield); y.set(k + "bow", e.getValue().bow); y.set(k + "eat", e.getValue().eat);
        }
        try { y.save(new java.io.File(plugin.getDataFolder(), "mimic_memory.yml")); } catch (java.io.IOException ignored) { }
    }

    void loadMemory() {
        java.io.File f = new java.io.File(plugin.getDataFolder(), "mimic_memory.yml");
        if (!f.exists()) return;
        var y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
        for (String k : y.getKeys(false)) {
            try {
                Profile pr = new Profile();
                pr.ticks = y.getLong(k + ".ticks"); pr.shield = y.getLong(k + ".shield"); pr.bow = y.getLong(k + ".bow"); pr.eat = y.getLong(k + ".eat");
                memory.put(UUID.fromString(k), pr);
            } catch (RuntimeException ignored) { }
        }
    }

    /** Several mimics on one player come in from different sides (a flank), not in a line behind each other. */
    private Vector flank(Mimic m, LivingEntity tg, Vector to, double dist) {
        if (tier(m) < 2 || dist < 2.5 || dist > 16) return to;
        int idx = 0, n = 0;
        for (Mimic o : byBrain.values()) {
            if (o.target != tg) continue;
            if (o == m) idx = n;
            n++;
        }
        if (n < 2 || idx == 0) return to;
        double deg = (idx % 2 == 1 ? 1 : -1) * Math.min(75, 40 * ((idx + 1) / 2)) * Math.min(1, (dist - 2.5) / 6);
        double r = Math.toRadians(deg), c = Math.cos(r), sn = Math.sin(r);
        return new Vector(to.getX() * c - to.getZ() * sn, 0, to.getX() * sn + to.getZ() * c);
    }

    /** Its target pearled: it notes where they landed and (hard+) pearls after them a moment later. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTargetPearl(org.bukkit.event.player.PlayerTeleportEvent e) {
        if (e.getCause() != org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.ENDER_PEARL || byBrain.isEmpty()) return;
        for (Mimic m : byBrain.values()) {
            if (m.target != e.getPlayer() || tier(m) < 2) continue;
            m.followAt = e.getTo().clone();
            m.followT = m.t + m.skill.reaction() + 2;
        }
    }

    /**
     * PEARL FOLLOW (normal+): they pearled = it pearls after them, to where they landed (a solved, exact throw), as soon as its
     * own pearl's ready (it waits up to 3 s for that). Skipped only when they're close enough to just walk to.
     */
    boolean followPearl(Mimic m, LivingEntity tg) {
        if (m.followAt == null || m.t < m.followT) return false;
        Vindicator v = m.brain;
        Location at = m.followAt;
        if (m.t > m.followT + 60 || at.getWorld() != v.getWorld() || m.pearlsLeft <= 0) { m.followAt = null; return false; }
        if (m.t < m.pearlReady) return false; // waits for its pearl
        double d = at.distance(v.getLocation());
        if (d > 64) { m.followAt = null; return false; }
        if (d < 4.5 || d < 10 && walkLine(v, at) && Math.abs(at.getY() - v.getLocation().getY()) < 1.3) { m.followAt = null; return false; }
        // where they are NOW if they've moved on from the landing spot (still in sight), else the landing spot
        Location aim = tg != null && tg.getWorld() == at.getWorld() && tg.getLocation().distance(at) < 6 && sees(v, tg) ? tg.getLocation() : at;
        if (throwPearl(m, aim, true) || throwPearl(m, at, true)) { m.followAt = null; return true; }
        m.followAt = null;
        return false;
    }

    // ---- cobwebs

    /**
     * Stuck in a COBWEB: a real player gets out by pouring a WATER BUCKET on it (the web washes away, the water is picked back
     * up), else pearls out, else cuts it with the sword.
     */
    private boolean webEscape(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        Block feet = v.getLocation().getBlock(), head = feet.getRelative(org.bukkit.block.BlockFace.UP);
        Block web = feet.getType() == Material.COBWEB ? feet : head.getType() == Material.COBWEB ? head : null;
        if (web == null) for (Block b : underNow(v.getLocation())) { // half in one counts too (a player's hitbox is stuck all the same)
            if (b.getType() == Material.COBWEB) { web = b; break; }
            if (b.getRelative(org.bukkit.block.BlockFace.UP).getType() == Material.COBWEB) { web = b.getRelative(org.bukkit.block.BlockFace.UP); break; }
        }
        if (web == null) { m.webTicks = 0; return false; }
        if (web != feet && web != head) { feet = web.getY() == feet.getY() ? web : web.getRelative(org.bukkit.block.BlockFace.DOWN); head = feet.getRelative(org.bukkit.block.BlockFace.UP); }
        m.webTicks++;
        if (m.webTicks < 2 + m.skill.reaction() / 3) return false; // a moment to notice
        if (m.water > 0 && worldTechs()) {
            hold(m, "water");
            m.lookDownUntil = m.t + 8;
            swing(m);
            for (Block b : new Block[]{feet, head}) {
                if (b.getType() != Material.COBWEB) continue;
                temp.remove(b); // one it placed itself: the water takes it
                b.setType(Material.AIR, false);
            }
            if (pour(m, feet, Material.WATER, 10)) feet.getWorld().playSound(feet.getLocation().clone().add(0.5, 0.5, 0.5), Sound.ITEM_BUCKET_EMPTY, 1, 1);
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 11L);
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
        if (m.webTicks % 4 == 0) swing(m);
        if (m.webTicks >= 2 + m.skill.reaction() / 3 + 8) {
            temp.remove(web);
            web.setType(Material.AIR, false);
            web.getWorld().playSound(web.getLocation().clone().add(0.5, 0.5, 0.5), Sound.BLOCK_WOOL_BREAK, 1, 1);
            m.webTicks = 0;
        }
        return true;
    }

    // ---- melee

    private void melee(Mimic m, LivingEntity tg, double dist, boolean sees) {
        Vindicator v = m.brain;
        Skill s = m.skill;
        if (m.lungeUntil > m.t && spearLungeHit(m, tg)) return;
        if (m.gear.mace != null && maceTech(m, tg, dist, sees)) return;
        if (spearTech(m, tg, dist, sees)) return;
        if (boomStart(m, tg, dist, sees)) return;
        if (tier(m) >= 2 && uhcTech(m, tg, dist, sees)) return;
        double reach = reachOf(m, tg);
        double ch = charge(m);
        boolean theyBlock = blocking(tg);
        // their shield up: out with the axe
        if (theyBlock && dist < 4.5 && m.axeUntil < m.t && m.gear.axe != null) { m.axeUntil = m.t + 30; m.lastHit = Math.min(m.lastHit, m.t - 20); }
        // a player who shields a LOT (it remembers): the axe comes out first
        if (m.gear.axe != null && tier(m) >= 3 && profile(tg).shieldRate() > 0.25 && dist < 5 && m.axeUntil < m.t && theyBlock) m.axeUntil = m.t + 40;
        String want = m.axeUntil > m.t ? "axe" : "sword";
        if (!want.equals(m.held)) hold(m, want);
        boolean shielding = m.blockUntil > m.t && m.shieldOffUntil <= m.t;
        // SHIELD UP, NO AXE (hard+): hitting a raised shield does nothing, so it circles round to their side / back instead
        if (theyBlock && m.axeUntil <= m.t && tier(m) >= 3 && dist < 5 && facing(tg, v)) {
            Vector toS = tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
            if (toS.lengthSquared() > 1e-4) {
                toS.normalize();
                Vector side = new Vector(-toS.getZ(), 0, toS.getX()).multiply(m.strafeDir);
                if (!safeStep(v, side)) { m.strafeDir = -m.strafeDir; side.multiply(-1); }
                Vector fw = toS.clone().multiply(dist > 2.8 ? 0.06 : -0.04);
                if (v.isOnGround()) walk(v, side.multiply(0.3).add(fw).setY(v.getVelocity().getY()), 3);
                return;
            }
        }
        // swing when charged enough and in reach (lower the shield first). EVERY hit is a CRIT (falling onto them after a
        // jump) or a SPRINT hit (a lunge in): never a plain standing hit, never a sweep.
        // COMBO BREAK (normal+): taking hits without hitting back = cuts out of the line, hard to the side, jumps the knockback
        Vector toB = tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
        if (tier(m) >= 2 && m.comboTaken >= 2 && m.t - m.lastHurt <= 10 && m.breakUntil < m.t && v.isOnGround() && toB.lengthSquared() > 1e-4 && (ch < s.hitSelect() || reach > cfg("reach", 3.0))) {
            Vector to = toB.normalize();
            m.strafeDir = -m.strafeDir; m.strafeFlip = 15;
            Vector out = new Vector(-to.getZ(), 0, to.getX()).multiply(0.38 * m.strafeDir);
            if (safeStep(v, out.clone().normalize())) { walk(v, out.setY(0.42), 3); m.breakUntil = m.t + 8; return; }
        }
        // I-FRAME TIMING (hard+): a hit while they're still flashing red is wasted, so it waits for their hurt time to run out
        boolean flashing = tier(m) >= 3 && tg.getNoDamageTicks() > tg.getMaximumNoDamageTicks() / 2 + 1;
        if (!flashing && comboTech(m, tg, dist, sees, reach, ch)) return;
        if (!flashing && reach <= cfg("reach", 3.0) + 0.6 && sees && ch >= s.hitSelect() - 0.1 && !(shielding && random.nextDouble() < 0.5)) {
            boolean falling = canCrit(m);
            boolean rising = !v.isOnGround() && !falling;
            boolean inReach = reach <= cfg("reach", 3.0);
            if (falling && inReach && ch >= s.hitSelect()) { // the crit lands
                if (shielding) { m.blockUntil = m.t; Emotes.q(() -> m.body.clearActiveItem()); }
                m.sprintHit = false;
                if (!whiff(m, tg)) strike(m, tg, ch);
                return;
            }
            // on a ladder / in water: a plain hit (no crit there), it still fights back
            boolean clinging = Tag.CLIMBABLE.isTagged(v.getLocation().getBlock().getType()) || v.isInWater();
            if (clinging && inReach && ch >= s.hitSelect()) { m.sprintHit = false; if (!whiff(m, tg)) strike(m, tg, ch); return; }
            // UPPERCUT: at the top of its jump (eyes highest) with them right at the edge of reach: the sprint hit there
            if (rising && Math.abs(v.getVelocity().getY()) < 0.12 && inReach && ch >= s.hitSelect() && tier(m) >= 3) { m.sprintHit = true; if (!whiff(m, tg)) strike(m, tg, ch); return; }
            if (rising) return; // on the way up: wait for the fall
            if (v.isOnGround() && inReach && ch >= s.hitSelect()) {
                if (shielding) { m.blockUntil = m.t; Emotes.q(() -> m.body.clearActiveItem()); }
                Vector to0 = tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
                if (to0.lengthSquared() > 1e-4) to0.normalize();
                boolean room = !v.getLocation().getBlock().getRelative(0, 2, 0).getType().isSolid() && !inWeb(m) && !v.isInWater();
                boolean critStyle = m.style.equals("axe") || m.style.equals("uhc") || m.style.equals("mace");
                // SWEEP (Java): they're bunched up and it has a sword: a standing, non-sprinting full hit sweeps everyone round them
                if (m.held.equals("sword") && ch > 0.848 && !sweepTargets(m, tg).isEmpty()) {
                    m.sprintHit = false; m.sweepHit = true;
                    Safe.vel(v, new Vector(0, v.getVelocity().getY(), 0));
                    if (!whiff(m, tg)) strike(m, tg, ch);
                    m.sweepHit = false;
                    return;
                }
                if (room && m.wtapUntil <= m.t && safePath(v, to0, 1.8) && random.nextDouble() < (critStyle ? Math.max(0.6, s.jumpCrit()) : s.jumpCrit() * 0.8)) {
                    // CRIT: jump now, hit on the way down (stays close so they're still in reach)
                    Safe.vel(v, to0.clone().multiply(0.12).setY(0.42));
                    return;
                }
                // SPRINT HIT: lunge in (sprinting) and hit (no lunge toward lava or a drop)
                if (safePath(v, to0, 1.8)) walk(v, new Vector(to0.getX() * 0.42, v.getVelocity().getY(), to0.getZ() * 0.42), 3);
                m.sprintHit = true;
                breachSwap(m, tg);
                if (!whiff(m, tg)) strike(m, tg, ch);
                return;
            }
        }
        // raise the shield: close, facing us, our hit not ready
        float theirs;
        try { theirs = attackCharge(tg); } catch (RuntimeException ex) { theirs = 1f; }
        boolean theyReady = theirs > 0.9f && reachTo(tg, v) < 3.3 && facing(tg, v);
        // SHIELD TIMING: up right when their swing is charged and we can't hit yet (axe players), else the odd block
        if (!shielding && hasShield(m) && m.shieldOffUntil <= m.t && dist < 4 && ch < 0.7 && facing(tg, v)
                && (theyReady && m.style.equals("axe") ? random.nextDouble() < 0.5 : random.nextDouble() < s.block())) {
            m.blockUntil = m.t + 10 + random.nextInt(12);
            Emotes.q(() -> m.body.startUsingItem(EquipmentSlot.OFF_HAND));
        } else if (shielding && m.blockUntil - m.t == 1) Emotes.q(() -> m.body.clearActiveItem());
        // IN WATER: swims at them like a player (sprint-swimming, up to them at the surface, down to them on the floor)
        if (v.isInWater() || v.getLocation().add(0, 1, 0).getBlock().getType() == Material.WATER) { swimAt(m, tg); return; }
        // above / below them, a wall between, round a corner: no strafing on the spot, it goes and gets them
        if (!direct(m, tg)) { navChase(m, tg); m.sprintHit = true; return; }
        m.path = null;
        // move: W-tap back, jump-crit in, strafe close, chase far
        Vector to = tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
        if (to.lengthSquared() < 1e-4) return;
        to.normalize();
        Vector cur = v.getVelocity();
        if (m.wtapUntil > m.t && safeStep(v, to.clone().multiply(-1))) {
            // S-TAP (hard+): a step back after the hit (they come to it); W-TAP below that: just lets go of W for a moment
            double back = tier(m) >= 3 ? 0.12 : 0;
            Safe.vel(v, new Vector(cur.getX() * 0.5 - to.getX() * back, cur.getY(), cur.getZ() * 0.5 - to.getZ() * back));
            return;
        }
        if (v.isOnGround() && dist > 2.4 && dist < 4.2 && ch > 0.55 && ch < 0.9 && safePath(v, to, 2.7) && random.nextDouble() < s.jumpCrit() * 0.3) {
            Safe.vel(v, to.clone().multiply(0.3).setY(0.42)); // lands the hit on the way down
            return;
        }
        if (dist < 4.5) {
            if (--m.strafeFlip <= 0) { m.strafeDir = -m.strafeDir; m.strafeFlip = tier(m) >= 4 ? 25 + random.nextInt(40) : 12 + random.nextInt(30); } // godlike circles longer
            // BACK TO A HAZARD (hard+): their knockback would put it in lava / off a ledge: circles round so its back is clear
            if (tier(m) >= 3 && !cellSafe(v.getLocation().clone().add(-to.getX() * 2.2, 0.01, -to.getZ() * 2.2).getBlock())) {
                Vector l = new Vector(-to.getZ(), 0, to.getX());
                boolean leftOk = cellSafe(v.getLocation().clone().add(l.getX() * 1.5 - to.getX() * 1.5, 0.01, l.getZ() * 1.5 - to.getZ() * 1.5).getBlock());
                m.strafeDir = leftOk ? 1 : -1; m.strafeFlip = 15;
            }
            // BAIT (godlike): a fake jump in while its hit charges, to draw their swing / shield
            if (tier(m) >= 4 && ch < s.hitSelect() - 0.2 && dist > 3 && dist < 4.2 && v.isOnGround() && m.t >= m.baitReady && random.nextDouble() < 0.04) {
                m.baitReady = m.t + 60;
                if (safePath(v, to, 1.5)) { walk(v, to.clone().multiply(0.15).setY(0.42), 3); return; }
            }
            if (!safeStep(v, new Vector(-to.getZ(), 0, to.getX()).multiply(m.strafeDir))) { m.strafeDir = -m.strafeDir; m.strafeFlip = 20; } // never strafes into lava / off a ledge
            Vector side = new Vector(-to.getZ(), 0, to.getX()).multiply(safeStep(v, new Vector(-to.getZ(), 0, to.getX()).multiply(m.strafeDir)) ? s.strafe() * m.strafeDir : 0);
            // SPACING: sit just outside their reach while our hit charges, step in the moment it's ready
            double keep = ch >= s.hitSelect() - 0.1 ? 2.5 : (m.rushUntil > m.t ? 2.5 : m.style.equals("sword") || m.style.startsWith("neth") || m.style.equals("diamond") || m.style.equals("pot") ? 3.35 : 2.8);
            Vector fw = to.clone().multiply(dist > keep + 0.3 ? (safeStep(v, to) ? 0.1 : 0) : dist < keep - 0.3 ? (safeStep(v, to.clone().multiply(-1)) ? -0.08 : 0) : 0);
            if (v.isOnGround()) walk(v, new Vector(cur.getX() * 0.55 + side.getX() + fw.getX(), cur.getY(), cur.getZ() * 0.55 + side.getZ() + fw.getZ()), 3);
            m.sprintHit = false;
        } else {
            chase(m, tg, flank(m, tg, intercept(m, tg, to, dist), dist));
            m.sprintHit = true; // running in = a sprint hit
        }
    }

    /**
     * Runs at them like a sprinting player: steers its own velocity straight in (hops a block in the way), with the
     * pathfinder only as a backup when it can't get closer that way (round walls).
     */
    private void chase(Mimic m, LivingEntity tg, Vector to) {
        Vindicator v = m.brain;
        double sp = m.skill.speed() * 0.9;
        PotionEffect speed = v.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) sp *= 1 + 0.2 * (speed.getAmplifier() + 1);
        Vector cur = v.getVelocity();
        Block ahead = v.getLocation().clone().add(to.getX() * 0.8, 0, to.getZ() * 0.8).getBlock();
        boolean wall = blocks(ahead), tall = blocks(ahead.getRelative(0, 1, 0));
        if (wall && tall) m.chaseStuck++; else m.chaseStuck = Math.max(0, m.chaseStuck - 1);
        if (m.rushUntil > m.t) sp *= 1.1;
        // no clear run at them (walls, a door, stairs, a drop, they're up / down a floor): the walker plans a real path
        if (m.chaseStuck > 3 || !walkLine(v, tg.getLocation())) { navChase(m, tg); return; }
        if (spearDash(m, tg, to)) return;
        m.path = null;
        // walled off: a door / gate nearby first (a player uses the door), else mines through with their pickaxe / axe / shovel
        if (m.doorGoal != null && (m.t > m.doorUntil || m.doorGoal.getWorld() != v.getWorld())) m.doorGoal = null;
        if (m.doorGoal == null && m.chaseStuck > 3) { m.doorGoal = findDoor(m, tg); m.doorUntil = m.t + 120; }
        if (m.doorGoal != null) {
            Location dc = m.doorGoal.getLocation().clone().add(0.5, 0, 0.5);
            Vector toDoor = dc.toVector().subtract(v.getLocation().toVector()).setY(0);
            if (toDoor.lengthSquared() < 0.25) { m.doorGoal = null; m.chaseStuck = 0; move(m, to, sp, false, false); return; } // in the doorway: on through
            m.chaseStuck = 0;
            move(m, toDoor.normalize(), sp, false, false);
            return;
        }
        // ZIG-ZAG in like a player dodging arrows when they hold a bow / crossbow / trident, or from far off
        double d = tg.getLocation().distance(v.getLocation());
        Material held = mainHand(tg).getType();
        boolean ranged = held == Material.BOW || held == Material.CROSSBOW || held == Material.TRIDENT || offHand(tg).getType() == Material.CROSSBOW;
        if (tier(m) >= 2 && profile(tg).bowRate() > 0.05) ranged = true; // it remembers they bow a lot
        if (m.rushUntil <= m.t && d > 5 && (ranged || d > 12)) {
            double a = Math.toRadians(Math.sin(m.t * 0.22 + m.strafeDir) * (ranged ? 35 : 15));
            double c = Math.cos(a), sn = Math.sin(a);
            to = new Vector(to.getX() * c - to.getZ() * sn, 0, to.getX() * sn + to.getZ() * c);
        }
        move(m, to, sp, wall && !tall, true);
    }

    // ================================================================ the hunt (1.2.90)

    int keepPearls() { return (int) cfg("hunt.keep-pearls", 4); }
    /** 1.2.93: 2 kept for the fight (an old config's 4 reads as 2: it walked everywhere). */
    int keepRockets() { return (int) Balance.num(plugin, "mimic-player.hunt.keep-rockets", 4, 2); }
    /** 1.2.93: flies anything over 48 blocks (an old config's 150 reads as 48). */
    double flyOver() { return Balance.num(plugin, "mimic-player.hunt.fly-over", 150, 48); }

    /** Keeps the chunks round it loaded (a plugin chunk ticket, 3 round = 7x7) while it hunts, so it moves with no one near. */
    void keepLoaded(Mimic m) {
        if (m.t - m.ticketAt < 20 && m.ticketWorld == m.brain.getWorld()) return;
        m.ticketAt = m.t;
        World w = m.brain.getWorld();
        if (m.ticketWorld != null && m.ticketWorld != w) releaseTickets(m);
        m.ticketWorld = w;
        int cx = m.brain.getLocation().getBlockX() >> 4, cz = m.brain.getLocation().getBlockZ() >> 4, r = (int) cfg("hunt.load-radius", 3);
        Set<Long> want = new HashSet<>();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) want.add(((long) (cx + dx) << 32) | ((cz + dz) & 0xffffffffL));
        for (Long k : new ArrayList<>(m.tickets)) if (!want.contains(k)) { try { w.removePluginChunkTicket((int) (k >> 32), (int) (long) k, plugin); } catch (RuntimeException ignored) { } m.tickets.remove(k); }
        for (Long k : want) if (m.tickets.add(k)) { try { w.addPluginChunkTicket((int) (k >> 32), (int) (long) k, plugin); } catch (RuntimeException ignored) { } }
    }

    void releaseTickets(Mimic m) {
        if (m.ticketWorld != null) for (Long k : m.tickets) try { m.ticketWorld.removePluginChunkTicket((int) (k >> 32), (int) (long) k, plugin); } catch (RuntimeException ignored) { }
        m.tickets.clear();
        m.ticketWorld = null;
    }

    /**
     * Gets to its quarry (or to where players hang out): ANOTHER WORLD = through a portal players have used (or, for the End,
     * the stronghold's: dug down to, frames filled with its Eyes of Ender, jumped in); FAR in this world = flies its elytra
     * (keeping `hunt.keep-rockets` for the fight) or walks it in 40-block legs on the walker (pearls over what it can't
     * cross, keeping `hunt.keep-pearls`); UNDERGROUND = digs straight down, sealing the hole above it.
     */
    void travel(Mimic m, Player q) {
        Vindicator v = m.brain;
        World here = v.getWorld();
        World dw; Location dest;
        if (q != null) { dw = q.getWorld(); dest = q.getLocation(); }
        else {
            if (hunt == null || (dw = hunt.favouriteWorld()) == null) { m.travelNote = "waiting (nobody to hunt)"; return; }
            dest = hunt.hotspot(dw);
        }
        String who = q != null ? q.getName() : "where players hang out";
        // the Nether shortcut (1.2.93): a long Overworld trip through recorded portals (8x shorter in there)
        if (dw == here && here.getEnvironment() == World.Environment.NORMAL && !m.travelFly && m.t - m.routeAt > 200) {
            m.routeAt = m.t;
            m.viaNether = false;
            double flat0 = MimicHunt.flat(dest, v.getLocation());
            if (hunt != null && flat0 > cfg("hunt.nether-over", 500) && !(canFly(m) && flat0 > flyOver())) {
                for (World nw : Bukkit.getWorlds()) {
                    if (nw.getEnvironment() != World.Environment.NETHER) continue;
                    MimicHunt.Portal in = hunt.bestPortal(here, nw, v.getLocation(), null);
                    if (in == null) continue;
                    MimicHunt.Portal out = hunt.bestPortal(nw, here, in.to(), dest);
                    if (out == null) continue;
                    double cost = MimicHunt.flat(in.from(), v.getLocation()) + MimicHunt.flat(in.to(), out.from()) * 1.3 + MimicHunt.flat(out.to(), dest) + 60;
                    if (cost < flat0 * 0.7) { m.viaNether = true; m.netherWorld = nw; m.netherDest = out.from(); break; }
                }
            }
        }
        if (m.viaNether && here.getEnvironment() == World.Environment.NORMAL && m.netherWorld != null) {
            m.travelNote = "heading for the Nether shortcut (to " + who + ")";
            toWorld(m, m.netherWorld, m.netherDest); return;
        }
        if (here.getEnvironment() == World.Environment.NETHER) m.viaNether = false;
        if (dw != here) { toWorld(m, dw, dest); return; }
        m.portalTicks = 0;
        double flat = Math.hypot(dest.getX() - v.getLocation().getX(), dest.getZ() - v.getLocation().getZ());
        if (q == null && flat < 24) { m.travelNote = "waiting where players hang out"; idle(m); return; } // (here idle is right: it's waiting around) // there: waits for someone to come by
        m.travelNote = (m.travelFly ? "flying" : "travelling") + " to " + who + " (" + Math.round(flat) + " blocks)";
        if (m.travelFly || flat > flyOver() && canFly(m)) { if (flyTo(m, dest)) return; }
        // its quarry's below it (a base, a stronghold): straight down when it's right above
        if (flat < 3 && dest.getY() < v.getLocation().getY() - 3 && digDown(m, dest)) { m.travelNote = "digging down to " + who; return; }
        goLeg(m, dest, flat);
    }

    /**
     * One leg of a walk (1.2.93): within 40 it goes straight for the spot; further, it looks at 9 headings 32 out (dry,
     * standable ground; water, lava and treetops cost, a big climb costs) and walks to the best on the walker. Not getting
     * closer for 8 s = it turns its legs aside (60, -60, 120, -120 degrees), pearls ahead above its reserve, then tries again.
     */
    void goLeg(Mimic m, Location dest, double flat) {
        Vindicator v = m.brain;
        if (v.isInWater()) { m.swimToY = dest.getWorld() == v.getWorld() && flat < 64 ? dest.getY() : v.getLocation().getY(); m.swimToT = m.t; }
        if (flat < m.travelBest - 3) { m.travelBest = flat; m.travelBestAt = m.t; m.legTries = 0; }
        else if (m.t - m.travelBestAt > 160) { // stuck: another way
            m.travelBestAt = m.t; m.travelBest = flat;
            int[] turns = {60, -60, 120, -120, 0};
            m.legBias = turns[m.legTries++ % turns.length];
            m.path = null;
            if (m.legTries % 2 == 0 && m.pearlsLeft > keepPearls() && m.t >= m.pearlReady) {
                Block far = legGoal(m, dest, 24);
                if (far != null && throwPearl(m, far.getLocation().clone().add(0.5, 0, 0.5), true)) return;
            }
        }
        if (m.t - m.travelBestAt > 100 && m.legBias != 0 && m.legTries > 5) m.legBias = 0;
        if (travelLeap(m, dest, flat)) return;
        Block goal;
        if (m.legCached != null && m.legCached.getWorld() == v.getWorld() && m.t - m.legAt < 10 && m.path != null && m.pathIdx < m.path.size()) goal = m.legCached;
        else {
            goal = flat <= 40 ? standNear(dest) : null;
            if (goal == null) goal = legGoal(m, dest, flat <= 40 ? Math.max(4, flat) : 32);
            m.legCached = goal; m.legAt = m.t;
        }
        if (goal == null) { move(m, new Vector(dest.getX() - v.getLocation().getX(), 0, dest.getZ() - v.getLocation().getZ()).normalize(), chaseSpeed(m), false, true); return; }
        if (m.pathGoal == null || m.pathGoal.getWorld() != goal.getWorld() || m.pathGoal.getLocation().distanceSquared(goal.getLocation()) > 64 || m.path == null || m.pathIdx >= m.path.size()) m.travelGoal = goal;
        navGoTo(m, m.travelGoal != null && m.travelGoal.getWorld() == v.getWorld() ? m.travelGoal : goal);
    }

    /**
     * 1.2.93: uses its stuff to get there faster, keeping what the fight needs: PEARL HOPS (more pearls than it keeps + 2: a
     * long throw onto good ground 25-35 ahead, when the throw really lands there) and WIND LEAPS (more than 2 charges: one
     * behind it on open flat ground = a long jump forward).
     */
    private boolean travelLeap(Mimic m, Location dest, double flat) {
        Vindicator v = m.brain;
        if (!v.isOnGround() || flat < 30) return false;
        if (m.pearlsLeft > keepPearls() + 2 && m.t >= m.pearlReady && m.t >= m.leapTryAt) {
            m.leapTryAt = m.t + 30; // a throw that doesn't land there: tried again in 1.5 s, not every tick
            Block far = legGoal(m, dest, Math.min(34, flat - 4));
            if (far != null && far.getLocation().distance(v.getLocation()) > 22 && !far.getRelative(0, -1, 0).isLiquid()
                    && throwPearl(m, far.getLocation().clone().add(0.5, 0.1, 0.5), true)) { m.travelNote = "pearling toward " + (dest.getWorld() == v.getWorld() ? Math.round(flat) + " blocks off" : "its quarry"); m.path = null; return true; }
        }
        if (m.wind > 2 && m.t >= m.windRunReady) {
            Vector dir = new Vector(dest.getX() - v.getLocation().getX(), 0, dest.getZ() - v.getLocation().getZ()).normalize();
            if (safePath(v, dir, 8) && walkLine(v, v.getLocation().add(dir.clone().multiply(8)))) {
                m.wind--; m.windRunReady = m.t + 50;
                hold(m, "wind"); swing(m);
                v.getWorld().spawnParticle(Particle.GUST_EMITTER_SMALL, v.getLocation(), 1);
                v.getWorld().playSound(v.getLocation(), Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1, 1);
                Safe.vel(v, dir.multiply(1.0).setY(0.6));
                v.setFallDistance(0); m.noFallUntil = m.t + 40;
                m.path = null;
                return true;
            }
        }
        return false;
    }

    /** The top solid block's air above it at that x/z, or null over the void / an unloaded chunk. */
    Block solidSurface(Location l) {
        Block s = surfaceAt(l);
        if (s == null || s.getY() <= l.getWorld().getMinHeight() + 1 || !s.getRelative(0, -1, 0).getType().isSolid() && !s.getRelative(0, -1, 0).isLiquid()) return null;
        return s;
    }

    /** Open void (nothing to stand on within 40 below) on the straight line between it and them. */
    boolean voidBetween(Location a, Location b) {
        Vector d = b.toVector().subtract(a.toVector()).setY(0);
        double len = d.length();
        if (len < 1) return false;
        d.multiply(1 / len);
        for (double t = 2; t < len - 1; t += 2) {
            Location p = a.clone().add(d.clone().multiply(t));
            if (!p.getWorld().isChunkLoaded(p.getBlockX() >> 4, p.getBlockZ() >> 4)) continue;
            boolean floor = false;
            for (int dy = 0; dy < 40 && !floor; dy++) if (p.getWorld().getBlockAt(p.getBlockX(), Math.min(a.getBlockY(), b.getBlockY()) - dy, p.getBlockZ()).getType().isSolid()) floor = true;
            if (!floor) return true;
        }
        return false;
    }

    /** Under ground: 7+ blocks of something over it (Overworld only; the Nether / End have no "surface" to aim for). */
    boolean underground(Location l) {
        if (l.getWorld().getEnvironment() != World.Environment.NORMAL || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return false;
        return l.getWorld().getHighestBlockYAt(l.getBlockX(), l.getBlockZ(), org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) - l.getY() > 7;
    }

    /** The best spot `r` out toward dest (see goLeg). */
    Block legGoal(Mimic m, Location dest, double r) {
        Vindicator v = m.brain;
        Location me = v.getLocation();
        Vector to = new Vector(dest.getX() - me.getX(), 0, dest.getZ() - me.getZ());
        if (to.lengthSquared() < 1e-4) return null;
        double base = Math.atan2(to.getZ(), to.getX()) + Math.toRadians(m.legBias);
        Block best = null; double bs = Double.MAX_VALUE;
        boolean wet = m.brain.isInWater() || dest.getWorld() == me.getWorld() && wetAt(dest) && MimicHunt.flat(me, dest) < 160;
        if (wet) {
            // 1.2.95 UNDER WATER: the surface heightmap counts water as the top, so it used to aim for the surface over its quarry
            // and bob there. Now: a spot at the depth it wants (water it can swim in, or a floor), working toward the goal's height
            World w = me.getWorld();
            MimicNav nav = new MimicNav(w, navRules(m));
            double wantY = me.getY() + Math.max(-r * 0.8, Math.min(r * 0.8, dest.getY() - me.getY()));
            for (int deg : new int[]{0, 25, -25, 50, -50, 80, -80}) {
                double ang = base + Math.toRadians(deg);
                int x = (int) Math.floor(me.getX() + Math.cos(ang) * r), z = (int) Math.floor(me.getZ() + Math.sin(ang) * r);
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                Block c = null;
                for (int dy = 0; dy <= 8 && c == null; dy++) for (int sg : new int[]{1, -1}) {
                    int yy = (int) Math.floor(wantY) + dy * sg;
                    if (yy <= w.getMinHeight() || yy >= w.getMaxHeight() - 2) continue;
                    Block b = w.getBlockAt(x, yy, z);
                    if (b.getType() == Material.WATER && !b.getRelative(0, 1, 0).getType().isSolid() || nav.standable(x, yy, z) && !hazardNear(b)) { c = b; break; }
                }
                if (c == null) continue;
                double score = Math.hypot(x - dest.getX(), z - dest.getZ()) + Math.abs(c.getY() - dest.getY()) * 0.5 + Math.abs(deg) * 0.08;
                if (score < bs) { bs = score; best = c; }
            }
            if (best != null) return best;
        }
        if (underground(me) || dest.getWorld() == me.getWorld() && underground(dest) && MimicHunt.flat(me, dest) < 120 && me.getY() - dest.getY() > 8) {
            // 1.2.93 UNDERGROUND: stays at its level and works toward the goal's height (a tunnel, never a staircase up to the
            // surface and back down); a spot it can stand in near that height, else the cell itself (the walker digs to it)
            World w = me.getWorld();
            MimicNav nav = new MimicNav(w, navRules(m));
            double wantY = me.getY() + Math.max(-r * 0.5, Math.min(r * 0.5, dest.getY() - me.getY()));
            for (int deg : new int[]{0, 25, -25, 50, -50}) {
                double ang = base + Math.toRadians(deg);
                int x = (int) Math.floor(me.getX() + Math.cos(ang) * r), z = (int) Math.floor(me.getZ() + Math.sin(ang) * r);
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                Block c = null;
                for (int dy = 0; dy <= 5 && c == null; dy++) for (int sg : new int[]{1, -1}) {
                    int y = (int) Math.floor(wantY) + dy * sg;
                    if (nav.standable(x, y, z) && !hazardNear(w.getBlockAt(x, y, z))) { c = w.getBlockAt(x, y, z); break; }
                }
                double score = Math.hypot(x - dest.getX(), z - dest.getZ()) + Math.abs(deg) * 0.08 + (c == null ? 8 : 0);
                if (c == null) {
                    Block cell = w.getBlockAt(x, (int) Math.floor(wantY), z);
                    if (HAZARD.contains(cell.getType()) || cell.isLiquid() || hazardNear(cell)) continue;
                    c = cell;
                }
                if (score < bs) { bs = score; best = c; }
            }
            if (best != null) return best;
        }
        for (int deg : new int[]{0, 20, -20, 40, -40, 60, -60, 80, -80}) {
            double ang = base + Math.toRadians(deg);
            Location l = me.clone().add(Math.cos(ang) * r, 0, Math.sin(ang) * r);
            Block s = surfaceAt(l);
            if (s == null) continue;
            Block under = s.getRelative(0, -1, 0);
            Material um = under.getType();
            if (um == Material.LAVA || HAZARD.contains(um)) continue;
            double score = MimicHunt.flat(l, dest) + Math.abs(s.getY() - me.getY()) * 2.5 + Math.abs(deg) * 0.05;
            if (um == Material.WATER || under.isLiquid()) score += 30; // swimming is slow
            if (Tag.LEAVES.isTagged(um)) score += 12;
            if (score < bs) { bs = score; best = s; }
        }
        return best;
    }

    /** The surface (the first air over a solid block, from the top) at that x/z, in a loaded chunk; null if it isn't loaded. */
    Block surfaceAt(Location l) {
        World w = l.getWorld();
        if (!w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return null;
        int y;
        if (w.getEnvironment() == World.Environment.NETHER) { // a roof: the floor nearest its own height
            int y0 = l.getBlockY();
            for (int d = 0; d < 40; d++) for (int sg : new int[]{-1, 1}) {
                int yy = y0 + d * sg;
                Block b = w.getBlockAt(l.getBlockX(), yy, l.getBlockZ());
                if (b.getType().isAir() && b.getRelative(0, 1, 0).getType().isAir() && b.getRelative(0, -1, 0).getType().isSolid()) return b;
            }
            return null;
        }
        y = w.getHighestBlockYAt(l.getBlockX(), l.getBlockZ(), org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES); // under the trees, not on them
        return w.getBlockAt(l.getBlockX(), y + 1, l.getBlockZ());
    }

    Block standNear(Location l) {
        MimicNav nav = new MimicNav(l.getWorld(), new MimicNav.Rules() {
            public int mineTicks(Block b) { return -1; } public int maxDrop() { return 3; } public boolean canPillar() { return false; } public double penalty(long a, long b) { return 0; } });
        for (int dy = 0; dy <= 4; dy++) for (int sg : new int[]{-1, 1}) {
            int y = l.getBlockY() + dy * sg;
            if (nav.standable(l.getBlockX(), y, l.getBlockZ())) return l.getWorld().getBlockAt(l.getBlockX(), y, l.getBlockZ());
        }
        return null;
    }

    /** Walks to a block on the walker; can't get there = pearls toward it (only above the pearls it keeps). */
    void navGoTo(Mimic m, Block goal) {
        Vindicator v = m.brain;
        boolean repath = m.path == null || m.pathIdx >= m.path.size() || m.pathGoal == null || m.pathGoal.getWorld() != goal.getWorld()
                || m.pathGoal.getLocation().distanceSquared(goal.getLocation()) > 9 || m.t - m.pathAt > 60;
        if (repath && m.t - m.pathAt >= 6) {
            plan(m, goal);
            if (!m.navReach && m.navStuck >= 2 && m.pearlsLeft > keepPearls() && m.t >= m.pearlReady && throwPearl(m, goal.getLocation().add(0.5, 0, 0.5), true)) return;
        }
        if (m.path == null || m.pathIdx >= m.path.size()) {
            Vector to = new Vector(goal.getX() + 0.5 - v.getLocation().getX(), 0, goal.getZ() + 0.5 - v.getLocation().getZ());
            if (to.lengthSquared() > 0.09) move(m, to.normalize(), chaseSpeed(m), false, true);
            return;
        }
        followPath(m, null);
    }

    /** Elytra + more rockets than it keeps for the fight. */
    boolean canFly(Mimic m) {
        if (!m.gear.elytra || m.brain.getWorld().getEnvironment() == World.Environment.NETHER || !openUp(m.brain.getLocation().getBlock(), 10)) return false;
        if (m.brain.getWorld().getEnvironment() == World.Environment.THE_END) return m.rockets > Math.min(keepRockets(), 1); // over the void a glide isn't enough
        return m.rockets > keepRockets() || m.wind > 2; // rockets to spare, or a wind charge to launch and glide on
    }

    /**
     * ELYTRA TRAVEL (1.2.95: real elytra physics). Takes off like a player: jumps, opens the elytra on the way down and fires a
     * rocket (or a wind charge launches it and it glides). In the air it only steers where it LOOKS (pitch / yaw, turned like a
     * mouse); the speed comes from vanilla's glide maths (`glideStep`): diving speeds it up, pulling up trades speed for height,
     * a rocket pulls it along its look. Cruises ~26 over the ground ahead, boosts when slow (rockets over its reserve), lands
     * on a dry spot near the end with a glide path and a flare.
     */
    boolean flyTo(Mimic m, Location dest) {
        Vindicator v = m.brain;
        Vector to = new Vector(dest.getX() - v.getLocation().getX(), 0, dest.getZ() - v.getLocation().getZ());
        double flat = to.length();
        Vector dir = flat > 1e-3 ? to.clone().multiply(1 / flat) : new Vector(0, 0, 1);
        float toYaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
        if (!m.travelFly) {
            if (!v.isOnGround() || !canFly(m)) return false;
            m.travelFly = true; m.flying = true; m.landSpot = null; m.glide = null; m.glideAt = null; m.boostTicks = 0;
            m.takeoffAt = m.t; m.flyYaw = toYaw; m.flyPitch = 0; m.yaw = toYaw; m.landT = 0;
            m.body.getEquipment().setChestplate(new ItemStack(Material.ELYTRA)); armourFor(m, true);
            if (m.rockets > keepRockets()) Safe.vel(v, dir.clone().multiply(0.28).setY(0.42)); // a sprint-jump
            else { // no spare rockets: a wind charge at its feet launches it, then it glides
                m.wind--; hold(m, "wind"); swing(m);
                v.getWorld().spawnParticle(Particle.GUST_EMITTER_SMALL, v.getLocation(), 1);
                v.getWorld().playSound(v.getLocation(), Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1, 1);
                Safe.vel(v, dir.clone().multiply(0.4).setY(1.3));
            }
            return true;
        }
        v.setFallDistance(0);
        if (m.glide == null) { // still in the jump: opens the elytra as it starts to fall (a player's second space press)
            if (v.isOnGround() && m.t - m.takeoffAt > 3) { m.travelFly = false; land(m); return false; }
            if (v.getVelocity().getY() > 0.02 && m.t - m.takeoffAt < 30) return true;
            m.glide = v.getVelocity().clone();
            v.getWorld().playSound(v.getLocation(), Sound.ITEM_ARMOR_EQUIP_ELYTRA, 0.8f, 1);
            if (m.rockets > keepRockets() || v.getWorld().getEnvironment() == World.Environment.THE_END && m.rockets > 0) { m.flyPitch = -30; elytraRocket(m); }
        }
        Block ground = solidSurface(v.getLocation());
        boolean overVoid = ground == null;
        double y = v.getLocation().getY();
        double groundY = ground != null ? ground.getY() : Math.min(y - 30, dest.getY() - 10);
        double speed = Math.hypot(m.glide.getX(), m.glide.getZ());
        // landing: a dry spot it can stand on near the end of the trip, picked once, glided down onto
        if (flat < 32 + Math.max(0, y - groundY - 26)) {
            if (m.landSpot == null || m.landSpot.getWorld() != v.getWorld()) {
                Block ls = standNear(dest);
                if (ls == null || ls.getRelative(0, -1, 0).isLiquid()) { Block lg = legGoal(m, dest, Math.max(4, flat - 6)); if (lg != null) ls = lg; }
                m.landSpot = ls != null ? ls.getLocation().clone().add(0.5, 0, 0.5) : dest.clone();
                m.landT = m.t;
            }
            Vector toLand = m.landSpot.toVector().subtract(v.getLocation().toVector());
            double hl = Math.hypot(toLand.getX(), toLand.getZ()), above = y - groundY;
            if (v.isOnGround() || above < 0.6 || toLand.length() < 1.5 && above < 2.5 || m.t - m.landT > 400) { m.travelFly = false; m.landSpot = null; land(m); return false; }
            float wantYaw = hl > 1.5 ? (float) Math.toDegrees(Math.atan2(-toLand.getX(), toLand.getZ())) : m.flyYaw;
            double slope = Math.toDegrees(Math.atan2(-toLand.getY(), Math.max(0.5, hl))); // how steeply the spot lies below
            float wantPitch;
            if (above < 3.5 || above < 8 && speed > 0.8) wantPitch = speed > 0.5 ? -25 : -8; // the flare: nose up, bleeds the speed off just over the ground
            else if (hl < 6 && -toLand.getY() > hl) wantPitch = 50; // high over it: spirals down steep
            else wantPitch = (float) Math.max(-10, Math.min(55, slope + (speed > 0.9 ? 6 : -6)));
            glideStep(m, wantPitch, wantYaw);
            return true;
        }
        m.landSpot = null;
        // looks ahead: the highest ground over the next 48 blocks it flies over, so it climbs before a hill, not into it
        double ahead = overVoid ? -1e9 : groundY;
        for (int k = 8; k <= 48; k += 8) {
            Block s2 = solidSurface(v.getLocation().clone().add(dir.clone().multiply(k)));
            if (s2 != null) ahead = Math.max(ahead, s2.getY());
        }
        // over the void (the End): never below where it's going (+12) or below the islands ahead
        double alt = ahead < -1e8 ? Math.max(y, dest.getY() + 12) : Math.min(v.getWorld().getMaxHeight() - 8, Math.max(ahead + 26, overVoid ? dest.getY() + 12 : -1e9));
        Vector hv = speed > 0.05 ? m.glide.clone().setY(0).multiply(1 / speed) : dir.clone();
        boolean wall = false;
        for (int k = 1; k <= 6 && !wall; k++) {
            Block b2 = v.getLocation().clone().add(hv.clone().multiply(k * Math.max(1.5, speed * 2.2))).getBlock();
            if (b2.getType().isSolid() || b2.getRelative(0, 1, 0).getType().isSolid()) wall = true;
        }
        double e = alt - y;
        boolean spare = m.rockets > keepRockets();
        if (m.boostTicks == 0 && m.t >= m.nextRocket && (spare && (speed < 0.9 || e > 18 || wall) || overVoid && m.rockets > 0 && y < dest.getY() + 6))
            elytraRocket(m); // over the void sinking: a rocket whatever it keeps
        float wantPitch;
        if (wall) wantPitch = -50; // pull up hard
        else if (m.boostTicks > 0) wantPitch = (float) Math.max(-40, Math.min(25, -e * 1.5));
        else if (e > 3 && speed > 1.1) wantPitch = -22; // zooms up on the speed it carries
        else if (e < -8) wantPitch = (float) Math.min(40, 8 + (-e - 8) * 0.8); // too high: dives
        else wantPitch = speed < 0.7 ? 18 : 3; // a shallow glide; nose down to get the speed back
        if (!overVoid && m.boostTicks == 0 && !spare && (y - groundY < 4 || speed < 0.35 && e < 6)) { // out of spare rockets and down low: lands, walks on
            if (v.isOnGround() || y - groundY < 0.6) { m.travelFly = false; land(m); return false; }
            wantPitch = y - groundY < 3 ? -15 : 20;
        }
        glideStep(m, wantPitch, toYaw + (wall ? 25 * m.strafeDir : 0));
        return true;
    }

    /** A rocket in flight: hold it, use it; vanilla's boost lasts ~1.1-1.5 s (`glideStep` applies it). */
    private void elytraRocket(Mimic m) {
        if (m.rockets <= 0) return;
        Vindicator v = m.brain;
        m.rockets--; m.boostTicks = 22 + random.nextInt(9); m.nextRocket = m.t + m.boostTicks + 4;
        hold(m, "rocket"); swing(m);
        v.getWorld().playSound(v.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1, 1);
        v.getWorld().spawnParticle(Particle.FIREWORK, v.getLocation(), 10, 0.2, 0.2, 0.2, 0.05);
    }

    /**
     * One tick of vanilla elytra flight (LivingEntity's fall-flying maths + a firework's boost): eases its look toward the
     * pitch / yaw it wants (a player's mouse, ~5 / 8 degrees a tick), then works the new speed out of the look and the old
     * speed. Whatever the world stopped last tick (a wall, the ground) is taken off the speed.
     */
    private void glideStep(Mimic m, float wantPitch, float wantYaw) {
        Vindicator v = m.brain;
        Vector pos = v.getLocation().toVector();
        Vector vel = m.glide;
        if (m.glideAt != null) {
            Vector moved = pos.clone().subtract(m.glideAt);
            if (Math.abs(moved.getX()) < Math.abs(vel.getX()) * 0.5 - 0.02) vel.setX(moved.getX());
            if (Math.abs(moved.getY()) < Math.abs(vel.getY()) * 0.5 - 0.02) vel.setY(moved.getY());
            if (Math.abs(moved.getZ()) < Math.abs(vel.getZ()) * 0.5 - 0.02) vel.setZ(moved.getZ());
        }
        m.flyPitch += (float) Math.max(-5, Math.min(5, wantPitch - m.flyPitch));
        float dYaw = ((wantYaw - m.flyYaw) % 360 + 540) % 360 - 180;
        m.flyYaw += (float) Math.max(-8, Math.min(8, dYaw));
        double pr = Math.toRadians(m.flyPitch), yr = Math.toRadians(m.flyYaw);
        Vector look = new Vector(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
        if (m.boostTicks > 0) { // the firework: vel += look * 0.1 + (look * 1.5 - vel) * 0.5
            m.boostTicks--;
            vel.add(look.clone().multiply(0.1)).add(look.clone().multiply(1.5).subtract(vel).multiply(0.5));
            if (m.t % 2 == 0) v.getWorld().spawnParticle(Particle.FIREWORK, v.getLocation(), 1, 0, 0, 0, 0);
        }
        double hl = Math.hypot(look.getX(), look.getZ()), hs = Math.hypot(vel.getX(), vel.getZ());
        double lift = Math.cos(pr); lift *= lift;
        vel.setY(vel.getY() + 0.08 * (-1 + lift * 0.75));
        if (vel.getY() < 0 && hl > 0) { double d = vel.getY() * -0.1 * lift; vel.add(new Vector(look.getX() * d / hl, d, look.getZ() * d / hl)); }
        if (pr < 0 && hl > 0) { double d = hs * -Math.sin(pr) * 0.04; vel.add(new Vector(-look.getX() * d / hl, d * 3.2, -look.getZ() * d / hl)); }
        if (hl > 0) vel.add(new Vector((look.getX() / hl * hs - vel.getX()) * 0.1, 0, (look.getZ() / hl * hs - vel.getZ()) * 0.1));
        vel.multiply(new Vector(0.99, 0.98, 0.99));
        m.glideAt = pos;
        Safe.vel(v, vel.clone());
        m.yaw = m.flyYaw;
    }

    /**
     * DIG DOWN to something under it: centres on its column, mines the block under its feet (never into lava / water / the
     * void), and seals the shaft above it with a block each time it drops (nobody follows it down).
     */
    boolean digDown(Mimic m, Location dest) {
        Vindicator v = m.brain;
        if (!worldTechs() || !v.isOnGround()) return m.digBlock != null;
        Block feet = feetCell(v), below = feet.getRelative(0, -1, 0);
        if (dest.getBlockY() >= feet.getY() - 1) return false; // down there now
        Material deeper = below.getRelative(0, -1, 0).getType();
        if (deeper == Material.LAVA || deeper == Material.WATER || below.getY() <= v.getWorld().getMinHeight() + 1) return false;
        double off = Math.hypot(feet.getX() + 0.5 - v.getLocation().getX(), feet.getZ() + 0.5 - v.getLocation().getZ());
        if (off > 0.25) { Safe.vel(v, new Vector((feet.getX() + 0.5 - v.getLocation().getX()) * 0.4, v.getVelocity().getY(), (feet.getZ() + 0.5 - v.getLocation().getZ()) * 0.4)); return true; }
        // dropped since last time: a block over its head (seals the way down behind it)
        if (m.lastDigY != Integer.MIN_VALUE && feet.getY() < m.lastDigY && m.blocks > 0) {
            Block seal = feet.getRelative(0, 2, 0);
            if (seal.getType().isAir() && place(seal, m.gear.block, 2400)) { m.blocks--; swing(m); seal.getWorld().playSound(seal.getLocation(), Sound.BLOCK_STONE_PLACE, 1, 1); }
        }
        m.lastDigY = feet.getY();
        if (!blocks(below)) return false; // a cave / the room: the walker takes it from here
        return navDig(m, below);
    }

    /**
     * Into another world: a portal players have used from here to there (walks in; vanilla takes it through, or after 3 s
     * standing in it, it's put where they came out), else for the End the stronghold's portal, else back to the Overworld first.
     */
    void toWorld(Mimic m, World dw, Location dest) {
        Vindicator v = m.brain;
        World here = v.getWorld();
        MimicHunt.Portal p = null;
        if (hunt != null && m.huntId != null) { // the very portal its quarry just took (1.2.93)
            MimicHunt.Portal last = hunt.lastUsed.get(m.huntId);
            if (last != null && last.from().getWorld() == here && last.to().getWorld() == dw && MimicHunt.flat(last.from(), v.getLocation()) < 3000) p = last;
        }
        if (p == null && hunt != null) p = hunt.bestPortal(here, dw, v.getLocation(), dest);
        if (m.travelNote == null || !m.travelNote.startsWith("heading for the Nether")) m.travelNote = "going to " + dw.getName();
        if (p == null && hunt != null && dw.getEnvironment() != World.Environment.NORMAL && here.getEnvironment() != World.Environment.NORMAL)
            p = hunt.anyPortalTo(here, World.Environment.NORMAL, v.getLocation()); // via the Overworld
        Location portal = p != null ? p.from() : null, out = p != null ? p.to() : null;
        if (portal == null && here.getEnvironment() == World.Environment.NORMAL && dw.getEnvironment() == World.Environment.THE_END) {
            if (m.endFrame == null) {
                Location center = hunt == null ? null : hunt.strongholdCenter(here);
                if (hunt != null && m.t >= m.frameSearchAt) {
                    m.frameSearchAt = m.t + (center == null ? 400 : 200);
                    if (center != null && MimicHunt.flat(center, v.getLocation()) < 80) hunt.scanAround(here, v.getLocation(), 4, f -> { if (f != null) m.endFrame = f; }); // there: looks round itself
                    else hunt.findEndPortal(here, v.getLocation(), f -> { if (f != null) m.endFrame = f; });
                }
                if (center == null) { m.travelNote = "looking for a stronghold"; return; }
                m.travelNote = "heading to a stronghold (" + Math.round(MimicHunt.flat(center, v.getLocation())) + " blocks)";
                double fc = MimicHunt.flat(center, v.getLocation());
                if (fc > flyOver() && (m.travelFly || canFly(m)) && flyTo(m, center)) return;
                if (fc > 6) goLeg(m, new Location(here, center.getX(), Math.min(center.getY(), 30), center.getZ()), fc);
                return;
            }
            portal = endPortalInside(m, m.endFrame);
            if (portal == null) { m.endFrame = null; m.travelNote = "no way into the End (not enough Eyes of Ender)"; return; } // no portal, no eyes to light one
            out = new Location(dw, 100.5, 49, 0.5); // the End's obsidian platform
        }
        if (portal == null && here.getEnvironment() == World.Environment.THE_END) { // the exit fountain in the middle
            Block exit = findPortalNear(new Location(here, 0, 64, 0), 8);
            if (exit != null) { portal = exit.getLocation().add(0.5, 0, 0.5); out = dw.getSpawnLocation(); }
        }
        if (portal == null) { m.travelNote = "no known way to " + dw.getName(); return; }
        Location at = v.getLocation();
        double flat = Math.hypot(portal.getX() - at.getX(), portal.getZ() - at.getZ());
        Block pb = portal.getBlock();
        boolean inside = flat < 1.2 && Math.abs(portal.getY() - at.getY()) < 2.5;
        if (inside || feetCell(v).getType() == Material.NETHER_PORTAL || feetCell(v).getType() == Material.END_PORTAL) {
            Safe.vel(v, new Vector((portal.getX() - at.getX()) * 0.3, v.getVelocity().getY(), (portal.getZ() - at.getZ()) * 0.3));
            if (++m.portalTicks >= 60) { // vanilla didn't take it (mob portals off, cooldown): through it ourselves
                m.portalTicks = 0;
                Location to = out != null ? out.clone() : dw.getSpawnLocation();
                if (dw.getEnvironment() == World.Environment.THE_END && !to.clone().add(0, -1, 0).getBlock().getType().isSolid()) { // vanilla's landing platform
                    for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                        dw.getBlockAt(to.getBlockX() + dx, to.getBlockY() - 1, to.getBlockZ() + dz).setType(Material.OBSIDIAN);
                        for (int dy = 0; dy <= 2; dy++) dw.getBlockAt(to.getBlockX() + dx, to.getBlockY() + dy, to.getBlockZ() + dz).setType(Material.AIR);
                    }
                }
                v.teleport(to);
                m.path = null; m.endFrame = null; m.lastDigY = Integer.MIN_VALUE;
                releaseTickets(m);
            }
            return;
        }
        m.portalTicks = 0;
        // end portals are down in a stronghold: dig to them when right above
        if (flat < 3 && portal.getBlockY() < feetCell(v).getY() - 1 && digDown(m, portal)) return;
        if (flat > flyOver() && (m.travelFly || canFly(m)) && flyTo(m, portal)) return;
        m.travelNote = (m.travelNote != null && m.travelNote.startsWith("heading for the Nether") ? m.travelNote : "going to " + dw.getName()) + " (portal " + Math.round(flat) + " blocks)";
        Block goal = null;
        if (flat <= 40) goal = pb.getType() == Material.END_PORTAL ? standNear(portal.clone().add(1, 1, 0)) : standNear(portal);
        if (goal == null && flat <= 40) goal = pb;
        if (flat <= 6 && portal.getY() < at.getY() - 3 && !underground(at)) goal = surfaceAt(portal); // over it first, then digs down
        if (flat > 40) { goLeg(m, portal, flat); return; }
        if (goal == null) return;
        navGoTo(m, goal);
    }

    Block findPortalNear(Location c, int r) {
        World w = c.getWorld();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (!w.isChunkLoaded((c.getBlockX() + dx) >> 4, (c.getBlockZ() + dz) >> 4)) continue;
            for (int y = w.getMinHeight(); y < Math.min(w.getMaxHeight(), 120); y++) {
                Block b = w.getBlockAt(c.getBlockX() + dx, y, c.getBlockZ() + dz);
                if (b.getType() == Material.END_PORTAL || b.getType() == Material.END_GATEWAY) return b;
            }
        }
        return null;
    }

    /**
     * The stronghold's portal by this frame: lit already = one of its portal blocks; not lit = it fills the empty frames with its
     * Eyes of Ender (the whole ring of 12 round a 3x3) and lights it; not enough eyes = null.
     */
    Location endPortalInside(Mimic m, Location frame) {
        World w = frame.getWorld();
        if (!w.isChunkLoaded(frame.getBlockX() >> 4, frame.getBlockZ() >> 4)) return frame; // walk over there first
        Block lit = null;
        List<Block> frames = new ArrayList<>();
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            Block b = frame.getBlock().getRelative(dx, 0, dz);
            if (b.getType() == Material.END_PORTAL) lit = b;
            if (b.getType() == Material.END_PORTAL_FRAME) frames.add(b);
        }
        if (lit != null) return lit.getLocation().add(0.5, 0, 0.5);
        if (frames.size() < 12) return frame; // not all of it loaded / seen yet: go there
        int empty = 0;
        for (Block b : frames) if (b.getBlockData() instanceof org.bukkit.block.data.type.EndPortalFrame ef && !ef.hasEye()) empty++;
        double near = frame.distance(m.brain.getLocation());
        if (near > 6) return frame; // fills it when it's there
        if (empty > m.eyesLeft) return null;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Block b : frames) {
            if (b.getBlockData() instanceof org.bukkit.block.data.type.EndPortalFrame ef && !ef.hasEye()) { ef.setEye(true); b.setBlockData(ef, false); m.eyesLeft--; }
            minX = Math.min(minX, b.getX()); maxX = Math.max(maxX, b.getX()); minZ = Math.min(minZ, b.getZ()); maxZ = Math.max(maxZ, b.getZ());
        }
        swing(m);
        w.playSound(frame, Sound.BLOCK_END_PORTAL_SPAWN, 1, 1);
        for (int x = minX + 1; x < maxX; x++) for (int z = minZ + 1; z < maxZ; z++) w.getBlockAt(x, frame.getBlockY(), z).setType(Material.END_PORTAL, false);
        return new Location(w, (minX + maxX) / 2.0 + 0.5, frame.getBlockY(), (minZ + maxZ) / 2.0 + 0.5);
    }

    // ================================================================ the walker

    double chaseSpeed(Mimic m) {
        double sp = m.skill.speed() * 0.9;
        PotionEffect speed = m.brain.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) sp *= 1 + 0.2 * (speed.getAmplifier() + 1);
        if (m.rushUntil > m.t) sp *= 1.1;
        return sp;
    }

    /** It can fight them right here: about level with them and a clear walk in (or already in their face). */
    boolean direct(Mimic m, LivingEntity tg) {
        Location a = m.brain.getLocation(), b = tg.getLocation();
        double dy = b.getY() - a.getY(), flat = Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ());
        if (Math.abs(dy) <= 1.3 && flat <= 3.2 && sees(m.brain, tg)) return true;
        return Math.abs(dy) <= 1.2 && walkLine(m.brain, b);
    }

    /** A plain walk from where it is to there: room for its body all the way, the same floor under it, nothing that hurts. */
    static boolean walkLine(LivingEntity v, Location to) {
        Location at = v.getLocation();
        if (to.getWorld() != at.getWorld()) return false;
        Vector d = to.toVector().subtract(at.toVector()).setY(0);
        double len = d.length();
        if (len < 0.3) return true;
        if (len > 48) return false;
        d.multiply(1 / len);
        int fy = (int) Math.floor(at.getY() + 0.01);
        Vector side = new Vector(-d.getZ(), 0, d.getX()).multiply(0.29);
        for (double s = 0.4; s <= len; s += 0.4) {
            for (int k = -1; k <= 1; k++) {
                double x = at.getX() + d.getX() * s + side.getX() * k, z = at.getZ() + d.getZ() * s + side.getZ() * k;
                Block feet = at.getWorld().getBlockAt((int) Math.floor(x), fy, (int) Math.floor(z));
                if (blocks(feet) || blocks(feet.getRelative(0, 1, 0)) || HAZARD.contains(feet.getType()) || HAZARD.contains(feet.getRelative(0, 1, 0).getType())) return false;
                Block under = feet.getRelative(0, -1, 0);
                if (HAZARD.contains(under.getType())) return false;
                if (!under.getType().isSolid() && under.getType() != Material.WATER && !under.getRelative(0, -1, 0).getType().isSolid()) return false; // a drop of 2+: not a plain walk
            }
        }
        return true;
    }

    /** The cell they stand in (they're jumping / pillaring: the floor under them). */
    static Block standBlock(LivingEntity tg) {
        Block b = tg.getLocation().add(0, 0.01, 0).getBlock();
        if (blocks(b)) return b.getRelative(0, 1, 0);
        for (int i = 0; i < 5; i++) {
            Block u = b.getRelative(0, -1, 0);
            if (u.getType().isSolid() || u.getType() == Material.WATER || Tag.CLIMBABLE.isTagged(b.getType()) || b.getType() == Material.WATER) break;
            b = u;
        }
        return b;
    }

    MimicNav.Rules navRules(Mimic m) {
        return new MimicNav.Rules() {
            public int mineTicks(Block b) {
                if (!worldTechs() || !plugin.getConfig().getBoolean("mimic-player.dig", true) || !diggable(b)) return -1;
                int t = breakTicks(m, b, true);
                if (t < 0 || t > 200) return -1; // no tool for it / far too slow (an obsidian wall by hand)
                return t;
            }
            public int maxDrop() {
                Vindicator v = m.brain;
                int byHealth = (int) Math.floor(v.getHealth() + v.getAbsorptionAmount() - 6) + 3; // fall damage = blocks - 3; keeps 3 hearts
                int d = Math.max(3, Math.min(23, byHealth));
                if (m.water > 0 && worldTechs()) d = Math.max(d, 30); // it can water-MLG the landing
                return d;
            }
            public boolean canPillar() { return tier(m) >= 2 && m.blocks > 0 && worldTechs(); }
            public boolean canBridge() { return m.blocks > 0 && worldTechs(); }
            public double maxFallDamage() {
                Vindicator v = m.brain;
                double d = v.getHealth() + v.getAbsorptionAmount() - 6; // keeps 3 hearts
                if (m.water > 0 && worldTechs()) d = Math.max(d, 40); // it can water-MLG the landing
                return Math.max(0, Math.min(20, d));
            }
            public double penalty(long from, long to) {
                if (m.navBad.isEmpty()) return 0;
                Integer until = m.navBad.get(from * 31 + to);
                return until != null && until > m.t ? 40 : 0;
            }
        };
    }

    /** Where its feet are as a grid cell (on a slab / stair top = the cell above it). */
    static Block feetCell(LivingEntity v) {
        Block b = v.getLocation().add(0, 0.01, 0).getBlock();
        return blocks(b) ? b.getRelative(0, 1, 0) : b;
    }

    private long budgetTick = -1; private int budgetUsed;

    void plan(Mimic m, Block goal) {
        Vindicator v = m.brain;
        // every mimic shares a budget of nodes a tick: lots of them chasing at once never adds up to a lag spike
        if (budgetTick != clock) { budgetTick = clock; budgetUsed = 0; }
        if (budgetUsed > cfg("path-budget-per-tick", 8000) && m.path != null) return; // keeps its old path a tick longer
        Block f = feetCell(v);
        MimicNav nav = new MimicNav(v.getWorld(), navRules(m));
        MimicNav.Result r = nav.find(f.getX(), f.getY(), f.getZ(), goal.getX(), goal.getY(), goal.getZ(), goal.equals(m.stageGoal) && m.stageUntil > m.t ? 0.5 : 1.6, (int) cfg("path-nodes", 2500), (int) Math.min(96, range()));
        budgetUsed += r.expanded;
        m.path = r.steps; m.pathIdx = 0; m.pathAt = m.t; m.pathGoal = goal; m.navReach = r.reached;
        m.navBest = 1e9; m.navProgressAt = m.t;
        m.navBad.values().removeIf(u -> u <= m.t);
    }

    /** Goes and gets them along a planned path (replans as they move, when it's stuck, every 2 s). */
    private void navChase(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        if (m.navVanillaUntil > m.t) { Emotes.q(() -> v.getPathfinder().moveTo(tg, 1.4)); return; }
        if (m.stageUntil > m.t && m.stageGoal != null && feetCell(v).equals(m.stageGoal)) { m.stageUntil = 0; m.path = null; m.pathAt = -100; m.navReach = false; }
        Block goal = m.stageUntil > m.t && m.stageGoal != null ? m.stageGoal : interceptBlock(m, tg);
        boolean near = m.path != null && m.path.size() - m.pathIdx < 6;
        boolean repath = m.path == null || m.pathIdx >= m.path.size() || m.pathGoal == null || m.pathGoal.getWorld() != goal.getWorld()
                || m.pathGoal.getLocation().distanceSquared(goal.getLocation()) > (near ? 2.5 : 9) || m.t - m.pathAt > 40;
        if (repath && m.t - m.pathAt >= 6) {
            plan(m, goal);
            // the way round is far longer than the straight line (a long detour round a wall / river): a pearl over it
            double d = tg.getLocation().distance(v.getLocation());
            if (m.navReach && m.path != null && m.path.size() > d * 2.2 + 8 && d > 10 && d < 50 && m.pearlsLeft > 0 && m.t >= m.pearlReady && sees(v, tg)) { pearl(m, tg); return; }
        }
        if (m.hopUntil > m.t && !v.isOnGround()) { // mid wind-hop: steers onto their ledge
            Vector to = new Vector(tg.getLocation().getX() - v.getLocation().getX(), 0, tg.getLocation().getZ() - v.getLocation().getZ());
            Vector cur = v.getVelocity();
            if (to.lengthSquared() > 1e-4) {
                to.normalize();
                double hx = cur.getX() * 0.9 + to.getX() * 0.06, hz = cur.getZ() * 0.9 + to.getZ() * 0.06, hl = Math.hypot(hx, hz);
                if (hl > 0.32) { hx *= 0.32 / hl; hz *= 0.32 / hl; }
                if (cur.getY() > 0.1) { hx *= 0.4; hz *= 0.4; } // straight up first, over the edge on the way down
                Safe.vel(v, new Vector(hx, cur.getY(), hz));
            }
            return;
        }
        if (m.path != null && (!m.navReach || m.stageUntil > m.t) && m.stageUntil <= m.t && (reachTech(m, tg) || farShot(m, tg))) return;
        if (m.path == null || m.pathIdx >= m.path.size()) { // no step left (it's there, or there's no way at all): straight at them
            Vector to = tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
            if (to.lengthSquared() > 1e-4) move(m, to.normalize(), chaseSpeed(m), false, false);
            return;
        }
        followPath(m, tg);
    }

    /**
     * No way to walk to them (and nothing to dig with): the movement techs. WIND HOP (they're up a ledge close by: a wind
     * charge at its feet), WATER CLIMB (a wall in the way: water on the wall, swims up it, picks it up and places it higher),
     * PEARL (it sees them), WIND CHARGE + PEARL (it doesn't, or it's far: the pearl rides the charge's burst).
     */
    private boolean reachTech(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        if (m.t < m.reachTechReady || m.digBlock != null) return false;
        Location a = v.getLocation(), b = tg.getLocation();
        double dy = b.getY() - a.getY(), flat = Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ()), dist = a.distance(b);
        boolean ground = v.isOnGround(), sees = sees(v, tg);
        Vector to = new Vector(b.getX() - a.getX(), 0, b.getZ() - a.getZ());
        if (to.lengthSquared() < 1e-4) to = new Vector(1, 0, 0); // right under them
        to.normalize();
        boolean roof = blocks(a.getBlock().getRelative(0, 2, 0));
        // wind hop: up onto a ledge 2-7 blocks up, close by
        if (ground && m.wind > 0 && dy >= 1.8 && dy <= 7 && flat <= 6 && !roof) {
            m.wind--; m.reachTechReady = m.t + 30;
            hold(m, "wind"); m.lookDownUntil = m.t + 3; swing(m);
            v.getWorld().spawnParticle(Particle.GUST_EMITTER_SMALL, a, 1);
            v.getWorld().playSound(a, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1, 1);
            Safe.vel(v, to.clone().multiply(0.18).setY(Math.min(1.45, 0.75 + dy * 0.12)));
            v.setFallDistance(0); m.noFallUntil = m.t + 40; m.hopUntil = m.t + 40;
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 4L);
            return true;
        }
        // water climb: a wall straight ahead between it and them
        boolean climbable = openUp(a.getBlock(), (int) Math.ceil(dy) + 2) && edgeBeside(a.getBlock(), b.getBlockY() - 1);
        // under their floor (a roof over it) or out in the open: first to the nearest open spot right by their floor's edge, then up
        if (ground && dy >= 1.8 && flat <= 8 && !climbable && m.water > 0 && worldTechs() && m.stageUntil <= m.t) {
            Block st = stageSpot(m, a.getBlock(), (int) Math.ceil(dy) + 2, b.getBlockY() - 1);
            if (st != null) { m.stageGoal = st; m.stageUntil = m.t + 80; m.path = null; m.pathAt = -100; return false; }
        }
        if (ground && m.water > 0 && worldTechs() && dy >= 1.8 && flat <= 7 && climbable) {
            Block head = a.getBlock().getRelative(0, 1, 0);
            if (head.getType().isAir() && pour(m, head, Material.WATER, 200)) {
                m.climbWater = head; m.climbTicks = 0; m.reachTechReady = m.t + 40;
                hold(m, "water"); swing(m);
                v.getWorld().playSound(head.getLocation(), Sound.ITEM_BUCKET_EMPTY, 1, 1);
                return true;
            }
        }
        if (m.pearlsLeft <= 0 || m.t < m.pearlReady || dist < 5 || dist > 60) return false;
        if (sees) { m.reachTechReady = m.t + 40; pearl(m, tg); return true; }
        if (m.wind > 0 && flat >= 4 && flat <= 22) { // over the wall / up the cliff on a charged pearl
            m.reachTechReady = m.t + 60;
            pearlCatch(m, to.clone().multiply(Math.min(1.1, 0.12 + flat * 0.05)).setY(Math.min(1.6, 0.8 + Math.max(0, dy) * 0.09)), false);
            return true;
        }
        return false;
    }

    /** Nothing solid over this cell for `h` blocks (room to climb / hop straight up). */
    static boolean openUp(Block b, int h) {
        for (int i = 1; i <= h; i++) if (blocks(b.getRelative(0, i, 0))) return false;
        return true;
    }

    /** A block of their floor's level right beside this cell (something to step onto at the top of a climb). */
    static boolean edgeBeside(Block c, int floorY) {
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) if (c.getWorld().getBlockAt(c.getX() + d[0], floorY, c.getZ() + d[1]).getType().isSolid()) return true;
        return false;
    }

    /** The nearest cell (6 round) on its level, open overhead, best right beside the edge of their floor (`floorY`). */
    Block stageSpot(Mimic m, Block feet, int h, int floorY) {
        MimicNav nav = new MimicNav(feet.getWorld(), navRules(m));
        Block best = null; double bs = 1e9;
        for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
            Block c = feet.getRelative(dx, 0, dz);
            if (!nav.standable(c.getX(), c.getY(), c.getZ()) || !openUp(c, h)) continue;
            if (!edgeBeside(c, floorY)) continue;
            double sc = Math.hypot(dx, dz);
            if (sc < bs) { bs = sc; best = c; }
        }
        return best;
    }

    /** WATER CLIMB: swims up the water it placed, and keeps moving the water up above its head until it's over the top. */
    private void waterClimb(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        Block w = m.climbWater;
        Location at = v.getLocation();
        if (tg == null || ++m.climbTicks > 240 || w.getWorld() != v.getWorld() || w.getType() != Material.WATER) { endClimb(m); return; }
        Vector to = new Vector(tg.getLocation().getX() - at.getX(), 0, tg.getLocation().getZ() - at.getZ());
        if (to.lengthSquared() > 1e-4) to.normalize();
        Block feet0 = at.getBlock();
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) // the side their floor's on
            if (feet0.getWorld().getBlockAt(feet0.getX() + d[0], tg.getLocation().getBlockY() - 1, feet0.getZ() + d[1]).getType().isSolid()) { to = new Vector(d[0], 0, d[1]); break; }
        Block feet = at.getBlock();
        Block front = at.clone().add(to.getX() * 0.8, 0.01, to.getZ() * 0.8).getBlock();
        // over the top (level with their floor and the way in is clear): steps forward onto it and lets the water go
        if (at.getY() >= tg.getLocation().getY() - 0.3 && !blocks(front) && !blocks(front.getRelative(0, 1, 0))) {
            Safe.vel(v, to.clone().multiply(0.25).setY(0.25));
            endClimb(m);
            return;
        }
        if (blocks(feet.getRelative(0, 2, 0))) { endClimb(m); return; } // a ceiling: can't go higher
        // risen to the water: picks it up and places it a block higher (above its head)
        if (feet.getY() >= w.getY()) {
            TempBlock t = temp.get(w);
            if (t != null) { restore(t); m.water++; }
            Block next = feet.getRelative(0, 1, 0);
            if (next.getType().isAir() && pour(m, next, Material.WATER, 200)) { m.climbWater = next; swing(m); v.getWorld().playSound(next.getLocation(), Sound.ITEM_BUCKET_EMPTY, 0.7f, 1.1f); }
            else { endClimb(m); return; }
        }
        // swimming straight up its water column (held on the column: drifting off it = out of the water)
        Vector hold = new Vector(m.climbWater.getX() + 0.5 - at.getX(), 0, m.climbWater.getZ() + 0.5 - at.getZ()).multiply(0.3);
        Safe.vel(v, hold.setY(0.24));
        v.setFallDistance(0);
    }

    private void endClimb(Mimic m) {
        Block w = m.climbWater;
        m.climbWater = null;
        if (w == null) return;
        TempBlock t = temp.get(w);
        if (t != null && t.owner == m) { // scoops it back up behind it
            Bukkit.getScheduler().runTaskLater(plugin, () -> { TempBlock t2 = temp.get(w); if (t2 != null && t2.owner == m) { restore(t2); m.water++; } }, 6L);
        }
        hold(m, "sword");
        m.path = null;
    }

    /** Low on air (a third left): goes up until it's full again, like a player. */
    boolean needAir(Mimic m) {
        Vindicator v = m.brain;
        int air = v.getRemainingAir(), max = Math.max(1, v.getMaximumAir());
        if (v.hasPotionEffect(PotionEffectType.WATER_BREATHING) || v.hasPotionEffect(PotionEffectType.CONDUIT_POWER)) { m.surfacing = false; return false; }
        if (air < max / 3) m.surfacing = true;
        else if (air >= max - 10) m.surfacing = false;
        return m.surfacing;
    }

    /**
     * 1.2.95 swimming like a player: sprint-swims toward (dx, dz), dives or rises to `wantY` (NaN = up to the surface, as when
     * holding space), and goes up for air when it's running out.
     */
    Vector swimVel(Mimic m, Vector cur, double dx, double dz, double wantY, double push) {
        Vindicator v = m.brain;
        double y = v.getLocation().getY(), vy;
        if (needAir(m) || Double.isNaN(wantY)) vy = Math.min(0.14, cur.getY() + 0.05);
        else {
            double dy = wantY - y;
            double want = Math.max(-0.22, Math.min(0.16, dy * 0.12)); // dives a bit faster than it climbs (a player's crouch + look down)
            vy = cur.getY() * 0.6 + want * 0.4;
            if (Math.abs(dy) < 0.3) vy *= 0.5;
        }
        double h = Math.hypot(dx, dz);
        if (h > 1e-4) { dx /= h; dz /= h; }
        return new Vector(cur.getX() * 0.7 + dx * push * 1.3, vy, cur.getZ() * 0.7 + dz * push * 1.3);
    }

    /** Under water there (a water column over it, or the block itself is water). */
    boolean wetAt(Location l) {
        if (!l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return false;
        Block b = l.getBlock();
        return b.getType() == Material.WATER || b.getRelative(0, 1, 0).getType() == Material.WATER || b.getRelative(0, 2, 0).getType() == Material.WATER;
    }

    private void followPath(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        Location at = v.getLocation();
        boolean wet = v.isInWater();
        // reached a step (any of the next few: it can skip ahead)
        Block f = feetCell(v);
        for (int j = Math.min(m.path.size() - 1, m.pathIdx + 5); j >= m.pathIdx; j--) {
            MimicNav.Step s = m.path.get(j);
            boolean ok = s.x == f.getX() && s.z == f.getZ() && Math.abs(f.getY() - s.y) <= (s.type == MimicNav.Type.DROP || s.type == MimicNav.Type.DIGDOWN ? 0 : 1)
                    && (v.isOnGround() || wet || s.type == MimicNav.Type.CLIMB || s.type == MimicNav.Type.SWIM || s.type == MimicNav.Type.WALK && j < m.path.size() - 1);
            if (ok && s.type == MimicNav.Type.PILLAR && at.getY() < s.y - 0.05) ok = false;
            if (ok) { m.pathIdx = j + 1; m.navBest = 1e9; m.navProgressAt = m.t; m.navStuck = 0; break; }
        }
        if (v.isOnGround() && m.gapUntil > 0 && m.gapUntil - m.t < 27) m.gapUntil = 0;
        if (m.pathIdx >= m.path.size()) return;
        MimicNav.Step s = m.path.get(m.pathIdx);
        // stuck: no closer to this step for 25 ticks
        double dd = Math.hypot(s.cx() - at.getX(), s.cz() - at.getZ()) + Math.abs(s.y - at.getY()) * 0.5;
        if (dd < m.navBest - 0.15) { m.navBest = dd; m.navProgressAt = m.t; }
        else if (m.t - m.navProgressAt > 25) { navStuck(m, s); return; }
        double sp = chaseSpeed(m);
        // what's in the way gets mined first
        for (Block b : s.dig) {
            if (!blocks(b)) continue;
            Location c = b.getLocation().add(0.5, 0.5, 0.5);
            if (s.type == MimicNav.Type.DIGDOWN && Math.hypot(s.cx() - at.getX(), s.cz() - at.getZ()) > 0.25) { centre(m, s, 0.12); return; }
            if (c.distance(v.getEyeLocation()) <= 4.3 && v.isOnGround()) { if (!navDig(m, b)) navStuck(m, s); return; }
            break; // not close yet: walk up to it
        }
        // a closed door in the way: open it like a player
        if (s.door != null && openable(s.door) && s.door.getLocation().add(0.5, 0, 0.5).distance(at) < 2.6) {
            setOpen(s.door, true);
            swing(m);
            Vector look = s.door.getLocation().add(0.5, 0.5, 0.5).toVector().subtract(v.getEyeLocation().toVector());
            m.yaw = (float) Math.toDegrees(Math.atan2(-look.getX(), look.getZ()));
            opened.put(s.door, clock + 50);
            m.navProgressAt = m.t;
            return;
        }
        Vector cur = v.getVelocity();
        // mid parkour jump: holds W (a player's sprinting air speed) straight at the landing block
        if (m.gapUntil > m.t && m.gapStep != null && !v.isOnGround()) {
            Vector d = new Vector(m.gapStep.cx() - at.getX(), 0, m.gapStep.cz() - at.getZ());
            double l = d.length();
            if (l > 0.05) { d.multiply(Math.min(0.3, l * 0.5) / l); Safe.vel(v, d.setY(cur.getY())); }
            return;
        }
        switch (s.type) {
            case GAP -> {
                MimicNav.Step from = m.pathIdx > 0 ? m.path.get(m.pathIdx - 1) : null;
                double fx = from != null ? from.cx() : Math.floor(at.getX()) + 0.5, fz = from != null ? from.cz() : Math.floor(at.getZ()) + 0.5;
                Vector d = new Vector(s.cx() - fx, 0, s.cz() - fz);
                if (d.lengthSquared() < 1e-4 || !v.isOnGround()) break;
                d.normalize();
                double out = (at.getX() - fx) * d.getX() + (at.getZ() - fz) * d.getZ(); // how far toward the edge it's run
                double side = (at.getX() - fx) * -d.getZ() + (at.getZ() - fz) * d.getX();
                Vector run = d.clone().multiply(chaseSpeed(m)).add(new Vector(-d.getZ(), 0, d.getX()).multiply(-side * 0.4));
                if (out >= 0.15) { // at the edge: the sprint-jump
                    Safe.vel(v, d.clone().multiply(0.36).setY(0.42));
                    m.gapUntil = m.t + 30; m.gapStep = s; m.navProgressAt = m.t;
                    return;
                }
                Safe.vel(v, run.setY(cur.getY()));
                return;
            }
            case BRIDGE -> {
                Block under = v.getWorld().getBlockAt(s.x, s.y - 1, s.z);
                if (blocks(under)) break; // already down: walk on
                MimicNav.Step from = m.pathIdx > 0 ? m.path.get(m.pathIdx - 1) : null;
                double fx = from != null ? from.cx() : Math.floor(at.getX()) + 0.5, fz = from != null ? from.cz() : Math.floor(at.getZ()) + 0.5;
                Vector d = new Vector(s.cx() - fx, 0, s.cz() - fz);
                if (d.lengthSquared() > 1e-4) d.normalize();
                double out = (at.getX() - fx) * d.getX() + (at.getZ() - fz) * d.getZ();
                // speed-bridge: creeps (sneaking) to the edge facing back, places the block against it, steps on
                if (out < 0.2 && v.isOnGround()) { Safe.vel(v, d.clone().multiply(0.08).setY(cur.getY())); return; }
                Safe.vel(v, new Vector(0, cur.getY(), 0));
                if (m.blocks > 0 && bridgeBlock(under, m.gear.block)) {
                    m.blocks--; hold(m, "block"); swing(m); m.navProgressAt = m.t;
                    under.getWorld().playSound(under.getLocation(), Sound.BLOCK_STONE_PLACE, 1, 1);
                } else navStuck(m, s);
                return;
            }
            case PILLAR -> {
                if (Math.hypot(s.cx() - at.getX(), s.cz() - at.getZ()) > 0.3 && v.isOnGround()) { centre(m, s, 0.1); return; }
                if (v.isOnGround()) { m.pillarFrom = at.getBlock(); hold(m, "block"); Safe.vel(v, new Vector(0, 0.42, 0)); m.navProgressAt = m.t; return; }
                Block under = v.getWorld().getBlockAt(s.x, s.y - 1, s.z);
                if (at.getY() >= s.y - 0.05 && under.getType().isAir() && m.blocks > 0 && place(under, m.gear.block, 600)) {
                    m.blocks--; swing(m); under.getWorld().playSound(under.getLocation(), Sound.BLOCK_STONE_PLACE, 1, 1); m.navProgressAt = m.t;
                }
                return;
            }
            case CLIMB -> {
                MimicNav.Step nx = m.pathIdx + 1 < m.path.size() ? m.path.get(m.pathIdx + 1) : null;
                boolean top = nx != null && nx.type != MimicNav.Type.CLIMB && at.getY() >= s.y - 0.3;
                Vector h = top ? new Vector(nx.cx() - at.getX(), 0, nx.cz() - at.getZ()).normalize().multiply(0.2) // off the top onto the floor
                        : new Vector(s.cx() - at.getX(), 0, s.cz() - at.getZ()).multiply(0.15);
                Safe.vel(v, h.setY(s.y > at.getY() - 0.1 ? 0.2 : -0.15));
                return;
            }
            case SWIM -> {
                Vector d3 = new Vector(s.cx() - at.getX(), s.y + 0.1 - at.getY(), s.cz() - at.getZ());
                if (d3.lengthSquared() > 1e-4) d3.normalize();
                if (needAir(m)) { Safe.vel(v, swimVel(m, cur, d3.getX(), d3.getZ(), Double.NaN, 0.12)); return; }
                Safe.vel(v, cur.multiply(0.5).add(d3.multiply(0.2)).add(new Vector(0, 0.01, 0))); // sprint-swimming
                return;
            }
            default -> { }
        }
        if (wet) { // swimming toward a land step: head for it, up or DOWN to its height (1.2.95: it only ever rose)
            Vector d3 = new Vector(s.cx() - at.getX(), 0, s.cz() - at.getZ());
            if (d3.lengthSquared() > 1e-4) d3.normalize();
            Safe.vel(v, swimVel(m, cur, d3.getX(), d3.getZ(), s.y + 0.1, 0.12));
            return;
        }
        // aim: the furthest of the next few level WALK steps it can walk straight to (smooth lines, no zig-zag on the grid)
        double ax = s.cx(), az = s.cz();
        if (s.type == MimicNav.Type.WALK && s.door == null) {
            for (int j = Math.min(m.path.size() - 1, m.pathIdx + 4); j > m.pathIdx; j--) {
                MimicNav.Step t = m.path.get(j);
                boolean level = true;
                for (int k = m.pathIdx; k <= j; k++) { MimicNav.Step q = m.path.get(k); if (q.type != MimicNav.Type.WALK || q.y != s.y || q.door != null || !q.dig.isEmpty()) { level = false; break; } }
                if (level && walkLine(v, new Location(v.getWorld(), t.cx(), at.getY(), t.cz()))) { ax = t.cx(); az = t.cz(); break; }
            }
        }
        Vector dir = new Vector(ax - at.getX(), 0, az - at.getZ());
        double flat = dir.length();
        if (flat < 1e-3) return;
        dir.multiply(1 / flat);
        m.yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
        boolean climbing = Tag.CLIMBABLE.isTagged(at.getBlock().getType()) || Tag.CLIMBABLE.isTagged(at.clone().add(0, 1, 0).getBlock().getType());
        if (climbing && !v.isOnGround()) { // on a ladder / vine: steps off at the top onto the floor (full control, keeps climbing if it's higher)
            Safe.vel(v, new Vector(dir.getX() * 0.2, s.y > at.getY() - 0.2 ? 0.2 : Math.max(-0.15, cur.getY()), dir.getZ() * 0.2));
            return;
        }
        if (!v.isOnGround()) { // in the air: it can still steer a little (a player's air strafe)
            Safe.vel(v, new Vector(cur.getX() * 0.9 + dir.getX() * 0.035, cur.getY(), cur.getZ() * 0.9 + dir.getZ() * 0.035));
            return;
        }
        // a step up: jump onto it once it's close (stairs / slabs under a low ceiling: walks up them)
        if (s.type == MimicNav.Type.STEP && flat < 1.35) {
            Block over = at.getBlock().getRelative(0, 2, 0);
            if (!blocks(over)) { Safe.vel(v, new Vector(dir.getX() * sp * 0.95, 0.42, dir.getZ() * sp * 0.95)); return; } // sprint-jumps up the step, no slowing down
        }
        // sprint-jump down long straight runs, like a player
        if (s.type == MimicNav.Type.WALK && flat > 3.5 && openAir(v, dir) && random.nextDouble() < 0.3) {
            Safe.vel(v, new Vector(dir.getX() * (sp + 0.2), 0.42, dir.getZ() * (sp + 0.2)));
            return;
        }
        // careful near a step it has to land exactly (a drop / a door / the end): slows down
        double want = sp;
        if (flat < 1.2 && (s.type == MimicNav.Type.DROP || s.door != null || m.pathIdx == m.path.size() - 1)) want = Math.max(0.12, sp * flat);
        Block ahead = at.clone().add(dir.getX() * 0.8, 0.01, dir.getZ() * 0.8).getBlock();
        if (HAZARD.contains(ahead.getType()) || HAZARD.contains(ahead.getRelative(0, -1, 0).getType()) && s.type != MimicNav.Type.DROP) { Safe.vel(v, new Vector(0, cur.getY(), 0)); return; }
        Safe.vel(v, new Vector(cur.getX() * 0.35 + dir.getX() * want * 0.9, cur.getY(), cur.getZ() * 0.35 + dir.getZ() * want * 0.9));
    }

    private static boolean openAir(LivingEntity v, Vector dir) {
        Location at = v.getLocation();
        for (double d = 1; d <= 3; d += 1) {
            Block b = at.clone().add(dir.getX() * d, 0.01, dir.getZ() * d).getBlock();
            if (blocks(b) || blocks(b.getRelative(0, 1, 0)) || blocks(b.getRelative(0, 2, 0)) || !b.getRelative(0, -1, 0).getType().isSolid()) return false;
        }
        return !blocks(at.getBlock().getRelative(0, 2, 0));
    }

    private void centre(Mimic m, MimicNav.Step s, double sp) {
        Vindicator v = m.brain;
        Vector d = new Vector(s.cx() - v.getLocation().getX(), 0, s.cz() - v.getLocation().getZ());
        double l = d.length();
        if (l > 1e-3) d.multiply(Math.min(sp, l) / l);
        Safe.vel(v, d.setY(v.getVelocity().getY()));
    }

    /** A bridge block: into air, water or lava (a TempBlock: put back like its other blocks). */
    boolean bridgeBlock(Block b, Material mat) {
        Material t = b.getType();
        if (!worldTechs() || temp.containsKey(b) || !(t.isAir() || t == Material.WATER || t == Material.LAVA)) return false;
        TempBlock tb = new TempBlock();
        tb.block = b; tb.before = b.getBlockData().clone(); tb.placed = mat; tb.until = clock + 600;
        b.setType(mat, false);
        temp.put(b, tb);
        return true;
    }

    // ================================================================ combo techs (1.2.90)

    double spearReach() { return cfg("spear-reach", 4.5); }

    /** Ticks between lunges: the server's own lunge rule (pvp-limits.lunge-cooldown-seconds) applies to it like a player. */
    int lungeTicks(Mimic m) {
        double secs = plugin.getConfig().getDouble("pvp-limits.lunge-cooldown-seconds", 5);
        int server = (int) Math.round(secs * 20);
        return Math.max(tier(m) >= 3 ? 10 : 50, server); // LUNGE SWAP (hard+): from a no-cooldown item = no spear cooldown of its own
    }

    /** Lunge swap: a no-cooldown item in hand first, the spear the same tick (the spear's own cooldown never starts). */
    private void lungeSwap(Mimic m) {
        if (tier(m) >= 3) hold(m, m.wind > 0 ? "wind" : m.pearlsLeft > 0 ? "pearl" : "sword");
        hold(m, "spear");
    }

    /** SPEAR DASH: a long clear run at them = a Lunge forward to close the gap. */
    private boolean spearDash(Mimic m, LivingEntity tg, Vector to) {
        Vindicator v = m.brain;
        if (m.gear.spear == null || tier(m) < 2 || !v.isOnGround() || m.t < m.spearReady || m.t < m.dashReady) return false;
        int lunge = level(m.gear.spear, "lunge");
        double d = tg.getLocation().distance(v.getLocation());
        if (lunge <= 0 || d < 7 || d > 30 || !safePath(v, to, 5) || random.nextDouble() > 0.4) return false;
        lungeSwap(m);
        swing(m);
        v.getWorld().playSound(v.getLocation(), Sound.ITEM_TRIDENT_THROW, 0.8f, 1.4f);
        Safe.vel(v, to.clone().multiply(0.75 + 0.2 * lunge).setY(0.12));
        m.spearReady = m.t + lungeTicks(m); m.dashReady = m.t + 20;
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 4L);
        return true;
    }

    /** Swimming fight: straight at their chest in 3D (0.2 a tick, a player's sprint-swim), holding space to come up to them. */
    private void swimAt(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        Vector to = tg.getLocation().add(0, 0.9, 0).toVector().subtract(v.getLocation().add(0, 0.9, 0).toVector());
        double d = to.length();
        Vector cur = v.getVelocity();
        if (d < 1e-3) return;
        double keep = reachOf(m, tg) < 2.6 ? 0 : 1; // close enough: holds there (the hit comes from the swing code)
        Vector want = to.multiply(1 / d).multiply(0.2 * keep);
        double up = tg.getLocation().getY() - v.getLocation().getY();
        double vy = up > 0.5 ? Math.max(want.getY(), 0.12) : up < -1 ? Math.min(want.getY(), -0.08) : 0.02; // space up / dive down / float
        // their surface: it climbs out onto the bank toward them
        Block ahead = v.getLocation().add(want.getX() * 4, 0, want.getZ() * 4).getBlock();
        if (blocks(ahead) && !blocks(ahead.getRelative(0, 1, 0))) vy = Math.max(vy, 0.3);
        Safe.vel(v, new Vector(cur.getX() * 0.4 + want.getX(), vy, cur.getZ() * 0.4 + want.getZ()));
        m.sprintHit = false;
    }

    // ---- damage the way a PLAYER's lands: the game scales damage from mobs by difficulty (x1.5 on Hard, halved + 1 on
    // Easy) before plugins see it; a mimic's hits (melee, slams, arrows, its TNT) are a player's, so that's undone here
    static double undoDifficulty(Difficulty d, double scaled) {
        return switch (d) {
            case HARD -> scaled / 1.5;
            case EASY -> scaled <= 2 ? scaled : 2 * (scaled - 1);
            default -> scaled;
        };
    }

    final Set<UUID> hitLog = new HashSet<>(); // `/mimicplayer debug`: admins who see every hit's numbers

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMimicHitsPlayer(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p) || byBrain.isEmpty()) return;
        Entity d = e.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity sh) d = sh;
        if (d instanceof TNTPrimed t && t.getSource() != null) d = t.getSource();
        Mimic m = byBrain.get(d.getUniqueId());
        if (m == null) return;
        double before = e.getDamage();
        double fixed = plugin.getConfig().getBoolean("mimic-player.player-damage", true) ? undoDifficulty(p.getWorld().getDifficulty(), before) : before;
        e.setDamage(fixed);
        if (!hitLog.isEmpty()) {
            double hp0 = p.getHealth() + p.getAbsorptionAmount();
            String line = ChatColor.GRAY + "[Mimic hit] " + m.name + " -> " + p.getName() + ": " + String.format("%.2f", fixed) + " raw"
                    + (fixed != before ? " (the game's " + p.getWorld().getDifficulty().name().toLowerCase(Locale.ROOT) + " scaling " + String.format("%.2f", before) + " undone)" : "")
                    + " via " + e.getCause().name().toLowerCase(Locale.ROOT) + (m.lastHitInfo != null ? ", " + m.lastHitInfo : "");
            Bukkit.getScheduler().runTask(plugin, () -> {
                double lost = hp0 - (p.getHealth() + p.getAbsorptionAmount());
                String full = line + ChatColor.GRAY + " -> they lost " + String.format("%.2f", lost) + " after armour";
                for (UUID id : hitLog) { Player a = Bukkit.getPlayer(id); if (a != null) a.sendMessage(full); }
            });
        }
        m.lastHitInfo = null;
    }

    /** Whoever's bunched up round its target (a sweep catches them): living, not it, not its own parts, within 1 of the target. */
    List<LivingEntity> sweepTargets(Mimic m, LivingEntity tg) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : tg.getNearbyEntities(1.0, 0.25, 1.0)) {
            if (!(e instanceof LivingEntity le) || e == m.brain || e == m.body || byBody.containsKey(e.getUniqueId()) || byBrain.containsKey(e.getUniqueId())) continue;
            if (e instanceof org.bukkit.entity.ArmorStand || e.isInvulnerable() || e instanceof Player p && p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            if (le.getLocation().distance(m.brain.getLocation()) > 3.5) continue;
            out.add(le);
        }
        return out;
    }

    /**
     * The close-range techs, best first: D-TAP (they're low with a totem: a spear charge pops it, the mace / sword right after
     * lands on the fresh life), SPEAR STUN SLAM (shield up: the spear's reach knocks it down from 4 blocks, then the slam / axe),
     * BACKSTAB (shield up and no reach: lunges round behind them), WIND CHARGE CANCEL (they're coming down on it / jumping in for
     * a crit: a charge at their feet), GRAPPLE (rod them in for a free mace slam), SPEAR REACH SWAP (just out of sword reach: the
     * spear's reach with the sword's hit).
     */
    private boolean comboTech(Mimic m, LivingEntity tg, double dist, boolean sees, double reach, double ch) {
        Vindicator v = m.brain;
        int tr = tier(m);
        if (!sees) return false;
        Vector to = tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
        if (to.lengthSquared() < 1e-4) return false;
        to.normalize();
        boolean spear = m.gear.spear != null;
        // D-TAP / spearing
        boolean totem = mainHand(tg).getType() == Material.TOTEM_OF_UNDYING || offHand(tg).getType() == Material.TOTEM_OF_UNDYING;
        if (tr >= 3 && spear && totem && tg.getHealth() <= 14 && reach <= spearReach() && ch >= 0.9 && m.t >= m.dtapReady) {
            m.dtapReady = m.t + 60;
            hold(m, "spear"); m.sprintHit = true;
            strike(m, tg, ch);
            String next = m.gear.mace != null ? "mace" : "sword";
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!v.isValid() || !tg.isValid() || tg.isDead() || reachOf(m, tg) > spearReach()) { if (m.body.isValid()) hold(m, "sword"); return; }
                hold(m, next); swing(m);
                double dmg = (next.equals("mace") ? 6 : baseDamage(m.gear.weapon == null ? Material.IRON_SWORD : m.gear.weapon.getType())) * cfg("damage-multiplier", 1.0);
                striking = true;
                try { tg.setNoDamageTicks(0); tg.damage(dmg, v); } finally { striking = false; } // the second hit lands on the totem's fresh life
                combat(tg);
                m.lastHit = m.t;
                hold(m, "sword");
            }, 2L);
            return true;
        }
        // SPEAR STUN SLAM / SUN SLAM: their shield goes down from spear reach, then the slam or the axe
        if (tr >= 3 && spear && blocking(tg) && facing(tg, v) && reach <= spearReach() && m.t >= m.stunReady) {
            m.stunReady = m.t + 80;
            hold(m, "spear"); swing(m);
            breakShield(tg, 100);
            Emotes.q(() -> tg.clearActiveItem());
            tg.getWorld().playSound(tg.getLocation(), Sound.ITEM_SHIELD_BREAK, 1, 1);
            if (m.gear.mace != null && m.wind > 0 && v.isOnGround()) windJump(m, tg, null);
            else if (m.gear.axe != null) { hold(m, "axe"); m.axeUntil = m.t + 20; }
            else hold(m, "sword");
            return true;
        }
        // BACKSTAB: shield up, too far for the reach trick: a lunge round to their back
        if (tr >= 3 && spear && blocking(tg) && facing(tg, v) && v.isOnGround() && m.t >= m.spearReady && dist > 2.5 && dist < 7 && level(m.gear.spear, "lunge") > 0) {
            Vector look = tg.getLocation().getDirection().setY(0);
            if (look.lengthSquared() > 1e-4) {
                Location behind = tg.getLocation().subtract(look.normalize().multiply(1.3));
                Vector go = behind.toVector().subtract(v.getLocation().toVector()).setY(0);
                if (go.lengthSquared() > 1e-4 && safePath(v, go.clone().normalize(), Math.min(6, go.length()))) {
                    lungeSwap(m); swing(m);
                    v.getWorld().playSound(v.getLocation(), Sound.ITEM_TRIDENT_THROW, 0.8f, 1.4f);
                    Safe.vel(v, go.multiply(0.35).setY(0.12));
                    m.lungeUntil = m.t + 10; m.spearReady = m.t + lungeTicks(m);
                    return true;
                }
            }
        }
        // WIND CHARGE CANCEL: they're dropping on it (a mace slam) or jumping in for a crit it can't answer
        double dy = tg.getLocation().getY() - v.getLocation().getY();
        boolean slamming = !tg.isOnGround() && dy > 1.5 && tg.getFallDistance() > 1.5 && dist < 6;
        boolean critIn = !tg.isOnGround() && dist < 4.2 && dist > 2.2 && ch < 0.6;
        if (tr >= 3 && m.wind > 0 && m.t >= m.cancelReady && (slamming || critIn && random.nextDouble() < 0.3)) {
            m.wind--; m.cancelReady = m.t + 40;
            hold(m, "wind"); swing(m);
            windAt(m, tg, tg.getLocation().clone());
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 4L);
            return true;
        }
        // GRAPPLE: rod them in, then the wind-charge mace slam on top of them
        if (tr >= 3 && m.gear.rod && m.gear.mace != null && v.isOnGround() && dist > 5 && dist < 14 && m.t >= m.grappleReady && m.t >= m.maceReady) {
            m.grappleReady = m.t + 100;
            hold(m, "rod"); swing(m);
            v.getWorld().playSound(v.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 1, 1);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!tg.isValid() || !v.isValid() || tg.getWorld() != v.getWorld()) return;
                Vector pull = v.getLocation().toVector().subtract(tg.getLocation().toVector()).multiply(0.1);
                Safe.vel(tg, tg.getVelocity().add(pull.setY(Math.max(0.15, pull.getY()))));
                v.getWorld().playSound(v.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1, 1);
                combat(tg);
                if (m.wind > 0 && v.isOnGround()) windJump(m, tg, null); else hold(m, "mace");
            }, 6L);
            return true;
        }
        // SPEAR REACH SWAP: out of sword reach, in spear reach: the spear's reach, then (hard+) the sword's damage on the hit tick
        if (spear && tr >= 2 && reach > cfg("reach", 3.0) && reach <= spearReach() && ch >= m.skill.hitSelect()) {
            hold(m, "spear"); m.sprintHit = true;
            if (tr >= 3) hold(m, blocking(tg) && m.gear.axe != null ? "axe" : "sword");
            if (!whiff(m, tg)) strike(m, tg, ch);
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid() && !m.held.equals("sword")) hold(m, "sword"); }, 3L);
            return true;
        }
        return false;
    }

    /** A wind charge thrown at a spot (a real WindCharge entity; if the server can't spawn one, the burst's push itself). */
    void windAt(Mimic m, LivingEntity tg, Location spot) {
        Vindicator v = m.brain;
        Location eye = eye(m);
        Vector aim = spot.toVector().subtract(eye.toVector());
        try {
            org.bukkit.entity.WindCharge wc = v.getWorld().spawn(eye, org.bukkit.entity.WindCharge.class, e -> e.setShooter(v));
            Safe.vel(wc, aim.normalize().multiply(1.5));
        } catch (RuntimeException ex) {
            Vector push = tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
            if (push.lengthSquared() > 1e-4) Safe.vel(tg, tg.getVelocity().add(push.normalize().multiply(0.8).setY(0.5)));
        }
        v.getWorld().playSound(eye, Sound.ENTITY_WIND_CHARGE_THROW, 1, 1);
    }

    /**
     * Wind charges in the air: MOMENTUM BREAK (a big fall with no water to clutch: a charge at the ground just before it lands
     * kills the fall), WIND RESET (godlike: falling with them far off, a charge under it throws it back up and toward them).
     */
    private boolean windAir(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        if (m.wind <= 0 || v.isOnGround() || m.t < m.windBreakReady || tier(m) < 2) return false;
        Vector cur = v.getVelocity();
        if (cur.getY() > -0.5) return false;
        boolean slamOn = m.slam && m.gear.mace != null && tg != null && reachOf(m, tg) < 6;
        double safe = Math.max(4, v.getHealth() - 6 + 3);
        Block below = v.getLocation().getBlock();
        int gap = -1;
        for (int i = 1; i <= 3; i++) { if (below.getRelative(0, -i, 0).getType().isSolid()) { gap = i; break; } }
        boolean water = m.water > 0 && worldTechs();
        if (!slamOn && gap > 0 && gap <= 2 && v.getFallDistance() > safe && !water) { // MOMENTUM BREAK
            m.wind--; m.windBreakReady = m.t + 20;
            hold(m, "wind"); m.lookDownUntil = m.t + 3; swing(m);
            Location at = v.getLocation();
            v.getWorld().spawnParticle(Particle.GUST_EMITTER_SMALL, at, 1);
            v.getWorld().playSound(at, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1, 1);
            Safe.vel(v, new Vector(cur.getX() * 0.5, 0.55, cur.getZ() * 0.5));
            v.setFallDistance(0); m.noFallUntil = m.t + 10;
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 4L);
            return true;
        }
        if (!slamOn && tier(m) >= 4 && tg != null && m.wind >= 2 && random.nextDouble() < 0.08) { // WIND RESET
            Vector to = tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
            if (to.length() < 6) return false;
            m.wind--; m.windBreakReady = m.t + 30;
            hold(m, "wind"); swing(m);
            v.getWorld().playSound(v.getLocation(), Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1, 1);
            Safe.vel(v, to.normalize().multiply(0.55).setY(0.7));
            v.setFallDistance(0);
            if (m.gear.mace != null) { m.slam = true; m.dive = false; }
            return true;
        }
        return false;
    }

    /**
     * ELYTRA MACE / DIVE BOMB (`mimic-player.elytra-mace`, off by default): rockets up on the elytra, swaps the chestplate back on
     * over them and drops onto them for the slam. Phase 1 climbs (rockets) toward a spot 18 above them, phase 2 dives.
     */
    private boolean bombTick(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        if (m.t > m.bombUntil) { m.bombPhase = 0; land(m); return false; }
        Location aimAt = tg.getLocation().add(0, 18, 0);
        if (m.bombPhase == 1) {
            Vector d = aimAt.toVector().subtract(v.getLocation().toVector());
            if (d.length() < 4 || v.getLocation().getY() >= aimAt.getY() || v.getLocation().getY() > v.getWorld().getMaxHeight() - 12) { // over them: chestplate back on, dive
                m.bombPhase = 2; m.flying = false;
                m.body.getEquipment().setChestplate(m.wear[1]); armourFor(m, false);
                m.slam = true; m.dive = true; m.maceReady = m.t + 80;
                hold(m, "mace");
                return false;
            }
            Safe.vel(v, d.normalize().multiply(1.1));
            v.setFallDistance(0);
            return true;
        }
        if (v.isOnGround()) { m.bombPhase = 0; return false; }
        return false; // phase 2: maceTech's slam steers the dive
    }

    private boolean startBomb(Mimic m, LivingEntity tg, double dist) {
        Vindicator v = m.brain;
        if (!plugin.getConfig().getBoolean("mimic-player.elytra-mace", false) || !m.gear.elytra || m.rockets <= 0 || m.gear.mace == null || tier(m) < 3) return false;
        if (!v.isOnGround() || dist < 10 || dist > 40 || m.t < m.maceReady || !openUp(v.getLocation().getBlock(), 12) || random.nextDouble() > 0.3) return false;
        m.rockets--;
        m.bombPhase = 1; m.bombUntil = m.t + 100; m.flying = true;
        m.body.getEquipment().setChestplate(new ItemStack(Material.ELYTRA)); armourFor(m, true);
        hold(m, "rocket"); swing(m);
        v.getWorld().playSound(v.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 1, 1);
        Safe.vel(v, new Vector(0, 1.4, 0));
        return true;
    }

    /** Where they'll be by the time it gets there on a long chase (it cuts them off), else where they stand. */
    Block interceptBlock(Mimic m, LivingEntity tg) {
        Block now = standBlock(tg);
        double flat = Math.hypot(tg.getLocation().getX() - m.brain.getLocation().getX(), tg.getLocation().getZ() - m.brain.getLocation().getZ());
        if (flat < 12 || m.tgVel.lengthSquared() < 0.0025 || tier(m) < 2) return now;
        double lead = Math.min(30, flat / Math.max(0.2, chaseSpeed(m)) * 0.5);
        Location p = tg.getLocation().add(m.tgVel.clone().multiply(lead));
        MimicNav nav = new MimicNav(tg.getWorld(), navRules(m));
        for (int dy = 0; dy <= 3; dy++) for (int sg : new int[]{1, -1}) {
            int y = p.getBlockY() + dy * sg;
            if (nav.standable(p.getBlockX(), y, p.getBlockZ())) return tg.getWorld().getBlockAt(p.getBlockX(), y, p.getBlockZ());
        }
        return now;
    }

    /**
     * Can't get to them at all (up a pillar, over a moat...): it fights from where it is. A WIND CHARGE at them (knocks them off
     * their pillar), the ROD (hooks and reels them in), the BOW, snowballs; else it waits under them.
     */
    private boolean farShot(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        if (m.t < m.farShotReady || !sees(v, tg)) return false;
        double dist = tg.getLocation().distance(v.getLocation()), dy = tg.getLocation().getY() - v.getLocation().getY();
        Location eye = eye(m);
        Vector aim = tg.getLocation().add(0, 0.9, 0).toVector().add(m.tgVel.clone().multiply(Math.min(8, dist / 1.5))).subtract(eye.toVector());
        if (m.wind > 0 && dist <= 24 && dy >= 1.5) { // wind charge at their feet: off the pillar they come
            m.wind--; m.farShotReady = m.t + 30;
            hold(m, "wind"); swing(m);
            try {
                org.bukkit.entity.WindCharge wc = v.getWorld().spawn(eye, org.bukkit.entity.WindCharge.class, e -> e.setShooter(v));
                Safe.vel(wc, aim.clone().normalize().multiply(1.5));
            } catch (RuntimeException ex) { // no wind charge entity here: the burst itself
                Vector push = tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
                if (push.lengthSquared() > 1e-4) Safe.vel(tg, tg.getVelocity().add(push.normalize().multiply(0.9).setY(0.6)));
            }
            v.getWorld().playSound(eye, Sound.ENTITY_WIND_CHARGE_THROW, 1, 1);
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 4L);
            return true;
        }
        if (m.gear.rod && dist >= 4 && dist <= 20) { // hook + reel: pulls them toward it (vanilla: 0.1 x the gap)
            m.farShotReady = m.t + 40;
            hold(m, "rod"); swing(m);
            v.getWorld().playSound(eye, Sound.ENTITY_FISHING_BOBBER_THROW, 1, 1);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!tg.isValid() || !v.isValid() || tg.getWorld() != v.getWorld()) return;
                Vector pull = v.getLocation().toVector().subtract(tg.getLocation().toVector()).multiply(0.1);
                Safe.vel(tg, tg.getVelocity().add(pull.setY(Math.max(0.1, pull.getY()))));
                v.getWorld().playSound(v.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1, 1);
                combat(tg);
                if (m.body.isValid()) hold(m, "sword");
            }, 8L);
            return true;
        }
        if (m.gear.bow != null && (m.arrows > 0 || level(m.gear.bow, "infinity") > 0) && dist >= 5 && dist <= 40) {
            m.farShotReady = m.t + 50;
            m.drawUntil = m.t + 20 + random.nextInt(6);
            hold(m, "bow");
            Emotes.q(() -> m.body.startUsingItem(EquipmentSlot.HAND));
            return true;
        }
        if (m.throwables > 0 && dist <= 16 && m.t >= m.throwReady) { m.farShotReady = m.t + 15; snowball(m, tg); return true; }
        return false;
    }

    /** Runs away along a real path (round walls, through doors, down off ledges) to a spot ~18 blocks from them. */
    /**
     * 1.2.93 RUNNING AWAY: picks where to run like a player would. 16 ways x 2 distances, each a spot it can stand on,
     * scored by how far from THEM it ends up (never past them or toward them), whether they lose sight of it there (round a
     * corner, behind a hill: a big plus), how open it is (a dead end / a pit is a big minus), lower ground (drops break a
     * chase) and keeping its last pick. The best ones are planned on the walker; a path that runs past them is thrown out.
     * Replanned every 1.5 s, when it gets there, or when they cut it off.
     */
    private boolean navFlee(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        Location tl = tg.getLocation();
        boolean cutOff = m.fleeGoal != null && m.fleeGoal.getWorld() == tl.getWorld()
                && m.fleeGoal.getLocation().clone().add(0.5, 0, 0.5).distanceSquared(tl) < m.fleeGoal.getLocation().clone().add(0.5, 0, 0.5).distanceSquared(v.getLocation());
        boolean done = m.path == null || m.pathIdx >= m.path.size();
        if (m.fleeGoal == null || m.t - m.fleePlanAt > 30 || cutOff || done || m.fleeGoal.getWorld() != v.getWorld()) {
            if (m.t - m.fleePlanAt < 6 && m.fleeGoal != null && !done) { followPath(m, tg); return true; }
            m.fleePlanAt = m.t;
            List<Block> picks = fleeSpots(m, tg);
            Block chosen = null;
            for (int i = 0; i < Math.min(3, picks.size()) && chosen == null; i++) {
                plan(m, picks.get(i));
                if (m.path == null || m.path.isEmpty() || !m.navReach) continue;
                boolean safe = true;
                for (int k = 2; k < m.path.size() && safe; k++) {
                    MimicNav.Step st = m.path.get(k);
                    if (tl.distanceSquared(new Location(v.getWorld(), st.x + 0.5, st.y, st.z + 0.5)) < 6.25) safe = false; // runs past them
                }
                if (safe) chosen = picks.get(i);
            }
            if (chosen == null) { m.fleeGoal = null; m.path = null; return false; }
            m.fleeGoal = chosen;
        }
        if (m.path == null || m.pathIdx >= m.path.size()) { m.fleeGoal = null; return false; }
        followPath(m, tg);
        return true;
    }

    /** Places to run to, best first (see navFlee). */
    List<Block> fleeSpots(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        World w = v.getWorld();
        MimicNav nav = new MimicNav(w, navRules(m));
        Location me = v.getLocation(), them = tg.getLocation(), eye = tg.getEyeLocation();
        Vector toThem = them.toVector().subtract(me.toVector()).setY(0);
        if (toThem.lengthSquared() < 1e-4) toThem = new Vector(-1, 0, 0);
        toThem.normalize();
        List<Block> out = new ArrayList<>();
        Map<Block, Double> score = new HashMap<>();
        for (int i = 0; i < 16; i++) {
            double ang = Math.PI * 2 * i / 16;
            Vector dir = new Vector(Math.cos(ang), 0, Math.sin(ang));
            if (dir.dot(toThem) > 0.35) continue; // not toward them
            for (int r : new int[]{14, 22}) {
                int x = (int) Math.floor(me.getX() + dir.getX() * r), z = (int) Math.floor(me.getZ() + dir.getZ() * r);
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                Block c = null;
                for (int dy = 6; dy >= -10 && c == null; dy--) if (nav.standable(x, me.getBlockY() + dy, z)) c = w.getBlockAt(x, me.getBlockY() + dy, z);
                if (c == null || hazardNear(c)) continue;
                Location cl = c.getLocation().clone().add(0.5, 0, 0.5);
                double dMe = cl.distance(me), dThem = cl.distance(them);
                if (dThem < dMe + 3) continue; // they're closer to it than it is
                double sc = dThem - 0.5 * dMe;
                if (!clearSight(eye, cl.clone().add(0, 1.6, 0))) sc += 12; // out of their sight there
                int open = openness(nav, c);
                sc += open < 14 ? -25 : Math.min(open, 35) * 0.2;
                if (c.getY() < me.getY() - 2) sc += 2;
                if (m.fleeGoal != null && m.fleeGoal.getWorld() == w && m.fleeGoal.getLocation().distanceSquared(c.getLocation()) < 49) sc += 4;
                score.put(c, sc); out.add(c);
            }
        }
        out.sort(Comparator.comparingDouble(b -> -score.get(b)));
        return out;
    }

    /** Lava, fire, cactus, magma beside it. */
    static boolean hazardNear(Block c) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++)
            if (HAZARD.contains(c.getRelative(dx, dy, dz).getType())) return true;
        return false;
    }

    /** Nothing that blocks sight on the straight line between two points (a block walk; no ray tracing needed). */
    static boolean clearSight(Location a, Location b) {
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len < 1e-3) return true;
        d.multiply(1 / len);
        for (double t = 0.5; t < len - 0.3; t += 0.4) {
            Block x = a.getWorld().getBlockAt((int) Math.floor(a.getX() + d.getX() * t), (int) Math.floor(a.getY() + d.getY() * t), (int) Math.floor(a.getZ() + d.getZ() * t));
            if (x.getType().isOccluding()) return false;
        }
        return true;
    }

    /** How many of the 7x7 columns round a spot it could stand in (about its height): small = a dead end / a hole. */
    static int openness(MimicNav nav, Block c) {
        int n = 0;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++)
            for (int dy = -1; dy <= 1; dy++) if (nav.standable(c.getX() + dx, c.getY() + dy, c.getZ() + dz)) { n++; break; }
        return n;
    }

    /** Mines one block in its path (the right tool, or a hand for dirt / sand / leaves), at a player's speed. */
    private boolean navDig(Mimic m, Block b) {
        if (!diggable(b) || liquidNext(b)) return false; // 1.2.93: never lets lava / water into its tunnel
        ItemStack tool = toolFor(m, b);
        int bt = breakTicks(m, b);
        if (bt < 0) return false;
        if (m.digBlock != null && !m.digBlock.equals(b)) crack(m, m.digBlock, 0);
        m.digBlock = b;
        m.digTicks = 0; m.digProgress = 0;
        m.digNeed = Math.max(1, bt);
        hold(m, tool == null ? "sword" : tool == m.gear.pickaxe ? "pickaxe" : tool == m.gear.axe ? "axe" : "shovel");
        Safe.vel(m.brain, new Vector(0, m.brain.getVelocity().getY(), 0));
        m.navProgressAt = m.t;
        return true;
    }

    // ================================================================ running & covering (1.2.90)

    /**
     * While running from them: BLOCK TRAIL (blocks dropped in the gap behind it, feet then head: they have to go round or
     * break through) and WIND DASH (hard+: a charge behind it launches it forward and up).
     */
    private void runTricks(Mimic m, LivingEntity tg, Vector away) {
        Vindicator v = m.brain;
        double d = tg.getLocation().distance(v.getLocation());
        if (tier(m) >= 3 && m.wind > 0 && d < 6 && v.isOnGround() && m.t >= m.windRunReady && safePath(v, away, 5)) {
            m.wind--; m.windRunReady = m.t + 60;
            hold(m, "wind"); swing(m);
            v.getWorld().spawnParticle(Particle.GUST_EMITTER_SMALL, v.getLocation(), 1);
            v.getWorld().playSound(v.getLocation(), Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1, 1);
            Safe.vel(v, away.clone().multiply(0.9).setY(0.55));
            v.setFallDistance(0); m.noFallUntil = m.t + 30;
            return;
        }
        if (tier(m) < 2 || m.blocks <= 0 || !worldTechs() || d > 10 || m.t < m.trailReady) return;
        Location at = v.getLocation();
        Block behind = at.clone().subtract(away.clone().multiply(1.3)).add(0, 0.01, 0).getBlock();
        boolean placed = false;
        for (Block b : new Block[]{behind, behind.getRelative(0, 1, 0)}) {
            if (m.blocks <= 0 || !b.getType().isAir() || occupied(b, tg) || occupied(b, v)) continue;
            if (place(b, m.gear.block, 300)) { m.blocks--; placed = true; }
        }
        if (placed) {
            hold(m, "block"); swing(m);
            behind.getWorld().playSound(behind.getLocation(), Sound.BLOCK_STONE_PLACE, 1, 1);
            m.trailReady = m.t + (tier(m) >= 4 ? 4 : tier(m) >= 3 ? 6 : 10);
        }
    }

    /** Someone's hitbox is in this block (never builds into a body). */
    static boolean occupied(Block b, Entity e) {
        return e.getBoundingBox().overlaps(new org.bukkit.util.BoundingBox(b.getX(), b.getY(), b.getZ(), b.getX() + 1, b.getY() + 1, b.getZ() + 1));
    }

    /** COVER UP: plans the box (4 sides x 2 + a roof; the side facing them first). Needs the blocks for most of it. */
    private boolean startCover(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        if (!worldTechs() || !v.isOnGround()) return false;
        Block f = feetCell(v);
        Vector to = tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        List<int[]> order = new ArrayList<>(List.of(dirs));
        order.sort(Comparator.comparingDouble(d -> -(d[0] * to.getX() + d[1] * to.getZ()))); // facing them first
        List<Block> plan = new ArrayList<>();
        for (int[] d : order) for (int y = 0; y <= 1; y++) {
            Block b = f.getRelative(d[0], y, d[1]);
            if (b.getType().isAir() && !occupied(b, tg)) plan.add(b);
        }
        Block roof = f.getRelative(0, 2, 0);
        if (roof.getType().isAir()) plan.add(roof);
        if (plan.isEmpty() || m.blocks < Math.max(3, plan.size() - 1)) return false;
        m.coverPlan = plan; m.coverIdx = 0; m.coverPlaced.clear(); m.covering = true; m.covered = false;
        m.coverReady = m.t + 300;
        Safe.vel(v, new Vector((f.getX() + 0.5 - v.getLocation().getX()) * 0.5, v.getVelocity().getY(), (f.getZ() + 0.5 - v.getLocation().getZ()) * 0.5));
        hold(m, "block");
        return true;
    }

    /** One tick in the box: places the next block (1 a tick godlike / hard, every 2 below), heals, opens up toward them once healed. */
    private void coverTick(Mimic m, LivingEntity tg, double hp, boolean food) {
        Vindicator v = m.brain;
        Safe.vel(v, new Vector(0, v.getVelocity().getY(), 0));
        if (m.covering) {
            if (tier(m) < 3 && m.t % 2 == 1) return;
            while (m.coverIdx < m.coverPlan.size()) {
                Block b = m.coverPlan.get(m.coverIdx++);
                if (m.blocks <= 0) break;
                if (!b.getType().isAir() || tg != null && occupied(b, tg)) continue;
                if (place(b, m.gear.block, 600)) { m.blocks--; m.coverPlaced.add(b); swing(m); b.getWorld().playSound(b.getLocation(), Sound.BLOCK_STONE_PLACE, 1, 1); return; }
            }
            m.covering = false; m.covered = true; m.coverUntil = m.t + 240;
            hold(m, "sword");
            return;
        }
        // broken into (one of its walls gone with them right there): out, fight
        boolean breached = false;
        for (Block b : m.coverPlaced) if (b.getType().isAir()) breached = true;
        if (breached || hp >= 0.8 || m.t > m.coverUntil || !food && m.pots == 0 && hp > 0.5) { openCover(m, tg); return; }
        if (food && hp < 0.8) { eat(m); return; } // eats behind its walls
        if (m.pots > 0) drink(m);
    }

    /** Opens up: the blocks on their side (feet + head) go, the rest stay to be put back on their own. */
    private void openCover(Mimic m, LivingEntity tg) {
        m.covered = false; m.covering = false;
        Location at = m.brain.getLocation();
        for (Block b : m.coverPlaced) {
            if (b.getY() > at.getBlockY() + 1) continue; // the roof stays
            Vector to = tg != null ? tg.getLocation().toVector().subtract(at.toVector()).setY(0) : new Vector();
            Vector bd = new Vector(b.getX() + 0.5 - at.getX(), 0, b.getZ() + 0.5 - at.getZ());
            if (to.lengthSquared() < 1e-4 || bd.dot(to) > 0.3) {
                TempBlock t = temp.get(b);
                if (t != null) { restore(t); b.getWorld().playSound(b.getLocation(), b.getBlockData().getSoundGroup().getBreakSound(), 1, 1); }
            }
        }
        m.coverPlaced.clear();
        hold(m, "sword");
    }

    /** Boxed in: fewer than 8 cells it can walk to round it (a hole, a 1x1, a trap). */
    boolean trapped(Mimic m) {
        Vindicator v = m.brain;
        MimicNav nav = new MimicNav(v.getWorld(), navRules(m));
        Block f = feetCell(v);
        Set<Long> seen = new HashSet<>();
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{f.getX(), f.getY(), f.getZ()});
        seen.add(MimicNav.key(f.getX(), f.getY(), f.getZ()));
        while (!q.isEmpty() && seen.size() < 8) {
            int[] c = q.poll();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) for (int dy = -1; dy <= 1; dy++) {
                int x = c[0] + d[0], y = c[1] + dy, z = c[2] + d[1];
                if (Math.abs(x - f.getX()) > 4 || Math.abs(z - f.getZ()) > 4) continue;
                if (dy == 1 && !nav.standable(c[0], c[1] + 2, c[2]) && blocks(v.getWorld().getBlockAt(c[0], c[1] + 2, c[2]))) continue; // no head room to step up
                if (nav.standable(x, y, z) && seen.add(MimicNav.key(x, y, z))) q.add(new int[]{x, y, z});
            }
        }
        return seen.size() < 8;
    }

    /**
     * Getting out (stuck, or boxed in and being hit): a PEARL to them if it can see them (a solved throw), else MINES OUT toward
     * them (pickaxe / axe / shovel, dirt by hand), else a PEARL OUT to the nearest open spot it can land a pearl on.
     */
    boolean escape(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        if (m.t < m.escapeReady) return false;
        m.escapeReady = m.t + 30;
        if (tg != null && tg.getWorld() == v.getWorld() && m.pearlsLeft > 0 && m.t >= m.pearlReady && sees(v, tg) && tg.getLocation().distance(v.getLocation()) > 4
                && throwPearl(m, tg.getLocation(), true)) return true;
        // mine out: the head then feet block toward them (or toward its step)
        if (worldTechs() && plugin.getConfig().getBoolean("mimic-player.dig", true)) {
            Vector to = tg != null && tg.getWorld() == v.getWorld() ? tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0) : v.getLocation().getDirection().setY(0);
            if (to.lengthSquared() < 1e-4) to = new Vector(1, 0, 0);
            to.normalize();
            double up = tg != null && tg.getWorld() == v.getWorld() ? tg.getLocation().getY() - v.getLocation().getY() : 0;
            Block feet = v.getLocation().getBlock();
            int sx = Math.abs(to.getX()) > Math.abs(to.getZ()) ? (int) Math.signum(to.getX()) : 0, sz = sx == 0 ? (int) Math.signum(to.getZ()) : 0;
            Block side = feet.getRelative(sx, 0, sz);
            List<Block> tries = new ArrayList<>();
            if (up > 1.5) { tries.add(feet.getRelative(0, 2, 0)); tries.add(side.getRelative(0, 2, 0)); tries.add(side.getRelative(0, 1, 0)); } // up and out: a staircase
            else if (up < -1.5) tries.add(feet.getRelative(0, -1, 0)); // down
            tries.add(side.getRelative(0, 1, 0)); tries.add(side);
            for (Block b : tries) if (blocks(b) && navDig(m, b)) return true;
        }
        // pearl out to open ground
        if (m.pearlsLeft > 0 && m.t >= m.pearlReady) {
            MimicNav nav = new MimicNav(v.getWorld(), navRules(m));
            Location eye = eye(m);
            for (double r : new double[]{10, 14, 7, 18}) for (int a = 0; a < 360; a += 30) {
                double rad = Math.toRadians(a + (tg != null ? 0 : 15));
                int x = (int) Math.floor(v.getLocation().getX() + Math.cos(rad) * r), z = (int) Math.floor(v.getLocation().getZ() + Math.sin(rad) * r);
                for (int dy = 4; dy >= -4; dy--) {
                    int y = v.getLocation().getBlockY() + dy;
                    if (!nav.standable(x, y, z) || !openUp(v.getWorld().getBlockAt(x, y, z), 3)) continue;
                    Location spot = new Location(v.getWorld(), x + 0.5, y, z + 0.5);
                    Shot sh = solvePearl(v.getWorld(), eye, spot);
                    if (sh != null && sh.err() < 2 && throwPearl(m, spot, true)) return true;
                    break;
                }
            }
        }
        return false;
    }

    /** Stuck on a step: out of a wall if it's in one, mark that move as bad, replan; stuck again and again = vanilla's own pathfinder for a moment. */
    private void navStuck(Mimic m, MimicNav.Step s) {
        Vindicator v = m.brain;
        m.navStuck++;
        if (m.navStuck >= 2 && escape(m, m.target)) { m.path = null; m.pathAt = -100; m.navProgressAt = m.t; return; }
        Block f = feetCell(v);
        if (inWall(v)) unwall(m);
        else {
            m.navBad.put(MimicNav.key(f.getX(), f.getY(), f.getZ()) * 31 + MimicNav.key(s.x, s.y, s.z), m.t + 200);
            if (v.isOnGround()) {
                Vector d = new Vector(s.cx() - v.getLocation().getX(), 0, s.cz() - v.getLocation().getZ());
                if (d.lengthSquared() > 1e-4) d.normalize().multiply(0.2);
                Safe.vel(v, d.setY(0.42)); // a hop: a lip / a block edge it was catching on
            }
        }
        m.path = null; m.pathAt = -100; m.navBest = 1e9; m.navProgressAt = m.t;
        if (m.navStuck >= 4) { m.navVanillaUntil = m.t + 60; m.navStuck = 0; }
    }

    /** Its feet or head inside a solid block (pushed into a wall, a block placed on it). */
    static boolean inWall(LivingEntity v) {
        Location at = v.getLocation();
        for (double dy : new double[]{0.2, 1.5}) {
            Location p = at.clone().add(0, dy, 0);
            Block b = p.getBlock();
            if (!blocks(b)) continue;
            try { if (!b.getBoundingBox().contains(p.toVector())) continue; } catch (RuntimeException ignored) { } // a slab / stair it's standing on isn't a wall
            return true;
        }
        return false;
    }

    /** Out to the nearest cell its body fits in (2 blocks round). */
    void unwall(Mimic m) {
        Vindicator v = m.brain;
        MimicNav nav = new MimicNav(v.getWorld(), navRules(m));
        Block f = v.getLocation().getBlock();
        Block best = null; double bd = 1e9;
        for (int dx = -2; dx <= 2; dx++) for (int dy = -1; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++) {
            int x = f.getX() + dx, y = f.getY() + dy, z = f.getZ() + dz;
            if (!nav.standable(x, y, z)) continue;
            double d = dx * dx + dz * dz + dy * dy * 0.7;
            if (d < bd) { bd = d; best = v.getWorld().getBlockAt(x, y, z); }
        }
        if (best == null) return;
        Location to = new Location(v.getWorld(), best.getX() + 0.5, best.getY(), best.getZ() + 0.5, v.getLocation().getYaw(), v.getLocation().getPitch());
        v.teleport(to);
        Safe.vel(v, new Vector());
    }

    /**
     * Moves like a player: sprint speed on the ground (0.28 b/t + Speed), SPRINT-JUMPS across open ground (a jump with
     * the sprint boost, keeping its speed through the air), jumps up single blocks, only small corrections in the air.
     */
    private void move(Mimic m, Vector dir, double sp, boolean stepUp, boolean sprintJump) {
        Vindicator v = m.brain;
        Vector cur = v.getVelocity();
        if (v.isInWater()) { // swims like a player: toward where it's going, DOWN too (1.2.95), up for air when it runs low
            Safe.vel(v, swimVel(m, cur, dir.getX(), dir.getZ(), m.swimToT >= m.t - 2 ? m.swimToY : Double.NaN, 0.06));
            return;
        }
        if (openAhead(m, dir)) return;
        if (v.isOnGround()) {
            // never walks into lava / fire / off a cliff: the way it wants, else 45 / 90 / 135 degrees aside (its strafe side
            // first), the first one whose real next steps are clear; nothing clear = stops
            // going round something: it COMMITS to the side it picked for a second (no dithering at a pool's edge), until the
            // straight way is clear again
            if (m.detourUntil > m.t && clearLine(v, dir, 3.6)) m.detourUntil = 0;
            int sg = Integer.signum(m.detourDeg);
            int[] tries = m.detourUntil > m.t && sg != 0 ? new int[]{45 * sg, 90 * sg, 135 * sg, 0} // only ever its chosen side
                    : new int[]{0, 45, -45, 90, -90, 135, -135};
            boolean roof = v.getLocation().getBlock().getRelative(0, 2, 0).getType().isSolid();
            for (int deg : tries) {
                double r = Math.toRadians(m.detourUntil > m.t ? deg : deg * m.strafeDir), c = Math.cos(r), sn = Math.sin(r);
                Vector d = new Vector(dir.getX() * c - dir.getZ() * sn, 0, dir.getX() * sn + dir.getZ() * c);
                if (deg != 0 && !safeStep(v, d)) continue;
                if (stepUp && deg == 0) { if (clearMove(v, new Vector(d.getX() * sp, 0.42, d.getZ() * sp), 3)) { Safe.vel(v, new Vector(d.getX() * sp, 0.42, d.getZ() * sp)); return; } continue; }
                Block ahead = v.getLocation().clone().add(d.getX() * 1.6, 0, d.getZ() * 1.6).getBlock();
                boolean open = !ahead.getType().isSolid() && !ahead.getRelative(0, 1, 0).getType().isSolid() && ahead.getRelative(0, -1, 0).getType().isSolid() && !roof;
                Vector jump = new Vector(d.getX() * (sp + 0.2), 0.42, d.getZ() * (sp + 0.2));
                if (deg == 0 && sprintJump && open && safePath(v, d, 4.5) && clearMove(v, jump, 9) && random.nextDouble() < 0.6) { Safe.vel(v, jump); return; } // sprint-jump: +0.2 on take-off
                double keep = deg == 0 ? 0.4 : 0.1; // turning aside drops its old momentum
                Vector nv = new Vector(cur.getX() * keep + d.getX() * sp * 0.9, cur.getY(), cur.getZ() * keep + d.getZ() * sp * 0.9);
                if (clearMove(v, nv, 3)) {
                    if (deg != 0 && m.detourUntil <= m.t) { m.detourDeg = deg * m.strafeDir; m.detourUntil = m.t + 25; }
                    else if (deg != 0) m.detourUntil = Math.max(m.detourUntil, m.t + 10); // still blocked: keeps going round the same side
                    Safe.vel(v, nv); return;
                }
            }
            Safe.vel(v, new Vector(0, cur.getY(), 0));
        } else {
            // air: keeps its momentum (a player can't turn much mid-jump), a little steering; heading into lava = lets go of W
            Vector h = new Vector(cur.getX(), 0, cur.getZ());
            if (h.lengthSquared() > 1e-4 && !safePath(v, h.clone().normalize(), 1.8)) Safe.vel(v, new Vector(cur.getX() * 0.6 - dir.getX() * 0.02, cur.getY(), cur.getZ() * 0.6 - dir.getZ() * 0.02));
            else Safe.vel(v, new Vector(cur.getX() * 0.98 + dir.getX() * 0.02, cur.getY(), cur.getZ() * 0.98 + dir.getZ() * 0.02));
        }
    }

    /**
     * The last check before any step: where this velocity really takes it over the next ticks (its whole hitbox, corners
     * too, so it never clips the corner of a lava pool). Unsafe = it stops dead instead (keeps falling / jumping).
     */
    static boolean walk(LivingEntity v, Vector nv, int ticks) {
        if (clearMove(v, nv, ticks)) { Safe.vel(v, nv); return true; }
        Safe.vel(v, new Vector(0, nv.getY(), 0));
        return false;
    }

    static boolean clearMove(LivingEntity v, Vector nv, int ticks) {
        Location at = v.getLocation();
        Set<Block> now = underNow(at); // cells its hitbox already touches don't count (it can always step back out)
        Vector h = new Vector(nv.getX(), 0, nv.getZ());
        double gx = 0, gz = 0; // ground friction takes speed off each tick; close enough for a look-ahead
        for (int k = 1; k <= ticks && h.lengthSquared() > 1e-6; k++) {
            gx += h.getX() * Math.pow(0.6, k - 1); gz += h.getZ() * Math.pow(0.6, k - 1);
            for (double cx : new double[]{-0.3, 0, 0.3}) for (double cz : new double[]{-0.3, 0, 0.3}) {
                Block b = at.clone().add(gx + cx, 0.01, gz + cz).getBlock();
                if (!now.contains(b) && !cellSafe(b)) return false;
            }
        }
        return true;
    }

    static Set<Block> underNow(Location at) {
        Set<Block> s = new HashSet<>();
        for (double cx : new double[]{-0.3, 0.3}) for (double cz : new double[]{-0.3, 0.3}) s.add(at.clone().add(cx, 0.01, cz).getBlock());
        return s;
    }

    /** The straight way, its whole width, clear out to `far` (to know a detour is over). */
    static boolean clearLine(LivingEntity v, Vector dir, double far) {
        Location at = v.getLocation();
        Set<Block> now = underNow(at);
        for (double d = 0.5; d <= far; d += 0.5)
            for (double cx : new double[]{-0.3, 0, 0.3}) for (double cz : new double[]{-0.3, 0, 0.3}) {
                Block b = at.clone().add(dir.getX() * d + cx, 0.01, dir.getZ() * d + cz).getBlock();
                if (!now.contains(b) && !cellSafe(b)) return false;
            }
        return true;
    }

    static boolean cellSafe(Block b) {
        if (HAZARD.contains(b.getType()) || HAZARD.contains(b.getRelative(0, 1, 0).getType())) return false;
        if (b.getType().isSolid()) return true;
        for (int i = 1; i <= 4; i++) {
            Block d = b.getRelative(0, -i, 0);
            if (HAZARD.contains(d.getType())) return false;
            if (d.getType().isSolid() || d.getType() == Material.WATER) return true;
        }
        return false;
    }

    static final Set<Material> HAZARD = EnumSet.of(Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.CACTUS, Material.MAGMA_BLOCK,
            Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.WITHER_ROSE);

    /** Is the next step safe: no hazard at the feet or under them, and ground within 3 blocks down (no cliffs). */
    static boolean safeStep(LivingEntity v, Vector dir) { return safeStep(v, dir, 0.9); }

    /** Safe all the way out to `far` blocks (a sprint-jump's landing, a lunge). */
    static boolean safePath(LivingEntity v, Vector dir, double far) {
        for (double d = 0.9; d <= far + 0.01; d += 0.9) if (!safeStep(v, dir, d)) return false;
        return true;
    }

    static boolean safeStep(LivingEntity v, Vector dir, double dist) {
        Location at = v.getLocation().clone().add(dir.getX() * dist, 0, dir.getZ() * dist);
        Block feet = at.getBlock();
        if (HAZARD.contains(feet.getType()) || HAZARD.contains(feet.getRelative(0, 1, 0).getType())) return false;
        if (feet.getType().isSolid()) return !HAZARD.contains(feet.getRelative(0, 1, 0).getType()); // a step up
        for (int i = 1; i <= 4; i++) {
            Block b = feet.getRelative(0, -i, 0);
            if (HAZARD.contains(b.getType())) return false;
            if (b.getType().isSolid() || b.getType() == Material.WATER) return i <= 4;
        }
        return false;
    }

    /** One hit, the way a player's would land. */
    private void strike(Mimic m, LivingEntity tg, double charge) {
        Vindicator v = m.brain;
        ItemStack w = weaponHeld(m);
        boolean full = charge > 0.9; // vanilla: crits and sprint knockback both need a 90% swing
        if (!full) m.sprintHit = false;
        boolean crit = full && canCrit(m) && !m.sprintHit;
        double base = w == null ? 1 : baseDamage(w.getType());
        double sharp = level(w, "sharpness");
        PotionEffect str = v.getPotionEffect(PotionEffectType.STRENGTH), weak = v.getPotionEffect(PotionEffectType.WEAKNESS);
        base += (str == null ? 0 : 3 * (str.getAmplifier() + 1)) - (weak == null ? 0 : 4 * (weak.getAmplifier() + 1));
        base = Math.max(0.5, base);
        double dmg = base * (0.2 + 0.8 * charge * charge) * (crit ? 1.5 : 1) + (sharp > 0 ? 0.5 * sharp + 0.5 : 0) * charge; // the crit multiplies the attack, not Sharpness
        if (m.held.equals("spear")) dmg += Math.min(6, Math.hypot(v.getVelocity().getX(), v.getVelocity().getZ()) * 6) * charge; // a spear hits harder the faster you're going
        if (m.held.equals("trident") && wet(tg)) dmg += 2.5 * level(w, "impaling");
        dmg *= breachMult(tg, dmg, level(w, "breach")); // a Breach mace's ground hit: their armour counts for less
        dmg *= cfg("damage-multiplier", 1.0);
        swing(m);
        boolean axeOnShield = m.held.equals("axe") && blocking(tg) && facing(tg, v);
        if (!hitLog.isEmpty()) m.lastHitInfo = (w == null ? "fist" : w.getType().name().toLowerCase(Locale.ROOT)) + " base " + String.format("%.1f", base)
                + (str != null ? " (Strength " + (str.getAmplifier() + 1) + ")" : "") + ", charge " + String.format("%.2f", charge) + (crit ? ", CRIT x1.5" : "")
                + (sharp > 0 ? ", Sharpness " + (int) sharp : "") + (m.sprintHit ? ", sprint hit" : "") + ", reach " + String.format("%.2f", reachOf(m, tg));
        striking = true;
        try { tg.damage(dmg, v); } finally { striking = false; }
        combat(tg);
        if (axeOnShield) {
            breakShield(tg, 100);
            Emotes.q(() -> tg.clearActiveItem());
            tg.getWorld().playSound(tg.getLocation(), Sound.ITEM_SHIELD_BREAK, 1, 1);
            m.axeUntil = m.t + 4;
        }
        // vanilla bonus knockback ((Knockback level + 1 for a charged sprint hit) x 0.5, along the attacker's facing);
        // the base 0.4 comes from the hit itself. A sprint hit then stops its sprint and slows it (x0.6)
        int kbLvl = level(w, "knockback");
        double bonus = (kbLvl + (m.sprintHit ? 1 : 0)) * 0.5;
        if (bonus > 0 && !axeOnShield) {
            double res = 0;
            try { res = tg.getAttribute(Attribute.KNOCKBACK_RESISTANCE).getValue(); } catch (RuntimeException ex) { }
            double kStr = bonus * (1 - Math.max(0, Math.min(1, res)));
            double yaw = Math.toRadians(v.getLocation().getYaw());
            Vector dir = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
            Vector old = tg.getVelocity();
            if (kStr > 0) Safe.vel(tg, new Vector(old.getX() / 2 + dir.getX() * kStr, tg.isOnGround() ? Math.min(0.4, old.getY() / 2 + kStr) : old.getY(), old.getZ() / 2 + dir.getZ() * kStr));
            Vector mine = v.getVelocity();
            Safe.vel(v, new Vector(mine.getX() * 0.6, mine.getY(), mine.getZ() * 0.6));
        }
        if (m.sweepHit) { // vanilla sweep: 1 + attack damage x Sweeping Edge's ratio to each, a little knockback
            int se = level(w, "sweeping_edge");
            double sweep = 1 + dmg * (se / (se + 1.0));
            for (LivingEntity o : sweepTargets(m, tg)) {
                striking = true;
                try { o.damage(sweep, v); } finally { striking = false; }
                if (o instanceof Player op) combat(op);
                Vector away = o.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
                if (away.lengthSquared() > 1e-4) Safe.vel(o, o.getVelocity().add(away.normalize().multiply(0.4).setY(0.1)));
            }
            Location sl = v.getLocation().add(v.getLocation().getDirection().setY(0).multiply(1)).add(0, 1, 0);
            v.getWorld().spawnParticle(Particle.SWEEP_ATTACK, sl, 1);
            v.getWorld().playSound(sl, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1, 1);
        }
        int fire = level(w, "fire_aspect");
        if (fire > 0 && !axeOnShield) tg.setFireTicks(Math.max(tg.getFireTicks(), 80 * fire));
        Location l = tg.getLocation().add(0, 1, 0);
        if (crit) { tg.getWorld().spawnParticle(Particle.CRIT, l, 14, 0.3, 0.4, 0.3, 0.25); tg.getWorld().playSound(l, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1, 1); }
        else if (charge > 0.9) tg.getWorld().playSound(l, m.sprintHit ? Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK : Sound.ENTITY_PLAYER_ATTACK_STRONG, 1, 1);
        else tg.getWorld().playSound(l, Sound.ENTITY_PLAYER_ATTACK_WEAK, 1, 1);
        m.lastHit = m.t;
        m.comboGiven++; m.comboTaken = 0;
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
     * it, then the mace lands), BACKSLAM (lands behind a shield / someone facing it), PEARL CATCHES (vertical, double height,
     * sideways, mid-air), ATTRIBUTE SWAP (hard+: the sword's charge, the mace's hit), DENSITY / BREACH SWAP, AXE/SWORD TO MACE
     * SWAP (falling into reach). No elytra mace (off on Faultline).
     */
    private boolean maceTech(Mimic m, LivingEntity tg, double dist, boolean sees) {
        Vindicator v = m.brain;
        boolean ground = v.isOnGround();
        if (m.slam) {
            if (ground && m.t > m.maceReady - 50 && m.catchAt <= m.t) { m.slam = false; m.dive = false; hold(m, "sword"); return false; } // missed: landed
            if (m.catchAt == m.t) { pearlCatch(m, m.catchVel, true); return true; } // DOUBLE HEIGHT: the pearl catch on the way up
            // air control toward the target (a player steering a slam); BACKSLAM (normal+): shield up / facing it = it lands BEHIND them
            Location aimAt = tg.getLocation();
            if (tier(m) >= 2 && (blocking(tg) && m.gear.axe == null || tier(m) >= 3 && facing(tg, v))) {
                Vector back = tg.getLocation().getDirection().setY(0);
                if (back.lengthSquared() > 1e-4) aimAt = tg.getLocation().clone().subtract(back.normalize().multiply(1.4));
            }
            Vector to = aimAt.toVector().subtract(v.getLocation().toVector()).setY(0);
            Vector cur = v.getVelocity();
            if (to.lengthSquared() > 0.04) {
                to.normalize().multiply(m.dive && cur.getY() < 0 ? 0.9 : 0.32);
                Safe.vel(v, new Vector(cur.getX() * 0.7 + to.getX() * 0.3, m.dive && cur.getY() < 0 ? Math.min(cur.getY(), -0.9) : cur.getY(), cur.getZ() * 0.7 + to.getZ() * 0.3));
            }
            // ATTRIBUTE SWAP (hard+): the sword stays out until the hit's last tick (smash() swaps); below that, the mace comes out early
            if (cur.getY() < -0.1 && v.getFallDistance() > 1.5 && tier(m) < 3) hold(m, "mace");
            if (cur.getY() < -0.1 && v.getFallDistance() > 1.5 && reachOf(m, tg) <= (tier(m) >= 4 && m.gear.spear != null ? spearReach() : cfg("reach", 3.0))) smash(m, tg); // TRIPLE SWAP (godlike): the spear's reach, the mace's hit
            return true;
        }
        // sword/axe -> mace swap: already falling into reach
        if (!ground && v.getVelocity().getY() < -0.1 && v.getFallDistance() > 1.5 && reachOf(m, tg) <= cfg("reach", 3.0) && charge(m) > 0.5) {
            hold(m, "mace"); smash(m, tg); return true;
        }
        if (m.t < m.maceReady || !sees) return false;
        int tr = tier(m);
        boolean canCatch = tr >= 3 && m.pearlsLeft > 0 && m.wind > 0 && m.t >= m.pearlReady && m.t >= m.catchReady;
        // SIDEWAYS PEARL CATCH (hard+): they're 12-32 off = a pearl at 45 degrees + a wind charge behind it rockets it at them
        if (canCatch && dist > 12 && dist < 32 && random.nextDouble() < 0.1) {
            Vector to = intercept(m, tg, tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0).normalize(), dist);
            double h = Math.min(2.1, 0.07 * dist + 0.5);
            pearlCatch(m, to.clone().multiply(h).setY(0.55), true);
            return true;
        }
        // VERTICAL PEARL CATCH (hard+): pearl straight up + a wind charge = ~20 blocks up for a Density slam;
        // DOUBLE HEIGHT (godlike, 2+ wind charges): wind charge first, then the catch on the way up inherits it = ~40
        if (canCatch && ground && dist <= 7 && level(m.gear.mace, "density") > 0 && random.nextDouble() < 0.06 && !v.getLocation().getBlock().getRelative(0, 3, 0).getType().isSolid()) {
            Vector to = tg.getLocation().toVector().subtract(v.getLocation().toVector()).setY(0);
            Vector drift = to.lengthSquared() > 1e-4 ? to.normalize().multiply(0.12) : new Vector();
            if (tr >= 4 && m.wind >= 2) {
                windJump(m, tg, drift.clone().multiply(1.5));
                m.catchAt = m.t + 5; m.catchVel = drift.clone().setY(2.6);
                m.maceReady = m.t + 100;
            } else pearlCatch(m, drift.clone().setY(1.9), true);
            return true;
        }
        // WIND-CHARGE SLAM: look down, jump, wind charge at its feet
        if (ground && dist <= 5.5 && m.wind > 0 && random.nextDouble() < 0.08 + m.skill.jumpCrit() * 0.1) {
            windJump(m, tg, null);
            return true;
        }
        return false;
    }

    /**
     * GROUND BREACH SWAP (hard+): into heavy armour (15+ points) with a Breach mace, the hit swaps from the sword to the
     * mace on its last tick: the sword's charge, the mace's Breach. The sword's back 2 ticks later.
     */
    private void breachSwap(Mimic m, LivingEntity tg) {
        ItemStack b = m.gear.breachMace;
        if (b == null && m.gear.mace != null && level(m.gear.mace, "breach") > 0) b = m.gear.mace;
        if (b == null || tier(m) < 3 || armourPoints(tg) < 15 || !m.held.equals("sword")) return;
        hold(m, b == m.gear.breachMace ? "breach" : "mace");
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid() && (m.held.equals("breach") || m.held.equals("mace")) && !m.slam) hold(m, "sword"); }, 2L);
    }

    /** Look straight down, jump, wind charge at the feet: ~7-8 blocks up, straight into a slam. */
    private void windJump(Mimic m, LivingEntity tg, Vector drift) {
        Vindicator v = m.brain;
        {
            m.wind--;
            m.lookDownUntil = m.t + 3;
            hold(m, "wind");
            swing(m);
            Location feet = v.getLocation();
            v.getWorld().spawnParticle(Particle.GUST_EMITTER_SMALL, feet, 1);
            v.getWorld().playSound(feet, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1, 1);
            Vector to = tg.getLocation().toVector().subtract(feet.toVector()).setY(0);
            Vector out = drift != null ? drift : to.lengthSquared() > 1e-4 ? to.normalize().multiply(0.2) : new Vector();
            Safe.vel(v, out.setY(v.isOnGround() ? 1.4 : 1.25)); // jump + the charge at its feet
            v.setFallDistance(0); // a wind charge's knockback resets the fall it counts from
            m.slam = true; m.dive = false;
            m.stunSlam = blocking(tg) && m.gear.axe != null;
            m.maceReady = m.t + 60;
        }
    }

    /**
     * PEARL CATCH: a pearl thrown along `vel` and a wind charge right behind it: the burst rockets the pearl (it inherits its
     * thrower's speed), and the thrower is teleported riding that momentum. `vel` is where it ends up going.
     */
    private void pearlCatch(Mimic m, Vector vel, boolean slam) {
        Vindicator v = m.brain;
        if (m.pearlsLeft <= 0 || m.wind <= 0) return;
        m.pearlsLeft--; m.wind--;
        m.pearlReady = m.t + pearlTicks(); m.catchReady = m.t + 80; m.catchAt = 0;
        Location eye = eye(m);
        Vector look = vel.clone().normalize();
        m.lookDownUntil = 0;
        hold(m, "pearl");
        swing(m);
        v.getWorld().playSound(eye, Sound.ENTITY_ENDER_PEARL_THROW, 1, 1);
        EnderPearl fx = v.getWorld().spawn(eye.clone().add(look.clone().multiply(0.6)), EnderPearl.class, e -> { e.setShooter(v); e.addScoreboardTag("faultline_mimic_fx"); });
        Safe.vel(fx, look.clone().multiply(1.5));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!v.isValid()) { fx.remove(); return; }
            hold(m, "wind");
            swing(m);
            Location at = fx.isValid() ? fx.getLocation() : v.getLocation().clone().add(look.clone().multiply(2));
            fx.remove();
            if (at.getBlock().getType().isSolid() || at.getBlock().getRelative(0, 1, 0).getType().isSolid()) at = v.getLocation(); // never into a wall
            at.setYaw(v.getLocation().getYaw());
            at.getWorld().spawnParticle(Particle.GUST_EMITTER_SMALL, at, 1);
            at.getWorld().playSound(at, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1, 1);
            v.teleport(at);
            v.getWorld().playSound(at, Sound.ENTITY_PLAYER_TELEPORT, 1, 1);
            Safe.vel(v, vel);
            v.setFallDistance(0);
            m.noFallUntil = m.t + 5;
            if (slam) { m.slam = true; m.dive = false; m.maceReady = m.t + 80; }
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid() && m.slam && tier(m) < 3) hold(m, "mace"); else if (m.body.isValid()) hold(m, "sword"); }, 3L);
        }, 3L);
    }

    /**
     * MID-AIR PEARL CATCH (hard+): falling fast from a failed dive / a knock off a ledge and not about to land a hit = a pearl
     * straight up + a wind charge: back up into the sky, fall reset, ready to drop again.
     */
    private boolean airCatch(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        if (tier(m) < 3 || v.isOnGround() || v.getVelocity().getY() > -0.8 || v.getFallDistance() < 10 || m.pearlsLeft <= 0 || m.wind <= 0 || m.t < m.pearlReady || m.t < m.catchReady) return false;
        if (tg != null && m.gear.mace != null && reachTo(v, tg) < 5 && tg.getLocation().getY() < v.getLocation().getY()) return false; // about to land the slam: let it
        if (m.water > 0 && worldTechs() && v.getFallDistance() < 20) return false; // a water clutch will do
        pearlCatch(m, new Vector(0, 1.3, 0), m.gear.mace != null);
        return true;
    }

    private void smash(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        double fall = v.getFallDistance();
        // DENSITY / BREACH SWAP: a high slam (7+) = the Density mace; a low one into heavy armour = the Breach mace
        boolean breachIt = m.gear.breachMace != null && tier(m) >= 2 && (fall < 7 || level(m.gear.mace, "density") == 0) && armourPoints(tg) >= 15;
        ItemStack mace = breachIt ? m.gear.breachMace : m.gear.mace;
        // STUN SLAM: shield up? the axe breaks it first
        if ((m.stunSlam || blocking(tg)) && m.gear.axe != null && blocking(tg)) {
            hold(m, "axe");
            breakShield(tg, 100);
            Emotes.q(() -> tg.clearActiveItem());
            tg.getWorld().playSound(tg.getLocation(), Sound.ITEM_SHIELD_BREAK, 1, 1);
            hold(m, "mace");
        }
        m.stunSlam = false;
        // ATTRIBUTE SWAP (hard+): it swings with the SWORD's cooldown and swaps to the mace on the hit's last tick, so the
        // swing counts as fully charged; without it the mace's own 1.65 s cooldown scales the base hit down
        double c = tier(m) >= 3 ? charge(m, m.gear.weapon) : charge(m, mace);
        hold(m, breachIt ? "breach" : "mace");
        double dmg = 6 * (0.2 + 0.8 * c * c) * 1.5 + maceBonus(fall) + 0.5 * level(mace, "density") * fall;
        dmg *= breachMult(tg, dmg, level(mace, "breach")); // Breach: their armour counts for less
        dmg *= cfg("damage-multiplier", 1.0);
        swing(m);
        if (!hitLog.isEmpty()) m.lastHitInfo = "mace smash, fall " + String.format("%.1f", fall) + ", charge " + String.format("%.2f", c) + (level(mace, "density") > 0 ? ", Density " + level(mace, "density") : "") + (breachIt ? ", Breach mace" : "");
        striking = true;
        try { tg.damage(dmg, v); } finally { striking = false; }
        combat(tg);
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

    static final class TempBlock { Block block; org.bukkit.block.data.BlockData before; Material placed; int until; Mimic owner; } // owner = whose bucket it came out of
    final Map<Block, TempBlock> temp = new HashMap<>();
    private int clock;

    boolean worldTechs() { return plugin.getConfig().getBoolean("mimic-player.world-techs", true); }

    boolean place(Block b, Material mat, int ticks) {
        if (!worldTechs() || temp.containsKey(b) || !(b.getType().isAir())) return false;
        MimicNav.dirty(b.getWorld());
        TempBlock t = new TempBlock();
        t.block = b; t.before = b.getBlockData().clone(); t.placed = mat; t.until = clock + ticks;
        b.setType(mat, false);
        temp.put(b, t);
        return true;
    }

    /**
     * Empties one of its buckets (water / lava) into this block: the bucket is GONE until it scoops the liquid back up itself
     * when the TempBlock runs out. A player who scoops it first with an empty bucket takes it (they're draining it).
     */
    boolean pour(Mimic m, Block b, Material liquid, int ticks) {
        if (liquid == Material.WATER ? m.water <= 0 : m.lava <= 0) return false;
        if (!place(b, liquid, ticks)) return false;
        temp.get(b).owner = m;
        if (liquid == Material.WATER) m.water--; else m.lava--;
        return true;
    }

    void restore(TempBlock t) {
        MimicNav.dirty(t.block.getWorld());
        if (t.block.getType() == t.placed) t.block.setBlockData(t.before, false);
        temp.remove(t.block);
    }

    void tickTemp() {
        clock++; MimicNav.clock = clock;
        try { tickDoors(); } catch (RuntimeException ignored) { }
        if (temp.isEmpty()) return;
        for (TempBlock t : new ArrayList<>(temp.values())) if (clock >= t.until) {
            if (t.owner != null && t.block.getType() == t.placed) { // it scoops its own water / lava back up: bucket back
                if (t.placed == Material.WATER) t.owner.water++; else if (t.placed == Material.LAVA) t.owner.lava++;
            }
            restore(t);
            Sound s = t.placed == Material.LAVA ? Sound.ITEM_BUCKET_FILL_LAVA : t.placed == Material.WATER ? Sound.ITEM_BUCKET_FILL : null;
            if (s != null) t.block.getWorld().playSound(t.block.getLocation().clone().add(0.5, 0.5, 0.5), s, 0.8f, 1);
        }
    }

    void restoreAll() {
        for (TempBlock t : new ArrayList<>(temp.values())) restore(t);
    }

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
    private boolean uhcTech(Mimic m, LivingEntity tg, double dist, boolean sees) {
        Vindicator v = m.brain;
        if (!sees || !v.isOnGround()) return false;
        Block feet = tg.getLocation().getBlock();
        boolean fireproof = tg.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE);
        // ROD: from 4-9 blocks, a hook hit (a little knockback), then sprint in for the combo
        if (m.gear.rod && dist > 4 && dist < 9 && m.t >= m.rodReady && random.nextDouble() < 0.06) {
            m.rodReady = m.t + 50;
            hold(m, "rod");
            swing(m);
            Location a = eye(m), b = tg.getLocation().add(0, 1.2, 0);
            Vector step = b.toVector().subtract(a.toVector());
            double len = step.length();
            step.normalize().multiply(0.6);
            Location c = a.clone();
            for (double d = 0; d < len; d += 0.6) { c.add(step); c.getWorld().spawnParticle(Particle.CRIT, c, 1, 0, 0, 0, 0); }
            v.getWorld().playSound(a, Sound.ENTITY_FISHING_BOBBER_THROW, 1, 1);
            striking = true;
            try { tg.damage(0.01, v); } finally { striking = false; }
            combat(tg);
            Vector away = b.toVector().subtract(a.toVector()).setY(0);
            if (away.lengthSquared() > 1e-4) Safe.vel(tg, tg.getVelocity().add(away.normalize().multiply(0.25).setY(0.25)));
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 5L);
            m.sprintHit = true;
            return true;
        }
        if (!worldTechs()) return false;
        // LAVA at their feet
        if (m.lava > 0 && !fireproof && dist <= 4 && m.t >= m.lavaReady && tg.isOnGround() && solidBelow(feet) && random.nextDouble() < 0.05) {
            if (pour(m, feet, Material.LAVA, 30)) {
                m.lavaReady = m.t + 200;
                hold(m, "lava");
                swing(m);
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
                swing(m);
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
                swing(m);
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
                if (pour(m, below, Material.WATER, 12)) {
                    m.clutchUntil = m.t + 20;
                    hold(m, "water");
                    swing(m);
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
                swing(m);
                b.getWorld().playSound(b.getLocation(), Sound.BLOCK_STONE_PLACE, 1, 1);
                if (++m.pillarCount >= 3) { m.pillarReady = m.t + 300; m.pillarCount = 0; }
            }
        }
        return true;
    }

    /** They're up on a pillar / ledge over it: jump-places blocks under itself until it's level, like a player towering up. */
    private boolean towerUp(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        double up = tg.getLocation().getY() - v.getLocation().getY();
        double flat = Math.hypot(tg.getLocation().getX() - v.getLocation().getX(), tg.getLocation().getZ() - v.getLocation().getZ());
        if (tier(m) < 2 || m.blocks <= 0 || !worldTechs() || up < 2.5 || flat > 4 || !tg.getLocation().clone().add(0, -0.1, 0).getBlock().getType().isSolid()) return false;
        if (v.getLocation().getBlock().getRelative(0, 2, 0).getType().isSolid()) return false; // a ceiling: can't
        if (v.isOnGround()) { Safe.vel(v, new Vector(0, 0.42, 0)); hold(m, "block"); return true; }
        if (v.getVelocity().getY() < 0.1) {
            Block b = v.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.DOWN);
            if (place(b, m.gear.block, 600)) {
                m.blocks--;
                swing(m);
                b.getWorld().playSound(b.getLocation(), Sound.BLOCK_STONE_PLACE, 1, 1);
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
    public void onBucket(org.bukkit.event.player.PlayerBucketFillEvent e) {
        TempBlock t = temp.get(e.getBlockClicked());
        if (t == null) return;
        if (t.owner != null && (t.placed == Material.WATER || t.placed == Material.LAVA)) { // DRAINED: the player scooped its bucket
            temp.remove(e.getBlockClicked()); // stays theirs; the mimic never gets that bucket back
            if (t.owner.body.isValid() && t.owner.held.equals(t.placed == Material.WATER ? "water" : "lava")) hold(t.owner, "sword");
            return;
        }
        e.setCancelled(true); // anything else it placed isn't free loot
    }

    @EventHandler(ignoreCancelled = true)
    public void onTempBreak(org.bukkit.event.block.BlockBreakEvent e) {
        TempBlock t = temp.remove(e.getBlock());
        if (t != null) e.setDropItems(false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTempExplode(EntityExplodeEvent e) { if (!temp.isEmpty()) e.blockList().removeIf(temp::containsKey); }

    // ---- pot PvP (Diamond SMP / Neth OP): their own potions

    /** A buff it's missing (or about to lose) from a potion it really carries: splash = at its feet, drinkable = 1.6 s. */
    private boolean buff(Mimic m, double dist) {
        Vindicator v = m.brain;
        for (Iterator<ItemStack> it = m.buffs.iterator(); it.hasNext(); ) {
            ItemStack pot = it.next();
            if (pot.getType() == Material.POTION && dist < 10) continue; // a drink takes 1.6 s: not with them this close
            boolean need = false;
            for (PotionEffect pe : effects(pot)) {
                if (BAD.contains(pe.getType())) continue; // Turtle Master's Slowness etc: never a reason to drink it again
                if (pe.getType().equals(PotionEffectType.INSTANT_HEALTH)) { if (v.getHealth() / maxHp(v) < 0.5) need = true; continue; }
                PotionEffect has = v.getPotionEffect(pe.getType());
                // only once it's ALMOST done (last 3 s) or this one's stronger: no wasted pots
                if (has == null || has.getAmplifier() < pe.getAmplifier() || has.getDuration() < 60 && has.getDuration() != PotionEffect.INFINITE_DURATION) need = true;
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
                swing(m);
                v.getWorld().playSound(at, Sound.ENTITY_SPLASH_POTION_THROW, 1, 1);
            }
            return true;
        }
        return false;
    }

    /** Slowness / Weakness / Poison / Harming... thrown AT you (leading your movement), if it carries any and you don't have it yet. */
    private boolean debuff(Mimic m, LivingEntity tg) {
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
            swing(m);
            v.getWorld().playSound(eye, Sound.ENTITY_SPLASH_POTION_THROW, 1, 1);
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 5L);
            return true;
        }
        return false;
    }

    /** Snowballs / eggs at a sprinting enemy: breaks their sprint (and their combo). */
    private void snowball(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        m.throwables--;
        m.throwReady = m.t + 15;
        Location eye = eye(m);
        Vector aim = tg.getEyeLocation().toVector().add(tg.getVelocity().clone().multiply(3)).subtract(eye.toVector());
        aim.setY(aim.getY() + aim.length() * 0.04);
        Snowball sb = v.getWorld().spawn(eye, Snowball.class, e -> e.setShooter(v));
        Safe.vel(sb, aim.normalize().multiply(1.5));
        swing(m);
        v.getWorld().playSound(eye, Sound.ENTITY_SNOWBALL_THROW, 1, 1);
    }

    /** UHC: on fire without Fire Resistance = water at its own feet. */
    private boolean selfExtinguish(Mimic m) {
        Vindicator v = m.brain;
        if (v.getFireTicks() <= 20 || m.water <= 0 || v.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE) || !v.isOnGround()) return false;
        Block feet = v.getLocation().getBlock();
        if (!pour(m, feet, Material.WATER, 10)) return false;
        hold(m, "water");
        swing(m);
        v.setFireTicks(0);
        v.getWorld().playSound(feet.getLocation(), Sound.ITEM_BUCKET_EMPTY, 1, 1);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 8L);
        return true;
    }

    private void flee(Mimic m, LivingEntity tg) {
        Vindicator v = m.brain;
        Vector away = v.getLocation().toVector().subtract(tg.getLocation().toVector()).setY(0);
        if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
        away.normalize();
        double d = tg.getLocation().distance(v.getLocation());
        // 1.2.93: a planned run (round corners, out of sight, never into a dead end); straight away only when nothing plans
        if (!navFlee(m, tg)) {
            Block ahead = v.getLocation().clone().add(away.getX() * 0.8, 0, away.getZ() * 0.8).getBlock();
            boolean wall = ahead.getType().isSolid(), tall = ahead.getRelative(0, 1, 0).getType().isSolid();
            // CORNERED: nowhere to run and they're on it: turns and fights (a player doesn't run into a wall)
            if (wall && tall && d < 3.5 && !openOut(m)) { m.fleeUntil = 0; m.healRun = false; m.reactAt = m.t; return; }
            move(m, away, m.skill.speed() * 0.9, wall, true);
        } else if (m.path != null && m.pathIdx < m.path.size()) {
            MimicNav.Step st = m.path.get(Math.min(m.path.size() - 1, m.pathIdx + 1));
            Vector run = new Vector(st.x + 0.5 - v.getLocation().getX(), 0, st.z + 0.5 - v.getLocation().getZ());
            if (run.lengthSquared() > 1e-4) away = run.normalize(); // the trail / dash go the way it's really running
        }
        runTricks(m, tg, away);
        fleeTricks(m, tg, away, d);
        if (!m.held.equals("block") && !m.held.equals("wind") && !m.held.equals("web") && !m.held.equals("pearl")) hold(m, "sword");
        if (m.fleeUntil - m.t < 2 || v.getHealth() / maxHp(v) > 0.7) { m.fleeUntil = 0; m.healRun = false; }
    }

    /** Any open way out (a standable cell 3 off in some direction). */
    private boolean openOut(Mimic m) {
        MimicNav nav = new MimicNav(m.brain.getWorld(), navRules(m));
        Block f = feetCell(m.brain);
        for (int[] dd : new int[][]{{3, 0}, {-3, 0}, {0, 3}, {0, -3}, {2, 2}, {-2, 2}, {2, -2}, {-2, -2}})
            for (int dy = -2; dy <= 1; dy++) if (nav.standable(f.getX() + dd[0], f.getY() + dy, f.getZ() + dd[1])) return true;
        return false;
    }

    /**
     * 1.2.93 tricks on the run: a PEARL out when they catch up (to where it was running, if the throw lands there),
     * JUKES (hard+: a sudden cut to one side when they're right behind it), a COBWEB behind it (normal+), and it never
     * looks back while sprinting off.
     */
    private void fleeTricks(Mimic m, LivingEntity tg, Vector away, double d) {
        Vindicator v = m.brain;
        if (d < 3.2 && m.pearlsLeft > 0 && m.t >= m.pearlReady && (m.t - m.lastHurt < 15 || sprinting(tg))) {
            boolean ok = false;
            if (m.fleeGoal != null && m.fleeGoal.getLocation().distance(v.getLocation()) > 9) ok = throwPearl(m, m.fleeGoal.getLocation().clone().add(0.5, 0, 0.5), true);
            if (!ok) pearlOut(m, tg);
            m.fleeGoal = null; m.path = null;
            return;
        }
        if (tier(m) >= 3 && d < 4 && v.isOnGround() && m.t >= m.jukeReady && random.nextDouble() < 0.12) {
            Vector side = new Vector(-away.getZ(), 0, away.getX()).multiply(random.nextBoolean() ? 1 : -1);
            if (safePath(v, side, 2)) { Safe.vel(v, side.multiply(0.42).add(away.clone().multiply(0.1)).setY(0.2)); m.jukeReady = m.t + 30; }
        }
        if (tier(m) >= 2 && m.webs > 0 && d < 5 && d > 1.5 && v.isOnGround() && m.t >= m.fleeWebReady && worldTechs()) {
            Block behind = v.getLocation().clone().subtract(away.clone().multiply(1.4)).add(0, 0.2, 0).getBlock();
            if (behind.getType().isAir() && !occupied(behind, tg) && !occupied(behind, v) && place(behind, Material.COBWEB, 100)) {
                m.webs--; m.fleeWebReady = m.t + 80;
                hold(m, "web"); swing(m);
                behind.getWorld().playSound(behind.getLocation(), Sound.BLOCK_COBWEB_PLACE, 1, 1);
            }
        }
    }

    boolean facing(LivingEntity who, Entity at) {
        Mimic o = mimicOf(who);
        if (o != null && o.body.isValid()) who = o.body; // another mimic: its body shows where it looks
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

    /** What it's up to, in a few words (shown to admins spectating it). */
    String doing(Mimic m) {
        if (m.hunting && m.target == null) return m.travelNote != null ? m.travelNote : "travelling";
        if (m.covering || m.covered) return "boxed in, healing";
        if (m.eatUntil > m.t) return "eating";
        if (m.drinkUntil > m.t || m.potUntil > m.t) return "potting";
        if (m.fleeUntil > m.t) return m.healRun ? "running off to heal" : "running away";
        if (m.climbWater != null) return "water-climbing";
        if (m.digBlock != null) return "digging";
        if (m.target != null) return (m.team != 0 ? "mimic fight vs " : "fighting ") + nameOf(m.target) + " (" + Math.round(m.target.getLocation().distance(m.brain.getLocation())) + "m)";
        return "idle";
    }

    /** The way it's really moving (its velocity), or the next step of its path; null when it's standing still. */
    private Float runYaw(Mimic m) {
        Vector vel = m.brain.getVelocity();
        if (vel.getX() * vel.getX() + vel.getZ() * vel.getZ() > 0.0025) return (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
        if (m.path != null && m.pathIdx < m.path.size()) {
            MimicNav.Step st = m.path.get(m.pathIdx);
            double dx = st.x + 0.5 - m.brain.getLocation().getX(), dz = st.z + 0.5 - m.brain.getLocation().getZ();
            if (dx * dx + dz * dz > 0.04) return (float) Math.toDegrees(Math.atan2(-dx, dz));
        }
        return null;
    }

    private void follow(Mimic m) {
        // a swing pose that didn't get cleaned up (cut off by another move): the real weapon back, arm down
        if (m.t > m.poseUntil && m.t % 5 == 0) {
            ItemStack inHand = m.body.getEquipment().getItemInMainHand();
            if (inHand != null && inHand.getPersistentDataContainer().has(POSE_KEY)) {
                Emotes.q(() -> m.body.clearActiveItem());
                m.body.getEquipment().setItemInMainHand(unposed(m));
            }
        }
        Location l = m.brain.getLocation();
        Location to = l.clone();
        float pitch = l.getPitch();
        float wantYaw = m.yaw;
        if (m.target != null) {
            // aims at their chest (players do), with a little hand shake by skill
            Vector d = m.target.getLocation().add(0, 1.15, 0).toVector().subtract(m.brain.getEyeLocation().toVector());
            if (m.t % 3 == 0) {
                double sp = m.skill.spread();
                m.aimJx = (float) (m.aimJx * 0.5 + random.nextGaussian() * sp * 0.9);
                m.aimJy = (float) (m.aimJy * 0.5 + random.nextGaussian() * sp * 0.6);
            }
            if (d.lengthSquared() > 1e-4) {
                wantYaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ())) + m.aimJx;
                pitch = (float) -Math.toDegrees(Math.atan2(d.getY(), Math.hypot(d.getX(), d.getZ()))) + m.aimJy;
            }
            boolean glance = m.t % 40 < 6; // 1.2.93: running off it looks where it runs, with a glance back now and then
            if (m.fleeUntil > m.t && !glance) { Float run = runYaw(m); wantYaw = run != null ? run : wantYaw + 180; pitch = 5; }
            else if (m.potRunUntil - m.t > 1) wantYaw += 180;
        } else if (m.hunting || m.travelFly) { // 1.2.93: travelling = faces the way it's going (it used to keep looking round at random)
            Float run = runYaw(m);
            if (run != null) wantYaw = run;
            pitch = m.travelFly ? 10 : 4;
            if (m.travelFly && m.glide != null) { wantYaw = m.flyYaw; pitch = m.flyPitch; } // 1.2.95: its body looks where it flies
        } else { wantYaw = m.idleYaw; pitch = (float) (Math.sin(m.t * 0.03) * 12); }
        if (m.digBlock != null && m.digBlock.getWorld() == m.brain.getWorld()) { // 1.2.93: looks at the block it's mining
            Vector look = m.digBlock.getLocation().clone().add(0.5, 0.5, 0.5).toVector().subtract(m.brain.getEyeLocation().toVector());
            if (look.lengthSquared() > 1e-4) {
                wantYaw = (float) Math.toDegrees(Math.atan2(-look.getX(), look.getZ()));
                pitch = (float) -Math.toDegrees(Math.atan2(look.getY(), Math.hypot(look.getX(), look.getZ())));
            }
        }
        if (m.drinkUntil > m.t || m.lookDownUntil > m.t) pitch = 88;
        float diff = ((wantYaw - m.yaw) % 360 + 540) % 360 - 180;
        float rate = turnRate(m);
        m.yaw += Math.max(-rate, Math.min(rate, diff)); // a quick but not instant turn (faster for better players)
        to.setYaw(m.yaw);
        to.setPitch(pitch);
        // MOVE the body (velocity = the gap to the brain) instead of teleporting it every tick: the game only animates a
        // body's legs / arms from real movement. A teleport only when it's fallen behind (corners, pearls, other world).
        Location bl = m.body.getLocation();
        if (bl.getWorld() != l.getWorld() || bl.distanceSquared(l) > 0.6 * 0.6) m.body.teleport(to);
        else Safe.vel(m.body, l.toVector().subtract(bl.toVector()));
        float yaw = m.yaw, p = pitch;
        Emotes.q(() -> m.body.setRotation(yaw, p));
        Emotes.q(() -> m.body.setBodyYaw(yaw));
        Pose want = m.riptideUntil > m.t ? Pose.SPIN_ATTACK : m.flying || m.dive && m.slam ? Pose.FALL_FLYING : m.blockUntil > m.t && m.shieldOffUntil <= m.t ? Pose.SNEAKING : Pose.STANDING;
        Emotes.q(() -> { if (m.body.getPose() != want) m.body.setPose(want, true); });
        if (m.tag != null && m.tag.isValid()) m.tag.teleport(tagSpot(l).add(0, want == Pose.SNEAKING ? -0.3 : 0, 0));
        bodyTick(m);
    }

    /** Its player body on viewers' screens (MimicBody): what a player's model would show for what it's doing. */
    private void bodyTick(Mimic m) {
        if (!MimicBody.active(plugin)) {
            if (!m.bodyViewers.isEmpty()) { // switched off: the mannequin back for them
                for (UUID u : m.bodyViewers) { Player p = Bukkit.getPlayer(u); if (p != null) p.showEntity(plugin, m.body); }
                MimicBody.remove(plugin, m);
            }
            return;
        }
        Vindicator v = m.brain;
        Vector vel = v.getVelocity();
        double hs = Math.hypot(vel.getX(), vel.getZ());
        boolean gliding = m.travelFly ? m.glide != null : m.flying || m.dive && m.slam;
        boolean crouch = m.blockUntil > m.t && m.shieldOffUntil <= m.t;
        boolean water = v.isInWater();
        boolean swimming = water && !gliding && hs > 0.06 && v.getEyeLocation().getBlock().isLiquid();
        boolean sprinting = !crouch && !gliding && !usingSomething(m) && (hs > 0.18 || swimming);
        MimicBody.tick(plugin, m, new MimicBody.Look(sprinting, crouch, swimming, gliding, m.riptideUntil > m.t));
    }

    // ---- bow and pearls

    private void shoot(Mimic m, LivingEntity tg) {
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

    /** Ticks between pearls: the server's own pearl rule (pvp-limits.ender-pearl-cooldown-seconds, 7 s), like a player. */
    int pearlTicks() { return Math.max(20, (int) Math.round(plugin.getConfig().getDouble("pvp-limits.ender-pearl-cooldown-seconds", 7) * 20)); }

    record Shot(Vector vel, Location land, double err) { }

    /**
     * Where a pearl thrown with this velocity lands: vanilla's projectile physics (move, x0.99 drag, -0.03 gravity a tick),
     * stopping at the first solid block along the way (checked in quarter steps).
     */
    static Location pearlLands(World w, Location from, Vector vel0, int maxTicks) { return pearlLands(w, from, vel0, maxTicks, -1e9); }

    /** 1.2.95: stops once it's fallen below `floorY` (it can't come back up), and reads each block once. */
    static Location pearlLands(World w, Location from, Vector vel0, int maxTicks, double floorY) {
        double px = from.getX(), py = from.getY(), pz = from.getZ(), vx = vel0.getX(), vy = vel0.getY(), vz = vel0.getZ();
        int lx = Integer.MIN_VALUE, ly = 0, lz = 0, lcx = Integer.MIN_VALUE, lcz = 0;
        boolean lastSolid = false, lastWater = false;
        int minY = w.getMinHeight(), maxY = w.getMaxHeight();
        for (int t = 0; t < maxTicks; t++) {
            for (int k = 1; k <= 4; k++) {
                double qx = px + vx * k / 4.0, qy = py + vy * k / 4.0, qz = pz + vz * k / 4.0;
                if (qy < minY || qy < floorY && vy < 0) return null;
                int bx = (int) Math.floor(qx), by = (int) Math.floor(qy), bz = (int) Math.floor(qz);
                if (bx != lx || by != ly || bz != lz) {
                    lx = bx; ly = by; lz = bz;
                    if (bx >> 4 != lcx || bz >> 4 != lcz) { if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return null; lcx = bx >> 4; lcz = bz >> 4; }
                    if (by >= maxY) { lastSolid = false; lastWater = false; }
                    else {
                        Block b = w.getBlockAt(bx, by, bz);
                        Material mt = b.getType();
                        lastSolid = mt.isSolid() && !(b.getBlockData() instanceof org.bukkit.block.data.Openable o && o.isOpen());
                        lastWater = mt == Material.WATER;
                    }
                }
                if (lastSolid) return new Location(w, px + vx * (k - 1) / 4.0, py + vy * (k - 1) / 4.0, pz + vz * (k - 1) / 4.0);
                if (lastWater && t > 2) return new Location(w, qx, qy, qz); // pearls stop in water
            }
            px += vx; py += vy; pz += vz;
            vx *= 0.99; vy = vy * 0.99 - 0.03; vz *= 0.99;
        }
        return null;
    }

    /**
     * The throw (speed 1.5, a player's) that lands closest to `to`, straight at it. 1.2.95: every 5 degrees from -45 to 80, then
     * every degree round the two best (it was all 126 pitches, every time).
     */
    static Shot solvePearl(World w, Location from, Location to) {
        Vector flat = to.toVector().subtract(from.toVector()).setY(0);
        if (flat.lengthSquared() < 1e-4) flat = new Vector(0.001, 0, 0);
        flat.normalize();
        double floorY = Math.min(from.getY(), to.getY()) - 6;
        Shot best = null, second = null;
        double bestP = 0, secondP = 0;
        for (double pitch = -45; pitch <= 80; pitch += 5) {
            Shot sh = pearlShot(w, from, to, flat, pitch, floorY);
            if (sh == null) continue;
            if (best == null || sh.err() < best.err()) { second = best; secondP = bestP; best = sh; bestP = pitch; }
            else if (second == null || sh.err() < second.err()) { second = sh; secondP = pitch; }
        }
        if (best == null) return null;
        Shot out = best;
        for (double c : second != null ? new double[]{bestP, secondP} : new double[]{bestP})
            for (double pitch = c - 4; pitch <= c + 4; pitch += 1) {
                if (pitch == c || pitch < -45 || pitch > 80) continue;
                Shot sh = pearlShot(w, from, to, flat, pitch, floorY);
                if (sh != null && sh.err() < out.err() - 0.05) out = sh;
            }
        return out;
    }

    private static Shot pearlShot(World w, Location from, Location to, Vector flat, double pitch, double floorY) {
        double r = Math.toRadians(pitch);
        Vector vel = flat.clone().multiply(Math.cos(r) * 1.5).setY(Math.sin(r) * 1.5);
        Location land = pearlLands(w, from, vel, 160, floorY);
        return land == null ? null : new Shot(vel, land, land.distance(to));
    }

    private void throwPearl(Mimic m, Location target) { throwPearl(m, target, false); }

    /** Throws a pearl to land on `target`. `exact` = only when the solved throw lands within 2.5 of it (chasing someone). */
    private boolean throwPearl(Mimic m, Location target, boolean exact) {
        Vindicator v = m.brain;
        Location eye = eye(m);
        if (exact && m.t < m.pearlSolveAt) return false; // 1.2.95: a throw that didn't land isn't solved again every tick
        Shot shot = solvePearl(v.getWorld(), eye, target);
        if (exact && (shot == null || shot.err() > 2.5)) { m.pearlSolveAt = m.t + 10; return false; }
        m.pearlsLeft--;
        m.pearlReady = m.t + pearlTicks();
        Vector aim = target.toVector().subtract(eye.toVector());
        double d = Math.hypot(aim.getX(), aim.getZ());
        Vector vel = shot != null ? shot.vel() : aim.clone().setY(0).normalize().multiply(Math.min(1.6, 0.05 * d + 0.6)).setY(Math.min(0.9, 0.2 + d * 0.022));
        if (vel.lengthSquared() > 1e-4) { // looks along the throw
            m.yaw = (float) Math.toDegrees(Math.atan2(-vel.getX(), vel.getZ()));
        }
        EnderPearl p = v.getWorld().spawn(eye, EnderPearl.class, e -> { e.setShooter(v); e.addScoreboardTag(TAG); });
        Safe.vel(p, vel);
        pearls.add(p.getUniqueId());
        hold(m, "pearl");
        swing(m);
        v.getWorld().playSound(v.getLocation(), Sound.ENTITY_ENDER_PEARL_THROW, 1, 1);
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (m.body.isValid()) hold(m, "sword"); }, 6L);
        return true;
    }

    private void pearl(Mimic m, LivingEntity tg) { throwPearl(m, tg.getLocation(), true); }

    /** 1.2.93: a pearl to the best place to run to (out of their sight, away from them, not a dead end), else straight away. */
    private void pearlOut(Mimic m, LivingEntity tg) {
        try {
            for (Block b : fleeSpots(m, tg)) {
                if (b.getLocation().distance(m.brain.getLocation()) < 12) continue;
                if (throwPearl(m, b.getLocation().clone().add(0.5, 0.1, 0.5), true)) { m.fleeGoal = null; m.path = null; return; }
            }
        } catch (RuntimeException ignored) { }
        pearlAway(m, tg);
    }

    private void pearlAway(Mimic m, LivingEntity tg) {
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
        for (Mimic o : byBrain.values()) if (o.target == v && tier(o) >= 2) { o.followAt = at.clone(); o.followT = o.t + o.skill.reaction() + 2; } // a mimic foe pearled
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

    /**
     * 1.2.95 safety net for the player body: it shares the hidden mannequin's entity id, so a click on it is a click on the
     * mannequin. Should the server ever refuse that (an entity hidden from the clicker), a swing whose look ray hits the body
     * with no real attack on a mimic that tick is turned into one (`Player#attack`, a full vanilla hit).
     */
    private final Map<UUID, Integer> attackedAt = new HashMap<>();

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAttackPacket(io.papermc.paper.event.player.PrePlayerAttackEntityEvent e) {
        UUID id = e.getAttacked().getUniqueId();
        if (byBody.containsKey(id) || byBrain.containsKey(id)) attackedAt.put(e.getPlayer().getUniqueId(), clock);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwingAtBody(org.bukkit.event.player.PlayerAnimationEvent e) {
        if (e.getAnimationType() != org.bukkit.event.player.PlayerAnimationType.ARM_SWING || byBrain.isEmpty() || !MimicBody.active(plugin)) return;
        Player p = e.getPlayer();
        Integer last = attackedAt.get(p.getUniqueId());
        if (last != null && clock - last <= 1) return;
        Location eye = p.getEyeLocation();
        double reach = 3.0;
        try { AttributeInstance ai = p.getAttribute(Attribute.ENTITY_INTERACTION_RANGE); if (ai != null) reach = ai.getValue(); } catch (Throwable ignored) { }
        for (Mimic m : byBrain.values()) {
            if (!m.bodyViewers.contains(p.getUniqueId()) || !m.body.isValid() || m.body.getWorld() != p.getWorld()) continue;
            org.bukkit.util.RayTraceResult hit = m.body.getBoundingBox().clone().expand(0.1).rayTrace(eye.toVector(), eye.getDirection(), reach);
            if (hit == null) continue;
            double d = hit.getHitPosition().distance(eye.toVector());
            if (p.rayTraceBlocks(d) != null) continue;
            attackedAt.put(p.getUniqueId(), clock);
            p.attack(m.body);
            return;
        }
    }

    // 1.2.95: the walker's shared cell cache forgets a world when its blocks change
    private void blocksChanged(World w) { if (!byBrain.isEmpty()) MimicNav.dirty(w); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onNavPlace(org.bukkit.event.block.BlockPlaceEvent e) { blocksChanged(e.getBlock().getWorld()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onNavBreak(org.bukkit.event.block.BlockBreakEvent e) { blocksChanged(e.getBlock().getWorld()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onNavBucket(org.bukkit.event.player.PlayerBucketEmptyEvent e) { blocksChanged(e.getBlock().getWorld()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onNavBoom(EntityExplodeEvent e) { blocksChanged(e.getLocation().getWorld()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onNavBlockBoom(org.bukkit.event.block.BlockExplodeEvent e) { blocksChanged(e.getBlock().getWorld()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onNavPush(org.bukkit.event.block.BlockPistonExtendEvent e) { blocksChanged(e.getBlock().getWorld()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onNavPull(org.bukkit.event.block.BlockPistonRetractEvent e) { blocksChanged(e.getBlock().getWorld()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onNavUse(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getClickedBlock() != null && e.getClickedBlock().getBlockData() instanceof org.bukkit.block.data.Openable) blocksChanged(e.getClickedBlock().getWorld());
    }

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
                boolean axe = by != null && by.getInventory().getItemInMainHand().getType().name().endsWith("_AXE") || att != null && att.held.equals("axe");
                if (!(e.getDamager() instanceof Projectile) && axe) {
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
        Mimic shooterMimic = e.getDamager() instanceof Projectile pj && pj.getShooter() instanceof LivingEntity sh ? byBrain.get(sh.getUniqueId()) : null;
        if (att != null) m.lastHitBy = att; else if (shooterMimic != null) m.lastHitBy = shooterMimic;
        { Player hitter = TotemLimit.playerBehind(e.getDamager()); if (hitter != null) combat(hitter); } // hitting a mimic puts YOU in combat too
        if (!(e.getDamager() instanceof Projectile)) { m.comboTaken++; m.comboGiven = 0; }
        Player by = TotemLimit.playerBehind(e.getDamager()); // someone else hit it: it turns on them, like a player would
        if (m.team == 0 && by != null && by != m.target && (by.getGameMode() == GameMode.SURVIVAL || by.getGameMode() == GameMode.ADVENTURE)
                && (m.target == null || random.nextDouble() < 0.6)) { m.target = by; m.reactAt = m.t + Math.max(1, m.skill.reaction() / 2); }
        if (TotemLimit.playerBehind(e.getDamager()) != null && m.t >= m.escapeReady && !m.covered && !m.covering && trapped(m)) m.escapeAt = m.t + 1; // boxed in and getting hit: out
        if (!(e.getDamager() instanceof Projectile) && m.brain.isOnGround()) {
            if (tier(m) >= 3 && random.nextDouble() < 0.35) m.scritAt = m.t + 1; // S-crit: back out, crit them coming in
            else if (random.nextDouble() < m.skill.jumpCrit()) m.jumpReset = m.t + 1; // jump reset: less knockback
        }
        Emotes.q(() -> m.body.playHurtAnimation(0));
        MimicBody.hurt(plugin, m);
        m.brain.getWorld().playSound(m.brain.getLocation(), Sound.ENTITY_PLAYER_HURT, 1, 1);
        if (m.team == 0 && TotemLimit.playerBehind(e.getDamager()) instanceof Player p && m.target != p && p.getLocation().distanceSquared(m.brain.getLocation()) < 400) m.target = p; // turns on whoever hits it
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
        MimicBody.hurt(plugin, m);
        Bukkit.getScheduler().runTaskLater(plugin, () -> MimicBody.remove(plugin, m), 10L); // falls, then it's gone
        releaseTickets(m);
        if (m.tabId != null && plugin.getConfig().getBoolean("mimic-player.fake-join", true)) { // a player's death line, then they log off
            Player killer = e.getEntity().getKiller();
            Mimic mk = killer == null && m.lastHitBy != null && m.t - m.lastHurt < 100 ? m.lastHitBy : null;
            announce(killer != null
                    ? Component.translatable("death.attack.player", Component.text(m.name), killer.displayName())
                    : mk != null ? Component.translatable("death.attack.player", Component.text(m.name), Component.text(mk.name))
                    : Component.translatable("death.attack.generic", Component.text(m.name)));
            Bukkit.getScheduler().runTaskLater(plugin, () -> leaveGame(m), 40L);
        }
        e.getDrops().clear();
        e.setDroppedExp(plugin.getConfig().getInt("mimic-player.xp", 0));
        if (plugin.getConfig().getBoolean("mimic-player.drop-inventory", true)) { // killed = it drops everything it had left, like a player
            e.getDrops().addAll(inventory(m));
            e.setDroppedExp(e.getDroppedExp() + Math.min(m.xpPool, 100) * 7 / 10 + 0); // a player drops 7 a level, capped at 100
        }
        for (String s : plugin.getConfig().getStringList("mimic-player.drops")) {
            try { String[] p = s.split(":"); e.getDrops().add(new ItemStack(Material.valueOf(p[0].toUpperCase(Locale.ROOT)), p.length > 1 ? Integer.parseInt(p[1]) : 1)); }
            catch (RuntimeException ignored) { }
        }
        fightDeath(m);
        m.body.getWorld().playSound(m.body.getLocation(), Sound.ENTITY_PLAYER_DEATH, 1, 1);
        m.body.getWorld().spawnParticle(Particle.POOF, m.body.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
        byBrain.remove(m.brain.getUniqueId());
        byBody.remove(m.body.getUniqueId());
        try { m.body.remove(); } catch (RuntimeException ignored) { }
        if (m.tag != null && m.tag.isValid()) m.tag.remove();
    }

    /** What it has left: its armour (as worn, durability and all), weapons and tools, and every consumable it didn't use. */
    List<ItemStack> inventory(Mimic m) {
        List<ItemStack> out = new ArrayList<>();
        Gear g = m.gear;
        for (ItemStack it : m.wear) if (it != null) out.add(it.clone());
        for (ItemStack it : new ItemStack[]{g.weapon, g.axe, g.mace, g.bow, g.crossbow, g.spear, m.trident, g.pickaxe, g.shovel})
            if (it != null && out.stream().noneMatch(x -> x.isSimilar(it))) out.add(it.clone());
        if (g.offhand != null && g.offhand.getType() != Material.TOTEM_OF_UNDYING) out.add(g.offhand.clone());
        add(out, Material.GOLDEN_APPLE, m.apples); add(out, Material.ENCHANTED_GOLDEN_APPLE, m.gapples);
        add(out, Material.ENDER_PEARL, m.pearlsLeft); add(out, Material.TOTEM_OF_UNDYING, m.totems);
        add(out, Material.WIND_CHARGE, m.wind); add(out, Material.BREEZE_ROD, m.rods); add(out, Material.ENDER_EYE, m.eyesLeft); add(out, Material.WATER_BUCKET, m.water); add(out, Material.LAVA_BUCKET, m.lava);
        add(out, Material.COBWEB, m.webs); if (g.block != null) add(out, g.block, m.blocks); add(out, Material.FIREWORK_ROCKET, m.rockets);
        add(out, Material.ARROW, m.arrows); add(out, Material.SNOWBALL, m.throwables); add(out, Material.TNT, m.tnt);
        add(out, Material.FIRE_CHARGE, m.fireCharges); add(out, Material.EXPERIENCE_BOTTLE, m.xpBottles);
        if (g.rod) out.add(new ItemStack(Material.FISHING_ROD));
        if (g.flint) out.add(new ItemStack(Material.FLINT_AND_STEEL));
        if (g.elytra && (m.wear[1] == null || m.wear[1].getType() != Material.ELYTRA)) out.add(new ItemStack(Material.ELYTRA));
        for (int i = 0; i < m.pots; i++) out.add(healingPotion());
        for (ItemStack it : m.buffs) out.add(it.clone());
        for (ItemStack it : m.debuffs) out.add(it.clone());
        return out;
    }

    static void add(List<ItemStack> out, Material mat, int n) {
        while (n > 0) { int k = Math.min(n, mat.getMaxStackSize()); out.add(new ItemStack(mat, k)); n -= k; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (byBrain.containsKey(e.getEntity().getUniqueId()) && e.getTarget() != null && !(e.getTarget() instanceof Player) && !byBrain.containsKey(e.getTarget().getUniqueId())) e.setCancelled(true); // players (and mimic foes) only
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
        if (sub.equals("fight") || sub.equals("duel") || sub.equals("ffa")) return fightCommand(s, sub, a);
        if (sub.equals("spectate") || sub.equals("watch") || sub.equals("spec")) {
            if (!(s instanceof Player p)) { s.sendMessage("In game only."); return true; }
            watch.command(p, a.length > 1 ? a[1] : null);
            return true;
        }
        if (sub.equals("debug")) {
            if (!(s instanceof Player p)) { s.sendMessage("In game only."); return true; }
            boolean on = hitLog.add(p.getUniqueId());
            if (!on) hitLog.remove(p.getUniqueId());
            s.sendMessage(ChatColor.YELLOW + "Mimic hit numbers " + (on ? "ON: every mimic hit on a player is shown here." : "off."));
            return true;
        }
        if (sub.equals("hunt")) {
            if (a.length > 1 && (a[1].equalsIgnoreCase("stop") || a[1].equalsIgnoreCase("off"))) {
                int n = 0;
                for (Mimic m : byBrain.values()) if (m.hunting && (a.length < 3 || m.name.equalsIgnoreCase(a[2]))) { m.hunting = false; m.huntId = null; m.travelFly = false; releaseTickets(m); n++; }
                s.sendMessage(ChatColor.GREEN + "" + n + " mimic" + (n == 1 ? "" : "s") + " stopped hunting.");
                return true;
            }
            UUID who = null; String whoName = "wherever players hang out";
            if (a.length > 1 && !a[1].equalsIgnoreCase("any")) {
                Player online = Bukkit.getPlayerExact(a[1]);
                OfflinePlayer op = online != null ? online : Bukkit.getOfflinePlayer(a[1]);
                if (op.getName() == null && online == null) { s.sendMessage(ChatColor.RED + "No player " + a[1] + "."); return true; }
                who = op.getUniqueId(); whoName = op.getName() == null ? a[1] : op.getName();
            }
            int n = 0;
            for (Mimic m : byBrain.values()) {
                if (a.length > 2 && !m.name.equalsIgnoreCase(a[2])) continue;
                m.hunting = true; m.huntId = who; m.path = null; n++;
            }
            if (n == 0 && a.length > 2) { // 1.2.93: no mimic of that player out = spawns one (next to you, or at the main world's spawn) and sends it
                OfflinePlayer of = Bukkit.getPlayerExact(a[2]) != null ? Bukkit.getPlayerExact(a[2]) : Bukkit.getOfflinePlayer(a[2]);
                Skill sk = SKILLS.getOrDefault(a.length > 3 ? a[3].toLowerCase(Locale.ROOT) : plugin.getConfig().getString("mimic-player.skill", "normal").toLowerCase(Locale.ROOT), SKILLS.get("normal"));
                Location at;
                if (s instanceof Player sp) { at = sp.getLocation().add(sp.getLocation().getDirection().setY(0).normalize().multiply(3)); at.setYaw(sp.getLocation().getYaw() + 180); }
                else at = Bukkit.getWorlds().get(0).getSpawnLocation().add(0.5, 0, 0.5);
                Mimic m = spawn(at, of, sk);
                m.hunting = true; m.huntId = who; m.path = null; n = 1;
                s.sendMessage(ChatColor.GREEN + "No mimic of " + a[2] + " was out: spawned one (" + sk.id() + ").");
            }
            if (n == 0) { s.sendMessage(ChatColor.RED + "No mimics out. /mimicplayer hunt <target> <player to mimic> [skill] spawns one and sends it."); return true; }
            s.sendMessage(ChatColor.GREEN + "" + n + " mimic" + (n == 1 ? "" : "s") + " hunting " + whoName + " (any range, any world; /mimicplayer hunt stop).");
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

    /**
     * 1.2.94 MIMIC FIGHTS. `/mimicplayer duel <player> <player> [skill]` spawns both where you look and they fight;
     * `/mimicplayer fight <mimic> <mimic>` sets two that are out on each other; `/mimicplayer fight ffa` (or `/mimicplayer ffa`)
     * = every mimic out (within 64 of you, in game) for itself; `/mimicplayer fight <a,b> vs <c,d>` = teams; `fight stop`.
     * Fighters leave players alone (being hit by one doesn't turn them) until the fight's over; the last team standing is announced.
     */
    private boolean fightCommand(CommandSender s, String sub, String[] a) {
        List<String> args = new ArrayList<>(Arrays.asList(a).subList(1, a.length));
        if (sub.equals("ffa")) args.add(0, "ffa");
        if (!args.isEmpty() && args.get(0).equalsIgnoreCase("stop")) {
            Set<String> ids = new HashSet<>();
            for (Mimic m : byBrain.values()) if (m.fightId != null) ids.add(m.fightId);
            for (Mimic m : byBrain.values()) { m.team = 0; m.fightId = null; m.target = null; }
            s.sendMessage(ChatColor.GREEN + (ids.isEmpty() ? "No mimic fight going on." : "Stopped " + ids.size() + " mimic fight" + (ids.size() == 1 ? "" : "s") + "."));
            return true;
        }
        if (sub.equals("duel")) {
            if (!(s instanceof Player p)) { s.sendMessage("In game only (they spawn where you look)."); return true; }
            if (args.size() < 2) { s.sendMessage(ChatColor.RED + "/mimicplayer duel <player> <player> [easy|normal|hard|godlike] [easy|normal|hard|godlike]"); return true; }
            Skill def = SKILLS.getOrDefault(plugin.getConfig().getString("mimic-player.skill", "normal").toLowerCase(Locale.ROOT), SKILLS.get("normal"));
            Skill s1 = args.size() > 2 ? SKILLS.getOrDefault(args.get(2).toLowerCase(Locale.ROOT), def) : def;
            Skill s2 = args.size() > 3 ? SKILLS.getOrDefault(args.get(3).toLowerCase(Locale.ROOT), s1) : s1;
            org.bukkit.block.Block hit;
            try { hit = p.getTargetBlockExact(30); } catch (RuntimeException ex) { hit = null; }
            Location mid = hit != null ? hit.getLocation().add(0.5, 1, 0.5) : p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(8));
            Vector side = p.getLocation().getDirection().setY(0);
            side = side.lengthSquared() < 1e-4 ? new Vector(1, 0, 0) : new Vector(-side.getZ(), 0, side.getX()).normalize();
            Location l1 = mid.clone().add(side.clone().multiply(5)), l2 = mid.clone().subtract(side.clone().multiply(5));
            l1.setDirection(l2.toVector().subtract(l1.toVector())); l2.setDirection(l1.toVector().subtract(l2.toVector()));
            Mimic m1 = spawn(l1, playerNamed(args.get(0)), s1), m2 = spawn(l2, playerNamed(args.get(1)), s2);
            startFight(List.of(List.of(m1), List.of(m2)));
            s.sendMessage(ChatColor.GREEN + "Duel: " + ChatColor.WHITE + m1.name + ChatColor.GRAY + " (" + s1.id() + ")" + ChatColor.GREEN + " vs "
                    + ChatColor.WHITE + m2.name + ChatColor.GRAY + " (" + s2.id() + ")" + ChatColor.GREEN + ". /mimicplayer spectate to watch, fight stop to end it.");
            return true;
        }
        List<List<Mimic>> teams = new ArrayList<>();
        if (args.isEmpty() || args.get(0).equalsIgnoreCase("ffa") || args.get(0).equalsIgnoreCase("all")) { // every mimic near you for itself
            for (Mimic m : byBrain.values()) {
                if (s instanceof Player p && (m.brain.getWorld() != p.getWorld() || m.brain.getLocation().distanceSquared(p.getLocation()) > 64 * 64)) continue;
                teams.add(List.of(m));
            }
        } else {
            int vs = args.indexOf("vs");
            List<String> sides = vs < 0 ? args : List.of(String.join(",", args.subList(0, vs)), String.join(",", args.subList(vs + 1, args.size())));
            for (String side : sides) {
                List<Mimic> team = new ArrayList<>();
                for (String name : side.split(",")) {
                    if (name.isBlank()) continue;
                    Mimic found = null;
                    for (Mimic m : byBrain.values()) if (m.name.equalsIgnoreCase(name.trim()) && !inTeams(teams, m) && !team.contains(m)) { found = m; break; }
                    if (found == null) { s.sendMessage(ChatColor.RED + "No mimic of " + name.trim() + " out (or it's already on a side). /mimicplayer list"); return true; }
                    team.add(found);
                }
                if (!team.isEmpty()) teams.add(team);
            }
        }
        if (teams.size() < 2) { s.sendMessage(ChatColor.RED + "Needs at least two mimics on different sides. /mimicplayer fight <mimic> <mimic>, fight <a,b> vs <c,d>, fight ffa, or /mimicplayer duel <player> <player>."); return true; }
        startFight(teams);
        StringBuilder line = new StringBuilder();
        for (List<Mimic> team : teams) {
            if (line.length() > 0) line.append(ChatColor.GREEN).append(" vs ");
            line.append(ChatColor.WHITE);
            for (int i = 0; i < team.size(); i++) line.append(i > 0 ? " + " : "").append(team.get(i).name);
        }
        s.sendMessage(ChatColor.GREEN + "Mimic fight: " + line + ChatColor.GREEN + ". /mimicplayer spectate to watch, fight stop to end it.");
        return true;
    }

    private static boolean inTeams(List<List<Mimic>> teams, Mimic m) { for (List<Mimic> t : teams) if (t.contains(m)) return true; return false; }

    private OfflinePlayer playerNamed(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online : Bukkit.getOfflinePlayer(name);
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
            List<String> out = new ArrayList<>(List.of("random", "remove", "clear", "list", "hunt", "spectate", "debug", "fight", "duel", "ffa"));
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            return out;
        }
        if (a.length == 2 && (a[0].equalsIgnoreCase("spectate") || a[0].equalsIgnoreCase("watch"))) {
            List<String> out = new ArrayList<>(List.of("next", "stop"));
            for (Mimic m : byBrain.values()) if (!out.contains(m.name)) out.add(m.name);
            return out;
        }
        if (a[0].equalsIgnoreCase("fight")) {
            List<String> out = new ArrayList<>();
            if (a.length == 2) { out.add("ffa"); out.add("stop"); }
            out.add("vs");
            for (Mimic m : byBrain.values()) if (!out.contains(m.name)) out.add(m.name);
            return out;
        }
        if (a[0].equalsIgnoreCase("duel")) {
            List<String> out = new ArrayList<>();
            if (a.length <= 3) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            else out.addAll(SKILLS.keySet());
            return out;
        }
        if (a[0].equalsIgnoreCase("hunt")) {
            List<String> out = new ArrayList<>();
            if (a.length == 2) { out.add("stop"); out.add("any"); }
            if (a.length <= 3) { for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName()); for (Mimic m : byBrain.values()) if (!out.contains(m.name)) out.add(m.name); }
            if (a.length == 4) out.addAll(SKILLS.keySet());
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
