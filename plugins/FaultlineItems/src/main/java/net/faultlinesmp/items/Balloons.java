package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * HOT AIR BALLOONS (Items 1.2.24, config balloon:, balloons.yml). Craft one (5 wool around a campfire, string, a barrel),
 * right-click the ground to set it up, step into the basket and right-click to board. Flying: Jump = rise, Sneak = sink
 * (and get out once it's on the ground), W/A/S/D = move the way you look, Sprint = faster. The burner eats fuel while
 * it's in the air: coal / charcoal 60 s, a blaze rod 150 s, a coal block 600 s (right-click with it, in or beside the
 * basket); with no fuel it slowly sinks. A dye recolours the envelope. Owner: sneak + punch in the basket when it's down
 * to pack it up (fuel and colour stay in the item).
 * Built from block displays (envelope, band, crown, mouth, ropes, basket, burner) moved together each tick; the rider
 * sits on an invisible marker armor stand (Bedrock can ride those; FaultlineBosses' BedrockFx mirrors the chunky
 * envelope for Bedrock). Balloons are saved (balloons.yml) and respawned when their chunk loads; one whose rider logs
 * out comes down where it is, the rider set on the ground first.
 */
final class Balloons implements Listener, org.bukkit.command.CommandExecutor {

    static final String TAG = "faultline_balloon";

    final class Balloon {
        final UUID id;
        UUID owner;
        String color;
        double fuel;          // seconds
        Location at;          // the basket floor's centre
        ArmorStand seat;
        final List<BlockDisplay> parts = new ArrayList<>();
        Vector vel = new Vector();
        boolean landed = true;
        int puffs;

        Balloon(UUID id, UUID owner, String color, double fuel, Location at) {
            this.id = id; this.owner = owner; this.color = color; this.fuel = fuel; this.at = at.clone();
        }

        boolean spawned() { return seat != null && seat.isValid(); }
        Player rider() { return seat != null && !seat.getPassengers().isEmpty() && seat.getPassengers().get(0) instanceof Player p ? p : null; }
    }

    private final FaultlineItems plugin;
    private final File file;
    final NamespacedKey key, itemKey;
    final Map<UUID, Balloon> balloons = new HashMap<>();
    Function<Player, org.bukkit.Input> input = Player::getCurrentInput; // swapped out by tests
    private final java.util.Set<UUID> releasing = new java.util.HashSet<>();
    /** 1.2.36: hits on a balloon (id -> [hits, last hit ms]), and balloons taken down for an offline owner (given on join). */
    private final Map<UUID, long[]> hits = new HashMap<>();
    final Map<UUID, List<String>> returns = new HashMap<>();
    private int ticks;

    Balloons(FaultlineItems plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "balloons.yml");
        key = new NamespacedKey(plugin, "balloon");
        itemKey = new NamespacedKey(plugin, "balloon_item");
        load();
        ShapedRecipe r = new ShapedRecipe(new NamespacedKey(plugin, "hot_air_balloon"), item(null, 0));
        r.shape("WWW", "WFW", "SBS");
        r.setIngredient('W', new org.bukkit.inventory.RecipeChoice.MaterialChoice(org.bukkit.Tag.WOOL));
        r.setIngredient('F', Material.CAMPFIRE);
        r.setIngredient('S', Material.STRING);
        r.setIngredient('B', Material.BARREL);
        try { Bukkit.addRecipe(r); } catch (IllegalStateException ignored) { } // a /reload
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 600L, 600L);
        for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) wake(c);
    }

    double cfg(String k, double d) { return plugin.getConfig().getDouble("balloon." + k, d); }

    // ---------------------------------------------------------------- the item

    static final List<String> COLORS = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

    ItemStack item(String color, double fuel) {
        String c = color == null ? "red" : color;
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Hot Air Balloon");
        List<String> lore = new ArrayList<>(List.of(
                ChatColor.GRAY + "Right-click the ground to set it up,",
                ChatColor.GRAY + "step into the basket and right-click to fly.",
                ChatColor.YELLOW + "Jump" + ChatColor.GRAY + " rise · " + ChatColor.YELLOW + "Sneak" + ChatColor.GRAY + " sink / get out",
                ChatColor.YELLOW + "W A S D" + ChatColor.GRAY + " move · " + ChatColor.YELLOW + "Sprint" + ChatColor.GRAY + " faster",
                ChatColor.GRAY + "Burns coal, charcoal, blaze rods, coal blocks.",
                ChatColor.GRAY + "Hit it a few times (an axe is faster) to take it down.",
                ChatColor.DARK_GRAY + "Envelope: " + pretty(c) + (fuel > 0 ? " · fuel " + clock(fuel) : "")));
        m.setLore(lore);
        m.setItemModel(new NamespacedKey("faultline", "hot_air_balloon"));
        m.setMaxStackSize(1);
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, c + ";" + Math.max(0, (int) fuel));
        it.setItemMeta(m);
        return it;
    }

    boolean isItem(ItemStack it) {
        return it != null && it.getType() == Material.PAPER && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.STRING);
    }

    static String pretty(String c) { return c.replace('_', ' '); }

    static String clock(double secs) { int s = (int) Math.max(0, secs); return s / 60 + ":" + String.format("%02d", s % 60); }

    // ---------------------------------------------------------------- the model

    /** {block, scale x/y/z, offset of its min corner from the basket floor's centre}. */
    private Object[][] shape(String color) {
        Material wool = Material.matchMaterial(color.toUpperCase(Locale.ROOT) + "_WOOL");
        if (wool == null) wool = Material.RED_WOOL;
        Material band = color.equals("yellow") ? Material.ORANGE_WOOL : Material.YELLOW_WOOL;
        Material rope = Material.matchMaterial("IRON_CHAIN") != null ? Material.matchMaterial("IRON_CHAIN")
                : Material.matchMaterial("CHAIN") != null ? Material.matchMaterial("CHAIN") : Material.OAK_FENCE;
        return new Object[][]{
                {Material.SPRUCE_PLANKS, 2.0f, 0.15f, 2.0f, -1.0f, -0.15f, -1.0f},  // basket floor
                {Material.SPRUCE_PLANKS, 2.0f, 1.0f, 0.12f, -1.0f, 0.0f, -1.0f},    // its four walls
                {Material.SPRUCE_PLANKS, 2.0f, 1.0f, 0.12f, -1.0f, 0.0f, 0.88f},
                {Material.SPRUCE_PLANKS, 0.12f, 1.0f, 1.76f, -1.0f, 0.0f, -0.88f},
                {Material.SPRUCE_PLANKS, 0.12f, 1.0f, 1.76f, 0.88f, 0.0f, -0.88f},
                {Material.STRIPPED_SPRUCE_LOG, 2.1f, 0.12f, 2.1f, -1.05f, 1.0f, -1.05f}, // the rim
                {rope, 1f, 3.0f, 1f, -1.45f, 1.0f, -1.45f},                         // ropes up to the envelope
                {rope, 1f, 3.0f, 1f, 0.45f, 1.0f, -1.45f},
                {rope, 1f, 3.0f, 1f, -1.45f, 1.0f, 0.45f},
                {rope, 1f, 3.0f, 1f, 0.45f, 1.0f, 0.45f},
                {Material.IRON_BARS, 0.5f, 0.4f, 0.5f, -0.25f, 2.6f, -0.25f},      // the burner
                {wool, 2.2f, 0.8f, 2.2f, -1.1f, 3.6f, -1.1f},                       // the envelope: its mouth
                {wool, 4.2f, 1.6f, 4.2f, -2.1f, 4.4f, -2.1f},                       // lower bulge
                {band, 4.45f, 0.5f, 4.45f, -2.225f, 5.4f, -2.225f},                 // band
                {wool, 4.8f, 2.4f, 4.8f, -2.4f, 5.9f, -2.4f},                       // the widest part
                {wool, 3.8f, 1.2f, 3.8f, -1.9f, 8.3f, -1.9f},                       // the top
                {band, 2.2f, 0.6f, 2.2f, -1.1f, 9.5f, -1.1f},                       // crown
        };
    }

    void spawn(Balloon b) { spawn(b, false); }

    /** here = we know its chunk is loaded (a player just clicked there). */
    void spawn(Balloon b, boolean here) {
        if (b.spawned()) return;
        World w = b.at.getWorld();
        if (w == null || (!here && !w.isChunkLoaded(b.at.getBlockX() >> 4, b.at.getBlockZ() >> 4))) return;
        Location base = b.at.clone(); base.setYaw(0); base.setPitch(0);
        b.seat = w.spawn(base.clone().add(0, 0.05, 0), ArmorStand.class, a -> {
            a.setMarker(true); a.setInvisible(true); a.setGravity(false); a.setInvulnerable(true); a.setSilent(true);
            a.setPersistent(false); a.addScoreboardTag(TAG);
            a.getPersistentDataContainer().set(key, PersistentDataType.STRING, b.id.toString());
        });
        for (Object[] s : shape(b.color)) {
            BlockData data = ((Material) s[0]).createBlockData();
            Transformation tr = new Transformation(new Vector3f((float) s[4], (float) s[5], (float) s[6]), new Quaternionf(),
                    new Vector3f((float) s[1], (float) s[2], (float) s[3]), new Quaternionf());
            b.parts.add(w.spawn(base, BlockDisplay.class, d -> {
                d.setBlock(data); d.setTransformation(tr); d.setPersistent(false); d.setTeleportDuration(1);
                d.setViewRange(6f); d.addScoreboardTag(TAG);
                d.getPersistentDataContainer().set(key, PersistentDataType.STRING, b.id.toString());
            }));
        }
    }

    void despawn(Balloon b) {
        Player r = b.rider();
        if (r != null) { releasing.add(r.getUniqueId()); try { b.seat.removePassenger(r); } finally { releasing.remove(r.getUniqueId()); } }
        if (b.seat != null) b.seat.remove();
        for (BlockDisplay d : b.parts) d.remove();
        b.parts.clear();
        b.seat = null;
    }

    void recolor(Balloon b, String color) {
        b.color = color;
        if (!b.spawned()) return;
        Object[][] sh = shape(color);
        for (int i = 0; i < b.parts.size() && i < sh.length; i++) b.parts.get(i).setBlock(((Material) sh[i][0]).createBlockData());
    }

    // ---------------------------------------------------------------- placing, boarding, fuel, colour, packing up

    /** The balloon whose basket this spot is in (within the 2x2 floor, up to 2 blocks above it), or null. */
    Balloon basketAt(Location l) {
        for (Balloon b : balloons.values()) {
            if (!b.spawned() || !b.at.getWorld().equals(l.getWorld())) continue;
            double dx = l.getX() - b.at.getX(), dz = l.getZ() - b.at.getZ(), dy = l.getY() - b.at.getY();
            if (Math.abs(dx) <= 1.3 && Math.abs(dz) <= 1.3 && dy > -1.2 && dy < 2.2) return b;
        }
        return null;
    }

    static double fuelOf(Material m) {
        return switch (m) {
            case COAL, CHARCOAL -> 60;
            case BLAZE_ROD -> 150;
            case COAL_BLOCK -> 600;
            default -> 0;
        };
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack hand = e.getItem();
        // set one up
        if (isItem(hand) && e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
            e.setCancelled(true);
            Block on = e.getClickedBlock().getRelative(e.getBlockFace());
            Location at = on.getLocation().add(0.5, 0, 0.5);
            String why = room(at);
            if (why != null) { p.sendMessage(ChatColor.RED + why); return; }
            String[] v = hand.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING).split(";");
            double fuel = 0; try { fuel = Double.parseDouble(v.length > 1 ? v[1] : "0"); } catch (NumberFormatException ignored) { }
            Balloon b = new Balloon(UUID.randomUUID(), p.getUniqueId(), v[0], fuel, at);
            balloons.put(b.id, b);
            spawn(b, true);
            if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
            save();
            p.playSound(at, Sound.BLOCK_WOOL_PLACE, 1f, 0.8f);
            p.sendMessage(ChatColor.GOLD + "Hot Air Balloon set up." + ChatColor.GRAY + (fuel <= 0 ? " Right-click it with coal to fuel the burner, then" : "")
                    + " step into the basket and right-click to fly.");
            return;
        }
        Balloon b = p.getVehicle() != null ? byEntity(p.getVehicle()) : basketAt(p.getLocation());
        if (b == null) return;
        // pack it up: the owner (or an admin), sneak + punch, on the ground, nobody in it
        if ((e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.LEFT_CLICK_BLOCK) && p.isSneaking() && p.getVehicle() == null) {
            if (!p.getUniqueId().equals(b.owner) && !p.hasPermission("items.balloon.admin")) { p.sendMessage(ChatColor.RED + "It isn't your balloon."); return; }
            if (!b.landed || b.rider() != null) return;
            e.setCancelled(true);
            pack(b, p);
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (hand != null && fuelOf(hand.getType()) > 0) {
            e.setCancelled(true);
            double max = cfg("max-fuel-seconds", 1800);
            if (b.fuel >= max - 1) { p.sendMessage(ChatColor.GRAY + "The burner is full (" + clock(b.fuel) + ")."); return; }
            b.fuel = Math.min(max, b.fuel + fuelOf(hand.getType()));
            hand.setAmount(hand.getAmount() - 1);
            p.playSound(b.at, Sound.ITEM_FIRECHARGE_USE, 0.6f, 1.4f);
            p.sendActionBar(ChatColor.GOLD + "🔥 Fuel " + clock(b.fuel));
            return;
        }
        if (hand != null && hand.getType().name().endsWith("_DYE") && p.getVehicle() == null) {
            String c = hand.getType().name().replace("_DYE", "").toLowerCase(Locale.ROOT);
            if (!COLORS.contains(c) || c.equals(b.color)) return;
            e.setCancelled(true);
            recolor(b, c);
            if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
            p.playSound(b.at, Sound.ITEM_DYE_USE, 1f, 1f);
            save();
            return;
        }
        if (p.getVehicle() == null && b.rider() == null && b.spawned()) {
            e.setCancelled(true);
            b.seat.addPassenger(p);
            p.sendMessage(ChatColor.GOLD + "🎈 " + ChatColor.YELLOW + "Jump" + ChatColor.GRAY + " to rise, " + ChatColor.YELLOW + "Sneak" + ChatColor.GRAY + " to sink (and get out on the ground), "
                    + ChatColor.YELLOW + "W A S D" + ChatColor.GRAY + " to move, " + ChatColor.YELLOW + "Sprint" + ChatColor.GRAY + " for speed. Fuel: " + clock(b.fuel));
        }
    }

    /** Why a balloon can't be set up here (needs open air for the envelope), or null. */
    String room(Location at) {
        World w = at.getWorld();
        if (at.getY() + 11 > w.getMaxHeight()) return "Too high up for a balloon.";
        for (int dx = -1; dx <= 0; dx++) for (int dz = -1; dz <= 0; dz++) for (int dy = 0; dy < 3; dy++)
            if (at.clone().add(dx + 0.5, dy, dz + 0.5).getBlock().getType().isSolid()) return "Not enough room for the basket here.";
        for (int dx = -2; dx <= 1; dx++) for (int dz = -2; dz <= 1; dz++) for (int dy = 4; dy < 10; dy++)
            if (at.clone().add(dx + 0.5, dy, dz + 0.5).getBlock().getType().isSolid()) return "Not enough room above for the envelope (it needs open sky around it).";
        return null;
    }

    Balloon byEntity(Entity e) {
        String v = e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (v == null) return null;
        try { return balloons.get(UUID.fromString(v)); } catch (IllegalArgumentException ex) { return null; }
    }

    void pack(Balloon b, Player p) {
        despawn(b);
        balloons.remove(b.id);
        hits.remove(b.id);
        save();
        ItemStack it = item(b.color, b.fuel);
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 0.7f);
        p.sendMessage(ChatColor.GRAY + "You packed up your balloon.");
    }

    // ---------------------------------------------------------------- breaking it (1.2.36)

    /**
     * Swinging at a landed balloon (any item, or a fist) knocks it down after a few hits, like a boat: an axe counts double.
     * It drops as its item (colour and fuel kept). The owner and admins can (balloon.anyone-can-break: anyone). The model
     * is display entities, which can't be hit, so the swing is traced against the basket and the envelope.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(org.bukkit.event.player.PlayerAnimationEvent e) {
        if (e.getAnimationType() != org.bukkit.event.player.PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        if (p.getVehicle() != null || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
        if (p.isSneaking() && basketAt(p.getLocation()) != null) return; // that's the sneak + punch pack-up
        Balloon b = aimed(p);
        if (b == null) return;
        if (!mayBreak(p, b)) { p.sendActionBar(ChatColor.RED + "It isn't your balloon."); return; }
        if (b.rider() != null && !b.landed) { p.sendActionBar(ChatColor.RED + "Someone's flying it."); return; }
        long now = System.currentTimeMillis();
        long[] h = hits.computeIfAbsent(b.id, k -> new long[2]);
        if (now - h[1] > 3000) h[0] = 0; // like a boat: the hits have to come close together
        if (now - h[1] < 200) return;    // one swing, one hit
        h[1] = now;
        h[0] += p.getInventory().getItemInMainHand().getType().name().endsWith("_AXE") ? 2 : 1;
        int need = (int) cfg("hits-to-break", 4);
        b.at.getWorld().spawnParticle(Particle.BLOCK, b.at.clone().add(0, 1, 0), 12, 0.6, 0.4, 0.6, 0, Material.BARREL.createBlockData());
        b.at.getWorld().playSound(b.at, Sound.BLOCK_WOOD_HIT, 1f, 0.8f);
        if (h[0] < need) { p.sendActionBar(ChatColor.GOLD + "🎈 " + "▮".repeat((int) h[0]) + ChatColor.DARK_GRAY + "▮".repeat(Math.max(0, need - (int) h[0]))); return; }
        hits.remove(b.id);
        takeDown(b, p, true);
    }

    boolean mayBreak(Player p, Balloon b) {
        return p.getUniqueId().equals(b.owner) || p.hasPermission("items.balloon.admin") || plugin.getConfig().getBoolean("balloon.anyone-can-break", false);
    }

    /** The balloon the player is looking at within reach (basket or envelope), nearer than any block in the way, or null. */
    Balloon aimed(Player p) {
        Location eye = p.getEyeLocation();
        Vector o = eye.toVector(), d = eye.getDirection();
        double reach = cfg("break-reach", 5);
        double wall = reach;
        try {
            var r = p.rayTraceBlocks(reach);
            if (r != null) wall = r.getHitPosition().distance(o);
        } catch (RuntimeException ignored) { }
        Balloon best = null;
        double bestT = wall;
        for (Balloon b : balloons.values()) {
            if (!b.spawned() || !b.at.getWorld().equals(eye.getWorld()) || b.at.distanceSquared(eye) > 400) continue;
            Vector c = b.at.toVector();
            double t = Math.min(hit(o, d, c.clone().add(new Vector(-1.1, 0, -1.1)), c.clone().add(new Vector(1.1, 3.6, 1.1))),
                    hit(o, d, c.clone().add(new Vector(-2.4, 3.5, -2.4)), c.clone().add(new Vector(2.4, 10.2, 2.4))));
            if (t <= bestT) { bestT = t; best = b; }
        }
        return best;
    }

    /** Distance along the ray to the box, or infinity. */
    static double hit(Vector o, Vector d, Vector lo, Vector hi) {
        double t0 = 0, t1 = Double.MAX_VALUE;
        double[] os = {o.getX(), o.getY(), o.getZ()}, ds = {d.getX(), d.getY(), d.getZ()}, l = {lo.getX(), lo.getY(), lo.getZ()}, h = {hi.getX(), hi.getY(), hi.getZ()};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(ds[i]) < 1e-9) { if (os[i] < l[i] || os[i] > h[i]) return Double.POSITIVE_INFINITY; continue; }
            double a = (l[i] - os[i]) / ds[i], b = (h[i] - os[i]) / ds[i];
            if (a > b) { double t = a; a = b; b = t; }
            t0 = Math.max(t0, a); t1 = Math.min(t1, b);
            if (t0 > t1) return Double.POSITIVE_INFINITY;
        }
        return t0;
    }

    /** Removes a balloon: anyone in it is set down; its item drops where it stood (knocked down) or goes back to its owner (a command). */
    void takeDown(Balloon b, Player by, boolean drop) {
        Player r = b.rider();
        if (r != null) {
            Location down = b.landed ? b.at : ground(b.at);
            releasing.add(r.getUniqueId());
            try { b.seat.removePassenger(r); } finally { releasing.remove(r.getUniqueId()); }
            r.teleport(down.clone().add(0, 0.1, 0)); r.setFallDistance(0);
            if (!b.landed) b.at = down;
        }
        Location at = b.at.clone().add(0, 0.5, 0);
        boolean loaded = b.spawned();
        despawn(b);
        balloons.remove(b.id);
        hits.remove(b.id);
        ItemStack it = item(b.color, b.fuel);
        if (drop && loaded && at.getWorld() != null) {
            at.getWorld().dropItemNaturally(at, it);
            at.getWorld().playSound(at, Sound.ENTITY_ITEM_FRAME_BREAK, 1f, 0.7f);
        } else giveBack(b.owner, it);
        save();
        if (by != null) by.sendMessage(ChatColor.GRAY + "Balloon taken down" + (by.getUniqueId().equals(b.owner) ? "." : " (" + ownerName(b) + "'s)."));
    }

    /** The item goes to the owner: now if they're online, else on their next join. */
    void giveBack(UUID owner, ItemStack it) {
        Player o = Bukkit.getPlayer(owner);
        if (o != null) {
            for (ItemStack left : o.getInventory().addItem(it).values()) o.getWorld().dropItemNaturally(o.getLocation(), left);
            o.sendMessage(ChatColor.GOLD + "Your hot air balloon was taken down and put back in your inventory.");
            return;
        }
        String[] v = it.getItemMeta().getPersistentDataContainer().getOrDefault(itemKey, PersistentDataType.STRING, "red;0").split(";");
        returns.computeIfAbsent(owner, k -> new ArrayList<>()).add(v[0] + ";" + (v.length > 1 ? v[1] : "0"));
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        List<String> back = returns.remove(e.getPlayer().getUniqueId());
        if (back == null) return;
        for (String s : back) {
            String[] v = s.split(";");
            double fuel = 0; try { fuel = Double.parseDouble(v.length > 1 ? v[1] : "0"); } catch (NumberFormatException ignored) { }
            giveBack(e.getPlayer().getUniqueId(), item(v[0], fuel));
        }
        save();
    }

    String ownerName(Balloon b) {
        String n = Bukkit.getOfflinePlayer(b.owner).getName();
        return n == null ? b.owner.toString().substring(0, 8) : n;
    }

    /** The balloons in a stable order, for /balloon list numbers. */
    List<Balloon> listed() {
        List<Balloon> l = new ArrayList<>(balloons.values());
        l.sort(java.util.Comparator.comparing((Balloon b) -> b.id.toString()));
        return l;
    }

    // /balloon [list | remove [#n | all [player]]]
    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        boolean admin = sender.hasPermission("items.balloon.admin");
        Player p = sender instanceof Player pl ? pl : null;
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "list";
        List<Balloon> all = listed();
        switch (sub) {
            case "list" -> {
                List<Balloon> show = new ArrayList<>();
                for (Balloon b : all) if (admin || (p != null && b.owner.equals(p.getUniqueId()))) show.add(b);
                if (show.isEmpty()) { sender.sendMessage(ChatColor.GRAY + (admin ? "There are no hot air balloons set up." : "You have no hot air balloons set up.")); return true; }
                sender.sendMessage(ChatColor.GOLD + "Hot air balloons (" + show.size() + "):");
                for (Balloon b : show) sender.sendMessage(ChatColor.YELLOW + "#" + (all.indexOf(b) + 1) + ChatColor.GRAY + " " + ownerName(b) + " · " + b.at.getWorld().getName()
                        + " " + b.at.getBlockX() + " " + b.at.getBlockY() + " " + b.at.getBlockZ() + (b.rider() != null ? ChatColor.AQUA + " (flying: " + b.rider().getName() + ")" : ""));
                sender.sendMessage(ChatColor.GRAY + "/balloon remove [#n" + (admin ? " | all [player]" : "") + "] takes one down (no number = the nearest of yours within 16 blocks).");
                return true;
            }
            case "remove", "despawn", "delete" -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("all")) {
                    if (!admin) { sender.sendMessage(ChatColor.RED + "Only admins can take down every balloon."); return true; }
                    UUID only = null;
                    if (args.length > 2) {
                        var op = Bukkit.getOfflinePlayer(args[2]);
                        if (op.getName() == null && !op.hasPlayedBefore()) { sender.sendMessage(ChatColor.RED + "No player " + args[2] + "."); return true; }
                        only = op.getUniqueId();
                    }
                    int n = 0;
                    for (Balloon b : all) if (only == null || b.owner.equals(only)) { takeDown(b, null, false); n++; }
                    sender.sendMessage(ChatColor.GREEN + "Took down " + n + " balloon" + (n == 1 ? "" : "s") + ". Each one went back to its owner (on their next join if they're offline).");
                    return true;
                }
                Balloon target = null;
                if (args.length > 1) {
                    try { int i = Integer.parseInt(args[1].replace("#", "")) - 1; if (i >= 0 && i < all.size()) target = all.get(i); } catch (NumberFormatException ignored) { }
                    if (target == null) { sender.sendMessage(ChatColor.RED + "No balloon " + args[1] + ". /balloon list shows the numbers."); return true; }
                } else if (p != null) {
                    double best = 16 * 16;
                    for (Balloon b : all) {
                        if (!b.at.getWorld().equals(p.getWorld()) || (!admin && !b.owner.equals(p.getUniqueId()))) continue;
                        double d = b.at.distanceSquared(p.getLocation());
                        if (d <= best) { best = d; target = b; }
                    }
                    if (target == null) { sender.sendMessage(ChatColor.RED + "No balloon of yours within 16 blocks. /balloon list shows them all."); return true; }
                } else { sender.sendMessage("Usage: /balloon remove <#n|all [player]>"); return true; }
                if (!admin && (p == null || !target.owner.equals(p.getUniqueId()))) { sender.sendMessage(ChatColor.RED + "It isn't your balloon."); return true; }
                boolean here = target.spawned() && p != null && target.at.getWorld().equals(p.getWorld()) && target.at.distanceSquared(p.getLocation()) < 64 * 64;
                if (here && target.owner.equals(p.getUniqueId())) { pack(target, p); return true; } // your own, next to you: straight into your inventory
                takeDown(target, p, false);
                return true;
            }
            default -> {
                sender.sendMessage(ChatColor.GOLD + "/balloon list" + ChatColor.GRAY + " · your balloons (admins: all of them)");
                sender.sendMessage(ChatColor.GOLD + "/balloon remove [#n]" + ChatColor.GRAY + " · take one down" + (admin ? ChatColor.GOLD + "  /balloon remove all [player]" : ""));
                return true;
            }
        }
    }

    // ---------------------------------------------------------------- flying

    private boolean solid(Location l) {
        Block bl = l.getBlock();
        return bl.getType().isSolid();
    }

    /** Would the balloon hit something with its basket floor at to? (the basket corners, and the envelope's edge and top) */
    boolean blocked(Location to) {
        double[][] pts = {{-0.9, 0.05, -0.9}, {0.9, 0.05, -0.9}, {-0.9, 0.05, 0.9}, {0.9, 0.05, 0.9}, {0, 0.9, 0},
                {-2.2, 6.5, 0}, {2.2, 6.5, 0}, {0, 6.5, -2.2}, {0, 6.5, 2.2}, {0, 9.9, 0}, {0, 4.0, 0}};
        for (double[] q : pts) if (solid(to.clone().add(q[0], q[1], q[2]))) return true;
        return false;
    }

    private void tick() {
        ticks++;
        for (Balloon b : new ArrayList<>(balloons.values())) {
            if (!b.spawned()) continue;
            try { fly(b); } catch (RuntimeException ex) { plugin.getLogger().warning("Balloon " + b.id + ": " + ex); }
        }
    }

    void fly(Balloon b) {
        Player r = b.rider();
        World w = b.at.getWorld();
        double maxH = cfg("speed", 0.35), up = cfg("climb-speed", 0.22);
        Vector want = new Vector();
        boolean burning = false;
        if (r != null) {
            org.bukkit.Input in = input.apply(r);
            Vector look = r.getLocation().getDirection().setY(0);
            if (look.lengthSquared() < 1e-6) look = new Vector(0, 0, 1);
            look.normalize();
            Vector side = new Vector(-look.getZ(), 0, look.getX());
            double sp = maxH * (in.isSprint() ? 1.6 : 1.0);
            if (in.isForward()) want.add(look.clone().multiply(sp));
            if (in.isBackward()) want.add(look.clone().multiply(-sp * 0.6));
            if (in.isLeft()) want.add(side.clone().multiply(-sp * 0.7));
            if (in.isRight()) want.add(side.clone().multiply(sp * 0.7));
            if (b.landed && want.lengthSquared() > 0 && !in.isJump()) want.zero(); // no driving along the ground
            if (in.isJump() && b.fuel > 0) { want.setY(up); burning = true; }
            else if (in.isSneak()) want.setY(-up);
            else if (!b.landed) want.setY(b.fuel > 0 ? 0 : -cfg("sink-speed", 0.06));
            if (!b.landed) burning |= b.fuel > 0;
            if (in.isSprint() && want.lengthSquared() > 0) burning = burning || b.fuel > 0;
        } else if (!b.landed) {
            want.setY(-cfg("sink-speed", 0.06) * 2); // nobody aboard: it comes down
        }
        // ease toward what's wanted
        b.vel.add(want.clone().subtract(b.vel).multiply(0.12));
        if (b.vel.lengthSquared() < 1e-6 && b.landed) return;
        Location to = b.at.clone();
        // one axis at a time: slide along walls, settle on the ground
        Location tx = to.clone().add(b.vel.getX(), 0, 0);
        if (!blocked(tx)) to = tx; else b.vel.setX(0);
        Location tz = to.clone().add(0, 0, b.vel.getZ());
        if (!blocked(tz)) to = tz; else b.vel.setZ(0);
        Location ty = to.clone().add(0, b.vel.getY(), 0);
        if (ty.getY() + 10.5 > w.getMaxHeight()) { b.vel.setY(Math.min(0, b.vel.getY())); ty = to; }
        if (!blocked(ty)) { to = ty; b.landed = false; }
        else {
            if (b.vel.getY() < 0) { // touched down
                if (!b.landed) { w.playSound(to, Sound.BLOCK_WOOL_FALL, 1f, 0.7f); if (r != null) r.sendActionBar(ChatColor.GRAY + "Landed. Sneak to get out."); }
                b.landed = true;
                to.setY(Math.floor(to.getY() + 0.05) + 0.0001);
            }
            b.vel.setY(0);
        }
        if (burning) {
            b.fuel = Math.max(0, b.fuel - 0.05);
            if (ticks % 3 == 0) w.spawnParticle(Particle.FLAME, to.clone().add(0, 3.0, 0), 3, 0.1, 0.15, 0.1, 0.01);
            if (ticks % 40 == 0) w.playSound(to, Sound.ENTITY_BLAZE_BURN, SoundCategory.PLAYERS, 0.5f, 0.6f);
            if (b.fuel <= 0 && r != null) r.sendMessage(ChatColor.RED + "The burner's out of fuel: you're sinking. Right-click with coal to refuel.");
        }
        move(b, to);
        if (r != null && ticks % 10 == 0) {
            int ground = w.getHighestBlockYAt(to.getBlockX(), to.getBlockZ());
            r.sendActionBar((b.fuel > 0 ? ChatColor.GOLD + "🔥 " + clock(b.fuel) : ChatColor.RED + "🔥 no fuel") + ChatColor.GRAY + "  ·  Height "
                    + ChatColor.WHITE + Math.max(0, to.getBlockY() - ground) + ChatColor.GRAY + "  ·  Y " + to.getBlockY());
        }
    }

    void move(Balloon b, Location to) {
        b.at = to.clone();
        Location base = to.clone(); base.setYaw(0); base.setPitch(0);
        if (b.seat != null) {
            Location s = base.clone().add(0, 0.05, 0);
            if (b.rider() != null) s.setYaw(b.rider().getLocation().getYaw());
            b.seat.teleport(s, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }
        for (BlockDisplay d : b.parts) d.teleport(base);
    }

    /** In the air the basket holds you: sneaking sinks instead. On the ground, sneak gets you out. */
    @EventHandler(ignoreCancelled = true)
    public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p) || releasing.contains(p.getUniqueId())) return;
        Balloon b = byEntity(e.getDismounted());
        if (b == null) return;
        if (!b.landed) { e.setCancelled(true); return; }
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) p.teleport(b.at.clone().add(0, 0.1, 0).setDirection(p.getLocation().getDirection())); });
    }

    /** The seat and the model can't be hurt (TNT, lava, a sword). */
    @EventHandler(ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        if (e.getEntity().getScoreboardTags().contains(TAG)) e.setCancelled(true);
        // riding in it: no fall damage from a hard landing
        if (e.getEntity() instanceof Player p && e.getCause() == EntityDamageEvent.DamageCause.FALL && p.getVehicle() != null && byEntity(p.getVehicle()) != null) e.setCancelled(true);
    }

    /** Logging out mid-air: set the rider on the ground below, the balloon comes down there too. */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Balloon b = p.getVehicle() != null ? byEntity(p.getVehicle()) : null;
        if (b == null) return;
        Location down = ground(b.at);
        releasing.add(p.getUniqueId());
        try { b.seat.removePassenger(p); } finally { releasing.remove(p.getUniqueId()); }
        p.teleport(down.clone().add(0, 0.1, 0));
        p.setFallDistance(0);
        b.vel.zero();
        move(b, down);
        b.landed = true;
        save();
    }

    /** The ground (or water) under a spot, for the basket floor. */
    Location ground(Location l) {
        World w = l.getWorld();
        Location g = l.clone();
        for (int y = l.getBlockY(); y > w.getMinHeight(); y--) {
            g.setY(y);
            Block below = w.getBlockAt(l.getBlockX(), y - 1, l.getBlockZ());
            if (below.getType().isSolid() || below.isLiquid()) { g.setY(y); return g; }
        }
        return l.clone();
    }

    // ---------------------------------------------------------------- saving, chunks

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) { wake(e.getChunk()); }

    void wake(Chunk c) {
        for (Balloon b : balloons.values()) {
            if (b.spawned() || !b.at.getWorld().equals(c.getWorld())) continue;
            if ((b.at.getBlockX() >> 4) == c.getX() && (b.at.getBlockZ() >> 4) == c.getZ()) {
                if (!b.landed || blocked(b.at)) { b.at = ground(b.at); b.landed = true; }
                spawn(b);
            }
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        for (Balloon b : balloons.values()) {
            if (!b.spawned() || !b.at.getWorld().equals(e.getWorld())) continue;
            if ((b.at.getBlockX() >> 4) == e.getChunk().getX() && (b.at.getBlockZ() >> 4) == e.getChunk().getZ()) despawn(b);
        }
    }

    void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection rs = y.getConfigurationSection("returns");
        if (rs != null) for (String k : rs.getKeys(false)) try { returns.put(UUID.fromString(k), new ArrayList<>(rs.getStringList(k))); } catch (IllegalArgumentException ignored) { }
        ConfigurationSection s = y.getConfigurationSection("balloons");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try {
                Location at = s.getLocation(k + ".at");
                if (at == null || at.getWorld() == null) continue;
                Balloon b = new Balloon(UUID.fromString(k), UUID.fromString(s.getString(k + ".owner")), s.getString(k + ".color", "red"),
                        s.getDouble(k + ".fuel"), at);
                b.landed = s.getBoolean(k + ".landed", true);
                balloons.put(b.id, b);
            } catch (RuntimeException ignored) { }
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Balloon b : balloons.values()) {
            String k = "balloons." + b.id;
            y.set(k + ".owner", b.owner.toString());
            y.set(k + ".color", b.color);
            y.set(k + ".fuel", Math.round(b.fuel * 10) / 10.0);
            y.set(k + ".at", b.at);
            y.set(k + ".landed", b.landed);
        }
        for (var r : returns.entrySet()) y.set("returns." + r.getKey(), r.getValue());
        try { y.save(file); } catch (IOException e) { plugin.getLogger().warning("Couldn't save balloons.yml: " + e.getMessage()); }
    }

    /** Server stopping: everyone aboard is set on the ground below first, then everything is saved. */
    void shutdown() {
        for (Balloon b : balloons.values()) {
            Player r = b.rider();
            if (r != null && !b.landed) {
                Location down = ground(b.at);
                releasing.add(r.getUniqueId());
                try { b.seat.removePassenger(r); } finally { releasing.remove(r.getUniqueId()); }
                r.teleport(down.clone().add(0, 0.1, 0)); r.setFallDistance(0);
                b.at = down; b.landed = true;
            }
            despawn(b);
        }
        save();
    }
}
