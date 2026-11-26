package net.faultlinesmp.raids;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ELITE MOBS and MUTATIONS (Raids 1.2.0, config elites:). Natural hostile spawns (never spawners, eggs or other
 * plugins' mobs) and raid waves can come out:
 *  - ELITE (elites.chance 1%, raids elites.raid-chance 6%): "★ Elite <mob>" name, 2x health, +50% damage, +15% speed,
 *    a little bigger; killed by a player = its drops twice, 3x XP and elites.bonus-loot rolls (+ a Goodie Bag
 *    sometimes), Index stat "elites". Tag faultline_elite.
 *  - LOOTER ZOMBIE (zombies, husks, drowned, zombie villagers; 2%): a hit on a player can STEAL a stack from their
 *    backpack slots (never the hotbar, armor or offhand; max 3). The loot is kept in the zombie's PDC (survives
 *    restarts; the zombie no longer despawns) and always drops where it dies, or where it's removed by a raid ending.
 *    Tag faultline_looter.
 *  - BLAZING SKELETON (skeletons, strays, bogged; 2%): fire-proof, its arrows are on fire. Tag faultline_blazing.
 *  - SPLITTING CREEPER (creepers; 2%): exploding or killed, it splits into 2-3 mini creepers (short fuse, small blast,
 *    no drops, they don't split again). Tags faultline_splitter / faultline_creeper_mini.
 * Every one of them is in the Faultline Index.
 */
final class Elites implements Listener {

    static final String ELITE = "faultline_elite", LOOTER = "faultline_looter", BLAZING = "faultline_blazing",
            SPLITTER = "faultline_splitter", MINI = "faultline_creeper_mini";
    static final List<String> DEFAULT_LOOT = List.of("DIAMOND:1-2:0.10", "EMERALD:1-3:0.25", "GOLDEN_APPLE:1-1:0.15",
            "EXPERIENCE_BOTTLE:2-5:0.40", "IRON_INGOT:2-6:0.35");
    /** Round 10: the Farlands are hard, so their Elites drop better. */
    static final List<String> FAR_LOOT = List.of("DIAMOND:2-4:0.20", "EMERALD:2-6:0.35", "GOLDEN_APPLE:1-2:0.20", "EXPERIENCE_BOTTLE:4-8:0.50",
            "IRON_INGOT:4-8:0.40", "GOLD_INGOT:2-6:0.30", "NETHERITE_SCRAP:1-1:0.05", "ENCHANTED_GOLDEN_APPLE:1-1:0.01", "ENDER_PEARL:1-3:0.25");
    static final Set<EntityType> NEVER = Set.of(EntityType.WARDEN, EntityType.WITHER, EntityType.ELDER_GUARDIAN, EntityType.ENDERMITE,
            EntityType.SILVERFISH, EntityType.VEX, EntityType.GIANT,
            // 1.2.1: the mobs big farms are built on (gold, ender pearl, guardian and witch farms) would turn into loot farms
            EntityType.ZOMBIFIED_PIGLIN, EntityType.ENDERMAN, EntityType.GUARDIAN, EntityType.WITCH);

    private final FaultlineRaids plugin;
    private final NamespacedKey stolenKey, healthKey, damageKey, speedKey, scaleKey;
    private final Set<UUID> exploded = new HashSet<>();

    Elites(FaultlineRaids plugin) {
        this.plugin = plugin;
        stolenKey = new NamespacedKey(plugin, "looter_stolen");
        healthKey = new NamespacedKey(plugin, "elite_health");
        damageKey = new NamespacedKey(plugin, "elite_damage");
        speedKey = new NamespacedKey(plugin, "elite_speed");
        scaleKey = new NamespacedKey(plugin, "elite_scale");
    }

    private double cfg(String path, double def) { return plugin.getConfig().getDouble("elites." + path, def); }

    private boolean on() { return plugin.getConfig().getBoolean("elites.enabled", true); }

    // ---------------------------------------------------------------- who becomes what

    static final String FAR = "faultline_far_elite", CAPTAIN = "faultline_elite_captain", ESCORT = "faultline_captain_escort", CRAWLER = "faultline_wall_crawler";
    static final Set<CreatureSpawnEvent.SpawnReason> FAR_REASONS = Set.of(CreatureSpawnEvent.SpawnReason.NATURAL, CreatureSpawnEvent.SpawnReason.PATROL,
            CreatureSpawnEvent.SpawnReason.SPAWNER, CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER, CreatureSpawnEvent.SpawnReason.REINFORCEMENTS,
            CreatureSpawnEvent.SpawnReason.JOCKEY, CreatureSpawnEvent.SpawnReason.MOUNT, CreatureSpawnEvent.SpawnReason.DEFAULT, CreatureSpawnEvent.SpawnReason.CUSTOM);

    /**
     * Round 10: in the Farlands EVERY hostile mob is an Elite or a Mutant (or both). A mob that can be neither (slimes,
     * endermen, witches...) is taken away. Spawner mobs become Mutants only: Mutants give no extra loot, so a spawner out
     * there is no loot farm. A few Elites are ELITE CAPTAINS leading a pack.
     */
    private void farlands(LivingEntity m, CreatureSpawnEvent.SpawnReason why) {
        var r = plugin.random;
        boolean spawner = why == CreatureSpawnEvent.SpawnReason.SPAWNER || why == CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER;
        boolean can = !(m instanceof org.bukkit.entity.Slime) && !NEVER.contains(m.getType());
        boolean mutated = false, elited = false;
        if (can && (spawner || r.nextDouble() < cfg("farlands-mutation-chance", 0.4))) mutated = mutate(m);
        if (can && !spawner && m instanceof Monster && (!mutated || r.nextDouble() < cfg("farlands-chance", 0.35))) {
            m.addScoreboardTag(FAR);
            elite(m);
            elited = true;
            if (!mutated && r.nextDouble() < cfg("captain.chance", 0.03)) captain(m);
        }
        if (!mutated && !elited && can) mutated = mutate(m);   // not a Monster (a ghast): it has to mutate
        if (!mutated && !elited) m.remove();
    }

    /** An Elite Captain: bigger, glowing, x1.5 more health, and an Elite escort of 3-4 of its own kind. */
    void captain(LivingEntity m) {
        m.addScoreboardTag(CAPTAIN);
        String base = m.getCustomName() != null ? ChatColor.stripColor(m.getCustomName()).replace("★ Elite ", "") : nice(m);
        name(m, ChatColor.RED + "♛ Elite Captain " + ChatColor.GOLD + base);
        modify(m, Attribute.MAX_HEALTH, new NamespacedKey(plugin, "captain_health"), cfg("captain.health-multiplier", 1.5) - 1);
        AttributeInstance mh = m.getAttribute(Attribute.MAX_HEALTH);
        if (mh != null) m.setHealth(mh.getValue());
        try { m.setGlowing(true); } catch (RuntimeException ignored) { }
        m.setCustomNameVisible(true);
        int n = 3 + plugin.random.nextInt(2);
        for (int i = 0; i < n; i++) {
            Location l = m.getLocation().add(plugin.random.nextGaussian() * 1.5, 0, plugin.random.nextGaussian() * 1.5);
            if (!Mutants.open(l.getBlock()) || !Mutants.open(l.clone().add(0, 1, 0).getBlock())) l = m.getLocation();
            LivingEntity esc = (LivingEntity) m.getWorld().spawnEntity(l, m.getType(), CreatureSpawnEvent.SpawnReason.CUSTOM, en -> en.addScoreboardTag(ESCORT));
            esc.addScoreboardTag(FAR);
            elite(esc);
        }
        m.getWorld().playSound(m.getLocation(), org.bukkit.Sound.EVENT_RAID_HORN, 2f, 1.2f);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        CreatureSpawnEvent.SpawnReason why = e.getSpawnReason();
        if (!on()) return;
        LivingEntity m = e.getEntity();
        if (FAR_REASONS.contains(why) && m instanceof org.bukkit.entity.Enemy && biomeKey(e.getLocation()).equals("faultline:farlands")
                && plugin.getConfig().getBoolean("elites.farlands-only-elites", true)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!m.isValid()) return;
                for (String t : m.getScoreboardTags()) if (t.startsWith("faultline") && !t.equals(CRAWLER)) return;   // bosses, echoes, escorts, raid units
                if (why == CreatureSpawnEvent.SpawnReason.CUSTOM && !m.getScoreboardTags().contains(CRAWLER)) return;   // other plugins' own mobs
                farlands(m, why);
            });
            return;
        }
        if (why != CreatureSpawnEvent.SpawnReason.NATURAL && why != CreatureSpawnEvent.SpawnReason.PATROL) return;
        // 1.3.0: any hostile mob can mutate (phantoms, ghasts, hoglins...; never slimes or magma cubes: slime farms);
        // only Monsters can turn Elite (the bonus loot)
        if (!(m instanceof org.bukkit.entity.Enemy) || m instanceof org.bukkit.entity.Slime || NEVER.contains(m.getType())
                || m.getWorld().getName().equals("faultline_void")) return;
        // other plugins tag their mobs right after spawning them: decide a tick later
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!m.isValid()) return;
            for (String t : m.getScoreboardTags()) if (t.startsWith("faultline")) return;
            // 2.0.0: Elites only spawn naturally in the Farlands (a lot of them) and, rarely, in the Fibers
            String biome = biomeKey(m.getLocation());
            double elite = biome.equals("faultline:farlands") ? cfg("farlands-chance", 0.35)
                    : biome.equals("faultline:fibers") ? cfg("fibers-chance", 0.04) : elsewhere();
            roll(m, elite, cfg("mutation-chance", 0.02));
        });
    }

    /** Everywhere else: 0. A server config kept from before 2.0 still says 0.01 (the old default): that reads as 0 too. */
    double elsewhere() { double c = cfg("chance", 0.0); return Math.abs(c - 0.01) < 1e-9 ? 0.0 : c; }

    /** The biome's key ("faultline:farlands"), or "" (the overworld generator's custom biomes come from its datapack). */
    static String biomeKey(org.bukkit.Location l) {
        try { return l.getBlock().getBiome().getKey().toString(); } catch (Throwable e) { return ""; }
    }

    /** Raid waves: a few elites and mutants mixed in. */
    void raidRoll(LivingEntity m) {
        if (!on() || NEVER.contains(m.getType())) return;
        roll(m, cfg("raid-chance", 0.06), cfg("raid-mutation-chance", 0.05));
    }

    private void roll(LivingEntity m, double elite, double mutation) {
        var r = plugin.random;
        if (r.nextDouble() < mutation) mutate(m);
        if (m instanceof Monster && r.nextDouble() < elite) elite(m);
    }

    /** A random mutation this kind of mob can have (1.3.0: 28 in all, Mutants.KINDS). */
    boolean mutate(LivingEntity m) {
        List<Mutants.Kind> can = plugin.mutants.candidates(m);
        if (can.isEmpty()) return false;
        return mutate(m, can.get(plugin.random.nextInt(can.size())));
    }

    boolean mutate(LivingEntity m, Mutants.Kind k) {
        Set<String> tags = m.getScoreboardTags();
        if (k == null || !k.fits().test(m) || tags.contains(MINI) || tags.contains(Mutants.MINION) || Mutants.kindOf(m) != null) return false;
        String kind = k.tag();
        m.addScoreboardTag(kind);
        switch (kind) {
            case LOOTER -> {
                name(m, ChatColor.DARK_GREEN + "Looter " + nice(m));
                if (m instanceof Mob mob) try { mob.setCanPickupItems(true); } catch (RuntimeException ignored) { }
            }
            case BLAZING -> {
                name(m, ChatColor.GOLD + "Blazing " + nice(m));
                m.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
                m.setVisualFire(true);
            }
            case SPLITTER -> name(m, ChatColor.GREEN + "Splitting Creeper");
            default -> plugin.mutants.apply(m, k);
        }
        return true;
    }

    void elite(LivingEntity m) {
        if (m.getScoreboardTags().contains(ELITE) || m.getScoreboardTags().contains(MINI) || m.getScoreboardTags().contains(Mutants.MINION)) return;
        m.addScoreboardTag(ELITE);
        String base = m.getCustomName() != null ? ChatColor.stripColor(m.getCustomName()) : nice(m);
        name(m, ChatColor.GOLD + "★ Elite " + ChatColor.YELLOW + base);
        modify(m, Attribute.MAX_HEALTH, healthKey, cfg("health-multiplier", 2.0) - 1);
        modify(m, Attribute.ATTACK_DAMAGE, damageKey, cfg("damage-multiplier", 1.5) - 1);
        modify(m, Attribute.MOVEMENT_SPEED, speedKey, cfg("speed-multiplier", 1.15) - 1);
        try { modify(m, Attribute.SCALE, scaleKey, 0.15); } catch (RuntimeException | LinkageError ignored) { }
        AttributeInstance mh = m.getAttribute(Attribute.MAX_HEALTH);
        if (mh != null) m.setHealth(mh.getValue());
        if (m instanceof Creeper c) c.setExplosionRadius(c.getExplosionRadius() + 1);
    }

    private void modify(LivingEntity m, Attribute a, NamespacedKey key, double amount) {
        AttributeInstance in = m.getAttribute(a);
        if (in == null || amount == 0) return;
        for (AttributeModifier old : new ArrayList<>(in.getModifiers())) if (old.getKey().equals(key)) in.removeModifier(old);
        in.addModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
    }

    private static void name(LivingEntity m, String n) {
        m.setCustomName(n);
        m.setCustomNameVisible(!m.getScoreboardTags().contains("faultline_stealth")); // 1.3.1: a Stealth Creeper's tag would show through its invisibility
    }

    static String nice(Entity e) {
        String s = e.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder b = new StringBuilder();
        for (String w : s.split(" ")) if (!w.isEmpty()) b.append(b.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return b.toString();
    }

    // ---------------------------------------------------------------- looters

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof LivingEntity z) || !z.getScoreboardTags().contains(LOOTER) || !(e.getEntity() instanceof Player p)) return;
        if (p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR || p.isBlocking()) return;
        if (plugin.random.nextDouble() >= cfg("looter.steal-chance", 0.3)) return;
        List<String> stolen = stolen(z);
        if (stolen.size() >= (int) cfg("looter.max-stacks", 3)) return;
        PlayerInventory inv = p.getInventory();
        List<Integer> slots = new ArrayList<>();
        for (int i = 9; i < 36; i++) { // the backpack only: never the hotbar, armor or offhand
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir() || protectedItem(it)) continue;
            slots.add(i);
        }
        if (slots.isEmpty()) return;
        int slot = slots.get(plugin.random.nextInt(slots.size()));
        ItemStack it = inv.getItem(slot);
        String data;
        try { data = Base64.getEncoder().encodeToString(it.serializeAsBytes()); } catch (RuntimeException | LinkageError ex) { return; }
        inv.setItem(slot, null);
        stolen.add(data);
        save(z, stolen);
        if (z instanceof Mob mob) try { mob.setRemoveWhenFarAway(false); mob.setPersistent(true); } catch (RuntimeException ignored) { }
        z.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 8, 1, false, true));
        p.sendMessage(ChatColor.RED + "A Looter Zombie stole your " + ChatColor.WHITE + it.getAmount() + " " + nice(it.getType()) + ChatColor.RED + "! Kill it to get it back.");
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 0.6f);
        z.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, z.getLocation().add(0, 2, 0), 6, 0.3, 0.3, 0.3, 0);
    }

    static String nice(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return s;
    }

    /** Lent race gear, the Faultline Index and the Quest Book stay with their owner. */
    static boolean protectedItem(ItemStack it) {
        if (!it.hasItemMeta()) return false;
        for (NamespacedKey k : it.getPersistentDataContainer().getKeys()) {
            String n = k.getKey();
            if (n.contains("race_lent") || n.equals("index_book") || n.contains("quest_book")) return true;
        }
        return false;
    }

    List<String> stolen(Entity z) {
        String s = z.getPersistentDataContainer().get(stolenKey, PersistentDataType.STRING);
        List<String> out = new ArrayList<>();
        if (s != null && !s.isEmpty()) for (String part : s.split(";")) if (!part.isEmpty()) out.add(part);
        return out;
    }

    private void save(Entity z, List<String> stolen) {
        PersistentDataContainer pdc = z.getPersistentDataContainer();
        if (stolen.isEmpty()) pdc.remove(stolenKey); else pdc.set(stolenKey, PersistentDataType.STRING, String.join(";", stolen));
    }

    /** Everything a looter carries, as items. */
    List<ItemStack> loot(Entity z) {
        List<ItemStack> out = new ArrayList<>();
        for (String s : stolen(z)) {
            try { out.add(ItemStack.deserializeBytes(Base64.getDecoder().decode(s))); } catch (RuntimeException | LinkageError ignored) { }
        }
        return out;
    }

    /** Hands a looter's stolen things to the mob it turned into (1.3.1). */
    void moveLoot(Entity from, Entity to) {
        List<String> s = stolen(from);
        if (s.isEmpty()) return;
        List<String> mine = stolen(to);
        mine.addAll(s);
        save(to, mine);
        save(from, new ArrayList<>());
        if (to instanceof Mob mob) try { mob.setRemoveWhenFarAway(false); mob.setPersistent(true); } catch (RuntimeException ignored) { }
    }

    /** Drops what a looter stole where it stands (used when a raid ends and removes its mobs). */
    void release(Entity z) {
        if (!z.getScoreboardTags().contains(LOOTER)) return;
        Location at = z.getLocation();
        for (ItemStack it : loot(z)) at.getWorld().dropItemNaturally(at, it);
        save(z, new ArrayList<>());
    }

    // ---------------------------------------------------------------- blazing skeletons

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(ProjectileLaunchEvent e) {
        Projectile pr = e.getEntity();
        if (pr instanceof AbstractArrow && pr.getShooter() instanceof LivingEntity s && s.getScoreboardTags().contains(BLAZING)) pr.setFireTicks(20 * 10);
    }

    // ---------------------------------------------------------------- splitting creepers

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        if (!(e.getEntity() instanceof Creeper c) || !c.getScoreboardTags().contains(SPLITTER)) return;
        exploded.add(c.getUniqueId());
        split(c.getLocation());
    }

    void split(Location at) {
        int n = 2 + plugin.random.nextInt(2);
        for (int i = 0; i < n; i++) {
            Location l = at.clone().add(plugin.random.nextDouble() * 2 - 1, 0.2, plugin.random.nextDouble() * 2 - 1);
            if (!(at.getWorld().spawnEntity(l, EntityType.CREEPER, false) instanceof Creeper mini)) continue;
            mini.addScoreboardTag(MINI);
            mini.setCustomName(ChatColor.GREEN + "Mini Creeper");
            mini.setMaxFuseTicks(20);
            mini.setExplosionRadius(1);
            try {
                AttributeInstance sc = mini.getAttribute(Attribute.SCALE);
                if (sc != null) sc.setBaseValue(0.55);
            } catch (RuntimeException | LinkageError ignored) { }
            AttributeInstance mh = mini.getAttribute(Attribute.MAX_HEALTH);
            if (mh != null) { mh.setBaseValue(6); mini.setHealth(6); }
            Safe.vel(mini, new org.bukkit.util.Vector(l.getX() - at.getX(), 0.35, l.getZ() - at.getZ()).multiply(0.6));
        }
        at.getWorld().playSound(at, Sound.ENTITY_SLIME_SQUISH, 1f, 1.4f);
    }

    // ---------------------------------------------------------------- deaths

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity m = e.getEntity();
        Set<String> tags = m.getScoreboardTags();
        if (tags.contains(MINI)) { e.getDrops().clear(); e.setDroppedExp(1); return; }
        // 1.3.1: what it carried (picked up off the ground, or stole) is never doubled: an Elite fed a stack of diamond
        // blocks dropped two. Only its own loot-table drops count, and the stolen things are added after the count.
        List<ItemStack> carried = new ArrayList<>();
        var eq = m.getEquipment();
        if (eq != null) for (ItemStack it : eq.getArmorContents()) if (it != null && !it.getType().isAir()) carried.add(it);
        if (eq != null) { carried.add(eq.getItemInMainHand()); carried.add(eq.getItemInOffHand()); }
        List<ItemStack> twice = new ArrayList<>();
        for (ItemStack d : e.getDrops()) {
            if (d == null || d.getType().isAir() || !plain(d)) continue;
            boolean held = false;
            for (ItemStack c : carried) if (c != null && !c.getType().isAir() && c.isSimilar(d)) { held = true; break; }
            if (!held) twice.add(d.clone()); // vanilla drops, not gear/stolen/picked-up/plugin items
        }
        if (tags.contains(LOOTER)) e.getDrops().addAll(loot(m));
        if (tags.contains(SPLITTER) && !exploded.remove(m.getUniqueId())) split(m.getLocation());
        Player killer = m.getKiller();
        if (tags.contains(BLAZING) && killer != null && plugin.random.nextDouble() < 0.5) e.getDrops().add(new ItemStack(Material.BLAZE_POWDER, 1 + plugin.random.nextInt(2)));
        if (!tags.contains(ELITE) || killer == null) return;
        e.getDrops().addAll(twice);
        e.setDroppedExp(Math.max(10, e.getDroppedExp() * 3));
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("elites");
        boolean far = tags.contains(FAR);
        List<String> rolls = far ? (c != null && c.isList("farlands-bonus-loot") ? c.getStringList("farlands-bonus-loot") : FAR_LOOT)
                : c != null && c.isList("bonus-loot") ? c.getStringList("bonus-loot") : DEFAULT_LOOT;
        int times = tags.contains(CAPTAIN) ? 3 : 1;
        for (int rep = 0; rep < times; rep++) for (String s : rolls) {
            String[] p = s.split(":");
            if (p.length < 3) continue;
            Material mat = Material.matchMaterial(p[0]);
            if (mat == null) continue;
            try {
                String[] mm = p[1].split("-");
                int lo = Integer.parseInt(mm[0]), hi = Integer.parseInt(mm[mm.length - 1]);
                if (plugin.random.nextDouble() < Double.parseDouble(p[2])) e.getDrops().add(new ItemStack(mat, lo + plugin.random.nextInt(Math.max(1, hi - lo + 1))));
            } catch (NumberFormatException ignored) { }
        }
        Location l = m.getLocation();
        if (tags.contains(CAPTAIN)) {
            if (Bukkit.getPluginCommand("givegoodiebag") != null)
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "givegoodiebag 1 " + killer.getName() + " at:" + l.getWorld().getName() + "," + l.getX() + "," + l.getY() + "," + l.getZ());
            if (plugin.random.nextDouble() < cfg("captain.sigil-chance", 0.1) && Bukkit.getPluginCommand("rimwarden") != null)
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "rimwarden sigil 1 " + killer.getName());
            if (Bukkit.getPluginCommand("index") != null) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + killer.getName() + " elite_captains 1");
            killer.sendMessage(ChatColor.RED + "You took down an Elite Captain.");
        } else if (plugin.random.nextDouble() < cfg(far ? "farlands-goodie-bag-chance" : "goodie-bag-chance", far ? 0.08 : 0.04) && Bukkit.getPluginCommand("givegoodiebag") != null)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "givegoodiebag 1 " + killer.getName() + " at:" + l.getWorld().getName() + "," + l.getX() + "," + l.getY() + "," + l.getZ());
        if (Bukkit.getPluginCommand("index") != null) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + killer.getName() + " elites 1");
        killer.playSound(l, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.6f);
    }

    /** A plain drop (bones, gunpowder...): no name, no enchantments, no plugin data; armor/weapons never double. */
    static boolean plain(ItemStack d) {
        if (d.getType().getMaxStackSize() == 1) return false;
        if (!d.hasItemMeta()) return true;
        var m = d.getItemMeta();
        return !m.hasDisplayName() && !m.hasEnchants() && m.getPersistentDataContainer().getKeys().isEmpty();
    }

    /** Admin: /zraid elite <type> spawns an Elite where you look. */
    LivingEntity spawn(Location at, EntityType type, boolean elite) {
        if (!(at.getWorld().spawnEntity(at, type, false) instanceof LivingEntity m)) return null;
        if (elite) elite(m);
        return m;
    }

    /** Admin: /zraid mutant <kind> [mob] [elite]. */
    LivingEntity spawnMutant(Location at, Mutants.Kind k, EntityType type, boolean elite) {
        if (!(at.getWorld().spawnEntity(at, type == null ? k.sample() : type, false) instanceof LivingEntity m)) return null;
        if (!mutate(m, k)) { m.remove(); return null; }
        if (elite) elite(m);
        return m;
    }
}
