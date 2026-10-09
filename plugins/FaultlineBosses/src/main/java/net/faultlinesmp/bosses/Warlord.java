package net.faultlinesmp.bosses;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hoglin;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Piglin;
import org.bukkit.entity.PiglinBrute;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.SmallFireball;
import org.bukkit.event.Event.Result;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

import static net.faultlinesmp.bosses.FaultlineBosses.*;

/**
 * GRIMTUSK, THE PIGLIN WARLORD: the Nether's boss. Raise a Warlord's Challenge inside a Bastion Remnant and he rides
 * out on IRONHIDE, his armored war hoglin.
 *   Phase 1: mounted. Tusk Charge, Golden Cleave, Rally the Bastion, Fireball Volley. Ironhide takes the hits for him
 *            (he takes 60% less while riding): bring Ironhide down, or he drops to 70% and Ironhide collapses.
 *   Phase 2: on foot. + Magma Slam, Soul Fire Lines, Warcry.
 *   Phase 3 (below 35%): enraged, faster, + Gold Rain.
 * Both are real (vanilla) mobs, so Java and Bedrock players see the same thing. Music: Pigstep.
 * He drops gold, netherite, Mythic Bags and sometimes GRIMTUSK'S CLEAVER (an axe that does his Golden Cleave).
 */
final class Warlord implements Listener {

    static final String TAG = "faultline_grimtusk", HOG_TAG = "faultline_ironhide", GUARD_TAG = "faultline_grimtusk_guard",
            XBOW_TAG = "faultline_grimtusk_xbow", FX_TAG = "faultline_grimtusk_fx";
    static final List<String> TAGS = List.of(TAG, HOG_TAG, GUARD_TAG, XBOW_TAG, FX_TAG);

    static final int TUSK_CHARGE = 1, CLEAVE = 2, RALLY = 3, SLAM = 4, SOUL_LINES = 5, VOLLEY = 6, GOLD_RAIN = 7, WARCRY = 8;

    final FaultlineBosses pl;
    final Random random;
    Grimtusk boss;
    long cooldownUntil;
    final NamespacedKey itemKey;
    private final Map<UUID, Long> cleaverCd = new HashMap<>();

    Warlord(FaultlineBosses pl) {
        this.pl = pl;
        this.random = pl.random;
        itemKey = new NamespacedKey(pl, "warlord_item");
        ShapedRecipe r = new ShapedRecipe(new NamespacedKey(pl, "warlord_challenge"), item("challenge"));
        r.shape("GBG", "BSB", "GBG");
        r.setIngredient('G', Material.GOLD_BLOCK);
        r.setIngredient('B', Material.BLAZE_ROD);
        r.setIngredient('S', Material.NETHERITE_SCRAP);
        addRecipeSafely(r);
    }

    double c(String path, double def) { return pl.getConfig().getDouble("warlord." + path, def); }

    static boolean ours(Entity e) {
        for (String t : TAGS) if (e.getScoreboardTags().contains(t)) return true;
        return false;
    }

    // =====================================================================================================
    //  items: the Warlord's Challenge (summons him) and Grimtusk's Cleaver (his axe)
    // =====================================================================================================
    ItemStack item(String which) {
        ItemStack s;
        ItemMeta m;
        if (which.equals("cleaver")) {
            s = new ItemStack(Material.NETHERITE_AXE);
            m = s.getItemMeta();
            m.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Grimtusk's Cleaver");
            m.setLore(List.of(ChatColor.GRAY + "The Piglin Warlord's golden axe.",
                    ChatColor.GREEN + "Right-click: Golden Cleave " + ChatColor.GRAY + "(a burning sweep",
                    ChatColor.GRAY + "in front of you, 12 second cooldown)",
                    ChatColor.DARK_GRAY + "Dropped by Grimtusk."));
            m.setItemModel(new NamespacedKey("faultline", "warlord_cleaver"));
            m.setEnchantmentGlintOverride(true);
        } else {
            which = "challenge";
            s = new ItemStack(Material.PAPER);
            m = s.getItemMeta();
            m.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Warlord's Challenge");
            m.setLore(List.of(ChatColor.GRAY + "Raise it inside a Bastion Remnant", ChatColor.GRAY + "in the Nether, if you dare.",
                    ChatColor.GOLD + "Summons Grimtusk, the Piglin Warlord."));
            m.setItemModel(new NamespacedKey("faultline", "warlord_challenge"));
            m.setMaxStackSize(16);
        }
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, which);
        s.setItemMeta(m);
        return s;
    }

    String itemType(ItemStack s) {
        if (s == null || !s.hasItemMeta()) return null;
        return s.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
    }

    /**
     * Inside (or right by) a Bastion Remnant. BUG FIX: this only asked the 3x3 chunks around you for the structure, which
     * failed on the live server, so the challenge never worked. Now any one of three checks is enough:
     *   1. a structure lookup over 5x5 chunks (a bastion is up to ~5 chunks wide),
     *   2. the nearest bastion (locate) within 96 blocks,
     *   3. the blocks around you: plenty of blackstone bricks / gilded blackstone = you're standing in one.
     */
    static boolean inBastion(Location l) {
        World w = l.getWorld();
        if (w.getEnvironment() != World.Environment.NETHER) return false;
        int cx = l.getBlockX() >> 4, cz = l.getBlockZ() >> 4;
        try {
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                if (!w.isChunkLoaded(cx + dx, cz + dz)) continue;
                for (org.bukkit.generator.structure.GeneratedStructure gs : w.getStructures(cx + dx, cz + dz, org.bukkit.generator.structure.Structure.BASTION_REMNANT))
                    if (gs.getBoundingBox().clone().expand(16).contains(l.toVector())) return true;
            }
        } catch (RuntimeException ignored) { }
        try {
            var r = w.locateNearestStructure(l, org.bukkit.generator.structure.Structure.BASTION_REMNANT, 6, false);
            if (r != null && Math.hypot(r.getLocation().getX() - l.getX(), r.getLocation().getZ() - l.getZ()) < 96) return true;
        } catch (RuntimeException ignored) { }
        return bastionBlocks(l) >= 40;
    }

    /** How many bastion blocks (polished blackstone bricks, gilded blackstone, ...) are within 8 blocks. */
    static int bastionBlocks(Location l) {
        World w = l.getWorld();
        int n = 0;
        for (int dx = -8; dx <= 8; dx++) for (int dy = -6; dy <= 6; dy++) for (int dz = -8; dz <= 8; dz++) {
            Material m = w.getBlockAt(l.getBlockX() + dx, l.getBlockY() + dy, l.getBlockZ() + dz).getType();
            if (m == Material.POLISHED_BLACKSTONE_BRICKS || m == Material.CRACKED_POLISHED_BLACKSTONE_BRICKS || m == Material.GILDED_BLACKSTONE
                    || m == Material.POLISHED_BLACKSTONE_BRICK_SLAB || m == Material.POLISHED_BLACKSTONE_BRICK_STAIRS || m == Material.POLISHED_BLACKSTONE_BRICK_WALL
                    || m == Material.CHISELED_POLISHED_BLACKSTONE) n++;
        }
        return n;
    }

    /** Somewhere he fits (3x3, 3 tall, a solid floor, no lava) 4-9 blocks in front of you, else right beside you. */
    static Location spawnSpot(Player p) {
        Location base = p.getLocation();
        Vector f = Vendetta.flatDir(base);
        World w = base.getWorld();
        for (double d = 8; d >= 3; d -= 1) for (int side = 0; side < 5; side++) {
            double off = new double[]{0, 2, -2, 4, -4}[side];
            Location at = base.clone().add(f.clone().multiply(d)).add(new Vector(-f.getZ(), 0, f.getX()).multiply(off));
            for (int dy = 2; dy >= -3; dy--) {
                int x = at.getBlockX(), y = base.getBlockY() + dy, z = at.getBlockZ();
                if (fits(w, x, y, z) && clear(w, base, x + 0.5, y, z + 0.5)) return new Location(w, x + 0.5, y, z + 0.5, base.getYaw() + 180, 0);
            }
        }
        return base.clone();
    }

    /** Nothing solid between you and the spot (so he never appears on the other side of a wall). */
    static boolean clear(World w, Location from, double x, double y, double z) {
        double dx = x - from.getX(), dz = z - from.getZ(), len = Math.hypot(dx, dz);
        for (double t = 0.5; t < len; t += 0.5) {
            double px = from.getX() + dx * t / len, pz = from.getZ() + dz * t / len, py = from.getY() + (y - from.getY()) * t / len;
            if (w.getBlockAt((int) Math.floor(px), (int) Math.floor(py + 1.2), (int) Math.floor(pz)).getType().isSolid()) return false;
        }
        return true;
    }

    static boolean fits(World w, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            org.bukkit.block.Block floor = w.getBlockAt(x + dx, y - 1, z + dz);
            if (!floor.getType().isSolid() || floor.getType() == Material.MAGMA_BLOCK) return false;
            for (int dy = 0; dy < 3; dy++) {
                org.bukkit.block.Block b = w.getBlockAt(x + dx, y + dy, z + dz);
                if (b.getType().isSolid() || b.isLiquid()) return false;
            }
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = event.getPlayer();
        String type = itemType(p.getInventory().getItemInMainHand());
        if (type == null) return;
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getType().isInteractable() && !p.isSneaking()) return;
        event.setUseInteractedBlock(Result.DENY);
        event.setUseItemInHand(Result.DENY);
        if (type.equals("cleaver")) { cleave(p); return; }
        if (boss != null) { p.sendActionBar(legacy(ChatColor.GRAY + "Grimtusk is already here.")); return; }
        if (p.getWorld().getDifficulty() == Difficulty.PEACEFUL) { p.sendActionBar(legacy(ChatColor.RED + "He won't come on Peaceful.")); return; }
        if (System.currentTimeMillis() < cooldownUntil) { p.sendActionBar(legacy(ChatColor.GRAY + "The bastion is still rebuilding... try again in a few minutes.")); return; }
        if (pl.getConfig().getBoolean("warlord.require-bastion", true) && !inBastion(p.getLocation())) {
            p.sendMessage(ChatColor.RED + (p.getWorld().getEnvironment() != World.Environment.NETHER ? "Only in the Nether: raise it inside a Bastion Remnant."
                    : "You're not inside a Bastion Remnant (the big blackstone fortresses). Go in and try again.") + ChatColor.GRAY + " (The challenge wasn't used.)");
            return;
        }
        // BUG FIX: the challenge was used up before he spawned: a failed spawn ate it
        try { summon(spawnSpot(p), p); } catch (RuntimeException ex) { pl.getLogger().log(java.util.logging.Level.WARNING, "Couldn't summon Grimtusk", ex); }
        if (boss == null) { p.sendMessage(ChatColor.RED + "Grimtusk couldn't appear here (no room). " + ChatColor.GRAY + "(The challenge wasn't used.)"); return; }
        if (p.getGameMode() != GameMode.CREATIVE) p.getInventory().getItemInMainHand().setAmount(p.getInventory().getItemInMainHand().getAmount() - 1);
    }

    /** Grimtusk's Cleaver: a burning sweep in front of you. */
    void cleave(Player p) {
        long now = System.currentTimeMillis();
        long ready = cleaverCd.getOrDefault(p.getUniqueId(), 0L);
        if (now < ready) { p.sendActionBar(legacy(ChatColor.GRAY + "Golden Cleave: " + ((ready - now) / 1000 + 1) + "s")); return; }
        cleaverCd.put(p.getUniqueId(), now + (long) (c("cleaver.cooldown-seconds", 12) * 1000));
        Location eye = p.getLocation();
        Vector f = Vendetta.flatDir(eye);
        World w = p.getWorld();
        w.playSound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 0.6f);
        w.playSound(eye, Sound.ITEM_FIRECHARGE_USE, 0.8f, 1.2f);
        for (int a = -60; a <= 60; a += 10) {
            Vector d = f.clone().rotateAroundY(Math.toRadians(a));
            for (double r = 1.5; r <= 5; r += 1.1) w.spawnParticle(Particle.FLAME, eye.clone().add(d.clone().multiply(r)).add(0, 1, 0), 1, 0.05, 0.05, 0.05, 0);
        }
        p.swingMainHand();
        double dmg = c("cleaver.damage", 10);
        for (Entity e : w.getNearbyEntities(eye, 5.5, 3, 5.5)) {
            if (!(e instanceof LivingEntity le) || e == p || e instanceof org.bukkit.entity.ArmorStand) continue;
            if (e instanceof Player o && (!survival(o) || o.getWorld().getPVP() == false)) continue;
            Vector to = e.getLocation().toVector().subtract(eye.toVector()).setY(0);
            if (to.lengthSquared() > 5.5 * 5.5 || to.lengthSquared() > 0.01 && to.clone().normalize().dot(f) < Math.cos(Math.toRadians(65))) continue;
            le.damage(dmg, p);
            le.setFireTicks(Math.max(le.getFireTicks(), 80));
            le.setVelocity(le.getVelocity().add(f.clone().multiply(0.8).setY(0.3)));
        }
    }

    void summon(Location at, Player by) {
        if (boss != null) return;
        Location spot = at.clone();
        spot.setY(Math.max(spot.getWorld().getMinHeight() + 1, spot.getY()));
        boss = new Grimtusk(spot, by);
        Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Grimtusk, the Piglin Warlord " + ChatColor.YELLOW + "answers the challenge"
                + (by != null ? " of " + by.getName() : "") + "!");
    }

    void tick() {
        if (boss != null) boss.tick();
    }

    void shutdown() {
        if (boss != null) boss.removeEverything();
        boss = null;
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (ours(e)) e.remove();
    }

    // =====================================================================================================
    //  the fight
    // =====================================================================================================
    final class Grimtusk {
        final World world;
        final Location home;
        PiglinBrute body;
        Hoglin hog;
        double hp, maxHp;
        int phase = 1, t, ticks, attack = -1, at, cd = 60, lonely, scaledFor = 1, enrageT;
        boolean dying, rewarded;
        final Set<UUID> fighters = new HashSet<>();
        final List<LivingEntity> guards = new ArrayList<>();
        final List<Fx> fx = new ArrayList<>();
        final BossBar bar;
        final Set<UUID> listeners = new HashSet<>();
        long musicStart = -1;
        // the current move
        Player focus; Vector lineFrom, lineDir; final List<Location> marks = new ArrayList<>(); double ring;

        Grimtusk(Location at, Player by) {
            world = at.getWorld();
            home = at.clone();
            maxHp = hp = c("health", 3000);
            hog = world.spawn(at, Hoglin.class, h -> {
                h.addScoreboardTag(HOG_TAG);
                h.setCustomName(ChatColor.RED + "" + ChatColor.BOLD + "Ironhide");
                h.setCustomNameVisible(false);
                h.setImmuneToZombification(true);
                h.setIsAbleToBeHunted(false);
                h.setAdult();
                h.setPersistent(false);
                h.setRemoveWhenFarAway(false);
                attr(h, Attribute.MAX_HEALTH, Math.min(1024, c("ironhide.health", 600)));
                h.setHealth(Math.min(1024, c("ironhide.health", 600)));
                attr(h, Attribute.SCALE, c("ironhide.scale", 1.7));
                attr(h, Attribute.KNOCKBACK_RESISTANCE, 1);
                attr(h, Attribute.ATTACK_DAMAGE, c("ironhide.damage", 9));
                attr(h, Attribute.MOVEMENT_SPEED, c("ironhide.speed", 0.32));
                attr(h, Attribute.FOLLOW_RANGE, 48);
                h.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false)); // bastions are full of lava
            });
            body = world.spawn(at, PiglinBrute.class, b -> {
                b.addScoreboardTag(TAG);
                b.setCustomName(ChatColor.GOLD + "" + ChatColor.BOLD + "Grimtusk, the Piglin Warlord");
                b.setCustomNameVisible(false);
                b.setImmuneToZombification(true);
                b.setPersistent(false);
                b.setRemoveWhenFarAway(false);
                b.setCanPickupItems(false);
                attr(b, Attribute.MAX_HEALTH, 1000);
                b.setHealth(1000);
                attr(b, Attribute.SCALE, c("scale", 1.8));
                attr(b, Attribute.KNOCKBACK_RESISTANCE, 1);
                attr(b, Attribute.ATTACK_DAMAGE, c("melee-damage", 11));
                attr(b, Attribute.MOVEMENT_SPEED, c("walk-speed", 0.3));
                attr(b, Attribute.FOLLOW_RANGE, 48);
                attr(b, Attribute.ARMOR, 10);
                b.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
                gear(b.getEquipment());
            });
            hog.addPassenger(body);
            if (by != null) fighters.add(by.getUniqueId());
            bar = Bukkit.createBossBar(ChatColor.GOLD + "" + ChatColor.BOLD + "Grimtusk, the Piglin Warlord", BarColor.YELLOW, BarStyle.SEGMENTED_10);
            world.strikeLightningEffect(at);
            world.spawnParticle(Particle.LAVA, at.clone().add(0, 1, 0), 60, 1.5, 1, 1.5, 0);
            world.playSound(at, Sound.EVENT_RAID_HORN, SoundCategory.HOSTILE, 4f, 0.7f);
            world.playSound(at, Sound.ENTITY_PIGLIN_BRUTE_ANGRY, SoundCategory.HOSTILE, 3f, 0.6f);
            say(ChatColor.GOLD + "\"Who dares challenge the Warlord in his own bastion?!\"");
            playMusic();
        }

        void gear(EntityEquipment eq) {
            ItemStack helm = trimmed(new ItemStack(Material.NETHERITE_HELMET), TrimPattern.SNOUT);
            ItemStack chest = trimmed(new ItemStack(Material.NETHERITE_CHESTPLATE), TrimPattern.RIB);
            ItemStack legs = trimmed(new ItemStack(Material.GOLDEN_LEGGINGS), TrimPattern.SNOUT);
            ItemStack axe = new ItemStack(Material.GOLDEN_AXE);
            ItemMeta am = axe.getItemMeta(); am.setEnchantmentGlintOverride(true); axe.setItemMeta(am);
            eq.setHelmet(helm); eq.setChestplate(chest); eq.setLeggings(legs); eq.setItemInMainHand(axe);
            eq.setHelmetDropChance(0); eq.setChestplateDropChance(0); eq.setLeggingsDropChance(0); eq.setItemInMainHandDropChance(0);
        }

        ItemStack trimmed(ItemStack it, TrimPattern pattern) {
            if (it.getItemMeta() instanceof ArmorMeta m) {
                try { m.setTrim(new ArmorTrim(TrimMaterial.GOLD, pattern)); } catch (RuntimeException ignored) { }
                it.setItemMeta(m);
            }
            return it;
        }

        boolean mounted() { return hog != null && hog.isValid() && !hog.isDead() && body.getVehicle() == hog; }

        LivingEntity mover() { return mounted() ? hog : body; }

        Location loc() { return body.getLocation(); }

        void say(String text) {
            for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(home) < 80 * 80) p.sendMessage(ChatColor.GOLD + "[Grimtusk] " + text);
        }

        List<Player> active() {
            List<Player> a = new ArrayList<>();
            for (Player p : world.getPlayers()) {
                if (!survival(p) || p.isDead()) continue;
                if (p.getLocation().distanceSquared(loc()) > c("fight-radius", 48) * c("fight-radius", 48)) continue;
                a.add(p);
            }
            return a;
        }

        void scaleFor(int n) {
            n = Math.max(1, Math.min(n, 10));
            if (n <= scaledFor) return;
            double frac = hp / maxHp;
            scaledFor = n;
            maxHp = c("health", 3000) * (1 + c("health-per-extra-fighter", 0.2) * (n - 1));
            hp = maxHp * frac;
        }

        // ---------- music: Pigstep, looped ----------
        String track() { return pl.getConfig().getString("warlord.music.sound", "minecraft:music_disc.pigstep"); }

        void playMusic() {
            musicStart = System.currentTimeMillis();
            if (!pl.getConfig().getBoolean("warlord.music.enabled", true)) return;
            for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(home) < 80 * 80) listen(p);
        }

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
            if (musicStart < 0 || !pl.getConfig().getBoolean("warlord.music.enabled", true)) return;
            if (System.currentTimeMillis() - musicStart > c("music.length-seconds", 149) * 1000) { stopMusic(); playMusic(); return; }
            for (Player p : world.getPlayers())
                if (!listeners.contains(p.getUniqueId()) && survival(p) && p.getLocation().distanceSquared(loc()) < 64 * 64) listen(p);
        }

        // ---------- every tick ----------
        void tick() {
            ticks++;
            if (dying) { deathTick(); return; }
            if (body == null || !body.isValid()) { leave(null); return; } // his area unloaded
            body.setHealth(Objects.requireNonNull(body.getAttribute(Attribute.MAX_HEALTH)).getValue()); // his real health is `hp`
            List<Player> a = active();
            for (Player p : a) fighters.add(p.getUniqueId());
            Player pilot = pl.pilot(this);
            if (a.isEmpty() && pilot == null) {
                if (++lonely > 600) { leave(ChatColor.GOLD + "Grimtusk " + ChatColor.GRAY + "grunts and rides back into the bastion."); return; }
            } else lonely = 0;
            if (ticks % 100 == 0) scaleFor(a.size());
            if (phase == 1 && !mounted()) startPhase(2);
            if (phase == 1 && hp < maxHp * 0.7) { if (hog != null && hog.isValid()) hog.setHealth(0); startPhase(2); }
            if (phase == 2 && hp < maxHp * 0.35) startPhase(3);
            leash();
            musicTick();
            fxTick();
            guards.removeIf(g -> !g.isValid() || g.isDead());
            if (phase == 3 && ticks % 4 == 0) world.spawnParticle(Particle.FLAME, loc().add(0, 1.5, 0), 3, 0.5, 1, 0.5, 0.01);
            if (pilot != null) pilotTick(pilot, a);
            else aimAI(a);
            if (attack >= 0) moveTick(a);
            else if (--cd <= 0) {
                int m = pilot != null ? pl.takeMove(this) : pick(a);
                if (m >= 0 && (pilot != null || !a.isEmpty())) start(m, pilot != null ? pl.pilotTarget(pilot, a) : target(a));
                else if (pilot == null) cd = 20;
            }
            updateBar(a);
        }

        void updateBar(List<Player> a) {
            bar.setProgress(Math.max(0, Math.min(1, hp / maxHp)));
            bar.setTitle(ChatColor.GOLD + "" + ChatColor.BOLD + "Grimtusk, the Piglin Warlord" + ChatColor.GRAY + "  Phase " + phase
                    + (mounted() ? ChatColor.RED + "  Ironhide " + (int) hog.getHealth() : "") + (phase == 3 ? ChatColor.RED + "  ENRAGED" : ""));
            for (Player p : world.getPlayers()) {
                boolean near = p.getLocation().distanceSquared(loc()) < 72 * 72;
                if (near && !bar.getPlayers().contains(p)) bar.addPlayer(p);
                else if (!near && bar.getPlayers().contains(p)) bar.removePlayer(p);
            }
        }

        /** Keeps him near where he was summoned (bastions are full of drops). */
        void leash() {
            if (ticks % 20 != 0) return;
            if (loc().distanceSquared(home) > c("arena-radius", 36) * c("arena-radius", 36)) {
                Location back = home.clone().add(random.nextInt(5) - 2, 0, random.nextInt(5) - 2);
                mover().teleport(back);
                world.spawnParticle(Particle.LARGE_SMOKE, back.clone().add(0, 1, 0), 30, 0.6, 1, 0.6, 0.02);
            }
            if (loc().getY() < world.getMinHeight() + 2 || mover().isInLava() && !mounted()) mover().teleport(home);
        }

        void aimAI(List<Player> a) {
            if (ticks % 20 != 0) return;
            Player t = target(a);
            if (t == null) return;
            if (mounted()) hog.setTarget(t);
            body.setTarget(t);
        }

        Player target(List<Player> a) {
            if (a.isEmpty()) return null;
            Player best = null; double bd = Double.MAX_VALUE;
            for (Player p : a) { double d = p.getLocation().distanceSquared(loc()) * (0.6 + random.nextDouble() * 0.8); if (d < bd) { bd = d; best = p; } }
            return best;
        }

        void pilotTick(Player pilot, List<Player> a) {
            body.setAware(false);
            if (mounted()) hog.setAware(false);
            if (attack == TUSK_CHARGE || attack == SLAM) return; // the move moves him
            Location want = pilot.getLocation();
            Location cur = mover().getLocation();
            Vector to = want.toVector().subtract(cur.toVector()).setY(0);
            if (to.lengthSquared() > 2.5 * 2.5) {
                Vector step = to.normalize().multiply(Math.min(0.45, to.length()));
                Location n = cur.clone().add(step);
                n.setY(want.getY());
                n.setDirection(to);
                mover().teleport(n);
            }
        }

        int pick(List<Player> a) {
            List<Integer> pool = new ArrayList<>(List.of(CLEAVE, VOLLEY, RALLY));
            if (phase == 1) pool.add(TUSK_CHARGE);
            if (phase >= 2) { pool.add(SLAM); pool.add(SOUL_LINES); pool.add(WARCRY); pool.add(CLEAVE); }
            if (phase == 3) { pool.add(GOLD_RAIN); pool.add(GOLD_RAIN); }
            if (guards.size() >= (int) c("max-guards", 6)) pool.removeIf(x -> x == RALLY || x == WARCRY && guards.isEmpty());
            return pool.get(random.nextInt(pool.size()));
        }

        void startPhase(int p) {
            if (p <= phase) return;
            phase = p;
            if (p == 2) {
                if (body.getVehicle() != null) body.leaveVehicle();
                body.setVelocity(new Vector(0, 0.6, 0));
                world.playSound(loc(), Sound.ENTITY_HOGLIN_DEATH, SoundCategory.HOSTILE, 3f, 0.6f);
                world.playSound(loc(), Sound.ENTITY_PIGLIN_BRUTE_ANGRY, SoundCategory.HOSTILE, 3f, 0.5f);
                say(ChatColor.GOLD + "\"IRONHIDE! ...You'll pay for that with your heads!\"");
                attack = -1; cd = 30;
            } else if (p == 3) {
                attr(body, Attribute.MOVEMENT_SPEED, c("enraged-speed", 0.38));
                body.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 0, false, false));
                world.playSound(loc(), Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3f, 0.6f);
                world.spawnParticle(Particle.LAVA, loc().add(0, 1.5, 0), 50, 1, 1, 1, 0);
                say(ChatColor.RED + "\"ENOUGH! The whole bastion burns with you!\"");
                for (Player pl2 : active()) pl2.showTitle(Title.title(legacy(ChatColor.RED + "" + ChatColor.BOLD + "GRIMTUSK IS ENRAGED"), legacy(ChatColor.GRAY + "Gold Rain incoming"),
                        Title.Times.times(java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(1600), java.time.Duration.ofMillis(400))));
            }
        }

        int nextCd() {
            double base = phase == 3 ? c("move-gap-ticks.phase3", 40) : phase == 2 ? c("move-gap-ticks.phase2", 55) : c("move-gap-ticks.phase1", 70);
            return (int) (base * (0.8 + random.nextDouble() * 0.4));
        }

        // ---------- moves ----------
        void start(int move, Player target) {
            attack = move; at = 0; focus = target; marks.clear(); ring = 0;
            switch (move) {
                case TUSK_CHARGE -> {
                    if (!mounted()) { attack = CLEAVE; }
                    else {
                        lineFrom = hog.getLocation().toVector();
                        lineDir = target.getLocation().toVector().subtract(lineFrom).setY(0);
                        if (lineDir.lengthSquared() < 0.01) lineDir = new Vector(1, 0, 0);
                        lineDir.normalize();
                        hog.setAware(false);
                        world.playSound(hog.getLocation(), Sound.ENTITY_HOGLIN_ANGRY, SoundCategory.HOSTILE, 3f, 0.6f);
                    }
                }
                case RALLY -> world.playSound(loc(), Sound.EVENT_RAID_HORN, SoundCategory.HOSTILE, 4f, 1.1f);
                case WARCRY -> world.playSound(loc(), Sound.ENTITY_PIGLIN_BRUTE_ANGRY, SoundCategory.HOSTILE, 4f, 0.5f);
                case GOLD_RAIN -> {
                    for (Player p : active()) for (int i = 0; i < 2; i++) marks.add(p.getLocation().add(random.nextGaussian() * 2.5, 0, random.nextGaussian() * 2.5));
                    for (int i = 0; i < 4; i++) marks.add(loc().add(random.nextGaussian() * 9, 0, random.nextGaussian() * 9));
                    for (Location m : marks) m.setY(floor(m));
                    world.playSound(loc(), Sound.BLOCK_BELL_RESONATE, SoundCategory.HOSTILE, 3f, 0.6f);
                }
                case SOUL_LINES -> world.playSound(loc(), Sound.PARTICLE_SOUL_ESCAPE, SoundCategory.HOSTILE, 4f, 0.6f);
                default -> { }
            }
        }

        void end() {
            if (attack == TUSK_CHARGE && hog != null && hog.isValid()) hog.setAware(pl.pilot(this) == null);
            attack = -1; cd = nextCd(); marks.clear();
        }

        double floor(Location l) {
            int y = l.getBlockY() + 3;
            for (int i = 0; i < 12; i++, y--) {
                if (world.getBlockAt(l.getBlockX(), y - 1, l.getBlockZ()).getType().isSolid() && !world.getBlockAt(l.getBlockX(), y, l.getBlockZ()).getType().isSolid()) return y;
            }
            return l.getY();
        }

        void moveTick(List<Player> a) {
            at++;
            switch (attack) {
                case TUSK_CHARGE -> tuskCharge();
                case CLEAVE -> cleaveMove();
                case RALLY -> rally();
                case SLAM -> slam();
                case SOUL_LINES -> soulLines(a);
                case VOLLEY -> volley(a);
                case GOLD_RAIN -> goldRain();
                case WARCRY -> warcry();
                default -> end();
            }
        }

        void hit(Player p, double dmg, Vector from, Guard g) {
            pl.hurt(p, dmg * c("damage-multiplier", 1.0), body, from, g);
        }

        /** Ironhide lowers his tusks (a red line), then charges straight along it. */
        void tuskCharge() {
            if (!mounted()) { end(); return; }
            int wind = phase >= 2 ? 14 : 22;
            if (at <= wind) {
                for (double d = 1; d < 22; d += 0.8) {
                    Vector v = lineFrom.clone().add(lineDir.clone().multiply(d));
                    world.spawnParticle(Particle.DUST, v.getX(), floor(v.toLocation(world)) + 0.15, v.getZ(), 1, 0.1, 0, 0.1, 0, new Particle.DustOptions(Color.RED, 1.3f));
                }
                if (at == wind) world.playSound(hog.getLocation(), Sound.ENTITY_HOGLIN_ATTACK, SoundCategory.HOSTILE, 3f, 0.5f);
                return;
            }
            Location cur = hog.getLocation();
            Location next = cur.clone().add(lineDir.clone().multiply(c("charge-speed", 1.1)));
            next.setY(floor(next));
            next.setDirection(lineDir);
            if (world.getBlockAt(next.clone().add(0, 0.6, 0)).getType().isSolid() || at > wind + 22) { // a wall, or done
                if (at <= wind + 22) { world.playSound(cur, Sound.ENTITY_ZOMBIE_ATTACK_IRON_DOOR, SoundCategory.HOSTILE, 2f, 0.5f); world.spawnParticle(Particle.EXPLOSION, cur, 2, 0.5, 0.5, 0.5, 0); }
                end(); return;
            }
            hog.teleport(next);
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, cur, 2, 0.4, 0.1, 0.4, 0.01);
            for (Player p : active()) {
                if (p.getLocation().distanceSquared(next) > 2.4 * 2.4) continue;
                hit(p, c("moves.tusk-charge", 12), next.toVector(), Guard.HEAVY);
                p.setVelocity(lineDir.clone().multiply(1.3).setY(0.7));
            }
        }

        /** A wide golden sweep in front of him (flames show the arc first). */
        void cleaveMove() {
            int wind = phase == 3 ? 12 : 18;
            Location c = loc();
            Vector f = focus != null && focus.isValid() ? focus.getLocation().toVector().subtract(c.toVector()).setY(0) : Vendetta.flatDir(c);
            if (f.lengthSquared() < 0.01) f = new Vector(1, 0, 0);
            f.normalize();
            double r = c("moves.cleave-radius", 6.5);
            if (at < wind) {
                if (at % 3 == 0) for (int a = -65; a <= 65; a += 10) {
                    Vector d = f.clone().rotateAroundY(Math.toRadians(a));
                    world.spawnParticle(Particle.FLAME, c.clone().add(d.multiply(r)).add(0, 0.2, 0), 1, 0, 0, 0, 0);
                }
                if (at == 1) world.playSound(c, Sound.ITEM_ARMOR_EQUIP_NETHERITE, SoundCategory.HOSTILE, 2f, 0.6f);
                return;
            }
            body.swingMainHand();
            world.playSound(c, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 3f, 0.5f);
            for (int a = -65; a <= 65; a += 8) {
                Vector d = f.clone().rotateAroundY(Math.toRadians(a));
                for (double k = 1.5; k <= r; k += 1.2) world.spawnParticle(Particle.SWEEP_ATTACK, c.clone().add(d.clone().multiply(k)).add(0, 1, 0), 1, 0, 0, 0, 0);
            }
            for (Player p : active()) {
                Vector to = p.getLocation().toVector().subtract(c.toVector()).setY(0);
                if (to.lengthSquared() > r * r) continue;
                if (to.lengthSquared() > 1 && to.clone().normalize().dot(f) < Math.cos(Math.toRadians(70))) continue;
                hit(p, c("moves.cleave", 14), c.toVector(), Guard.HEAVY);
                p.setFireTicks(Math.max(p.getFireTicks(), 60));
                p.setVelocity(to.normalize().multiply(1.1).setY(0.45));
            }
            end();
        }

        /** Blows his horn: bastion guards and crossbowmen pour out. */
        void rally() {
            if (at < 15) { if (at % 5 == 0) world.spawnParticle(Particle.NOTE, loc().add(0, 3.5, 0), 3, 0.5, 0.3, 0.5, 1); return; }
            int n = Math.min((int) c("max-guards", 6) - guards.size(), (int) c("moves.rally-count", 4));
            for (int i = 0; i < n; i++) {
                Location l = loc().add(random.nextGaussian() * 4, 0, random.nextGaussian() * 4);
                l.setY(floor(l));
                guards.add(i % 2 == 0 ? guard(l, false) : guard(l, true));
                world.spawnParticle(Particle.LARGE_SMOKE, l.clone().add(0, 1, 0), 15, 0.3, 0.6, 0.3, 0.02);
            }
            end();
        }

        LivingEntity guard(Location l, boolean crossbow) {
            if (crossbow) return world.spawn(l, Piglin.class, g -> {
                g.addScoreboardTag(XBOW_TAG);
                g.setCustomName(ChatColor.GOLD + "Bastion Crossbowman");
                g.setImmuneToZombification(true); g.setIsAbleToHunt(false); g.setAdult(); g.setPersistent(false); g.setRemoveWhenFarAway(false);
                g.getEquipment().setItemInMainHand(new ItemStack(Material.CROSSBOW)); g.getEquipment().setItemInMainHandDropChance(0);
                g.getEquipment().setHelmet(new ItemStack(Material.GOLDEN_HELMET)); g.getEquipment().setHelmetDropChance(0);
                attr(g, Attribute.MAX_HEALTH, c("guards.crossbow-health", 24)); g.setHealth(c("guards.crossbow-health", 24));
                g.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
            });
            return world.spawn(l, PiglinBrute.class, g -> {
                g.addScoreboardTag(GUARD_TAG);
                g.setCustomName(ChatColor.GOLD + "Bastion Guard");
                g.setImmuneToZombification(true); g.setPersistent(false); g.setRemoveWhenFarAway(false);
                g.getEquipment().setItemInMainHandDropChance(0);
                attr(g, Attribute.MAX_HEALTH, c("guards.guard-health", 40)); g.setHealth(c("guards.guard-health", 40));
                g.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
            });
        }

        /** Leaps at you and lands in a ring of magma: jump over the shockwave. */
        void slam() {
            Location c = mover().getLocation();
            if (at == 1) {
                Vector to = focus != null && focus.isValid() ? focus.getLocation().toVector().subtract(c.toVector()).setY(0) : new Vector();
                double d = Math.min(14, to.length());
                Vector v = to.lengthSquared() > 0.01 ? to.normalize().multiply(d * 0.07) : new Vector();
                mover().setVelocity(v.setY(1.1));
                world.playSound(c, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 2f, 1.2f);
                return;
            }
            if (ring <= 0) {
                if (at > 6 && (mover().isOnGround() || at > 40)) {
                    ring = 0.5;
                    world.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2.5f, 0.7f);
                    world.spawnParticle(Particle.EXPLOSION, c, 3, 1, 0.2, 1, 0);
                    lineFrom = c.toVector();
                }
                return;
            }
            double prev = ring;
            ring += 0.55;
            for (int a = 0; a < 360; a += 8) {
                double rad = Math.toRadians(a);
                Location l = new Location(world, lineFrom.getX() + Math.cos(rad) * ring, 0, lineFrom.getZ() + Math.sin(rad) * ring);
                l.setY(floor(l) + 0.1);
                world.spawnParticle(a % 16 == 0 ? Particle.LAVA : Particle.FLAME, l, 1, 0.05, 0, 0.05, 0);
            }
            for (Player p : active()) {
                double d = Math.hypot(p.getLocation().getX() - lineFrom.getX(), p.getLocation().getZ() - lineFrom.getZ());
                if (d < prev - 0.3 || d > ring + 0.5 || !p.isOnGround()) continue;
                if (Math.abs(p.getLocation().getY() - lineFrom.getY()) > 2.5) continue;
                hit(p, c("moves.magma-slam", 11), lineFrom, Guard.UNBLOCKABLE);
                p.setVelocity(p.getLocation().toVector().subtract(lineFrom).setY(0).normalize().multiply(0.9).setY(0.6));
                p.setFireTicks(Math.max(p.getFireTicks(), 40));
            }
            if (ring > c("moves.slam-radius", 10)) end();
        }

        /** Three lines of soul fire race out across the ground toward the fighters. */
        void soulLines(List<Player> a) {
            Location c = loc();
            if (at == 1) {
                marks.clear();
                List<Player> ts = new ArrayList<>(a);
                Collections.shuffle(ts, random);
                for (int i = 0; i < 3; i++) {
                    Vector d = !ts.isEmpty() ? ts.get(i % ts.size()).getLocation().toVector().subtract(c.toVector()).setY(0)
                            : Vendetta.flatDir(c).rotateAroundY(i * 2.1);
                    if (i >= ts.size() && !ts.isEmpty()) d.rotateAroundY(Math.toRadians(i == 1 ? 30 : -30));
                    if (d.lengthSquared() < 0.01) d = new Vector(1, 0, 0);
                    marks.add(c.clone().setDirection(d.normalize()));
                }
                return;
            }
            if (at < 12) { // they glow first
                for (Location m : marks) for (double k = 1; k < 4; k += 1) world.spawnParticle(Particle.SOUL_FIRE_FLAME, m.clone().add(m.getDirection().multiply(k)).add(0, 0.2, 0), 1, 0, 0, 0, 0);
                return;
            }
            double k = (at - 12) * 1.0 + 1;
            for (Location m : marks) {
                Location l = m.clone().add(m.getDirection().multiply(k));
                l.setY(floor(l) + 0.2);
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, l, 6, 0.3, 0.3, 0.3, 0.02);
                world.spawnParticle(Particle.SOUL, l, 1, 0.2, 0.2, 0.2, 0.01);
                for (Player p : a) {
                    if (p.getLocation().distanceSquared(l) > 1.6 * 1.6) continue;
                    hit(p, c("moves.soul-fire", 9), l.toVector(), Guard.BLOCKABLE);
                    p.setFireTicks(Math.max(p.getFireTicks(), 80));
                }
            }
            if (at % 4 == 0) world.playSound(c, Sound.BLOCK_SOUL_SAND_BREAK, SoundCategory.HOSTILE, 1.5f, 0.6f);
            if (k > c("moves.soul-fire-length", 22)) end();
        }

        /** Fireballs at the fighters, one after another. */
        void volley(List<Player> a) {
            int shots = phase == 3 ? 8 : 5;
            if (at % 6 != 0) return;
            if (at / 6 > shots || a.isEmpty()) { end(); return; }
            Player p = a.get(random.nextInt(a.size()));
            Location from = loc().add(0, 2.4, 0);
            Vector dir = p.getEyeLocation().toVector().subtract(from.toVector()).normalize();
            dir.add(new Vector(random.nextGaussian() * 0.04, random.nextGaussian() * 0.03, random.nextGaussian() * 0.04)).normalize();
            from.add(dir.clone().multiply(1.6));
            world.spawn(from, SmallFireball.class, f -> {
                f.setShooter(body);
                f.setDirection(dir.clone().multiply(1.2));
                f.setIsIncendiary(false);
                f.addScoreboardTag(FX_TAG);
            });
            world.playSound(from, Sound.ENTITY_BLAZE_SHOOT, SoundCategory.HOSTILE, 1.5f, 0.8f);
        }

        /** Golden blocks fall from the sky onto glowing marks: get out of the circles. */
        void goldRain() {
            int warn = 28;
            if (at <= warn) {
                if (at % 2 == 0) for (Location m : marks) for (int a = 0; a < 360; a += 30) {
                    double rad = Math.toRadians(a + at * 4);
                    world.spawnParticle(Particle.DUST, m.clone().add(Math.cos(rad) * 2, 0.15, Math.sin(rad) * 2), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(255, 200, 40), 1.3f));
                }
                if (at == warn) for (Location m : marks) fx.add(new Fx(m, 12));
                return;
            }
            if (fx.isEmpty() || at > warn + 20) end();
        }

        /** A roar that throws everyone back, and drives his guards on. */
        void warcry() {
            if (at < 12) { if (at % 3 == 0) world.spawnParticle(Particle.ANGRY_VILLAGER, loc().add(0, 3, 0), 3, 0.6, 0.4, 0.6, 0); return; }
            world.playSound(loc(), Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3f, 0.8f);
            world.spawnParticle(Particle.SONIC_BOOM, loc().add(0, 1.5, 0), 1);
            for (Player p : active()) {
                Vector to = p.getLocation().toVector().subtract(loc().toVector());
                if (to.lengthSquared() > 10 * 10) continue;
                hit(p, c("moves.warcry", 6), loc().toVector(), Guard.UNBLOCKABLE);
                p.setVelocity(to.setY(0).normalize().multiply(1.6).setY(0.5));
            }
            for (LivingEntity g : guards) {
                g.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 200, 0));
                g.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 1));
            }
            body.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 120, 1, false, true));
            end();
        }

        // ---------- falling gold ----------
        final class Fx {
            final Location target; final BlockDisplay block; int t; final int fall;
            Fx(Location target, int fall) {
                this.target = target; this.fall = fall;
                Location start = target.clone().add(0, 16, 0);
                block = world.spawn(start, BlockDisplay.class, d -> {
                    d.setBlock(Material.GOLD_BLOCK.createBlockData());
                    d.setTransformation(new Transformation(new Vector3f(-0.75f, 0, -0.75f), new Quaternionf(), new Vector3f(1.5f, 1.5f, 1.5f), new Quaternionf()));
                    d.setTeleportDuration(1);
                    d.setBrightness(new Display.Brightness(15, 15));
                    d.setPersistent(false);
                    d.addScoreboardTag(FX_TAG);
                });
            }
            boolean tick() {
                t++;
                double f = Math.min(1, t / (double) fall);
                block.teleport(target.clone().add(0, 16 * (1 - f * f), 0));
                if (t < fall) return false;
                world.spawnParticle(Particle.BLOCK, target.clone().add(0, 0.3, 0), 40, 1, 0.3, 1, 0, Material.GOLD_BLOCK.createBlockData());
                world.playSound(target, Sound.BLOCK_ANVIL_LAND, SoundCategory.HOSTILE, 1.2f, 0.6f);
                for (Player p : active()) if (p.getLocation().distanceSquared(target) < 2.3 * 2.3) hit(p, c("moves.gold-rain", 14), target.toVector().add(new Vector(0, 3, 0)), Guard.HEAVY);
                block.remove();
                return true;
            }
        }

        void fxTick() { fx.removeIf(Fx::tick); }

        // ---------- damage, defeat ----------
        void damage(double amount, Player by) {
            if (dying) return;
            if (by != null) fighters.add(by.getUniqueId());
            if (mounted()) amount *= 1 - c("mounted-damage-reduction", 0.6);
            if (body.hasPotionEffect(PotionEffectType.RESISTANCE)) amount *= 0.6;
            hp -= amount;
            if (hp <= 0) { hp = 0; defeat(); }
        }

        int deathT;
        void defeat() {
            dying = true; deathT = 0;
            stopMusic();
            bar.removeAll();
            attack = -1;
            for (Fx f : fx) f.block.remove();
            fx.clear();
            for (LivingEntity g : guards) if (g.isValid()) g.remove();
            guards.clear();
            if (body.getVehicle() != null) body.leaveVehicle();
            body.setAware(false);
            say(ChatColor.GOLD + "\"The bastion... falls...\"");
        }

        void deathTick() {
            deathT++;
            if (body.isValid()) {
                if (deathT % 4 == 0) world.spawnParticle(Particle.BLOCK, loc().add(0, 1.5, 0), 20, 0.6, 1, 0.6, 0, Material.GOLD_BLOCK.createBlockData());
                if (deathT % 10 == 0) world.playSound(loc(), Sound.ENTITY_PIGLIN_BRUTE_HURT, SoundCategory.HOSTILE, 2f, 0.5f);
            }
            if (deathT == 50) {
                Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Grimtusk, the Piglin Warlord " + ChatColor.YELLOW + "has fallen!");
                for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(loc()) < 80 * 80)
                    p.showTitle(Title.title(legacy(ChatColor.GOLD + "" + ChatColor.BOLD + "THE WARLORD HAS FALLEN"), legacy(ChatColor.GRAY + "The bastion is yours."),
                            Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(3000), java.time.Duration.ofMillis(800))));
                world.spawnParticle(Particle.EXPLOSION_EMITTER, loc(), 1);
                if (!rewarded) { rewarded = true; if (!pl.rushSuppressLoot("grimtusk")) rewards(); }
                pl.rushDefeated("grimtusk");
                Bukkit.getScheduler().runTask(pl, () -> { if (body.isValid()) body.setHealth(0); }); // the kill (for the Index)
            }
            if (deathT > 70) {
                removeEverything();
                if (boss == this) boss = null;
                cooldownUntil = System.currentTimeMillis() + (long) (c("cooldown-minutes", 15) * 60000);
            }
        }

        void rewards() {
            for (UUID id : fighters) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.getWorld().equals(world)) continue;
                Location at = p.getLocation();
                pl.console("givemythicbag " + (int) (c("rewards.mythic-bags-min", 6) + random.nextInt((int) c("rewards.mythic-bags-extra", 5))) + " " + p.getName(), p);
                pl.console("givegoodiebag " + (int) c("rewards.goodie-bags", 4) + " " + p.getName(), p);
                pl.dropLocked(at, p, new ItemStack(Material.GOLD_BLOCK, 8 + random.nextInt(9)));
                pl.dropLocked(at, p, new ItemStack(Material.ANCIENT_DEBRIS, 2 + random.nextInt(3)));
                if (random.nextDouble() < c("rewards.netherite-chance", 0.5)) pl.dropLocked(at, p, new ItemStack(Material.NETHERITE_INGOT, 1 + random.nextInt(3)));
                if (random.nextDouble() < c("rewards.cleaver-chance", 0.25)) {
                    pl.dropLocked(at, p, item("cleaver"));
                    p.sendMessage(ChatColor.GOLD + "Grimtusk's Cleaver dropped for you! Right-click it for a Golden Cleave.");
                }
                int xp = (int) c("rewards.xp", 2000);
                for (int left = xp; left > 0; ) { int n = Math.min(left, 100); left -= n; world.spawn(at, org.bukkit.entity.ExperienceOrb.class).setExperience(n); }
                pl.console("index discover " + p.getName() + " grimtusk", p);
            }
        }

        void leave(String message) {
            if (boss == this) boss = null; // first: even if the cleanup below fails, he's no longer "already here"
            if (message != null) Bukkit.broadcastMessage(message);
            removeEverything();
        }

        void removeEverything() {
            stopMusic();
            bar.removeAll();
            for (Fx f : fx) f.block.remove();
            fx.clear();
            for (LivingEntity g : guards) if (g.isValid()) g.remove();
            guards.clear();
            if (body != null && body.isValid() && !body.isDead()) body.remove();
            if (hog != null && hog.isValid() && !hog.isDead()) hog.remove();
            for (Entity e : world.getEntities()) if (e.getScoreboardTags().contains(FX_TAG)) e.remove(); // fireballs still in the air
        }
    }

    // =====================================================================================================
    //  events
    // =====================================================================================================
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        Grimtusk g = boss;
        if (g == null) return;
        if (e.getEntity() == g.body) {
            if (g.dying) { e.setCancelled(true); return; }
            if (e.getCause() == EntityDamageEvent.DamageCause.FIRE || e.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK
                    || e.getCause() == EntityDamageEvent.DamageCause.LAVA || e.getCause() == EntityDamageEvent.DamageCause.FALL
                    || e.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION || e.getCause() == EntityDamageEvent.DamageCause.HOT_FLOOR) { e.setCancelled(true); return; }
            Player by = null;
            if (e instanceof EntityDamageByEntityEvent ee) {
                if (ours(ee.getDamager()) || ee.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Entity s && ours(s)) { e.setCancelled(true); return; }
                by = ee.getDamager() instanceof Player p ? p : ee.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player s ? s : null;
                if (by != null && MORPHED.contains(by.getUniqueId())) { e.setCancelled(true); return; }
            }
            g.damage(e.getFinalDamage(), by);
            e.setDamage(Math.min(e.getDamage(), 0.5)); // the hurt flash and sound, but his real health is tracked here
            return;
        }
        if (e instanceof EntityDamageByEntityEvent ee && ours(e.getEntity())) { // his side never hurts each other
            Entity src = ee.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Entity s ? s : ee.getDamager();
            if (ours(src)) e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (!ours(e.getEntity())) return;
        if (e.getTarget() != null && (ours(e.getTarget()) || e.getTarget() instanceof Player p && !survival(p))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) { if (ours(e.getEntity())) e.setCancelled(true); }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (!ours(e.getEntity())) return;
        e.getDrops().clear();
        Set<String> t = e.getEntity().getScoreboardTags();
        if (t.contains(GUARD_TAG) || t.contains(XBOW_TAG)) {
            if (random.nextDouble() < 0.6) e.getDrops().add(new ItemStack(Material.GOLD_NUGGET, 3 + random.nextInt(5)));
            e.setDroppedExp(10);
        } else if (t.contains(HOG_TAG)) {
            e.getDrops().add(new ItemStack(Material.COOKED_PORKCHOP, 6 + random.nextInt(6)));
            e.getDrops().add(new ItemStack(Material.LEATHER, 4 + random.nextInt(4)));
            e.setDroppedExp(60);
        } else e.setDroppedExp(0);
    }

    // =====================================================================================================
    //  boss form
    // =====================================================================================================
    List<MoveSlot> morphMoves() {
        List<MoveSlot> m = new ArrayList<>();
        if (boss == null) return m;
        if (boss.phase == 1) m.add(new MoveSlot(TUSK_CHARGE, "Tusk Charge", Material.HOGLIN_SPAWN_EGG));
        m.add(new MoveSlot(CLEAVE, "Golden Cleave", Material.GOLDEN_AXE));
        m.add(new MoveSlot(RALLY, "Rally the Bastion", Material.GOAT_HORN));
        m.add(new MoveSlot(VOLLEY, "Fireball Volley", Material.FIRE_CHARGE));
        if (boss.phase >= 2) {
            m.add(new MoveSlot(SLAM, "Magma Slam", Material.MAGMA_BLOCK));
            m.add(new MoveSlot(SOUL_LINES, "Soul Fire Lines", Material.SOUL_CAMPFIRE));
            m.add(new MoveSlot(WARCRY, "Warcry", Material.PIGLIN_HEAD));
        }
        if (boss.phase == 3) m.add(new MoveSlot(GOLD_RAIN, "Gold Rain", Material.GOLD_BLOCK));
        return m;
    }

    static void attr(LivingEntity e, Attribute a, double v) {
        AttributeInstance ai = e.getAttribute(a);
        if (ai != null) ai.setBaseValue(v);
    }

    void giveItem(Player p, String which, int amount) { for (int i = 0; i < amount; i++) pl.give(p, item(which)); }
}
