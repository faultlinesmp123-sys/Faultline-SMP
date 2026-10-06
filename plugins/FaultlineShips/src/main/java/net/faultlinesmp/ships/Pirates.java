package net.faultlinesmp.ships;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Drowned;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Guardian;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.Stray;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Skeleton ships and the Pirate Invasion.
 *
 * A skeleton ship (a Pirate Ship crewed by skeletons) turns up now and then near players sailing the ocean
 * (pirates.ship-chance a minute). It keeps a broadside and fires its cannons, rams a ship that's crippled or stopped,
 * and runs from swimmers. Pull alongside and it drops anchor to fight you on deck: its hold is yours to plunder.
 *
 * The Pirate Invasion (pirates.invasion-chance a minute, or a Cursed Pirate Horn, which only skeleton ships carry):
 * 15+ skeleton ships come at you, with a flagship in the back. Sink them, board the flagship, and beat its three
 * bosses one after the other: the Captain's Son, the Skeleton Commander, the Skeleton Captain.
 */
final class Pirates implements Listener {
    static final String TAG = "faultline_pirate";
    static final UUID OWNER = new UUID(0x5ce1e7a1L, 0x5ce1e7a1L);

    enum Kind {
        DECKHAND("Skeleton Deckhand", false), MUSKETEER("Skeleton Musketeer", false), GUNNER("Skeleton Gunner", false),
        BOARDER("Skeleton Boarder", false), POWDER_MONKEY("Powder Monkey", false), CORSAIR("Drowned Corsair", false),
        BOSUN("Skeleton Bosun", false), GULL("Ghost Gull", false), SHARK("Bone Shark", false), NAVIGATOR("Skeleton Navigator", false),
        WRAITH("Ghost Pirate", false), // tag stays faultline_pirate_wraith
        SON("The Captain's Son", true), COMMANDER("The Skeleton Commander", true), CAPTAIN("The Skeleton Captain", true);

        final String title;
        final boolean boss;
        Kind(String title, boolean boss) { this.title = title; this.boss = boss; }
        String tag() { return "faultline_pirate_" + name().toLowerCase(); }
        static Kind of(String s) {
            for (Kind k : values()) if (k.name().equalsIgnoreCase(s) || k.name().replace("_", "").equalsIgnoreCase(s.replace("_", ""))) return k;
            return null;
        }
    }

    /** Keys the brain presses. */
    static final class AiInput implements Input {
        boolean f, b, l, r, sp;
        @Override public boolean isForward() { return f; }
        @Override public boolean isBackward() { return b; }
        @Override public boolean isLeft() { return l; }
        @Override public boolean isRight() { return r; }
        @Override public boolean isJump() { return false; }
        @Override public boolean isSneak() { return false; }
        @Override public boolean isSprint() { return sp; }
        void clear() { f = b = l = r = sp = false; }
    }

    /** One pirate mob. */
    static final class Crew {
        final Kind kind;
        Brain home;
        long next, fuse = -1;
        boolean summoned, ghostCrew, waiting; // waiting: a boss on the flagship's deck whose turn hasn't come
        Crew(Kind kind, Brain home) { this.kind = kind; this.home = home; }
    }

    /** One skeleton ship's mind. */
    final class Brain {
        final Ship ship;
        final Invasion inv;
        final boolean flagship;
        final AiInput input = new AiInput();
        final Set<UUID> looted = new HashSet<>();
        Player target;
        long avoidUntil = -1, wanderUntil = -1, quietSince = -1, sunkAt = -1, lastFire, born;
        int avoidDir = 1;
        float wander;
        boolean boarded, sentSwimmers, holdBack;
        double gap = 999;   // the real gap between our hull and the target's
        long gapAt = -99;
        int shots;          // cannonballs fired: they shell you before they board
        boolean beaten;     // the invasion's flagship after you've won: it stays put (its hold is yours) and fades when you leave

        Brain(Ship ship, Invasion inv, boolean flagship) { this.ship = ship; this.inv = inv; this.flagship = flagship; born = pl.now; }
    }

    /** A Pirate Invasion. */
    final class Invasion {
        final World world;
        final Location center;
        final List<Ship> ships = new ArrayList<>();
        Ship flagship;
        int total, stage; // 0 the fleet, 1 the son, 2 the commander, 3 the captain, 4 won
        LivingEntity boss;
        final BossBar bar = Bukkit.createBossBar("Pirate Invasion", BarColor.RED, BarStyle.SEGMENTED_20);
        final BossBar bossBar = Bukkit.createBossBar("", BarColor.PURPLE, BarStyle.SOLID);
        final Set<UUID> fighters = new HashSet<>();
        long started, quietSince = -1, bossDueAt = -1;
        final LivingEntity[] bosses = new LivingEntity[3]; // the Son, the Commander, the Captain: all three on the flagship's deck
        int heaveTries;
        final Map<UUID, Long> music = new HashMap<>(); // when each fighter's track last started
        boolean over;

        Invasion(Location center) { this.world = center.getWorld(); this.center = center.clone(); started = pl.now; }

        int alive() { int n = 0; for (Ship s : ships) if (pl.ships.containsKey(s.id) && !s.wrecked) n++; return n; }
        int escortsAlive() { int n = 0; for (Ship s : ships) if (s != flagship && pl.ships.containsKey(s.id) && !s.wrecked) n++; return n; }
    }

    final FaultlineShips pl;
    final Map<UUID, Brain> brains = new HashMap<>();
    final Map<UUID, Crew> crew = new HashMap<>();
    final List<Invasion> invasions = new ArrayList<>();
    final Map<UUID, Long> nextRoll = new HashMap<>();
    final NamespacedKey hornKey, grenadeKey, eggKey;
    final Random rnd = new Random();

    Pirates(FaultlineShips pl) {
        this.pl = pl;
        hornKey = new NamespacedKey(pl, "pirate_horn");
        grenadeKey = new NamespacedKey(pl, "pirate_grenade");
        eggKey = new NamespacedKey(pl, "pirate_egg");
    }

    double cfg(String k, double d) { return pl.getConfig().getDouble("pirates." + k, d); }
    boolean isPirate(Entity e) { return e != null && e.getScoreboardTags().contains(TAG); }

    // =====================================================================================================
    //  items
    // =====================================================================================================
    ItemStack horn() {
        ItemStack it = new ItemStack(Material.GOAT_HORN);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Cursed Pirate Horn");
        m.setLore(List.of(ChatColor.GRAY + "Found in the holds of skeleton ships.",
                ChatColor.GREEN + "Blow it on a ship at sea to call",
                ChatColor.GREEN + "the Pirate Invasion: 15+ skeleton ships",
                ChatColor.GREEN + "and their flagship's three captains.",
                ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "It smells of salt and old bones."));
        m.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        m.setEnchantmentGlintOverride(true);
        m.setMaxStackSize(1);
        m.getPersistentDataContainer().set(hornKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isHorn(ItemStack it) { return FaultlineShips.has(it, hornKey); }

    /** A spawn egg for one pirate (admin, /itemsmenu). */
    ItemStack egg(Kind k) {
        Material m = switch (k) {
            case CORSAIR -> Material.DROWNED_SPAWN_EGG;
            case GULL -> Material.PHANTOM_SPAWN_EGG;
            case SHARK -> Material.GUARDIAN_SPAWN_EGG;
            case NAVIGATOR -> Material.STRAY_SPAWN_EGG;
            case WRAITH, CAPTAIN -> Material.WITHER_SKELETON_SPAWN_EGG;
            default -> Material.SKELETON_SPAWN_EGG;
        };
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName((k.boss ? ChatColor.GOLD + "" + ChatColor.BOLD : ChatColor.WHITE + "") + k.title + (k.boss ? " (Boss)" : "") + " Spawn Egg");
        meta.setLore(List.of(ChatColor.GRAY + "Right-click a block to spawn it."));
        meta.getPersistentDataContainer().set(eggKey, PersistentDataType.STRING, k.name());
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEgg(PlayerInteractEvent event) {
        ItemStack it = event.getItem();
        if (it == null || !it.hasItemMeta()) return;
        String k = it.getItemMeta().getPersistentDataContainer().get(eggKey, PersistentDataType.STRING);
        if (k == null) return;
        event.setCancelled(true); // never the vanilla mob
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || event.getHand() != EquipmentSlot.HAND) return;
        Kind kind = Kind.of(k);
        if (kind == null) return;
        Player p = event.getPlayer();
        Location at = event.getClickedBlock().getRelative(event.getBlockFace()).getLocation().add(0.5, 0, 0.5);
        at.setYaw(p.getLocation().getYaw() + 180);
        if (spawnMob(kind, at, null) != null && p.getGameMode() != GameMode.CREATIVE) it.setAmount(it.getAmount() - 1);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHorn(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        Player p = event.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!isHorn(hand)) return;
        event.setCancelled(true);
        if (pl.used(p)) return;
        Ship on = pl.ridingOn(p);
        if (on == null) on = standingOn(p);
        if (on == null || on.ai != null) { p.sendActionBar(Component.text("Blow it on your ship, out at sea.", NamedTextColor.YELLOW)); return; }
        if (invasionNear(p.getLocation(), 400) != null) { p.sendActionBar(Component.text("They're already coming.", NamedTextColor.RED)); return; }
        if (startInvasion(p, on) == null) { p.sendActionBar(Component.text("The horn echoes, but there's no open sea here for a fleet.", NamedTextColor.YELLOW)); return; }
        if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
    }

    Ship standingOn(Player p) {
        Location l = p.getLocation();
        for (Ship s : pl.ships.values()) {
            if (!s.spawned() || !p.getWorld().getName().equals(s.world)) continue;
            if (s.cellAt(l.getX(), l.getY() - 0.2, l.getZ()) >= 0 || s.cellAt(l.getX(), l.getY() - 1.2, l.getZ()) >= 0) return s;
        }
        return null;
    }

    // =====================================================================================================
    //  the ships
    // =====================================================================================================
    /** A skeleton ship: dark wood, black sails, a crew on its seats and plunder in its hold. Null if it doesn't fit there. */
    Ship spawnShip(ShipType t, World w, double x, double y, double z, float yaw, Invasion inv, boolean flagship) {
        Ship s = new Ship(pl, UUID.randomUUID(), t, OWNER);
        s.world = w.getName();
        s.x = Math.floor(x) + 0.5; s.y = y; s.z = Math.floor(z) + 0.5;
        s.yaw = ((yaw % 360) + 360) % 360;
        if (!s.clear(s.x, s.z, s.yaw, true, null)) return null;
        for (int i = 0; i < s.blocks.length; i++) s.blocks[i] = s.blockFor(i, dark(s.type.cells.get(i).need()));
        s.recount();
        Brain b = new Brain(s, inv, flagship);
        s.ai = b;
        s.hp = s.maxHp();
        s.name = flagship ? "The Black Gallows" : null;
        s.cargo = loot(t, inv != null);
        brains.put(s.id, b);
        pl.ships.put(s.id, s);
        s.spawn();
        crewUp(b);
        if (inv == null || rnd.nextDouble() < 0.35) { // company in the water and the air
            int corsairs = inv == null ? 2 : 1;
            for (int i = 0; i < corsairs; i++) spawnMob(Kind.CORSAIR, around(s, 6), b);
            if (rnd.nextDouble() < 0.6) spawnMob(Kind.SHARK, around(s, 8), b);
            if (rnd.nextDouble() < 0.6) spawnMob(Kind.GULL, s.center().add(0, 12, 0), b);
        }
        return s;
    }

    Location around(Ship s, double r) {
        double a = rnd.nextDouble() * Math.PI * 2, d = s.radius() + r;
        return new Location(s.world(), s.x + Math.cos(a) * d, s.y - 0.5, s.z + Math.sin(a) * d);
    }

    static Material dark(ShipType.Need n) {
        return switch (n) {
            case PLANKS -> Material.DARK_OAK_PLANKS;
            case LOG -> Material.DARK_OAK_LOG;
            case FENCE -> Material.DARK_OAK_FENCE;
            case STAIRS -> Material.DARK_OAK_STAIRS;
            case WOOL, SAIL_BLACK -> Material.BLACK_WOOL;
            case SAIL_WHITE -> Material.WHITE_WOOL;
            case CHEST -> Material.BARREL;
            case LANTERN -> Material.SOUL_LANTERN;
            case LADDER -> Material.LADDER;
            case TRAPDOOR, HATCH -> Material.DARK_OAK_TRAPDOOR;
            case PANE -> Material.BLACK_STAINED_GLASS_PANE;
            case CANNON -> Material.IRON_BLOCK;
        };
    }

    /** Skeletons in every seat: the navigator at the helm, a mix on the rest. */
    void crewUp(Brain b) {
        Ship s = b.ship;
        if (s.seats == null) return;
        Kind[] mix = {Kind.DECKHAND, Kind.DECKHAND, Kind.MUSKETEER, Kind.MUSKETEER, Kind.GUNNER, Kind.GUNNER, Kind.BOARDER,
                Kind.BOARDER, Kind.POWDER_MONKEY, Kind.BOSUN, Kind.WRAITH, Kind.MUSKETEER};
        for (int i = 0; i < s.seats.length; i++) {
            if (s.seats[i] == null || !s.seats[i].isValid() || !s.seats[i].getPassengers().isEmpty()) continue;
            Kind k = i == 0 ? Kind.NAVIGATOR : mix[rnd.nextInt(mix.length)];
            LivingEntity m = spawnMob(k, s.seatLoc(i), b);
            if (m != null) s.seats[i].addPassenger(m);
        }
    }

    ItemStack[] loot(ShipType t, boolean invasion) {
        List<ItemStack> l = new ArrayList<>();
        l.add(new ItemStack(Material.GOLD_INGOT, 4 + rnd.nextInt(9)));
        l.add(new ItemStack(Material.EMERALD, 2 + rnd.nextInt(7)));
        if (rnd.nextDouble() < 0.6) l.add(new ItemStack(Material.DIAMOND, 1 + rnd.nextInt(3)));
        l.add(new ItemStack(Material.GUNPOWDER, 3 + rnd.nextInt(6)));
        ItemStack balls = pl.ball(); balls.setAmount(4 + rnd.nextInt(9)); l.add(balls);
        if (rnd.nextDouble() < 0.5) l.add(new ItemStack(Material.BONE, 4 + rnd.nextInt(8)));
        PotionType[] pots = {PotionType.HEALING, PotionType.STRENGTH, PotionType.SWIFTNESS, PotionType.WATER_BREATHING, PotionType.REGENERATION, PotionType.FIRE_RESISTANCE};
        int np = 1 + rnd.nextInt(3);
        for (int i = 0; i < np; i++) {
            ItemStack pot = new ItemStack(rnd.nextBoolean() ? Material.POTION : Material.SPLASH_POTION);
            PotionMeta pm = (PotionMeta) pot.getItemMeta();
            pm.setBasePotionType(pots[rnd.nextInt(pots.length)]);
            pot.setItemMeta(pm);
            l.add(pot);
        }
        if (rnd.nextDouble() < cfg("horn-chance", invasion ? 0.05 : 0.15)) l.add(horn());
        ItemStack[] out = new ItemStack[Math.max(9, t.cargo)];
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < out.length; i++) slots.add(i);
        java.util.Collections.shuffle(slots, rnd);
        for (int i = 0; i < l.size() && i < out.length; i++) out[slots.get(i)] = l.get(i);
        return out;
    }

    /** A player opens a skeleton ship's hold: the first time, they find a little more than what's in it. */
    void plunder(Player p, Ship s) {
        Brain b = s.ai;
        if (b == null) return;
        if (b.looted.add(p.getUniqueId())) {
            List<String> found = new ArrayList<>();
            for (Map<?, ?> r : pl.getConfig().getMapList("pirates.plunder")) {
                double chance = r.get("chance") instanceof Number n ? n.doubleValue() : 1;
                if (rnd.nextDouble() >= chance) continue;
                String cmd = String.valueOf(r.get("command"));
                run(cmd, p);
                if (r.get("name") != null) found.add(String.valueOf(r.get("name")));
            }
            p.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Plunder! " + ChatColor.YELLOW + (found.isEmpty() ? "The hold is yours." : "You found " + String.join(", ", found) + ", and the hold is yours."));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 1f, 0.8f);
        }
        p.openInventory(s.hold());
    }

    /** Runs a reward command for a player: {player}, {accessory} (a random one from pirates.accessories), {1-3} (a number). */
    void run(String cmd, Player p) {
        String c = cmd.replace("{player}", p.getName());
        List<String> acc = pl.getConfig().getStringList("pirates.accessories");
        if (c.contains("{accessory}")) c = c.replace("{accessory}", acc.isEmpty() ? "scarf" : acc.get(rnd.nextInt(acc.size())));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{(\\d+)-(\\d+)}").matcher(c);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int lo = Integer.parseInt(m.group(1)), hi = Integer.parseInt(m.group(2));
            m.appendReplacement(sb, String.valueOf(lo + rnd.nextInt(Math.max(1, hi - lo + 1))));
        }
        m.appendTail(sb);
        try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), sb.toString()); }
        catch (RuntimeException ex) { pl.getLogger().warning("Pirate reward command failed: " + sb + " (" + ex + ")"); }
    }

    // =====================================================================================================
    //  the mobs
    // =====================================================================================================
    LivingEntity spawnMob(Kind k, Location at, Brain home) {
        World w = at.getWorld();
        Class<? extends LivingEntity> cls = switch (k) {
            case CORSAIR -> Drowned.class;
            case GULL -> Phantom.class;
            case SHARK -> Guardian.class;
            case NAVIGATOR -> Stray.class;
            case WRAITH -> WitherSkeleton.class;
            default -> Skeleton.class;
        };
        LivingEntity m;
        try { m = w.spawn(at, cls, e -> setup(e, k)); }
        catch (RuntimeException ex) { pl.getLogger().warning("Couldn't spawn a " + k.title + ": " + ex); return null; }
        crew.put(m.getUniqueId(), new Crew(k, home));
        return m;
    }

    void setup(LivingEntity e, Kind k) {
        e.addScoreboardTag(TAG);
        e.addScoreboardTag(k.tag());
        e.setPersistent(false);
        e.setRemoveWhenFarAway(false);
        e.setCanPickupItems(false);
        ChatColor c = k.boss ? ChatColor.DARK_RED : ChatColor.GRAY;
        e.setCustomName(c + (k.boss ? "" + ChatColor.BOLD : "") + k.title);
        e.setCustomNameVisible(k.boss);
        EntityEquipment g = e.getEquipment();
        double hp = 20;
        switch (k) {
            case DECKHAND -> { hp = 24; gear(g, leather(Material.LEATHER_HELMET, Color.fromRGB(160, 30, 30)), null, new ItemStack(Material.IRON_SWORD)); }
            case MUSKETEER -> { hp = 22; gear(g, leather(Material.LEATHER_HELMET, Color.fromRGB(25, 25, 30)), leather(Material.LEATHER_CHESTPLATE, Color.fromRGB(30, 40, 90)), new ItemStack(Material.BOW)); }
            case GUNNER -> { hp = 26; gear(g, leather(Material.LEATHER_HELMET, Color.fromRGB(90, 90, 95)), null, pl.ball()); }
            case BOARDER -> { hp = 28; gear(g, new ItemStack(Material.CHAINMAIL_HELMET), null, new ItemStack(Material.IRON_AXE)); }
            case POWDER_MONKEY -> {
                hp = 12;
                gear(g, new ItemStack(Material.TNT), null, null);
                FaultlineShips.attr(e, Attribute.SCALE, 0.6);
                FaultlineShips.attr(e, Attribute.MOVEMENT_SPEED, 0.36);
            }
            case CORSAIR -> {
                hp = 30;
                gear(g, leather(Material.LEATHER_HELMET, Color.fromRGB(25, 25, 30)), null, new ItemStack(Material.TRIDENT));
                e.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, PotionEffect.INFINITE_DURATION, 1, false, false));
            }
            case BOSUN -> {
                hp = 34;
                Material chain = Material.matchMaterial("IRON_CHAIN") != null ? Material.matchMaterial("IRON_CHAIN") : Material.matchMaterial("CHAIN");
                gear(g, leather(Material.LEATHER_HELMET, Color.fromRGB(110, 70, 40)), leather(Material.LEATHER_CHESTPLATE, Color.fromRGB(110, 70, 40)),
                        chain == null ? new ItemStack(Material.IRON_SWORD) : new ItemStack(chain));
            }
            case GULL -> { hp = 10; if (e instanceof Phantom ph) ph.setSize(0); FaultlineShips.attr(e, Attribute.SCALE, 0.55); }
            case SHARK -> { hp = 36; FaultlineShips.attr(e, Attribute.SCALE, 1.3); }
            case NAVIGATOR -> {
                hp = 26;
                gear(g, leather(Material.LEATHER_HELMET, Color.fromRGB(20, 60, 50)), null, new ItemStack(Material.BOW));
                if (g != null) { g.setItemInOffHand(new ItemStack(Material.SPYGLASS)); g.setItemInOffHandDropChance(0f); }
            }
            case WRAITH -> { // a ghost: you only see its glowing outline and its cutlass, drifting in a haze of souls
                hp = 34;
                gear(g, null, null, new ItemStack(Material.IRON_SWORD));
                e.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false));
                e.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, PotionEffect.INFINITE_DURATION, 0, false, false));
                e.setGlowing(true);
                e.setSilent(true); // its sounds are ours (ghostly)
            }
            case SON -> {
                hp = cfg("bosses.son-health", 320);
                gear(g, leather(Material.LEATHER_HELMET, Color.fromRGB(20, 20, 24)), leather(Material.LEATHER_CHESTPLATE, Color.fromRGB(150, 20, 20)), new ItemStack(Material.GOLDEN_SWORD));
                FaultlineShips.attr(e, Attribute.MOVEMENT_SPEED, 0.34);
            }
            case COMMANDER -> {
                hp = cfg("bosses.commander-health", 480);
                ItemStack bow = new ItemStack(Material.BOW);
                bow.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.POWER, 3);
                gear(g, jollyRoger(), new ItemStack(Material.IRON_CHESTPLATE), bow);
                FaultlineShips.attr(e, Attribute.SCALE, 1.15);
            }
            case CAPTAIN -> {
                hp = cfg("bosses.captain-health", 900);
                gear(g, leather(Material.LEATHER_HELMET, Color.fromRGB(15, 15, 18)), new ItemStack(Material.NETHERITE_CHESTPLATE), new ItemStack(Material.NETHERITE_SWORD));
                FaultlineShips.attr(e, Attribute.SCALE, 1.5);
                FaultlineShips.attr(e, Attribute.KNOCKBACK_RESISTANCE, 0.8);
                FaultlineShips.attr(e, Attribute.ATTACK_DAMAGE, 12);
            }
        }
        FaultlineShips.attr(e, Attribute.MAX_HEALTH, hp);
        AttributeInstance max = e.getAttribute(Attribute.MAX_HEALTH);
        e.setHealth(Math.min(hp, max != null ? max.getValue() : hp));
        FaultlineShips.attr(e, Attribute.FOLLOW_RANGE, 40);
    }

    static void gear(EntityEquipment g, ItemStack helmet, ItemStack chest, ItemStack hand) {
        if (g == null) return;
        g.setHelmet(helmet); g.setChestplate(chest); g.setItemInMainHand(hand);
        g.setHelmetDropChance(0f); g.setChestplateDropChance(0f); g.setItemInMainHandDropChance(0f);
    }

    static ItemStack leather(Material m, Color c) {
        ItemStack it = new ItemStack(m);
        if (it.getItemMeta() instanceof LeatherArmorMeta lm) { lm.setColor(c); it.setItemMeta(lm); }
        return it;
    }

    /** A black banner with a white skull: the Commander wears it, like the Bone Commander's. */
    static ItemStack jollyRoger() {
        ItemStack b = new ItemStack(Material.BLACK_BANNER);
        if (b.getItemMeta() instanceof BannerMeta bm) {
            try {
                bm.addPattern(new Pattern(DyeColor.WHITE, PatternType.SKULL));
                bm.addPattern(new Pattern(DyeColor.BLACK, PatternType.BORDER));
            } catch (RuntimeException ignored) { }
            b.setItemMeta(bm);
        }
        return b;
    }

    // =====================================================================================================
    //  every tick
    // =====================================================================================================
    void tick() {
        long now = pl.now;
        for (Brain b : new ArrayList<>(brains.values())) {
            try { think(b, now); } catch (RuntimeException ex) { pl.getLogger().warning("Skeleton ship: " + ex); }
        }
        if (now % 5 == 0) crewTick(now);
        if (now % 20 == 7) {
            for (Invasion inv : new ArrayList<>(invasions)) invasionTick(inv, now);
            rolls(now);
        }
    }

    void think(Brain b, long now) {
        Ship s = b.ship;
        if (!pl.ships.containsKey(s.id)) { brains.remove(s.id); return; }
        if (!s.spawned() && !s.unseenLoaded()) return; // out past the draw range it still thinks (and sails) while its water is loaded
        AiInput in = b.input;
        if (s.wrecked) {
            in.clear();
            if (b.sunkAt < 0) b.sunkAt = now;
            if (s.sink >= s.sinkTarget() - 1e-6 && now - b.sunkAt > 60) discard(s);
            return;
        }
        // who's around
        Player t = null;
        // how far it sees you: an invasion ship hunts the fleet's fighters from anywhere nearby, so none drift off
        double td = b.inv != null ? cfg("invasion.sight", 400) : cfg("sight", 200);
        for (Player p : s.world().getPlayers()) {
            if (!fighting(p)) continue;
            double d = p.getLocation().distance(new Location(s.world(), s.x, p.getLocation().getY(), s.z));
            if (d < td) { td = d; t = p; }
        }
        b.target = t;
        if (t == null) {
            if (b.quietSince < 0) b.quietSince = now;
            if (now - b.quietSince > cfg("leave-after-seconds", 90) * 20 && b.inv == null) { discard(s); return; }
        } else b.quietSince = -1;

        if (b.beaten) { // won: it stays where it is until nobody's near
            in.clear();
            if (t != null && td < 64) { b.quietSince = -1; return; }
            if (b.quietSince < 0) b.quietSince = now;
            if (now - b.quietSince > 1200) discard(s); // a minute with nobody aboard or near
            return;
        }
        if (s.anchored) { boarded(b, now); return; }
        if (s.anchorStep >= 0) { in.clear(); return; }

        if (b.flagship && b.inv != null && b.inv.stage == 0 && b.inv.escortsAlive() == 0 && b.inv.heaveTries < 40) {
            // the fleet's sunk: the Black Gallows heaves to and drops anchor where she is. Climb her ladders and face her captains.
            in.clear();
            double max = s.type.speed * pl.getConfig().getDouble("ships." + s.type.size + ".speed-multiplier", 1.0);
            s.speed = Ship.brake(s.speed, max);
            s.turnRate = 0;
            if (Math.abs(s.speed) <= max * 0.3 && now % 10 == 0) { b.inv.heaveTries++; s.dropAnchor(null); }
            return;
        }
        Ship ts = t == null ? null : pl.ridingOn(t);
        if (ts == null && t != null) ts = standingOn(t);
        if (ts != null && ts.ai != null) ts = null;
        double dist = td;
        float yaw = s.yaw;
        float want;
        boolean sprint = false, brake = false;
        if (t == null) { // patrol
            if (now > b.wanderUntil) { b.wander = (float) (rnd.nextDouble() * 360); b.wanderUntil = now + 600 + rnd.nextInt(600); }
            want = b.wander;
        } else {
            float bearing = bearing(s.x, s.z, t.getLocation().getX(), t.getLocation().getZ());
            boolean swimming = (ts == null || ts.wrecked) && t.isInWater();
            boolean holdBack = b.flagship && b.inv != null && b.inv.escortsAlive() > cfg("flagship-waits-for", 3) && b.inv.stage == 0;
            if (ts != null && ts.wrecked && !b.sentSwimmers && dist < 40) { b.sentSwimmers = true; abandon(b, 3, t); } // over the side after them
            if (ts != null && dist < s.radius() + ts.radius() + 3) { if (now - b.gapAt >= 10) { b.gapAt = now; b.gap = gap(s, ts); } }
            else b.gap = 999;
            if (swimming && dist < s.radius() + 14) { want = bearing + 180; sprint = true; } // never let a swimmer catch up
            else if (holdBack) { want = dist > 75 ? bearing : dist < 55 ? bearing + 180 : bearing + 90; }
            else if (ts != null && b.gap < cfg("board-gap", 4) && Math.abs(ts.speed) < 0.12 && !swimming && readyToBoard(b, ts)) { // alongside: drop anchor, they board
                want = yaw; brake = true;
                if (Math.abs(s.speed) < 0.05) s.dropAnchor(null);
            } else if (ts != null && ramming(b, ts)) { want = bearing; sprint = true; }
            else if (ts != null && b.gap < 7 && !readyToBoard(b, ts)) want = bearing + 180;                // too close to shoot: open the range
            else if (ts != null && Math.abs(ts.speed) < 0.12 && readyToBoard(b, ts) && !swimming) want = bearing; // shelled enough: close in and board
            else if (dist > 34) want = bearing + (float) (Math.sin(now * 0.004 + s.id.hashCode()) * 25);
            else if (dist < 15) want = bearing + 180 + (float) (Math.sin(now * 0.01) * 30);
            else { // broadside: turn side-on, whichever side is quicker
                float a = bearing + 90, c = bearing - 90;
                want = Math.abs(wrap(a - yaw)) < Math.abs(wrap(c - yaw)) ? a : c;
            }
            if (now - b.lastFire >= 8 && dist < cfg("cannon-range", 48)) { b.lastFire = now; broadside(b, t, ts); }
        }
        // land in the way: swing hard one way for a while
        if (s.lastBlockedTick > s.ticks - 3 && now > b.avoidUntil) { b.avoidUntil = now + 40; b.avoidDir = rnd.nextBoolean() ? 1 : -1; }
        if (now < b.avoidUntil) want = yaw + 90 * b.avoidDir;
        float diff = wrap(want - yaw);
        in.clear();
        in.r = diff > 4;
        in.l = diff < -4;
        if (brake) in.b = true; else in.f = true;
        in.sp = sprint;
    }

    /** How far apart two hulls really are (their outer blocks, sampled). */
    static double gap(Ship a, Ship b) {
        List<Integer> pa = a.type.probes, pb = b.type.probes;
        double[][] bp = new double[(pb.size() + 2) / 3][];
        for (int j = 0, k = 0; j < pb.size(); j += 3, k++) {
            ShipType.Cell c = b.type.cells.get(pb.get(j));
            double[] w = Ship.toWorld(b.x, b.z, b.yaw, c.x(), c.z());
            bp[k] = new double[]{w[0], b.y + c.y(), w[1]};
        }
        double best = Double.MAX_VALUE;
        for (int i = 0; i < pa.size(); i += 3) {
            ShipType.Cell c = a.type.cells.get(pa.get(i));
            double[] w = Ship.toWorld(a.x, a.z, a.yaw, c.x(), c.z());
            for (double[] q : bp) {
                if (q == null) continue;
                double dx = q[0] - w[0], dz = q[2] - w[1];
                best = Math.min(best, dx * dx + dz * dz);
            }
        }
        return Math.sqrt(best);
    }

    boolean ramming(Brain b, Ship ts) {
        Ship s = b.ship;
        return s.hp > s.maxHp() * 0.4 && (ts.hp < ts.maxHp() * cfg("ram-when-below", 0.35) || (Math.abs(ts.speed) < 0.03 && !ts.anchored) || ts.cannons().isEmpty());
    }

    /** They board once they've shelled you a while, or your hull is hurting. */
    boolean readyToBoard(Brain b, Ship ts) {
        return b.shots >= cfg("shots-before-boarding", 6) || ts.hp < ts.maxHp() * 0.6;
    }

    boolean fighting(Player p) {
        return p.isValid() && !p.isDead() && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE);
    }

    static float bearing(double x, double z, double tx, double tz) { return (float) Math.toDegrees(Math.atan2(-(tx - x), tz - z)); }
    static float wrap(float a) { a %= 360; if (a > 180) a -= 360; if (a < -180) a += 360; return a; }

    /** Fire one ready cannon on the side facing the target, aiming a little ahead and up to reach. */
    void broadside(Brain b, Player t, Ship ts) {
        Ship s = b.ship;
        Location tl = t.getLocation().add(0, 0.5, 0);
        if (ts != null) { // lead a moving ship
            double v = ts.speed * 20;
            tl.add(Ship.fx(ts.yaw) * v * 0.9, 0, Ship.fz(ts.yaw) * v * 0.9);
        }
        boolean spotted = t.isGlowing();
        for (int i : s.cannons()) {
            if (s.cannonReady.getOrDefault(i, 0L) > pl.now) continue;
            if (s.broken.contains(i)) continue;
            Location mz = s.muzzle(i);
            Vector to = tl.toVector().subtract(mz.toVector());
            double d = to.length();
            Vector flat = new Vector(to.getX(), 0, to.getZ()).normalize();
            if (flat.dot(s.outward(i)) < Math.cos(Math.toRadians(40))) continue;
            double spread = Math.toRadians(spotted ? 2 : cfg("cannon-spread-degrees", 6));
            double yawOff = (rnd.nextDouble() * 2 - 1) * spread;
            double cs = Math.cos(yawOff), sn = Math.sin(yawOff);
            flat = new Vector(flat.getX() * cs - flat.getZ() * sn, 0, flat.getX() * sn + flat.getZ() * cs);
            double speed = pl.cfg("cannon.speed", 2.4);
            double lift = Math.min(0.5, d * cfg("cannon-arc", 0.006) + (to.getY() / Math.max(1, d)) * 0.8);
            Vector vel = flat.multiply(speed).setY(lift * speed);
            LivingEntity gunner = null;
            for (Entity e : s.seats == null ? new Entity[0] : s.seats) if (e != null) for (Entity q : e.getPassengers()) if (q instanceof LivingEntity le) { gunner = le; break; }
            pl.launch(s, mz, vel, gunner);
            b.shots++;
            s.cannonReady.put(i, pl.now + (long) (cfg("reload-seconds", 4.5) * 20));
            return;
        }
    }

    /** Anchored next to you: the crew is on deck. When nobody's around for a while, the anchor comes up again. */
    void boarded(Brain b, long now) {
        Ship s = b.ship;
        b.input.clear();
        if (!b.boarded) {
            b.boarded = true;
            if (s.seats != null) for (ArmorStand seat : s.seats) {
                if (seat == null) continue;
                for (Entity q : new ArrayList<>(seat.getPassengers())) {
                    seat.removePassenger(q);
                    q.teleport(seat.getLocation().add(0, 0.1, 0));
                }
            }
            for (int i = 0; i < 2; i++) spawnMob(Kind.DECKHAND, s.seatLoc(1 + rnd.nextInt(Math.max(1, s.type.seats.length - 1))), b);
            s.world().playSound(s.center(), Sound.ENTITY_SKELETON_AMBIENT, SoundCategory.HOSTILE, 2f, 0.6f);
            for (Player p : s.world().getPlayers()) if (p.getLocation().distanceSquared(s.center()) < 40 * 40)
                p.sendActionBar(Component.text("The skeletons drop anchor: board them!", NamedTextColor.RED));
            if (b.flagship && b.inv != null && b.inv.stage == 0) nextBoss(b.inv);
        }
        if (b.flagship && b.inv != null && !b.inv.over) return; // the bosses fight on its deck: it stays at anchor
        boolean near = false;
        for (Player p : s.world().getPlayers()) if (fighting(p) && p.getLocation().distanceSquared(s.center()) < 32 * 32) { near = true; break; }
        if (near) { b.quietSince = -1; return; }
        if (b.quietSince < 0) b.quietSince = now;
        if (now - b.quietSince > 600) { // nobody for 30 s: weigh anchor and sail on
            s.raiseAnchor(null);
            b.boarded = false;
            for (Map.Entry<UUID, Crew> e : crew.entrySet()) {
                if (e.getValue().home != b) continue;
                Entity m = Bukkit.getEntity(e.getKey());
                if (!(m instanceof LivingEntity le) || !le.isValid() || le.isInsideVehicle()) continue;
                for (ArmorStand seat : s.seats) if (seat != null && seat.getPassengers().isEmpty()) { seat.addPassenger(le); break; }
            }
        }
    }

    /** Some of the crew jump over the side and swim after a player. */
    void abandon(Brain b, int n, Player t) {
        Ship s = b.ship;
        if (s.seats == null) return;
        int done = 0;
        for (int i = s.seats.length - 1; i > 0 && done < n; i--) {
            ArmorStand seat = s.seats[i];
            if (seat == null) continue;
            for (Entity q : new ArrayList<>(seat.getPassengers())) {
                seat.removePassenger(q);
                Vector v = t.getLocation().toVector().subtract(q.getLocation().toVector()).setY(0).normalize().multiply(0.8).setY(0.5);
                q.setVelocity(v);
                done++;
            }
        }
    }

    /** A skeleton ship goes down: the crew abandons ship and the plunder floats. */
    void onWreck(Ship s) {
        Brain b = s.ai;
        if (b == null) return;
        if (s.seats != null) for (ArmorStand seat : s.seats) if (seat != null) seat.eject();
        if (b.looted.isEmpty() && s.cargo != null) {
            Location up = s.center().add(0, 1, 0);
            ItemStack[] c = s.hold != null ? s.hold.getContents() : s.cargo;
            for (ItemStack it : c) if (it != null && !it.getType().isAir()) s.world().dropItem(up, it);
            if (s.hold != null) s.hold.clear();
            s.cargo = new ItemStack[s.type.cargo];
        }
        Player by = null;
        for (Player p : s.world().getPlayers()) if (p.getLocation().distanceSquared(s.center()) < 80 * 80) { by = p; break; }
        if (b.inv != null) {
            for (UUID f : b.inv.fighters) { Player p = Bukkit.getPlayer(f); if (p != null) p.sendActionBar(Component.text("A skeleton ship goes down! " + (b.inv.alive()) + " left.", NamedTextColor.GOLD)); }
            if (b.flagship) for (int i = 0; i < 3; i++) { // bosses still waiting on its deck: they'll come through the water instead
                LivingEntity w = b.inv.bosses[i];
                Crew wc = w == null ? null : crew.get(w.getUniqueId());
                if (wc != null && wc.waiting) { crew.remove(w.getUniqueId()); w.remove(); b.inv.bosses[i] = null; }
            }
            if (b.flagship && b.inv.stage > 0 && b.inv.stage < 4 && b.inv.boss != null && b.inv.boss.isValid() && by != null) // the boss goes into the water after you
                b.inv.boss.setVelocity(by.getLocation().toVector().subtract(b.inv.boss.getLocation().toVector()).setY(0).normalize().multiply(0.8).setY(0.6));
        }
    }

    /** Gone for good: the ship, its crew (unless they're off fighting), its barriers. */
    void discard(Ship s) {
        Brain b = s.ai;
        s.despawn();
        s.clearBarriers();
        pl.ships.remove(s.id);
        brains.remove(s.id);
        for (Iterator<Map.Entry<UUID, Crew>> it = crew.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Crew> e = it.next();
            if (e.getValue().home != b) continue;
            Entity m = Bukkit.getEntity(e.getKey());
            if (m != null && (b == null || b.inv == null || !e.getValue().kind.boss)) { m.remove(); it.remove(); }
        }
    }

    // =====================================================================================================
    //  the crew: abilities and swimming
    // =====================================================================================================
    void crewTick(long now) {
        for (Map.Entry<UUID, Crew> e : new ArrayList<>(crew.entrySet())) { // a copy: abilities can call up more crew
            Entity ent = Bukkit.getEntity(e.getKey());
            if (!(ent instanceof LivingEntity m) || !m.isValid() || m.isDead()) { crew.remove(e.getKey()); continue; }
            Crew c = e.getValue();
            if (c.waiting) continue; // on the deck, waiting for its turn
            if (c.kind == Kind.WRAITH) { // a ghost: souls drift off it, and it moans now and then
                Location gl = m.getLocation().add(0, 1.1, 0);
                m.getWorld().spawnParticle(Particle.SOUL, gl, 2, 0.3, 0.5, 0.3, 0.01);
                m.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, gl, 1, 0.25, 0.4, 0.25, 0.005);
                if (rnd.nextInt(16) == 0) m.getWorld().playSound(gl, Sound.ENTITY_VEX_AMBIENT, SoundCategory.HOSTILE, 0.8f, 0.6f);
                // Bedrock has no glowing outline, so an invisible ghost would be truly invisible there: Bedrock players
                // get its shape in pale soul-blue dust instead (a 2-block figure where it stands)
                Particle.DustOptions ghostDust = new Particle.DustOptions(Color.fromRGB(170, 235, 255), 1.3f);
                for (Player bp : m.getWorld().getPlayers()) {
                    if (!FaultlineShips.bedrock(bp) || bp.getLocation().distanceSquared(m.getLocation()) > 40 * 40) continue;
                    bp.spawnParticle(Particle.DUST, m.getLocation().add(0, 1.0, 0), 10, 0.22, 0.6, 0.22, 0, ghostDust);
                    bp.spawnParticle(Particle.DUST, m.getLocation().add(0, 1.85, 0), 4, 0.15, 0.12, 0.15, 0, ghostDust);
                }
            }
            Player t = nearest(m.getLocation(), c.kind.boss ? 40 : 30);
            if (m instanceof Mob mob && t != null && (mob.getTarget() == null || !mob.getTarget().isValid())) mob.setTarget(t);
            // in the water, skeletons swim after you (they'd sink otherwise)
            if (m.isInWater() && !m.isInsideVehicle() && t != null && c.kind != Kind.SHARK && c.kind != Kind.CORSAIR) {
                Vector v = t.getLocation().toVector().subtract(m.getLocation().toVector());
                double sp = cfg("swim-speed", 0.2) * (c.kind.boss ? 1.2 : 1);
                Vector h = v.clone().setY(0);
                if (h.lengthSquared() > 1e-4) h.normalize().multiply(sp);
                double up = m.getEyeLocation().getBlock().isLiquid() ? 0.12 : (v.getY() > 1 ? 0.35 : 0.02);
                m.setVelocity(h.setY(up));
            }
            if (c.fuse >= 0 && now >= c.fuse) { crew.remove(e.getKey()); blowUp(m); continue; }
            if (now < c.next || t == null) continue;
            ability(m, c, t, now);
        }
    }

    Player nearest(Location l, double r) {
        Player best = null;
        double bd = r * r;
        for (Player p : l.getWorld().getPlayers()) {
            if (!fighting(p)) continue;
            double d = p.getLocation().distanceSquared(l);
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    void ability(LivingEntity m, Crew c, Player t, long now) {
        double d = m.getLocation().distance(t.getLocation());
        World w = m.getWorld();
        switch (c.kind) {
            case GUNNER -> { // lobs a lit cannonball
                c.next = now + 120;
                if (d > 22 || d < 3) return;
                Vector v = t.getLocation().toVector().subtract(m.getEyeLocation().toVector());
                Vector vel = v.clone().setY(0).multiply(1 / Math.max(1, d) * 0.9).setY(0.35 + v.getY() * 0.04 + d * 0.012);
                Snowball g = m.launchProjectile(Snowball.class, vel);
                g.setItem(pl.ball());
                g.getPersistentDataContainer().set(grenadeKey, PersistentDataType.BYTE, (byte) 1);
                w.playSound(m.getLocation(), Sound.ENTITY_TNT_PRIMED, SoundCategory.HOSTILE, 0.8f, 1.6f);
            }
            case BOARDER -> { // leaps across onto you
                c.next = now + 140;
                if (d < 4 || d > 16) return;
                if (m.isInsideVehicle()) m.leaveVehicle();
                Vector v = t.getLocation().toVector().subtract(m.getLocation().toVector());
                m.setVelocity(v.clone().setY(0).multiply(0.11).setY(0.75 + Math.max(0, v.getY()) * 0.08));
                w.playSound(m.getLocation(), Sound.ENTITY_SKELETON_AMBIENT, SoundCategory.HOSTILE, 1f, 1.4f);
                w.spawnParticle(Particle.CLOUD, m.getLocation(), 8, 0.2, 0.1, 0.2, 0.02);
            }
            case POWDER_MONKEY -> {
                c.next = now + 5;
                if (m.isInsideVehicle()) return;
                if (d < 2.6 && c.fuse < 0) { c.fuse = now + 20; w.playSound(m.getLocation(), Sound.ENTITY_TNT_PRIMED, SoundCategory.HOSTILE, 1f, 1f); }
            }
            case BOSUN -> { // the chain: drags you in, and drives the crew on
                c.next = now + 100;
                if (d < 10 && d > 2.5) {
                    t.setVelocity(m.getLocation().toVector().subtract(t.getLocation().toVector()).normalize().multiply(0.9).setY(0.3));
                    w.playSound(t.getLocation(), Sound.BLOCK_CHAIN_HIT, SoundCategory.HOSTILE, 1.2f, 0.7f);
                }
                for (Entity o : m.getNearbyEntities(8, 4, 8)) if (isPirate(o) && o instanceof LivingEntity le)
                    le.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 0, false, true));
            }
            case NAVIGATOR -> { // spots you: the cannons aim true at a glowing target
                c.next = now + 160;
                if (d > 44) return;
                t.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 120, 0, false, false));
                t.sendActionBar(Component.text("A Skeleton Navigator has spotted you: the cannons aim true!", NamedTextColor.RED));
                w.playSound(t.getLocation(), Sound.ITEM_SPYGLASS_USE, SoundCategory.HOSTILE, 1f, 0.8f);
            }
            case SON -> son(m, c, t, d, now);
            case COMMANDER -> commander(m, c, t, d, now);
            case CAPTAIN -> captain(m, c, t, d, now);
            default -> c.next = now + 40;
        }
    }

    void blowUp(LivingEntity m) {
        Location at = m.getLocation();
        if (!m.isDead()) m.remove(); // not one that's dying (its death event is still running)
        pl.ballExploding = true;
        try { at.getWorld().createExplosion(at, (float) cfg("powder-monkey-power", 2.2), false, false); }
        finally { pl.ballExploding = false; }
    }

    // ---- the bosses ----
    void son(LivingEntity m, Crew c, Player t, double d, long now) {
        c.next = now + 100;
        World w = m.getWorld();
        switch (rnd.nextInt(3)) {
            case 0 -> { // lunge
                m.setVelocity(t.getLocation().toVector().subtract(m.getLocation().toVector()).setY(0).normalize().multiply(1.3).setY(0.25));
                w.playSound(m.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 1.3f, 1.3f);
                Bukkit.getScheduler().runTaskLater(pl, () -> { if (m.isValid() && t.isValid() && m.getLocation().distance(t.getLocation()) < 3) t.damage(cfg("bosses.son-lunge", 10), m); }, 8);
            }
            case 1 -> { // smoke bomb: blinds you and he's behind you
                w.spawnParticle(Particle.LARGE_SMOKE, m.getLocation().add(0, 1, 0), 60, 1.5, 1, 1.5, 0.02);
                w.playSound(m.getLocation(), Sound.ENTITY_GENERIC_EXTINGUISH_FIRE, SoundCategory.HOSTILE, 1.2f, 0.6f);
                for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(m.getLocation()) < 36) p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0));
                Vector back = t.getLocation().getDirection().setY(0).normalize().multiply(-1.6);
                Location behind = t.getLocation().add(back);
                if (behind.getBlock().isPassable()) m.teleport(behind.setDirection(t.getLocation().toVector().subtract(behind.toVector())));
            }
            default -> { // flintlock
                Arrow a = m.launchProjectile(Arrow.class, t.getEyeLocation().toVector().subtract(m.getEyeLocation().toVector()).normalize().multiply(3.2));
                a.setDamage(cfg("bosses.son-shot", 6));
                a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                w.playSound(m.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 0.8f, 1.8f);
                w.spawnParticle(Particle.SMOKE, m.getEyeLocation(), 10, 0.1, 0.1, 0.1, 0.02);
            }
        }
    }

    void commander(LivingEntity m, Crew c, Player t, double d, long now) {
        c.next = now + 120;
        World w = m.getWorld();
        switch (rnd.nextInt(3)) {
            case 0 -> { // volley: a fan of arrows
                Vector dir = t.getEyeLocation().toVector().subtract(m.getEyeLocation().toVector()).normalize();
                for (int i = -3; i <= 3; i++) {
                    double a = Math.toRadians(i * 7), cs = Math.cos(a), sn = Math.sin(a);
                    Vector v = new Vector(dir.getX() * cs - dir.getZ() * sn, dir.getY() + 0.05, dir.getX() * sn + dir.getZ() * cs).multiply(2.2);
                    Arrow ar = m.launchProjectile(Arrow.class, v);
                    ar.setDamage(4);
                    ar.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                }
                w.playSound(m.getLocation(), Sound.ENTITY_ARROW_SHOOT, SoundCategory.HOSTILE, 1.5f, 0.7f);
            }
            case 1 -> { // rally: more crew
                long have = crew.values().stream().filter(x -> x.summoned && x.home == c.home).count();
                if (have < 8) for (int i = 0; i < 3; i++) {
                    LivingEntity s = spawnMob(rnd.nextBoolean() ? Kind.DECKHAND : Kind.MUSKETEER, m.getLocation().add(rnd.nextGaussian(), 0.2, rnd.nextGaussian()), c.home);
                    if (s != null) crew.get(s.getUniqueId()).summoned = true;
                }
                w.playSound(m.getLocation(), Sound.ENTITY_SKELETON_AMBIENT, SoundCategory.HOSTILE, 2f, 0.5f);
            }
            default -> { // war horn: the crew fights harder
                try { w.playSound(m.getLocation(), Sound.ITEM_GOAT_HORN_SOUND_7, SoundCategory.HOSTILE, 3f, 0.7f); } catch (RuntimeException ignored) { }
                for (Entity o : m.getNearbyEntities(12, 6, 12)) if (isPirate(o) && o instanceof LivingEntity le)
                    le.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 200, 0, false, true));
            }
        }
    }

    void captain(LivingEntity m, Crew c, Player t, double d, long now) {
        c.next = now + 90;
        World w = m.getWorld();
        AttributeInstance max = m.getAttribute(Attribute.MAX_HEALTH);
        if (!c.ghostCrew && max != null && m.getHealth() < max.getValue() / 2) { // the ghost crew rises
            c.ghostCrew = true;
            for (int i = 0; i < 4; i++) spawnMob(Kind.WRAITH, m.getLocation().add(rnd.nextGaussian() * 2, 0.2, rnd.nextGaussian() * 2), c.home);
            w.playSound(m.getLocation(), Sound.ENTITY_WITHER_SPAWN, SoundCategory.HOSTILE, 1f, 1.4f);
            w.playSound(m.getLocation(), Sound.ENTITY_VEX_CHARGE, SoundCategory.HOSTILE, 1.5f, 0.5f);
            w.spawnParticle(Particle.SOUL, m.getLocation().add(0, 1, 0), 60, 2, 1, 2, 0.05);
            for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(m.getLocation()) < 60 * 60) p.sendTitle("", ChatColor.DARK_RED + "\"To me, my ghosts!\"", 5, 40, 10);
            return;
        }
        switch (rnd.nextInt(4)) {
            case 0 -> { // cutlass sweep
                w.spawnParticle(Particle.SWEEP_ATTACK, m.getLocation().add(0, 1.2, 0), 12, 2, 0.3, 2, 0);
                w.playSound(m.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 2f, 0.6f);
                for (Player p : w.getPlayers()) if (fighting(p) && p.getLocation().distanceSquared(m.getLocation()) < 4.8 * 4.8) {
                    p.damage(cfg("bosses.captain-sweep", 12), m);
                    p.setVelocity(p.getLocation().toVector().subtract(m.getLocation().toVector()).setY(0).normalize().multiply(1.1).setY(0.45));
                }
            }
            case 1 -> { // broadside: cannon fire comes down where you stand
                List<Location> marks = new ArrayList<>();
                for (Player p : w.getPlayers()) if (fighting(p) && p.getLocation().distanceSquared(m.getLocation()) < 30 * 30) marks.add(p.getLocation().clone());
                for (int i = 0; i < 3; i++) marks.add(t.getLocation().clone().add(rnd.nextGaussian() * 3, 0, rnd.nextGaussian() * 3));
                for (Location l : marks) w.spawnParticle(Particle.DUST, l.clone().add(0, 0.2, 0), 40, 1.2, 0.05, 1.2, 0, new Particle.DustOptions(Color.RED, 1.6f));
                w.playSound(m.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1f, 0.5f);
                if (m instanceof Mob mm) mm.getWorld();
                for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(m.getLocation()) < 40 * 40) p.sendTitle("", ChatColor.RED + "\"Fire!\"", 0, 25, 5);
                Bukkit.getScheduler().runTaskLater(pl, () -> {
                    for (Location l : marks) {
                        w.spawnParticle(Particle.EXPLOSION, l, 2, 0.5, 0.3, 0.5, 0);
                        w.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.4f, 0.9f);
                        for (Player p : w.getPlayers()) if (fighting(p) && p.getLocation().distanceSquared(l) < 2.4 * 2.4) p.damage(cfg("bosses.captain-broadside", 10), m.isValid() ? m : null);
                    }
                }, 30);
            }
            case 2 -> { // anchor slam: up, then down on you
                m.setVelocity(new Vector(0, 1.1, 0));
                w.playSound(m.getLocation(), Sound.BLOCK_CHAIN_BREAK, SoundCategory.HOSTILE, 2f, 0.5f);
                Bukkit.getScheduler().runTaskLater(pl, () -> {
                    if (!m.isValid()) return;
                    m.setVelocity(t.getLocation().toVector().subtract(m.getLocation().toVector()).multiply(0.12).setY(-1.2));
                }, 12);
                Bukkit.getScheduler().runTaskLater(pl, () -> {
                    if (!m.isValid()) return;
                    w.spawnParticle(Particle.EXPLOSION, m.getLocation(), 6, 2, 0.2, 2, 0);
                    w.playSound(m.getLocation(), Sound.BLOCK_ANVIL_LAND, SoundCategory.HOSTILE, 2f, 0.5f);
                    for (Player p : w.getPlayers()) if (fighting(p) && p.getLocation().distanceSquared(m.getLocation()) < 6 * 6) {
                        p.damage(cfg("bosses.captain-slam", 14), m);
                        p.setVelocity(new Vector(0, 0.8, 0));
                    }
                }, 26);
            }
            default -> { // flintlock blast: a cone in front of him
                Vector f = t.getLocation().toVector().subtract(m.getLocation().toVector()).setY(0).normalize();
                w.spawnParticle(Particle.FLAME, m.getEyeLocation().add(f.clone().multiply(2)), 40, 1, 0.4, 1, 0.05);
                w.playSound(m.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.4f, 1.4f);
                for (Player p : w.getPlayers()) {
                    if (!fighting(p)) continue;
                    Vector to = p.getLocation().toVector().subtract(m.getLocation().toVector());
                    double dist = to.length();
                    if (dist < 8 && to.setY(0).normalize().dot(f) > 0.7) { p.damage(cfg("bosses.captain-blast", 10), m); p.setFireTicks(60); }
                }
            }
        }
    }

    // =====================================================================================================
    //  events
    // =====================================================================================================
    @EventHandler(ignoreCancelled = true)
    public void onBurn(EntityCombustEvent event) { // pirates don't burn in the sun
        if (isPirate(event.getEntity()) && !(event instanceof org.bukkit.event.entity.EntityCombustByEntityEvent)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFriendlyTarget(EntityTargetLivingEntityEvent event) {
        if (isPirate(event.getEntity()) && isPirate(event.getTarget())) event.setCancelled(true);
    }

    @EventHandler
    public void onHitWaiting(EntityDamageByEntityEvent event) {
        Crew c = crew.get(event.getEntity().getUniqueId());
        if (c == null || !c.waiting) return;
        event.setCancelled(true);
        Entity d = event.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity sh) d = sh;
        if (d instanceof Player p) p.sendActionBar(Component.text(c.kind.title + " waits for his turn.", NamedTextColor.GRAY));
    }

    @EventHandler(ignoreCancelled = true)
    public void onFriendlyFire(EntityDamageByEntityEvent event) {
        Entity d = event.getDamager();
        if (d instanceof Projectile pr && pr.getShooter() instanceof Entity sh) d = sh;
        if (isPirate(d) && isPirate(event.getEntity())) event.setCancelled(true);
    }

    /** Musketeers' shots are fast and hard; a flintlock's crack and smoke. */
    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        Crew c = crew.get(event.getEntity().getUniqueId());
        if (c == null || !(event.getProjectile() instanceof AbstractArrow a)) return;
        a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        if (c.kind == Kind.MUSKETEER) {
            a.setVelocity(a.getVelocity().multiply(1.6));
            a.setDamage(a.getDamage() * 1.5);
            event.getEntity().getWorld().playSound(event.getEntity().getLocation(), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 0.6f, 1.9f);
            event.getEntity().getWorld().spawnParticle(Particle.SMOKE, event.getEntity().getEyeLocation(), 8, 0.1, 0.1, 0.1, 0.02);
        }
    }

    @EventHandler
    public void onGrenade(ProjectileHitEvent event) {
        Projectile p = event.getEntity();
        if (!p.getPersistentDataContainer().has(grenadeKey, PersistentDataType.BYTE)) return;
        Location at = p.getLocation();
        p.remove();
        pl.ballExploding = true;
        try { at.getWorld().createExplosion(at, (float) cfg("gunner-power", 1.4), false, false, p.getShooter() instanceof Entity e ? e : null); }
        finally { pl.ballExploding = false; }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity m = event.getEntity();
        Crew c = crew.remove(m.getUniqueId());
        if (c == null) return;
        event.getDrops().clear();
        boolean byPlayer = m.getKiller() != null;
        if (byPlayer && !c.summoned) {
            event.getDrops().add(new ItemStack(Material.BONE, 1 + rnd.nextInt(3)));
            if (rnd.nextDouble() < 0.3) event.getDrops().add(new ItemStack(Material.GOLD_NUGGET, 2 + rnd.nextInt(5)));
            if (rnd.nextDouble() < 0.2) { ItemStack b = pl.ball(); b.setAmount(1 + rnd.nextInt(3)); event.getDrops().add(b); }
            if (c.kind == Kind.POWDER_MONKEY) event.getDrops().add(new ItemStack(Material.GUNPOWDER, 2 + rnd.nextInt(3)));
        }
        if (c.kind == Kind.POWDER_MONKEY) blowUp(m); // shoot it and it still goes off
        if (!c.kind.boss) return;
        event.setDroppedExp((int) cfg("bosses.xp", 300));
        Invasion inv = c.home == null ? null : c.home.inv;
        if (inv == null) return;
        inv.boss = null;
        inv.bossBar.removeAll();
        for (UUID f : inv.fighters) {
            Player p = Bukkit.getPlayer(f);
            if (p != null) p.sendTitle(ChatColor.GOLD + c.kind.title, ChatColor.GRAY + "has fallen" + (m.getKiller() != null ? " to " + m.getKiller().getName() : ""), 5, 50, 10);
        }
        if (c.kind == Kind.CAPTAIN) win(inv);
        else inv.bossDueAt = pl.now + 60; // the next one comes up in 3 s (invasionTick)
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { nextRoll.remove(event.getPlayer().getUniqueId()); }

    // =====================================================================================================
    //  encounters
    // =====================================================================================================
    /** Once a minute for each player sailing the ocean: a skeleton ship (5%), or the whole invasion (1%). */
    void rolls(long now) {
        if (!pl.getConfig().getBoolean("pirates.enabled", true)) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Long next = nextRoll.get(p.getUniqueId());
            if (next == null) { nextRoll.put(p.getUniqueId(), now + (long) (cfg("check-seconds", 60) * 20)); continue; }
            if (now < next) continue;
            nextRoll.put(p.getUniqueId(), now + (long) (cfg("check-seconds", 60) * 20));
            Ship on = pl.ridingOn(p);
            if (on == null || on.ai != null || !on.sailing() || !fighting(p)) continue;
            if (pl.getConfig().getBoolean("pirates.require-ocean", true) && !ocean(p.getLocation())) continue;
            if (shipNear(p.getLocation(), 250) || invasionNear(p.getLocation(), 400) != null) continue;
            double r = rnd.nextDouble();
            if (r < cfg("invasion-chance", 0.01)) startInvasion(p, on);
            else if (r < cfg("invasion-chance", 0.01) + cfg("ship-chance", 0.05)) spawnNear(p, on);
        }
    }

    static boolean ocean(Location l) {
        try { return l.getBlock().getBiome().getKey().getKey().contains("ocean"); }
        catch (RuntimeException ex) { return false; }
    }

    boolean shipNear(Location l, double r) {
        for (Brain b : brains.values()) {
            Ship s = b.ship;
            if (l.getWorld().getName().equals(s.world) && Math.hypot(s.x - l.getX(), s.z - l.getZ()) < r) return true;
        }
        return false;
    }

    Invasion invasionNear(Location l, double r) {
        for (Invasion inv : invasions) if (!inv.over && inv.world.equals(l.getWorld()) && inv.center.distance(l) < r) return inv;
        return null;
    }

    /** A lone skeleton ship (a Pirate Ship) on the horizon, sailing at you. */
    Ship spawnNear(Player p, Ship on) {
        double base = rnd.nextDouble() * 360;
        for (int i = 0; i < 16; i++) {
            double a = Math.toRadians(base + i * 22.5), r = cfg("spawn-distance", 75) + (i % 2) * 15;
            double x = on.x - Math.sin(a) * r, z = on.z + Math.cos(a) * r;
            float yaw = bearing(x, z, on.x, on.z);
            Ship s = spawnShip(ShipType.PIRATE, on.world(), x, on.y, z, Math.round(yaw / 90f) * 90f, null, false);
            if (s == null) continue;
            s.yaw = yaw; s.sendRotation();
            for (Player q : on.world().getPlayers()) if (q.getLocation().distanceSquared(p.getLocation()) < 120 * 120) {
                q.sendTitle("", ChatColor.DARK_RED + "A skeleton ship on the horizon!", 10, 50, 10);
                q.playSound(q.getLocation(), Sound.EVENT_RAID_HORN, SoundCategory.HOSTILE, 0.6f, 1.4f);
            }
            return s;
        }
        return null;
    }

    /** The Pirate Invasion: the flagship far back, the fleet in a wide arc ahead of it. */
    Invasion startInvasion(Player p, Ship on) {
        World w = on.world();
        Invasion inv = new Invasion(new Location(w, on.x, on.y, on.z));
        double a0 = rnd.nextDouble() * 360;
        int want = (int) cfg("invasion.ships", 15);
        double fr = cfg("invasion.flagship-distance", 120);
        for (int i = 0; i < 12 && inv.flagship == null; i++) {
            double a = Math.toRadians(a0 + (i % 2 == 0 ? i : -i) * 6);
            double x = on.x - Math.sin(a) * fr, z = on.z + Math.cos(a) * fr;
            inv.flagship = spawnShip(ShipType.PIRATE, w, x, on.y, z, Math.round(bearing(x, z, on.x, on.z) / 90f) * 90f, inv, true);
        }
        if (inv.flagship == null) return null;
        inv.ships.add(inv.flagship);
        List<double[]> placed = new ArrayList<>();
        placed.add(new double[]{inv.flagship.x, inv.flagship.z});
        for (int tries = 0; tries < 400 && inv.ships.size() <= want; tries++) {
            // the arc and depth widen when it gets crowded, so the whole fleet always fits
            double arc = Math.min(170, cfg("invasion.arc-degrees", 70) + tries * 0.4), depth = cfg("invasion.depth", 45) + tries * 0.15;
            double a = Math.toRadians(a0 + (rnd.nextDouble() * 2 - 1) * arc);
            double r = cfg("invasion.min-distance", 62) + rnd.nextDouble() * depth;
            double x = on.x - Math.sin(a) * r, z = on.z + Math.cos(a) * r;
            boolean crowded = false;
            for (double[] q : placed) if (Math.hypot(q[0] - x, q[1] - z) < cfg("invasion.spacing", 22)) { crowded = true; break; }
            if (crowded) continue;
            ShipType t = rnd.nextDouble() < 0.6 ? ShipType.SLOOP : ShipType.BRIGANTINE;
            Ship s = spawnShip(t, w, x, on.y, z, Math.round(bearing(x, z, on.x, on.z) / 90f) * 90f, inv, false);
            if (s == null) continue;
            inv.ships.add(s);
            placed.add(new double[]{s.x, s.z});
        }
        inv.total = inv.ships.size();
        invasions.add(inv);
        for (Player q : w.getPlayers()) if (q.getLocation().distanceSquared(inv.center) < 200 * 200) {
            inv.fighters.add(q.getUniqueId());
            q.sendTitle(ChatColor.DARK_RED + "" + ChatColor.BOLD + "PIRATE INVASION", ChatColor.GRAY + "" + (inv.total - 1) + " skeleton ships and their flagship are coming", 10, 80, 20);
            q.playSound(q.getLocation(), Sound.EVENT_RAID_HORN, SoundCategory.HOSTILE, 1f, 0.7f);
        }
        pl.getLogger().info("Pirate Invasion at " + w.getName() + " " + (int) on.x + ", " + (int) on.z + ": " + inv.total + " ships.");
        return inv;
    }

    void invasionTick(Invasion inv, long now) {
        if (inv.over) { invasions.remove(inv); return; }
        // who's fighting: anyone near the fleet
        Location c = inv.center;
        double cx = 0, cz = 0; int n = 0;
        for (Ship s : inv.ships) if (pl.ships.containsKey(s.id)) { cx += s.x; cz += s.z; n++; }
        if (n > 0) c = new Location(inv.world, cx / n, inv.center.getY(), cz / n);
        Set<UUID> now2 = new HashSet<>();
        for (Player p : inv.world.getPlayers()) if (p.getLocation().distanceSquared(c) < 220 * 220 || p.getLocation().distanceSquared(inv.center) < 160 * 160) now2.add(p.getUniqueId());
        for (UUID u : inv.fighters) if (!now2.contains(u)) { inv.music.remove(u); Player p = Bukkit.getPlayer(u); if (p != null) { inv.bar.removePlayer(p); inv.bossBar.removePlayer(p); stopMusic(p); } }
        inv.fighters.clear();
        inv.fighters.addAll(now2);
        int alive = inv.alive();
        inv.bar.setTitle(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Pirate Invasion" + ChatColor.GRAY + "  " + alive + "/" + inv.total + " ships");
        inv.bar.setProgress(Math.max(0, Math.min(1, alive / (double) Math.max(1, inv.total))));
        boolean anyAlive = false;
        for (UUID u : inv.fighters) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            if (!inv.bar.getPlayers().contains(p)) inv.bar.addPlayer(p);
            if (fighting(p)) anyAlive = true;
        }
        // the music, looped, from the moment each fighter joins in
        double len = cfg("invasion.music-seconds", 291);
        for (UUID u : inv.fighters) {
            Player p = Bukkit.getPlayer(u);
            Long at = inv.music.get(u);
            if (p == null || (at != null && now - at < len * 20)) continue;
            inv.music.put(u, now);
            stopMusic(p);
            p.playSound(p.getLocation(), "faultline:pirates.music", SoundCategory.RECORDS, 1f, 1f);
        }
        // the bosses: on the flagship's deck once it's boarded (boarded()), or through the water if it's sunk
        if (inv.stage == 0 && (inv.flagship.wrecked || !pl.ships.containsKey(inv.flagship.id))) nextBoss(inv);
        if (inv.boss != null && !inv.boss.isValid()) { inv.boss = null; inv.stage--; inv.bossDueAt = now + 40; } // lost (unloaded): again
        if (inv.bossDueAt >= 0 && now >= inv.bossDueAt) { inv.bossDueAt = -1; nextBoss(inv); }
        if (inv.boss != null && inv.boss.isValid()) {
            AttributeInstance max = inv.boss.getAttribute(Attribute.MAX_HEALTH);
            inv.bossBar.setProgress(Math.max(0, Math.min(1, inv.boss.getHealth() / (max == null ? 1 : max.getValue()))));
            for (UUID u : inv.fighters) { Player p = Bukkit.getPlayer(u); if (p != null && !inv.bossBar.getPlayers().contains(p)) inv.bossBar.addPlayer(p); }
        }
        // giving up: nobody left fighting for a minute, or it's run long
        if (!anyAlive) { if (inv.quietSince < 0) inv.quietSince = now; }
        else inv.quietSince = -1;
        if ((inv.quietSince >= 0 && now - inv.quietSince > 1200) || now - inv.started > cfg("invasion.max-minutes", 25) * 1200) end(inv, false);
    }

    /** The next of the flagship's three: the Captain's Son, the Skeleton Commander, the Skeleton Captain. */
    static Kind bossKind(int i) { return i == 0 ? Kind.SON : i == 1 ? Kind.COMMANDER : Kind.CAPTAIN; }

    /** Where each boss stands on the flagship's deck (local x, feet y, z): the Son at the bow, the Commander amidships, the Captain on the poop deck. */
    static final double[][] DECK_SPOTS = {{4, 4, 0}, {0, 4, -2}, {-7, 5, 0}};

    Location deckSpot(Ship f, int i) {
        double[] d = DECK_SPOTS[i];
        if (f.type != ShipType.PIRATE) d = f.type.seats[Math.min(f.type.seats.length - 1, 1 + i)];
        double[] w = Ship.toWorld(f.x, f.z, f.yaw, d[0], d[2]);
        Location l = new Location(f.world(), w[0], f.y + d[1] + 0.05, w[1]);
        l.setYaw(f.yaw + (i == 2 ? 0 : 180));
        return l;
    }

    /**
     * The next of the flagship's three: the Captain's Son, the Skeleton Commander, the Skeleton Captain.
     * All three stand on its deck once it's anchored; the ones whose turn hasn't come wait there (frozen, can't be hurt).
     * If the flagship's gone, they come for you through the water.
     */
    void nextBoss(Invasion inv) {
        if (inv.over || inv.stage >= 3) return;
        inv.stage++;
        int k = inv.stage - 1;
        Kind kind = bossKind(k);
        Ship f = inv.flagship;
        boolean deck = f != null && pl.ships.containsKey(f.id) && !f.wrecked && f.anchored;
        Brain home = f == null ? null : f.ai;
        if (deck) for (int i = k; i < 3; i++) {
            if (inv.bosses[i] != null && inv.bosses[i].isValid()) continue;
            inv.bosses[i] = spawnMob(bossKind(i), deckSpot(f, i), home);
            if (inv.bosses[i] != null && i > k) setWaiting(inv.bosses[i], true);
        }
        LivingEntity boss = inv.bosses[k];
        if (boss == null || !boss.isValid()) { // the flagship's gone: through the water
            Player near = null;
            for (UUID u : inv.fighters) { Player p = Bukkit.getPlayer(u); if (p != null && fighting(p)) { near = p; break; } }
            if (near == null) { inv.stage--; inv.bossDueAt = pl.now + 40; return; }
            boss = spawnMob(kind, near.getLocation().add(near.getLocation().getDirection().setY(0).normalize().multiply(8)), home);
            if (boss == null) { inv.stage--; inv.bossDueAt = pl.now + 40; return; }
            inv.bosses[k] = boss;
        }
        setWaiting(boss, false);
        Crew bc = crew.get(boss.getUniqueId());
        if (bc != null && bc.home == null) bc.home = new Brain(f, inv, true);
        inv.boss = boss;
        inv.bossBar.setTitle(ChatColor.DARK_RED + "" + ChatColor.BOLD + kind.title);
        inv.bossBar.setProgress(1);
        String line = switch (kind) {
            case SON -> "\"Father said I could have your ship.\"";
            case COMMANDER -> "\"Form up! Bows at the ready!\"";
            default -> "\"Who dares board the Black Gallows?\"";
        };
        for (UUID u : inv.fighters) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            p.sendTitle(ChatColor.DARK_RED + "" + ChatColor.BOLD + kind.title, ChatColor.GRAY + line, 10, 60, 15);
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SKELETON_AMBIENT, SoundCategory.HOSTILE, 1.5f, 0.5f);
            if (deck && k == 0) p.sendMessage(ChatColor.GOLD + "The Black Gallows has dropped anchor at " + (int) f.x + ", " + (int) f.z + ". " + ChatColor.GRAY
                    + "Climb her ladders and face her three captains, one after another.");
        }
    }

    /** A boss on the deck waiting for its turn: stands still and can't be hurt. */
    void setWaiting(LivingEntity m, boolean waiting) {
        Crew c = crew.get(m.getUniqueId());
        if (c != null) c.waiting = waiting;
        m.setAI(!waiting);
        m.setInvulnerable(waiting);
        Kind k = c == null ? null : c.kind;
        if (k != null) m.setCustomName(ChatColor.DARK_RED + "" + ChatColor.BOLD + k.title + (waiting ? ChatColor.GRAY + " (waiting)" : ""));
    }

    void win(Invasion inv) {
        inv.stage = 4;
        for (UUID u : inv.fighters) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            p.sendTitle(ChatColor.GOLD + "" + ChatColor.BOLD + "VICTORY", ChatColor.YELLOW + "The Pirate Invasion is beaten", 10, 80, 20);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1f, 1f);
            for (Map<?, ?> r : pl.getConfig().getMapList("pirates.invasion.rewards")) {
                double chance = r.get("chance") instanceof Number nn ? nn.doubleValue() : 1;
                if (rnd.nextDouble() < chance) run(String.valueOf(r.get("command")), p);
            }
        }
        end(inv, true);
    }

    /** Over: the fleet that's left sails off (it's gone), the music stops. */
    void end(Invasion inv, boolean won) {
        inv.over = true;
        for (UUID u : inv.fighters) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            stopMusic(p);
            if (!won) p.sendTitle("", ChatColor.GRAY + "The pirate fleet sails off into the fog...", 10, 60, 20);
        }
        inv.bar.removeAll();
        inv.bossBar.removeAll();
        for (Ship s : new ArrayList<>(inv.ships)) {
            if (!pl.ships.containsKey(s.id) || s.wrecked) continue;
            if (won && s == inv.flagship && s.ai != null) { s.ai.beaten = true; s.ai.quietSince = pl.now; continue; } // you're on its deck, and its hold is yours
            discard(s);
        }
        for (Iterator<Map.Entry<UUID, Crew>> it = crew.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Crew> e = it.next();
            if (e.getValue().home == null || e.getValue().home.inv != inv) continue;
            Entity m = Bukkit.getEntity(e.getKey());
            if (m != null) m.remove();
            it.remove();
        }
    }

    void stopMusic(Player p) { try { p.stopSound("faultline:pirates.music", SoundCategory.RECORDS); } catch (RuntimeException ignored) { } }

    /** Plugin off: no skeleton ship or pirate stays behind. */
    void shutdown() {
        for (Invasion inv : new ArrayList<>(invasions)) end(inv, false);
        for (Brain b : new ArrayList<>(brains.values())) discard(b.ship);
        for (UUID id : new ArrayList<>(crew.keySet())) { Entity m = Bukkit.getEntity(id); if (m != null) m.remove(); }
        crew.clear();
    }

    /** /ship pirates ... (admin) */
    boolean command(org.bukkit.command.CommandSender sender, String[] args) {
        Player p = sender instanceof Player pp ? pp : null;
        String sub = args.length > 1 ? args[1].toLowerCase() : "";
        switch (sub) {
            case "ship" -> {
                if (p == null) return msg(sender, "Players only.");
                Ship on = pl.ridingOn(p);
                if (on == null) on = standingOn(p);
                if (on == null) return msg(sender, ChatColor.RED + "Be on a ship at sea.");
                Ship s = spawnNear(p, on);
                return msg(sender, s == null ? ChatColor.RED + "No open water for it nearby." : ChatColor.GREEN + "A skeleton ship is coming.");
            }
            case "invasion" -> {
                if (p == null) return msg(sender, "Players only.");
                Ship on = pl.ridingOn(p);
                if (on == null) on = standingOn(p);
                if (on == null) return msg(sender, ChatColor.RED + "Be on a ship at sea.");
                Invasion inv = startInvasion(p, on);
                return msg(sender, inv == null ? ChatColor.RED + "No open sea for a fleet here." : ChatColor.GREEN + "The Pirate Invasion is coming: " + inv.total + " ships.");
            }
            case "horn" -> {
                Player to = args.length > 2 ? Bukkit.getPlayerExact(args[2]) : p;
                int amount = 1;
                if (args.length > 2) try { amount = Integer.parseInt(args[2]); to = args.length > 3 ? Bukkit.getPlayerExact(args[3]) : p; } catch (NumberFormatException ignored) { }
                if (to == null) return msg(sender, ChatColor.RED + "Player not found.");
                for (int i = 0; i < Math.max(1, Math.min(16, amount)); i++) to.getInventory().addItem(horn());
                return msg(sender, ChatColor.GREEN + "Gave a Cursed Pirate Horn to " + to.getName() + ".");
            }
            case "egg" -> { // /ship pirates egg <mob> [amount] [player]
                Kind k = args.length > 2 ? Kind.of(args[2]) : null;
                if (k == null) return msg(sender, ChatColor.RED + "/ship pirates egg <" + kinds() + "> [amount] [player]");
                int amount = 1;
                try { if (args.length > 3) amount = Integer.parseInt(args[3]); } catch (NumberFormatException e) { return msg(sender, ChatColor.RED + "Not a number: " + args[3]); }
                Player to = args.length > 4 ? Bukkit.getPlayerExact(args[4]) : p;
                if (to == null) return msg(sender, ChatColor.RED + "Player not found.");
                ItemStack egg = egg(k);
                egg.setAmount(Math.max(1, Math.min(64, amount)));
                to.getInventory().addItem(egg);
                return msg(sender, ChatColor.GREEN + "Gave " + to.getName() + " a " + k.title + " Spawn Egg.");
            }
            case "spawn" -> {
                Kind k = args.length > 2 ? Kind.of(args[2]) : null;
                if (k == null) return msg(sender, ChatColor.RED + "/ship pirates spawn <" + kinds() + ">");
                if (p == null) return msg(sender, "Players only.");
                LivingEntity m = spawnMob(k, p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(3)), null);
                return msg(sender, m == null ? ChatColor.RED + "Couldn't." : ChatColor.GREEN + "Spawned a " + k.title + ".");
            }
            case "stop" -> {
                for (Invasion inv : new ArrayList<>(invasions)) end(inv, false);
                for (Brain b : new ArrayList<>(brains.values())) discard(b.ship);
                return msg(sender, ChatColor.YELLOW + "All skeleton ships are gone.");
            }
            default -> {
                return msg(sender, ChatColor.GRAY + "/ship pirates <ship|invasion|horn [amount] [player]|spawn <mob>|egg <mob> [amount] [player]|stop>  (" + brains.size() + " skeleton ships, " + invasions.size() + " invasions)");
            }
        }
    }

    static String kinds() {
        StringBuilder sb = new StringBuilder();
        for (Kind k : Kind.values()) sb.append(sb.length() > 0 ? "|" : "").append(k.name().toLowerCase());
        return sb.toString();
    }

    static boolean msg(org.bukkit.command.CommandSender s, String m) { s.sendMessage(m); return true; }
}
