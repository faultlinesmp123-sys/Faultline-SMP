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
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static net.faultlinesmp.bosses.FaultlineBosses.*;

/**
 * THE WAY DOWN to the Lost Explorer (the final boss).
 *
 *  1. SWARM, a citizen of the city Below the Bedrock. Anyone exploring between y=-1 and y=-50 (underground, no sky) has a
 *     2% chance every minute of him turning up nearby. Right-click to talk.
 *  2. He only helps people who have killed DIAMOND JACOB (recorded when Jacob's rewards are handed out; kills from before
 *     this update are read from the Faultline Index), and only for the VENDETTA FIST, held out to him (he keeps it).
 *  3. He turns around and digs a staircase down to the bedrock, one step at a time, lighting it as he goes.
 *  4. At the bottom he draws "a pickaxe you guys don't know" and breaks through the bedrock into the void.
 *  5. Jumping in lands you in the void world: pitch black, nothing but a white path. At its end stands the Lost Explorer.
 *     (He doesn't fight yet. Right-clicking him is how the fight will start.) A white rift at the start takes you back.
 *  6. After 10 minutes Swarm seals the hole and walks back up, filling the staircase behind him. Every block he dug is
 *     put back exactly as it was (also on a restart, or after a crash: the list is kept in below.yml).
 *
 * FaultlineBosses ticks this, shuts it down, and passes it the /below command.
 */
final class Below implements Listener, CommandExecutor {

    static final String TAG = "faultline_below";
    static final String SWARM_TAG = "faultline_swarm", STATUE_TAG = "faultline_explorer_statue";
    // the void world's layout (block coordinates): spawn platform, a winding 3-wide path, the arena where he stands
    static final int PATH_Y = 40, CEILING_Y = 64, PATH_END = 158, ARENA_Z = 174, ARENA_R = 18;

    final FaultlineBosses pl;
    final Random random;
    final File dataFile;
    final Set<UUID> slayers = new HashSet<>();
    final Map<UUID, Location> returns = new HashMap<>();
    final Map<UUID, Long> encounterCd = new HashMap<>(), talkCd = new HashMap<>();
    final Map<UUID, Integer> arrived = new HashMap<>();
    final Map<Block, Change> changes = new LinkedHashMap<>();
    Swarm swarm;
    Statue statue;
    TextDisplay riftLabel;
    boolean dirty;
    int now;

    static final class Change {
        final BlockData original;
        Material placed;
        Change(BlockData original, Material placed) { this.original = original; this.placed = placed; }
    }

    Below(FaultlineBosses pl) {
        this.pl = pl;
        this.random = pl.random;
        dataFile = new File(pl.getDataFolder(), "below.yml");
        load();
        restoreAll(); // anything left open by a crash goes back first
        Bukkit.getScheduler().runTask(pl, () -> pl.bossPart("The way down", "void world", this::voidWorld));
    }

    double c(String path, double def) { return pl.getConfig().getDouble("below." + path, def); }
    boolean enabled() { return pl.getConfig().getBoolean("below.enabled", true); }
    String voidName() { return pl.getConfig().getString("below.void-world", "faultline_void"); }

    static boolean ours(Entity e) {
        Set<String> t = e.getScoreboardTags();
        return t.contains(TAG) || t.contains(SWARM_TAG) || t.contains(STATUE_TAG);
    }

    // =====================================================================================================
    //  saving: who killed Jacob, where to send people back to, and every block Swarm changed
    // =====================================================================================================
    void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (String s : y.getStringList("jacob-slayers")) try { slayers.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        var r = y.getConfigurationSection("returns");
        if (r != null) for (String id : r.getKeys(false)) {
            Location l = parseLoc(r.getString(id));
            if (l != null) try { returns.put(UUID.fromString(id), l); } catch (IllegalArgumentException ignored) { }
        }
        for (String s : y.getStringList("restore")) {
            String[] f = s.split("\\|", 6);
            if (f.length < 6) continue;
            World w = Bukkit.getWorld(f[0]);
            if (w == null) continue;
            try {
                Block b = w.getBlockAt(Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]));
                Material placed = Material.matchMaterial(f[4]);
                changes.put(b, new Change(Bukkit.createBlockData(f[5]), placed == null ? Material.AIR : placed));
            } catch (IllegalArgumentException ex) {
                pl.getLogger().warning("[The way down] couldn't read a saved block: " + s);
            }
        }
    }

    void save() {
        dirty = false;
        YamlConfiguration y = new YamlConfiguration();
        y.set("jacob-slayers", slayers.stream().map(UUID::toString).toList());
        for (var e : returns.entrySet()) y.set("returns." + e.getKey(), locString(e.getValue()));
        List<String> rs = new ArrayList<>();
        for (var e : changes.entrySet()) {
            Block b = e.getKey();
            rs.add(b.getWorld().getName() + "|" + b.getX() + "|" + b.getY() + "|" + b.getZ() + "|" + e.getValue().placed.name() + "|" + e.getValue().original.getAsString());
        }
        y.set("restore", rs);
        try { pl.getDataFolder().mkdirs(); y.save(dataFile); } catch (IOException ex) { pl.getLogger().warning("[The way down] couldn't save below.yml: " + ex.getMessage()); }
    }

    static String locString(Location l) {
        return l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw();
    }

    static Location parseLoc(String s) {
        if (s == null) return null;
        String[] f = s.split(";");
        if (f.length < 5) return null;
        World w = Bukkit.getWorld(f[0]);
        if (w == null) return null;
        try { return new Location(w, Double.parseDouble(f[1]), Double.parseDouble(f[2]), Double.parseDouble(f[3]), Float.parseFloat(f[4]), 0); }
        catch (NumberFormatException e) { return null; }
    }

    /** Diamond Jacob's rewards call this for everyone who fought him. */
    void markSlayer(Player p) {
        if (slayers.add(p.getUniqueId())) save();
    }

    /** Killed Jacob: recorded since this update, or (from before it) his Faultline Index entry is unlocked. */
    boolean killedJacob(Player p) {
        if (slayers.contains(p.getUniqueId())) return true;
        File idx = new File(pl.getDataFolder().getParentFile(), "FaultlineIndex/progress.yml");
        if (!idx.exists()) return false;
        boolean found = YamlConfiguration.loadConfiguration(idx).getStringList("players." + p.getUniqueId() + ".found").contains("diamond_jacob");
        if (found) markSlayer(p);
        return found;
    }

    // =====================================================================================================
    //  every tick (from FaultlineBosses)
    // =====================================================================================================
    void tick() {
        now++;
        if (swarm != null) pl.bossPart("Swarm", "tick", swarm::tick);
        if (swarm != null && swarm.gone) swarm = null;
        pl.bossPart("The way down", "hole", this::holeTick);
        if (now % 5 == 0) pl.bossPart("The way down", "void world", this::voidTick);
        if (now % 20 == 0 && enabled()) pl.bossPart("The way down", "encounters", this::encounterTick);
        if (dirty && now % 100 == 0) save();
    }

    void shutdown() {
        if (swarm != null) swarm.remove();
        swarm = null;
        if (statue != null) statue.remove();
        statue = null;
        if (riftLabel != null && riftLabel.isValid()) riftLabel.remove();
        restoreAll();
        save();
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (ours(e)) e.remove();
    }

    // =====================================================================================================
    //  Swarm turning up
    // =====================================================================================================
    boolean exploring(Player p) {
        World w = p.getWorld();
        if (w.getEnvironment() != World.Environment.NORMAL || w.getName().equals(voidName()) || !survival(p)) return false;
        double y = p.getLocation().getY();
        return y >= c("swarm.min-y", -50) && y <= c("swarm.max-y", -1) && p.getEyeLocation().getBlock().getLightFromSky() <= 2;
    }

    void encounterTick() {
        if (swarm != null) return;
        int every = Math.max(1, (int) c("swarm.check-seconds", 60));
        if ((now / 20) % every != 0) return;
        List<Player> ps = new ArrayList<>(Bukkit.getOnlinePlayers());
        Collections.shuffle(ps, random);
        long ms = System.currentTimeMillis();
        for (Player p : ps) {
            if (!exploring(p) || ms < encounterCd.getOrDefault(p.getUniqueId(), 0L)) continue;
            if (random.nextDouble() >= c("swarm.chance", 0.02)) continue;
            Location at = spotNear(p);
            if (at == null) continue;
            spawnSwarm(at, p);
            return;
        }
    }

    /** A dark, open spot 5-10 blocks away, preferably behind them. */
    Location spotNear(Player p) {
        World w = p.getWorld();
        Location base = p.getLocation();
        Vector back = flatDirOf(base).multiply(-1);
        for (int i = 0; i < 60; i++) {
            double ang = Math.toRadians((i < 30 ? 120 : 360) * (random.nextDouble() - 0.5));
            Vector d = rotY(back, ang).multiply(5 + random.nextDouble() * 5);
            int x = (int) Math.floor(base.getX() + d.getX()), z = (int) Math.floor(base.getZ() + d.getZ());
            for (int dy = 3; dy >= -4; dy--) {
                int y = base.getBlockY() + dy;
                if (y <= w.getMinHeight() + 8) continue;
                Block feet = w.getBlockAt(x, y, z);
                if (open(feet) && open(feet.getRelative(BlockFace.UP)) && feet.getRelative(BlockFace.DOWN).getType().isSolid()) {
                    return new Location(w, x + 0.5, y, z + 0.5);
                }
            }
        }
        return null;
    }

    static boolean open(Block b) { return b.isPassable() && !b.isLiquid(); }

    static Vector flatDirOf(Location l) { return Vendetta.flatDir(l); }

    static Vector rotY(Vector v, double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new Vector(v.getX() * c - v.getZ() * s, 0, v.getX() * s + v.getZ() * c);
    }

    void spawnSwarm(Location at, Player near) {
        if (swarm != null) swarm.remove();
        swarm = new Swarm(at);
        if (near != null) {
            swarm.faceNow(near.getLocation().toVector());
            encounterCd.put(near.getUniqueId(), System.currentTimeMillis() + (long) (c("swarm.player-cooldown-seconds", 600) * 1000));
            near.playSound(at, Sound.BLOCK_DEEPSLATE_STEP, SoundCategory.NEUTRAL, 0.8f, 0.7f);
            near.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.ITALIC + "Footsteps, somewhere in the dark behind you...");
        }
    }

    // =====================================================================================================
    //  talking to him
    // =====================================================================================================
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(PlayerInteractEntityEvent event) {
        Entity e = event.getRightClicked();
        LivingEntity anchor = pl.proxyAnchorOf(e); // a Bedrock player clicked the stand-in
        Entity who = anchor != null ? anchor : e;
        boolean isSwarm = who.getScoreboardTags().contains(SWARM_TAG), isStatue = who.getScoreboardTags().contains(STATUE_TAG);
        if (!isSwarm && !isStatue) return;
        event.setCancelled(true); // no trading window on the Bedrock stand-in
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player p = event.getPlayer();
        long ms = System.currentTimeMillis();
        if (ms < talkCd.getOrDefault(p.getUniqueId(), 0L)) return;
        talkCd.put(p.getUniqueId(), ms + 700);
        if (isSwarm && swarm != null) swarm.talk(p);
        if (isStatue && statue != null) statue.talk(p);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClickAt(PlayerInteractAtEntityEvent event) {
        if (ours(event.getRightClicked())) event.setCancelled(true);
    }

    static String swarmLine(String text) { return ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "Swarm" + ChatColor.DARK_GRAY + " » " + ChatColor.GRAY + text; }

    // =====================================================================================================
    //  Swarm
    // =====================================================================================================
    static final int IDLE = 0, TALK = 1, TURN = 2, DIG = 3, LANDING = 4, DRAW = 5, BEDROCK = 6, OPEN = 7, COVER_HOLE = 8, COVER_WALK = 9, LEAVING = 10;
    static final int HOLE = -2, LANDING_GROUP = -1;

    final class Swarm {
        final World world;
        Vector pos;
        float yaw;
        ItemDisplay[] parts = new ItemDisplay[6];
        ItemDisplay tool;
        Interaction click;
        ArmorStand anchor;
        Pose shown = new Pose();
        int state = IDLE, t, life, wait, gestureT = -1, quiet;
        String gesture;
        boolean gone, whispered, warned;
        UUID talker;
        final Set<UUID> met = new HashSet<>();
        final ArrayDeque<Object> script = new ArrayDeque<>();
        // the staircase
        Block start;
        BlockFace dir, side;
        int steps, k, layer, coverAt;
        long openUntil;
        Vector moveFrom, moveTo;
        final Map<Integer, List<Block>> groups = new HashMap<>();
        Location entrance;

        Swarm(Location at) {
            world = at.getWorld();
            pos = at.toVector();
            spawnRig();
            world.spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 1, 0), 12, 0.3, 0.6, 0.3, 0.01);
        }

        void spawnRig() {
            Location at = loc();
            String[] pieces = {"_leg_r", "_leg_l", "_body", "_arm_r", "_arm_l", "_head"};
            for (int i = 0; i < 6; i++) {
                if (parts[i] != null && parts[i].isValid()) parts[i].remove();
                parts[i] = pl.spawnDisplay(at, "swarm" + pieces[i], 1f, 2, Display.Billboard.FIXED);
                parts[i].setViewRange(4f);
            }
            if (tool != null && tool.isValid()) tool.remove();
            ItemStack held = tool != null ? tool.getItemStack() : null;
            tool = world.spawn(at, ItemDisplay.class, d -> {
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND);
                d.setTeleportDuration(2); d.setInterpolationDuration(2); d.setViewRange(4f);
                d.setBrightness(new Display.Brightness(13, 13));
                d.setPersistent(false);
                d.addScoreboardTag(DISPLAY_TAG);
            });
            if (held != null) tool.setItemStack(held);
            if (click != null && click.isValid()) click.remove();
            click = world.spawn(at, Interaction.class, x -> {
                x.setInteractionWidth(0.8f); x.setInteractionHeight(2.0f); x.setResponsive(true);
                x.setPersistent(false);
                x.addScoreboardTag(SWARM_TAG);
            });
            if (anchor != null && anchor.isValid()) anchor.remove();
            anchor = world.spawn(at, ArmorStand.class, a -> {
                a.setVisibleByDefault(false); a.setInvisible(true); a.setMarker(true); a.setGravity(false);
                a.setInvulnerable(true); a.setSilent(true); a.setPersistent(false);
                a.addScoreboardTag(SWARM_TAG);
            });
            pl.proxy(anchor, anchor, EntityType.WANDERING_TRADER, 1.0); // Bedrock players see a hooded wanderer
            LivingEntity stand = pl.proxyStandOf(anchor);
            if (stand instanceof WanderingTrader wt) wt.setDespawnDelay(0);
        }

        boolean rigOk() {
            for (ItemDisplay d : parts) if (d == null || !d.isValid()) return false;
            return click != null && click.isValid() && anchor != null && anchor.isValid();
        }

        Location loc() { return pos.toLocation(world, yaw, 0); }

        void faceNow(Vector target) {
            Vector d = target.clone().subtract(pos).setY(0);
            if (d.lengthSquared() > 0.01) yaw = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
        }

        void face(Vector target, float maxStep) {
            Vector d = target.clone().subtract(pos).setY(0);
            if (d.lengthSquared() < 0.01) return;
            float want = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
            float diff = ((want - yaw) % 360 + 540) % 360 - 180;
            yaw += Math.max(-maxStep, Math.min(maxStep, diff));
        }

        Player nearest(double range) {
            Player best = null;
            double bd = range * range;
            for (Player p : world.getPlayers()) {
                if (p.getGameMode() == GameMode.SPECTATOR) continue;
                double d = p.getLocation().toVector().distanceSquared(pos);
                if (d < bd) { bd = d; best = p; }
            }
            return best;
        }

        void line(String text) {
            // whoever he's talking to always hears him (even if they hung back at the top of the stairs)
            for (Player p : world.getPlayers()) if (p.getUniqueId().equals(talker) || p.getLocation().toVector().distanceSquared(pos) < 32 * 32) p.sendMessage(swarmLine(text));
            world.playSound(loc(), Sound.ENTITY_VILLAGER_AMBIENT, SoundCategory.NEUTRAL, 0.5f, 0.55f);
        }

        void gesture(String g) { gesture = g; gestureT = 0; }

        // ------------------------------------------------------------------ the conversation
        void talk(Player p) {
            switch (state) {
                case IDLE -> converse(p);
                case OPEN -> {
                    long left = Math.max(0, (openUntil - now) / 20);
                    line("Go on. Jump. " + ChatColor.DARK_GRAY + "(" + (left / 60) + ":" + String.format("%02d", left % 60) + " left before I seal it)");
                }
                case TALK -> { }
                default -> p.sendActionBar(legacy(ChatColor.GRAY + "Swarm is busy digging. Stay close."));
            }
        }

        void converse(Player p) {
            talker = p.getUniqueId();
            state = TALK; t = 0; wait = 0;
            script.clear();
            pl.console("index discover " + p.getName() + " swarm", p);
            if (met.add(p.getUniqueId())) {
                script.add("...You're a long way from the sun, surface-walker.");
                script.add("They call me Swarm. I'm from the city Below the Bedrock. Don't go looking for it. You won't find it.");
                script.add("You're here for him, aren't you? The one who got lost down there.");
            }
            boolean creative = p.getGameMode() == GameMode.CREATIVE;
            if (!creative && !killedJacob(p)) {
                script.add("Diamond Jacob still sits on his mountain.");
                script.add("Nobody who couldn't beat Jacob comes back from where you want to go. Kill him. Then find me again.");
                script.add((Runnable) this::backToIdle);
                return;
            }
            if (!creative && !holdingFist(p)) {
                script.add("You beat Jacob. Good. But the way down isn't free.");
                script.add("Bring me the Vendetta Fist. " + ChatColor.WHITE + "Hold it out to me" + ChatColor.GRAY + ", and talk to me again.");
                script.add((Runnable) this::backToIdle);
                return;
            }
            script.add(creative ? "...Fine. I'll take you." : "Rocco's fist... So you really took it off him.");
            script.add((Runnable) () -> takeFist(p));
        }

        boolean holdingFist(Player p) { return "fist".equals(pl.vendetta.itemType(p.getInventory().getItemInMainHand())); }

        void takeFist(Player p) {
            boolean creative = p.getGameMode() == GameMode.CREATIVE;
            if (!creative) {
                if (!p.isOnline() || !holdingFist(p) || p.getLocation().toVector().distanceSquared(pos) > 8 * 8) {
                    script.clear();
                    script.add("...Where'd it go? Hold it out to me, then we'll talk.");
                    script.add((Runnable) this::backToIdle);
                    return;
                }
                ItemStack hand = p.getInventory().getItemInMainHand();
                hand.setAmount(hand.getAmount() - 1);
                p.getInventory().setItemInMainHand(hand.getAmount() > 0 ? hand : null);
                world.playSound(loc(), Sound.ITEM_ARMOR_EQUIP_CHAIN, SoundCategory.NEUTRAL, 1f, 0.8f);
            }
            gesture("take");
            script.add(40);
            script.add("A deal's a deal. Stay close, and don't touch the walls.");
            script.add((Runnable) () -> startStairs(p));
        }

        void backToIdle() { state = IDLE; t = 0; talker = null; }

        // ------------------------------------------------------------------ the staircase
        void startStairs(Player p) {
            Block s = pos.toLocation(world).getBlock();
            int n = s.getY() - (world.getMinHeight() + 5);
            if (world.getEnvironment() != World.Environment.NORMAL || n < 3) {
                script.clear();
                script.add("Not from here. Too close to the bottom already. Find me higher up.");
                script.add((Runnable) this::backToIdle);
                return;
            }
            Vector away = pos.clone().subtract(p.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 0.01) away = new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
            dir = Math.abs(away.getX()) > Math.abs(away.getZ()) ? (away.getX() > 0 ? BlockFace.EAST : BlockFace.WEST) : (away.getZ() > 0 ? BlockFace.SOUTH : BlockFace.NORTH);
            side = switch (dir) { case NORTH -> BlockFace.EAST; case EAST -> BlockFace.SOUTH; case SOUTH -> BlockFace.WEST; default -> BlockFace.NORTH; };
            start = s;
            steps = n;
            k = 0;
            entrance = new Location(world, s.getX() + 0.5, s.getY(), s.getZ() + 0.5);
            pos = entrance.toVector();
            state = TURN; t = 0;
        }

        Block col(int i) { return start.getRelative(dir, i).getRelative(0, -i, 0); }
        Vector at(Block b) { return new Vector(b.getX() + 0.5, b.getY(), b.getZ() + 0.5); }
        float yawOf(BlockFace f) { return (float) Math.toDegrees(Math.atan2(-f.getModX(), f.getModZ())); }

        /** Digs out one 3-tall column (feet, head, headroom), with a floor under it, liquids sealed, and nothing loose above. */
        void digColumn(int group, Block feet, boolean light) {
            List<Block> cells = List.of(feet, feet.getRelative(BlockFace.UP), feet.getRelative(0, 2, 0));
            for (Block b : cells) clear(group, b, false);
            Block floor = feet.getRelative(BlockFace.DOWN);
            if (!floor.getType().isSolid() && floor.getY() > world.getMinHeight()) set(group, floor, Material.DEEPSLATE.createBlockData());
            Block above = feet.getRelative(0, 3, 0);
            if (above.getType().hasGravity()) set(group, above, Material.DEEPSLATE.createBlockData());
            for (Block b : cells) seal(group, b);
            if (light) {
                BlockData l = Material.LIGHT.createBlockData();
                if (l instanceof Levelled lv) lv.setLevel(11);
                Block top = feet.getRelative(0, 2, 0);
                if (top.getType().isAir()) set(group, top, l);
            }
        }

        void clear(int group, Block b, boolean bedrockToo) {
            Material m = b.getType();
            if (m.isAir()) return;
            if (unbreakable(m) && !(bedrockToo && m == Material.BEDROCK)) return; // only the black pickaxe breaks bedrock
            if (b.getState() instanceof TileState) return; // chests, spawners, signs...: left alone
            if (!b.isLiquid()) {
                world.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 18, 0.3, 0.3, 0.3, 0, b.getBlockData());
                world.playSound(b.getLocation(), b.getBlockData().getSoundGroup().getBreakSound(), SoundCategory.BLOCKS, 0.9f, 0.9f);
            }
            set(group, b, Material.AIR.createBlockData());
        }

        boolean unbreakable(Material m) {
            return m == Material.BEDROCK || m == Material.BARRIER || m == Material.END_PORTAL_FRAME || m == Material.END_PORTAL
                    || m == Material.REINFORCED_DEEPSLATE || m == Material.COMMAND_BLOCK || m == Material.STRUCTURE_BLOCK || m == Material.LIGHT;
        }

        /** Lava and water next to the dug-out space get plugged with deepslate (they'd pour in). */
        void seal(int group, Block cell) {
            for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN}) {
                int ny = cell.getY() + f.getModY();
                if (ny < world.getMinHeight() || ny >= world.getMaxHeight()) continue; // nothing under the bottom of the world
                Block n = cell.getRelative(f);
                Change ch = changes.get(n);
                if (ch != null && ch.placed.isAir()) continue; // part of what we dug
                BlockData d = n.getBlockData();
                if (n.isLiquid() || (d instanceof Waterlogged w && w.isWaterlogged() && !(n.getState() instanceof TileState))) set(group, n, Material.DEEPSLATE.createBlockData());
            }
        }

        void set(int group, Block b, BlockData data) {
            Change ch = changes.get(b);
            if (ch == null) {
                changes.put(b, new Change(b.getBlockData(), data.getMaterial()));
                groups.computeIfAbsent(group, x -> new ArrayList<>()).add(b);
            } else ch.placed = data.getMaterial();
            b.setBlockData(data, false);
            dirty = true;
        }

        List<Block> holeCells() {
            Block bottom = col(steps);
            Block a = bottom.getRelative(dir), b = a.getRelative(dir);
            return List.of(a, a.getRelative(side), b, b.getRelative(side));
        }

        Vector holeCenter() {
            Block a = col(steps).getRelative(dir);
            return new Vector(a.getX() + 0.5 + (dir.getModX() + side.getModX()) * 0.5, world.getMinHeight(), a.getZ() + 0.5 + (dir.getModZ() + side.getModZ()) * 0.5);
        }

        void restoreGroup(int group) {
            List<Block> g = groups.remove(group);
            if (g == null) return;
            for (int i = g.size() - 1; i >= 0; i--) restore(g.get(i), entrance);
        }

        // ------------------------------------------------------------------ the update
        void tick() {
            t++; life++;
            if (!rigOk()) {
                if (state == IDLE || state == TALK) { if (nearest(48) == null) { remove(); return; } }
                if (nearest(64) != null) spawnRig();
            }
            Player look = null;
            Pose p;
            switch (state) {
                case IDLE -> {
                    look = nearest(16);
                    if (look != null && !whispered && look.getLocation().toVector().distanceSquared(pos) < 14 * 14) {
                        whispered = true;
                        look.sendMessage(swarmLine("...psst. Up here, surface-walker. Over here."));
                    }
                    if (nearest(48) == null) quiet++; else quiet = 0;
                    if (life > c("swarm.stay-seconds", 300) * 20 || quiet > 600) { leave(); return; }
                    if (look != null) face(look.getLocation().toVector(), 6);
                    p = idlePose(life, look == null);
                }
                case TALK -> {
                    Player tk = talker != null ? Bukkit.getPlayer(talker) : null;
                    look = tk != null && tk.getWorld().equals(world) ? tk : nearest(16);
                    if (look != null) face(look.getLocation().toVector(), 10);
                    runScript();
                    p = talkPose(t);
                }
                case TURN -> {                                       // turns his back on you and takes out his pickaxe
                    float target = yawOf(dir);
                    float diff = ((target - yaw) % 360 + 540) % 360 - 180;
                    yaw += Math.max(-12, Math.min(12, diff));
                    if (t == 12) { tool.setItemStack(new ItemStack(Material.IRON_PICKAXE)); world.playSound(loc(), Sound.ITEM_ARMOR_EQUIP_IRON, 0.8f, 1.1f); }
                    p = t < 12 ? stepPose(t, 0.5f) : FaultlineBosses.anim(t - 12, new int[]{0, 10}, new Pose[]{stepPose(0, 0), swingRest()});
                    if (t >= 24) { yaw = target; state = DIG; t = 0; k = 1; }
                }
                case DIG -> {                                        // one step: a swing, the column falls away, he steps down
                    p = swingPose(Math.min(t, 18));
                    if (t == 7) digColumn(k, col(k), k % 4 == 0);
                    if (t >= 10) {
                        Vector a = at(col(k - 1)), b = at(col(k));
                        float f = Math.min(1, (t - 10) / 7f);
                        pos = a.clone().add(b.clone().subtract(a).multiply(FaultlineBosses.ease(f)));
                        if (t > 10) p = stepPose(t, 1f);
                    }
                    if (t >= 18) {
                        t = 0; k++;
                        if (k > steps) { state = LANDING; t = 0; }
                    }
                }
                case LANDING -> {                                    // clears a landing to the side and room over the hole
                    p = swingPose(Math.min(t, 18));
                    Block bottom = col(steps);
                    if (t == 7) {
                        digColumn(LANDING_GROUP, bottom.getRelative(side), false);
                        for (Block h : holeCells()) for (int y = 0; y < 3; y++) clear(LANDING_GROUP, h.getRelative(0, y, 0), false);
                        for (Block h : holeCells()) for (int y = 0; y < 3; y++) seal(LANDING_GROUP, h.getRelative(0, y, 0));
                        Block above = bottom.getRelative(dir).getRelative(0, 3, 0);
                        if (above.getType().hasGravity()) set(LANDING_GROUP, above, Material.DEEPSLATE.createBlockData());
                    }
                    if (t >= 10 && t <= 20) {
                        Vector a = at(bottom), b = at(bottom.getRelative(side));
                        pos = a.clone().add(b.clone().subtract(a).multiply(FaultlineBosses.ease(Math.min(1, (t - 10) / 10f))));
                        p = stepPose(t, 0.6f);
                    }
                    if (t >= 22) {
                        yaw = yawOf(dir);
                        state = DRAW; t = 0;
                        line("Bedrock. You all think this is the bottom.");
                    }
                }
                case DRAW -> {                                       // puts the old pick away and draws the black one
                    p = drawPose(t);
                    if (t == 30) { tool.setItemStack(new ItemStack(Material.AIR)); }
                    if (t == 44) {
                        tool.setItemStack(blackPickaxe());
                        world.playSound(loc(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.NEUTRAL, 0.9f, 0.5f);
                        world.spawnParticle(Particle.SQUID_INK, loc().add(0, 1.6, 0), 14, 0.3, 0.3, 0.3, 0.02);
                    }
                    if (t == 56) line(ChatColor.WHITE + "This is a pickaxe you guys don't know..");
                    if (t >= 110) { state = BEDROCK; t = 0; layer = col(steps).getY() - 1; }
                }
                case BEDROCK -> {                                    // smashes down through the bedrock, a layer per blow
                    int tt = t % 24;
                    p = slamPose(tt);
                    if (tt == 13) {
                        Vector hc = holeCenter();
                        Location fx = new Location(world, hc.getX(), layer + 0.9, hc.getZ());
                        for (Block h : holeCells()) {
                            Block b = world.getBlockAt(h.getX(), layer, h.getZ());
                            clear(HOLE, b, true);
                            for (int y = layer; y <= layer + 2; y++) seal(HOLE, world.getBlockAt(h.getX(), y, h.getZ()));
                        }
                        world.spawnParticle(Particle.SQUID_INK, fx, 30, 0.7, 0.2, 0.7, 0.05);
                        world.spawnParticle(Particle.WHITE_ASH, fx, 40, 0.8, 0.4, 0.8, 0.02);
                        world.playSound(fx, Sound.BLOCK_DEEPSLATE_BREAK, SoundCategory.BLOCKS, 1.4f, 0.5f);
                        world.playSound(fx, Sound.ENTITY_WARDEN_ATTACK_IMPACT, SoundCategory.HOSTILE, 0.8f, 0.6f);
                        layer--;
                        if (layer < world.getMinHeight()) {
                            world.playSound(fx, Sound.BLOCK_END_PORTAL_SPAWN, SoundCategory.AMBIENT, 1f, 0.5f);
                            world.spawnParticle(Particle.REVERSE_PORTAL, fx, 120, 0.8, 1.5, 0.8, 0.05);
                            openUntil = now + (long) (c("stairs-open-seconds", 600) * 20);
                            state = TALK; t = 0; wait = 20;
                            script.clear();
                            script.add("There.");
                            script.add("Jump in. You'll land on a white path. Follow it, and don't step off.");
                            script.add("He's waiting at the end.");
                            script.add("Ten minutes. Then I seal it again, with or without you.");
                            script.add((Runnable) () -> { state = OPEN; t = 0; tool.setItemStack(new ItemStack(Material.AIR)); });
                        }
                    }
                }
                case OPEN -> {                                       // waits by the hole while people jump
                    look = nearest(16);
                    if (look != null) face(look.getLocation().toVector(), 6);
                    p = idlePose(life, false);
                    if (t % 6 == 0) {
                        Vector hc = holeCenter();
                        world.spawnParticle(Particle.WHITE_ASH, hc.getX(), world.getMinHeight() + 2, hc.getZ(), 6, 0.6, 1.5, 0.6, 0.01);
                    }
                    if (now >= openUntil - 200 && !warned) { warned = true; line("Ten more seconds."); }
                    if (now >= openUntil) {
                        line("Time's up.");
                        state = COVER_HOLE; t = 0; yaw = yawOf(dir);
                    }
                }
                case COVER_HOLE -> {                                 // fills the hole back in, bottom up
                    p = placePose(t);
                    if (t % 3 == 0) {
                        List<Block> g = groups.get(HOLE);
                        if (g == null || g.isEmpty()) {
                            groups.remove(HOLE); // (the landing is filled in once he's stepped off it)
                            state = COVER_WALK; t = 0; coverAt = steps + 1;
                            moveFrom = pos.clone(); moveTo = at(col(steps));
                        } else {
                            Block b = g.remove(g.size() - 1);
                            restore(b, entrance);
                            world.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3, 0, b.getBlockData());
                            if (t % 6 == 0) world.playSound(b.getLocation(), b.getBlockData().getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 0.8f, 0.8f);
                        }
                    }
                }
                case COVER_WALK -> {                                 // walks back up, the steps filling in behind him
                    float f = Math.min(1, t / 10f);
                    pos = moveFrom.clone().add(moveTo.clone().subtract(moveFrom).multiply(FaultlineBosses.ease(f)));
                    face(moveTo.clone().add(moveTo.clone().subtract(moveFrom)), 30);
                    p = stepPose(t + 4, 1f);
                    if (t >= 12) {
                        int done = coverAt;                          // the cell he just left
                        if (done == steps + 1) restoreGroup(LANDING_GROUP); else restoreGroup(done);
                        world.playSound(loc(), Sound.BLOCK_DEEPSLATE_PLACE, SoundCategory.BLOCKS, 0.7f, 0.8f);
                        coverAt--;
                        if (coverAt < 1) { restoreGroup(1); finish(); return; }
                        t = 0; moveFrom = pos.clone(); moveTo = at(col(coverAt - 1));
                    }
                }
                case LEAVING -> {
                    p = bowPose(t);
                    if (t >= 30) { remove(); return; }
                }
                default -> p = new Pose();
            }
            if (gestureT >= 0) {
                if ("take".equals(gesture)) p = takePose(gestureT);
                if (++gestureT > 40) gestureT = -1;
            }
            render(p, look);
        }

        void runScript() {
            if (wait > 0) { wait--; return; }
            while (!script.isEmpty()) {
                Object o = script.poll();
                if (o instanceof String s) { line(s); wait = 34 + ChatColor.stripColor(s).length(); return; }
                if (o instanceof Integer i) { wait = i; return; }
                if (o instanceof Runnable r) { r.run(); if (state != TALK) return; }
            }
            if (state == TALK && script.isEmpty() && wait <= 0) backToIdle();
        }

        void render(Pose p, Player look) {
            Location root = loc();
            if (click != null && click.isValid()) click.teleport(root);
            if (anchor != null && anchor.isValid()) anchor.teleport(root);
            float breath = (float) Math.sin(life * 0.08);
            p.add(BODY, breath * 1.2f, 0, 0);
            if (look != null && look.getWorld().equals(world)) {
                Vector to = look.getEyeLocation().toVector().subtract(pos.clone().add(new Vector(0, 1.6, 0)));
                float want = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
                float rel = ((want - yaw) % 360 + 540) % 360 - 180;
                float pitch = (float) -Math.toDegrees(Math.atan2(to.getY(), Math.max(0.5, Math.hypot(to.getX(), to.getZ()))));
                p.add(HEAD, Math.max(-35, Math.min(35, pitch)), -Math.max(-60, Math.min(60, rel)), 0);
            }
            shown = Pose.lerp(shown, p, 0.45f);
            pl.renderRig(parts, tool, root, yaw, 1f, shown);
        }

        void leave() {
            line(state == IDLE && met.isEmpty() ? "...never mind." : "Don't get lost.");
            state = LEAVING; t = 0;
        }

        void finish() {
            line("Don't get lost, surface-walker.");
            state = LEAVING; t = 0;
            groups.clear();
        }

        void remove() {
            if (gone) return;
            gone = true;
            if (!groups.isEmpty()) for (Integer g : new ArrayList<>(groups.keySet())) restoreGroup(g);
            if (world.isChunkLoaded(pos.getBlockX() >> 4, pos.getBlockZ() >> 4))
                world.spawnParticle(Particle.LARGE_SMOKE, loc().add(0, 1, 0), 16, 0.3, 0.6, 0.3, 0.02);
            for (ItemDisplay d : parts) if (d != null && d.isValid()) d.remove();
            if (tool != null && tool.isValid()) tool.remove();
            if (click != null && click.isValid()) click.remove();
            if (anchor != null && anchor.isValid()) anchor.remove();
        }
    }

    ItemStack blackPickaxe() {
        ItemStack s = new ItemStack(Material.NETHERITE_PICKAXE);
        ItemMeta m = s.getItemMeta();
        m.setItemModel(new NamespacedKey("faultline", "below_pickaxe"));
        m.setEnchantmentGlintOverride(true);
        s.setItemMeta(m);
        return s;
    }

    // =====================================================================================================
    //  putting blocks back
    // =====================================================================================================
    /** Puts one block back as it was, unless someone has built there since. Anyone standing in it is moved to `safe`. */
    void restore(Block b, Location safe) {
        Change ch = changes.remove(b);
        if (ch == null) return;
        dirty = true;
        Material cur = b.getType();
        boolean ours = cur == ch.placed || (ch.placed.isAir() && (cur.isAir() || b.isLiquid())) || (ch.placed == Material.LIGHT && cur.isAir());
        if (!ours) return;
        BoundingBox box = BoundingBox.of(b);
        for (Entity e : b.getWorld().getNearbyEntities(box.clone().expand(0.01))) {
            if (e instanceof Player p && p.getBoundingBox().overlaps(box)) {
                Location to = safe != null ? safe : p.getWorld().getSpawnLocation();
                p.teleport(to);
                p.sendMessage(swarmLine("Careful. You nearly got sealed in."));
            }
        }
        b.setBlockData(ch.original, false);
    }

    void restoreAll() {
        if (changes.isEmpty()) return;
        List<Block> all = new ArrayList<>(changes.keySet());
        Collections.reverse(all);
        for (Block b : all) restore(b, null);
        changes.clear();
        dirty = true;
    }

    // =====================================================================================================
    //  the hole: falling through it takes you to the void
    // =====================================================================================================
    void holeTick() {
        if (swarm == null || swarm.openUntil <= 0 || !swarm.groups.containsKey(HOLE)) return; // open from the last blow until it's sealed
        Vector hc = swarm.holeCenter();
        for (Player p : swarm.world.getPlayers()) {
            Location l = p.getLocation();
            if (l.getY() > swarm.world.getMinHeight() - 0.5) continue;
            if (Math.hypot(l.getX() - hc.getX(), l.getZ() - hc.getZ()) > 4) continue;
            returns.put(p.getUniqueId(), swarm.entrance.clone());
            dirty = true;
            sendToVoid(p);
        }
    }

    void sendToVoid(Player p) {
        World v = voidWorld();
        if (v == null) { p.sendMessage(ChatColor.RED + "The void world couldn't be loaded (see the console)."); return; }
        if (!returns.containsKey(p.getUniqueId())) { returns.put(p.getUniqueId(), p.getLocation()); dirty = true; }
        p.setFallDistance(0);
        p.teleport(voidSpawn(v));
        p.setFallDistance(0);
        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 80, 0, false, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 40, 0, false, false, false));
        p.playSound(p.getLocation(), Sound.BLOCK_END_PORTAL_SPAWN, SoundCategory.AMBIENT, 0.6f, 0.4f);
        p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, SoundCategory.AMBIENT, 1f, 0.6f);
        p.sendActionBar(legacy(ChatColor.WHITE + "" + ChatColor.ITALIC + "Below the Bedrock"));
        arrived.put(p.getUniqueId(), now);
        pl.console("index discover " + p.getName() + " swarm", p);
    }

    void sendBack(Player p) {
        Location to = returns.remove(p.getUniqueId());
        dirty = true;
        if (to == null || to.getWorld() == null) to = p.getRespawnLocation();
        if (to == null) to = Bukkit.getWorlds().get(0).getSpawnLocation();
        p.setFallDistance(0);
        p.teleport(to);
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0, false, false, false));
        p.playSound(p.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, SoundCategory.AMBIENT, 0.25f, 1.6f);
    }

    // =====================================================================================================
    //  the void world: black, a white path, and at its end, him
    // =====================================================================================================
    World voidWorld() {
        if (!enabled()) return null;
        World w = Bukkit.getWorld(voidName());
        if (w != null) return w;
        try {
            w = new WorldCreator(voidName()).generator(new PathGenerator()).environment(World.Environment.NORMAL)
                    .type(WorldType.NORMAL).generateStructures(false).createWorld();
        } catch (RuntimeException e) {
            pl.getLogger().log(java.util.logging.Level.SEVERE, "[The way down] couldn't create the void world", e);
            return null;
        }
        if (w == null) return null;
        w.setSpawnLocation(0, PATH_Y + 1, 0);
        w.setTime(18000);
        w.setStorm(false);
        w.setThundering(false);
        WorldBorder border = w.getWorldBorder();
        border.setCenter(0, ARENA_Z / 2.0);
        border.setSize(520);
        return w;
    }

    static Location voidSpawn(World v) { return new Location(v, 0.5, PATH_Y + 1, 0.5, 0, 0); }

    static int pathCenter(int z) { return (int) Math.round(7 * Math.sin((z - 4) / 24.0)); }

    /** Is there a path block under (x, z)? (The platform, the winding path, or the arena.) */
    static boolean isPath(int x, int z) {
        if (Math.abs(x) <= 3 && Math.abs(z) <= 3) return true;
        if (z >= 3 && z <= PATH_END && Math.abs(x - pathCenter(z)) <= 1) return true;
        int dz = z - ARENA_Z;
        return x * x + dz * dz <= ARENA_R * ARENA_R;
    }

    /** Black ring inlaid in the arena floor. */
    static boolean inlay(int x, int z) {
        int dz = z - ARENA_Z;
        double r = Math.sqrt(x * x + dz * dz);
        return r >= 12.5 && r < 13.5;
    }

    static boolean lit(int x, int z) {
        if (x == 0 && z == 0) return true;
        if (z >= 4 && z <= PATH_END && z % 4 == 0 && x == pathCenter(z)) return true;
        int dz = z - ARENA_Z;
        return x % 6 == 0 && dz % 6 == 0 && x * x + dz * dz <= (ARENA_R - 1) * (ARENA_R - 1);
    }

    /** Nothing but the white path (lit by invisible light blocks) under a black ceiling that hides the sky. */
    static final class PathGenerator extends ChunkGenerator {
        @Override
        public void generateNoise(WorldInfo info, Random random, int cx, int cz, ChunkData data) {
            int bx = cx << 4, bz = cz << 4;
            boolean inside = Math.abs(bx) < 300 && Math.abs(bz - ARENA_Z / 2) < 300;
            BlockData white = Material.WHITE_CONCRETE.createBlockData(), black = Material.BLACK_CONCRETE.createBlockData();
            BlockData light = Material.LIGHT.createBlockData();
            if (light instanceof Levelled lv) lv.setLevel(15);
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int wx = bx + x, wz = bz + z;
                if (inside) data.setBlock(x, CEILING_Y, z, black);
                if (isPath(wx, wz)) {
                    data.setBlock(x, PATH_Y, z, inlay(wx, wz) ? black : white);
                    if (lit(wx, wz)) data.setBlock(x, PATH_Y + 1, z, light);
                }
            }
        }

        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
        @Override public Location getFixedSpawnLocation(World world, Random random) { return voidSpawn(world); }

        @Override
        public BiomeProvider getDefaultBiomeProvider(WorldInfo info) {
            return new BiomeProvider() {
                @Override public Biome getBiome(WorldInfo i, int x, int y, int z) { return Biome.THE_VOID; }
                @Override public List<Biome> getBiomes(WorldInfo i) { return List.of(Biome.THE_VOID); }
            };
        }
    }

    boolean inVoid(World w) { return w != null && w.getName().equals(voidName()); }

    /** Every 5 ticks: falling off the path, the way back, the rift's glow, the statue, and the endless midnight. */
    void voidTick() {
        World v = Bukkit.getWorld(voidName());
        if (v == null) return;
        if (now % 200 == 0) v.setTime(18000);
        List<Player> ps = v.getPlayers();
        if (ps.isEmpty()) {
            if (statue != null) { statue.remove(); statue = null; }
            if (riftLabel != null && riftLabel.isValid()) riftLabel.remove();
            return;
        }
        Location rift = new Location(v, 0.5, PATH_Y + 1, -2.5);
        v.spawnParticle(Particle.END_ROD, rift.clone().add(0, 1.2, 0), 3, 0.15, 0.9, 0.15, 0.005);
        v.spawnParticle(Particle.WHITE_ASH, rift.clone().add(0, 1, 0), 8, 0.4, 1, 0.4, 0);
        if (riftLabel == null || !riftLabel.isValid()) {
            riftLabel = v.spawn(rift.clone().add(0, 2.6, 0), TextDisplay.class, d -> {
                d.text(legacy(ChatColor.WHITE + "The way back"));
                d.setBillboard(Display.Billboard.CENTER);
                d.setPersistent(false);
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                d.addScoreboardTag(TAG);
            });
        }
        for (Player p : ps) {
            Location l = p.getLocation();
            if (l.getY() < PATH_Y - 12) { // fell off: the dark puts you back at the start
                p.setFallDistance(0);
                p.teleport(voidSpawn(v));
                p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false, false));
                p.sendActionBar(legacy(ChatColor.GRAY + "" + ChatColor.ITALIC + "The dark spits you back onto the path."));
                arrived.put(p.getUniqueId(), now);
                continue;
            }
            boolean settled = now - arrived.getOrDefault(p.getUniqueId(), -1000) > 60;
            if (settled && Math.abs(l.getX() - rift.getX()) < 0.75 && Math.abs(l.getZ() - rift.getZ()) < 0.75 && Math.abs(l.getY() - rift.getY()) < 1.5) {
                sendBack(p);
            }
        }
        boolean near = false;
        for (Player p : ps) if (p.getWorld().equals(v) && p.getLocation().distanceSquared(new Location(v, 0, PATH_Y, ARENA_Z)) < 140 * 140) near = true; // (some may have just left)
        if (near) {
            if (statue == null) statue = new Statue(v);
            statue.tick();
        } else if (statue != null) { statue.remove(); statue = null; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (inVoid(event.getBlock().getWorld()) && event.getPlayer().getGameMode() != GameMode.CREATIVE) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (inVoid(event.getBlock().getWorld()) && event.getPlayer().getGameMode() != GameMode.CREATIVE) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        if (inVoid(event.getLocation().getWorld())) event.blockList().clear();
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!inVoid(event.getLocation().getWorld())) return;
        CreatureSpawnEvent.SpawnReason r = event.getSpawnReason();
        if (r == CreatureSpawnEvent.SpawnReason.NATURAL || r == CreatureSpawnEvent.SpawnReason.PATROL || r == CreatureSpawnEvent.SpawnReason.REINFORCEMENTS) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onWeather(WeatherChangeEvent event) {
        if (inVoid(event.getWorld()) && event.toWeatherState()) event.setCancelled(true);
    }

    // =====================================================================================================
    //  the Lost Explorer, waiting at the end of the path (the fight comes later)
    // =====================================================================================================
    static final float STATUE_SCALE = 1.5f;

    final class Statue {
        final World world;
        final Location root;
        final ItemDisplay[] parts = new ItemDisplay[6];
        final ItemDisplay axe;
        final Interaction click;
        final ArmorStand anchor;
        Pose shown = new Pose();
        float headYaw, headPitch;
        int t;

        Statue(World w) {
            world = w;
            root = new Location(w, 0.5, PATH_Y + 1, ARENA_Z + 0.5, 180, 0); // the middle of the white circle
            String[] pieces = {"_leg_r", "_leg_l", "_body", "_arm_r", "_arm_l", "_head"};
            for (int i = 0; i < 6; i++) { parts[i] = pl.spawnDisplay(root, "explorer" + pieces[i], STATUE_SCALE, 3, Display.Billboard.FIXED); parts[i].setViewRange(8f); parts[i].addScoreboardTag(STATUE_TAG); }
            axe = w.spawn(root, ItemDisplay.class, d -> {
                d.setItemStack(modelItem("explorer_axe"));
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.THIRDPERSON_RIGHTHAND);
                d.setTeleportDuration(3); d.setInterpolationDuration(3); d.setViewRange(8f);
                d.setBrightness(new Display.Brightness(13, 13));
                d.setPersistent(false);
                d.addScoreboardTag(DISPLAY_TAG); d.addScoreboardTag(STATUE_TAG);
            });
            click = w.spawn(root, Interaction.class, x -> {
                x.setInteractionWidth(1.4f); x.setInteractionHeight(3.4f); x.setResponsive(true);
                x.setPersistent(false); x.addScoreboardTag(STATUE_TAG);
            });
            anchor = w.spawn(root, ArmorStand.class, a -> {
                a.setVisibleByDefault(false); a.setInvisible(true); a.setMarker(true); a.setGravity(false);
                a.setInvulnerable(true); a.setPersistent(false); a.setRotation(180, 0); a.addScoreboardTag(STATUE_TAG);
            });
            pl.proxy(anchor, anchor, EntityType.WITHER_SKELETON, STATUE_SCALE);
        }

        void tick() {
            t++;
            if (!click.isValid() || !anchor.isValid()) { remove(); statue = null; return; }
            for (ItemDisplay d : parts) if (d == null || !d.isValid()) { remove(); statue = null; return; }
            // he stands with the axe planted, hands on its haft; his head follows whoever comes closest
            Player look = null;
            double bd = 36 * 36;
            for (Player p : world.getPlayers()) { double d = p.getLocation().distanceSquared(root); if (d < bd) { bd = d; look = p; } }
            float wantYaw = 0, wantPitch = 0;
            if (look != null) {
                Vector to = look.getEyeLocation().toVector().subtract(root.toVector().add(new Vector(0, 2.4, 0)));
                float want = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
                wantYaw = Math.max(-55, Math.min(55, ((want - 180) % 360 + 540) % 360 - 180));
                wantPitch = (float) Math.max(-30, Math.min(30, -Math.toDegrees(Math.atan2(to.getY(), Math.max(0.5, Math.hypot(to.getX(), to.getZ()))))));
            }
            headYaw += (wantYaw - headYaw) * 0.04f;   // slowly. He's in no hurry.
            headPitch += (wantPitch - headPitch) * 0.04f;
            float b = (float) Math.sin(t * 0.05);
            Pose p = new Pose().set(ARM_R, -32, -8, -4).set(ARM_L, -30, 34, 8).set(BODY, 3 + b, 0, 0)
                    .set(LEG_R, 0, 0, -4).set(LEG_L, 0, 0, 4).set(HEAD, headPitch, -headYaw, 0);
            shown = Pose.lerp(shown, p, 0.3f);
            pl.renderRig(parts, axe, root, 180, STATUE_SCALE, shown);
            click.teleport(root);
            anchor.teleport(root);
            if (t % 8 == 0) world.spawnParticle(Particle.WHITE_ASH, root.clone().add(0, 1.5, 0), 4, 0.8, 1.2, 0.8, 0);
        }

        void talk(Player p) {
            p.sendMessage(ChatColor.WHITE + "" + ChatColor.BOLD + "The Lost Explorer" + ChatColor.DARK_GRAY + " » " + ChatColor.GRAY + "" + ChatColor.ITALIC + "...");
            p.sendActionBar(legacy(ChatColor.GRAY + "He looks at you, and says nothing. Not yet."));
            p.playSound(root, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 1f, 0.5f);
        }

        void remove() {
            for (ItemDisplay d : parts) if (d != null && d.isValid()) d.remove();
            if (axe.isValid()) axe.remove();
            if (click.isValid()) click.remove();
            if (anchor.isValid()) anchor.remove();
        }
    }

    // =====================================================================================================
    //  Swarm's animations (pure functions of time; arms/legs: negative X = forward/up, right arm out = negative Z)
    // =====================================================================================================
    /** Waiting: weight on one leg, left hand resting on his lantern, looking around when nobody's near. */
    static Pose idlePose(int t, boolean lookAround) {
        float sway = (float) Math.sin(t * 0.04) * 2;
        Pose p = new Pose().set(ARM_L, -14, 0, 14).set(ARM_R, 4, 0, -6).set(BODY, 2, 0, sway)
                .set(LEG_R, 0, 0, -3).set(LEG_L, -3, 0, 5);
        if (lookAround) p.add(HEAD, 4, (float) Math.sin(t * 0.021) * 40, 0);
        return p;
    }

    /** Talking: the right hand turns over as he speaks, little nods, a slow sway. */
    static Pose talkPose(int t) {
        float g = (float) Math.max(0, Math.sin(t * 0.11));
        float nod = (float) Math.sin(t * 0.27) * 4;
        return new Pose().set(ARM_R, -30 - 22 * g, -18 * g, -10).set(ARM_L, -14, 0, 14).set(BODY, 4, (float) Math.sin(t * 0.05) * 5, 0)
                .set(HEAD, nod, 0, (float) Math.sin(t * 0.07) * 4).set(LEG_L, -3, 0, 4);
    }

    /** Takes the Fist: reaches out, weighs it, tucks it away in his cloak. */
    static Pose takePose(int t) {
        return FaultlineBosses.anim(t, new int[]{0, 8, 18, 26, 36, 40},
                new Pose[]{talkPose(0), new Pose().set(ARM_R, -82, -10, 0).set(BODY, 10, 0, 0).set(HEAD, 12, 0, 0),
                        new Pose().set(ARM_R, -70, 25, 0).set(BODY, 4, 0, 0).set(HEAD, 22, 8, 0),
                        new Pose().set(ARM_R, -50, 50, 0).set(HEAD, 10, 0, 0),
                        new Pose().set(ARM_R, 10, 0, 4).set(ARM_L, -14, 0, 14), idlePose(0, false)});
    }

    /** A step (walking, or down a stair). */
    static Pose stepPose(int t, float a) {
        float s = (float) Math.sin(t * 0.45) * 30 * a;
        return new Pose().set(LEG_R, s, 0, 0).set(LEG_L, -s, 0, 0).set(ARM_R, -40, 0, -6).set(ARM_L, s * 0.7f, 0, 6).set(BODY, 8 * a, 0, 0).set(HEAD, 4, 0, 0);
    }

    static Pose swingRest() { return new Pose().set(ARM_R, -50, 0, -6).set(ARM_L, -30, 0, 10).set(BODY, 6, 0, 0); }

    /** One pickaxe swing (18 ticks): over the head, down hard at tick 7, recover. */
    static Pose swingPose(int t) {
        return FaultlineBosses.anim(t, new int[]{0, 5, 8, 14, 18}, new Pose[]{swingRest(),
                new Pose().set(ARM_R, -165, 10, -8).set(ARM_L, -140, -10, 10).set(BODY, -10, 0, 0).set(HEAD, -6, 0, 0).set(LEG_R, -6, 0, 0).set(LEG_L, 8, 0, 0),
                new Pose().set(ARM_R, -40, 0, -4).set(ARM_L, -35, 0, 6).set(BODY, 24, 0, 0).set(HEAD, 14, 0, 0).set(LEG_R, -10, 0, 0).set(LEG_L, 10, 0, 0).drop(-0.06f),
                new Pose().set(ARM_R, -55, 0, -6).set(ARM_L, -35, 0, 10).set(BODY, 10, 0, 0), swingRest()});
    }

    /** Puts his pick away over his shoulder, reaches behind his back, draws the black one and holds it up to look at it. */
    static Pose drawPose(int t) {
        return FaultlineBosses.anim(t, new int[]{0, 14, 30, 44, 58, 80, 110}, new Pose[]{swingRest(),
                new Pose().set(ARM_R, -170, 0, 30).set(HEAD, 0, -20, 0).set(BODY, 0, -15, 0),          // the old pick goes over his shoulder
                new Pose().set(ARM_R, -170, 0, 32).set(ARM_L, -20, 0, 10).set(HEAD, 6, -25, 0).set(BODY, 4, -18, 0),
                new Pose().set(ARM_R, 30, -40, 25).set(BODY, 8, 20, 0).set(HEAD, 10, 30, 0),          // reaching behind his back
                new Pose().set(ARM_R, -118, -25, -6).set(ARM_L, -30, 0, 10).set(HEAD, -8, -10, 0),    // out it comes
                new Pose().set(ARM_R, -100, -45, 0).set(ARM_L, -45, 30, 10).set(HEAD, -14, -18, 0).set(BODY, -4, 0, 0), // holds it up
                new Pose().set(ARM_R, -96, -40, 0).set(ARM_L, -40, 25, 10).set(HEAD, -10, -12, 0).set(BODY, -3, 0, 0)});
    }

    /** A heavy two-handed blow into the bedrock (24 ticks, lands at 13). */
    static Pose slamPose(int t) {
        return FaultlineBosses.anim(t, new int[]{0, 9, 13, 18, 24}, new Pose[]{
                new Pose().set(ARM_R, -60, 0, -4).set(ARM_L, -50, 0, 8).set(BODY, 8, 0, 0),
                new Pose().set(ARM_R, -178, 0, -4).set(ARM_L, -165, 0, 6).set(BODY, -18, 0, 0).set(HEAD, -12, 0, 0).set(LEG_R, -14, 0, 0).set(LEG_L, 14, 0, 0).drop(0.03f),
                new Pose().set(ARM_R, -18, 0, -4).set(ARM_L, -16, 0, 6).set(BODY, 38, 0, 0).set(HEAD, 22, 0, 0).set(LEG_R, -36, 0, 0).set(LEG_L, 30, 0, 0).drop(-0.28f),
                new Pose().set(ARM_R, -24, 0, -4).set(ARM_L, -20, 0, 6).set(BODY, 34, 0, 2).set(HEAD, 18, 0, 0).set(LEG_R, -32, 0, 0).set(LEG_L, 28, 0, 0).drop(-0.24f),
                new Pose().set(ARM_R, -60, 0, -4).set(ARM_L, -50, 0, 8).set(BODY, 12, 0, 0).drop(-0.04f)});
    }

    /** Down on one knee, patting blocks into place. */
    static Pose placePose(int t) {
        float s = (float) Math.max(0, Math.sin(t * 0.7));
        return new Pose().drop(-0.5f).set(BODY, 26, 0, 0).set(HEAD, 20, 0, 0).set(ARM_R, -62 + 28 * s, 0, -6).set(ARM_L, -30, 0, 14)
                .set(LEG_R, -85, 0, 0).set(LEG_L, 75, 0, 0);
    }

    /** Leaving: pulls his hood down over his face, a small bow, and he's smoke. */
    static Pose bowPose(int t) {
        return FaultlineBosses.anim(t, new int[]{0, 12, 30}, new Pose[]{idlePose(0, false),
                new Pose().set(ARM_L, -122, 40, 0).set(HEAD, 20, 0, 0).set(BODY, 14, 0, 0),
                new Pose().set(ARM_L, -118, 42, 0).set(HEAD, 28, 0, 0).set(BODY, 22, 0, 0).drop(-0.05f)});
    }

    // =====================================================================================================
    //  /below
    // =====================================================================================================
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("bosses.admin")) { sender.sendMessage(ChatColor.RED + "You don't have permission to do that."); return true; }
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
        switch (sub) {
            case "swarm" -> {
                if (target == null) { sender.sendMessage(ChatColor.RED + "Who? /below swarm [player]"); return true; }
                Location at = spotNear(target);
                if (at == null) at = target.getLocation().add(Vendetta.flatDir(target.getLocation()).multiply(3));
                spawnSwarm(at, target);
                swarm.faceNow(target.getLocation().toVector());
                sender.sendMessage(ChatColor.GREEN + "Swarm is near " + target.getName() + ". (In creative you skip the Jacob and Fist checks.)");
            }
            case "void" -> {
                if (target == null) { sender.sendMessage(ChatColor.RED + "Who? /below void [player]"); return true; }
                if (!inVoid(target.getWorld())) { returns.put(target.getUniqueId(), target.getLocation()); dirty = true; }
                sendToVoid(target);
            }
            case "leave" -> {
                if (target == null) { sender.sendMessage(ChatColor.RED + "Who? /below leave [player]"); return true; }
                sendBack(target);
            }
            case "close" -> {
                if (swarm == null) { sender.sendMessage(ChatColor.GRAY + "Swarm isn't around."); return true; }
                swarm.remove();
                swarm = null;
                sender.sendMessage(ChatColor.GREEN + "Swarm is gone and everything he dug is filled back in.");
            }
            case "slayer", "unslayer" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "/below " + sub + " <player>"); return true; }
                @SuppressWarnings("deprecation") org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(args[1]);
                if (sub.equals("slayer")) slayers.add(op.getUniqueId()); else slayers.remove(op.getUniqueId());
                save();
                sender.sendMessage(ChatColor.GREEN + args[1] + (sub.equals("slayer") ? " now counts as having killed Diamond Jacob." : " no longer counts as having killed Diamond Jacob."));
            }
            case "info" -> {
                sender.sendMessage(ChatColor.AQUA + "Swarm: " + ChatColor.WHITE + (swarm == null ? "not around" : "state " + swarm.state + " at "
                        + swarm.pos.getBlockX() + ", " + swarm.pos.getBlockY() + ", " + swarm.pos.getBlockZ() + " (" + swarm.world.getName() + ")"));
                sender.sendMessage(ChatColor.AQUA + "Blocks waiting to be put back: " + ChatColor.WHITE + changes.size());
                sender.sendMessage(ChatColor.AQUA + "Void world: " + ChatColor.WHITE + (Bukkit.getWorld(voidName()) != null ? voidName() + " (loaded)" : "not loaded"));
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "/below swarm [player] | void [player] | leave [player] | close | slayer <player> | unslayer <player> | info");
        }
        return true;
    }
}
