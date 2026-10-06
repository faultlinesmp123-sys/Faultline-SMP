package net.faultlinesmp.ships;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockState;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One ship in the world: built from block displays riding an invisible root entity, so moving the ship is one
 * teleport (since 1.21.10 a teleport keeps the passengers on). Turning re-sends each block's transformation (interpolated). Players ride seat entities.
 *
 * States: building (a glowing outline being filled in), anchored (snapped to the block grid with barrier blocks
 * in every cell, so the crew can walk the deck), afloat (sailing; everyone aboard sits), wrecked (health hit 0:
 * can't sail until a Shipwright's Hammer brings it back to full).
 */
final class Ship {
    static final String TAG = "faultline_ship";
    static final float GHOST = 0.45f;
    static final String CANNON_DATA = "faultline:cannon";
    /** Local axes -> display axes (bow = display +z at yaw 0); the entity's own yaw turns the whole ship as one piece. */
    static final Quaternionf Q0 = new Quaternionf().rotationY((float) Math.toRadians(-90));
    /** Item displays draw items turned half way round. */
    static final Quaternionf FLIP = new Quaternionf().rotationY((float) Math.PI);

    final FaultlineShips pl;
    final UUID id;
    final ShipType type;
    final UUID owner;
    String world;
    double x, y, z;  // the root: local origin, y = the top water block
    float yaw;       // Minecraft yaw: 0 = bow to the south
    double hp;
    boolean building, anchored, wrecked;
    boolean prebuilt;              // an admin's ready-made ship: scrapping it gives the built item back, not its blocks
    final String[] blocks;                      // block data placed in each cell (null = still a ghost)
    String name;                                // optional, set with /ship name
    ItemStack banner;                           // flown from the tallest mast
    final List<String> barriers = new ArrayList<>(); // "x,y,z|original block data" for every barrier we put down
    ItemStack[] cargo;

    // ---- runtime ----
    ItemDisplay root;
    Display[] displays;  // block displays; item displays for cannons
    ItemDisplay flag;
    ArmorStand flagStand; // Bedrock only: wears the banner
    ArmorStand[] seats;  // armor stands, not displays: Bedrock (Geyser) has no display entities to sit on
    ArmorStand stand;    // Bedrock only: wears an item whose Bedrock model is the whole ship
    boolean reconciled;
    final List<Interaction> hitboxes = new ArrayList<>();
    TextDisplay label;
    BossBar bar;
    Inventory hold;
    double speed, turnRate;
    float sentYaw;
    long ticks;
    int anchorStep = -1;
    float anchorYaw0, anchorYaw1;
    double anchorX0, anchorZ0, anchorX1, anchorZ1;
    boolean prevJump, prevForward, prevBack, braking, anchorQueued, allStop;
    // someone wants off while it isn't anchored (no deck to stand on yet): it anchors first, then lets them off
    final Set<UUID> gettingOff = new HashSet<>();
    boolean anchorForLeave, releasing;
    int leaveTries;
    // ---- damage you can see ----
    final Set<Integer> broken = new HashSet<>();  // cells knocked out by crashes, cannonballs, blasts: holes until patched
    double breakAcc;                               // damage not yet turned into a broken block
    double sink;                                   // how far a wreck has gone down
    Block lastBlocked;                             // what stopped the ship last (where it crashed)
    long lastBlockedTick = -999;
    Pirates.Brain ai;                              // a skeleton ship: steered by the plugin, crewed by skeletons, never saved
    Ship lastBlockShip;                            // ...if that was another ship
    final java.util.Map<Integer, Long> cannonReady = new java.util.HashMap<>();
    long lastRam, lastHurtFx;
    int filled;

    Ship(FaultlineShips pl, UUID id, ShipType type, UUID owner) {
        this.pl = pl; this.id = id; this.type = type; this.owner = owner;
        blocks = new String[type.cells.size()];
        hp = type.maxHp;
    }

    // =====================================================================================================
    //  geometry
    // =====================================================================================================
    World world() { return Bukkit.getWorld(world); }
    String title() { return name != null ? name : type.title; }
    boolean sailing() { return !building && !anchored && anchorStep < 0; }
    double maxHp() {
        if (ai != null) return type.maxHp * pl.getConfig().getDouble("pirates.health-multiplier", 0.5);
        return type.maxHp * pl.getConfig().getDouble("ships." + type.size + ".health-multiplier", 1.0);
    }

    static double fx(float yaw) { return -Math.sin(Math.toRadians(yaw)); }
    static double fz(float yaw) { return Math.cos(Math.toRadians(yaw)); }

    /** World point of a local point for a given pose. */
    static double[] toWorld(double px, double pz, float yaw, double lx, double lz) {
        double fx = fx(yaw), fz = fz(yaw);
        return new double[]{px + lx * fx - lz * fz, pz + lx * fz + lz * fx}; // right = (-fz, fx)
    }

    /** The cell of the ship at a world point, or -1. */
    int cellAt(double wx, double wy, double wz) {
        double dx = wx - x, dz = wz - z, fx = fx(yaw), fz = fz(yaw);
        double lx = dx * fx + dz * fz, lz = -dx * fz + dz * fx;
        return type.cellAt((int) Math.round(lx), (int) Math.floor(wy - y), (int) Math.round(lz));
    }

    int cellAt(Block b) { return world != null && b.getWorld().getName().equals(world) ? cellAt(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5) : -1; }

    Block blockOf(int i) { return blockOf(i, x, z, yaw); }

    Block blockOf(int i, double px, double pz, float pyaw) {
        ShipType.Cell c = type.cells.get(i);
        double[] w = toWorld(px, pz, pyaw, c.x(), c.z());
        return world().getBlockAt((int) Math.floor(w[0]), (int) Math.floor(y + c.y() + 0.5), (int) Math.floor(w[1]));
    }

    Location center() { return new Location(world(), x, y + type.deckY + 1, z); }
    double radius() { return Math.max(-type.minX, type.maxX + 4) + 1; }

    Location seatLoc(int i) {
        double[] s = type.seats[i];
        double[] w = toWorld(x, z, yaw, s[0], s[2]);
        return new Location(world(), w[0], y + s[1] + 0.05 + visualY(), w[1], yaw, 0);
    }

    /** A wreck sinks this far: a player's ship settles with its deck awash (it can be repaired and refloats). */
    double sinkTarget() { return ai != null ? type.height + 4 : type.deckY + 1.4; } // skeleton ships go all the way down

    float pitch() { return wrecked || sink > 0 ? (float) (-7 * Math.min(1, sink / Math.max(0.5, sinkTarget()))) : 0f; }

    double visualY() {
        if (wrecked || sink > 0) return -sink;
        if (sailing()) return Math.sin(ticks * 0.08) * 0.06 + Math.sin(ticks * 0.031) * 0.04; // a gentle swell
        return 0;
    }

    /** Water for the bottom, open air above; deep ships also need water under the keel. all = every cell. */
    boolean clear(double px, double pz, float pyaw, boolean all, Set<Block> bad) {
        World w = world();
        if (w == null) return false;
        List<Integer> check = all ? null : type.probes;
        int n = all ? type.cells.size() : check.size();
        boolean ok = true;
        // sailing at an angle a block pokes into its neighbours: check the middle of each side too, not just the centre
        double[][] spots = all ? new double[][]{{0, 0}} : new double[][]{{0, 0}, {0.49, 0}, {-0.49, 0}, {0, 0.49}, {0, -0.49}};
        for (int k = 0; k < n; k++) {
            int i = all ? k : check.get(k);
            if (type.loose.contains(i)) continue; // ladders may brush the bank
            ShipType.Cell c = type.cells.get(i);
            for (double[] o : spots) {
                double[] wp = toWorld(px, pz, pyaw, c.x() + o[0], c.z() + o[1]);
                Block b = w.getBlockAt((int) Math.floor(wp[0]), (int) Math.floor(y + c.y() + 0.5), (int) Math.floor(wp[1]));
                if (!w.isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) { ok = false; if (bad == null) return false; continue; }
                boolean fine = c.y() == 0 ? water(b) : air(b);
                if (fine && c.y() == 0 && type.deep && c.z() == 0 && !water(b.getRelative(0, -1, 0))) fine = false;
                Ship other = fine ? pl.shipCellAt(b, this) : null;
                if (other != null) fine = false; // another ship
                if (!fine) { lastBlocked = b; lastBlockShip = other; lastBlockedTick = ticks; ok = false; if (bad == null) return false; bad.add(b); }
            }
        }
        return ok;
    }

    static boolean water(Block b) {
        Material m = b.getType();
        if (m == Material.WATER || m == Material.BUBBLE_COLUMN || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS
                || m == Material.KELP || m == Material.KELP_PLANT) return true;
        return b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged() && !m.isSolid();
    }

    static boolean air(Block b) {
        Material m = b.getType();
        return m.isAir() || (b.isPassable() && m != Material.WATER && m != Material.LAVA && m != Material.BUBBLE_COLUMN
                && !(b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged()));
    }

    // =====================================================================================================
    //  entities
    // =====================================================================================================
    boolean spawned() { return root != null && root.isValid(); }

    void spawn() {
        despawn();
        World w = world();
        if (w == null) return;
        Location base = new Location(w, x, y + visualY(), z, yaw, 0);
        root = w.spawn(base, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setTeleportDuration(3); // same smoothing as the armor stands the crew sit on
        });
        // The blocks go in a batch a tick (FaultlineShips.tick shares out spawn-blocks-per-tick between ships):
        // a fleet of 16 ships is ~3700 displays, and spawning them all in one tick froze clients for seconds.
        displays = new Display[type.cells.size()];
        spawnCursor = 0;
        sentYaw = yaw;
        spawnFlag();
        if (!building) { spawnSeats(); spawnStand(); }
        if (!reconciled) { reconciled = true; reconcileBarriers(); }
        if (sailing()) spawnHitboxes();
        refreshLabel();
    }

    int spawnCursor = -1; // >= 0 while the block displays are still going in

    boolean spawningBlocks() { return spawnCursor >= 0 && displays != null; }

    /** Spawn up to n more block displays; returns how many it spawned. */
    int spawnMore(int n) {
        if (!spawningBlocks() || !spawned()) { spawnCursor = -1; return 0; }
        World w = world();
        Location base = new Location(w, x, y + visualY(), z, yaw, pitch());
        int done = 0;
        while (spawnCursor < displays.length && done < n) {
            final int idx = spawnCursor++;
            java.util.function.Consumer<Display> setup = d -> {
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
                d.setTeleportDuration(3); // turning is the entity's yaw: this smooths it, the same for every block
                d.setTransformation(transform(idx));
                if (blocks[idx] == null) { d.setGlowing(true); d.setGlowColorOverride(Color.fromRGB(150, 220, 255)); }
            };
            if (type.cells.get(idx).need() == ShipType.Need.CANNON)
                displays[idx] = w.spawn(base, ItemDisplay.class, d -> { d.setItemStack(pl.cannonModel()); setup.accept(d); });
            else
                displays[idx] = w.spawn(base, BlockDisplay.class, d -> { d.setBlock(dataOf(idx)); setup.accept(d); });
            root.addPassenger(displays[idx]);
            done++;
        }
        if (spawnCursor >= displays.length) spawnCursor = -1;
        return done;
    }

    void spawnSeats() {
        World w = world();
        seats = new ArmorStand[type.seats.length];
        for (int i = 0; i < seats.length; i++) seats[i] = w.spawn(seatLoc(i), ArmorStand.class, Ship::seatSetup);
    }

    static void seatSetup(ArmorStand a) {
        a.setPersistent(false);
        a.addScoreboardTag(TAG);
        a.setMarker(true);       // no hitbox; a rider sits right at its feet
        a.setInvisible(true);
        a.setGravity(false);
        a.setInvulnerable(true);
        a.setSilent(true);
        a.setBasePlate(false);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            try { a.addEquipmentLock(slot, ArmorStand.LockType.REMOVING_OR_CHANGING); } catch (RuntimeException ignored) { }
        }
    }

    /** The Bedrock stand-in: hidden from Java players, wearing the ship model (Geyser maps it to a Bedrock attachable). */
    void spawnStand() {
        World w = world();
        if (w == null || building) return;
        stand = w.spawn(new Location(w, x, y + visualY(), z, yaw, 0), ArmorStand.class, a -> {
            seatSetup(a);
            a.setMarker(false);  // Geyser needs a real armor stand to draw the helmet on
            a.setInvulnerable(false); // so a Bedrock player's hit reaches the damage event (cancelled there, counted on the ship)
            a.setVisibleByDefault(false);
            a.getEquipment().setHelmet(standItem());
        });
        for (Player p : w.getPlayers()) {
            if (FaultlineShips.bedrock(p)) p.showEntity(pl, stand);
            else p.hideEntity(pl, stand); // in case visible-by-default isn't honoured
        }
    }

    /** Geyser maps paper with this item model to the Bedrock ship model (tools/ship_assets.py --bedrock). */
    String standModel() { return "faultline:ship/" + type.name().toLowerCase() + (ai != null ? "_dark" : "") + (wrecked ? "_wreck" : ""); }

    ItemStack standItem() {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(NamespacedKey.fromString(standModel()));
        it.setItemMeta(m);
        return it;
    }

    void spawnHitboxes() {
        removeHitboxes();
        World w = world();
        float width = (float) Math.min(type.halfWidth * 2 + 1, 3.2), height = type.deckY + 2.2f;
        for (int lx = type.minX + 1; lx <= type.maxX; lx += 2) {
            final int at = lx;
            double[] p = toWorld(x, z, yaw, at, 0);
            hitboxes.add(w.spawn(new Location(w, p[0], y, p[1]), Interaction.class, it -> {
                it.setPersistent(false);
                it.addScoreboardTag(TAG);
                it.setInteractionWidth(width);
                it.setInteractionHeight(height);
                it.setResponsive(true);
            }));
        }
    }

    void removeHitboxes() {
        for (Interaction it : hitboxes) if (it.isValid()) it.remove();
        hitboxes.clear();
    }

    void despawn() {
        if (seats != null) for (ArmorStand s : seats) if (s != null && s.isValid()) { s.eject(); s.remove(); }
        if (stand != null && stand.isValid()) stand.remove();
        stand = null;
        if (displays != null) for (Display d : displays) if (d != null && d.isValid()) d.remove();
        spawnCursor = -1;
        if (flag != null && flag.isValid()) flag.remove();
        if (flagStand != null && flagStand.isValid()) flagStand.remove();
        flag = null; flagStand = null;
        if (root != null && root.isValid()) { root.eject(); root.remove(); }
        removeHitboxes();
        if (label != null && label.isValid()) label.remove();
        if (bar != null) bar.removeAll();
        root = null; displays = null; seats = null; label = null;
    }

    BlockData dataOf(int i) {
        if (type.cells.get(i).need() == ShipType.Need.CHEST) return Material.BARREL.createBlockData(); // chests are drawn as block entities: a display shows nothing
        if (blocks[i] != null) {
            try { return Bukkit.createBlockData(blocks[i]); } catch (IllegalArgumentException ignored) { }
        }
        return withProps(type.cells.get(i).need().ghost, type.cells.get(i).props());
    }

    static BlockData withProps(Material m, String props) {
        if (props != null && !props.isEmpty()) {
            try { return Bukkit.createBlockData(m, "[" + props + "]"); } catch (IllegalArgumentException ignored) { }
        }
        return m.createBlockData();
    }

    float scaleOf(int i) {
        if (blocks[i] == null) return GHOST;
        if (broken.contains(i)) return 0f; // knocked out: a hole until it's patched
        if (anchored && type.cells.get(i).need().shaped()) return 0f; // the real block stands there (stairs, fences, ladders...)
        if (wrecked && type.cells.get(i).need().sail() && (i % 2 == 0 || type.cells.get(i).y() % 3 == 0)) return 0f; // torn sails
        return 1f;
    }

    /**
     * Where a cell's display draws, in the ship's own frame (it never changes while sailing). The display entity's yaw
     * (the ship's yaw) turns it, so every block of the ship turns together as one rigid piece.
     */
    Transformation transform(int i) {
        ShipType.Cell c = type.cells.get(i);
        float s = scaleOf(i);
        if (c.need() == ShipType.Need.CANNON) { // the cannon model points at +z; port-side ones turn round
            Quaternionf q = new Quaternionf(Q0).mul(c.props().contains("south") ? FLIP : new Quaternionf());
            Vector3f t = new Vector3f(c.x(), c.y() + 0.5f * s, c.z());
            Q0.transform(t);
            return new Transformation(t, q, new Vector3f(s, s, s), new Quaternionf());
        }
        Vector3f t = new Vector3f(c.x() - s * 0.5f, c.y() + 0.5f - s * 0.5f, c.z() - s * 0.5f);
        Q0.transform(t);
        return new Transformation(t, new Quaternionf(Q0), new Vector3f(s, s, s), new Quaternionf());
    }

    /** Turn the whole ship: every display (and the flag) gets the ship's yaw; the client smooths it. */
    void sendRotation() {
        float pt = pitch();
        if (displays != null) for (Display d : displays) if (d != null && d.isValid()) d.setRotation(yaw, pt);
        if (flag != null && flag.isValid()) flag.setRotation(yaw, pt);
        sentYaw = yaw;
    }

    void refreshCell(int i) {
        if (displays == null || displays[i] == null || !displays[i].isValid()) return;
        Display d = displays[i];
        if (d instanceof BlockDisplay bd) bd.setBlock(dataOf(i));
        d.setGlowing(blocks[i] == null);
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(4);
        d.setTransformation(transform(i));
    }

    void refreshLadders() {
        for (int i = 0; i < blocks.length; i++) if (type.cells.get(i).need().shaped()) refreshCell(i);
    }

    // ---- the banner ----
    void spawnFlag() {
        World w = world();
        if (w == null || banner == null || root == null) return;
        Location base = new Location(w, x, y + visualY(), z, yaw, 0);
        flag = w.spawn(base, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setTeleportDuration(3);
            d.setItemStack(banner);
            float sc = 1.4f;
            Vector3f t = new Vector3f(type.flagX, type.flagY + 0.5f * sc, 0);
            Q0.transform(t);
            d.setTransformation(new Transformation(t, new Quaternionf(Q0).mul(FLIP), new Vector3f(sc, sc, sc), new Quaternionf()));
        });
        root.addPassenger(flag);
        flagStand = w.spawn(flagStandLoc(), ArmorStand.class, a -> {
            seatSetup(a);
            a.setMarker(false);
            a.setVisibleByDefault(false);
            a.getEquipment().setHelmet(banner.clone());
        });
        for (Player p : w.getPlayers()) {
            if (FaultlineShips.bedrock(p)) p.showEntity(pl, flagStand);
            else p.hideEntity(pl, flagStand);
        }
    }

    Location flagStandLoc() {
        double[] w = toWorld(x, z, yaw, type.flagX, 0);
        return new Location(world(), w[0], y + type.flagY - 1.2 + visualY(), w[1], yaw + 90, 0);
    }

    /** Hang a banner (any banner, patterns and all). Returns the one it replaces, or null. */
    ItemStack setBanner(ItemStack b) {
        ItemStack old = banner;
        banner = b == null ? null : b.clone();
        if (banner != null) banner.setAmount(1);
        if (flag != null && flag.isValid()) flag.remove();
        if (flagStand != null && flagStand.isValid()) flagStand.remove();
        flag = null; flagStand = null;
        spawnFlag();
        pl.dirty = true;
        return old;
    }

    // ---- cannons ----
    List<Integer> cannons() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < blocks.length; i++) if (type.cells.get(i).need() == ShipType.Need.CANNON && blocks[i] != null) out.add(i);
        return out;
    }

    /** +1 for a starboard cannon, -1 for port. */
    int sideOf(int i) { return type.cells.get(i).props().contains("south") ? 1 : -1; }

    /** World position of a cannon's muzzle. */
    Location muzzle(int i) {
        ShipType.Cell c = type.cells.get(i);
        double[] w = toWorld(x, z, yaw, c.x(), c.z() + sideOf(i) * 0.9);
        return new Location(world(), w[0], y + c.y() + 0.55 + visualY(), w[1]);
    }

    /** Straight out of the cannon's side of the ship. */
    org.bukkit.util.Vector outward(int i) {
        double[] a = toWorld(0, 0, yaw, 0, sideOf(i));
        return new org.bukkit.util.Vector(a[0], 0, a[1]).normalize();
    }

    void refreshLabel() {
        World w = world();
        if (w == null || !spawned()) return;
        String text = null;
        if (building) {
            StringBuilder sb = new StringBuilder(ChatColor.AQUA + "" + ChatColor.BOLD + type.title + ChatColor.GRAY + " (" + filled + "/" + blocks.length + ")\n"
                    + ChatColor.WHITE + "Still needs:");
            for (var e : missing().entrySet()) sb.append("\n").append(ChatColor.YELLOW).append(e.getValue()).append(" ").append(ChatColor.WHITE).append(e.getKey().label);
            text = sb.toString();
        } else if (wrecked) {
            text = ChatColor.RED + "" + ChatColor.BOLD + "SINKING\n" + ChatColor.GRAY + "Patch it with a Shipwright's Hammer to raise it\n"
                    + ChatColor.WHITE + "Hull " + (int) Math.round(100 * hp / maxHp()) + "%";
        } else if (anchored && (hp < maxHp() || !broken.isEmpty())) {
            text = ChatColor.GOLD + title() + ChatColor.GRAY + " · Hull " + (int) Math.round(100 * hp / maxHp()) + "%"
                    + (broken.isEmpty() ? "" : ChatColor.RED + " · " + broken.size() + " holes");
        } else if (anchored && name != null) {
            text = ChatColor.GOLD + "" + ChatColor.BOLD + name;
        }
        if (text == null) {
            if (label != null && label.isValid()) label.remove();
            label = null;
            return;
        }
        Location at = new Location(w, x, y + type.height + 1.2, z);
        if (label == null || !label.isValid()) {
            label = w.spawn(at, TextDisplay.class, t -> {
                t.setPersistent(false);
                t.addScoreboardTag(TAG);
                t.setBillboard(Display.Billboard.CENTER);
                t.setShadowed(true);
            });
        } else label.teleport(at);
        label.setText(text);
    }

    java.util.Map<ShipType.Need, Integer> missing() {
        java.util.Map<ShipType.Need, Integer> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < blocks.length; i++) if (blocks[i] == null) m.merge(type.cells.get(i).need(), 1, Integer::sum);
        return m;
    }

    // =====================================================================================================
    //  people
    // =====================================================================================================
    Player rider(int seat) {
        if (seats == null || seats[seat] == null || !seats[seat].isValid()) return null;
        for (Entity e : seats[seat].getPassengers()) if (e instanceof Player p) return p;
        return null;
    }

    List<Player> riders() {
        List<Player> out = new ArrayList<>();
        if (seats == null) return out;
        for (int i = 0; i < seats.length; i++) { Player p = rider(i); if (p != null) out.add(p); }
        return out;
    }

    int seatOf(Player p) {
        if (seats == null) return -1;
        for (int i = 0; i < seats.length; i++) if (seats[i] != null && seats[i].getPassengers().contains(p)) return i;
        return -1;
    }

    /** Sit a player down: the helm if they may steer and it's free (or they asked for it), else the closest free seat. */
    boolean board(Player p, boolean helmOnly, Location near) {
        if (building || seats == null || p.isInsideVehicle()) return false;
        if (wrecked) { p.sendActionBar(Component.text("The " + title() + " is sinking: patch it with a Shipwright's Hammer.", NamedTextColor.RED)); return false; }
        if (ai != null) return false; // the skeletons' seats
        boolean captain = pl.mayCaptain(p, this);
        if (helmOnly && !captain) { p.sendActionBar(Component.text("Only the owner and their crew can take the helm.", NamedTextColor.RED)); return false; }
        int best = -1;
        if (captain && rider(0) == null) best = 0;
        else if (!helmOnly) {
            double bd = Double.MAX_VALUE;
            Location from = near != null ? near : p.getLocation();
            for (int i = 1; i < seats.length; i++) {
                if (rider(i) != null) continue;
                double d = seatLoc(i).distanceSquared(from);
                if (d < bd) { bd = d; best = i; }
            }
        }
        if (best < 0) { p.sendActionBar(Component.text(helmOnly ? "Someone is already at the helm." : "No free seats.", NamedTextColor.RED)); return false; }
        seats[best].teleport(seatLoc(best));
        seats[best].addPassenger(p);
        if (best == 0) {
            p.sendMessage(ChatColor.AQUA + "You take the helm of the " + title() + ". " + ChatColor.GRAY
                    + (anchored ? "W to raise the anchor and sail. " : "") + "W sail, S brake (then reverse), A/D steer, Sprint for full sail, Jump to drop anchor.");
        }
        return true;
    }

    // =====================================================================================================
    //  every tick
    // =====================================================================================================
    void tick() {
        ticks++;
        if (!spawned()) {
            // a skeleton ship too far off to be drawn still sails (just numbers) while its water is loaded, so it keeps
            // coming after you instead of freezing out past the draw range
            if (ai != null && !wrecked && unseenLoaded()) {
                if (anchorStep >= 0) stepAnchor();
                else if (sailing()) sail();
            }
            return;
        }
        if (anchorStep >= 0) stepAnchor();
        else if (anchored && !building) helmWhileAnchored();
        else if (sailing() && anchorForLeave) anchorToLeave();
        else if (sailing()) sail();
        if (anchored && anchorStep < 0 && !gettingOff.isEmpty()) letOff(false); // the deck is solid now
        // move everything
        // a wreck goes down slowly (tilting); a repaired one comes back up
        boolean sinking = false;
        if (wrecked && sink < sinkTarget()) { sink = Math.min(sinkTarget(), sink + pl.cfg("sink-speed", 0.006) * (ai != null ? 3 : 1)); sinking = true; }
        else if (!wrecked && sink > 0) { sink = Math.max(0, sink - 0.03); sinking = true; }
        if (ticks % 20 == 0) viewerNear = viewerWithin(64);
        if (sailing() || anchorStep >= 0 || sinking) {
            // every block's yaw is its own packet: big ships, skeleton ships and ships nobody's near send fewer
            int every = !viewerNear ? 15 : ai != null ? 3 : type == ShipType.GALLEON || type == ShipType.PIRATE ? 2 : 1;
            if ((Math.abs(yaw - sentYaw) >= 0.5f || (sinking && ticks % 10 == 0)) && ticks % every == 0) sendRotation();
            place();
        }
        if (ticks % 10 == 0) {
            effects();
            hud();
        }
    }

    boolean viewerNear = true;

    boolean unseenLoaded() {
        World w = world();
        return w != null && w.isChunkLoaded((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
    }

    double nearestPlayerSq() {
        World w = world();
        double best = Double.MAX_VALUE;
        if (w != null) for (Player p : w.getPlayers()) {
            double dx = p.getLocation().getX() - x, dz = p.getLocation().getZ() - z;
            best = Math.min(best, dx * dx + dz * dz);
        }
        return best;
    }

    boolean viewerWithin(double r) {
        World w = world();
        if (w == null) return false;
        for (Player p : w.getPlayers()) {
            double dx = p.getLocation().getX() - x, dz = p.getLocation().getZ() - z;
            if (dx * dx + dz * dz < r * r) return true;
        }
        return false;
    }

    /** Root, seats, hitboxes and the Bedrock stand-in to where the ship is now. */
    void place() {
        World w = world();
        if (w == null || root == null) return;
        root.teleport(new Location(w, x, y + visualY(), z));
        if (seats != null) for (int i = 0; i < seats.length; i++) {
            if (seats[i] != null && seats[i].isValid()) seats[i].teleport(seatLoc(i));
        }
        int k = 0;
        for (int lx = type.minX + 1; lx <= type.maxX && k < hitboxes.size(); lx += 2, k++) {
            double[] p = toWorld(x, z, yaw, lx, 0);
            Interaction it = hitboxes.get(k);
            if (it.isValid()) it.teleport(new Location(w, p[0], y, p[1]));
        }
        if (stand != null && stand.isValid()) stand.teleport(new Location(w, x, y + visualY(), z, yaw, 0));
        if (flagStand != null && flagStand.isValid()) flagStand.teleport(flagStandLoc());
    }

    private Input input(Player p) { return p == null ? null : pl.inputSource.apply(p); }

    void helmWhileAnchored() {
        Player cap = rider(0);
        Input in = input(cap);
        boolean f = in != null && in.isForward();
        if (f && !prevForward && cap != null && !wrecked) raiseAnchor(cap);
        else if (f && !prevForward && cap != null) cap.sendActionBar(Component.text("The ship is wrecked: repair it first.", NamedTextColor.RED));
        prevForward = f;
        prevJump = in != null && in.isJump();
    }

    void sail() {
        Player cap = rider(0);
        if (cap != null && !pl.mayCaptain(cap, this)) cap = null;
        Input in = ai != null ? ai.input : input(cap);
        boolean f = false, b = false, l = false, r = false, sp = false, j = false;
        if (in != null) { f = in.isForward(); b = in.isBackward(); l = in.isLeft(); r = in.isRight(); sp = in.isSprint(); j = in.isJump(); }
        if (wrecked) { f = b = l = r = sp = j = false; }
        boolean jumped = j && !prevJump;
        boolean backPressed = b && !prevBack;
        prevJump = j;
        prevForward = f;
        prevBack = b;
        double max = type.speed * pl.getConfig().getDouble("ships." + type.size + ".speed-multiplier", 1.0);
        if (jumped && cap != null) {
            if (Math.abs(speed) <= max * 0.3) { dropAnchor(cap); return; }
            anchorQueued = true; // too fast: brake hard, then drop it
            cap.sendActionBar(Component.text("Braking to drop anchor...", NamedTextColor.YELLOW));
        }
        if (anchorQueued) {
            if (f || cap == null) anchorQueued = false;
            else {
                speed = brake(speed, max);
                if (Math.abs(speed) <= max * 0.3) { anchorQueued = false; dropAnchor(cap); return; }
            }
        }
        // S brakes hard to a stop; let go and press it again to go astern
        if (b && speed > 0.002 && !braking && backPressed) braking = true;
        if (allStop) { braking = true; if (speed == 0 || f) allStop = false; }
        else if (!b) braking = false;
        double target = f ? max * (sp ? 1.25 : 1) : b && !braking ? -max * 0.35 : 0;
        double acc = max / 45;
        if (braking || anchorQueued) { speed = brake(speed, max); if (speed == 0) braking = b; }
        else if (target == 0) { speed *= 0.975; if (Math.abs(speed) < 0.004) speed = 0; }
        else speed += Math.max(-acc, Math.min(acc, target - speed));
        double steer = (r ? 1 : 0) - (l ? 1 : 0);
        double rate = steer * type.turn * (0.35 + 0.65 * Math.min(1, Math.abs(speed) / max));
        turnRate += Math.max(-0.25, Math.min(0.25, rate - turnRate));
        if (Math.abs(turnRate) < 0.01) turnRate = 0;
        if (speed == 0 && turnRate == 0) return;
        float ny = (float) (yaw + turnRate);
        double nx = x + fx(ny) * speed, nz = z + fz(ny) * speed;
        if (clear(nx, nz, ny, false, null)) { x = nx; z = nz; yaw = ny; }
        else if (turnRate != 0 && clear(x, z, ny, false, null)) { yaw = ny; ram(max); }
        else { turnRate = 0; ram(max); }
        yaw = ((yaw % 360) + 360) % 360;
        if (Math.abs(sentYaw - yaw) > 180) sentYaw += sentYaw > yaw ? -360 : 360; // keep the interpolation the short way round
        if (ticks % 20 == 0) pl.dirty = true;
    }

    /** Brake and drop anchor so whoever asked can step off onto a solid deck (or, if it can't anchor here, over the side). */
    void anchorToLeave() {
        if (wrecked || gettingOff.isEmpty()) { anchorForLeave = false; letOff(true); return; }
        double max = type.speed * pl.getConfig().getDouble("ships." + type.size + ".speed-multiplier", 1.0);
        turnRate = 0;
        speed = brake(speed, max);
        if (Math.abs(speed) > max * 0.3) return;
        if (++leaveTries > 3) { // land in the way: no anchoring here
            for (UUID u : gettingOff) { Player p = Bukkit.getPlayer(u); if (p != null) p.sendActionBar(Component.text("Too close to land to anchor: over the side you go.", NamedTextColor.YELLOW)); }
            letOff(true);
            return;
        }
        dropAnchor(null);
    }

    /** Off they get: onto the deck if it's anchored, otherwise into the water beside the ship. */
    void letOff(boolean overboard) {
        anchorForLeave = false;
        leaveTries = 0;
        List<UUID> who = new ArrayList<>(gettingOff);
        gettingOff.clear();
        releasing = true;
        try {
            for (UUID u : who) {
                Player p = Bukkit.getPlayer(u);
                if (p == null || seats == null) continue;
                for (int i = 0; i < seats.length; i++) {
                    if (seats[i] == null || !seats[i].getPassengers().contains(p)) continue;
                    seats[i].removePassenger(p);
                    if (overboard || !anchored) overboard(p, i);
                }
            }
        } finally { releasing = false; }
    }

    /** Into the water beside the ship, level with this seat (never inside the hull). */
    boolean overboard(Player p, int seat) {
        double sx = type.seats[seat][0], sz = type.seats[seat][2];
        for (double side : sz >= 0 ? new double[]{1, -1} : new double[]{-1, 1}) {
            double[] w = toWorld(x, z, yaw, sx, side * (type.halfWidth + 1.6));
            Location l = new Location(world(), w[0], y + 0.1, w[1], p.getLocation().getYaw(), p.getLocation().getPitch());
            Block b = l.getBlock(), up = b.getRelative(0, 1, 0);
            if ((b.isLiquid() || b.isPassable()) && (up.isLiquid() || up.isPassable())) {
                Bukkit.getScheduler().runTask(pl, () -> { if (p.isOnline() && !p.isInsideVehicle()) p.teleport(l); });
                return true;
            }
        }
        return false;
    }

    static double brake(double speed, double max) {
        double d = max / 12;
        return Math.abs(speed) <= d ? 0 : speed - Math.signum(speed) * d;
    }

    void ram(double max) {
        double hit = Math.abs(speed) / max;
        if (hit > 0.45 && ticks - lastRam > 20) {
            lastRam = ticks;
            Location at = center();
            world().playSound(at, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.BLOCKS, 1.4f, 0.6f);
            world().spawnParticle(Particle.BLOCK, at, 40, 2, 1, 2, 0, Material.OAK_PLANKS.createBlockData());
            Location crash = lastBlocked != null ? lastBlocked.getLocation().add(0.5, 0.5, 0.5) : null;
            damage(pl.getConfig().getDouble("ram-damage", 30) * hit, null, crash);
            if (lastBlockShip != null && lastBlockShip != this && !lastBlockShip.building)
                lastBlockShip.damage(pl.getConfig().getDouble("ram-damage", 30) * hit * pl.cfg("ram-other-multiplier", 1.6), null, crash);
            for (Player p : riders()) p.sendActionBar(Component.text("The hull scrapes against the shore!", NamedTextColor.RED));
        }
        speed = -speed * 0.2;
    }

    // =====================================================================================================
    //  anchor
    // =====================================================================================================
    void dropAnchor(Player cap) {
        double max = type.speed * pl.getConfig().getDouble("ships." + type.size + ".speed-multiplier", 1.0);
        if (Math.abs(speed) > max * 0.3) { if (cap != null) cap.sendActionBar(Component.text("Slow down to drop anchor.", NamedTextColor.YELLOW)); return; }
        float ty = Math.round(yaw / 90f) * 90f;
        double[] spot = snapSpot(ty);
        if (spot == null) { if (cap != null) cap.sendActionBar(Component.text("Too close to land or blocks to drop anchor here.", NamedTextColor.RED)); return; }
        speed = 0; turnRate = 0;
        anchorYaw0 = yaw; anchorYaw1 = ty;
        if (Math.abs(anchorYaw1 - anchorYaw0) > 180) anchorYaw1 += anchorYaw1 > anchorYaw0 ? -360 : 360;
        anchorX0 = x; anchorZ0 = z; anchorX1 = spot[0]; anchorZ1 = spot[1];
        anchorStep = 0;
        world().playSound(center(), Sound.BLOCK_CHAIN_PLACE, SoundCategory.BLOCKS, 1.5f, 0.6f);
        world().playSound(center(), Sound.ENTITY_GENERIC_SPLASH, SoundCategory.BLOCKS, 1.2f, 0.8f);
    }

    /** The nearest grid spot (block centre, yaw a multiple of 90) where every cell fits. */
    double[] snapSpot(float ty) {
        double bx = Math.floor(x) + 0.5, bz = Math.floor(z) + 0.5;
        double[][] tries = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        for (double[] t : tries) if (clear(bx + t[0], bz + t[1], ty, true, null)) return new double[]{bx + t[0], bz + t[1]};
        return null;
    }

    void stepAnchor() {
        anchorStep++;
        double t = Math.min(1, anchorStep / 12.0), e = t * t * (3 - 2 * t);
        yaw = (float) (anchorYaw0 + (anchorYaw1 - anchorYaw0) * e);
        x = anchorX0 + (anchorX1 - anchorX0) * e;
        z = anchorZ0 + (anchorZ1 - anchorZ0) * e;
        if (anchorStep >= 12) {
            anchorStep = -1;
            yaw = ((Math.round(anchorYaw1) % 360) + 360) % 360;
            x = anchorX1; z = anchorZ1;
            if (!clear(x, z, yaw, true, null)) { // something moved in while we swung round: one more look nearby
                double[] spot = snapSpot((float) yaw);
                if (spot == null) {
                    if (anchorForLeave) return; // someone getting off: anchorToLeave tries again, then puts them over the side
                    for (Player p : riders()) p.sendMessage(ChatColor.RED + "Couldn't drop anchor here: the " + title()
                            + " is still afloat (the deck isn't walkable). Move away from the shore and try again.");
                    return;
                }
                x = spot[0]; z = spot[1];
            }
            sendRotation();
            int solid = anchor();
            Player cap = rider(0);
            if (cap != null) cap.sendMessage(ChatColor.AQUA + "Anchor dropped. " + ChatColor.GRAY + "The deck is solid (" + solid + "/" + filled
                    + " blocks), climb the ladders from the water. W at the helm raises the anchor.");
        }
    }

    /** Snap is done: barriers in every built cell, so the deck can be walked on. Returns how many cells are solid. */
    int anchor() {
        anchored = true;
        speed = 0; turnRate = 0; braking = false; anchorQueued = false; allStop = false;
        removeHitboxes();
        place();
        int solid = 0;
        for (int i = 0; i < blocks.length; i++) if (blocks[i] != null && !broken.contains(i) && putBarrier(i)) solid++;
        refreshLadders();
        liftPlayers();
        refreshLabel();
        pl.saveNow();
        return solid;
    }

    /**
     * A barrier in a built cell, or for stairs, fences, trapdoors, ladders, panes and lanterns the real block, so they
     * feel like the real thing (walk up stairs, can't hop the rails, climb the ladders, open the hatch).
     * True if the cell is solid now.
     */
    boolean putBarrier(int i) {
        Block b = blockOf(i);
        boolean real = type.cells.get(i).need().shaped();
        BlockData want = real ? dataOf(i) : Material.BARRIER.createBlockData();
        if (b.getType() == want.getMaterial()) return true;
        if (!water(b) && !air(b)) return false;
        boolean wet = water(b);
        String was = b.getBlockData().getAsString();
        BlockData bar = want;
        if (bar instanceof Waterlogged wl) wl.setWaterlogged(wet);
        b.setBlockData(bar, false);
        barriers.add(b.getX() + "," + b.getY() + "," + b.getZ() + "|" + was);
        return true;
    }

    void clearBarriers() {
        World w = world();
        if (w != null) for (String s : barriers) restoreBarrier(w, s);
        barriers.clear();
    }

    static void restoreBarrier(World w, String s) {
        int bar = s.indexOf('|');
        String[] p = s.substring(0, bar).split(",");
        Block b = w.getBlockAt(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        if (!shipBlock(b.getType())) return;
        try { b.setBlockData(Bukkit.createBlockData(s.substring(bar + 1)), false); }
        catch (IllegalArgumentException ex) { b.setType(s.contains("water") ? Material.WATER : Material.AIR, false); }
    }

    /** A block a ship puts in the world while anchored: barriers, and the real shaped blocks. */
    static boolean shipBlock(Material m) {
        if (m == Material.BARRIER || m == Material.LADDER || m == Material.LANTERN || m == Material.SOUL_LANTERN) return true;
        String n = m.name();
        return n.endsWith("_STAIRS") || n.endsWith("_FENCE") || n.endsWith("_TRAPDOOR") || n.endsWith("GLASS_PANE");
    }

    void raiseAnchor(Player cap) {
        clearBarriers();
        anchored = false;
        refreshLadders();
        // whoever is standing on the deck gets a seat, or is left behind (not on a skeleton ship: its seats are its crew's)
        if (ai == null) for (Player p : world().getPlayers()) {
            if (p.isInsideVehicle() || p.getLocation().distanceSquared(center()) > radius() * radius() + 25) continue;
            Location l = p.getLocation();
            if (cellAt(l.getX(), l.getY() - 0.2, l.getZ()) < 0 && cellAt(l.getX(), l.getY() - 1.2, l.getZ()) < 0) continue;
            if (!board(p, false, l)) p.sendMessage(ChatColor.GRAY + "No free seat on the " + type.title + ": you're left behind.");
        }
        spawnHitboxes();
        refreshLabel();
        world().playSound(center(), Sound.BLOCK_CHAIN_BREAK, SoundCategory.BLOCKS, 1.5f, 0.7f);
        world().playSound(center(), Sound.ENTITY_PLAYER_SPLASH, SoundCategory.BLOCKS, 1f, 1.2f);
        if (cap != null) cap.sendActionBar(Component.text("Anchor up. Full sail with Sprint, S to brake, Jump to drop anchor.", NamedTextColor.AQUA));
        pl.saveNow();
    }

    /** Barriers just went in: nobody may be left stuck inside one (they'd suffocate). Up onto the deck with them. */
    void liftPlayers() {
        World w = world();
        for (Player p : w.getPlayers()) {
            if (p.isInsideVehicle()) continue;
            Location l = p.getLocation();
            if (Math.abs(l.getX() - x) > radius() + 2 || Math.abs(l.getZ() - z) > radius() + 2) continue;
            if (cellAt(l.getX(), l.getY() + 0.1, l.getZ()) < 0 && cellAt(l.getX(), l.getY() + 1.1, l.getZ()) < 0) continue;
            double ty = Math.floor(l.getY());
            while (ty < y + type.height + 2 && (cellAt(l.getX(), ty + 0.1, l.getZ()) >= 0 || cellAt(l.getX(), ty + 1.1, l.getZ()) >= 0)) ty++;
            p.teleport(new Location(w, l.getX(), ty, l.getZ(), l.getYaw(), l.getPitch()));
        }
    }

    /**
     * After a crash the saved barrier list can be behind the world: a block placed after the last save left a barrier
     * we don't know about. Built cells get theirs recorded; empty cells (and afloat ships) get water or air back.
     */
    void reconcileBarriers() {
        World w = world();
        if (w == null) return;
        Set<String> known = new HashSet<>(), cellsAt = new HashSet<>();
        for (String b : barriers) known.add(b.substring(0, b.indexOf('|')));
        boolean changed = false;
        for (int i = 0; i < blocks.length; i++) {
            Block b = blockOf(i);
            String at = b.getX() + "," + b.getY() + "," + b.getZ();
            cellsAt.add(at);
            if (!shipBlock(b.getType())) {
                if (anchored && blocks[i] != null && putBarrier(i)) changed = true; // a cell the ship didn't have before (an update)
                continue;
            }
            if (known.contains(at)) continue;
            String was = type.cells.get(i).y() == 0 ? "minecraft:water" : "minecraft:air";
            if (anchored && blocks[i] != null) barriers.add(at + "|" + was);
            else b.setBlockData(Bukkit.createBlockData(was), false);
            changed = true;
        }
        // barriers of cells the ship doesn't have any more (an update): water or air again
        for (java.util.Iterator<String> it = barriers.iterator(); it.hasNext(); ) {
            String b = it.next();
            if (cellsAt.contains(b.substring(0, b.indexOf('|')))) continue;
            restoreBarrier(w, b);
            it.remove();
            changed = true;
        }
        if (changed) pl.saveNow();
    }

    /** What Bedrock players are sent while it's being built: the real block in each built cell. */
    List<BlockState> fakes(List<Integer> cells) {
        List<BlockState> out = new ArrayList<>();
        for (int i : cells) {
            if (blocks[i] == null || type.cells.get(i).need() == ShipType.Need.CANNON || type.cells.get(i).need().shaped()) continue; // shaped ones are real already
            Block b = blockOf(i);
            if (b.getType() != Material.BARRIER) continue;
            BlockState st = b.getState();
            st.setBlockData(dataOf(i));
            out.add(st);
        }
        return out;
    }

    // =====================================================================================================
    //  building
    // =====================================================================================================
    String blockFor(int i, Material m) {
        if (type.cells.get(i).need() == ShipType.Need.CANNON) return CANNON_DATA;
        return withProps(m, type.cells.get(i).props()).getAsString();
    }

    void fill(int i, Material m) {
        if (blocks[i] != null) return;
        blocks[i] = blockFor(i, m);
        filled++;
        if (anchored) { putBarrier(i); liftPlayers(); }
        refreshCell(i);
        pl.showFakes(this, List.of(i));
        Block b = blockOf(i);
        Sound place = type.cells.get(i).need() == ShipType.Need.CANNON ? Sound.BLOCK_ANVIL_PLACE : m.createBlockData().getSoundGroup().getPlaceSound();
        world().playSound(b.getLocation().add(0.5, 0.5, 0.5), place, SoundCategory.BLOCKS, type.cells.get(i).need() == ShipType.Need.CANNON ? 0.5f : 1f, 0.9f);
        if (filled >= blocks.length) finish();
        else if (filled % 4 == 0) refreshLabel();
        pl.dirty = true;
    }

    /** An admin's ready-made ship: every cell filled with the default blocks at once. */
    void completeAll() {
        for (int i = 0; i < blocks.length; i++) {
            if (blocks[i] != null) continue;
            blocks[i] = blockFor(i, type.cells.get(i).need().ghost);
            if (anchored) putBarrier(i);
            refreshCell(i);
        }
        recount();
        if (anchored) liftPlayers();
        finish();
    }

    void finish() {
        building = false;
        hp = maxHp();
        spawnSeats();
        spawnStand();
        pl.clearFakes(this);
        refreshLabel();
        Location c = center();
        world().playSound(c, Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 1f, 1f);
        world().spawnParticle(Particle.FIREWORK, c.clone().add(0, type.height / 2.0, 0), 80, 2.5, 2, 2.5, 0.05);
        Player o = Bukkit.getPlayer(owner);
        String msg = ChatColor.AQUA + "" + ChatColor.BOLD + "Your " + type.title + " is finished! " + ChatColor.GRAY
                + "Right-click the wheel to take the helm, W to raise the anchor.";
        if (o != null) o.sendMessage(msg);
        for (Player p : world().getPlayers()) if (!p.getUniqueId().equals(owner) && p.getLocation().distanceSquared(c) < 900)
            p.sendMessage(ChatColor.AQUA + "The " + type.title + " is finished!");
        pl.saveNow();
    }

    int recount() {
        filled = 0;
        for (String b : blocks) if (b != null) filled++;
        return filled;
    }

    // =====================================================================================================
    //  health
    // =====================================================================================================
    void damage(double amount, Player by) { damage(amount, by, null); }

    /** at: where it was hit (the blocks nearest it break), or null for anywhere. */
    void damage(double amount, Player by, Location at) {
        if (building || wrecked || amount <= 0) return;
        hp = Math.max(0, hp - amount);
        pl.dirty = true;
        breakAcc += amount;
        double per = Math.max(1, pl.cfg("parts.hp-per-broken-block", 6));
        int n = (int) Math.min(pl.cfg("parts.max-broken-per-hit", 6), Math.floor(breakAcc / per));
        if (n > 0) { breakAcc -= n * per; breakNear(at, n); }
        if (ticks - lastHurtFx > 4) {
            lastHurtFx = ticks;
            Location c = center();
            world().playSound(c, Sound.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 1.3f, 0.7f);
            world().spawnParticle(Particle.BLOCK, c, 20, type.halfWidth, 1, 2, 0, Material.SPRUCE_PLANKS.createBlockData());
        }
        if (hp <= 0) wreck(by);
        else if (anchored) refreshLabel();
    }

    void wreck(Player by) {
        if (ai != null) pl.pirates.onWreck(this);
        wrecked = true;
        hp = 0;
        speed = 0; turnRate = 0;
        if (seats != null) for (ArmorStand s : seats) if (s != null && s.isValid()) s.eject();
        anchorStep = -1;
        if (anchored) { // it's going down: the deck can't be stood on any more
            clearBarriers();
            anchored = false;
            refreshLadders();
            spawnHitboxes();
        }
        place();
        if (stand != null && stand.isValid()) stand.getEquipment().setHelmet(standItem());
        for (int i = 0; i < blocks.length; i++) if (type.cells.get(i).need().sail()) refreshCell(i);
        refreshLabel();
        Location c = center();
        world().playSound(c, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 1.2f, 0.7f);
        world().playSound(c, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.BLOCKS, 1.5f, 0.5f);
        world().spawnParticle(Particle.LARGE_SMOKE, c, 60, type.halfWidth, 1.5, 3, 0.02);
        String who = by != null ? " by " + by.getName() : "";
        for (Player p : world().getPlayers()) if (p.getLocation().distanceSquared(c) < 64 * 64)
            p.sendMessage(ChatColor.RED + (name != null ? "The " + name : "A " + type.title) + " has been wrecked" + who + "!");
        Player o = Bukkit.getPlayer(owner);
        if (o != null && o.getLocation().distanceSquared(c) >= 64 * 64)
            o.sendMessage(ChatColor.RED + "Your " + title() + " has been wrecked and is sinking! Patch it with a Shipwright's Hammer to raise it.");
        pl.saveNow();
    }

    /** Hammer work. Returns true once the ship is back to full (and every hole is patched). */
    boolean repair(double amount) { return repair(amount, null) > 0; }

    /**
     * Hammer work by a player: patches the holes within reach (2 a hit), and the hull.
     * Returns -1 if there are holes but none within reach (nothing happens), 0 for progress, 1 once it's whole again.
     */
    int repair(double amount, Player by) {
        if (!broken.isEmpty() && by != null) {
            List<Integer> near = brokenNear(by.getLocation(), pl.cfg("parts.reach", 6));
            if (near.isEmpty()) return -1;
            for (int k = 0; k < Math.min(2, near.size()); k++) fixPart(near.get(k));
        } else if (!broken.isEmpty()) {
            for (int i : new ArrayList<>(broken)) fixPart(i);
        }
        hp = Math.min(maxHp(), hp + amount);
        pl.dirty = true;
        if (hp >= maxHp() && broken.isEmpty() && wrecked) {
            wrecked = false;
            for (int i = 0; i < blocks.length; i++) if (type.cells.get(i).need().sail()) refreshCell(i);
            place();
            if (stand != null && stand.isValid()) stand.getEquipment().setHelmet(standItem());
            Location c = center();
            world().playSound(c, Sound.BLOCK_ANVIL_USE, SoundCategory.BLOCKS, 1f, 1.2f);
            world().spawnParticle(Particle.HAPPY_VILLAGER, c.clone().add(0, 2, 0), 60, type.halfWidth, 2, 4, 0);
        }
        refreshLabel();
        return hp >= maxHp() && broken.isEmpty() ? 1 : 0;
    }

    // ---- broken parts ----
    /** Knock out the n built blocks nearest to where it was hit (holes you can see, and fall through when anchored). */
    void breakNear(Location at, int n) {
        int cap = (int) (blocks.length * pl.cfg("parts.max-broken-fraction", 0.35));
        if (broken.size() >= cap) return;
        World w = world();
        if (w == null) return;
        double ax, ay, az;
        if (at != null) { ax = at.getX(); ay = at.getY(); az = at.getZ(); }
        else { // somewhere on the hull
            ShipType.Cell c = type.cells.get(type.probes.get((int) (Math.random() * type.probes.size())));
            double[] p = toWorld(x, z, yaw, c.x(), c.z());
            ax = p[0]; ay = y + c.y() + 0.5; az = p[1];
        }
        List<double[]> cand = new ArrayList<>();
        for (int i = 0; i < blocks.length; i++) {
            if (blocks[i] == null || broken.contains(i)) continue;
            ShipType.Cell c = type.cells.get(i);
            if (c.need() == ShipType.Need.LADDER) continue;
            double[] p = toWorld(x, z, yaw, c.x(), c.z());
            double dx = p[0] - ax, dy = y + c.y() + 0.5 - ay, dz = p[1] - az;
            cand.add(new double[]{dx * dx + dy * dy + dz * dz, i});
        }
        cand.sort((a, b) -> Double.compare(a[0], b[0]));
        for (int k = 0; k < Math.min(n, cand.size()) && broken.size() < cap; k++) {
            int i = (int) cand.get(k)[1];
            broken.add(i);
            if (anchored) unbarrier(i);
            refreshCell(i);
            Location l = cellLoc(i);
            try { w.spawnParticle(Particle.BLOCK, l, 14, 0.3, 0.3, 0.3, 0, dataOf(i)); } catch (RuntimeException ignored) { }
        }
        w.playSound(new Location(w, ax, ay, az), Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.BLOCKS, 1f, 0.8f);
        refreshLabel();
    }

    void fixPart(int i) {
        if (!broken.remove(i)) return;
        if (anchored) putBarrier(i);
        refreshCell(i);
        World w = world();
        if (w != null) w.spawnParticle(Particle.HAPPY_VILLAGER, cellLoc(i), 6, 0.3, 0.3, 0.3, 0);
    }

    /** Take a cell's barrier (or real block) back out: the original water or air. */
    void unbarrier(int i) {
        Block b = blockOf(i);
        String at = b.getX() + "," + b.getY() + "," + b.getZ() + "|";
        for (java.util.Iterator<String> it = barriers.iterator(); it.hasNext(); ) {
            String r = it.next();
            if (!r.startsWith(at)) continue;
            restoreBarrier(world(), r);
            it.remove();
        }
    }

    /** The middle of a cell in the world (where a wreck has sunk to, too). */
    Location cellLoc(int i) {
        ShipType.Cell c = type.cells.get(i);
        double[] p = toWorld(x, z, yaw, c.x(), c.z());
        return new Location(world(), p[0], y + c.y() + 0.5 - (wrecked || sink > 0 ? sink : 0), p[1]);
    }

    /** Broken cells within reach of a spot, nearest first. */
    List<Integer> brokenNear(Location l, double reach) {
        List<Integer> out = new ArrayList<>();
        for (int i : broken) if (cellLoc(i).distanceSquared(l) <= reach * reach) out.add(i);
        out.sort((a, b) -> Double.compare(cellLoc(a).distanceSquared(l), cellLoc(b).distanceSquared(l)));
        return out;
    }

    /** Where a cell is, in words: below deck, the rigging, the bow, the stern, port or starboard. */
    String partName(int i) {
        ShipType.Cell c = type.cells.get(i);
        if (c.y() < type.deckY && c.y() > 0) return "below deck";
        if (c.need().sail() || c.need() == ShipType.Need.LOG || c.y() > type.deckY + 2) return "rigging";
        if (c.x() >= type.maxX - 2) return "bow";
        if (c.x() <= type.minX + 2) return "stern";
        if (c.y() == 0) return "keel";
        return c.z() < 0 ? "port side" : c.z() > 0 ? "starboard side" : "deck";
    }

    /** The nearest broken part to a spot, in words ("the bow, 12 blocks away"). */
    String nearestDamage(Location l) {
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i : broken) { double d = cellLoc(i).distanceSquared(l); if (d < bd) { bd = d; best = i; } }
        return best < 0 ? "nothing" : "the " + partName(best) + ", " + (int) Math.sqrt(bd) + " blocks away";
    }

    void effects() {
        Location c = center();
        if (wrecked) world().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, c.clone().add(0, 1, 0), 2, type.halfWidth, 0.5, 2, 0.01);
        else if (!building && hp < maxHp() * 0.4) world().spawnParticle(Particle.SMOKE, c, 4, type.halfWidth, 0.5, 2, 0.01);
        if (sailing() && Math.abs(speed) > 0.05) {
            double[] bow = toWorld(x, z, yaw, type.maxX + 0.5, 0);
            world().spawnParticle(Particle.SPLASH, new Location(world(), bow[0], y + 1, bow[1]), 12, 0.6, 0.1, 0.6, 0);
        }
    }

    void hud() {
        List<Player> riders = riders();
        if (bar == null) bar = Bukkit.createBossBar(type.title, BarColor.BLUE, BarStyle.SEGMENTED_10);
        bar.setTitle(ChatColor.AQUA + title() + ChatColor.GRAY + "  Hull " + (int) Math.ceil(hp) + "/" + (int) maxHp()
                + (broken.isEmpty() ? "" : ChatColor.RED + "  " + broken.size() + " holes"));
        bar.setProgress(Math.max(0, Math.min(1, hp / maxHp())));
        bar.setColor(hp < maxHp() * 0.3 ? BarColor.RED : hp < maxHp() * 0.7 ? BarColor.YELLOW : BarColor.BLUE);
        Set<Player> want = new HashSet<>(riders);
        for (Player p : new ArrayList<>(bar.getPlayers())) if (!want.contains(p)) bar.removePlayer(p);
        for (Player p : want) if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
        Player cap = rider(0);
        if (cap != null && sailing() && !pl.repairing(cap)) {
            double ms = Math.abs(speed) * 20;
            cap.sendActionBar(Component.text(String.format("⚓ %.1f m/s", ms), NamedTextColor.AQUA)
                    .append(Component.text(braking ? "   BRAKING" : anchorQueued ? "   dropping anchor..." : "   W sail · S brake · A/D steer · Sprint full sail · Jump anchor", braking || anchorQueued ? NamedTextColor.YELLOW : NamedTextColor.GRAY)));
        }
    }

    // =====================================================================================================
    //  cargo
    // =====================================================================================================
    static final class Hold implements InventoryHolder {
        final Ship ship;
        Inventory inv;
        Hold(Ship ship) { this.ship = ship; }
        @Override public Inventory getInventory() { return inv; }
    }

    Inventory hold() {
        if (hold == null && type.cargo > 0) {
            Hold h = new Hold(this);
            hold = Bukkit.createInventory(h, type.cargo, title() + "'s Hold");
            h.inv = hold;
            if (cargo != null) hold.setContents(java.util.Arrays.copyOf(cargo, type.cargo));
        }
        return hold;
    }

    ItemStack[] cargoNow() { return hold != null ? hold.getContents() : cargo; }
}
