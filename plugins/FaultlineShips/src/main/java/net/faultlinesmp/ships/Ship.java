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

    final FaultlineShips pl;
    final UUID id;
    final ShipType type;
    final UUID owner;
    String world;
    double x, y, z;  // the root: local origin, y = the top water block
    float yaw;       // Minecraft yaw: 0 = bow to the south
    double hp;
    boolean building, anchored, wrecked;
    final String[] blocks;                      // block data placed in each cell (null = still a ghost)
    final List<String> barriers = new ArrayList<>(); // "x,y,z|original block data" for every barrier we put down
    ItemStack[] cargo;

    // ---- runtime ----
    ItemDisplay root;
    BlockDisplay[] displays;
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
    boolean prevJump, prevForward;
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
    boolean sailing() { return !building && !anchored && anchorStep < 0; }
    double maxHp() { return type.maxHp * pl.getConfig().getDouble("ships." + type.size + ".health-multiplier", 1.0); }

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

    double visualY() {
        if (wrecked) return -0.35;
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
            ShipType.Cell c = type.cells.get(i);
            for (double[] o : spots) {
                double[] wp = toWorld(px, pz, pyaw, c.x() + o[0], c.z() + o[1]);
                Block b = w.getBlockAt((int) Math.floor(wp[0]), (int) Math.floor(y + c.y() + 0.5), (int) Math.floor(wp[1]));
                if (!w.isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) { ok = false; if (bad == null) return false; continue; }
                boolean fine = c.y() == 0 ? water(b) : air(b);
                if (fine && c.y() == 0 && type.deep && c.z() == 0 && !water(b.getRelative(0, -1, 0))) fine = false;
                if (fine && pl.shipCellAt(b, this) != null) fine = false; // another ship
                if (!fine) { ok = false; if (bad == null) return false; bad.add(b); }
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
        Location base = new Location(w, x, y + visualY(), z);
        root = w.spawn(base, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setTeleportDuration(2);
        });
        displays = new BlockDisplay[type.cells.size()];
        for (int i = 0; i < displays.length; i++) {
            final int idx = i;
            displays[i] = w.spawn(base, BlockDisplay.class, d -> {
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
                d.setBlock(dataOf(idx));
                d.setTransformation(transform(idx, yaw));
                if (blocks[idx] == null) { d.setGlowing(true); d.setGlowColorOverride(Color.fromRGB(150, 220, 255)); }
            });
            root.addPassenger(displays[i]);
        }
        sentYaw = yaw;
        if (!building) { spawnSeats(); spawnStand(); }
        if (!reconciled) { reconciled = true; reconcileBarriers(); }
        if (sailing()) spawnHitboxes();
        refreshLabel();
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
    String standModel() { return "faultline:ship/" + type.name().toLowerCase() + (wrecked ? "_wreck" : ""); }

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
        if (displays != null) for (BlockDisplay d : displays) if (d != null && d.isValid()) d.remove();
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
        if (wrecked && type.cells.get(i).need() == ShipType.Need.WOOL && (i % 2 == 0 || type.cells.get(i).y() % 3 == 0)) return 0f; // torn sails
        return 1f;
    }

    Transformation transform(int i, float atYaw) {
        ShipType.Cell c = type.cells.get(i);
        float s = scaleOf(i);
        Quaternionf q = new Quaternionf().rotationY((float) -Math.toRadians(atYaw + 90));
        Vector3f t = new Vector3f(c.x() - s * 0.5f, c.y() + 0.5f - s * 0.5f, c.z() - s * 0.5f);
        q.transform(t);
        return new Transformation(t, q, new Vector3f(s, s, s), new Quaternionf());
    }

    void sendTransforms(int interp) {
        if (displays == null) return;
        for (int i = 0; i < displays.length; i++) {
            BlockDisplay d = displays[i];
            if (d == null || !d.isValid()) continue;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(interp);
            d.setTransformation(transform(i, yaw));
        }
        sentYaw = yaw;
    }

    void refreshCell(int i) {
        if (displays == null || displays[i] == null || !displays[i].isValid()) return;
        BlockDisplay d = displays[i];
        d.setBlock(dataOf(i));
        d.setGlowing(blocks[i] == null);
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(4);
        d.setTransformation(transform(i, yaw));
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
            text = ChatColor.RED + "" + ChatColor.BOLD + "WRECKED\n" + ChatColor.GRAY + "Repair it with a Shipwright's Hammer\n"
                    + ChatColor.WHITE + "Hull " + (int) Math.round(100 * hp / maxHp()) + "%";
        } else if (anchored && hp < maxHp()) {
            text = ChatColor.GOLD + type.title + ChatColor.GRAY + " · Hull " + (int) Math.round(100 * hp / maxHp()) + "%";
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
            p.sendMessage(ChatColor.AQUA + "You take the helm of the " + type.title + ". " + ChatColor.GRAY
                    + (anchored ? "W to raise the anchor and sail. " : "") + "W/S sail, A/D steer, Sprint for full sail, Jump to drop anchor.");
        }
        return true;
    }

    // =====================================================================================================
    //  every tick
    // =====================================================================================================
    void tick() {
        ticks++;
        if (!spawned()) return;
        if (anchorStep >= 0) stepAnchor();
        else if (anchored && !building) helmWhileAnchored();
        else if (sailing()) sail();
        // move everything
        if (sailing() || anchorStep >= 0) {
            int every = type == ShipType.SLOOP ? 2 : type == ShipType.BRIGANTINE ? 3 : 4;
            if (Math.abs(yaw - sentYaw) > 0.01f && ticks % every == 0) sendTransforms(every);
            place();
        }
        if (ticks % 10 == 0) {
            effects();
            hud();
        }
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
        Input in = input(cap);
        boolean f = false, b = false, l = false, r = false, sp = false, j = false;
        if (in != null) { f = in.isForward(); b = in.isBackward(); l = in.isLeft(); r = in.isRight(); sp = in.isSprint(); j = in.isJump(); }
        if (wrecked) { f = b = l = r = sp = j = false; }
        boolean jumped = j && !prevJump;
        prevJump = j;
        prevForward = f;
        if (jumped && cap != null) { dropAnchor(cap); return; }
        double max = type.speed * pl.getConfig().getDouble("ships." + type.size + ".speed-multiplier", 1.0);
        double target = f ? max * (sp ? 1.25 : 1) : b ? -max * 0.35 : 0;
        double acc = max / 45;
        if (target == 0) { speed *= 0.975; if (Math.abs(speed) < 0.004) speed = 0; }
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

    void ram(double max) {
        double hit = Math.abs(speed) / max;
        if (hit > 0.45 && ticks - lastRam > 20) {
            lastRam = ticks;
            Location at = center();
            world().playSound(at, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.BLOCKS, 1.4f, 0.6f);
            world().spawnParticle(Particle.BLOCK, at, 40, 2, 1, 2, 0, Material.OAK_PLANKS.createBlockData());
            damage(pl.getConfig().getDouble("ram-damage", 30) * hit, null);
            for (Player p : riders()) p.sendActionBar(Component.text("The hull scrapes against the shore!", NamedTextColor.RED));
        }
        speed = -speed * 0.2;
    }

    // =====================================================================================================
    //  anchor
    // =====================================================================================================
    void dropAnchor(Player cap) {
        double max = type.speed * pl.getConfig().getDouble("ships." + type.size + ".speed-multiplier", 1.0);
        if (Math.abs(speed) > max * 0.3) { cap.sendActionBar(Component.text("Slow down to drop anchor.", NamedTextColor.YELLOW)); return; }
        float ty = Math.round(yaw / 90f) * 90f;
        double[] spot = snapSpot(ty);
        if (spot == null) { cap.sendActionBar(Component.text("Too close to land or blocks to drop anchor here.", NamedTextColor.RED)); return; }
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
            if (!clear(x, z, yaw, true, null)) { // something moved in while we swung round
                for (Player p : riders()) p.sendActionBar(Component.text("Couldn't drop anchor here.", NamedTextColor.RED));
                return;
            }
            sendTransforms(3);
            anchor();
            Player cap = rider(0);
            if (cap != null) cap.sendMessage(ChatColor.AQUA + "Anchor dropped. " + ChatColor.GRAY + "The crew can walk the deck. W at the helm raises it again.");
        }
    }

    /** Snap is done: barriers in every built cell, so the deck can be walked on. */
    void anchor() {
        anchored = true;
        speed = 0; turnRate = 0;
        removeHitboxes();
        place();
        for (int i = 0; i < blocks.length; i++) if (blocks[i] != null) putBarrier(i);
        liftPlayers();
        refreshLabel();
        pl.saveNow();
    }

    void putBarrier(int i) {
        Block b = blockOf(i);
        if (b.getType() == Material.BARRIER) return;
        if (!water(b) && !air(b)) return;
        boolean wet = water(b);
        String was = b.getBlockData().getAsString();
        BlockData bar = Material.BARRIER.createBlockData();
        if (bar instanceof Waterlogged wl) wl.setWaterlogged(wet);
        b.setBlockData(bar, false);
        barriers.add(b.getX() + "," + b.getY() + "," + b.getZ() + "|" + was);
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
        if (b.getType() != Material.BARRIER) return;
        try { b.setBlockData(Bukkit.createBlockData(s.substring(bar + 1)), false); }
        catch (IllegalArgumentException ex) { b.setType(Material.WATER, false); }
    }

    void raiseAnchor(Player cap) {
        clearBarriers();
        anchored = false;
        // whoever is standing on the deck gets a seat, or is left behind
        for (Player p : world().getPlayers()) {
            if (p.isInsideVehicle() || p.getLocation().distanceSquared(center()) > radius() * radius() + 25) continue;
            Location l = p.getLocation();
            if (cellAt(l.getX(), l.getY() - 0.2, l.getZ()) < 0 && cellAt(l.getX(), l.getY() - 1.2, l.getZ()) < 0) continue;
            if (!board(p, false, l)) p.sendMessage(ChatColor.GRAY + "No free seat on the " + type.title + ": you're left behind.");
        }
        spawnHitboxes();
        refreshLabel();
        world().playSound(center(), Sound.BLOCK_CHAIN_BREAK, SoundCategory.BLOCKS, 1.5f, 0.7f);
        world().playSound(center(), Sound.ENTITY_PLAYER_SPLASH, SoundCategory.BLOCKS, 1f, 1.2f);
        cap.sendActionBar(Component.text("Anchor up. Full sail with Sprint, Jump to drop anchor.", NamedTextColor.AQUA));
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
        Set<String> known = new HashSet<>();
        for (String b : barriers) known.add(b.substring(0, b.indexOf('|')));
        boolean changed = false;
        for (int i = 0; i < blocks.length; i++) {
            Block b = blockOf(i);
            if (b.getType() != Material.BARRIER) continue;
            String at = b.getX() + "," + b.getY() + "," + b.getZ();
            if (known.contains(at)) continue;
            String was = type.cells.get(i).y() == 0 ? "minecraft:water" : "minecraft:air";
            if (anchored && blocks[i] != null) barriers.add(at + "|" + was);
            else b.setBlockData(Bukkit.createBlockData(was), false);
            changed = true;
        }
        if (changed) pl.saveNow();
    }

    /** What Bedrock players are sent while it's being built: the real block in each built cell. */
    List<BlockState> fakes(List<Integer> cells) {
        List<BlockState> out = new ArrayList<>();
        for (int i : cells) {
            if (blocks[i] == null) continue;
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
    String blockFor(int i, Material m) { return withProps(m, type.cells.get(i).props()).getAsString(); }

    void fill(int i, Material m) {
        if (blocks[i] != null) return;
        blocks[i] = blockFor(i, m);
        filled++;
        if (anchored) { putBarrier(i); liftPlayers(); }
        refreshCell(i);
        pl.showFakes(this, List.of(i));
        Block b = blockOf(i);
        world().playSound(b.getLocation().add(0.5, 0.5, 0.5), m.createBlockData().getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1f, 0.9f);
        if (filled >= blocks.length) finish();
        else if (filled % 4 == 0) refreshLabel();
        pl.dirty = true;
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
    void damage(double amount, Player by) {
        if (building || wrecked || amount <= 0) return;
        hp = Math.max(0, hp - amount);
        pl.dirty = true;
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
        wrecked = true;
        hp = 0;
        speed = 0; turnRate = 0;
        if (seats != null) for (ArmorStand s : seats) if (s != null && s.isValid()) s.eject();
        anchorStep = -1;
        if (!anchored) {
            float ty = Math.round(yaw / 90f) * 90f;
            double[] spot = snapSpot(ty);
            if (spot != null) {
                yaw = ((Math.round(ty) % 360) + 360) % 360; x = spot[0]; z = spot[1];
                sendTransforms(6);
                anchor();
            }
        }
        place();
        if (stand != null && stand.isValid()) stand.getEquipment().setHelmet(standItem());
        for (int i = 0; i < blocks.length; i++) if (type.cells.get(i).need() == ShipType.Need.WOOL) refreshCell(i);
        refreshLabel();
        Location c = center();
        world().playSound(c, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 1.2f, 0.7f);
        world().playSound(c, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.BLOCKS, 1.5f, 0.5f);
        world().spawnParticle(Particle.LARGE_SMOKE, c, 60, type.halfWidth, 1.5, 3, 0.02);
        String who = by != null ? " by " + by.getName() : "";
        for (Player p : world().getPlayers()) if (p.getLocation().distanceSquared(c) < 64 * 64)
            p.sendMessage(ChatColor.RED + "A " + type.title + " has been wrecked" + who + "!");
        Player o = Bukkit.getPlayer(owner);
        if (o != null && o.getLocation().distanceSquared(c) >= 64 * 64)
            o.sendMessage(ChatColor.RED + "Your " + type.title + " has been wrecked! Repair it with a Shipwright's Hammer.");
        pl.saveNow();
    }

    /** Hammer work. Returns true once the ship is back to full. */
    boolean repair(double amount) {
        hp = Math.min(maxHp(), hp + amount);
        pl.dirty = true;
        if (hp >= maxHp() && wrecked) {
            wrecked = false;
            for (int i = 0; i < blocks.length; i++) if (type.cells.get(i).need() == ShipType.Need.WOOL) refreshCell(i);
            place();
            if (stand != null && stand.isValid()) stand.getEquipment().setHelmet(standItem());
            Location c = center();
            world().playSound(c, Sound.BLOCK_ANVIL_USE, SoundCategory.BLOCKS, 1f, 1.2f);
            world().spawnParticle(Particle.HAPPY_VILLAGER, c.clone().add(0, 2, 0), 60, type.halfWidth, 2, 4, 0);
        }
        refreshLabel();
        return hp >= maxHp();
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
        bar.setTitle(ChatColor.AQUA + type.title + ChatColor.GRAY + "  Hull " + (int) Math.ceil(hp) + "/" + (int) maxHp());
        bar.setProgress(Math.max(0, Math.min(1, hp / maxHp())));
        bar.setColor(hp < maxHp() * 0.3 ? BarColor.RED : hp < maxHp() * 0.7 ? BarColor.YELLOW : BarColor.BLUE);
        Set<Player> want = new HashSet<>(riders);
        for (Player p : new ArrayList<>(bar.getPlayers())) if (!want.contains(p)) bar.removePlayer(p);
        for (Player p : want) if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
        Player cap = rider(0);
        if (cap != null && sailing() && !pl.repairing(cap)) {
            double ms = Math.abs(speed) * 20;
            cap.sendActionBar(Component.text(String.format("⚓ %.1f m/s", ms), NamedTextColor.AQUA)
                    .append(Component.text("   W/S sail · A/D steer · Sprint full sail · Jump anchor", NamedTextColor.GRAY)));
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
            hold = Bukkit.createInventory(h, type.cargo, type.title + "'s Hold");
            h.inv = hold;
            if (cargo != null) hold.setContents(java.util.Arrays.copyOf(cargo, type.cargo));
        }
        return hold;
    }

    ItemStack[] cargoNow() { return hold != null ? hold.getContents() : cargo; }
}
