package net.faultlinesmp.bosses;

import net.faultlinesmp.bosses.FaultlineBosses.Pose;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
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
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

import static net.faultlinesmp.bosses.ExplorerAnims.*;
import static net.faultlinesmp.bosses.FaultlineBosses.*;

/**
 * THE LOST EXPLORER, the final boss, at the end of the white path in the void world (see Below.java for the way there).
 *
 * He stands on top of a huge black pillar in the middle of his arena, digging into it with a pickaxe. Walk into the arena:
 *   CUTSCENE 1: walls close the arena, he stops digging, "Who are you?" "You're not from this world." He snaps his fingers.
 *   THE TIGER: his black tiger leaps down. 2,000 health, and swords work on it. Every hit it lands takes a quarter of your
 *     health (4 and you're down). He watches from his pillar.
 *   CUTSCENE 2: the tiger dies. "Useless." "You shall die." He drops off his pillar, it sinks into the floor, and the four
 *     fighting pillars rise.
 *   THE EXPLORER (4 phases): he can't be hurt by weapons (hit him and he backhands you to half a heart). He fights like the
 *     Roaring Knight: fast, with slash lines across the floor, dashes, cross cuts, rings of swords, starbursts of blades,
 *     sword rain. Every hit is randomly a one-shot or a two-shot. After every 15 moves he CHARGES: dodge so he hits a
 *     pillar, then hit that pillar 3 times with a pickaxe while he's stunned to drop it on him. Four pillars, four phases.
 * SOLO: only whoever walks in first fights him; anyone else who walks into the arena is put back on the path.
 * Nobody dies and nobody loses anything here: you drop to your knees and can't move. When everyone is down he talks for half a
 * minute about the kingdom Below the Bedrock, then "Get out of my sight.", kicks you all out, and Swarm's hole closes.
 */
final class Explorer implements Listener, CommandExecutor {

    static final String TAG = "faultline_explorer", TIGER_TAG = "faultline_explorer_tiger";
    static final float SCALE = 1.5f;
    static final float TIGER_SCALE = 1.7f;
    static final int PILLAR_H = 9;
    static final int BIG_H = 12; // his pillar in the middle of the arena (3x3, BIG_H tall)
    static final int[][] PILLARS = {{-7, -7}, {7, -7}, {7, 7}, {-7, 7}}; // offsets from the arena center (x, z)
    static final Material PILLAR_BODY = Material.POLISHED_BLACKSTONE_BRICKS, PILLAR_CAP = Material.CHISELED_POLISHED_BLACKSTONE;
    static final Material BIG_BODY = Material.DEEPSLATE_BRICKS, BIG_CAP = Material.DEEPSLATE_TILES;

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
        Bukkit.getScheduler().runTaskLater(pl, () -> pl.bossPart("The Lost Explorer", "cleanup", () -> cleanArena(false)), 5L);
    }

    double c(String path, double def) { return pl.getConfig().getDouble("explorer." + path, def); }

    static boolean ours(Entity e) { return e.getScoreboardTags().contains(TAG); }

    /** While he's fighting, or gone for a while after losing, his statue isn't on top of the pillar. */
    boolean blocksStatue() { return fight != null || System.currentTimeMillis() < respawnAt; }

    void tick() {
        if (fight != null) pl.bossPart("The Lost Explorer", "fight", fight::tick);
        if (fight != null && fight.over) fight = null;
    }

    void shutdown() {
        if (fight != null) fight.end(false);
        fight = null;
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (ours(e)) e.remove();
        cleanArena(false);
    }

    /** Starts the fight (walking into the arena, or right-clicking him). */
    void begin(World w, Player by) {
        if (fight != null) return;
        cleanArena(true); // BUG FIX: pillars left by a crash were remembered as "what was there" and put back after the fight
        ensureBigPillar(w);
        fight = new Fight(w, by);
    }

    static Vector center() { return new Vector(0.5, Below.PATH_Y + 1, Below.ARENA_Z + 0.5); }

    /** Where his tiger sits before the fight (at the foot of his pillar, facing the path). */
    static Vector tigerSeat() { return center().add(new Vector(0, 0, -3.2)); }

    /** Where he stands while his pillar is up. */
    static Vector pillarTop() { return center().add(new Vector(0, BIG_H, 0)); }

    /** Leftover pillars/walls (a crash mid-fight) go back to the generated arena: air, and the light blocks. */
    void cleanArena(boolean force) {
        World v = Bukkit.getWorld(below.voidName());
        if (v == null || fight != null) return;
        int cz = Below.ARENA_Z;
        for (int x = -24; x <= 24; x++) for (int z = cz - 24; z <= cz + 24; z++) for (int y = Below.PATH_Y; y <= Below.PATH_Y + 1 + PILLAR_H; y++) {
            if (!force && !v.isChunkLoaded(x >> 4, z >> 4)) continue;
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

    /** His pillar: 3x3, BIG_H tall, in the middle of the arena. It's always there, except while he's fighting on the floor. */
    static List<Block> bigPillarBlocks(World v, int layer) {
        List<Block> out = new ArrayList<>();
        int y = Below.PATH_Y + 1 + layer;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) out.add(v.getBlockAt(dx, y, Below.ARENA_Z + dz));
        return out;
    }

    void ensureBigPillar(World v) {
        for (int layer = 0; layer < BIG_H; layer++) {
            Material m = layer == 0 || layer == BIG_H - 1 ? BIG_CAP : BIG_BODY;
            for (Block b : bigPillarBlocks(v, layer)) if (b.getType() != m) b.setType(m, false);
        }
    }

    // =====================================================================================================
    //  the fight
    // =====================================================================================================
    enum St { INTRO, TIGER, TIGER_DEATH, FIGHT, CRASH, STUNNED, PINNED, PHASE_UP, LOSS, DEFEAT }
    enum Move { NONE, WALK, CLEAVE, SWEEP, CHARGE_WINDUP, CHARGE, RECOVER, SLASH, DASH, CROSS, RING, STARBURST, THROW, VANISH, AMBUSH, RAIN }

    final class Pillar {
        final int idx, cx, cz;
        final List<Block> blocks = new ArrayList<>();
        boolean standing, falling;
        int cracks;
        Pillar(int idx, int cx, int cz) { this.idx = idx; this.cx = cx; this.cz = cz; }
        Vector center() { return new Vector(cx + 0.5, Below.PATH_Y + 1, cz + 0.5); }
        boolean contains(Block b) { return blocks.contains(b); }
    }

    /** A white slash line across the floor: a thin warning first, then the cut (the Roaring Knight's signature). */
    final class Slash {
        final Vector a, b;
        final int cutAt;
        boolean done;
        Slash(Vector a, Vector b, int cutAt) { this.a = a; this.b = b; this.cutAt = cutAt; }
    }

    /** A flying sword: hovers (wait), then flies straight. */
    final class Blade {
        final ItemDisplay d;
        final Vector pos, dir;
        final double speed;
        int wait, life;
        final Set<UUID> hit = new HashSet<>();
        Blade(World w, Vector pos, Vector dir, double speed, int wait, int life) {
            this.pos = pos.clone(); this.dir = dir.clone().normalize(); this.speed = speed; this.wait = wait; this.life = life;
            Quaternionf q = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), new Vector3f((float) this.dir.getX(), (float) this.dir.getY(), (float) this.dir.getZ()));
            d = w.spawn(pos.toLocation(w), ItemDisplay.class, x -> {
                x.setItemStack(modelItem("explorer_blade"));
                x.setTeleportDuration(1); x.setInterpolationDuration(1); x.setViewRange(6f);
                x.setBrightness(new Display.Brightness(15, 15));
                x.setPersistent(false);
                x.addScoreboardTag(TAG);
                x.setTransformation(new Transformation(new Vector3f(), q, new Vector3f(0.7f, 0.7f, 0.7f), new Quaternionf()));
            });
        }
    }

    final class Fight {
        final World world;
        final Vector home = center();
        Vector pos;
        float yaw = 0;
        final ItemDisplay[] parts = new ItemDisplay[6];
        final ItemDisplay weapon;
        final Slime hitbox;
        final ArmorStand anchor;
        Pose shown;
        St st = St.INTRO;
        Move move = Move.NONE;
        int t, mt, ticks, phase = 1, moves, gap = 30, counterT = -1;
        boolean over, blade, onPillar = true;
        final Set<UUID> fighters = new LinkedHashSet<>(), downed = new HashSet<>(), hitThisMove = new HashSet<>();
        final Map<Block, BlockData> changed = new LinkedHashMap<>();
        final List<Pillar> pillars = new ArrayList<>();
        Pillar crashed;
        UUID target;
        Vector moveDir, aim, from, to;
        final List<Slash> slashes = new ArrayList<>();
        final List<Blade> blades = new ArrayList<>();
        int dashLeft;
        ItemDisplay thrown;
        Vector thrownPos, thrownVel;
        boolean thrownBack;
        final Map<UUID, Long> counterCd = new HashMap<>();
        final Map<UUID, Integer> iframes = new HashMap<>();
        Tiger tiger;
        int bigLayers = BIG_H; // how much of his pillar is still standing
        // the falling pillar
        final List<BlockDisplay> fallParts = new ArrayList<>();
        final List<Vector> fallOffsets = new ArrayList<>();
        Location fallPivot;
        Vector fallDir;
        int fallT = -1;

        Fight(World w, Player by) {
            world = w;
            pos = pillarTop();
            Location at = pos.toLocation(w);
            String[] pieces = {"_leg_r", "_leg_l", "_body", "_arm_r", "_arm_l", "_head"};
            for (int i = 0; i < 6; i++) {
                parts[i] = pl.spawnDisplay(at, "explorer" + pieces[i], SCALE, 2, Display.Billboard.FIXED);
                parts[i].setViewRange(8f);
                parts[i].addScoreboardTag(TAG);
            }
            weapon = w.spawn(at, ItemDisplay.class, d -> {
                d.setItemStack(modelItem("below_pickaxe")); // he's still digging when you walk in
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
            // SOLO: only whoever started it fights (anyone else in the arena is put back on the path)
            Player solo = by;
            if (solo == null) { double bd = Double.MAX_VALUE; for (Player p : w.getPlayers()) if (survival(p) && inArena(p, 19) && p.getLocation().distanceSquared(at) < bd) { bd = p.getLocation().distanceSquared(at); solo = p; } }
            if (solo != null) fighters.add(solo.getUniqueId());
            for (int i = 0; i < 4; i++) pillars.add(new Pillar(i, (int) Math.floor(home.getX()) + PILLARS[i][0], (int) Math.floor(home.getZ()) + PILLARS[i][1]));
            shown = ExplorerAnims.dig(0);
            tiger = new Tiger(w);
        }

        boolean inArena(Player p, double r) {
            if (!p.getWorld().equals(world)) return false;
            Location l = p.getLocation();
            return Math.hypot(l.getX() - home.getX(), l.getZ() - home.getZ()) <= r && Math.abs(l.getY() - home.getY()) < 14;
        }

        List<Player> alive() {
            List<Player> out = new ArrayList<>();
            for (UUID id : fighters) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && !downed.contains(id) && survival(p) && inArena(p, 24)) out.add(p);
            }
            return out;
        }

        /** He's fast like the Roaring Knight, and gets faster every phase. */
        double spd() { return c("speed", 1.1) * switch (phase) { case 1 -> 1.0; case 2 -> 1.12; case 3 -> 1.25; default -> 1.4; }; }

        void say(String text) {
            for (Player p : world.getPlayers())
                p.sendMessage(ChatColor.WHITE + "" + ChatColor.BOLD + "The Lost Explorer" + ChatColor.DARK_GRAY + " » " + ChatColor.GRAY + "" + ChatColor.ITALIC + text);
            world.playSound(pos.toLocation(world), Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 1.2f, 0.5f);
        }

        void hint(String text) {
            for (Player p : world.getPlayers()) p.sendActionBar(legacy(text));
        }

        // ------------------------------------------------------------------ music: Black Knife, looped for the fight
        long musicStart = -1;
        final Set<UUID> listeners = new HashSet<>();

        boolean musicOn() { return pl.getConfig().getBoolean("explorer.music.enabled", true); }

        void playMusic() {
            musicStart = System.currentTimeMillis();
            if (!musicOn()) return;
            for (Player p : world.getPlayers()) if (inArena(p, 40)) listen(p);
        }

        void listen(Player p) {
            p.stopSound(SoundCategory.MUSIC);
            p.playSound(p, "faultline:explorer.music", SoundCategory.RECORDS, (float) c("music.volume", 1.0), 1f);
            listeners.add(p.getUniqueId());
        }

        void stopMusic() {
            for (UUID id : listeners) { Player p = Bukkit.getPlayer(id); if (p != null) p.stopSound("faultline:explorer.music", SoundCategory.RECORDS); }
            listeners.clear();
            musicStart = -1;
        }

        void musicTick() {
            if (musicStart < 0 || !musicOn()) return;
            if (System.currentTimeMillis() - musicStart > c("music.length-seconds", 122) * 1000) { stopMusic(); playMusic(); return; }
            if (ticks % 20 == 0) for (Player p : world.getPlayers()) if (!listeners.contains(p.getUniqueId()) && inArena(p, 30)) listen(p);
        }

        // ------------------------------------------------------------------ every tick
        void tick() {
            ticks++; t++;
            musicTick();
            if (ticks % 5 == 0) keepOut();
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
                case TIGER -> p = tigerPhase();
                case TIGER_DEATH -> p = tigerDeath();
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
            if (over) return;
            if (fallT >= 0) fallTick();
            if (thrown != null) thrownTick();
            slashTick();
            bladeTick();
            if (tiger != null) tiger.tick();
            if (counterT >= 0) { p = ExplorerAnims.counter(counterT); if (++counterT > 12) counterT = -1; }
            render(p);
        }

        /** Solo fight: anyone else who walks into the arena (survival/adventure) is put back on the path outside it. */
        void keepOut() {
            for (Player p : world.getPlayers()) {
                if (fighters.contains(p.getUniqueId()) || !survival(p) || !inArena(p, 22.2)) continue;
                p.setFallDistance(0);
                p.teleport(new Location(world, 0.5, Below.PATH_Y + 1, Below.ARENA_Z - 24.5, 180, 0));
                p.sendActionBar(legacy(ChatColor.GRAY + "Someone is already facing him. " + ChatColor.WHITE + "He fights one at a time."));
            }
        }

        void render(Pose p) {
            if (over) return;
            Location root = pos.toLocation(world, yaw, 0);
            if (hitbox.isValid()) hitbox.teleport(root.clone().add(0, 0.05, 0));
            if (anchor.isValid()) anchor.teleport(root);
            shown = Pose.lerp(shown, p, 0.6f);
            pl.renderRig(parts, weapon, root, yaw, SCALE, shown, 90, 1.0f);
        }

        // ------------------------------------------------------------------ cutscene 1: "Who are you?"
        Pose intro() {
            if (t == 10) {
                placeWalls();
                world.playSound(home.toLocation(world), Sound.BLOCK_END_PORTAL_SPAWN, SoundCategory.HOSTILE, 1.4f, 0.5f);
            }
            if (t < 24) {
                if (t % 12 == 3) { // still digging
                    Location bite = pos.toLocation(world).add(fwd().multiply(1.0));
                    world.spawnParticle(Particle.BLOCK, bite, 10, 0.3, 0.1, 0.3, 0, BIG_BODY.createBlockData());
                    world.playSound(bite, Sound.BLOCK_DEEPSLATE_BRICKS_HIT, SoundCategory.BLOCKS, 1.2f, 0.6f);
                }
                return ExplorerAnims.dig(ticks);
            }
            Player look = nearest();
            if (look != null) face(look.getLocation().toVector(), 3);
            if (t == 24) weapon.setItemStack(modelItem("explorer_axe"));
            if (t == 50) say("...");
            if (t == 80) say("Who are you?");
            if (t == 130) say("You're not from this world.");
            if (t >= 165 && t < 195) {
                if (t == 183) { // the snap
                    Location hand = pos.toLocation(world).add(0, 3.6, 0);
                    world.playSound(hand, Sound.BLOCK_TRIPWIRE_CLICK_ON, SoundCategory.HOSTILE, 2f, 1.8f);
                    world.playSound(hand, Sound.ENTITY_EVOKER_CAST_SPELL, SoundCategory.HOSTILE, 1.4f, 0.6f);
                    world.spawnParticle(Particle.END_ROD, hand, 20, 0.2, 0.2, 0.2, 0.05);
                    if (tiger != null) tiger.wake();
                }
                return ExplorerAnims.snap(t - 165);
            }
            if (t >= 215) {
                st = St.TIGER; t = 0;
                playMusic();
                hint(ChatColor.GRAY + "His tiger can be hurt. " + ChatColor.WHITE + "Kill it.");
            }
            return t < 64 ? ExplorerAnims.straighten(t - 24) : ExplorerAnims.watch(ticks);
        }

        // ------------------------------------------------------------------ the tiger fight (he watches from his pillar)
        Pose tigerPhase() {
            Vector watchAt = tiger != null ? tiger.pos : null;
            Player p = nearest();
            if (p != null && (watchAt == null || ticks % 120 < 60)) watchAt = p.getLocation().toVector();
            if (watchAt != null) face(watchAt, 3);
            if (tiger == null || tiger.hp <= 0) { st = St.TIGER_DEATH; t = 0; stopMusic(); if (tiger != null) tiger.die(); }
            return ExplorerAnims.watch(ticks);
        }

        // ------------------------------------------------------------------ cutscene 2: "Useless." "You shall die."
        Pose tigerDeath() {
            if (tiger != null) face(tiger.pos, 3);
            if (t == 30) say("...");
            if (t == 70) say("Useless.");
            if (t == 100) say("Completely useless.");
            if (t == 150) {
                say("You shall die.");
                world.playSound(pos.toLocation(world), Sound.ENTITY_WARDEN_ROAR, SoundCategory.HOSTILE, 2f, 0.6f);
                playMusic();
            }
            if (t < 150) return t < 60 ? ExplorerAnims.watch(ticks) : ExplorerAnims.useless(Math.min(60, t - 60));
            // he drops off his pillar onto the floor
            if (t == 160) {
                from = pos.clone();
                Player tg = nearest();
                Vector d = tg != null ? tg.getLocation().toVector().subtract(home).setY(0) : new Vector(0, 0, -1);
                if (d.lengthSquared() < 0.01) d = new Vector(0, 0, -1);
                to = home.clone().add(d.normalize().multiply(4.5));
                onPillar = false;
            }
            if (t > 160 && t <= 178) {
                double f = (t - 160) / 18.0;
                Vector p = from.clone().add(to.clone().subtract(from).multiply(f));
                p.setY(home.getY() + BIG_H * (1 - f) + Math.sin(f * Math.PI) * 3);
                pos = p;
                if (t == 178) {
                    pos.setY(home.getY());
                    world.spawnParticle(Particle.EXPLOSION, pos.toLocation(world), 6, 1.5, 0.1, 1.5, 0);
                    world.playSound(pos.toLocation(world), Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.HOSTILE, 2f, 0.5f);
                    for (Player pp : alive()) if (flatDist(pp) < 5) pp.setVelocity(pp.getLocation().toVector().subtract(pos).setY(0).normalize().multiply(0.8).setY(0.4));
                }
                return t < 178 ? ExplorerAnims.airborne(t) : ExplorerAnims.slam(0);
            }
            // his pillar sinks; the four pillars rise
            if (t >= 180 && t % 3 == 0 && bigLayers > 0) {
                bigLayers--;
                for (Block b : bigPillarBlocks(world, bigLayers)) b.setBlockData(generated(b.getX(), b.getY(), b.getZ()), false);
                Location fx = home.toLocation(world).add(0, bigLayers, 0);
                world.spawnParticle(Particle.BLOCK, fx, 30, 1, 0.3, 1, 0, BIG_BODY.createBlockData());
                if (bigLayers % 3 == 0) world.playSound(fx, Sound.BLOCK_DEEPSLATE_BRICKS_BREAK, SoundCategory.BLOCKS, 1.5f, 0.5f);
            }
            if (t >= 184 && t <= 184 + PILLAR_H * 4 && (t - 184) % 4 == 0) for (Pillar pr : pillars) raise(pr, (t - 184) / 4);
            if (t >= 232) {
                for (Pillar pr : pillars) pr.standing = true;
                st = St.FIGHT; t = 0; move = Move.NONE; mt = 0; gap = 16; moves = 0;
                hint(ChatColor.GRAY + "Weapons won't touch him. " + ChatColor.WHITE + "When he charges, make him hit a pillar" + ChatColor.GRAY + ", then pickaxe it.");
            }
            return t < 200 ? ExplorerAnims.slam(Math.min(24, t - 178)) : ExplorerAnims.idle(ticks);
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
            for (Player p : world.getPlayers()) { // anyone standing where the wall goes up is moved inside, not walled in
                Vector d = p.getLocation().toVector().subtract(home).setY(0);
                if (d.length() >= 17.8 && d.length() < 22.2 && Math.abs(p.getLocation().getY() - home.getY()) < 6) {
                    Vector in = d.normalize().multiply(17.4);
                    Location l = p.getLocation(); l.setX(home.getX() + in.getX()); l.setZ(home.getZ() + in.getZ()); l.setY(home.getY());
                    p.teleport(l);
                }
            }
            // BUG FIX: the wall used to be a circle (radius 18.6-19.9) a little outside the floor, whose edge is stepped, so in
            // places there was a gap of open void between the last floor block and the barriers. Now it hugs the floor: every
            // void column touching the arena floor gets barriers (from floor level up), and the path is closed off just past
            // the arena's edge.
            for (int x = -23; x <= 23; x++) for (int z = -23; z <= 23; z++) {
                int wx = cx + x, wz = cz + z;
                double d = Math.hypot(wx + 0.5 - home.getX(), wz + 0.5 - home.getZ());
                if (d > 23) continue;
                boolean wall;
                if (Below.isPath(wx, wz)) wall = d > 18.8 && d < 21.5; // across the path, just outside the arena
                else {
                    wall = false;
                    for (int ox = -1; ox <= 1 && !wall; ox++) for (int oz = -1; oz <= 1 && !wall; oz++)
                        if (Below.isPath(wx + ox, wz + oz) && Math.hypot(wx + ox + 0.5 - home.getX(), wz + oz + 0.5 - home.getZ()) < 21.5) wall = true;
                }
                if (!wall) continue;
                for (int y = -1; y < 5; y++) { // from floor level (nothing to slip through below) up
                    Block b = world.getBlockAt(wx, (int) home.getY() + y, wz);
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
            if (move == Move.NONE) {
                if (tg != null) face(tg.getLocation().toVector(), 14);
                if (--gap > 0) return ExplorerAnims.idle(ticks);
                if (tg == null) return ExplorerAnims.idle(ticks);
                pick(tg);
            }
            mt++;
            double tt = mt * spd(); // everything he does runs on a sped-up clock
            return switch (move) {
                case WALK -> walk(tg);
                case CLEAVE -> cleave(tg, tt);
                case SWEEP -> sweep(tt);
                case CHARGE_WINDUP -> chargeWindup(tg);
                case CHARGE -> charging();
                case RECOVER -> { if (mt >= 16) done(10); yield ExplorerAnims.idle(ticks); }
                case SLASH -> slashMove(tg, tt);
                case DASH -> dashMove(tg);
                case CROSS -> crossMove(tg, tt);
                case RING -> ringMove(tg, tt);
                case STARBURST -> starMove(tt);
                case THROW -> throwMove(tg, tt);
                case VANISH -> vanish(tg);
                case AMBUSH -> ambush(tt);
                case RAIN -> rainMove(tt);
                default -> { done(8); yield ExplorerAnims.idle(ticks); }
            };
        }

        void done(int pause) { move = Move.NONE; gap = (int) Math.max(4, pause / spd()); }

        void pick(Player tg) {
            double d = tg.getLocation().toVector().distance(pos);
            long standing = pillars.stream().filter(pr -> pr.standing).count();
            moves++;
            int every = (int) c("charge-after-moves", 15);
            if (standing > 0 && moves > every) { moves = 0; target = tg.getUniqueId(); start(Move.CHARGE_WINDUP); return; }
            if (standing > 0 && moves == every - 1) hint(ChatColor.DARK_RED + "He's gathering himself... " + ChatColor.GRAY + "get in front of a pillar.");
            List<Move> pool = new ArrayList<>();
            pool.add(Move.SLASH); pool.add(Move.SLASH); pool.add(Move.DASH);
            if (d <= 6) { pool.add(Move.CLEAVE); pool.add(Move.SWEEP); }
            if (phase >= 2) { pool.add(Move.CROSS); pool.add(Move.RING); pool.add(Move.RING); }
            if (phase >= 3) { pool.add(Move.STARBURST); pool.add(Move.VANISH); if (blade && thrown == null && d >= 5) pool.add(Move.THROW); }
            if (phase >= 4) { pool.add(Move.RAIN); pool.add(Move.STARBURST); pool.add(Move.DASH); }
            start(pool.get(random.nextInt(pool.size())));
            target = tg.getUniqueId();
        }

        // --- WALK: closes in, then picks again
        Pose walk(Player tg) {
            if (tg == null) { done(6); return ExplorerAnims.idle(ticks); }
            face(tg.getLocation().toVector(), 12);
            Vector d = tg.getLocation().toVector().subtract(pos).setY(0);
            if (d.length() < 4.5 || mt > 40) { move = Move.NONE; gap = 0; return ExplorerAnims.walk(ticks, 1); }
            pos.add(d.normalize().multiply(0.3 * spd()));
            clampToArena();
            return ExplorerAnims.walk(ticks * 2, 1);
        }

        // --- CLEAVE: the axe/blade comes down on whatever's in front of him
        Pose cleave(Player tg, double tt) {
            if (tt < 12 && tg != null) face(tg.getLocation().toVector(), 10);
            if (tt < 18) cone(5.8, 40, Color.fromRGB(200, 0, 0));
            if (crossed(tt, 18)) {
                for (Player p : alive()) if (inCone(p, 5.8, 40)) hit(p, pos);
                Location fx = pos.clone().add(fwd().multiply(3.2)).toLocation(world);
                world.spawnParticle(Particle.EXPLOSION, fx, 3, 0.8, 0.1, 0.8, 0);
                world.spawnParticle(Particle.BLOCK, fx, 40, 1.5, 0.2, 1.5, 0, Material.WHITE_CONCRETE.createBlockData());
                world.playSound(fx, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1f, 0.6f);
                world.playSound(fx, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.HOSTILE, 1.4f, 0.6f);
            }
            if (tt >= 32) done(12);
            return ExplorerAnims.cleave((float) tt);
        }

        // --- SWEEP: a full spin at hip height
        Pose sweep(double tt) {
            if (tt < 18) { ring(4.8, Color.fromRGB(200, 0, 0)); ring(4.0, Color.fromRGB(200, 0, 0)); }
            if (tt >= 18 && tt < 26) yaw += 45 * (float) spd();
            if (crossed(tt, 19)) {
                for (Player p : alive()) if (flatDist(p) <= 4.9) hit(p, pos);
                world.spawnParticle(Particle.SWEEP_ATTACK, pos.clone().add(new Vector(0, 1.2, 0)).toLocation(world), 12, 2.5, 0.2, 2.5, 0);
                world.playSound(pos.toLocation(world), Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 1.5f, 0.5f);
            }
            if (tt >= 36) done(12);
            return ExplorerAnims.sweep((float) tt);
        }

        // --- SLASH: one rising slash, and white lines cut across the floor (one through you, others around)
        Pose slashMove(Player tg, double tt) {
            if (tt < 8 && tg != null) face(tg.getLocation().toVector(), 14);
            if (mt == 1) {
                int n = phase >= 4 ? 6 : phase >= 2 ? 4 : 3;
                int warn = (int) Math.max(16, 26 / spd());
                List<Player> ps = alive();
                for (int i = 0; i < n; i++) {
                    Vector through = i < ps.size() ? ps.get(i).getLocation().toVector() : tg != null ? tg.getLocation().toVector() : home.clone();
                    if (i >= ps.size()) through.add(new Vector(random.nextGaussian() * 5, 0, random.nextGaussian() * 5));
                    double ang = random.nextDouble() * Math.PI;
                    addLine(through, ang, ticks + warn + (phase >= 3 ? i * 3 : 0));
                }
                world.playSound(pos.toLocation(world), Sound.ITEM_TRIDENT_RIPTIDE_1, SoundCategory.HOSTILE, 1.4f, 0.6f);
            }
            if (tt >= 22) done(8);
            return ExplorerAnims.slash((float) tt);
        }

        // --- DASH: two to four dashes straight through you, a red line first, white streaks behind him
        Pose dashMove(Player tg) {
            int warn = (int) Math.max(10, 15 / spd());
            if (mt == 1) dashLeft = phase >= 4 ? 4 : phase >= 2 ? 3 : 2;
            int local = (mt - 1) % (warn + 12);
            if (local == 0) {
                Vector at = tg != null ? tg.getLocation().toVector().setY(home.getY()) : home.clone();
                aim = at.subtract(pos).setY(0);
                if (aim.lengthSquared() < 0.01) aim = fwd();
                aim.normalize();
                face(pos.clone().add(aim), 360);
                hitThisMove.clear();
                world.playSound(pos.toLocation(world), Sound.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.HOSTILE, 1f, 1.6f);
            }
            if (local < warn) {
                line(aim, 22, Color.fromRGB(230, 0, 0));
                return ExplorerAnims.dash(Math.min(6, local * 6f / warn));
            }
            // the dash itself: 12 ticks at full speed
            for (int s = 0; s < 2; s++) {
                pos.add(aim.clone().multiply(0.85));
                for (Player p : alive()) if (flatDist(p) < 1.7 && hitThisMove.add(p.getUniqueId())) hit(p, pos);
            }
            Vector d = pos.clone().subtract(home).setY(0);
            if (d.length() > 16.8) { d.normalize().multiply(16.8); pos.setX(home.getX() + d.getX()); pos.setZ(home.getZ() + d.getZ()); }
            world.spawnParticle(Particle.SQUID_INK, pos.toLocation(world).add(0, 1.5, 0), 6, 0.3, 0.8, 0.3, 0.01);
            world.spawnParticle(Particle.WHITE_ASH, pos.toLocation(world).add(0, 1.2, 0), 10, 0.4, 0.6, 0.4, 0);
            if (local == warn) world.playSound(pos.toLocation(world), Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 1.5f, 0.6f);
            if (local == warn + 11) {
                clampToArena();
                if (--dashLeft <= 0) done(10);
            }
            return ExplorerAnims.dash(6 + (local - warn) * 0.5f);
        }

        // --- CROSS: an X cut through you, then a + over it
        Pose crossMove(Player tg, double tt) {
            if (tt < 6 && tg != null) face(tg.getLocation().toVector(), 14);
            if (mt == 1) {
                Vector c = tg != null ? tg.getLocation().toVector() : home.clone();
                int warn = (int) Math.max(16, 24 / spd());
                double base = random.nextDouble() * Math.PI;
                addLine(c, base + Math.PI / 4, ticks + warn);
                addLine(c, base - Math.PI / 4, ticks + warn);
                addLine(c, base, ticks + warn + 10);
                addLine(c, base + Math.PI / 2, ticks + warn + 10);
                if (phase >= 3) { addLine(c, base + Math.PI / 8, ticks + warn + 18); addLine(c, base - 3 * Math.PI / 8, ticks + warn + 18); }
            }
            if (tt >= 26) done(10);
            return ExplorerAnims.cross((float) tt);
        }

        // --- RING: a ring of swords appears around you, hangs there, then they all thrust in
        Pose ringMove(Player tg, double tt) {
            if (tt < 8 && tg != null) face(tg.getLocation().toVector(), 14);
            if (crossed(tt, 8)) {
                int rings = phase >= 4 ? 2 : 1;
                for (Player p : alive()) {
                    if (rings == 1 && p != tg && random.nextDouble() < 0.5) continue; // not always everyone
                    Vector c = p.getLocation().toVector().add(new Vector(0, 1.0, 0));
                    for (int r = 0; r < rings; r++) {
                        int n = 8;
                        for (int i = 0; i < n; i++) {
                            double a = (i + r * 0.5) * Math.PI * 2 / n;
                            Vector at = c.clone().add(new Vector(Math.cos(a) * 4.5, 0, Math.sin(a) * 4.5));
                            Vector in = c.clone().subtract(at);
                            blades.add(new Blade(world, at, in, 0.55, (int) (30 / Math.min(1.3, spd())) + r * 16, 18));
                        }
                    }
                    world.playSound(p.getLocation(), Sound.ITEM_TRIDENT_RETURN, SoundCategory.HOSTILE, 1.4f, 0.8f);
                }
            }
            if (tt >= 30) done(10);
            return ExplorerAnims.summon((float) tt);
        }

        // --- STARBURST: waves of blades burst out from him in every direction (stand in the gaps)
        Pose starMove(double tt) {
            for (int wave = 0; wave < 3; wave++) {
                if (crossed(tt, 14 + wave * 8)) {
                    int n = phase >= 4 ? 16 : 12;
                    double off = wave * Math.PI / n + random.nextDouble() * 0.2;
                    for (int i = 0; i < n; i++) {
                        double a = off + i * Math.PI * 2 / n;
                        Vector dir = new Vector(Math.cos(a), 0, Math.sin(a));
                        blades.add(new Blade(world, pos.clone().add(new Vector(0, 1.2, 0)).add(dir.clone().multiply(1.5)), dir, 0.7, 10, 26));
                    }
                    world.playSound(pos.toLocation(world), Sound.ENTITY_WITHER_SHOOT, SoundCategory.HOSTILE, 1.2f, 1.4f);
                }
            }
            if (tt < 14 && mt % 3 == 0) ring(2.5, Color.fromRGB(200, 200, 210));
            if (tt >= 40) done(12);
            return ExplorerAnims.starburst((float) tt);
        }

        // --- THROW (phase 3+): the greatsword spins out and comes back
        Pose throwMove(Player tg, double tt) {
            if (tg != null && tt < 10) face(tg.getLocation().toVector(), 12);
            if (crossed(tt, 12)) {
                Vector dir = tg != null ? tg.getEyeLocation().toVector().subtract(pos.clone().add(new Vector(0, 2, 0))).setY(0) : fwd();
                if (dir.lengthSquared() < 0.01) dir = fwd();
                thrownPos = pos.clone().add(new Vector(0, 1.8, 0));
                thrownVel = dir.normalize().multiply(1.0 * Math.min(1.3, spd()));
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
            if (tt >= 22) done(8);
            return ExplorerAnims.throwBlade((float) tt);
        }

        void thrownTick() {
            if (!thrown.isValid()) { thrown = null; weapon.setItemStack(modelItem(blade ? "explorer_blade" : "explorer_axe")); return; }
            Vector hand = pos.clone().add(new Vector(0, 1.8, 0));
            if (!thrownBack && (thrownPos.distance(hand) > 22 || thrownPos.clone().subtract(home).setY(0).length() > 18)) { thrownBack = true; hitThisMove.clear(); }
            if (thrownBack) {
                Vector back = hand.clone().subtract(thrownPos);
                if (back.length() < 1.2) { thrown.remove(); thrown = null; weapon.setItemStack(modelItem(blade ? "explorer_blade" : "explorer_axe")); world.playSound(hand.toLocation(world), Sound.ITEM_TRIDENT_RETURN, SoundCategory.HOSTILE, 1.2f, 0.7f); return; }
                thrownVel = back.normalize().multiply(1.1);
            }
            thrownPos.add(thrownVel);
            Location l = thrownPos.toLocation(world);
            thrown.teleport(l);
            float spin = (float) (ticks * 0.9);
            thrown.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateY((float) Math.atan2(thrownVel.getX(), thrownVel.getZ())).rotateX((float) Math.PI / 2).rotateZ(spin),
                    new Vector3f(1.2f, 1.2f, 1.2f), new Quaternionf()));
            world.spawnParticle(Particle.WHITE_ASH, l, 6, 0.5, 0.2, 0.5, 0);
            if (ticks % 4 == 0) world.playSound(l, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 0.8f, 1.5f);
            for (Player p : alive()) if (p.getLocation().toVector().add(new Vector(0, 1, 0)).distance(thrownPos) < 1.6 && hitThisMove.add(p.getUniqueId())) hit(p, thrownPos);
        }

        // --- SHADOW STEP (phase 3+): sinks into the dark and comes up behind you
        Pose vanish(Player tg) {
            if (mt == 1) world.playSound(pos.toLocation(world), Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 1.2f, 0.5f);
            if (mt == 3 && tg != null) {
                Vector back = Vendetta.flatDir(tg.getLocation()).multiply(-2.2);
                to = tg.getLocation().toVector().add(back).setY(home.getY());
                Vector d = to.clone().subtract(home).setY(0);
                if (d.length() > 16.5) to = home.clone().add(d.normalize().multiply(16.5));
            }
            if (mt >= 3 && to != null && mt % 2 == 0) world.spawnParticle(Particle.SQUID_INK, to.toLocation(world).add(0, 1, 0), 8, 0.4, 0.8, 0.4, 0.02);
            world.spawnParticle(Particle.LARGE_SMOKE, pos.toLocation(world).add(0, 1.5, 0), 4, 0.6, 1, 0.6, 0.01);
            if (mt >= 10) {
                if (to != null) { pos = to.clone(); clampToArena(); }
                if (tg != null) face(tg.getLocation().toVector(), 360);
                start(Move.AMBUSH);
                world.playSound(pos.toLocation(world), Sound.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.HOSTILE, 1f, 1.4f);
            }
            return ExplorerAnims.vanish(mt * 1.4f);
        }

        Pose ambush(double tt) {
            if (crossed(tt, 7)) {
                for (Player p : alive()) if (inCone(p, 4.2, 60)) hit(p, pos);
                world.spawnParticle(Particle.SWEEP_ATTACK, pos.clone().add(fwd().multiply(2)).add(new Vector(0, 1.5, 0)).toLocation(world), 6, 1, 0.3, 1, 0);
                world.playSound(pos.toLocation(world), Sound.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.HOSTILE, 1.5f, 0.5f);
            }
            if (tt >= 18) done(10);
            return ExplorerAnims.ambush((float) tt);
        }

        // --- SWORD RAIN (phase 4): blades fall from the black sky onto marked circles
        Pose rainMove(double tt) {
            if (crossed(tt, 10)) {
                for (Player p : alive()) for (int i = 0; i < 3; i++) {
                    Vector v = p.getLocation().toVector().add(new Vector(random.nextGaussian() * 1.8, 0, random.nextGaussian() * 1.8)).setY(home.getY());
                    if (v.clone().subtract(home).setY(0).length() > 17) continue;
                    blades.add(new Blade(world, v.clone().add(new Vector(0, 12, 0)), new Vector(0, -1, 0), 0.9, 20 + i * 4, 15));
                }
                world.playSound(pos.toLocation(world), Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.HOSTILE, 1.5f, 0.5f);
            }
            if (tt >= 40) done(14);
            return ExplorerAnims.callDown((float) tt);
        }

        // ------------------------------------------------------------------ slash lines and flying blades
        /** A line through a point at an angle, clipped to the arena circle. */
        void addLine(Vector through, double ang, int cutAt) {
            Vector dir = new Vector(Math.cos(ang), 0, Math.sin(ang));
            Vector p = through.clone().setY(home.getY());
            Vector rel = p.clone().subtract(home);
            double R = 18, bq = rel.dot(dir), cq = rel.lengthSquared() - R * R, disc = bq * bq - cq;
            if (disc <= 0) return;
            double s1 = -bq - Math.sqrt(disc), s2 = -bq + Math.sqrt(disc);
            slashes.add(new Slash(p.clone().add(dir.clone().multiply(s1)), p.clone().add(dir.clone().multiply(s2)), cutAt));
        }

        void slashTick() {
            for (Iterator<Slash> it = slashes.iterator(); it.hasNext(); ) {
                Slash s = it.next();
                Vector ab = s.b.clone().subtract(s.a);
                double len = ab.length();
                Vector dir = ab.clone().multiply(1 / len);
                if (ticks < s.cutAt) { // the warning: the whole band that gets cut (white, turning red as the cut gets close)
                    int left = s.cutAt - ticks;
                    Color col = left > 10 ? Color.fromRGB(235, 235, 245) : Color.fromRGB(235, 20, 20);
                    Vector side = new Vector(-dir.getZ(), 0, dir.getX());
                    for (double d = 0; d <= len; d += 0.55) {
                        Vector c = s.a.clone().add(dir.clone().multiply(d));
                        world.spawnParticle(Particle.DUST, c.toLocation(world).add(0, 0.15, 0), 1, 0, 0, 0, 0, new Particle.DustOptions(col, 1.8f));
                        if (ticks % 2 == 0) for (int k = -1; k <= 1; k += 2) // the edges of the danger band
                            world.spawnParticle(Particle.DUST, c.clone().add(side.clone().multiply(1.1 * k)).toLocation(world).add(0, 0.15, 0), 1, 0, 0, 0, 0, new Particle.DustOptions(col, 1.1f));
                    }
                    continue;
                }
                // the cut
                for (double d = 0; d <= len; d += 1.5) {
                    Location l = s.a.clone().add(dir.clone().multiply(d)).toLocation(world).add(0, 0.9, 0);
                    world.spawnParticle(Particle.SWEEP_ATTACK, l, 1, 0, 0, 0, 0);
                    world.spawnParticle(Particle.END_ROD, l, 1, 0.1, 0.4, 0.1, 0.02);
                }
                world.playSound(s.a.clone().add(ab.clone().multiply(0.5)).toLocation(world), Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 1.6f, 0.5f);
                for (Player p : alive()) {
                    Vector rel = p.getLocation().toVector().setY(home.getY()).subtract(s.a);
                    double along = Math.max(0, Math.min(len, rel.dot(dir)));
                    double off = rel.clone().subtract(dir.clone().multiply(along)).length();
                    if (off <= 1.1 && Math.abs(p.getLocation().getY() - home.getY()) < 3) hit(p, s.a.clone().add(dir.clone().multiply(along)));
                }
                it.remove();
            }
        }

        void bladeTick() {
            for (Iterator<Blade> it = blades.iterator(); it.hasNext(); ) {
                Blade b = it.next();
                if (!b.d.isValid()) { it.remove(); continue; }
                if (b.wait > 0) {
                    b.wait--;
                    if (b.wait % 4 == 0) world.spawnParticle(Particle.END_ROD, b.pos.toLocation(world), 1, 0.05, 0.05, 0.05, 0);
                    if (b.wait % 2 == 0) { // where it's going to go
                        if (b.dir.getY() < -0.5) circle(b.pos.clone().setY(home.getY()), 1.2, Color.fromRGB(230, 0, 0));
                        else for (double d = 0.5; d <= 3; d += 0.5) dust(b.pos.clone().add(b.dir.clone().multiply(d)).setY(home.getY()), Color.fromRGB(230, 0, 0));
                    }
                    if (b.wait == 0) world.playSound(b.pos.toLocation(world), Sound.ITEM_TRIDENT_THROW, SoundCategory.HOSTILE, 0.6f, 1.4f);
                    continue;
                }
                b.pos.add(b.dir.clone().multiply(b.speed));
                b.d.teleport(b.pos.toLocation(world));
                for (Player p : alive())
                    if (p.getLocation().toVector().add(new Vector(0, 0.9, 0)).distance(b.pos) < 1.15 && b.hit.add(p.getUniqueId())) hit(p, b.pos);
                if (--b.life <= 0 || b.pos.getY() < home.getY() - 0.5 || b.pos.clone().subtract(home).setY(0).length() > 20) {
                    if (b.dir.getY() < -0.5) world.spawnParticle(Particle.BLOCK, b.pos.toLocation(world), 10, 0.3, 0.1, 0.3, 0, Material.WHITE_CONCRETE.createBlockData());
                    b.d.remove();
                    it.remove();
                }
            }
        }

        // --- CHARGE: after every 15 moves. Lock on, a line on the floor, then a bull rush; into a pillar = stunned
        Pose chargeWindup(Player tg) {
            if (mt == 1) {
                world.playSound(pos.toLocation(world), Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 1.4f, 0.5f);
                hint(ChatColor.DARK_RED + "" + ChatColor.BOLD + "HE'S ABOUT TO CHARGE! " + ChatColor.GRAY + "Stand in front of a pillar, then dodge.");
            }
            if (tg != null && mt < 18) { face(tg.getLocation().toVector(), 10); aim = tg.getLocation().toVector().subtract(pos).setY(0); }
            if (aim == null || aim.lengthSquared() < 0.01) aim = fwd();
            if (mt % 2 == 0) line(aim.clone().normalize(), 26, mt < 18 ? Color.fromRGB(120, 0, 0) : Color.fromRGB(230, 0, 0));
            if (mt >= 26) { moveDir = aim.clone().normalize(); start(Move.CHARGE); }
            return ExplorerAnims.chargeWindup(mt);
        }

        Pose charging() {
            double step = c("charge-speed", 1.1);
            for (int sub = 0; sub < 2; sub++) { // two half-steps, so he can't skip over a pillar edge
                pos.add(moveDir.clone().multiply(step / 2));
                for (Pillar pr : pillars) {
                    if (!pr.standing) continue;
                    if (pos.clone().subtract(pr.center()).setY(0).length() < 2.1) { crash(pr); return ExplorerAnims.crash(0); }
                }
                for (Player p : alive()) if (!hitThisMove.contains(p.getUniqueId()) && flatDist(p) < 1.9) { hitThisMove.add(p.getUniqueId()); hit(p, pos); }
            }
            world.spawnParticle(Particle.BLOCK, pos.toLocation(world), 6, 0.4, 0.05, 0.4, 0, Material.WHITE_CONCRETE.createBlockData());
            if (mt % 4 == 0) world.playSound(pos.toLocation(world), Sound.BLOCK_DEEPSLATE_STEP, SoundCategory.HOSTILE, 1.5f, 0.6f);
            boolean wall = pos.clone().subtract(home).setY(0).length() > 16.6;
            if (wall || mt * step > 30) { clampToArena(); start(Move.RECOVER); moves = (int) c("charge-after-moves", 15) - 4; world.playSound(pos.toLocation(world), Sound.ENTITY_IRON_GOLEM_DAMAGE, SoundCategory.HOSTILE, 1f, 0.5f); }
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
            clearAttacks();
            Location fx = pr.center().add(new Vector(0, 2, 0)).toLocation(world);
            world.spawnParticle(Particle.EXPLOSION, fx, 4, 1, 1, 1, 0);
            world.spawnParticle(Particle.BLOCK, fx, 60, 1.2, 2, 1.2, 0, PILLAR_BODY.createBlockData());
            world.playSound(fx, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.HOSTILE, 1.6f, 0.4f);
            world.playSound(fx, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.HOSTILE, 1.6f, 0.5f);
            hint(ChatColor.WHITE + "" + ChatColor.BOLD + "HE'S STUNNED! " + ChatColor.GRAY + "Hit that pillar with a " + ChatColor.WHITE + "pickaxe" + ChatColor.GRAY + "!");
        }

        void clearAttacks() {
            slashes.clear();
            for (Blade b : blades) if (b.d.isValid()) b.d.remove();
            blades.clear();
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
                st = St.FIGHT; t = 0; crashed = null; done(10);
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
                BlockData was = changed.remove(b);
                b.setBlockData(was != null ? was : Material.AIR.createBlockData(), false);
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
                List<BlockDisplay> gone = new ArrayList<>(fallParts);
                fallParts.clear();
                Bukkit.getScheduler().runTaskLater(pl, () -> { for (BlockDisplay d : gone) if (d.isValid()) d.remove(); }, 12L);
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
                if (along > 1.5 && along < PILLAR_H + 0.5 && side < 1.8) hit(p, fallPivot.toVector());
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
                case 2 -> say("Is that all?");
                case 3 -> say("Enough.");
                default -> say("I was lost down here long before any of you were born.");
            }
            if (t == 30 && phase == 3) { blade = true; weapon.setItemStack(modelItem("explorer_blade")); world.playSound(pos.toLocation(world), Sound.ITEM_TRIDENT_RETURN, SoundCategory.HOSTILE, 1.5f, 0.5f); }
            if (t >= 16 && t < 48 && t % 4 == 0) world.spawnParticle(Particle.SQUID_INK, pos.clone().add(new Vector(0, 2.5, 0)).toLocation(world), 20, 1.2, 1.2, 1.2, 0.05);
            if (t >= 60) { st = St.FIGHT; t = 0; done(10); moves = 0; }
            return ExplorerAnims.roar(t);
        }

        // ------------------------------------------------------------------ telegraphs (on the white floor)
        void dust(Vector v, Color col) { world.spawnParticle(Particle.DUST, v.toLocation(world).add(0, 0.12, 0), 2, 0.05, 0, 0.05, 0, new Particle.DustOptions(col, 2.0f)); }

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

        boolean crossed(double tt, double frame) { double prev = (mt - 1) * spd(); return prev < frame && tt >= frame; }

        double flatDist(Player p) { return Math.hypot(p.getLocation().getX() - pos.getX(), p.getLocation().getZ() - pos.getZ()); }

        boolean inCone(Player p, double range, double halfDeg) {
            Vector to = p.getLocation().toVector().subtract(pos).setY(0);
            double d = to.length();
            if (d > range) return false;
            if (d < 1.2) return true;
            return Math.toDegrees(fwd().angle(to)) <= halfDeg;
        }

        // ------------------------------------------------------------------ damage: never a death
        /** His hits: a quarter of your health each (4 to go down). */
        void hit(Player p, Vector from) {
            boolean one = random.nextDouble() < c("one-shot-chance", 0.0); // (off by default: 4 hits to go down)
            hitShare(p, one ? 1.0 : c("damage.hit", 0.26), from, one ? 1.2 : 0.8);
        }

        /**
         * Takes a share of max health straight off (absorption hearts first). Armor, Protection and Resistance don't shrink it,
         * so the hit counts stay exact. Two hits within 8 ticks (him and a blade at once) count as one.
         */
        void hitShare(Player p, double share, Vector from, double push) {
            if (downed.contains(p.getUniqueId()) || !survival(p)) return;
            if (ticks < iframes.getOrDefault(p.getUniqueId(), 0)) return;
            iframes.put(p.getUniqueId(), ticks + 8);
            AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
            double hp = max == null ? 20 : max.getValue();
            double dmg = hp * share;
            Vector dir = p.getLocation().toVector().subtract(from).setY(0);
            if (dir.lengthSquared() < 0.01) dir = fwd();
            double abs = p.getAbsorptionAmount(), fromAbs = Math.min(abs, dmg);
            if (fromAbs > 0) p.setAbsorptionAmount(abs - fromAbs);
            double left = p.getHealth() - (dmg - fromAbs);
            p.playHurtAnimation(0);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 1f, 0.8f);
            p.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, p.getLocation().add(0, 1, 0), 6, 0.3, 0.4, 0.3, 0);
            if (left <= 0.01) { down(p); return; }
            p.setHealth(left);
            p.setVelocity(dir.normalize().multiply(push).setY(0.4));
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

        // ------------------------------------------------------------------ losing: half a minute about the kingdom Below the Bedrock
        static final String[] LORE = {
                "You cannot defeat me...",
                "Do you even know where you are? Below the Bedrock. Nobody up there remembers this place.",
                "There was a kingdom here once. White towers. Rivers of light. A city that never once knew the dark.",
                "Its people sang in the streets. Its king walked among them, and they loved him for it.",
                "Its army was the finest that ever marched. Its general wore armor of diamond, and he never lost a battle.",
                "Yes. Your Diamond Jacob. He was a general once. Down here. Before he ran up to your mountain and hid.",
                "Then one night the king went looking for something no king should ever want... and the light went out. All of it.",
                "The towers fell. The rivers stopped. The singing stopped. Only the dark stayed. And me.",
                "What happened to the king? Nobody knows. Nobody ever will.",
        };

        void startLoss() {
            st = St.LOSS; t = 0; move = Move.NONE;
            stopMusic();
            clearAttacks();
            if (thrown != null && thrown.isValid()) thrown.remove();
            thrown = null;
            weapon.setItemStack(modelItem(blade ? "explorer_blade" : "explorer_axe"));
            if (tiger != null && tiger.hp > 0) tiger.calm();
        }

        // the speech can be skipped; then "Do you want to do it again..?" (Yes: straight back to the tiger. No: kicked out)
        int askAt = -1, outAt = -1;
        Boolean again;

        Pose lossTick() {
            int talk = (int) (c("loss-speech-seconds", 30) * 20);
            int every = Math.max(20, talk / LORE.length);
            UUID any = downed.isEmpty() ? null : downed.iterator().next();
            Player look = any == null ? null : Bukkit.getPlayer(any);
            if (look != null && t < 60 && !onPillar) {
                face(look.getLocation().toVector(), 4);
                Vector d = look.getLocation().toVector().subtract(pos).setY(0);
                if (d.length() > 3.5) { pos.add(d.normalize().multiply(0.12)); clampToArena(); return ExplorerAnims.walk(ticks, 0.6f); }
            } else if (look != null) face(look.getLocation().toVector(), 4);
            if (askAt < 0) { // the speech
                if (t == 2) offerSkip();
                if (t % every == 20 && (t - 20) / every < LORE.length) say(LORE[(t - 20) / every]);
                if (t >= 20 + every * LORE.length) ask();
            } else if (outAt < 0) { // waiting for an answer
                if (again != null) {
                    if (again) { restart(); return ExplorerAnims.watch(ticks); }
                    outAt = t;
                } else if (t - askAt >= c("again-seconds", 15) * 20) outAt = t;
            }
            if (outAt >= 0) {
                if (t == outAt) say("Get out of my sight.");
                if (t == outAt + 30) { // the kick
                    world.playSound(pos.toLocation(world), Sound.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.HOSTILE, 1.5f, 0.5f);
                    for (Player p : world.getPlayers()) {
                        if (!fighters.contains(p.getUniqueId())) continue;
                        if (downed.contains(p.getUniqueId())) restore(p);
                        Vector away = p.getLocation().toVector().subtract(pos).setY(0);
                        if (away.lengthSquared() < 0.01) away = fwd();
                        p.setVelocity(away.normalize().multiply(1.6).setY(0.8));
                    }
                }
                if (t == outAt + 38) {
                    for (Player p : new ArrayList<>(world.getPlayers())) {
                        if (!fighters.contains(p.getUniqueId())) continue; // (solo: only the one who fought him)
                        below.paid.add(p.getUniqueId()); // Swarm takes them back down without another Fist
                        below.sendBack(p);
                        p.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "The void spits you out. The way down has closed. You'll have to find Swarm again.");
                    }
                    below.dirty = true;
                    below.sealNow();
                    end(false);
                    return ExplorerAnims.loom(ticks);
                }
                if (onPillar) return ExplorerAnims.snap(Math.min(30, t - outAt));
                return ExplorerAnims.dismiss(Math.min(40, t - outAt));
            }
            return onPillar ? ExplorerAnims.watch(ticks) : ExplorerAnims.loom(ticks);
        }

        List<Player> fighterList() {
            List<Player> out = new ArrayList<>();
            for (UUID id : fighters) { Player p = Bukkit.getPlayer(id); if (p != null && p.getWorld().equals(world)) out.add(p); }
            return out;
        }

        /** A clickable [Skip] for everyone in the void (any fighter can skip it for everyone). */
        void offerSkip() {
            for (Player p : fighterList()) p.spigot().sendMessage(button("  [ Skip ▶ ]", net.md_5.bungee.api.ChatColor.DARK_GRAY, "/lostexplorer skip", "Skip what he has to say"));
        }

        void ask() {
            askAt = t;
            say("Do you want to do it again..?");
            world.playSound(pos.toLocation(world), Sound.BLOCK_BELL_RESONATE, SoundCategory.HOSTILE, 1.2f, 0.5f);
            for (Player p : fighterList()) {
                p.sendTitle(ChatColor.WHITE + "Do you want to do it again..?", ChatColor.GRAY + "Click Yes or No in chat", 10, (int) (c("again-seconds", 15) * 20), 20);
                net.md_5.bungee.api.chat.TextComponent line = new net.md_5.bungee.api.chat.TextComponent("  ");
                line.addExtra(button("[ Yes ]", net.md_5.bungee.api.ChatColor.WHITE, "/lostexplorer yes", "Fight him again, right now (from the tiger)"));
                line.addExtra(new net.md_5.bungee.api.chat.TextComponent("   "));
                line.addExtra(button("[ No ]", net.md_5.bungee.api.ChatColor.DARK_GRAY, "/lostexplorer no", "Leave the void"));
                p.spigot().sendMessage(line);
            }
        }

        net.md_5.bungee.api.chat.TextComponent button(String text, net.md_5.bungee.api.ChatColor color, String command, String hover) {
            net.md_5.bungee.api.chat.TextComponent b = new net.md_5.bungee.api.chat.TextComponent(text);
            b.setColor(color);
            b.setBold(true);
            b.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND, command));
            b.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                    new net.md_5.bungee.api.chat.hover.content.Text(hover)));
            return b;
        }

        /** /lostexplorer skip|yes|no from a fighter. */
        void choose(Player p, String what) {
            if (st != St.LOSS || !fighters.contains(p.getUniqueId())) return;
            switch (what) {
                case "skip" -> { if (askAt < 0) { for (Player o : world.getPlayers()) o.sendMessage(ChatColor.DARK_GRAY + p.getName() + " skipped it."); ask(); } }
                case "yes", "no" -> {
                    if (askAt < 0 || outAt >= 0 || again != null) return;
                    again = what.equals("yes");
                    for (Player o : world.getPlayers()) { o.resetTitle(); o.sendMessage(ChatColor.GRAY + p.getName() + (again ? " wants to go again." : " has had enough.")); }
                }
                default -> { }
            }
        }

        /** Yes: everyone back on their feet, the arena reset, and straight into the snap and the tiger. */
        void restart() {
            World w = world;
            Player solo = null;
            for (UUID id : fighters) { Player p = Bukkit.getPlayer(id); if (p != null && p.getWorld().equals(w)) { solo = p; break; } }
            end(false);
            fight = null;
            begin(w, solo);
            Fight f = fight;
            if (f == null) return;
            f.placeWalls();
            f.weapon.setItemStack(modelItem("explorer_axe"));
            f.t = 160; // the snap, then the tiger
            f.say("Again, then.");
        }

        // ------------------------------------------------------------------ winning
        void startDefeat() {
            st = St.DEFEAT; t = 0;
            stopMusic();
            clearAttacks();
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
            int bags = (int) c("rewards.mythic-bags", 32);
            if (bags > 0) pl.console("givemythicbag " + bags + " " + p.getName(), p);
            int neth = (int) c("rewards.netherite-blocks", 5);
            if (neth > 0) pl.dropLocked(at, p, new ItemStack(Material.NETHERITE_BLOCK, neth));
            if (pl.getConfig().getBoolean("explorer.rewards.mirror", true)) pl.console("givelostmirror 1 " + p.getName(), p);
        }

        // ------------------------------------------------------------------ cleanup
        void end(boolean won) {
            if (over) return;
            over = true;
            stopMusic();
            clearAttacks();
            for (UUID id : new ArrayList<>(downed)) { Player p = Bukkit.getPlayer(id); if (p != null) restore(p); }
            downed.clear();
            for (ItemDisplay d : parts) if (d != null && d.isValid()) d.remove();
            if (weapon.isValid()) weapon.remove();
            if (hitbox.isValid()) hitbox.remove();
            if (anchor.isValid()) anchor.remove();
            if (thrown != null && thrown.isValid()) thrown.remove();
            for (BlockDisplay d : fallParts) if (d.isValid()) d.remove();
            if (tiger != null) tiger.remove();
            if (won) Bukkit.getScheduler().runTaskLater(pl, this::afterFight, 100L);
            else afterFight();
        }

        void afterFight() {
            for (Pillar pr : pillars) if (!pr.blocks.isEmpty())
                world.spawnParticle(Particle.BLOCK, pr.center().add(new Vector(0, 2, 0)).toLocation(world), 40, 1, 2, 1, 0, PILLAR_BODY.createBlockData());
            restoreBlocks();
            ensureBigPillar(world); // his pillar comes back for next time
        }
    }

    // =====================================================================================================
    //  the black tiger: fight 1. 2,000 health, swords work, every hit takes a quarter of your health
    // =====================================================================================================
    /** Joints in px: body from the ground; the rest from the body joint (jaw from the head joint). Matches tools/explorer_models.py. */
    static final float[] T_BODY_J = {0, 14, 0}, T_HEAD_J = {0, 4, 13}, T_JAW_J = {0, -3.9f, 4.6f}, T_TAIL_J = {0, 3, -12},
            T_FR_J = {-3.4f, -2, 8.5f}, T_FL_J = {3.4f, -2, 8.5f}, T_BR_J = {-3.8f, -1, -9}, T_BL_J = {3.8f, -1, -9};
    static final String[] TIGER_PARTS = {"body", "head", "jaw", "tail", "leg_fr", "leg_fl", "leg_br", "leg_bl"};

    static Vector3f v(float[] a) { return new Vector3f(a[0], a[1], a[2]); }

    static ItemDisplay[] spawnTiger(FaultlineBosses pl, Location at, String tag) {
        ItemDisplay[] parts = new ItemDisplay[8];
        for (int i = 0; i < 8; i++) {
            parts[i] = pl.spawnDisplay(at, "explorer_tiger_" + TIGER_PARTS[i], TIGER_SCALE, 2, Display.Billboard.FIXED);
            parts[i].setViewRange(8f);
            parts[i].addScoreboardTag(tag);
        }
        return parts;
    }

    /** {bodyPitch, headPitch, headYaw, jaw, tailYaw, legFR, legFL, legBR, legBL, drop(px), roll, tailPitch} -> the eight model pieces. */
    static void renderTiger(FaultlineBosses pl, ItemDisplay[] parts, Location root, float yaw, float[] p) {
        float px = TIGER_SCALE / 16f;
        Quaternionf flip = pl.facing(0);
        Quaternionf qBody = new Quaternionf().rotateY((float) -Math.toRadians(yaw)).rotateX((float) Math.toRadians(p[T_BODY])).rotateZ((float) Math.toRadians(p[T_ROLL]));
        Vector3f body = new Vector3f(T_BODY_J[0], T_BODY_J[1] + p[T_DROP], T_BODY_J[2]).mul(px);
        Quaternionf qHead = new Quaternionf(qBody).rotateY((float) Math.toRadians(p[T_HYAW])).rotateX((float) Math.toRadians(p[T_HEAD]));
        Vector3f head = new Vector3f(body).add(qBody.transform(v(T_HEAD_J).mul(px)));
        Quaternionf qJaw = new Quaternionf(qHead).rotateX((float) Math.toRadians(p[T_JAW]));
        Vector3f jaw = new Vector3f(head).add(qHead.transform(v(T_JAW_J).mul(px)));
        Quaternionf qTail = new Quaternionf(qBody).rotateY((float) Math.toRadians(p[T_TAIL])).rotateX((float) Math.toRadians(p[T_TPITCH]));
        Vector3f tail = new Vector3f(body).add(qBody.transform(v(T_TAIL_J).mul(px)));
        float[][] legJ = {T_FR_J, T_FL_J, T_BR_J, T_BL_J};
        int[] legI = {T_FR, T_FL, T_BR, T_BL};
        Vector3f[] at = new Vector3f[8];
        Quaternionf[] rot = new Quaternionf[8];
        at[0] = body; rot[0] = qBody; at[1] = head; rot[1] = qHead; at[2] = jaw; rot[2] = qJaw; at[3] = tail; rot[3] = qTail;
        for (int i = 0; i < 4; i++) {
            at[4 + i] = new Vector3f(body).add(qBody.transform(v(legJ[i]).mul(px)));
            rot[4 + i] = new Quaternionf(qBody).rotateX((float) Math.toRadians(p[legI[i]]));
        }
        for (int i = 0; i < 8; i++) {
            ItemDisplay d = parts[i];
            if (d == null || !d.isValid()) continue;
            Location l = root.clone().add(at[i].x, at[i].y, at[i].z);
            l.setYaw(0); l.setPitch(0);
            d.teleport(l);
            d.setInterpolationDelay(0);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(rot[i]).mul(flip), new Vector3f(TIGER_SCALE, TIGER_SCALE, TIGER_SCALE), new Quaternionf()));
        }
    }

    final class Tiger {
        static final int SIT = 0, WAKE = 1, ARRIVE = 2, PROWL = 3, SWIPE = 4, CROUCH = 5, AIR = 6, LAND = 7, ROAR = 8, DEATH = 9, CALM = 10;
        final World world;
        Vector pos, from, to;
        float yaw = 180;
        final ItemDisplay[] parts;
        final ArmorStand anchor;
        final Slime hitbox;
        final BossBar bar;
        double hp, maxHp;
        int t, state = SIT, cooldown = 60, hurtT = -1, combo;
        boolean right;
        float[] shownPose = ExplorerAnims.tigerSit(0);
        final Set<UUID> hit = new HashSet<>();

        Tiger(World w) {
            world = w;
            pos = tigerSeat(); // sitting at the foot of his pillar, facing whoever comes up the path
            parts = spawnTiger(pl, pos.toLocation(w), TAG);
            anchor = w.spawn(pos.toLocation(w), ArmorStand.class, a -> {
                a.setVisibleByDefault(false); a.setInvisible(true); a.setMarker(true); a.setGravity(false);
                a.setInvulnerable(true); a.setPersistent(false); a.addScoreboardTag(TAG);
            });
            pl.proxy(anchor, anchor, EntityType.RAVAGER, 1.0);
            hitbox = (Slime) w.spawnEntity(pos.toLocation(w), EntityType.SLIME, false);
            hitbox.setSize(2);
            pl.setupHitbox(hitbox, 1000, TAG, "The Black Tiger");
            hitbox.addScoreboardTag(TIGER_TAG);
            AttributeInstance hs = hitbox.getAttribute(Attribute.SCALE);
            if (hs != null) hs.setBaseValue(2.2);
            maxHp = hp = c("tiger.health", 2000);
            bar = Bukkit.createBossBar(ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "The Black Tiger", BarColor.WHITE, BarStyle.SEGMENTED_10);
            bar.setVisible(false);
        }

        Fight f() { return fight; }

        /** The snap: it rises, roars, and bounds down at you. */
        void wake() { state = WAKE; t = 0; }

        void calm() { state = CALM; t = 0; }

        void die() { state = DEATH; t = 0; bar.setVisible(false); bar.removeAll(); hp = 0; }

        void damage(double dmg, Player by) {
            if (hp <= 0 || state == SIT || state == WAKE || state == DEATH) return;
            hp = Math.max(0, hp - dmg);
            hurtT = 0;
            world.playSound(pos.toLocation(world), Sound.ENTITY_RAVAGER_HURT, SoundCategory.HOSTILE, 1.2f, 0.6f);
            world.spawnParticle(Particle.DAMAGE_INDICATOR, pos.toLocation(world).add(0, 1.6, 0), 4, 0.6, 0.4, 0.6, 0);
            bar.setProgress(Math.max(0, Math.min(1, hp / maxHp)));
        }

        double spd() { return hp < maxHp * 0.5 ? 1.25 : 1.0; } // angrier below half

        void tick() {
            t++;
            Fight f = f();
            if (f == null) return;
            if (hurtT >= 0 && ++hurtT > 6) hurtT = -1;
            if (state != SIT && state != DEATH && state != CALM) { // the bar for everyone fighting it
                bar.setVisible(true);
                for (UUID id : f.fighters) { Player p = Bukkit.getPlayer(id); if (p != null && !bar.getPlayers().contains(p)) bar.addPlayer(p); }
            }
            float[] pose;
            Player tg = f.nearestTo(pos);
            switch (state) {
                case SIT -> pose = ExplorerAnims.tigerSit(f.ticks);
                case WAKE -> { // rises from sitting and roars
                    pose = t < 12 ? tlerp(ExplorerAnims.tigerSit(f.ticks), ExplorerAnims.tigerIdle(f.ticks), ExplorerAnims.ease(t / 12f)) : ExplorerAnims.tigerRoar(t - 12);
                    if (t == 14) world.playSound(pos.toLocation(world), Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 2f, 0.6f);
                    if (t >= 36) {
                        from = pos.clone();
                        Vector d = tg != null ? tg.getLocation().toVector().subtract(pos).setY(0) : new Vector(0, 0, -1);
                        if (d.lengthSquared() < 0.01) d = new Vector(0, 0, -1);
                        double len = Math.min(8, Math.max(3, d.length() - 2.5));
                        to = pos.clone().add(d.normalize().multiply(len));
                        state = ARRIVE; t = 0; faceTo(to);
                    }
                }
                case ARRIVE, AIR -> { // a bounding leap (ARRIVE: off the dais; AIR: a pounce at you)
                    pose = ExplorerAnims.tigerLeap(t);
                    int dur = state == ARRIVE ? 18 : 14;
                    double k = Math.min(1, t / (double) dur);
                    Vector p = from.clone().add(to.clone().subtract(from).multiply(k));
                    p.setY(center().getY() + Math.sin(k * Math.PI) * (state == ARRIVE ? 4 : 3));
                    pos = p;
                    if (state == AIR && t % 2 == 0) f.circle(to, 2.6, Color.fromRGB(230, 0, 0));
                    if (t >= dur) {
                        pos = to.clone(); clamp();
                        if (state == AIR) for (Player pl2 : f.alive()) if (pl2.getLocation().toVector().setY(pos.getY()).distance(pos) <= 2.6) f.hitShare(pl2, share(), pos, 1.0);
                        world.spawnParticle(Particle.BLOCK, pos.toLocation(world), 30, 1, 0.1, 1, 0, Material.WHITE_CONCRETE.createBlockData());
                        world.playSound(pos.toLocation(world), Sound.ENTITY_RAVAGER_ATTACK, SoundCategory.HOSTILE, 1.5f, 0.7f);
                        state = LAND; t = 0;
                    }
                }
                case LAND -> {
                    pose = ExplorerAnims.tigerLand(t);
                    if (t >= 12) { state = PROWL; t = 0; cooldown = (int) (20 / spd()); }
                }
                case PROWL -> {
                    if (tg == null) { pose = ExplorerAnims.tigerIdle(f.ticks); break; }
                    Vector d = tg.getLocation().toVector().subtract(pos).setY(0);
                    double dist = d.length();
                    turnTo(tg.getLocation().toVector(), 12);
                    cooldown--;
                    if (cooldown <= 0 && dist <= 3.4) { state = SWIPE; t = 0; right = !right; combo = spd() > 1 ? 2 : 1; hit.clear(); pose = ExplorerAnims.tigerIdle(f.ticks); break; }
                    if (cooldown <= 0 && dist >= 5 && dist <= 14 && random.nextDouble() < 0.06) { state = CROUCH; t = 0; to = tg.getLocation().toVector().setY(center().getY()); pose = ExplorerAnims.tigerCrouch(t); break; }
                    if (cooldown <= -60 && random.nextDouble() < 0.02) { state = ROAR; t = 0; pose = ExplorerAnims.tigerIdle(f.ticks); break; }
                    if (dist > 2.6) {
                        double sp = (dist > 7 ? 0.32 : 0.2) * spd();
                        Vector step = d.normalize();
                        if (dist < 6) step.add(new Vector(-step.getZ(), 0, step.getX()).multiply(0.35)).normalize(); // circles you when it's close
                        pos.add(step.multiply(sp));
                        clamp();
                        pose = ExplorerAnims.tigerWalk(f.ticks, dist > 7 ? 1.3f : 0.9f);
                    } else pose = ExplorerAnims.tigerIdle(f.ticks);
                }
                case SWIPE -> { // 18 ticks, contact at 10
                    float tt = (float) (t * spd());
                    pose = ExplorerAnims.tigerSwipe(tt, right);
                    if (tg != null && tt < 8) turnTo(tg.getLocation().toVector(), 10);
                    if (tt < 10 && t % 2 == 0) for (double r = 1.2; r <= 3.8; r += 0.8) for (double ang = -70; ang <= 70; ang += 20) { // where the paw rakes
                        double rad = Math.toRadians(yaw + ang);
                        f.dust(pos.clone().add(new Vector(-Math.sin(rad) * r, 0, Math.cos(rad) * r)), Color.fromRGB(200, 0, 0));
                    }
                    if (tt >= 10 && tt - spd() < 10) {
                        world.playSound(pos.toLocation(world), Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 1.3f, 0.6f);
                        Vector fw = fwd();
                        world.spawnParticle(Particle.SWEEP_ATTACK, pos.clone().add(fw.clone().multiply(2.2)).add(new Vector(0, 1.2, 0)).toLocation(world), 2, 0.6, 0.2, 0.6, 0);
                        for (Player p : f.alive()) {
                            Vector to2 = p.getLocation().toVector().subtract(pos).setY(0);
                            if (to2.length() <= 3.8 && (to2.length() < 1.2 || Math.toDegrees(fw.angle(to2)) <= 70) && hit.add(p.getUniqueId())) f.hitShare(p, share(), pos, 0.9);
                        }
                    }
                    if (tt >= 18) {
                        if (--combo > 0) { t = 0; right = !right; hit.clear(); }
                        else { state = PROWL; t = 0; cooldown = (int) (24 / spd()); }
                    }
                }
                case CROUCH -> {
                    pose = ExplorerAnims.tigerCrouch(t);
                    if (tg != null && t <= 8) { to = tg.getLocation().toVector().setY(center().getY()); faceTo(to); }
                    if (to != null && t % 2 == 0) f.circle(to, 2.6, Color.fromRGB(200, 0, 0));
                    if (t >= (int) (14 / spd())) { from = pos.clone(); state = AIR; t = 0; world.playSound(pos.toLocation(world), Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 1.4f, 0.9f); }
                }
                case ROAR -> {
                    pose = ExplorerAnims.tigerRoar(t);
                    if (t == 8) {
                        world.playSound(pos.toLocation(world), Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 2f, 0.5f);
                        for (Player p : f.alive()) if (p.getLocation().toVector().distance(pos) < 7) {
                            p.setVelocity(p.getLocation().toVector().subtract(pos).setY(0).normalize().multiply(1.1).setY(0.4));
                            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 1, false, true, true));
                        }
                    }
                    if (t >= 24) { state = PROWL; t = 0; cooldown = 10; }
                }
                case DEATH -> {
                    pose = ExplorerAnims.tigerDie(t);
                    if (t == 2) world.playSound(pos.toLocation(world), Sound.ENTITY_RAVAGER_DEATH, SoundCategory.HOSTILE, 2f, 0.5f);
                    if (t == 60) world.playSound(pos.toLocation(world), Sound.ENTITY_RAVAGER_STUNNED, SoundCategory.HOSTILE, 1.2f, 0.4f);
                    if (t >= 120) {
                        world.spawnParticle(Particle.ASH, pos.toLocation(world).add(0, 0.6, 0), 120, 1.4, 0.5, 1.4, 0);
                        world.spawnParticle(Particle.LARGE_SMOKE, pos.toLocation(world).add(0, 0.6, 0), 40, 1.2, 0.4, 1.2, 0.02);
                        remove();
                        return;
                    }
                }
                case CALM -> pose = ExplorerAnims.tigerIdle(f.ticks);
                default -> pose = ExplorerAnims.tigerIdle(f.ticks);
            }
            if (hurtT >= 0 && state != DEATH) pose = ExplorerAnims.tigerFlinch(pose, hurtT);
            shownPose = tlerp(shownPose, pose, 0.6f);
            render(shownPose);
        }

        double share() { return c("tiger.damage-share", 0.26); }

        Vector fwd() { return new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw))); }

        void faceTo(Vector at) {
            Vector d = at.clone().subtract(pos).setY(0);
            if (d.lengthSquared() > 0.01) yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        }

        void turnTo(Vector at, float step) {
            Vector d = at.clone().subtract(pos).setY(0);
            if (d.lengthSquared() < 0.01) return;
            float want = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
            float diff = ((want - yaw) % 360 + 540) % 360 - 180;
            yaw += Math.max(-step, Math.min(step, diff));
        }

        /** Stays in the arena and out of his pillar. */
        void clamp() {
            Vector home = center();
            Vector d = pos.clone().subtract(home).setY(0);
            if (d.length() > 16.5) { d.normalize().multiply(16.5); pos.setX(home.getX() + d.getX()); pos.setZ(home.getZ() + d.getZ()); }
            Fight f = f();
            if (f != null && f.bigLayers > 0 && d.length() < 2.8) {
                if (d.lengthSquared() < 0.01) d = new Vector(0, 0, -1);
                d.normalize().multiply(2.8); pos.setX(home.getX() + d.getX()); pos.setZ(home.getZ() + d.getZ());
            }
            pos.setY(home.getY());
        }

        void render(float[] p) {
            Location root = pos.toLocation(world);
            if (anchor.isValid()) { Location a = root.clone(); a.setYaw(yaw); anchor.teleport(a); }
            if (hitbox.isValid()) hitbox.teleport(root.clone().add(0, 0.1, 0));
            renderTiger(pl, parts, root, yaw, p);
        }


        void remove() {
            for (ItemDisplay d : parts) if (d != null && d.isValid()) d.remove();
            if (anchor.isValid()) anchor.remove();
            if (hitbox.isValid()) hitbox.remove();
            bar.setVisible(false);
            bar.removeAll();
            Fight f = f();
            if (f != null && f.tiger == this) f.tiger = null;
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

    /** His body can't be hurt (hit him and he backhands you to half a heart). His tiger can. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onHitHim(EntityDamageEvent event) {
        Fight f = fight;
        if (!ours(event.getEntity())) return;
        double dmg = event.getDamage();
        event.setCancelled(true);
        if (f == null || !(event instanceof EntityDamageByEntityEvent by)) return;
        Player p = by.getDamager() instanceof Player pp ? pp : by.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player sh ? sh : null;
        if (event.getEntity().getScoreboardTags().contains(TIGER_TAG)) { // the tiger: swords, axes, arrows, all of it
            if (p != null && f.tiger != null && f.fighters.contains(p.getUniqueId()) && !f.downed.contains(p.getUniqueId()) && survival(p)) f.tiger.damage(dmg, p);
            return;
        }
        if (by.getDamager() instanceof Projectile pr) {
            f.world.playSound(pr.getLocation(), Sound.ITEM_SHIELD_BLOCK, SoundCategory.HOSTILE, 1f, 0.6f);
            return;
        }
        if (p == null || f.downed.contains(p.getUniqueId()) || !survival(p)) return;
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
        p.setAbsorptionAmount(0);
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

    /** BUG FIX: after a crash mid-fight the walls stayed up (cleanup only reached loaded chunks), so nobody could reach him. */
    @EventHandler
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        if (fight != null || !below.inVoid(event.getWorld())) return;
        int x = event.getChunk().getX(), z = event.getChunk().getZ();
        if (x < -2 || x > 1 || z < (Below.ARENA_Z - 24) >> 4 || z > (Below.ARENA_Z + 24) >> 4) return;
        Bukkit.getScheduler().runTask(pl, () -> cleanArena(false));
    }

    /** The fallen stay down: no pearls, no chorus fruit, no eating, building or shooting their way out. */
    @EventHandler(priority = EventPriority.LOW)
    public void onDownedInteract(PlayerInteractEvent event) {
        Fight f = fight;
        if (f != null && f.downed.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDownedTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        Fight f = fight;
        if (f == null || !f.downed.contains(event.getPlayer().getUniqueId())) return;
        var c = event.getCause();
        if (c == org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.ENDER_PEARL || c == org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.CONSUMABLE_EFFECT)
            event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDownedEat(org.bukkit.event.player.PlayerItemConsumeEvent event) {
        Fight f = fight;
        if (f != null && f.downed.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
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
        if (command.getName().equalsIgnoreCase("lostexplorer")) { // the [Skip] / [Yes] / [No] buttons (any player)
            if (sender instanceof Player p && fight != null && args.length > 0) fight.choose(p, args[0].toLowerCase());
            return true;
        }
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
            case "skip" -> { // past the cutscene and the tiger, straight to him
                if (fight == null) { sender.sendMessage(ChatColor.RED + "No fight."); return true; }
                Fight f = fight;
                if (f.st == St.INTRO) { f.placeWalls(); f.st = St.TIGER; f.t = 0; if (f.tiger != null) { f.tiger.state = Tiger.PROWL; f.tiger.t = 0; } }
                if (f.st == St.TIGER && f.tiger != null) { f.tiger.hp = 0; }
                sender.sendMessage(ChatColor.GREEN + "Skipped ahead.");
            }
            case "phase" -> {
                if (fight == null || args.length < 2) { sender.sendMessage(ChatColor.RED + "/explorer phase <1-4> (during a fight)"); return true; }
                int ph;
                try { ph = Math.max(1, Math.min(4, Integer.parseInt(args[1]))); }
                catch (NumberFormatException e) { sender.sendMessage(ChatColor.RED + "/explorer phase <1-4>"); return true; }
                Fight f = fight;
                if (f.tiger != null) f.tiger.remove();
                if (f.onPillar) { // skip the cutscenes: walls, his pillar gone, pillars up, him on the floor
                    f.placeWalls();
                    for (int layer = BIG_H - 1; layer >= 0; layer--) for (Block b : bigPillarBlocks(f.world, layer)) b.setBlockData(generated(b.getX(), b.getY(), b.getZ()), false);
                    f.bigLayers = 0;
                    for (Pillar pr : f.pillars) { for (int l = 0; l < PILLAR_H; l++) f.raise(pr, l); pr.standing = true; }
                    f.onPillar = false;
                    f.pos = f.home.clone().add(new Vector(0, 0, -4));
                    f.weapon.setItemStack(modelItem("explorer_axe"));
                    if (f.musicStart < 0) f.playMusic();
                }
                for (int i = 0; i < ph - 1; i++) { // knocks over that many pillars instantly
                    for (Pillar pr : f.pillars) if (pr.standing) { pr.standing = false; for (Block b : pr.blocks) { BlockData was = f.changed.remove(b); b.setBlockData(was != null ? was : Material.AIR.createBlockData(), false); } break; }
                }
                f.phase = ph;
                if (ph >= 3) { f.blade = true; f.weapon.setItemStack(modelItem("explorer_blade")); }
                f.st = St.FIGHT; f.t = 0; f.moves = 0; f.done(10);
                sender.sendMessage(ChatColor.GREEN + "Phase " + ph + ".");
            }
            case "stun" -> {
                if (fight == null || fight.onPillar) { sender.sendMessage(ChatColor.RED + "Only once he's fighting on the floor (/explorer phase 1)."); return true; }
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
                tg.teleport(new Location(v, 0.5, Below.PATH_Y + 1, Below.ARENA_Z - 24.5, 0, 0)); // on the path, just short of the arena
                if (!tg.equals(sender)) sender.sendMessage(ChatColor.GREEN + "Sent " + tg.getName() + " to the Lost Explorer's arena.");
            }
            case "reset" -> { respawnAt = 0; sender.sendMessage(ChatColor.GREEN + "He'll be back on his pillar right away."); }
            default -> sender.sendMessage(ChatColor.YELLOW + "/explorer <start|stop|skip|phase <1-4>|stun|reset|arena [player]>");
        }
        return true;
    }
}
