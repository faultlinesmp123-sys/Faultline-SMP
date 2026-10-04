package net.faultlinesmp.bosses;

import net.faultlinesmp.bosses.FaultlineBosses.Pose;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
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
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

import static net.faultlinesmp.bosses.FaultlineBosses.*;

/**
 * THE LOST EXPLORER, the final boss, at the end of the white path in the void world (see Below.java for the way there).
 *
 * Right-click him to start. Four black pillars rise around the arena and invisible walls close it (nobody falls off).
 * He can't be hurt by weapons: hit him and he backhands you down to half a heart. The ONLY way to hurt him is to make
 * him CHARGE into a pillar: he's stunned, and while he's down, hit that pillar with a pickaxe (3 hits) and it falls on
 * him. Four pillars, four phases; the fourth pillar finishes him.
 *   Phase 1: Cleave, Sweep, Charge.   Phase 2: + Slam, and his black tiger (pounces).
 *   Phase 3: draws the greatsword: + Throw, Shadow Step.   Phase 4: faster, + Void Rain.
 * Every hit he (or the tiger) lands takes 40% or 55% of your max health (armor doesn't help): two or three and you're down.
 * Nobody dies here and nobody loses anything: you drop to your knees and can't move. When everyone is down he says
 * "You cannot defeat me... Get out of my sight.", kicks you all out of the void, and Swarm's hole closes behind you.
 */
final class Explorer implements Listener, CommandExecutor {

    static final String TAG = "faultline_explorer";
    static final float SCALE = 1.5f;
    static final float TIGER_SCALE = 1.7f;
    static final int PILLAR_H = 9;
    static final int[][] PILLARS = {{-7, -7}, {7, -7}, {7, 7}, {-7, 7}}; // offsets from the arena center (x, z)
    static final Material PILLAR_BODY = Material.POLISHED_BLACKSTONE_BRICKS, PILLAR_CAP = Material.CHISELED_POLISHED_BLACKSTONE;

    final FaultlineBosses pl;
    final Below below;
    final Random random;
    final NamespacedKey downKey;
    Fight fight;
    long respawnAt;

    Explorer(FaultlineBosses pl, Below below) {
        this.pl = pl;
        this.below = below;
        this.random = pl.random;
        downKey = new NamespacedKey(pl, "explorer_downed");
        Bukkit.getScheduler().runTaskLater(pl, () -> pl.bossPart("The Lost Explorer", "cleanup", this::cleanArena), 5L);
    }

    double c(String path, double def) { return pl.getConfig().getDouble("explorer." + path, def); }

    static boolean ours(Entity e) { return e.getScoreboardTags().contains(TAG); }

    /** While he's fighting, or gone for a while after losing, his statue isn't at the end of the path. */
    boolean blocksStatue() { return fight != null || System.currentTimeMillis() < respawnAt; }

    void tick() {
        if (fight != null) pl.bossPart("The Lost Explorer", "fight", fight::tick);
        if (fight != null && fight.over) fight = null;
    }

    void shutdown() {
        if (fight != null) fight.end(false);
        fight = null;
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (ours(e)) e.remove();
        cleanArena();
    }

    /** Starts the fight (right-clicking his statue). */
    void begin(World w, Player by) {
        if (fight != null) return;
        fight = new Fight(w, by);
    }

    static Vector center() { return new Vector(0.5, Below.PATH_Y + 1, Below.ARENA_Z + 0.5); }

    /** Leftover pillars/walls (a crash mid-fight) go back to the generated arena: air, and the light blocks. */
    void cleanArena() {
        World v = Bukkit.getWorld(below.voidName());
        if (v == null || fight != null) return;
        int cz = Below.ARENA_Z;
        for (int x = -21; x <= 21; x++) for (int z = cz - 21; z <= cz + 21; z++) for (int y = Below.PATH_Y + 1; y <= Below.PATH_Y + 1 + PILLAR_H; y++) {
            if (!v.isChunkLoaded(x >> 4, z >> 4)) continue;
            Block b = v.getBlockAt(x, y, z);
            Material m = b.getType();
            if (m == Material.BARRIER || m == PILLAR_BODY || m == PILLAR_CAP) b.setBlockData(generated(x, y, z), false);
        }
    }

    static BlockData generated(int x, int y, int z) {
        if (y == Below.PATH_Y + 1 && Below.isPath(x, z) && Below.lit(x, z)) {
            BlockData l = Material.LIGHT.createBlockData();
            if (l instanceof Levelled lv) lv.setLevel(15);
            return l;
        }
        return Material.AIR.createBlockData();
    }

    // =====================================================================================================
    //  the fight
    // =====================================================================================================
    enum St { INTRO, FIGHT, CRASH, STUNNED, PINNED, PHASE_UP, LOSS, DEFEAT }
    enum Move { NONE, WALK, CLEAVE, SWEEP, CHARGE_WINDUP, CHARGE, RECOVER, LEAP, AIR, SLAM, THROW, VANISH, AMBUSH, RAIN, COUNTER }

    final class Pillar {
        final int idx, cx, cz;
        final List<Block> blocks = new ArrayList<>();
        boolean standing, falling;
        int cracks;
        Pillar(int idx, int cx, int cz) { this.idx = idx; this.cx = cx; this.cz = cz; }
        Vector center() { return new Vector(cx + 0.5, Below.PATH_Y + 1, cz + 0.5); }
        boolean contains(Block b) { return blocks.contains(b); }
    }

    final class Fight {
        final World world;
        final Vector home = center();
        Vector pos;
        float yaw = 180;
        final ItemDisplay[] parts = new ItemDisplay[6];
        final ItemDisplay weapon;
        final Slime hitbox;
        final ArmorStand anchor;
        Pose shown = new Pose();
        St st = St.INTRO;
        Move move = Move.NONE;
        int t, mt, ticks, phase = 1, sinceCharge, gap = 40, counterT = -1;
        boolean over, blade;
        final Set<UUID> fighters = new LinkedHashSet<>(), downed = new HashSet<>(), hitThisMove = new HashSet<>();
        final Map<Block, BlockData> changed = new LinkedHashMap<>();
        final List<Pillar> pillars = new ArrayList<>();
        Pillar crashed;
        UUID target;
        Vector moveDir, aim, from, to;
        final List<Vector> rain = new ArrayList<>();
        ItemDisplay thrown;
        Vector thrownPos, thrownVel;
        boolean thrownBack;
        final Map<UUID, Long> counterCd = new HashMap<>();
        Tiger tiger;
        // the falling pillar
        final List<BlockDisplay> fallParts = new ArrayList<>();
        final List<Vector> fallOffsets = new ArrayList<>();
        Location fallPivot;
        Vector fallDir;
        int fallT = -1;

        Fight(World w, Player by) {
            world = w;
            pos = home.clone();
            Location at = pos.toLocation(w);
            String[] pieces = {"_leg_r", "_leg_l", "_body", "_arm_r", "_arm_l", "_head"};
            for (int i = 0; i < 6; i++) {
                parts[i] = pl.spawnDisplay(at, "explorer" + pieces[i], SCALE, 2, Display.Billboard.FIXED);
                parts[i].setViewRange(8f);
                parts[i].addScoreboardTag(TAG);
            }
            weapon = w.spawn(at, ItemDisplay.class, d -> {
                d.setItemStack(modelItem("explorer_axe"));
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND);
                d.setTeleportDuration(2); d.setInterpolationDuration(2); d.setViewRange(8f);
                d.setBrightness(new Display.Brightness(13, 13));
                d.setPersistent(false);
                d.addScoreboardTag(DISPLAY_TAG); d.addScoreboardTag(TAG);
            });
            hitbox = (Slime) w.spawnEntity(at, EntityType.SLIME, false);
            hitbox.setSize(2);
            pl.setupHitbox(hitbox, 1000, TAG, "The Lost Explorer");
            AttributeInstance hs = hitbox.getAttribute(Attribute.SCALE);
            if (hs != null) hs.setBaseValue(2.4);
            anchor = w.spawn(at, ArmorStand.class, a -> {
                a.setVisibleByDefault(false); a.setInvisible(true); a.setMarker(true); a.setGravity(false);
                a.setInvulnerable(true); a.setPersistent(false); a.addScoreboardTag(TAG);
            });
            pl.proxy(anchor, anchor, EntityType.WITHER_SKELETON, SCALE); // Bedrock players see a big wither skeleton
            for (Player p : w.getPlayers()) if (inArena(p, 19)) fighters.add(p.getUniqueId());
            if (by != null) fighters.add(by.getUniqueId());
            for (int i = 0; i < 4; i++) pillars.add(new Pillar(i, (int) Math.floor(home.getX()) + PILLARS[i][0], (int) Math.floor(home.getZ()) + PILLARS[i][1]));
        }

        boolean inArena(Player p, double r) {
            if (!p.getWorld().equals(world)) return false;
            Location l = p.getLocation();
            return Math.hypot(l.getX() - home.getX(), l.getZ() - home.getZ()) <= r && Math.abs(l.getY() - home.getY()) < 12;
        }

        List<Player> alive() {
            List<Player> out = new ArrayList<>();
            for (UUID id : fighters) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && !downed.contains(id) && survival(p) && inArena(p, 24)) out.add(p);
            }
            return out;
        }

        double spd() { return switch (phase) { case 1 -> 1.0; case 2 -> 1.15; case 3 -> 1.3; default -> 1.5; }; }

        void say(String text) {
            for (Player p : world.getPlayers())
                p.sendMessage(ChatColor.WHITE + "" + ChatColor.BOLD + "The Lost Explorer" + ChatColor.DARK_GRAY + " » " + ChatColor.GRAY + "" + ChatColor.ITALIC + text);
            world.playSound(pos.toLocation(world), Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 1.2f, 0.5f);
        }

        void hint(String text) {
            for (Player p : world.getPlayers()) p.sendActionBar(legacy(text));
        }

        // ------------------------------------------------------------------ every tick
        void tick() {
            ticks++; t++;
            for (Player p : world.getPlayers()) if (!fighters.contains(p.getUniqueId()) && inArena(p, 18.5) && st != St.LOSS && st != St.DEFEAT) fighters.add(p.getUniqueId());
            for (UUID id : downed) { // the fallen stay where they are, on their knees
                Player p = Bukkit.getPlayer(id);
                if (p != null && t % 10 == 0) p.getWorld().spawnParticle(Particle.ASH, p.getLocation().add(0, 1, 0), 6, 0.3, 0.4, 0.3, 0);
            }
            if (st != St.LOSS && st != St.DEFEAT && st != St.INTRO) {
                if (alive().isEmpty()) {
                    if (!downed.isEmpty()) startLoss();
                    else { end(false); return; } // everyone left: he waits again
                }
            }
            Pose p;
            switch (st) {
                case INTRO -> p = intro();
                case FIGHT -> p = fightTick();
                case CRASH -> {
                    p = ExplorerAnims.crash(t);
                    if (t >= 16) { st = St.STUNNED; t = 0; }
                }
                case STUNNED -> p = stunnedTick();
                case PINNED -> p = pinnedTick();
                case PHASE_UP -> p = phaseUpTick();
                case LOSS -> p = lossTick();
                case DEFEAT -> p = defeatTick();
                default -> p = ExplorerAnims.idle(ticks);
            }
            if (fallT >= 0) fallTick();
            if (thrown != null) thrownTick();
            if (tiger != null) tiger.tick();
            if (counterT >= 0) { p = ExplorerAnims.counter(counterT); if (++counterT > 12) counterT = -1; }
            render(p);
        }

        void render(Pose p) {
            if (over) return;
            Location root = pos.toLocation(world, yaw, 0);
            if (hitbox.isValid()) hitbox.teleport(root.clone().add(0, 0.05, 0));
            if (anchor.isValid()) anchor.teleport(root);
            shown = Pose.lerp(shown, p, 0.55f);
            pl.renderRig(parts, weapon, root, yaw, SCALE, shown, 90, 1.0f);
        }

        // ------------------------------------------------------------------ intro: he wakes, the pillars rise
        Pose intro() {
            if (t == 10) say("...");
            if (t == 35) say("You came all this way. And Swarm let you through.");
            if (t == 40) {
                placeWalls();
                world.playSound(home.toLocation(world), Sound.BLOCK_END_PORTAL_SPAWN, SoundCategory.HOSTILE, 1.4f, 0.5f);
            }
            if (t >= 40 && t <= 40 + PILLAR_H * 4 && (t - 40) % 4 == 0) for (Pillar pr : pillars) raise(pr, (t - 40) / 4);
            if (t == 70) say("Then show me.");
            if (t == 95) hint(ChatColor.GRAY + "Weapons won't touch him. " + ChatColor.WHITE + "Make him crash into a pillar" + ChatColor.GRAY + ", then hit it with a pickaxe.");
            Player look = nearest();
            if (look != null && t > 20) face(look.getLocation().toVector(), 4);
            if (t >= 110) { st = St.FIGHT; t = 0; move = Move.NONE; mt = 0; gap = 20; for (Pillar pr : pillars) pr.standing = true; }
            return ExplorerAnims.awaken(Math.min(t, 70));
        }

        void raise(Pillar pr, int layer) {
            if (layer >= PILLAR_H) return;
            int y = (int) home.getY() + layer;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                Block b = world.getBlockAt(pr.cx + dx, y, pr.cz + dz);
                for (Player p : world.getPlayers()) // nobody gets buried in a rising pillar
                    if (p.getLocation().getBlock().equals(b) || p.getLocation().getBlock().getRelative(0, 1, 0).equals(b)) {
                        Vector out = p.getLocation().toVector().subtract(pr.center()).setY(0);
                        if (out.lengthSquared() < 0.01) out = new Vector(1, 0, 0);
                        p.teleport(p.getLocation().add(out.normalize().multiply(2.2)));
                    }
                set(b, (layer == 0 || layer == PILLAR_H - 1 ? PILLAR_CAP : PILLAR_BODY).createBlockData());
                if (!pr.blocks.contains(b)) pr.blocks.add(b);
            }
            Location fx = new Location(world, pr.cx + 0.5, y + 0.5, pr.cz + 0.5);
            world.spawnParticle(Particle.BLOCK, fx, 20, 1, 0.3, 1, 0, PILLAR_BODY.createBlockData());
            world.playSound(fx, Sound.BLOCK_DEEPSLATE_BRICKS_PLACE, SoundCategory.BLOCKS, 1.2f, 0.6f);
        }

        /** Invisible walls all around the arena edge (and across the path), so nobody can fall into the void. */
        void placeWalls() {
            int cx = (int) Math.floor(home.getX()), cz = (int) Math.floor(home.getZ());
            for (int x = -21; x <= 21; x++) for (int z = -21; z <= 21; z++) {
                double d = Math.hypot(x, z);
                if (d < 18.6 || d >= 19.9) continue;
                for (int y = 0; y < 5; y++) {
                    Block b = world.getBlockAt(cx + x, (int) home.getY() + y, cz + z);
                    if (b.getType().isAir() || b.getType() == Material.LIGHT) set(b, Material.BARRIER.createBlockData());
                }
            }
        }

        void set(Block b, BlockData data) {
            changed.putIfAbsent(b, b.getBlockData());
            b.setBlockData(data, false);
        }

        void restoreBlocks() {
            List<Block> all = new ArrayList<>(changed.keySet());
            Collections.reverse(all);
            for (Block b : all) b.setBlockData(changed.get(b), false);
            changed.clear();
        }

        // ------------------------------------------------------------------ choosing and running moves
        Player nearest() { return nearestTo(pos); }

        Player nearestTo(Vector v) {
            Player best = null; double bd = Double.MAX_VALUE;
            for (Player p : alive()) { double d = p.getLocation().toVector().distanceSquared(v); if (d < bd) { bd = d; best = p; } }
            return best;
        }

        Player targetPlayer() {
            Player p = target == null ? null : Bukkit.getPlayer(target);
            if (p == null || downed.contains(target) || !alive().contains(p)) { p = nearest(); target = p == null ? null : p.getUniqueId(); }
            return p;
        }

        void face(Vector at, float maxStep) {
            Vector d = at.clone().subtract(pos).setY(0);
            if (d.lengthSquared() < 0.01) return;
            float want = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
            float diff = ((want - yaw) % 360 + 540) % 360 - 180;
            yaw += Math.max(-maxStep, Math.min(maxStep, diff));
        }

        Vector fwd() { return new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw))); }

        void clampToArena() {
            Vector d = pos.clone().subtract(home).setY(0);
            if (d.length() > 16.8) { d.normalize().multiply(16.8); pos.setX(home.getX() + d.getX()); pos.setZ(home.getZ() + d.getZ()); }
            for (Pillar pr : pillars) { // he walks around standing pillars, not through them
                if (!pr.standing) continue;
                Vector o = pos.clone().subtract(pr.center()).setY(0);
                if (o.length() < 2.3) { if (o.lengthSquared() < 0.01) o = new Vector(1, 0, 0); o.normalize().multiply(2.3); pos.setX(pr.center().getX() + o.getX()); pos.setZ(pr.center().getZ() + o.getZ()); }
            }
            pos.setY(home.getY());
        }

        void start(Move m) { move = m; mt = 0; hitThisMove.clear(); }

        Pose fightTick() {
            Player tg = targetPlayer();
            sinceCharge++;
            if (move == Move.NONE) {
                if (tg != null) face(tg.getLocation().toVector(), 6);
                if (--gap > 0) return ExplorerAnims.idle(ticks);
                if (tg == null) return ExplorerAnims.idle(ticks);
                pick(tg);
            }
            mt++;
            double tt = mt * (1 + (phase - 1) * 0.1); // later phases swing faster
            return switch (move) {
                case WALK -> walk(tg);
                case CLEAVE -> cleave(tg, tt);
                case SWEEP -> sweep(tt);
                case CHARGE_WINDUP -> chargeWindup(tg);
                case CHARGE -> charging();
                case RECOVER -> { if (mt >= 20) done(16); yield ExplorerAnims.idle(ticks); }
                case LEAP -> leap(tg);
                case AIR -> air();
                case SLAM -> slam();
                case THROW -> throwMove(tg, tt);
                case VANISH -> vanish(tg);
                case AMBUSH -> ambush(tt);
                case RAIN -> rainMove(tt);
                default -> { done(10); yield ExplorerAnims.idle(ticks); }
            };
        }

        void done(int pause) { move = Move.NONE; gap = (int) Math.max(6, pause / spd()); }

        void pick(Player tg) {
            double d = tg.getLocation().toVector().distance(pos);
            long standing = pillars.stream().filter(pr -> pr.standing).count();
            int chargeEvery = (int) (c("charge-every-seconds", 12) * 20 / spd());
            if (standing > 0 && (sinceCharge > chargeEvery || (d > 7 && random.nextDouble() < 0.45))) { target = tg.getUniqueId(); start(Move.CHARGE_WINDUP); return; }
            List<Move> pool = new ArrayList<>();
            if (d <= 5.5) { pool.add(Move.CLEAVE); pool.add(Move.CLEAVE); pool.add(Move.SWEEP); }
            if (phase >= 2 && d >= 5 && d <= 18) pool.add(Move.LEAP);
            if (phase >= 3 && blade && thrown == null && d >= 5 && d <= 20) pool.add(Move.THROW);
            if (phase >= 3) pool.add(Move.VANISH);
            if (phase >= 4) pool.add(Move.RAIN);
            if (pool.isEmpty()) { start(Move.WALK); return; }
            start(pool.get(random.nextInt(pool.size())));
            target = tg.getUniqueId();
        }

        // --- WALK: closes in, then picks again
        Pose walk(Player tg) {
            if (tg == null) { done(10); return ExplorerAnims.idle(ticks); }
            face(tg.getLocation().toVector(), 8);
            Vector d = tg.getLocation().toVector().subtract(pos).setY(0);
            if (d.length() < 4.5 || mt > 70) { move = Move.NONE; gap = 0; return ExplorerAnims.walk(ticks, 1); }
            pos.add(d.normalize().multiply(0.16 * spd()));
            clampToArena();
            return ExplorerAnims.walk(ticks, 1);
        }

        // --- CLEAVE: the axe comes down on whatever's in front of him
        Pose cleave(Player tg, double tt) {
            if (tt < 12 && tg != null) face(tg.getLocation().toVector(), 5);
            if (tt < 18 && mt % 3 == 0) cone(5.8, 40, Color.fromRGB(120, 0, 0));
            if (crossed(tt, 18)) {
                for (Player p : alive()) if (inCone(p, 5.8, 40)) hit(p, true, pos);
                Location fx = pos.clone().add(fwd().multiply(3.2)).toLocation(world);
                world.spawnParticle(Particle.EXPLOSION, fx, 3, 0.8, 0.1, 0.8, 0);
                world.spawnParticle(Particle.BLOCK, fx, 40, 1.5, 0.2, 1.5, 0, Material.WHITE_CONCRETE.createBlockData());
                world.playSound(fx, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1f, 0.6f);
                world.playSound(fx, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.HOSTILE, 1.4f, 0.6f);
            }
            if (tt >= 32) done(22);
            return ExplorerAnims.cleave((float) tt);
        }

        // --- SWEEP: a full spin with the axe at hip height
        Pose sweep(double tt) {
            if (tt < 18 && mt % 3 == 0) ring(4.8, Color.fromRGB(120, 0, 0));
            if (tt >= 18 && tt < 26) yaw += 45 * (float) (1 + (phase - 1) * 0.1);
            if (crossed(tt, 19)) {
                for (Player p : alive()) if (flatDist(p) <= 4.9) hit(p, false, pos);
                world.spawnParticle(Particle.SWEEP_ATTACK, pos.clone().add(new Vector(0, 1.2, 0)).toLocation(world), 12, 2.5, 0.2, 2.5, 0);
                world.playSound(pos.toLocation(world), Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 1.5f, 0.5f);
            }
            if (tt >= 36) done(22);
            return ExplorerAnims.sweep((float) tt);
        }

        // --- CHARGE: the move that matters. Lock on, a line on the floor, then a bull rush; into a pillar = stunned
        Pose chargeWindup(Player tg) {
            if (mt == 1) world.playSound(pos.toLocation(world), Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 1.4f, 0.5f);
            if (tg != null && mt < 16) { face(tg.getLocation().toVector(), 10); aim = tg.getLocation().toVector().subtract(pos).setY(0); }
            if (aim == null || aim.lengthSquared() < 0.01) aim = fwd();
            if (mt % 2 == 0) line(aim.clone().normalize(), 26, mt < 16 ? Color.fromRGB(120, 0, 0) : Color.fromRGB(230, 0, 0));
            if (mt >= 24) { moveDir = aim.clone().normalize(); start(Move.CHARGE); sinceCharge = 0; }
            return ExplorerAnims.chargeWindup(mt);
        }

        Pose charging() {
            double step = c("charge-speed", 0.95) * Math.min(1.3, spd());
            for (int sub = 0; sub < 2; sub++) { // two half-steps, so he can't skip over a pillar edge
                pos.add(moveDir.clone().multiply(step / 2));
                for (Pillar pr : pillars) {
                    if (!pr.standing) continue;
                    if (pos.clone().subtract(pr.center()).setY(0).length() < 2.1) { crash(pr); return ExplorerAnims.crash(0); }
                }
                for (Player p : alive()) if (!hitThisMove.contains(p.getUniqueId()) && flatDist(p) < 1.9) { hitThisMove.add(p.getUniqueId()); hit(p, true, pos); }
            }
            world.spawnParticle(Particle.BLOCK, pos.toLocation(world), 6, 0.4, 0.05, 0.4, 0, Material.WHITE_CONCRETE.createBlockData());
            if (mt % 4 == 0) world.playSound(pos.toLocation(world), Sound.BLOCK_DEEPSLATE_STEP, SoundCategory.HOSTILE, 1.5f, 0.6f);
            boolean wall = pos.clone().subtract(home).setY(0).length() > 16.6;
            if (wall || mt * step > 28) { clampToArena(); start(Move.RECOVER); world.playSound(pos.toLocation(world), Sound.ENTITY_IRON_GOLEM_DAMAGE, SoundCategory.HOSTILE, 1f, 0.5f); }
            return ExplorerAnims.charge(ticks);
        }

        void crash(Pillar pr) {
            Vector o = pos.clone().subtract(pr.center()).setY(0);
            if (o.lengthSquared() < 0.01) o = moveDir.clone().multiply(-1);
            o.normalize().multiply(2.4);
            pos = pr.center().add(o); pos.setY(home.getY());
            face(pr.center(), 360);
            crashed = pr;
            pr.cracks = 0;
            st = St.CRASH; t = 0; move = Move.NONE;
            Location fx = pr.center().add(new Vector(0, 2, 0)).toLocation(world);
            world.spawnParticle(Particle.EXPLOSION, fx, 4, 1, 1, 1, 0);
            world.spawnParticle(Particle.BLOCK, fx, 60, 1.2, 2, 1.2, 0, PILLAR_BODY.createBlockData());
            world.playSound(fx, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.HOSTILE, 1.6f, 0.4f);
            world.playSound(fx, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.HOSTILE, 1.6f, 0.5f);
            hint(ChatColor.WHITE + "" + ChatColor.BOLD + "HE'S STUNNED! " + ChatColor.GRAY + "Hit that pillar with a " + ChatColor.WHITE + "pickaxe" + ChatColor.GRAY + "!");
        }

        int stunTicks() { return (int) (Math.max(3, c("stun-seconds", 8) - (phase - 1) * c("stun-seconds-less-per-phase", 1)) * 20); }

        Pose stunnedTick() {
            if (t % 6 == 0) world.spawnParticle(Particle.CRIT, pos.clone().add(new Vector(0, 3.3, 0)).toLocation(world), 6, 0.5, 0.1, 0.5, 0.05);
            if (crashed != null && t % 4 == 0) {
                Location top = crashed.center().add(new Vector(0, PILLAR_H + 0.2, 0)).toLocation(world);
                world.spawnParticle(Particle.END_ROD, top, 2, 0.6, 0.1, 0.6, 0.01);
            }
            if (fallT >= 0) return ExplorerAnims.stunned(ticks); // a pillar is coming down on him
            if (t >= stunTicks()) { // too slow
                st = St.FIGHT; t = 0; crashed = null; done(20);
                world.playSound(pos.toLocation(world), Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 1.2f, 0.7f);
                hint(ChatColor.GRAY + "He's back on his feet.");
            }
            return ExplorerAnims.stunned(ticks);
        }

        /** A pickaxe hit on the pillar he's stunned against. */
        void pickaxe(Player p, Block b) {
            if (st != St.STUNNED || crashed == null || !crashed.contains(b) || fallT >= 0) return;
            crashed.cracks++;
            int need = (int) c("pickaxe-hits", 3);
            world.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 25, 0.5, 0.5, 0.5, 0, PILLAR_BODY.createBlockData());
            world.playSound(b.getLocation(), Sound.BLOCK_DEEPSLATE_BRICKS_BREAK, SoundCategory.BLOCKS, 1.4f, 0.6f);
            if (crashed.cracks < need) { hint(ChatColor.WHITE + "The pillar cracks... " + ChatColor.GRAY + crashed.cracks + "/" + need); return; }
            topple(crashed);
        }

        /** The pillar tips over onto him (block displays rotating about its base edge). */
        void topple(Pillar pr) {
            pr.standing = false; pr.falling = true;
            fallDir = pos.clone().subtract(pr.center()).setY(0);
            if (fallDir.lengthSquared() < 0.01) fallDir = new Vector(1, 0, 0);
            fallDir.normalize();
            Vector pivot = pr.center().add(fallDir.clone().multiply(1.5));
            fallPivot = pivot.toLocation(world);
            fallParts.clear(); fallOffsets.clear();
            for (Block b : pr.blocks) {
                BlockData data = b.getBlockData();
                Vector off = new Vector(b.getX(), b.getY(), b.getZ()).subtract(pivot);
                BlockDisplay d = world.spawn(fallPivot, BlockDisplay.class, x -> {
                    x.setBlock(data);
                    x.setPersistent(false);
                    x.setTeleportDuration(0);
                    x.setInterpolationDuration(2);
                    x.setBrightness(new Display.Brightness(12, 12));
                    x.addScoreboardTag(TAG);
                    x.setTransformation(new Transformation(new Vector3f((float) off.getX(), (float) off.getY(), (float) off.getZ()),
                            new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf()));
                });
                fallParts.add(d); fallOffsets.add(off);
                Block orig = b;
                BlockData was = changed.remove(orig);
                orig.setBlockData(was != null ? was : Material.AIR.createBlockData(), false);
            }
            fallT = 0;
            world.playSound(fallPivot, Sound.BLOCK_GRINDSTONE_USE, SoundCategory.BLOCKS, 1.6f, 0.4f);
            hint(ChatColor.WHITE + "" + ChatColor.BOLD + "TIMBER!");
        }

        void fallTick() {
            fallT++;
            int dur = 18;
            float f = Math.min(1f, fallT / (float) dur);
            float ang = (float) Math.toRadians(90 * f * f);
            Vector3f axis = new Vector3f((float) fallDir.getZ(), 0, (float) -fallDir.getX());
            Quaternionf q = new Quaternionf(new AxisAngle4f(ang, axis.normalize()));
            if (fallT % 2 == 0 || f >= 1) {
                for (int i = 0; i < fallParts.size(); i++) {
                    BlockDisplay d = fallParts.get(i);
                    if (!d.isValid()) continue;
                    Vector o = fallOffsets.get(i);
                    Vector3f tr = q.transform(new Vector3f((float) o.getX(), (float) o.getY(), (float) o.getZ()));
                    d.setInterpolationDelay(0);
                    d.setTransformation(new Transformation(tr, new Quaternionf(q), new Vector3f(1, 1, 1), new Quaternionf()));
                }
            }
            if (f >= 1) {
                fallT = -1;
                impact();
                Bukkit.getScheduler().runTaskLater(pl, () -> { for (BlockDisplay d : fallParts) if (d.isValid()) d.remove(); fallParts.clear(); }, 12L);
            }
        }

        void impact() {
            Location at = pos.toLocation(world).add(0, 1, 0);
            world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
            for (int i = 2; i <= PILLAR_H; i++)
                world.spawnParticle(Particle.BLOCK, fallPivot.clone().add(fallDir.clone().multiply(i)).add(0, 0.5, 0), 25, 0.8, 0.3, 0.8, 0, PILLAR_BODY.createBlockData());
            world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2f, 0.5f);
            world.playSound(at, Sound.ENTITY_WARDEN_DEATH, SoundCategory.HOSTILE, 1f, 0.6f);
            st = St.PINNED; t = 0;
            if (crashed != null) crashed.falling = false;
            crashed = null;
            for (Player p : alive()) { // don't stand where it lands
                Vector rel = p.getLocation().toVector().subtract(fallPivot.toVector()).setY(0);
                double along = rel.dot(fallDir), side = rel.clone().subtract(fallDir.clone().multiply(along)).length();
                if (along > 1.5 && along < PILLAR_H + 0.5 && side < 1.8) hit(p, true, fallPivot.toVector());
            }
        }

        Pose pinnedTick() {
            if (t == 1 && phase >= 4) { startDefeat(); return ExplorerAnims.pinned(0); }
            if (t >= 30) {
                phase++;
                st = St.PHASE_UP; t = 0;
            }
            return ExplorerAnims.pinned(t);
        }

        Pose phaseUpTick() {
            if (t == 2) world.playSound(pos.toLocation(world), Sound.ENTITY_WARDEN_ROAR, SoundCategory.HOSTILE, 2f, 0.5f);
            if (t == 6) switch (phase) {
                case 2 -> say("Hm. Clever. Come, then.");
                case 3 -> say("Enough.");
                default -> say("I was lost down here long before any of you were born.");
            }
            if (t == 20 && phase == 2) { tiger = new Tiger(world); hint(ChatColor.GRAY + "Something huge pads out of the dark behind him..."); }
            if (t == 30 && phase == 3) { blade = true; weapon.setItemStack(modelItem("explorer_blade")); world.playSound(pos.toLocation(world), Sound.ITEM_TRIDENT_RETURN, SoundCategory.HOSTILE, 1.5f, 0.5f); }
            if (t >= 16 && t < 48 && t % 4 == 0) world.spawnParticle(Particle.SQUID_INK, pos.clone().add(new Vector(0, 2.5, 0)).toLocation(world), 20, 1.2, 1.2, 1.2, 0.05);
            if (t >= 60) { st = St.FIGHT; t = 0; done(20); sinceCharge = 0; }
            return ExplorerAnims.roar(t);
        }

        // --- LEAP + SLAM (phase 2+): jumps onto you; the landing cracks the floor in a ring you have to jump over
        Pose leap(Player tg) {
            if (tg != null && mt <= 12) { face(tg.getLocation().toVector(), 10); to = tg.getLocation().toVector().setY(home.getY()); }
            if (to != null && mt % 3 == 0) circle(to, 3.5, Color.fromRGB(120, 0, 0));
            if (mt >= 20) { from = pos.clone(); start(Move.AIR); world.playSound(pos.toLocation(world), Sound.ENTITY_RAVAGER_ATTACK, SoundCategory.HOSTILE, 1.5f, 0.5f); }
            return ExplorerAnims.leap(mt);
        }

        Pose air() {
            int dur = 16;
            double f = Math.min(1, mt / (double) dur);
            Vector p = from.clone().add(to.clone().subtract(from).multiply(f));
            p.setY(home.getY() + Math.sin(f * Math.PI) * 5);
            pos = p;
            if (to != null && mt % 3 == 0) circle(to, 3.5, Color.fromRGB(230, 0, 0));
            if (mt >= dur) { pos.setY(home.getY()); clampToArena(); start(Move.SLAM); landed(); }
            return ExplorerAnims.airborne(mt);
        }

        int ringT;
        void landed() {
            for (Player p : alive()) if (flatDist(p) <= 3.6) hit(p, true, pos);
            world.spawnParticle(Particle.EXPLOSION, pos.toLocation(world), 5, 1.5, 0.1, 1.5, 0);
            world.playSound(pos.toLocation(world), Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.HOSTILE, 2f, 0.5f);
            ringT = 0;
        }

        Pose slam() {
            ringT++;
            double r = 3.5 + ringT * 0.55;
            if (ringT <= 12) {
                for (int i = 0; i < 40; i++) {
                    double a = i * Math.PI * 2 / 40;
                    world.spawnParticle(Particle.BLOCK, pos.clone().add(new Vector(Math.cos(a) * r, 0.15, Math.sin(a) * r)).toLocation(world), 1, 0, 0, 0, 0, Material.WHITE_CONCRETE.createBlockData());
                }
                for (Player p : alive()) {
                    double d = flatDist(p);
                    if (Math.abs(d - r) < 0.7 && p.isOnGround() && hitThisMove.add(p.getUniqueId())) hit(p, false, pos);
                }
            }
            if (mt >= 24) done(24);
            return ExplorerAnims.slam(mt);
        }

        // --- THROW (phase 3+): the greatsword spins out and comes back
        Pose throwMove(Player tg, double tt) {
            if (tg != null && tt < 10) face(tg.getLocation().toVector(), 8);
            if (crossed(tt, 12)) {
                Vector dir = tg != null ? tg.getEyeLocation().toVector().subtract(pos.clone().add(new Vector(0, 2, 0))).setY(0) : fwd();
                if (dir.lengthSquared() < 0.01) dir = fwd();
                thrownPos = pos.clone().add(new Vector(0, 1.8, 0));
                thrownVel = dir.normalize().multiply(0.9 * Math.min(1.2, spd()));
                thrownBack = false;
                hitThisMove.clear();
                thrown = world.spawn(thrownPos.toLocation(world), ItemDisplay.class, d -> {
                    d.setItemStack(modelItem("explorer_blade"));
                    d.setTeleportDuration(1); d.setInterpolationDuration(1); d.setViewRange(8f);
                    d.setBrightness(new Display.Brightness(15, 15));
                    d.setPersistent(false);
                    d.addScoreboardTag(TAG);
                });
                weapon.setItemStack(new ItemStack(Material.AIR));
                world.playSound(thrownPos.toLocation(world), Sound.ITEM_TRIDENT_THROW, SoundCategory.HOSTILE, 1.5f, 0.5f);
            }
            if (tt >= 22) done(14);
            return ExplorerAnims.throwBlade((float) tt);
        }

        void thrownTick() {
            if (!thrown.isValid()) { thrown = null; weapon.setItemStack(modelItem(blade ? "explorer_blade" : "explorer_axe")); return; }
            Vector hand = pos.clone().add(new Vector(0, 1.8, 0));
            if (!thrownBack && (thrownPos.distance(hand) > 22 || thrownPos.clone().subtract(home).setY(0).length() > 18)) { thrownBack = true; hitThisMove.clear(); }
            if (thrownBack) {
                Vector back = hand.clone().subtract(thrownPos);
                if (back.length() < 1.2) { thrown.remove(); thrown = null; weapon.setItemStack(modelItem(blade ? "explorer_blade" : "explorer_axe")); world.playSound(hand.toLocation(world), Sound.ITEM_TRIDENT_RETURN, SoundCategory.HOSTILE, 1.2f, 0.7f); return; }
                thrownVel = back.normalize().multiply(1.0);
            }
            thrownPos.add(thrownVel);
            Location l = thrownPos.toLocation(world);
            thrown.teleport(l);
            float spin = (float) (ticks * 0.9);
            thrown.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateY((float) Math.atan2(thrownVel.getX(), thrownVel.getZ())).rotateX((float) Math.PI / 2).rotateZ(spin),
                    new Vector3f(1.2f, 1.2f, 1.2f), new Quaternionf()));
            world.spawnParticle(Particle.WHITE_ASH, l, 6, 0.5, 0.2, 0.5, 0);
            if (ticks % 4 == 0) world.playSound(l, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 0.8f, 1.5f);
            for (Player p : alive()) if (p.getLocation().toVector().add(new Vector(0, 1, 0)).distance(thrownPos) < 1.6 && hitThisMove.add(p.getUniqueId())) hit(p, false, thrownPos);
        }

        // --- SHADOW STEP (phase 3+): sinks into the dark and comes up behind you
        Pose vanish(Player tg) {
            if (mt == 1) world.playSound(pos.toLocation(world), Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 1.2f, 0.5f);
            if (mt == 4 && tg != null) {
                Vector back = Vendetta.flatDir(tg.getLocation()).multiply(-2.2);
                to = tg.getLocation().toVector().add(back).setY(home.getY());
                Vector d = to.clone().subtract(home).setY(0);
                if (d.length() > 16.5) to = home.clone().add(d.normalize().multiply(16.5));
            }
            if (mt >= 4 && to != null && mt % 2 == 0) world.spawnParticle(Particle.SQUID_INK, to.toLocation(world).add(0, 1, 0), 8, 0.4, 0.8, 0.4, 0.02);
            world.spawnParticle(Particle.LARGE_SMOKE, pos.toLocation(world).add(0, 1.5, 0), 4, 0.6, 1, 0.6, 0.01);
            if (mt >= 14) {
                if (to != null) { pos = to.clone(); clampToArena(); }
                if (tg != null) face(tg.getLocation().toVector(), 360);
                start(Move.AMBUSH);
                world.playSound(pos.toLocation(world), Sound.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.HOSTILE, 1f, 1.4f);
            }
            return ExplorerAnims.vanish(mt);
        }

        Pose ambush(double tt) {
            if (crossed(tt, 7)) {
                for (Player p : alive()) if (inCone(p, 4.2, 60)) hit(p, false, pos);
                world.spawnParticle(Particle.SWEEP_ATTACK, pos.clone().add(fwd().multiply(2)).add(new Vector(0, 1.5, 0)).toLocation(world), 6, 1, 0.3, 1, 0);
                world.playSound(pos.toLocation(world), Sound.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.HOSTILE, 1.5f, 0.5f);
            }
            if (tt >= 18) done(20);
            return ExplorerAnims.ambush((float) tt);
        }

        // --- VOID RAIN (phase 4): white light falls on marked circles
        Pose rainMove(double tt) {
            if (mt == 12) {
                rain.clear();
                for (Player p : alive()) for (int i = 0; i < 2; i++) {
                    Vector v = p.getLocation().toVector().add(new Vector(random.nextGaussian() * 1.5, 0, random.nextGaussian() * 1.5)).setY(home.getY());
                    if (v.clone().subtract(home).setY(0).length() < 17) rain.add(v);
                }
                world.playSound(pos.toLocation(world), Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.HOSTILE, 1.5f, 0.5f);
            }
            if (mt > 12 && mt < 36 && mt % 3 == 0) for (Vector v : rain) circle(v, 2, Color.fromRGB(120 + (mt - 12) * 5, 0, 0));
            if (mt == 36) {
                for (Vector v : rain) {
                    for (int y = 0; y < 14; y++) world.spawnParticle(Particle.END_ROD, v.toLocation(world).add(0, y, 0), 3, 0.3, 0.3, 0.3, 0.01);
                    world.spawnParticle(Particle.EXPLOSION, v.toLocation(world), 2, 0.6, 0.1, 0.6, 0);
                    for (Player p : alive()) if (p.getLocation().toVector().setY(v.getY()).distance(v) <= 2.1 && hitThisMove.add(p.getUniqueId())) hit(p, true, v);
                }
                world.playSound(pos.toLocation(world), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.HOSTILE, 1.4f, 0.7f);
            }
            if (mt >= 40) done(24);
            return ExplorerAnims.callDown(mt);
        }

        // ------------------------------------------------------------------ telegraphs (dark red on the white floor)
        void dust(Vector v, Color col) { world.spawnParticle(Particle.DUST, v.toLocation(world).add(0, 0.12, 0), 1, 0.05, 0, 0.05, 0, new Particle.DustOptions(col, 1.6f)); }

        void cone(double range, double halfDeg, Color col) {
            for (double r = 1.5; r <= range; r += 0.8) for (double a = -halfDeg; a <= halfDeg; a += 14) {
                double rad = Math.toRadians(yaw + a);
                dust(pos.clone().add(new Vector(-Math.sin(rad) * r, 0, Math.cos(rad) * r)), col);
            }
        }

        void ring(double r, Color col) {
            for (int i = 0; i < 36; i++) { double a = i * Math.PI * 2 / 36; dust(pos.clone().add(new Vector(Math.cos(a) * r, 0, Math.sin(a) * r)), col); }
        }

        void circle(Vector at, double r, Color col) {
            for (int i = 0; i < 28; i++) { double a = i * Math.PI * 2 / 28; dust(at.clone().add(new Vector(Math.cos(a) * r, 0, Math.sin(a) * r)), col); }
        }

        void line(Vector dir, double len, Color col) {
            Vector side = new Vector(-dir.getZ(), 0, dir.getX()).multiply(1.2);
            for (double d = 1; d <= len; d += 1.2) {
                Vector c = pos.clone().add(dir.clone().multiply(d));
                if (c.clone().subtract(home).setY(0).length() > 18) break;
                dust(c.clone().add(side), col); dust(c.clone().subtract(side), col);
            }
        }

        boolean crossed(double tt, double frame) { double prev = (mt - 1) * (1 + (phase - 1) * 0.1); return prev < frame && tt >= frame; }

        double flatDist(Player p) { return Math.hypot(p.getLocation().getX() - pos.getX(), p.getLocation().getZ() - pos.getZ()); }

        boolean inCone(Player p, double range, double halfDeg) {
            Vector to = p.getLocation().toVector().subtract(pos).setY(0);
            double d = to.length();
            if (d > range) return false;
            if (d < 1.2) return true;
            return Math.toDegrees(fwd().angle(to)) <= halfDeg;
        }

        // ------------------------------------------------------------------ damage: always 2-3 hits, never a death
        void hit(Player p, boolean heavy, Vector from) {
            if (downed.contains(p.getUniqueId()) || !survival(p)) return;
            AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
            double hp = max == null ? 20 : max.getValue();
            double dmg = hp * (heavy ? c("damage.heavy", 0.55) : c("damage.light", 0.40));
            Vector push = p.getLocation().toVector().subtract(from).setY(0);
            if (push.lengthSquared() < 0.01) push = fwd();
            p.damage(dmg, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.MAGIC).withCausingEntity(hitbox).withDirectEntity(hitbox).build());
            if (!downed.contains(p.getUniqueId())) p.setVelocity(push.normalize().multiply(heavy ? 1.1 : 0.7).setY(heavy ? 0.5 : 0.35));
            p.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, p.getLocation().add(0, 1, 0), 6, 0.3, 0.4, 0.3, 0);
        }

        void down(Player p) {
            if (!downed.add(p.getUniqueId())) return;
            p.setVelocity(new Vector());
            AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
            p.setHealth(Math.min(max == null ? 20 : max.getValue(), 2));
            for (Attribute a : List.of(Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH)) {
                AttributeInstance ai = p.getAttribute(a);
                if (ai != null && ai.getModifier(downKey) == null) ai.addTransientModifier(new AttributeModifier(downKey, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
            }
            p.setPose(org.bukkit.entity.Pose.SNEAKING, true); // down on one knee
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 1f, 0.6f);
            p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BELL_RESONATE, SoundCategory.PLAYERS, 1f, 0.5f);
            p.sendActionBar(legacy(ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "You fall to your knees. You can't get up."));
            for (Player o : world.getPlayers()) if (!o.equals(p)) o.sendMessage(ChatColor.DARK_GRAY + p.getName() + " has fallen.");
        }

        void restore(Player p) {
            downed.remove(p.getUniqueId());
            for (Attribute a : List.of(Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH)) {
                AttributeInstance ai = p.getAttribute(a);
                if (ai != null && ai.getModifier(downKey) != null) ai.removeModifier(downKey);
            }
            p.setPose(org.bukkit.entity.Pose.STANDING, false);
            AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
            if (max != null && !p.isDead()) p.setHealth(max.getValue());
            p.setFireTicks(0);
        }

        // ------------------------------------------------------------------ losing: "You cannot defeat me..."
        void startLoss() {
            st = St.LOSS; t = 0; move = Move.NONE;
            if (thrown != null && thrown.isValid()) thrown.remove();
            thrown = null;
            weapon.setItemStack(modelItem(blade ? "explorer_blade" : "explorer_axe"));
        }

        Pose lossTick() {
            UUID any = downed.isEmpty() ? null : downed.iterator().next();
            Player look = any == null ? null : Bukkit.getPlayer(any);
            if (look != null && t < 60) {
                face(look.getLocation().toVector(), 4);
                Vector d = look.getLocation().toVector().subtract(pos).setY(0);
                if (d.length() > 3.5) { pos.add(d.normalize().multiply(0.1)); clampToArena(); return ExplorerAnims.walk(ticks, 0.6f); }
            }
            if (t == 20) say("You cannot defeat me...");
            if (t == 70) say("Get out of my sight.");
            if (t == 100) { // the kick
                world.playSound(pos.toLocation(world), Sound.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.HOSTILE, 1.5f, 0.5f);
                for (Player p : world.getPlayers()) {
                    if (downed.contains(p.getUniqueId())) restore(p);
                    Vector away = p.getLocation().toVector().subtract(pos).setY(0);
                    if (away.lengthSquared() < 0.01) away = fwd();
                    p.setVelocity(away.normalize().multiply(1.6).setY(0.8));
                }
            }
            if (t == 108) {
                for (Player p : new ArrayList<>(world.getPlayers())) {
                    below.paid.add(p.getUniqueId()); // Swarm takes them back down without another Fist
                    below.sendBack(p);
                    p.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "The void spits you out. The way down has closed. You'll have to find Swarm again.");
                }
                below.dirty = true;
                below.sealNow();
                end(false);
                return ExplorerAnims.loom(ticks);
            }
            return t < 70 ? ExplorerAnims.loom(ticks) : ExplorerAnims.dismiss(t - 70);
        }

        // ------------------------------------------------------------------ winning
        void startDefeat() {
            st = St.DEFEAT; t = 0;
            if (tiger != null) tiger.leaving = true;
        }

        Pose defeatTick() {
            if (t == 20) say("...");
            if (t == 50) say("So. You found the way after all.");
            if (t == 85) say("Go on. It's yours now.");
            if (t == 110) {
                for (int i = 0; i < 6; i++) if (parts[i].isValid()) world.spawnParticle(Particle.ASH, parts[i].getLocation(), 60, 0.6, 0.8, 0.6, 0);
                world.spawnParticle(Particle.WHITE_ASH, pos.toLocation(world).add(0, 2, 0), 200, 1.2, 1.8, 1.2, 0.02);
                world.playSound(pos.toLocation(world), Sound.ENTITY_WARDEN_DEATH, SoundCategory.HOSTILE, 2f, 0.4f);
                respawnAt = System.currentTimeMillis() + (long) (c("respawn-minutes", 30) * 60_000);
                try { rewards(); } finally { end(true); }
                return ExplorerAnims.defeat(90);
            }
            return ExplorerAnims.defeat(Math.min(t, 90));
        }

        void rewards() {
            List<String> names = new ArrayList<>();
            for (UUID id : fighters) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.getWorld().equals(world)) continue;
                if (downed.contains(id)) restore(p);
                names.add(p.getName());
                try { reward(p); } catch (RuntimeException ex) { pl.getLogger().log(java.util.logging.Level.WARNING, "[The Lost Explorer] couldn't give " + p.getName() + " their rewards", ex); }
            }
            if (!names.isEmpty()) Bukkit.broadcastMessage(ChatColor.WHITE + "" + ChatColor.BOLD + String.join(", ", names) + ChatColor.GRAY + " defeated " + ChatColor.WHITE + "" + ChatColor.BOLD + "The Lost Explorer" + ChatColor.GRAY + "!");
        }

        void reward(Player p) {
            pl.console("index discover " + p.getName() + " the_lost_explorer", p);
            p.sendMessage(ChatColor.WHITE + "" + ChatColor.BOLD + "The Lost Explorer is defeated. " + ChatColor.GRAY + "The rift at the start of the path takes you home.");
            Location at = p.getLocation();
            int xp = (int) c("rewards.xp", 3000);
            for (int left = xp; left > 0; ) { int n = Math.min(left, 100); left -= n; world.spawn(at, org.bukkit.entity.ExperienceOrb.class).setExperience(n); }
            int bags = (int) c("rewards.mythic-bags", 5);
            if (bags > 0) pl.console("givemythicbag " + bags + " " + p.getName(), p);
            int dia = (int) c("rewards.diamonds", 32);
            if (dia > 0) pl.dropLocked(at, p, new ItemStack(Material.DIAMOND, dia));
        }

        // ------------------------------------------------------------------ cleanup
        void end(boolean won) {
            if (over) return;
            over = true;
            for (UUID id : new ArrayList<>(downed)) { Player p = Bukkit.getPlayer(id); if (p != null) restore(p); }
            downed.clear();
            for (ItemDisplay d : parts) if (d != null && d.isValid()) d.remove();
            if (weapon.isValid()) weapon.remove();
            if (hitbox.isValid()) hitbox.remove();
            if (anchor.isValid()) anchor.remove();
            if (thrown != null && thrown.isValid()) thrown.remove();
            for (BlockDisplay d : fallParts) if (d.isValid()) d.remove();
            if (tiger != null) tiger.remove();
            if (won) Bukkit.getScheduler().runTaskLater(pl, this::sinkPillars, 100L);
            else sinkPillars();
        }

        void sinkPillars() {
            for (Pillar pr : pillars) if (!pr.blocks.isEmpty())
                world.spawnParticle(Particle.BLOCK, pr.center().add(new Vector(0, 2, 0)).toLocation(world), 40, 1, 2, 1, 0, PILLAR_BODY.createBlockData());
            restoreBlocks();
        }
    }

    // =====================================================================================================
    //  the black tiger (phase 2+): sits behind him, roars, pounces
    // =====================================================================================================
    static final float[][] TIGER_JOINT = {{0, 0, 0}, {0, 19.5f, 9.0f}, {0, 15.6f, 13.6f}, {0, 1.5f, -9.0f}}; // body, head, jaw, tail (px)

    final class Tiger {
        final World world;
        final Vector seat;
        Vector pos, from, to;
        float yaw = 180;
        final ItemDisplay[] parts = new ItemDisplay[4];
        final ArmorStand anchor;
        int t, state, cooldown = 120; // 0 sit, 1 roar, 2 crouch, 3 leap, 4 land, 5 leap back
        boolean leaving;
        final Set<UUID> hit = new HashSet<>();

        Tiger(World w) {
            world = w;
            seat = center().add(new Vector(0, 0, 13.5));
            from = seat.clone().add(new Vector(0, 0, 10)); // bounds in from the dark
            pos = from.clone();
            to = seat.clone();
            String[] names = {"explorer_tiger_body", "explorer_tiger_head", "explorer_tiger_jaw", "explorer_tiger_tail"};
            for (int i = 0; i < 4; i++) {
                parts[i] = pl.spawnDisplay(pos.toLocation(w), names[i], TIGER_SCALE, 2, Display.Billboard.FIXED);
                parts[i].setViewRange(8f);
                parts[i].addScoreboardTag(TAG);
            }
            anchor = w.spawn(pos.toLocation(w), ArmorStand.class, a -> {
                a.setVisibleByDefault(false); a.setInvisible(true); a.setMarker(true); a.setGravity(false);
                a.setInvulnerable(true); a.setPersistent(false); a.addScoreboardTag(TAG);
            });
            pl.proxy(anchor, anchor, EntityType.RAVAGER, 1.0);
            state = 5; t = 0; // arrives with a leap onto its seat
            world.playSound(seat.toLocation(w), Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 2f, 0.6f);
        }

        void tick() {
            t++;
            Fight f = fight;
            float[] pose;
            switch (state) {
                case 1 -> { // roar
                    pose = ExplorerAnims.tigerRoar(t);
                    if (t == 2) world.playSound(pos.toLocation(world), Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 2f, 0.7f);
                    if (t >= 20) { state = 2; t = 0; }
                }
                case 2 -> { // crouch, marking where it'll land
                    pose = ExplorerAnims.tigerCrouch(t);
                    Player tg = f == null ? null : f.nearestTo(pos);
                    if (tg != null && t <= 10) { to = tg.getLocation().toVector().setY(seat.getY()); faceTo(to); }
                    if (to != null && f != null && t % 3 == 0) f.circle(to, 2.6, Color.fromRGB(150, 0, 0));
                    if (t >= 18) { from = pos.clone(); state = 3; t = 0; hit.clear(); }
                }
                case 3, 5 -> { // in the air (3 = at you, 5 = back to its seat)
                    pose = ExplorerAnims.tigerLeap(t);
                    int dur = 16;
                    double k = Math.min(1, t / (double) dur);
                    Vector p = from.clone().add(to.clone().subtract(from).multiply(k));
                    p.setY(seat.getY() + Math.sin(k * Math.PI) * 4);
                    pos = p;
                    if (state == 3 && f != null && t % 3 == 0) f.circle(to, 2.6, Color.fromRGB(230, 0, 0));
                    if (t >= dur) {
                        pos = to.clone();
                        if (state == 3) {
                            if (f != null) for (Player pl2 : f.alive()) if (pl2.getLocation().toVector().setY(pos.getY()).distance(pos) <= 2.6 && hit.add(pl2.getUniqueId())) f.hit(pl2, false, pos);
                            world.spawnParticle(Particle.BLOCK, pos.toLocation(world), 30, 1, 0.1, 1, 0, Material.WHITE_CONCRETE.createBlockData());
                            world.playSound(pos.toLocation(world), Sound.ENTITY_RAVAGER_ATTACK, SoundCategory.HOSTILE, 1.5f, 0.7f);
                            state = 4; t = 0;
                        } else { state = 0; t = 0; yaw = 180; cooldown = nextCooldown(); }
                    }
                }
                case 4 -> { // landed: a moment, then back to its seat
                    pose = ExplorerAnims.tigerSit(t);
                    if (t >= 24) { from = pos.clone(); to = seat.clone(); faceTo(to); state = 5; t = 0; }
                }
                default -> { // sitting behind him, watching
                    pose = ExplorerAnims.tigerSit(t);
                    if (leaving) { remove(); return; }
                    if (f != null && f.st == St.FIGHT && --cooldown <= 0 && f.nearestTo(pos) != null) { state = 1; t = 0; }
                }
            }
            render(pose);
        }

        int nextCooldown() { Fight f = fight; return (int) (c("tiger-pounce-seconds", 12) * 20 / (f == null ? 1 : f.spd())); }

        void faceTo(Vector at) {
            Vector d = at.clone().subtract(pos).setY(0);
            if (d.lengthSquared() > 0.01) yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        }

        /** {bodyPitch, headPitch, headYaw, jawOpen, tailSway} -> the four model pieces. */
        void render(float[] p) {
            Location root = pos.toLocation(world);
            if (anchor.isValid()) { Location a = root.clone(); a.setYaw(yaw); anchor.teleport(a); }
            float px = TIGER_SCALE / 16f;
            Quaternionf qYaw = new Quaternionf().rotateY((float) -Math.toRadians(yaw));
            Quaternionf flip = pl.facing(0);
            Quaternionf qBody = new Quaternionf(qYaw).rotateX((float) Math.toRadians(p[0]));
            Quaternionf qHead = new Quaternionf(qBody).rotateY((float) Math.toRadians(p[2])).rotateX((float) Math.toRadians(p[1]));
            Quaternionf qJaw = new Quaternionf(qHead).rotateX((float) Math.toRadians(p[3]));
            Quaternionf qTail = new Quaternionf(qBody).rotateY((float) Math.toRadians(p[4]));
            Vector3f head = qBody.transform(new Vector3f(TIGER_JOINT[1][0], TIGER_JOINT[1][1], TIGER_JOINT[1][2]).mul(px));
            Vector3f jaw = new Vector3f(head).add(qHead.transform(new Vector3f(TIGER_JOINT[2][0] - TIGER_JOINT[1][0], TIGER_JOINT[2][1] - TIGER_JOINT[1][1], TIGER_JOINT[2][2] - TIGER_JOINT[1][2]).mul(px)));
            Vector3f tail = qBody.transform(new Vector3f(TIGER_JOINT[3][0], TIGER_JOINT[3][1], TIGER_JOINT[3][2]).mul(px));
            Vector3f[] at = {new Vector3f(), head, jaw, tail};
            Quaternionf[] rot = {qBody, qHead, qJaw, qTail};
            for (int i = 0; i < 4; i++) {
                ItemDisplay d = parts[i];
                if (d == null || !d.isValid()) continue;
                Location l = root.clone().add(at[i].x, at[i].y, at[i].z);
                l.setYaw(0); l.setPitch(0);
                d.teleport(l);
                d.setInterpolationDelay(0);
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(rot[i]).mul(flip), new Vector3f(TIGER_SCALE, TIGER_SCALE, TIGER_SCALE), new Quaternionf()));
            }
        }

        void remove() {
            if (parts[0] != null && parts[0].isValid()) world.spawnParticle(Particle.LARGE_SMOKE, pos.toLocation(world).add(0, 1.5, 0), 40, 1, 1, 1, 0.02);
            for (ItemDisplay d : parts) if (d != null && d.isValid()) d.remove();
            if (anchor.isValid()) anchor.remove();
            if (fight != null && fight.tiger == this) fight.tiger = null;
        }
    }

    // =====================================================================================================
    //  events
    // =====================================================================================================
    /** In his arena nobody dies: a hit that would kill you puts you on your knees instead (and you keep everything). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerHurt(EntityDamageEvent event) {
        Fight f = fight;
        if (f == null || !(event.getEntity() instanceof Player p) || !p.getWorld().equals(f.world)) return;
        if (f.downed.contains(p.getUniqueId())) { event.setCancelled(true); return; }
        if (!f.fighters.contains(p.getUniqueId()) || f.st == St.LOSS || f.st == St.DEFEAT) return;
        if (event.getFinalDamage() >= p.getHealth()) {
            event.setCancelled(true);
            f.down(p);
        }
    }

    /** His body can't be hurt. Hitting him earns a backhand that leaves you at half a heart. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onHitHim(EntityDamageEvent event) {
        Fight f = fight;
        if (!ours(event.getEntity())) return;
        event.setCancelled(true);
        if (f == null || !(event instanceof EntityDamageByEntityEvent by)) return;
        if (by.getDamager() instanceof Projectile pr) {
            f.world.playSound(pr.getLocation(), Sound.ITEM_SHIELD_BLOCK, SoundCategory.HOSTILE, 1f, 0.6f);
            return;
        }
        if (!(by.getDamager() instanceof Player p) || f.downed.contains(p.getUniqueId()) || !survival(p)) return;
        if (f.st == St.STUNNED || f.st == St.PINNED || f.st == St.CRASH) {
            p.sendActionBar(legacy(ChatColor.GRAY + "Your weapon does nothing. " + ChatColor.WHITE + "The pillar! Use a pickaxe!"));
            return;
        }
        if (f.st != St.FIGHT) return;
        long now = System.currentTimeMillis();
        if (now < f.counterCd.getOrDefault(p.getUniqueId(), 0L)) return;
        f.counterCd.put(p.getUniqueId(), now + 1500);
        f.face(p.getLocation().toVector(), 360);
        f.counterT = 0;
        p.setHealth(Math.min(p.getHealth(), 1.0)); // half a heart
        p.setVelocity(p.getLocation().toVector().subtract(f.pos).setY(0).normalize().multiply(0.9).setY(0.4));
        f.world.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.HOSTILE, 1.5f, 0.5f);
        p.sendActionBar(legacy(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Don't touch me."));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPickaxe(PlayerInteractEvent event) {
        Fight f = fight;
        if (f == null || event.getAction() != Action.LEFT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
        Block b = event.getClickedBlock();
        boolean pillar = false;
        for (Pillar pr : f.pillars) if (pr.contains(b)) pillar = true;
        if (!pillar) return;
        event.setCancelled(true);
        Player p = event.getPlayer();
        if (f.downed.contains(p.getUniqueId())) return;
        if (!p.getInventory().getItemInMainHand().getType().name().endsWith("_PICKAXE")) {
            if (f.st == St.STUNNED) p.sendActionBar(legacy(ChatColor.GRAY + "You need a " + ChatColor.WHITE + "pickaxe" + ChatColor.GRAY + " to crack it."));
            return;
        }
        f.pickaxe(p, b);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPillarBreak(BlockBreakEvent event) {
        Fight f = fight;
        if (f == null) return;
        for (Pillar pr : f.pillars) if (pr.contains(event.getBlock())) { event.setCancelled(true); return; }
        if (event.getBlock().getType() == Material.BARRIER && event.getBlock().getWorld().equals(f.world)) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Fight f = fight;
        if (f != null && f.downed.contains(event.getPlayer().getUniqueId())) f.restore(event.getPlayer());
    }

    // =====================================================================================================
    //  /explorer (admins)
    // =====================================================================================================
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("bosses.admin")) { sender.sendMessage(ChatColor.RED + "You don't have permission to do that."); return true; }
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "start" -> {
                if (!(sender instanceof Player p) || !below.inVoid(p.getWorld())) { sender.sendMessage(ChatColor.RED + "Stand in the void world (/below void), near his arena."); return true; }
                if (fight != null) { sender.sendMessage(ChatColor.GRAY + "He's already fighting."); return true; }
                respawnAt = 0;
                below.removeStatue();
                begin(p.getWorld(), p);
                sender.sendMessage(ChatColor.GREEN + "The Lost Explorer wakes up.");
            }
            case "stop" -> {
                if (fight == null) { sender.sendMessage(ChatColor.GRAY + "He isn't fighting."); return true; }
                fight.end(false); fight = null;
                sender.sendMessage(ChatColor.GREEN + "Stopped the fight and cleared the arena.");
            }
            case "phase" -> {
                if (fight == null || args.length < 2) { sender.sendMessage(ChatColor.RED + "/explorer phase <1-4> (during a fight)"); return true; }
                int ph;
                try { ph = Math.max(1, Math.min(4, Integer.parseInt(args[1]))); }
                catch (NumberFormatException e) { sender.sendMessage(ChatColor.RED + "/explorer phase <1-4>"); return true; }
                Fight f = fight;
                for (int i = 0; i < ph - 1; i++) { // knocks over that many pillars instantly
                    for (Pillar pr : f.pillars) if (pr.standing) { pr.standing = false; for (Block b : pr.blocks) { BlockData was = f.changed.remove(b); b.setBlockData(was != null ? was : Material.AIR.createBlockData(), false); } break; }
                }
                f.phase = ph;
                if (ph >= 2 && f.tiger == null) f.tiger = new Tiger(f.world);
                if (ph >= 3) { f.blade = true; f.weapon.setItemStack(modelItem("explorer_blade")); }
                f.st = St.FIGHT; f.t = 0; f.done(10);
                sender.sendMessage(ChatColor.GREEN + "Phase " + ph + ".");
            }
            case "stun" -> {
                if (fight == null) { sender.sendMessage(ChatColor.RED + "No fight."); return true; }
                Fight f = fight;
                Pillar pr = null; double best = Double.MAX_VALUE;
                for (Pillar x : f.pillars) if (x.standing && x.center().distance(f.pos) < best) { best = x.center().distance(f.pos); pr = x; }
                if (pr == null) { sender.sendMessage(ChatColor.RED + "No pillars left."); return true; }
                f.moveDir = pr.center().subtract(f.pos).setY(0);
                f.crash(pr);
                sender.sendMessage(ChatColor.GREEN + "He crashed into a pillar.");
            }
            case "arena" -> { // straight to the edge of his arena (the admin menu uses this)
                Player tg = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player sp ? sp : null;
                if (tg == null) { sender.sendMessage(ChatColor.RED + "Who? /explorer arena [player]"); return true; }
                World v = below.voidWorld();
                if (v == null) { sender.sendMessage(ChatColor.RED + "The void world couldn't be loaded (see the console)."); return true; }
                if (!below.inVoid(tg.getWorld()) && !below.returns.containsKey(tg.getUniqueId())) { below.returns.put(tg.getUniqueId(), tg.getLocation()); below.dirty = true; }
                tg.setFallDistance(0);
                tg.teleport(new Location(v, 0.5, Below.PATH_Y + 1, Below.ARENA_Z - 14.5, 0, 0));
                if (!tg.equals(sender)) sender.sendMessage(ChatColor.GREEN + "Sent " + tg.getName() + " to the Lost Explorer's arena.");
            }
            case "reset" -> { respawnAt = 0; sender.sendMessage(ChatColor.GREEN + "He'll be back at the end of the path right away."); }
            default -> sender.sendMessage(ChatColor.YELLOW + "/explorer <start|stop|phase <1-4>|stun|reset|arena [player]>");
        }
        return true;
    }
}
