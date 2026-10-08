package net.faultlinesmp.bosses;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Trident;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.Event.Result;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.BooleanSupplier;

import static net.faultlinesmp.bosses.FaultlineBosses.*;

/**
 * THE WILD BOSSES (the "wild" update): the Leviathan (the deep ocean, fights ships too), the Sandworm King (deserts),
 * the Lich (the Deep Dark), the Frost Wyrm (the frozen peaks) and the Stone Golem (a lush-cave mini boss).
 *
 * They share everything here: a RIG of model parts (tools/wild_assets.py; WildParts.java holds each part's scale), the
 * fight loop (health scaled per fighter, phases, music, boss bar, boss form, the Boss Rush, loot), their summoning
 * items and the weapons they drop. Each boss is its own class (Leviathan.java, Sandworm.java, Lich.java,
 * FrostWyrm.java, StoneGolem.java) with just its body and its moves.
 *
 * Java players see each part as an ItemDisplay. Bedrock players (Geyser can't draw displays) see the same part as the
 * helmet of an armor stand only they can see, drawn by FaultlineWild.mcpack full size. Hitting that stand hits the boss.
 */
final class Wild implements Listener {

    static final String PART_TAG = "faultline_wild_part", STAND_TAG = "faultline_wild_stand", HIT_TAG = "faultline_wild_hitbox",
            MOB_TAG = "faultline_wild_mob", FX_TAG = "faultline_wild_fx";
    static final List<String> KINDS = List.of("leviathan", "sandworm", "lich", "frostwyrm", "golem");
    static final Map<String, String> NAMES = Map.of("leviathan", "The Leviathan", "sandworm", "The Sandworm King", "lich", "The Lich",
            "frostwyrm", "The Frost Wyrm", "golem", "The Stone Golem");
    /** Their Faultline Index entries (and the tag on their hitboxes, for the Index's kill detection). */
    static final Map<String, String> INDEX = Map.of("leviathan", "leviathan", "sandworm", "sandworm_king", "lich", "lich",
            "frostwyrm", "frost_wyrm", "golem", "stone_golem");
    static final Map<String, ChatColor> COLOR = Map.of("leviathan", ChatColor.DARK_AQUA, "sandworm", ChatColor.GOLD, "lich", ChatColor.DARK_PURPLE,
            "frostwyrm", ChatColor.AQUA, "golem", ChatColor.DARK_GREEN);
    /** Local -> display axes (a part's front +x = display +z at yaw 0), and the half turn item displays draw items with. */
    static final Quaternionf Q0 = new Quaternionf().rotationY((float) Math.toRadians(-90));
    static final Quaternionf FLIP = new Quaternionf().rotationY((float) Math.PI);

    final FaultlineBosses pl;
    final Random random;
    final NamespacedKey itemKey;
    final Map<String, Boss> bosses = new LinkedHashMap<>();
    final Map<String, Long> cooldownUntil = new HashMap<>();
    private final Map<UUID, Long> weaponCd = new HashMap<>();
    private int ticks;

    Wild(FaultlineBosses pl) {
        this.pl = pl;
        this.random = pl.random;
        itemKey = new NamespacedKey(pl, "wild_item");
        recipe("abyssal_lure", new String[]{"NPN", "PHP", "NPN"}, 'N', Material.NAUTILUS_SHELL, 'P', Material.PRISMARINE_CRYSTALS, 'H', Material.HEART_OF_THE_SEA);
        recipe("sandworm_drum", new String[]{"RSR", "SGS", "RSR"}, 'R', Material.RABBIT_HIDE, 'S', Material.CHISELED_SANDSTONE, 'G', Material.GOLD_BLOCK);
        recipe("lich_phylactery", new String[]{"EBE", "BTB", "EBE"}, 'E', Material.ECHO_SHARD, 'B', Material.BONE_BLOCK, 'T', Material.TOTEM_OF_UNDYING);
        recipe("frozen_horn", new String[]{"IDI", "DGD", "IDI"}, 'I', Material.BLUE_ICE, 'D', Material.DIAMOND, 'G', Material.GOAT_HORN);
    }

    private void recipe(String which, String[] shape, Object... ing) {
        ShapedRecipe r = new ShapedRecipe(new NamespacedKey(pl, "wild_" + which), item(which));
        r.shape(shape);
        for (int i = 0; i < ing.length; i += 2) r.setIngredient((Character) ing[i], (Material) ing[i + 1]);
        addRecipeSafely(r);
    }

    double c(String path, double def) { return pl.getConfig().getDouble("wild." + path, def); }

    static boolean ours(Entity e) {
        Set<String> t = e.getScoreboardTags();
        return t.contains(PART_TAG) || t.contains(STAND_TAG) || t.contains(HIT_TAG) || t.contains(MOB_TAG) || t.contains(FX_TAG);
    }

    // =====================================================================================================
    //  items: what summons them, and what they drop
    // =====================================================================================================
    static final Map<String, String[]> ITEMS = new LinkedHashMap<>();
    static {
        ITEMS.put("abyssal_lure", new String[]{"PAPER", "&3&lAbyssal Lure", "&7Drop it in the deep ocean, from a ship or a boat|&7or swimming. Something vast takes the bait.|&3Summons the Leviathan."});
        ITEMS.put("sandworm_drum", new String[]{"PAPER", "&6&lSandworm Drum", "&7Beat it on the sand of a desert or badlands.|&7The ground answers.|&6Summons the Sandworm King."});
        ITEMS.put("lich_phylactery", new String[]{"PAPER", "&5&lCursed Phylactery", "&7Break it open in the Deep Dark.|&7Something that should be dead comes for it.|&5Summons the Lich."});
        ITEMS.put("frozen_horn", new String[]{"PAPER", "&b&lFrozen Horn", "&7Sound it on a frozen peak or snowy slope.|&7The sky answers with wings.|&bSummons the Frost Wyrm."});
        ITEMS.put("tidebreaker", new String[]{"TRIDENT", "&3&lTidebreaker", "&7The Leviathan's fang, made a trident.|&aWhere it lands, the sea bursts up:|&aenemies nearby are thrown and slowed.|&8Dropped by the Leviathan."});
        ITEMS.put("sandworm_fang", new String[]{"DIAMOND_SWORD", "&6&lSandworm Fang", "&7A tooth from the Sandworm King's maw.|&aRight-click: Burrow Dash &7(lunge through the|&7ground, blinding what you pass; 10 s)|&8Dropped by the Sandworm King."});
        ITEMS.put("lich_staff", new String[]{"STICK", "&5&lStaff of the Lich", "&7It still remembers its master.|&aRight-click: Soul Bolt &7(a withering skull,|&7no explosion; 3 s)|&8Dropped by the Lich."});
        ITEMS.put("glacial_fang", new String[]{"DIAMOND_SWORD", "&b&lGlacial Fang", "&7Carved from the Frost Wyrm's horn.|&aYour hits freeze and slow.|&8Dropped by the Frost Wyrm."});
    }

    ItemStack item(String which) {
        String[] d = ITEMS.get(which);
        if (d == null) return null;
        ItemStack s = new ItemStack(Material.valueOf(d[0]));
        ItemMeta m = s.getItemMeta();
        m.setDisplayName(ChatColor.translateAlternateColorCodes('&', d[1]));
        List<String> lore = new ArrayList<>();
        for (String l : d[2].split("\\|")) lore.add(ChatColor.translateAlternateColorCodes('&', l));
        m.setLore(lore);
        m.setItemModel(new NamespacedKey("faultline", which));
        if (d[0].equals("PAPER")) m.setMaxStackSize(16);
        else m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, which);
        s.setItemMeta(m);
        return s;
    }

    String itemType(ItemStack s) {
        if (s == null || !s.hasItemMeta()) return null;
        return s.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
    }

    void giveItem(Player p, String which, int amount) { for (int i = 0; i < amount; i++) { ItemStack it = item(which); if (it != null) pl.give(p, it); } }

    static String summonItemOf(String kind) {
        return switch (kind) { case "leviathan" -> "abyssal_lure"; case "sandworm" -> "sandworm_drum"; case "lich" -> "lich_phylactery";
            case "frostwyrm" -> "frozen_horn"; default -> null; };
    }

    static String kindOfItem(String item) {
        for (String k : KINDS) if (item.equals(summonItemOf(k))) return k;
        return null;
    }

    // =====================================================================================================
    //  where each one can be called
    // =====================================================================================================
    static final Set<Biome> OCEANS = new HashSet<>(List.of(Biome.OCEAN, Biome.DEEP_OCEAN, Biome.COLD_OCEAN, Biome.DEEP_COLD_OCEAN,
            Biome.LUKEWARM_OCEAN, Biome.DEEP_LUKEWARM_OCEAN, Biome.WARM_OCEAN, Biome.FROZEN_OCEAN, Biome.DEEP_FROZEN_OCEAN));
    static final Set<Biome> SANDS = new HashSet<>(List.of(Biome.DESERT, Biome.BADLANDS, Biome.ERODED_BADLANDS, Biome.WOODED_BADLANDS, Biome.BEACH));
    static final Set<Biome> PEAKS = new HashSet<>(List.of(Biome.FROZEN_PEAKS, Biome.JAGGED_PEAKS, Biome.SNOWY_SLOPES, Biome.GROVE,
            Biome.ICE_SPIKES, Biome.SNOWY_PLAINS, Biome.STONY_PEAKS, Biome.FROZEN_OCEAN, Biome.SNOWY_TAIGA));

    /** Null if it can be summoned here, else why not. */
    String whyNot(String kind, Player p) {
        Location l = p.getLocation();
        if (l.getWorld().getEnvironment() != World.Environment.NORMAL) return "Only in the Overworld.";
        Biome b = l.getBlock().getBiome();
        if (!pl.getConfig().getBoolean("wild.require-biome", true)) return null;
        return switch (kind) {
            case "leviathan" -> OCEANS.contains(b) && Leviathan.seaSpot(l, 18) != null ? null : "Only over open ocean (from a ship, a boat, or swimming).";
            case "sandworm" -> SANDS.contains(b) ? null : "Only in a desert or badlands.";
            case "lich" -> b == Biome.DEEP_DARK ? null : "Only in the Deep Dark (deep below, where the Wardens sleep).";
            case "frostwyrm" -> PEAKS.contains(b) ? null : "Only on frozen peaks, snowy slopes, groves or ice spikes.";
            default -> null;
        };
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = event.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        String type = itemType(hand);
        if (type == null) return;
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getType().isInteractable() && !p.isSneaking()) return;
        switch (type) {
            case "sandworm_fang" -> { event.setUseItemInHand(Result.DENY); burrowDash(p); return; }
            case "lich_staff" -> { event.setUseItemInHand(Result.DENY); event.setUseInteractedBlock(Result.DENY); soulBolt(p); return; }
            case "tidebreaker", "glacial_fang" -> { return; } // the trident throws as usual; the sword just hits
            default -> { }
        }
        String kind = kindOfItem(type);
        if (kind == null) return;
        event.setUseInteractedBlock(Result.DENY);
        event.setUseItemInHand(Result.DENY);
        if (bosses.containsKey(kind)) { p.sendActionBar(legacy(ChatColor.GRAY + NAMES.get(kind) + " is already out there.")); return; }
        if (p.getWorld().getDifficulty() == Difficulty.PEACEFUL) { p.sendActionBar(legacy(ChatColor.RED + "Nothing answers on Peaceful.")); return; }
        long cd = cooldownUntil.getOrDefault(kind, 0L);
        if (System.currentTimeMillis() < cd) { p.sendActionBar(legacy(ChatColor.GRAY + "Nothing answers yet... try again in " + ((cd - System.currentTimeMillis()) / 60000 + 1) + " min.")); return; }
        String why = whyNot(kind, p);
        if (why != null) { p.sendMessage(ChatColor.RED + why + ChatColor.GRAY + " (It wasn't used.)"); return; }
        if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        summon(kind, p.getLocation(), p);
    }

    // =====================================================================================================
    //  the weapons they drop
    // =====================================================================================================
    boolean ready(Player p, String what, double seconds) {
        long now = System.currentTimeMillis();
        long at = weaponCd.getOrDefault(p.getUniqueId(), 0L);
        if (now < at) { p.sendActionBar(legacy(ChatColor.GRAY + what + ": " + ((at - now) / 1000 + 1) + "s")); return false; }
        weaponCd.put(p.getUniqueId(), now + (long) (seconds * 1000));
        return true;
    }

    /** Sandworm Fang: a lunge along the ground, blinding and hurting what you pass through. */
    void burrowDash(Player p) {
        if (!ready(p, "Burrow Dash", c("weapons.sandworm-fang.cooldown-seconds", 10))) return;
        Vector f = Vendetta.flatDir(p.getLocation());
        p.setVelocity(f.clone().multiply(1.6).setY(0.25));
        World w = p.getWorld();
        w.playSound(p.getLocation(), Sound.BLOCK_SAND_BREAK, 1.4f, 0.6f);
        Set<UUID> hitAlready = new HashSet<>();
        int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(pl, task -> {
            if (!p.isOnline() || ++t[0] > 10) { task.cancel(); return; }
            Location l = p.getLocation();
            w.spawnParticle(Particle.BLOCK, l, 18, 0.5, 0.2, 0.5, 0, Material.SAND.createBlockData());
            for (Entity e : w.getNearbyEntities(l, 1.8, 1.5, 1.8)) {
                if (!(e instanceof LivingEntity le) || e == p || e instanceof ArmorStand || !hitAlready.add(e.getUniqueId())) continue;
                if (e instanceof Player o && (!survival(o) || !o.getWorld().getPVP())) continue;
                le.damage(c("weapons.sandworm-fang.dash-damage", 8), p);
                le.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 60, 0));
            }
        }, 1L, 1L);
    }

    /** Staff of the Lich: a slow withering skull that doesn't break blocks. */
    void soulBolt(Player p) {
        if (!ready(p, "Soul Bolt", c("weapons.lich-staff.cooldown-seconds", 3))) return;
        WitherSkull s = p.launchProjectile(WitherSkull.class, p.getEyeLocation().getDirection().multiply(1.2));
        s.setYield(0);
        s.setIsIncendiary(false);
        s.addScoreboardTag(FX_TAG);
        s.addScoreboardTag("faultline_soul_bolt");
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.7f, 1.5f);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBoltHit(ProjectileHitEvent e) {
        Projectile pr = e.getEntity();
        if (pr instanceof Trident t && "tidebreaker".equals(itemType(t.getItemStack())) && t.getShooter() instanceof Player p) {
            Location at = pr.getLocation();
            World w = at.getWorld();
            w.spawnParticle(Particle.SPLASH, at, 120, 2, 1, 2, 0.4);
            w.spawnParticle(Particle.BUBBLE_POP, at, 40, 1.5, 1, 1.5, 0.1);
            w.playSound(at, Sound.ENTITY_GENERIC_SPLASH, 1.5f, 0.6f);
            for (Entity en : w.getNearbyEntities(at, 3.5, 2.5, 3.5)) {
                if (!(en instanceof LivingEntity le) || en == p || en instanceof ArmorStand) continue;
                if (en instanceof Player o && (!survival(o) || !o.getWorld().getPVP())) continue;
                Vector away = en.getLocation().toVector().subtract(at.toVector()).setY(0);
                if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
                le.setVelocity(away.normalize().multiply(0.9).setY(0.6));
                le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
                le.damage(c("weapons.tidebreaker.burst-damage", 4), p);
            }
        }
        if (pr.getScoreboardTags().contains("faultline_leviathan_bile")) { Leviathan.bileLands(pr); pr.remove(); return; }
        if (pr.getScoreboardTags().contains("faultline_lich_bolt")) { Lich.boltLands(this, e); return; }
        if (pr.getScoreboardTags().contains("faultline_wild_boulder")) { e.setCancelled(true); return; }
        if (pr.getScoreboardTags().contains("faultline_soul_bolt") && pr.getShooter() instanceof Player p) {
            e.setCancelled(true);
            Location at = pr.getLocation();
            pr.getWorld().spawnParticle(Particle.SOUL, at, 20, 0.5, 0.5, 0.5, 0.02);
            pr.getWorld().playSound(at, Sound.PARTICLE_SOUL_ESCAPE, 1.2f, 0.8f);
            if (e.getHitEntity() instanceof LivingEntity le && le != p && !(le instanceof ArmorStand)) {
                if (!(le instanceof Player o) || survival(o) && o.getWorld().getPVP()) {
                    le.damage(c("weapons.lich-staff.damage", 8), p);
                    le.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 1));
                }
            }
            pr.remove();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSkullBlast(EntityExplodeEvent e) {
        if (ours(e.getEntity())) { e.blockList().clear(); e.setCancelled(true); }
    }

    /** Glacial Fang: a frozen hit. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFangHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity le)) return;
        if (!"glacial_fang".equals(itemType(p.getInventory().getItemInMainHand()))) return;
        le.setFreezeTicks(Math.min(le.getMaxFreezeTicks() + 60, le.getFreezeTicks() + 80));
        le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 1));
        le.getWorld().spawnParticle(Particle.SNOWFLAKE, le.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
    }

    // =====================================================================================================
    //  summoning, ticking, cleanup
    // =====================================================================================================
    Boss summon(String kind, Location at, Player by) {
        if (bosses.containsKey(kind)) return bosses.get(kind);
        Boss b;
        try {
            b = switch (kind) {
                case "leviathan" -> new Leviathan(this, at, by);
                case "sandworm" -> new Sandworm(this, at, by);
                case "lich" -> new Lich(this, at, by);
                case "frostwyrm" -> new FrostWyrm(this, at, by);
                case "golem" -> new StoneGolem(this, at, by);
                default -> null;
            };
        } catch (RuntimeException e) {
            pl.getLogger().log(java.util.logging.Level.WARNING, "Couldn't summon " + kind, e);
            return null;
        }
        if (b == null) return null;
        bosses.put(kind, b);
        b.started();
        return b;
    }

    Boss get(String kind) { return bosses.get(kind); }

    Boss any() { return bosses.isEmpty() ? null : bosses.values().iterator().next(); }

    boolean isBoss(Object o) { return o instanceof Boss b && bosses.containsValue(b); }

    void ended(Boss b) {
        bosses.values().remove(b);
        cooldownUntil.put(b.kind, System.currentTimeMillis() + (long) (b.c("cooldown-minutes", 10) * 60000));
    }

    void tick() {
        ticks++;
        for (Boss b : new ArrayList<>(bosses.values())) {
            try { b.tick(); }
            catch (RuntimeException e) {
                pl.getLogger().log(java.util.logging.Level.SEVERE, "[" + b.name() + "] error (it leaves; please report this):", e);
                b.leave(null);
            }
        }
        if (ticks % 40 == 0) standUpkeep();
        if (ticks % 1200 == 600) StoneGolem.naturalSpawns(this);
        if (ticks % 2400 == 1200) Leviathan.naturalSpawns(this);
    }

    void shutdown() {
        for (Boss b : new ArrayList<>(bosses.values())) b.removeEverything();
        bosses.clear();
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (ours(e)) e.remove();
    }

    /** Java players never see a Bedrock stand; Bedrock players always do. */
    void standUpkeep() {
        for (Boss b : bosses.values()) for (Part p : b.rig.parts) {
            if (p.stand == null || !p.stand.isValid()) continue;
            for (Player pl2 : p.stand.getWorld().getPlayers()) {
                if (bedrock(pl2)) { if (!pl2.canSee(p.stand)) pl2.showEntity(pl, p.stand); }
                else if (pl2.canSee(p.stand)) pl2.hideEntity(pl, p.stand);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        for (Boss b : bosses.values()) for (Part part : b.rig.parts) {
            if (part.stand == null || !part.stand.isValid()) continue;
            if (bedrock(p)) p.showEntity(pl, part.stand); else p.hideEntity(pl, part.stand);
        }
    }

    /** Whose is this hitbox (or Bedrock stand, or minion)? */
    Boss ownerOf(Entity e) {
        for (Boss b : bosses.values()) {
            if (b.hitboxes.contains(e) || b.minions.contains(e)) return b;
            for (Part p : b.rig.parts) if (e.equals(p.stand)) return b;
        }
        return null;
    }

    // =====================================================================================================
    //  events
    // =====================================================================================================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        Entity ent = e.getEntity();
        Set<String> tags = ent.getScoreboardTags();
        if (tags.contains(Lich.CRYSTAL_TAG)) { // a Phylactery: a player's hit shatters it (never an explosion)
            e.setCancelled(true);
            if (byPlayer(e) && get("lich") instanceof Lich l && ent instanceof org.bukkit.entity.EnderCrystal cr) l.crystalBroken(cr);
            return;
        }
        if (tags.contains(HIT_TAG) || tags.contains(STAND_TAG)) {
            e.setCancelled(true);
            Boss b = ownerOf(ent);
            if (b == null || b.dying) return;
            if (!byPlayer(e)) return;
            Player by = null;
            if (e instanceof EntityDamageByEntityEvent ee) {
                by = ee.getDamager() instanceof Player p ? p : ee.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player s ? s : null;
                if (by != null && MORPHED.contains(by.getUniqueId())) return;
                if (ee.getDamager() instanceof Projectile pr && !(pr instanceof Trident)) pr.remove(); // it stuck in the boss
            }
            if (ent instanceof LivingEntity le) hurtFlash(le);
            b.damage(e.getFinalDamage() > 0 ? e.getFinalDamage() : e.getDamage(), by);
            return;
        }
        if (e instanceof EntityDamageByEntityEvent ee && tags.contains(MOB_TAG)) { // a boss's side never hurts its own
            Entity src = ee.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Entity s ? s : ee.getDamager();
            if (ours(src)) e.setCancelled(true);
        }
    }

    static void hurtFlash(LivingEntity le) {
        try { le.playHurtAnimation(0); } catch (RuntimeException ignored) { } // just the red flash
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (!ours(e.getEntity()) && !(e.getTarget() != null && ours(e.getTarget()))) return;
        if (e.getTarget() != null && (ours(e.getTarget()) || e.getTarget() instanceof Player p && !survival(p))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(EntityCombustEvent e) { if (ours(e.getEntity())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) { if (ours(e.getEntity())) e.setCancelled(true); }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(MOB_TAG) && !e.getEntity().getScoreboardTags().contains(HIT_TAG)) return;
        e.getDrops().clear();
        e.setDroppedExp(e.getEntity().getScoreboardTags().contains(MOB_TAG) ? 8 : 0);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBossShot(ProjectileLaunchEvent e) { // their skeleton / stray minions' arrows stay theirs
        if (e.getEntity().getShooter() instanceof Entity s && ours(s)) e.getEntity().addScoreboardTag(FX_TAG);
    }

    // =====================================================================================================
    //  admin commands: /leviathan, /sandworm, /lich, /frostwyrm, /stonegolem
    // =====================================================================================================
    boolean command(String kind, CommandSender sender, String[] args) {
        if (!sender.hasPermission("bosses.admin")) { sender.sendMessage(ChatColor.RED + "You don't have permission to do that."); return true; }
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        Player self = sender instanceof Player p ? p : null;
        Boss b = bosses.get(kind);
        String label = kind.equals("golem") ? "stonegolem" : kind;
        switch (sub) {
            case "summon" -> {
                Location at = null;
                if (args.length >= 5) { // summon <world> <x> <y> <z> (the Boss Rush, consoles)
                    World w = Bukkit.getWorld(args[1]);
                    try { if (w != null) at = new Location(w, Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4])); }
                    catch (NumberFormatException ignored) { }
                } else if (self != null) at = self.getLocation();
                if (at == null) { sender.sendMessage(ChatColor.YELLOW + "/" + label + " summon [<world> <x> <y> <z>]"); return true; }
                if (b != null) { sender.sendMessage(ChatColor.GRAY + NAMES.get(kind) + " is already out."); return true; }
                Boss nb = summon(kind, at, self);
                sender.sendMessage(nb != null ? ChatColor.GREEN + NAMES.get(kind) + " is coming. " + ChatColor.GRAY + "(Fight it in survival; creative players don't count.)"
                        : ChatColor.RED + "It couldn't appear there (" + (kind.equals("leviathan") ? "it needs open water: 8+ blocks deep nearby" : "no room") + ").");
            }
            case "kill" -> { if (b != null) { b.leave(null); sender.sendMessage(ChatColor.GREEN + "Removed " + NAMES.get(kind) + "."); } else sender.sendMessage(ChatColor.GRAY + "It isn't out."); }
            case "phase" -> {
                if (b == null || args.length < 2) { sender.sendMessage(ChatColor.YELLOW + "/" + label + " phase <2|3>"); return true; }
                int ph;
                try { ph = Math.max(2, Math.min(3, Integer.parseInt(args[1]))); } catch (NumberFormatException ex) { sender.sendMessage(ChatColor.YELLOW + "/" + label + " phase <2|3>"); return true; }
                b.hp = Math.min(b.hp, b.maxHp * (ph == 3 ? b.c("phase3-at", 0.3) - 0.01 : b.c("phase2-at", 0.6) - 0.01));
            }
            case "item" -> {
                String which = args.length > 1 ? args[1].toLowerCase() : summonItemOf(kind);
                if (which == null || !ITEMS.containsKey(which)) { sender.sendMessage(ChatColor.YELLOW + "/" + label + " item <" + String.join("|", ITEMS.keySet()) + "> [amount] [player]"); return true; }
                int amount = 1; Player target = self;
                for (int i = 2; i < args.length; i++) {
                    Player online = Bukkit.getPlayerExact(args[i]);
                    if (online != null) target = online;
                    else try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[i]))); } catch (NumberFormatException ignored) { }
                }
                if (target == null) { sender.sendMessage("Who should get it?"); return true; }
                giveItem(target, which, amount);
                sender.sendMessage(ChatColor.GREEN + "Gave " + amount + " " + which + " to " + target.getName() + ".");
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "/" + label + " <summon [world x y z]|kill|phase <2|3>|item <name> [amount] [player]>");
        }
        return true;
    }

    // =====================================================================================================
    //  the rig: model parts (Java ItemDisplay + Bedrock armor stand)
    // =====================================================================================================
    static ItemStack partItem(String model) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey("faultline", "wild/" + model));
        it.setItemMeta(m);
        return it;
    }

    final class Rig {
        final World world;
        final List<Part> parts = new ArrayList<>();
        Rig(World world) { this.world = world; }

        Part add(String model, double k, Location at) {
            Part p = new Part(model, k);
            p.spawn(world, at);
            parts.add(p);
            return p;
        }

        void remove() { for (Part p : parts) p.remove(); parts.clear(); }
    }

    final class Part {
        final String model;
        final double[] meta;
        double k;
        ItemDisplay d;
        ArmorStand stand;
        Location at;

        Part(String model, double k) { this.model = model; this.k = k; meta = WildParts.of(model); }

        void spawn(World w, Location where) {
            Location l = where.clone(); l.setYaw(0); l.setPitch(0);
            at = where.clone();
            d = w.spawn(l, ItemDisplay.class, e -> {
                e.setItemStack(partItem(model));
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.setPersistent(false);
                e.addScoreboardTag(PART_TAG);
                e.setTeleportDuration(2);
                e.setInterpolationDuration(2);
                e.setViewRange(5f);
                e.setShadowRadius(0);
                e.setBrightness(new Display.Brightness(12, 12));
            });
            stand = w.spawn(where, ArmorStand.class, a -> {
                a.setVisibleByDefault(false);
                a.setPersistent(false);
                a.addScoreboardTag(STAND_TAG);
                a.setInvisible(true);
                a.setGravity(false);
                a.setSilent(true);
                a.setBasePlate(false);
                a.setMarker(false); // Geyser needs a real armor stand to draw the helmet
                a.getEquipment().setHelmet(partItem(model));
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    try { a.addEquipmentLock(slot, ArmorStand.LockType.REMOVING_OR_CHANGING); } catch (RuntimeException ignored) { }
                }
            });
            AttributeInstance sc = stand.getAttribute(Attribute.SCALE);
            if (sc != null) sc.setBaseValue(Math.max(0.0625, Math.min(16, k)));
            for (Player p : w.getPlayers()) if (bedrock(p)) p.showEntity(pl, stand);
            pose(where, where.getYaw(), 0, 0, null);
        }

        void resize(double nk) {
            k = nk;
            AttributeInstance sc = stand == null ? null : stand.getAttribute(Attribute.SCALE);
            if (sc != null) sc.setBaseValue(Math.max(0.0625, Math.min(16, k)));
        }

        /** Put the part's origin at pos, facing yaw (pitch: nose down +, roll: right side down +), with an extra turn in its own frame. */
        void pose(Location pos, float yaw, float pitch, float roll, Quaternionf local) {
            if (d == null || !d.isValid()) return;
            at = pos.clone(); at.setYaw(yaw); at.setPitch(pitch);
            Location l = pos.clone(); l.setYaw(0); l.setPitch(0);
            d.teleport(l);
            Quaternionf D = new Quaternionf().rotationY((float) Math.toRadians(-yaw)).rotateX((float) Math.toRadians(pitch))
                    .rotateZ((float) Math.toRadians(roll)).mul(Q0);
            if (local != null) D.mul(local);
            Vector3f off = new Vector3f((float) (meta[1] * k), (float) (meta[2] * k), (float) (meta[3] * k));
            D.transform(off);
            float s = (float) (meta[0] * k);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(2);
            d.setTransformation(new Transformation(off, new Quaternionf(D).mul(FLIP), new Vector3f(s, s, s), new Quaternionf()));
            if (stand != null && stand.isValid()) {
                Location sl = pos.clone(); sl.setYaw(yaw); sl.setPitch(0);
                stand.teleport(sl);
            }
        }

        void hide(boolean hidden) {
            if (d != null && d.isValid() && hidden) d.setViewRange(0f);
            else if (d != null && d.isValid()) d.setViewRange(5f);
            if (stand != null && stand.isValid()) stand.getEquipment().setHelmet(hidden ? null : partItem(model));
        }

        void remove() {
            if (d != null && d.isValid()) d.remove();
            if (stand != null && stand.isValid()) stand.remove();
        }
    }

    // =====================================================================================================
    //  the fight, shared by all of them
    // =====================================================================================================
    abstract static class Boss {
        final Wild w;
        final FaultlineBosses pl;
        final Random random;
        final String kind;
        final World world;
        final Location home;
        final Rig rig;
        double hp, maxHp;
        int phase = 1, ticks, attack = -1, at, cd = 60, lonely, scaledFor = 1, deathT;
        boolean dying, rewarded;
        final Set<UUID> fighters = new HashSet<>();
        final List<LivingEntity> hitboxes = new ArrayList<>();
        final List<LivingEntity> minions = new ArrayList<>();
        final List<BooleanSupplier> fx = new ArrayList<>();
        final BossBar bar;
        final Set<UUID> listeners = new HashSet<>();
        long musicStart = -1;
        Player focus, pilot;

        Boss(Wild w, String kind, Location at, Player by) {
            this.w = w;
            this.pl = w.pl;
            this.random = w.random;
            this.kind = kind;
            this.world = at.getWorld();
            this.home = at.clone();
            this.rig = w.new Rig(world);
            maxHp = hp = c("health", defHealth());
            if (by != null && survival(by)) fighters.add(by.getUniqueId());
            bar = Bukkit.createBossBar(color() + "" + ChatColor.BOLD + name(), barColor(), BarStyle.SEGMENTED_10);
        }

        double c(String path, double def) { return pl.getConfig().getDouble("wild." + kind + "." + path, def); }
        String name() { return NAMES.get(kind); }
        ChatColor color() { return COLOR.get(kind); }
        BarColor barColor() { return BarColor.WHITE; }

        abstract double defHealth();
        /** Movement + animation, every tick (also while a move runs). */
        abstract void bodyTick(List<Player> a);
        abstract int pick(List<Player> a);
        abstract void startMove(int move, Player target);
        /** The current move, every tick; call end() when it's done. */
        abstract void moveTick(List<Player> a);
        abstract List<MoveSlot> morphMoves();
        /** The boss's own loot for one fighter (on top of the shared bags and XP). */
        abstract void drops(Player p, Location at);
        abstract Location center();
        abstract boolean valid();
        abstract String music();
        abstract double musicLength();
        /** Lines: spawn, phase2, phase3, death, leave. */
        abstract String line(String what);
        void onPhase(int p) { }
        double damageTaken(double amount) { return amount; }
        void deathAnim(int t) {
            Location c = center();
            if (t % 4 == 0) world.spawnParticle(Particle.CLOUD, c, 12, 1.5, 1, 1.5, 0.05);
            if (t % 10 == 0) world.playSound(c, Sound.ENTITY_GENERIC_HURT, SoundCategory.HOSTILE, 2f, 0.5f);
        }

        /** Right after it's registered: music, a line, the bar. */
        void started() {
            say(line("spawn"));
            playMusic();
        }

        void say(String text) {
            if (text == null) return;
            for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(center()) < 96 * 96) p.sendMessage(color() + "[" + name() + "] " + ChatColor.WHITE + text);
        }

        List<Player> active() {
            List<Player> a = new ArrayList<>();
            double r = c("fight-radius", 56);
            Location c = center();
            for (Player p : world.getPlayers()) {
                if (!survival(p) || p.isDead()) continue;
                if (p.getLocation().distanceSquared(c) > r * r) continue;
                a.add(p);
            }
            return a;
        }

        void scaleFor(int n) {
            n = Math.max(1, Math.min(n, 10));
            if (n <= scaledFor) return;
            double frac = hp / maxHp;
            scaledFor = n;
            maxHp = c("health", defHealth()) * (1 + c("health-per-extra-fighter", 0.2) * (n - 1));
            hp = maxHp * frac;
        }

        void tick() {
            ticks++;
            if (dying) { deathTick(); return; }
            if (!valid()) { leave(null); return; }
            List<Player> a = active();
            for (Player p : a) fighters.add(p.getUniqueId());
            pilot = pl.pilot(this);
            if (a.isEmpty() && pilot == null) {
                if (++lonely > c("leave-after-ticks", 600)) { leave(line("leave")); return; }
            } else lonely = 0;
            if (ticks % 100 == 0) scaleFor(a.size());
            if (phase == 1 && hp < maxHp * c("phase2-at", 0.6)) startPhase(2);
            if (phase == 2 && hp < maxHp * c("phase3-at", 0.3)) startPhase(3);
            musicTick();
            fx.removeIf(BooleanSupplier::getAsBoolean);
            minions.removeIf(m -> !m.isValid() || m.isDead());
            bodyTick(a);
            if (attack >= 0) { at++; moveTick(a); }
            else if (--cd <= 0) {
                int m = pilot != null ? pl.takeMove(this) : pick(a);
                if (m >= 0 && (pilot != null || !a.isEmpty())) {
                    attack = m; at = 0;
                    focus = pilot != null ? pl.pilotTarget(pilot, a) : target(a);
                    startMove(m, focus);
                } else if (pilot == null) cd = 20;
            }
            updateBar();
        }

        void end() { attack = -1; cd = nextCd(); }

        int nextCd() {
            double base = phase == 3 ? c("move-gap-ticks.phase3", 34) : phase == 2 ? c("move-gap-ticks.phase2", 46) : c("move-gap-ticks.phase1", 60);
            return (int) (base * (0.8 + random.nextDouble() * 0.4));
        }

        Player target(List<Player> a) {
            if (a.isEmpty()) return null;
            Player best = null; double bd = Double.MAX_VALUE;
            Location c = center();
            for (Player p : a) { double d = p.getLocation().distanceSquared(c) * (0.6 + random.nextDouble() * 0.8); if (d < bd) { bd = d; best = p; } }
            return best;
        }

        void startPhase(int p) {
            if (p <= phase) return;
            phase = p;
            onPhase(p);
            say(line("phase" + p));
            for (Player pp : active()) pp.showTitle(Title.title(legacy(color() + "" + ChatColor.BOLD + name().toUpperCase()), legacy(ChatColor.GRAY + "Phase " + p),
                    Title.Times.times(java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(1400), java.time.Duration.ofMillis(400))));
        }

        void updateBar() {
            bar.setProgress(Math.max(0, Math.min(1, hp / maxHp)));
            bar.setTitle(color() + "" + ChatColor.BOLD + name() + ChatColor.GRAY + "  Phase " + phase);
            Location c = center();
            for (Player p : world.getPlayers()) {
                boolean near = p.getLocation().distanceSquared(c) < 80 * 80;
                if (near && !bar.getPlayers().contains(p)) bar.addPlayer(p);
                else if (!near && bar.getPlayers().contains(p)) bar.removePlayer(p);
            }
        }

        // ---------- music (a vanilla track each: the pack stays small) ----------
        void playMusic() {
            musicStart = System.currentTimeMillis();
            if (!pl.getConfig().getBoolean("wild." + kind + ".music.enabled", true)) return;
            for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(center()) < 80 * 80) listen(p);
        }

        String track() { return pl.getConfig().getString("wild." + kind + ".music.sound", music()); }

        void listen(Player p) {
            p.stopSound(SoundCategory.MUSIC);
            p.playSound(p, track(), SoundCategory.RECORDS, (float) c("music.volume", 1.0), 1f);
            listeners.add(p.getUniqueId());
        }

        void stopMusic() {
            for (UUID id : listeners) { Player p = Bukkit.getPlayer(id); if (p != null) p.stopSound(track(), SoundCategory.RECORDS); }
            listeners.clear();
            musicStart = -1;
        }

        void musicTick() {
            if (musicStart < 0 || !pl.getConfig().getBoolean("wild." + kind + ".music.enabled", true)) return;
            if (System.currentTimeMillis() - musicStart > c("music.length-seconds", musicLength()) * 1000) { stopMusic(); playMusic(); return; }
            if (ticks % 20 != 0) return;
            for (Player p : world.getPlayers())
                if (!listeners.contains(p.getUniqueId()) && survival(p) && p.getLocation().distanceSquared(center()) < 64 * 64) listen(p);
        }

        // ---------- hitting players ----------
        LivingEntity source() { return hitboxes.isEmpty() ? null : hitboxes.get(0); }

        void hit(Player p, double dmg, Vector from, Guard g) {
            if (!survival(p) || p.isDead()) return;
            pl.hurt(p, dmg * c("damage-multiplier", 1.0), source(), from, g);
        }

        /** Everyone within r of a spot (ground level, the column above it). */
        List<Player> near(Location c, double r, double up) {
            List<Player> out = new ArrayList<>();
            for (Player p : active()) {
                Location l = p.getLocation();
                double dx = l.getX() - c.getX(), dz = l.getZ() - c.getZ();
                if (dx * dx + dz * dz <= r * r && l.getY() > c.getY() - 1.5 && l.getY() < c.getY() + up) out.add(p);
            }
            return out;
        }

        // ---------- taking damage, losing ----------
        void damage(double amount, Player by) {
            if (dying) return;
            if (by != null) {
                if (!survival(by)) return;
                fighters.add(by.getUniqueId());
            }
            amount = damageTaken(amount);
            if (amount <= 0) return;
            hp -= amount;
            if (hp <= 0) { hp = 0; defeat(); }
        }

        void defeat() {
            dying = true; deathT = 0;
            attack = -1;
            stopMusic();
            bar.removeAll();
            for (LivingEntity m : minions) if (m.isValid()) m.remove();
            minions.clear();
            fx.clear();
            for (Entity e : world.getEntities()) if (e.getScoreboardTags().contains(FX_TAG) && e.getLocation().distanceSquared(center()) < 96 * 96) e.remove();
            say(line("death"));
        }

        void deathTick() {
            deathT++;
            deathAnim(deathT);
            if (deathT == 60) {
                Location c = center();
                Bukkit.broadcastMessage(color() + "" + ChatColor.BOLD + name() + ChatColor.YELLOW + " has been defeated!");
                for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(c) < 96 * 96)
                    p.showTitle(Title.title(legacy(color() + "" + ChatColor.BOLD + name().toUpperCase() + " FALLS"), legacy(ChatColor.GRAY + "Victory."),
                            Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(3000), java.time.Duration.ofMillis(800))));
                world.spawnParticle(Particle.EXPLOSION_EMITTER, c, 1);
                if (!rewarded) { rewarded = true; if (!pl.rushSuppressLoot(kind)) rewards(); }
                pl.rushDefeated(kind);
            }
            if (deathT > 80) {
                removeEverything();
                w.ended(this);
            }
        }

        void rewards() {
            Location c = center();
            for (UUID id : fighters) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.getWorld().equals(world)) continue;
                Location at = p.getLocation();
                int bags = (int) c("rewards.mythic-bags", 4) + random.nextInt((int) Math.max(1, c("rewards.mythic-bags-extra", 3)));
                if (bags > 0) pl.console("givemythicbag " + bags + " " + p.getName(), p);
                int goodie = (int) c("rewards.goodie-bags", 3);
                if (goodie > 0) pl.console("givegoodiebag " + goodie + " " + p.getName(), p);
                for (String line : pl.getConfig().getStringList("wild." + kind + ".rewards.items")) {
                    ItemStack it = parse(line);
                    if (it != null) pl.dropLocked(at, p, it);
                }
                drops(p, at);
                int xp = (int) c("rewards.xp", 1500);
                for (int left = xp; left > 0; ) { int n = Math.min(left, 100); left -= n; world.spawn(at, ExperienceOrb.class).setExperience(n); }
                pl.console("index discover " + p.getName() + " " + INDEX.get(kind), p);
            }
            world.playSound(c, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 2f, 1f);
        }

        /** "DIAMOND:4-8" / "TOTEM_OF_UNDYING:1:50" (50% chance). */
        ItemStack parse(String line) {
            try {
                String[] p = line.split(":");
                Material m = Material.valueOf(p[0].trim().toUpperCase());
                int lo = 1, hi = 1;
                if (p.length > 1) { String[] r = p[1].split("-"); lo = Integer.parseInt(r[0].trim()); hi = r.length > 1 ? Integer.parseInt(r[1].trim()) : lo; }
                if (p.length > 2 && random.nextDouble() * 100 >= Double.parseDouble(p[2].trim())) return null;
                return new ItemStack(m, lo + random.nextInt(Math.max(1, hi - lo + 1)));
            } catch (RuntimeException e) { return null; }
        }

        /** Its signature item, for one lucky fighter roll. */
        void maybeDrop(Player p, Location at, String which, double chance) {
            if (random.nextDouble() >= chance) return;
            ItemStack it = w.item(which);
            if (it == null) return;
            pl.dropLocked(at, p, it);
            p.sendMessage(color() + "" + ChatColor.BOLD + it.getItemMeta().getDisplayName() + ChatColor.RESET + ChatColor.GREEN + " dropped for you!");
        }

        void leave(String message) {
            if (message != null) Bukkit.broadcastMessage(message);
            removeEverything();
            w.bosses.values().remove(this);
        }

        void removeEverything() {
            stopMusic();
            bar.removeAll();
            rig.remove();
            for (LivingEntity h : hitboxes) if (h.isValid()) h.remove();
            hitboxes.clear();
            for (LivingEntity m : minions) if (m.isValid()) m.remove();
            minions.clear();
            fx.clear();
            for (Entity e : world.getEntities()) if (e.getScoreboardTags().contains(FX_TAG) && e.getLocation().distanceSquared(home) < 160 * 160) e.remove();
        }

        // ---------- helpers ----------
        Slime hitbox(Location at, int size, String name) {
            Slime s = world.spawn(at, Slime.class, sl -> {
                sl.setSize(size);
                sl.addScoreboardTag(HIT_TAG);
                sl.addScoreboardTag("faultline_" + INDEX.get(kind));
            });
            pl.setupHitbox(s, 1000, HIT_TAG, name);
            hitboxes.add(s);
            return s;
        }

        <T extends LivingEntity> T minion(Location at, Class<T> type, String name, double health, java.util.function.Consumer<T> more) {
            T m = world.spawn(at, type, e -> {
                e.addScoreboardTag(MOB_TAG);
                e.addScoreboardTag("faultline_" + INDEX.get(kind) + "_minion");
                e.setPersistent(false);
                e.setRemoveWhenFarAway(false);
                e.setCustomName(name);
                e.setCustomNameVisible(false);
                AttributeInstance mh = e.getAttribute(Attribute.MAX_HEALTH);
                if (mh != null) { mh.setBaseValue(health); e.setHealth(health); }
                if (more != null) more.accept(e);
            });
            minions.add(m);
            return m;
        }

        /** The ground under a spot (the top solid block +1), searching a few blocks up and down. */
        double ground(Location l) {
            int y = l.getBlockY() + 4;
            for (int i = 0; i < 16; i++, y--) {
                Block b = world.getBlockAt(l.getBlockX(), y - 1, l.getBlockZ());
                if (b.getType().isSolid() && !world.getBlockAt(l.getBlockX(), y, l.getBlockZ()).getType().isSolid()) return y;
            }
            return world.getHighestBlockYAt(l.getBlockX(), l.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        }

        void warnRing(Location c, double r, Color col) {
            int n = (int) Math.max(12, r * 6);
            for (int i = 0; i < n; i++) {
                double a = i * Math.PI * 2 / n;
                world.spawnParticle(Particle.DUST, c.getX() + Math.cos(a) * r, c.getY() + 0.15, c.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0, new Particle.DustOptions(col, 1.4f));
            }
        }

        void warnLine(Location from, Vector dir, double len, Color col) {
            Vector d = dir.clone().setY(0);
            if (d.lengthSquared() < 1e-4) return;
            d.normalize();
            for (double t = 0; t < len; t += 0.7) {
                Location l = from.clone().add(d.clone().multiply(t));
                world.spawnParticle(Particle.DUST, l.getX(), ground(l) + 0.15, l.getZ(), 1, 0.15, 0, 0.15, 0, new Particle.DustOptions(col, 1.3f));
            }
        }

        /** Runs every tick until it returns true. */
        void later(int delay, Runnable r) {
            int[] t = {0};
            fx.add(() -> { if (++t[0] < delay) return false; r.run(); return true; });
        }

        static Vector flat(Vector v) { Vector o = v.clone().setY(0); return o.lengthSquared() < 1e-6 ? new Vector(1, 0, 0) : o.normalize(); }

        static float yawOf(Vector v) { return (float) Math.toDegrees(Math.atan2(-v.getX(), v.getZ())); }

        static float pitchOf(Vector v) {
            double h = Math.hypot(v.getX(), v.getZ());
            return (float) -Math.toDegrees(Math.atan2(v.getY(), Math.max(1e-6, h)));
        }

        static Vector dirOf(float yaw) { return new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw))); }

        /** A point in a frame (yaw): forward f, up u, right r. */
        static Location offset(Location base, float yaw, double f, double u, double r) {
            Vector fw = dirOf(yaw), right = new Vector(-fw.getZ(), 0, fw.getX());
            return base.clone().add(fw.multiply(f)).add(0, u, 0).add(right.multiply(r));
        }

        static float turn(float from, float to, float max) {
            float d = ((to - from) % 360 + 540) % 360 - 180;
            return from + Math.max(-max, Math.min(max, d));
        }

        static void attr(LivingEntity e, Attribute a, double v) {
            AttributeInstance ai = e.getAttribute(a);
            if (ai != null) ai.setBaseValue(v);
        }
    }

    /** A body that follows its head (worms, serpents, tails): each segment keeps its spacing along the path walked. */
    static final class Chain {
        final List<Location> pts = new ArrayList<>();
        Chain(Location head, int n, double gap, Vector back) {
            for (int i = 0; i <= n; i++) pts.add(head.clone().add(back.clone().normalize().multiply(gap * i)));
        }

        /** Move the head; each next point is pulled to sit `gap` behind the one before it. */
        void lead(Location head, double[] gaps) {
            pts.set(0, head.clone());
            for (int i = 1; i < pts.size(); i++) {
                Location a = pts.get(i - 1), b = pts.get(i);
                Vector d = b.toVector().subtract(a.toVector());
                double len = d.length();
                double g = gaps[Math.min(gaps.length - 1, i - 1)];
                if (len < 1e-4) d = new Vector(0, 0, -1); else d.multiply(1 / len);
                if (len > g || len < g * 0.5) pts.set(i, a.clone().add(d.multiply(g)));
            }
        }

        /** The direction a segment faces (toward the one before it). */
        Vector facing(int i) {
            if (i == 0) return pts.size() > 1 ? pts.get(0).toVector().subtract(pts.get(1).toVector()) : new Vector(0, 0, 1);
            return pts.get(i - 1).toVector().subtract(pts.get(i).toVector());
        }
    }
}
