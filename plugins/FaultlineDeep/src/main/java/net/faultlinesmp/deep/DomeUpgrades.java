package net.faultlinesmp.deep;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.FurnaceStartSmeltEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * DOME UPGRADES (Deep 1.12.0): 20 upgrades for a dome base, bought with materials from `/dome upgrades` (stand in your dome:
 * a chest menu on Java, a form on Bedrock). Each is per dome and saved with it in domes.yml (`upgrades`, `tint`); the
 * trust list is per owner (`trust.<owner>`, /dome trust|untrust <player>) and counts for all their domes.
 *  airlock (doors shut themselves, Water Breathing on the way out), keycard (only you + trusted open the doors), vault
 *  (containers inside: only you + trusted open or break them), oxygen (leaving = 60 s Water Breathing), bubble_ring
 *  (breathe within 6 blocks outside), hydroponics (crops +50%), fish_trap (a barrel inside fills with fish), furnace
 *  (cook x2), regen (Regeneration I for you + trusted inside), floodlights (no Abyss dark within 12), sonar (hostiles within
 *  40 glow), turret (hostiles within 6 of the glass get hurt and pushed), ward (no hostile spawns within 24), beacon
 *  (/dome home), respawn (die in that world, come back here), dock (Submersibles within 12 repair), pressure (no Pressure
 *  Surge / hadal pressure / acid within 16), heat (no freezing), alarm (strangers walking in), tint (recolour the glass).
 *  Costs: `habitat.upgrades.<id>` = "MATERIAL:n,MATERIAL:n" (plain items only), the defaults in UPS.
 */
final class DomeUpgrades implements Listener {
    record Up(String id, String name, Material icon, String cost, String desc) { }

    static final List<Up> UPS = List.of(
            new Up("airlock", "Airlock", Material.IRON_DOOR, "IRON_BLOCK:2,PRISMARINE_BRICKS:8", "Doors shut themselves 2 s after they open, and swimming out through one gives you 20 s of Water Breathing."),
            new Up("keycard", "Keycard Doors", Material.TRIPWIRE_HOOK, "GOLD_INGOT:8,REDSTONE:16", "Only you and the players you trust can open its doors."),
            new Up("vault", "Locked Vault", Material.CHEST, "DIAMOND:4,IRON_BLOCK:2", "Chests, barrels, furnaces... inside only open for you and your trusted players, and nobody else can break them."),
            new Up("oxygen", "Oxygen Reserve", Material.GLASS_BOTTLE, "PRISMARINE_CRYSTALS:16,NAUTILUS_SHELL:1", "Leaving the dome gives you 60 s of Water Breathing (once every 2 minutes)."),
            new Up("bubble_ring", "Bubble Ring", Material.BUBBLE_CORAL_BLOCK, "PRISMARINE_CRYSTALS:24,SPONGE:2", "Anyone swimming within 6 blocks of the glass can breathe."),
            new Up("hydroponics", "Hydroponics Bay", Material.WHEAT, "BONE_BLOCK:8,GLOWSTONE:8", "Crops inside grow 50% faster."),
            new Up("fish_trap", "Fish Trap", Material.COD, "STRING:16,IRON_BARS:8", "A barrel inside fills up with fish: one every 5 minutes."),
            new Up("furnace", "Thermal Vents", Material.BLAST_FURNACE, "MAGMA_BLOCK:8,COPPER_BLOCK:4", "Furnaces, smokers and blast furnaces inside cook twice as fast."),
            new Up("regen", "Med Bay", Material.GLISTERING_MELON_SLICE, "GOLDEN_APPLE:2,GLISTERING_MELON_SLICE:8", "Regeneration I inside, for you and your trusted players."),
            new Up("floodlights", "Floodlights", Material.SEA_LANTERN, "SEA_LANTERN:16,GLOWSTONE:16", "The Abyss's darkness lifts within 12 blocks of the dome."),
            new Up("sonar", "Sonar", Material.ECHO_SHARD, "ECHO_SHARD:2,AMETHYST_SHARD:8", "Every 5 seconds, hostile creatures within 40 blocks light up (they glow)."),
            new Up("turret", "Defense Pulse", Material.CONDUIT, "HEART_OF_THE_SEA:1,PRISMARINE_SHARD:32", "Every 3 seconds, hostile creatures within 6 blocks of the glass take damage and are pushed away."),
            new Up("ward", "Mob Ward", Material.LODESTONE, "LODESTONE:1,ENDER_PEARL:8", "No hostile creature spawns within 24 blocks of the dome."),
            new Up("beacon", "Home Beacon", Material.RECOVERY_COMPASS, "ECHO_SHARD:4,COMPASS:1", "/dome home takes you (and your trusted players) back here. Every 5 minutes, never mid-fight."),
            new Up("respawn", "Respawn Pod", Material.RED_BED, "NAUTILUS_SHELL:2,DIAMOND:2", "Die in this world and you wake up in the dome."),
            new Up("dock", "Docking Bay", Material.IRON_TRAPDOOR, "IRON_BLOCK:4,COPPER_BLOCK:8", "Submersibles parked within 12 blocks slowly repair their hull."),
            new Up("pressure", "Pressure Hull", Material.NETHERITE_SCRAP, "NETHERITE_SCRAP:2,OBSIDIAN:16", "No Pressure Surge, hadal pressure or acid water within 16 blocks of the dome."),
            new Up("heat", "Heat Lamps", Material.CAMPFIRE, "BLAZE_ROD:6,MAGMA_BLOCK:4", "Nobody freezes inside the dome or within 8 blocks of it."),
            new Up("alarm", "Intruder Alarm", Material.BELL, "BELL:1,REDSTONE_BLOCK:2", "You're told (wherever you are) when someone you don't trust walks in."),
            new Up("tint", "Stained Shell", Material.MAGENTA_STAINED_GLASS, "AMETHYST_SHARD:16,GLASS:16", "Recolour the dome's glass. Click it again here (or /dome color <colour>) to change the colour."));

    static final List<String> COLOURS = List.of("light_blue", "cyan", "blue", "purple", "magenta", "pink", "red", "orange", "yellow", "lime", "green",
            "white", "light_gray", "gray", "black", "brown");

    final FaultlineDeep pl;
    final Habitat hab;
    final Map<UUID, Set<UUID>> trust = new HashMap<>();
    final Map<UUID, Integer> insideOf = new HashMap<>();       // player -> the dome they were in last second
    final Map<UUID, Long> oxygenAt = new HashMap<>(), homeAt = new HashMap<>(), hurtAt = new HashMap<>();
    final Map<String, Long> alarmed = new HashMap<>();          // "dome:intruder" -> when last told
    final Map<Integer, Long> barrel = new HashMap<>();          // dome -> its fish trap barrel
    final Random random = new Random();
    int secs;

    DomeUpgrades(FaultlineDeep pl, Habitat hab) {
        this.pl = pl;
        this.hab = hab;
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20L, 20L);
    }

    static Up up(String id) { for (Up u : UPS) if (u.id.equals(id)) return u; return null; }

    boolean enabled() { return pl.getConfig().getBoolean("habitat.upgrades.enabled", true); }

    String cost(Up u) { return pl.getConfig().getString("habitat.upgrades." + u.id, u.cost); }

    /** The cost as materials (anything that doesn't parse is skipped). */
    static Map<Material, Integer> parse(String cost) {
        Map<Material, Integer> m = new LinkedHashMap<>();
        for (String part : cost.split(",")) {
            String[] kv = part.trim().split(":");
            Material mat = Material.matchMaterial(kv[0].trim());
            if (mat == null || !mat.isItem()) continue;
            int n = 1;
            try { if (kv.length > 1) n = Math.max(1, Integer.parseInt(kv[1].trim())); } catch (NumberFormatException ignored) { }
            m.merge(mat, n, Integer::sum);
        }
        return m;
    }

    static String pretty(Map<Material, Integer> m) {
        List<String> l = new ArrayList<>();
        for (var e : m.entrySet()) l.add(e.getValue() + " " + e.getKey().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        return String.join(", ", l);
    }

    // =========================================================================================== who, where
    boolean trusted(Habitat.Dome d, Player p) {
        return d.owner.equals(p.getUniqueId()) || trust.getOrDefault(d.owner, Set.of()).contains(p.getUniqueId()) || p.hasPermission("faultlinedeep.market");
    }

    static boolean has(Habitat.Dome d, String id) { return d != null && d.ups.contains(id); }

    /** The dome whose air this spot is in. */
    Habitat.Dome domeAt(Location l) {
        if (l.getWorld() == null) return null;
        String w = l.getWorld().getName();
        for (Habitat.Dome d : hab.domes.values()) if (d.world.equals(w) && d.inside(l.getX(), l.getY(), l.getZ())) return d;
        return null;
    }

    /** The dome whose space (shell and floor included) holds this block. */
    Habitat.Dome domeOfBlock(Block b) {
        String w = b.getWorld().getName();
        for (Habitat.Dome d : hab.domes.values()) if (d.world.equals(w) && d.area(b.getX(), b.getY(), b.getZ())) return d;
        return null;
    }

    /** A dome with this upgrade within `extra` blocks of its glass. */
    Habitat.Dome near(Location l, String id, double extra) {
        if (l.getWorld() == null) return null;
        String w = l.getWorld().getName();
        for (Habitat.Dome d : hab.domes.values()) {
            if (!d.world.equals(w) || !d.ups.contains(id)) continue;
            double dx = l.getX() - d.x - 0.5, dy = l.getY() - d.y, dz = l.getZ() - d.z - 0.5;
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) < d.r + extra) return d;
        }
        return null;
    }

    /** Floodlights: the Abyss's dark lifts here (Ocean.darkZone). */
    boolean lit(Location l) { return enabled() && near(l, "floodlights", 12) != null; }

    /** Pressure Hull: no Pressure Surge / hadal pressure / acid water here. */
    boolean shielded(Location l) { return enabled() && near(l, "pressure", 16) != null; }

    /** Mob Ward: no hostile creatures spawn here. */
    boolean warded(Location l) { return enabled() && near(l, "ward", 24) != null; }

    static boolean hostile(Entity e, FaultlineDeep pl) {
        if (!(e instanceof LivingEntity) || e instanceof Player) return false;
        DeepMobs.Beast b = pl.mobs == null ? null : pl.mobs.live.get(e.getUniqueId());
        if (b != null) return b.k.temper() == DeepMobs.Temper.AGGRESSIVE;
        return e instanceof Enemy;
    }

    // =========================================================================================== every second
    void tick() {
        secs++;
        if (!enabled() || hab.domes.isEmpty()) return;
        for (Habitat.Dome d : new ArrayList<>(hab.domes.values())) {
            if (d.ups.isEmpty()) continue;
            World w = Bukkit.getWorld(d.world);
            if (w == null || !w.isChunkLoaded(d.x >> 4, d.z >> 4)) continue;
            Location c = new Location(w, d.x + 0.5, d.y + d.r * 0.4, d.z + 0.5);
            for (Player p : w.getPlayers()) {
                double dist = p.getLocation().distance(new Location(w, d.x + 0.5, d.y, d.z + 0.5));
                if (dist > d.r + 16) continue;
                boolean in = d.inside(p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ());
                boolean friend = trusted(d, p);
                if (in && has(d, "regen") && friend) p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, true, false, true));
                if (has(d, "heat") && dist < d.r + 8 && p.getFreezeTicks() > 0) p.setFreezeTicks(0);
                if (has(d, "bubble_ring") && !in && dist < d.r + 6) p.setRemainingAir(p.getMaximumAir());
                if (in && has(d, "alarm") && !friend && p.getGameMode() != GameMode.SPECTATOR) alarm(d, p);
            }
            if (has(d, "turret") && secs % 3 == 0) turret(d, w, c);
            if (has(d, "sonar") && secs % 5 == 0)
                for (Entity e : w.getNearbyEntities(c, d.r + 40, d.r + 40, d.r + 40))
                    if (hostile(e, pl)) ((LivingEntity) e).addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 70, 0, true, false, false));
            if (has(d, "fish_trap") && secs % (int) Math.max(5, pl.getConfig().getDouble("habitat.upgrades-settings.fish-every-seconds", 300)) == 0) fishTrap(d, w);
            if (has(d, "dock") && secs % 5 == 0 && pl.subs != null) dock(d, w);
        }
        // leaving a dome (the Oxygen Reserve; the Airlock's breath is on the doors)
        for (Player p : Bukkit.getOnlinePlayers()) {
            Habitat.Dome now = domeAt(p.getLocation());
            Integer was = insideOf.get(p.getUniqueId());
            if (now != null) { insideOf.put(p.getUniqueId(), now.id); continue; }
            if (was == null) continue;
            insideOf.remove(p.getUniqueId());
            Habitat.Dome left = hab.domes.get(was);
            if (!has(left, "oxygen")) continue;
            long t = oxygenAt.getOrDefault(p.getUniqueId(), -999L);
            if (secs - t < 120) continue;
            oxygenAt.put(p.getUniqueId(), (long) secs);
            p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 20 * 60, 0, true, false, true));
            p.sendActionBar(ChatColor.AQUA + "Oxygen Reserve: " + ChatColor.GRAY + "60 s of air for the swim.");
        }
    }

    void turret(Habitat.Dome d, World w, Location c) {
        double reach = d.r + 6;
        boolean any = false;
        for (Entity e : w.getNearbyEntities(c, reach, reach, reach)) {
            if (!hostile(e, pl)) continue;
            Location el = e.getLocation();
            double dx = el.getX() - d.x - 0.5, dy = el.getY() - d.y, dz = el.getZ() - d.z - 0.5;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > reach) continue;
            LivingEntity le = (LivingEntity) e;
            le.damage(pl.getConfig().getDouble("habitat.upgrades-settings.turret-damage", 4));
            org.bukkit.util.Vector away = dist < 0.01 ? new org.bukkit.util.Vector(0, 0.6, 0) : new org.bukkit.util.Vector(dx, dy, dz).multiply(0.8 / dist);
            if (Double.isFinite(away.getX()) && Double.isFinite(away.getY()) && Double.isFinite(away.getZ())) le.setVelocity(away);
            w.spawnParticle(Particle.ELECTRIC_SPARK, el.clone().add(0, 0.5, 0), 10, 0.3, 0.3, 0.3, 0.05);
            any = true;
        }
        if (any) w.playSound(c, Sound.BLOCK_CONDUIT_ATTACK_TARGET, 1f, 1.2f);
    }

    void alarm(Habitat.Dome d, Player intruder) {
        String key = d.id + ":" + intruder.getUniqueId();
        long last = alarmed.getOrDefault(key, -999L);
        if (secs - last < 60) return;
        alarmed.put(key, (long) secs);
        Player owner = Bukkit.getPlayer(d.owner);
        if (owner != null && !owner.equals(intruder)) {
            owner.sendMessage(ChatColor.RED + "[Dome alarm] " + ChatColor.WHITE + intruder.getName() + ChatColor.GRAY + " walked into your dome at " + d.x + " " + d.y + " " + d.z + ".");
            owner.playSound(owner.getLocation(), Sound.BLOCK_BELL_USE, 1f, 1.4f);
        }
        intruder.sendActionBar(ChatColor.RED + "An alarm rings somewhere in the dome.");
    }

    void fishTrap(Habitat.Dome d, World w) {
        Block b = null;
        Long known = barrel.get(d.id);
        if (known != null) { int[] p = Volcano.unpack(known); Block k = w.getBlockAt(p[0], p[1], p[2]); if (k.getType() == Material.BARREL) b = k; }
        if (b == null) { // look for one inside
            for (int dx = -d.r; dx <= d.r && b == null; dx++) for (int dz = -d.r; dz <= d.r && b == null; dz++) for (int dy = -1; dy <= d.r && b == null; dy++) {
                if (!d.area(d.x + dx, d.y + dy, d.z + dz)) continue;
                Block k = w.getBlockAt(d.x + dx, d.y + dy, d.z + dz);
                if (k.getType() == Material.BARREL) b = k;
            }
            if (b == null) { barrel.remove(d.id); return; }
            barrel.put(d.id, Habitat.pack(b.getX(), b.getY(), b.getZ()));
        }
        if (!(b.getState() instanceof Container box)) return;
        double r = random.nextDouble();
        Material fish = r < 0.6 ? Material.COD : r < 0.85 ? Material.SALMON : r < 0.97 ? Material.TROPICAL_FISH : Material.PUFFERFISH;
        box.getInventory().addItem(new ItemStack(fish));
    }

    void dock(Habitat.Dome d, World w) {
        Location c = new Location(w, d.x + 0.5, d.y, d.z + 0.5);
        for (Submersible.Sub s : pl.subs.subs.values()) {
            if (s.stand == null || !s.stand.isValid() || !s.stand.getWorld().equals(w)) continue;
            if (s.stand.getLocation().distance(c) > d.r + 12) continue;
            double h = s.hull();
            if (h >= 100) continue;
            s.hull(h + pl.getConfig().getDouble("habitat.upgrades-settings.dock-repair", 2));
            w.spawnParticle(Particle.WAX_OFF, s.stand.getLocation().add(0, 1, 0), 6, 0.6, 0.4, 0.6, 0);
        }
    }

    // =========================================================================================== events
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onDoor(PlayerInteractEvent e) {
        if (!enabled() || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        Player p = e.getPlayer();
        if (b.getType() == Material.DARK_OAK_DOOR && b.getBlockData() instanceof Openable) {
            Habitat.Piece piece = hab.shellPiece(b);
            if (!(piece instanceof Habitat.Dome d)) return;
            if (has(d, "keycard") && !trusted(d, p)) {
                e.setCancelled(true);
                p.sendActionBar(ChatColor.RED + "The door won't open." + ChatColor.GRAY + " (Keycard Doors: only people its owner trusts.)");
                p.playSound(b.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 0.8f, 1.6f);
                return;
            }
            if (has(d, "airlock") && !((Openable) b.getBlockData()).isOpen()) { // opening it: it shuts itself, and you get a breath
                p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 20 * 20, 0, true, false, true));
                Bukkit.getScheduler().runTaskLater(pl, () -> {
                    if (b.getBlockData() instanceof Openable o && o.isOpen()) {
                        o.setOpen(false);
                        b.setBlockData(o);
                        b.getWorld().playSound(b.getLocation(), Sound.BLOCK_WOODEN_DOOR_CLOSE, 1f, 0.8f);
                        b.getWorld().spawnParticle(Particle.BUBBLE_POP, b.getLocation().add(0.5, 1, 0.5), 20, 0.4, 0.6, 0.4, 0.05);
                    }
                }, 40L);
            }
            return;
        }
        if (b.getState() instanceof Container) {
            Habitat.Dome d = domeOfBlock(b);
            if (has(d, "vault") && !trusted(d, p)) {
                e.setCancelled(true);
                p.sendActionBar(ChatColor.RED + "Locked." + ChatColor.GRAY + " (Locked Vault: only people its owner trusts.)");
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!enabled() || !(e.getBlock().getState() instanceof Container)) return;
        Habitat.Dome d = domeOfBlock(e.getBlock());
        if (has(d, "vault") && !trusted(d, e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(ChatColor.RED + "Locked." + ChatColor.GRAY + " (Locked Vault)");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) {
        if (!enabled()) return;
        Block b = e.getBlock();
        Habitat.Dome d = domeOfBlock(b);
        if (!has(d, "hydroponics") || random.nextDouble() >= pl.getConfig().getDouble("habitat.upgrades-settings.hydroponics-boost", 0.5)) return;
        Bukkit.getScheduler().runTask(pl, () -> {
            if (b.getBlockData() instanceof Ageable a && a.getAge() < a.getMaximumAge()) { a.setAge(a.getAge() + 1); b.setBlockData(a); }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onSmelt(FurnaceStartSmeltEvent e) {
        if (!enabled()) return;
        if (has(domeOfBlock(e.getBlock()), "furnace")) e.setTotalCookTime(Math.max(1, e.getTotalCookTime() / 2));
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!enabled() || !(e.getEntity() instanceof Enemy)) return;
        switch (e.getSpawnReason()) {
            case NATURAL, SPAWNER, PATROL, REINFORCEMENTS, JOCKEY, MOUNT, DROWNED, TRIAL_SPAWNER -> { }
            default -> { return; }
        }
        if (warded(e.getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFreeze(EntityDamageEvent e) {
        if (!enabled() || e.getCause() != EntityDamageEvent.DamageCause.FREEZE || !(e.getEntity() instanceof Player p)) return;
        if (near(p.getLocation(), "heat", 8) != null) { e.setCancelled(true); p.setFreezeTicks(0); }
    }

    @EventHandler
    public void onHurt(EntityDamageEvent e) { if (e.getEntity() instanceof Player p) hurtAt.put(p.getUniqueId(), System.currentTimeMillis()); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) {
        if (!enabled() || e.isBedSpawn() || e.isAnchorSpawn()) return;
        Player p = e.getPlayer();
        World died = p.getWorld();
        for (Habitat.Dome d : hab.domes.values()) {
            if (!d.owner.equals(p.getUniqueId()) || !has(d, "respawn") || !d.world.equals(died.getName())) continue;
            World w = Bukkit.getWorld(d.world);
            if (w == null) continue;
            e.setRespawnLocation(new Location(w, d.x + 0.5, d.y, d.z + 0.5, p.getLocation().getYaw(), 0));
            return;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { insideOf.remove(e.getPlayer().getUniqueId()); }

    // =========================================================================================== the menu
    static final class Menu implements InventoryHolder {
        final int dome;
        Inventory inv;
        Menu(int dome) { this.dome = dome; }
        @Override public Inventory getInventory() { return inv; }
    }

    void open(Player p, Habitat.Dome d) {
        if (Edition.bedrock(p)) {
            List<String> buttons = new ArrayList<>();
            for (Up u : UPS) buttons.add((d.ups.contains(u.id) ? "§2✔ " : "§0") + u.name + (d.ups.contains(u.id) ? (u.id.equals("tint") ? " §7(colour)" : "") : "\n§8" + pretty(parse(cost(u)))));
            if (BedrockForms.menu(pl, p, "Dome Upgrades", "Your " + (d.r >= 10 ? "large" : "small") + " dome at " + d.x + " " + d.y + " " + d.z
                    + ". Pick one to install (you need the materials), or a ✔ one to read about it.", buttons, i -> { if (i >= 0 && i < UPS.size()) pick(p, d, UPS.get(i)); }, null)) return;
        }
        Menu holder = new Menu(d.id);
        Inventory inv = Bukkit.createInventory(holder, 27, ChatColor.DARK_AQUA + "Dome Upgrades");
        holder.inv = inv;
        for (int i = 0; i < UPS.size(); i++) inv.setItem(i, icon(d, UPS.get(i)));
        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta m = info.getItemMeta();
        m.setDisplayName(ChatColor.AQUA + "Your dome");
        int n = d.ups.size();
        m.setLore(List.of(ChatColor.GRAY + "" + n + " of " + UPS.size() + " upgrades installed.", ChatColor.GRAY + "Trusted: " + trustedNames(d.owner),
                ChatColor.DARK_GRAY + "/dome trust <player>, /dome untrust <player>"));
        info.setItemMeta(m);
        inv.setItem(22, info);
        p.openInventory(inv);
    }

    ItemStack icon(Habitat.Dome d, Up u) {
        boolean got = d.ups.contains(u.id);
        ItemStack it = new ItemStack(u.id.equals("tint") && got ? glass(d.tint) : u.icon);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName((got ? ChatColor.GREEN + "✔ " : ChatColor.AQUA + "") + u.name);
        List<String> lore = new ArrayList<>();
        for (String line : wrap(u.desc, 34)) lore.add(ChatColor.GRAY + line);
        lore.add("");
        if (got) lore.add(u.id.equals("tint") ? ChatColor.YELLOW + "Click: next colour (" + (d.tint == null ? "light_blue" : d.tint) + ")" : ChatColor.GREEN + "Installed");
        else { lore.add(ChatColor.GOLD + "Cost: " + ChatColor.WHITE + pretty(parse(cost(u)))); lore.add(ChatColor.YELLOW + "Click to install"); }
        m.setLore(lore);
        if (got) m.setEnchantmentGlintOverride(true);
        it.setItemMeta(m);
        return it;
    }

    static List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : s.split(" ")) {
            if (line.length() > 0 && line.length() + w.length() + 1 > width) { out.add(line.toString()); line.setLength(0); }
            if (line.length() > 0) line.append(' ');
            line.append(w);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    String trustedNames(UUID owner) {
        Set<UUID> t = trust.getOrDefault(owner, Set.of());
        if (t.isEmpty()) return "nobody";
        List<String> n = new ArrayList<>();
        for (UUID id : t) { OfflinePlayer o = Bukkit.getOfflinePlayer(id); n.add(o.getName() == null ? id.toString().substring(0, 8) : o.getName()); }
        return String.join(", ", n);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        Habitat.Dome d = hab.domes.get(menu.dome);
        if (d == null) { p.closeInventory(); return; }
        if (slot < 0 || slot >= UPS.size()) return;
        pick(p, d, UPS.get(slot));
        if (p.getOpenInventory().getTopInventory() == menu.inv) for (int i = 0; i < UPS.size(); i++) menu.inv.setItem(i, icon(d, UPS.get(i)));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    /** Install an upgrade (or, for the Stained Shell, the next colour). */
    void pick(Player p, Habitat.Dome d, Up u) {
        if (!d.owner.equals(p.getUniqueId()) && !p.hasPermission("faultlinedeep.market")) { p.sendMessage(ChatColor.RED + "Only the dome's owner can change it."); return; }
        if (d.ups.contains(u.id)) {
            if (u.id.equals("tint")) { int i = COLOURS.indexOf(d.tint == null ? "light_blue" : d.tint); tint(d, COLOURS.get((i + 1) % COLOURS.size())); p.sendActionBar(ChatColor.AQUA + "The glass turns " + d.tint.replace('_', ' ') + "."); }
            else p.sendMessage(ChatColor.AQUA + u.name + ChatColor.GRAY + ": " + u.desc);
            return;
        }
        Map<Material, Integer> need = parse(cost(u));
        if (p.getGameMode() != GameMode.CREATIVE) {
            for (var en : need.entrySet()) if (!p.getInventory().containsAtLeast(new ItemStack(en.getKey()), en.getValue())) {
                p.sendMessage(ChatColor.RED + "You need " + pretty(need) + " for the " + u.name + ".");
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
                return;
            }
            for (var en : need.entrySet()) p.getInventory().removeItem(new ItemStack(en.getKey(), en.getValue()));
        }
        d.ups.add(u.id);
        if (u.id.equals("tint")) tint(d, "cyan");
        hab.save();
        World w = Bukkit.getWorld(d.world);
        if (w != null) {
            Location c = new Location(w, d.x + 0.5, d.y + 1, d.z + 0.5);
            w.playSound(c, Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.3f);
            w.spawnParticle(Particle.HAPPY_VILLAGER, c, 30, d.r * 0.4, 1, d.r * 0.4, 0);
        }
        p.sendMessage(ChatColor.GREEN + "Installed: " + ChatColor.AQUA + u.name + ChatColor.GRAY + ". " + u.desc);
        pl.console("index stat " + p.getName() + " dome_upgrades 1");
    }

    static Material glass(String colour) {
        Material m = Material.matchMaterial((colour == null ? "light_blue" : colour) + "_stained_glass");
        return m == null ? Material.LIGHT_BLUE_STAINED_GLASS : m;
    }

    /** Recolours the dome's glass (every stained glass block of its own shell). */
    void tint(Habitat.Dome d, String colour) {
        d.tint = colour;
        World w = Bukkit.getWorld(d.world);
        if (w != null) {
            Material to = glass(colour);
            for (long key : d.before.keySet()) {
                int[] c = Volcano.unpack(key);
                if (!d.shellCell(c[0], c[1], c[2]) || !w.isChunkLoaded(c[0] >> 4, c[2] >> 4)) continue;
                Block b = w.getBlockAt(c[0], c[1], c[2]);
                if (b.getType().name().endsWith("_STAINED_GLASS")) b.setType(to, false);
            }
        }
        hab.save();
    }

    // =========================================================================================== /dome upgrades, trust, home, color
    boolean command(Player p, String sub, String[] args) {
        switch (sub) {
            case "upgrades", "upgrade", "up" -> {
                if (!enabled()) { p.sendMessage(ChatColor.GRAY + "Dome upgrades are switched off."); return true; }
                Habitat.Dome d = domeAt(p.getLocation());
                if (d == null) d = domeOfBlock(p.getLocation().getBlock());
                if (d == null) { p.sendMessage(ChatColor.RED + "Stand in one of your domes."); return true; }
                if (!d.owner.equals(p.getUniqueId()) && !p.hasPermission("faultlinedeep.market")) { p.sendMessage(ChatColor.RED + "It isn't your dome."); return true; }
                open(p, d);
            }
            case "trust", "untrust" -> {
                if (args.length < 2) { p.sendMessage(ChatColor.YELLOW + "/dome " + sub + " <player>" + ChatColor.GRAY + "  (trusted: " + trustedNames(p.getUniqueId()) + ")"); return true; }
                OfflinePlayer o = Bukkit.getPlayerExact(args[1]);
                if (o == null) o = Bukkit.getOfflinePlayerIfCached(args[1]);
                if (o == null) { p.sendMessage(ChatColor.RED + "Nobody called " + args[1] + " has played here."); return true; }
                Set<UUID> t = trust.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
                if (sub.equals("trust")) t.add(o.getUniqueId()); else t.remove(o.getUniqueId());
                if (t.isEmpty()) trust.remove(p.getUniqueId());
                hab.save();
                p.sendMessage(ChatColor.GREEN + (sub.equals("trust") ? "Trusted " : "No longer trusting ") + o.getName() + ChatColor.GRAY + " in all your domes.");
            }
            case "home" -> {
                List<Habitat.Dome> homes = new ArrayList<>();
                for (Habitat.Dome d : hab.domes.values()) if (has(d, "beacon") && trusted(d, p) && Bukkit.getWorld(d.world) != null) homes.add(d);
                homes.sort((a, b) -> Boolean.compare(!a.owner.equals(p.getUniqueId()), !b.owner.equals(p.getUniqueId()))); // yours first
                if (homes.isEmpty()) { p.sendMessage(ChatColor.RED + "None of your domes (or your friends') has a Home Beacon."); return true; }
                int pick = 0;
                if (args.length > 1) try { pick = Math.max(0, Math.min(homes.size() - 1, Integer.parseInt(args[1]) - 1)); } catch (NumberFormatException ignored) { }
                long now = System.currentTimeMillis();
                long cd = (long) (pl.getConfig().getDouble("habitat.upgrades-settings.home-cooldown-seconds", 300) * 1000);
                if (!p.hasPermission("faultlinedeep.market")) {
                    long since = now - homeAt.getOrDefault(p.getUniqueId(), 0L);
                    if (since < cd) { p.sendMessage(ChatColor.RED + "The beacon is recharging: " + ((cd - since) / 1000 + 1) + " s."); return true; }
                    if (now - hurtAt.getOrDefault(p.getUniqueId(), 0L) < 10_000) { p.sendMessage(ChatColor.RED + "Not in the middle of a fight."); return true; }
                }
                Habitat.Dome d = homes.get(pick);
                homeAt.put(p.getUniqueId(), now);
                p.teleport(new Location(Bukkit.getWorld(d.world), d.x + 0.5, d.y, d.z + 0.5, p.getLocation().getYaw(), 0));
                p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.4f);
                if (homes.size() > 1) p.sendMessage(ChatColor.GRAY + "(" + homes.size() + " beacons: /dome home <1-" + homes.size() + ">)");
            }
            case "color", "colour" -> {
                Habitat.Dome d = domeAt(p.getLocation());
                if (d == null) { p.sendMessage(ChatColor.RED + "Stand in one of your domes."); return true; }
                if (!d.owner.equals(p.getUniqueId()) && !p.hasPermission("faultlinedeep.market")) { p.sendMessage(ChatColor.RED + "It isn't your dome."); return true; }
                if (!has(d, "tint")) { p.sendMessage(ChatColor.RED + "Install the Stained Shell first (/dome upgrades)."); return true; }
                String c = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
                if (!COLOURS.contains(c)) { p.sendMessage(ChatColor.YELLOW + "/dome color <" + String.join("|", COLOURS) + ">"); return true; }
                tint(d, c);
                p.sendActionBar(ChatColor.AQUA + "The glass turns " + c.replace('_', ' ') + ".");
            }
            default -> { return false; }
        }
        return true;
    }
}
