package net.faultlinesmp.deep;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * HALCYON'S PEOPLE (Deep 1.17.0): every citizen is a real villager, with a name, a home (a bed in one of the 16 houses, or the
 * barracks for the guards), a job and a day. They exist while the city is awake (someone near), and walk only along the
 * city's streets (the ring road, the avenues, the diagonal paths: planned in `route()`), moved a little every tick
 * (their AI is off: no vanilla villager business, no wandering off, no zombies).
 *  - THE DAY (the Dark Ocean world's clock): up at dawn and breakfast at home, work, lunch at the Kelp & Kettle (or at
 *    work), back to work, an evening on the plaza, then home to bed (they really sleep in their beds). Guards work in two
 *    shifts; the night shift sleeps by day.
 *  - THE JOBS: farmers harvest and tend the real crops, woodcutters chop in the grove and stack logs, miners go down the
 *    stair tunnel and dig at the coal faces, builders put up the house on the building site block by block (and when it's
 *    done and inspected, it comes down and they start again), scientists work the Bio-Center's jars and benches, aquarists
 *    feed the tanks, merchants keep the market stalls, the cook runs the tavern kitchen, the curator minds the Aquarium,
 *    children play on the plaza.
 *  - TRADING: right-click anyone with a trade = their trades (vanilla style, by profession; the Dock Master sells the
 *    Submersible for 128 emeralds). Guards and children don't trade. Nobody can be leashed, ridden, named, bucketed,
 *    zapped into a witch or bitten into a zombie.
 *  - HURTING THEM: they scream, everyone near runs home, and you're WANTED (HalcyonCity). The guards come for you: they
 *    run you down, hit hard, and shoot from range. Killing a citizen: wanted 2; they come back after `respawn-minutes`.
 */
final class Citizens implements Listener {
    static final String TAG = "faultline_citizen", BUBBLE_TAG = "faultline_citizen_bubble";

    enum Job {
        FARMER("Farmer", Villager.Profession.FARMER), WOODCUTTER("Woodcutter", Villager.Profession.FLETCHER), MINER("Miner", Villager.Profession.TOOLSMITH),
        BUILDER("Builder", Villager.Profession.MASON), SCIENTIST("Scientist", Villager.Profession.CLERIC), AQUARIST("Aquarist", Villager.Profession.FISHERMAN),
        MERCHANT("Merchant", Villager.Profession.LIBRARIAN), COOK("Cook", Villager.Profession.BUTCHER), CURATOR("Curator", Villager.Profession.LIBRARIAN),
        IDLER("Layabout", Villager.Profession.NITWIT), GUARD("City Guard", Villager.Profession.WEAPONSMITH), CHILD("Child", Villager.Profession.NONE),
        DOCKMASTER("Dock Master", Villager.Profession.CARTOGRAPHER);
        final String title;
        final Villager.Profession prof;
        Job(String title, Villager.Profession prof) { this.title = title; this.prof = prof; }
    }

    enum Act { SLEEP, BREAKFAST, WORK, LUNCH, EVENING, PATROL, PLAY, STAY, PANIC, CHASE }

    /** A place a citizen can stand: the ring junction (degrees) + the points from the ring out to it (rel x, y, rel z). */
    record Spot(String key, double angle, List<double[]> pts, double face) {
        double[] end() { return pts.get(pts.size() - 1); }
    }

    final class Person {
        final int id;
        final String name;
        final Job job;
        final int house, bed;          // house index (-1 none), bed 0/1
        int stall = -1, guardBed = -1;
        boolean night;                 // a night-shift guard
        Villager.Profession prof;
        Villager.Type type;
        Villager v;
        Spot at, goal;
        final Deque<double[]> route = new ArrayDeque<>();
        double speed = 0.11;
        Act act, doing;
        int t, until, workAt;
        long deadUntil, panicUntil;
        UUID chasing;
        int attackCd, shotCd;
        double[] pos;                  // rel x, y, rel z right now
        float yaw;
        TextDisplay bubble;
        int bubbleT;
        Person(int id, String name, Job job, int house, int bed) { this.id = id; this.name = name; this.job = job; this.house = house; this.bed = bed; }
        boolean alive() { return v != null && v.isValid() && !v.isDead(); }
        boolean onDuty(long time) { return job == Job.GUARD && (night ? time >= 12000 || time < 1000 : time >= 0 && time < 13000); }
    }

    final FaultlineDeep pl;
    final HalcyonCity city;
    final List<Person> people = new ArrayList<>();
    final Map<UUID, Person> byEntity = new HashMap<>();
    final Map<String, Spot> spots = new HashMap<>();
    final Random random = new Random();
    int ticks, planDone = -1;
    long planCompleteAt;

    static final String[] FIRST = {"Ada", "Bram", "Cora", "Dmitri", "Edda", "Finn", "Greta", "Hal", "Ines", "Jory", "Kesh", "Lio", "Mara", "Nils", "Oona",
            "Pell", "Quill", "Rhea", "Soren", "Tilda", "Ulf", "Vera", "Wim", "Xan", "Yara", "Zeb", "Orrin", "Maeve", "Tobin", "Sable", "Corin", "Hedda",
            "Nell", "Arlo", "Brin", "Isla", "Kip", "Luca", "Mira", "Tamsin", "Ezra", "Juno", "Rook"};

    Citizens(FaultlineDeep pl, HalcyonCity city) {
        this.pl = pl;
        this.city = city;
        buildSpots();
        buildRoster();
    }

    double cfg(String path, double def) { return pl.getConfig().getDouble("halcyon.citizens." + path, def); }

    // =========================================================================================== the street network
    static double[] ring(double deg) { double a = Math.toRadians(deg); return new double[]{Math.cos(a) * Halcyon.RING_R, Halcyon.G, Math.sin(a) * Halcyon.RING_R}; }

    static double[] pt(double x, double z) { return new double[]{x, Halcyon.G, z}; }

    static double[] pt(double x, double y, double z) { return new double[]{x, y, z}; }

    static double angleOf(double x, double z) { return Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(z, x))), 360); }

    void spot(String key, double angle, double face, double[]... pts) {
        List<double[]> l = new ArrayList<>();
        l.add(ring(angle));
        for (double[] p : pts) l.add(p);
        spots.put(key, new Spot(key, angle, l, face));
    }

    void buildSpots() {
        // homes: in, eat, bed sides
        for (Halcyon.House h : Halcyon.HOUSES) {
            double a = Halcyon.HOUSE_ANGLES[h.i()];
            double[] porch = rel(h.porch()), door = rel(h.door()), in = rel(h.world(4, 2)), eat = rel(h.world(4, 3));
            double faceIn = yawTo(door, in);
            spot("home" + h.i(), a, faceIn, porch, door, in);
            spot("home" + h.i() + "_eat", a, yawTo(eat, rel(h.table())), porch, door, in, eat);
            spot("home" + h.i() + "_bed0", a, faceIn, porch, door, in, rel(h.world(2, 4)));
            spot("home" + h.i() + "_bed1", a, faceIn, porch, door, in, rel(h.world(Halcyon.HW - 3, 4)));
        }
        // the Bio-Center
        double[] labIn = pt(0, -54);
        int[][] jars = Halcyon.JARS;
        for (int i = 0; i < jars.length; i++) {
            int[] j = jars[i];
            int sx = j[0] < 0 ? -1 : 1;
            if (Math.abs(j[0]) == 17) spot("lab_jar" + i, 270, sx < 0 ? 90 : 270, pt(0, -40), pt(0, -50), labIn, pt(sx * 11, -62), pt(sx * 13, j[1]));
            else spot("lab_jar" + i, 270, 180, pt(0, -40), pt(0, -50), labIn, pt(sx * 11, -62), pt(sx * 11, -79), pt(j[0], -80));
        }
        spot("lab_console", 270, 180, pt(0, -40), pt(0, -50), labIn, pt(11, -62), pt(11, -79), pt(3, -79), pt(0, -83));
        spot("lab_bench0", 270, 180, pt(0, -40), pt(0, -50), labIn, pt(-10, -55));
        spot("lab_bench1", 270, 180, pt(0, -40), pt(0, -50), labIn, pt(10, -55));
        spot("lab_tank", 270, 180, pt(0, -40), pt(0, -50), labIn, pt(0, -63));
        // the Aquarium
        double[] aqIn = pt(55, 0);
        for (int s : new int[]{-1, 1}) for (int x = 60; x <= 84; x += 6)
            spot("aq_" + (s < 0 ? "n" : "s") + x, 0, s < 0 ? 180 : 0, pt(44, 0), pt(51, 0), aqIn, pt(58, 0), pt(58, s * 10), pt(x, s * 10));
        int[] cur = Halcyon.curator();
        spot("aq_curator", 0, 270, pt(44, 0), pt(51, 0), aqIn, pt(58, 0), pt(58, cur[1] - Halcyon.HZ), pt(cur[0] - Halcyon.HX, cur[1] - Halcyon.HZ));
        // the south avenue: the tavern, the barracks, the dock office
        double[] sAve = pt(0, 45), tavDoor = pt(0, 63);
        spot("tavern", 90, 270, sAve, tavDoor, pt(-6, 63), pt(-11, 63));
        double[][] seats = {{-15.5, 57.5}, {-15.5, 59.5}, {-15.5, 64.5}, {-15.5, 67.5}, {-10.5, 58}, {-10.5, 66}};
        for (int i = 0; i < seats.length; i++) spot("tavern_seat" + i, 90, 180, sAve, tavDoor, pt(-6, 63), pt(-11, 63), pt(-15.5, 62), pt(seats[i][0], seats[i][1]));
        spot("tavern_cook", 90, 90, sAve, tavDoor, pt(-6, 63), pt(-11, 63), pt(-11, 70.5), pt(-24.5, 70.5), pt(-24.5, 63));
        spot("barracks", 90, 90, sAve, tavDoor, pt(6, 63), pt(11, 63));
        int[][] gb = Halcyon.barrackBeds();
        for (int i = 0; i < gb.length; i++) spot("barracks_bed" + i, 90, 90, sAve, tavDoor, pt(6, 63), pt(11, 63), pt(gb[i][0], gb[i][1] < 63 ? 58 : 68));
        int[] dm = Halcyon.dockMaster();
        spot("dockmaster", 90, 270, sAve, tavDoor, pt(0, 85), pt(dm[0] - Halcyon.HX, dm[1] - Halcyon.HZ));
        spot("dock_gate", 90, 90, sAve, tavDoor, pt(0, 92));
        // the market
        int[][] st = Halcyon.stalls();
        for (int i = 0; i < st.length; i++) {
            int sx = st[i][0], sz = st[i][1], side = st[i][2];
            spot("stall" + i, 180, side < 0 ? 90 : 270, pt(-40, 0), pt(sx + 3, 0), pt(sx + 3, sz + side), pt(sx, sz + side));
        }
        spot("west_gate", 180, 180, pt(-40, 0), pt(-92, 0));
        // the plaza: spots round its edge
        for (int a = 0; a < 360; a += 30) {
            double r = Math.toRadians(a + 15);
            spot("plaza" + a, a + 15, a + 195, pt(Math.cos(r) * 23.5, Math.sin(r) * 23.5));
        }
        // the farms: walk the lane, then along a glowing path row
        double fa = angleOf(-46, 40);
        for (int row = Halcyon.FARM_Z0 + 4; row <= Halcyon.FARM_Z1 - 4; row += 4)
            for (int x = -50; x >= -78; x -= 7) if (Halcyon.farmCell(x, row + 1) && Halcyon.farmCell(x, row - 1) && Halcyon.e(Halcyon.HX + x, Halcyon.HF, Halcyon.HZ + row) < 0.9) spot("farm_" + row + "_" + x, fa, 180, pt(-46, 40), pt(-45, 40), pt(-45, row), pt(x, row));
        // the grove
        double ga = angleOf(46, 40);
        for (int i = 0; i < Halcyon.TREES.size(); i++) {
            int[] tr = Halcyon.TREES.get(i);
            // along the lane (x 46) to the gap between its row and the next, then in beside its trunk: never through a tree
            spot("tree" + i, ga, 270, pt(46, 40), pt(46, tr[1] + 3), pt(tr[0] - 2, tr[1] + 3), pt(tr[0] - 2, tr[1]));
        }
        spot("logpile", ga, 180, pt(46, 40), pt(45.5, 36.5));
        // the building site: round its edge
        double sa = angleOf(45, -52);
        for (int x = 39; x <= 51; x += 3) spot("site_n" + x, sa, 0, pt(45, -52), pt(45, -55), pt(x, -55));
        for (int z = -58; z >= -65; z -= 3) {
            spot("site_w" + z, sa, 0, pt(45, -52), pt(45, -55), pt(39, -55), pt(39, z));
            spot("site_e" + z, sa, 0, pt(45, -52), pt(45, -55), pt(51, -55), pt(51, z));
        }
        // the mine: across the headframe, down the stair tunnel, into the coal gallery
        double ma = 225;
        int gy = Halcyon.G - Halcyon.DEPTH;
        List<double[]> dl = new ArrayList<>(List.of(pt(-45, -45), pt(-50, -46), pt(-51, -55)));
        // each step: across it at its height, out over the next one, then straight down onto it (never through the step's edge)
        for (int i = 0; i <= Halcyon.DEPTH; i++) {
            dl.add(pt(-52 - i + 0.45, Halcyon.G - i, -55));
            dl.add(pt(-52 - i - 0.45, Halcyon.G - i, -55));
            if (i < Halcyon.DEPTH) dl.add(pt(-52 - i - 0.6, Halcyon.G - i, -55));
        }
        dl.add(pt(-71, gy, -55)); dl.add(pt(-72, gy, -57.5));
        double[][] down = dl.toArray(new double[0][]);
        int[][] faces = Halcyon.coalFaces();
        for (int i = 0; i < faces.length; i++) {
            int[] f = faces[i];
            List<double[]> l = new ArrayList<>(List.of(down));
            if (i == 0 || i == 1) { l.add(pt(-83, gy, -57.5)); }
            else if (i == 2) l.add(pt(-78, gy, -57.5));
            else l.add(pt(-72, gy, -50));
            l.add(pt(f[3], gy, f[5]));
            spot("mine" + i, ma, yawTo(pt(f[3], gy, f[5]), pt(f[0], gy, f[2])), l.toArray(new double[0][]));
        }
        // guard posts
        spot("post_lab", 270, 90, pt(0, -40), pt(0, -48));
        spot("post_aq", 0, 180, pt(44, 0), pt(48, 0));
        for (int a = 0; a < 360; a += 45) spot("ring" + a, a, a + 90);
    }

    static double[] rel(int[] w) { return pt(w[0] - Halcyon.HX, w[1] - Halcyon.HZ); }

    static float yawTo(double[] from, double[] to) { return (float) Math.toDegrees(Math.atan2(-(to[0] - from[0]), to[2] - from[2])); }

    /** The way between two spots: back out of one along its chain, round the ring, in along the other's (shared start trimmed). */
    List<double[]> route(Spot from, Spot to) {
        List<double[]> out = new ArrayList<>();
        if (from == null || to == null || from == to) return out;
        int k = 0;
        if (Math.abs(from.angle() - to.angle()) < 0.01)
            while (k < from.pts().size() && k < to.pts().size() && same(from.pts().get(k), to.pts().get(k))) k++;
        if (k == 0) {
            for (int i = from.pts().size() - 2; i >= 0; i--) out.add(from.pts().get(i));
            double a = from.angle(), b = to.angle(), d = Math.IEEEremainder(b - a, 360);
            int n = (int) Math.ceil(Math.abs(d) / 5);
            for (int i = 1; i < n; i++) out.add(ring(a + d * i / n));
            out.addAll(to.pts());
        } else {
            for (int i = from.pts().size() - 2; i >= k - 1; i--) out.add(from.pts().get(i));
            for (int i = k; i < to.pts().size(); i++) out.add(to.pts().get(i));
        }
        return out;
    }

    static boolean same(double[] a, double[] b) { return Math.abs(a[0] - b[0]) < 1e-6 && Math.abs(a[1] - b[1]) < 1e-6 && Math.abs(a[2] - b[2]) < 1e-6; }

    // =========================================================================================== who lives here
    void buildRoster() {
        List<Job> jobs = new ArrayList<>(List.of(Job.FARMER, Job.FARMER, Job.FARMER, Job.WOODCUTTER, Job.WOODCUTTER, Job.MINER, Job.MINER, Job.MINER,
                Job.BUILDER, Job.BUILDER, Job.BUILDER, Job.SCIENTIST, Job.SCIENTIST, Job.SCIENTIST, Job.SCIENTIST, Job.AQUARIST, Job.AQUARIST,
                Job.MERCHANT, Job.MERCHANT, Job.MERCHANT, Job.MERCHANT, Job.MERCHANT, Job.MERCHANT, Job.COOK, Job.CURATOR, Job.IDLER));
        Random r = new Random(7341);
        java.util.Collections.shuffle(jobs, r);
        int n = 0, stall = 0;
        Villager.Profession[] stallProf = {Villager.Profession.LIBRARIAN, Villager.Profession.CARTOGRAPHER, Villager.Profession.BUTCHER,
                Villager.Profession.LEATHERWORKER, Villager.Profession.SHEPHERD, Villager.Profession.ARMORER};
        Villager.Type[] types = {Villager.Type.PLAINS, Villager.Type.SWAMP, Villager.Type.TAIGA, Villager.Type.SNOW, Villager.Type.SAVANNA, Villager.Type.JUNGLE, Villager.Type.DESERT};
        for (Halcyon.House h : Halcyon.HOUSES) for (int b = 0; b < h.beds() && n < jobs.size(); b++) {
            Job j = jobs.get(n);
            Person p = new Person(people.size(), FIRST[people.size() % FIRST.length], j, h.i(), b);
            p.prof = j.prof;
            if (j == Job.MERCHANT) { p.stall = stall; p.prof = stallProf[stall % stallProf.length]; stall++; }
            p.type = types[r.nextInt(types.length)];
            people.add(p);
            n++;
        }
        for (int g = 0; g < 8; g++) {
            Person p = new Person(people.size(), FIRST[people.size() % FIRST.length], Job.GUARD, -1, 0);
            p.guardBed = g;
            p.night = g % 2 == 1;
            p.prof = Job.GUARD.prof;
            p.type = types[r.nextInt(types.length)];
            people.add(p);
        }
        for (int c = 0; c < 4; c++) {
            Person p = new Person(people.size(), FIRST[people.size() % FIRST.length], Job.CHILD, 1 + c * 3, 0);
            p.prof = Job.CHILD.prof;
            p.type = types[r.nextInt(types.length)];
            people.add(p);
        }
        Person dm = new Person(people.size(), "Captain " + FIRST[(people.size() + 5) % FIRST.length], Job.DOCKMASTER, -1, 0);
        dm.prof = Job.DOCKMASTER.prof;
        dm.type = Villager.Type.SWAMP;
        people.add(dm);
    }

    // =========================================================================================== spawning
    long time() { return pl.world == null ? 6000 : Math.floorMod(pl.world.getTime(), 24000); }

    void spawnAll() {
        if (pl.world == null) return;
        if (!pl.world.isChunkLoaded(Halcyon.HX >> 4, Halcyon.HZ >> 4)) return;
        planDone = -1;
        long now = System.currentTimeMillis();
        for (Person p : people) if (!p.alive() && now >= p.deadUntil) spawn(p);
    }

    void spawn(Person p) {
        Act a = schedule(p);
        Spot s = target(p, a);
        if (s == null) s = spots.get("plaza0");
        double[] e = s.end();
        Location l = loc(e, (float) s.face());
        if (!l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) return;
        Entity ent = l.getWorld().spawnEntity(l, EntityType.VILLAGER, false);
        if (!(ent instanceof Villager v)) { ent.remove(); return; }
        v.addScoreboardTag(TAG);
        v.addScoreboardTag(TAG + "_" + p.id);
        v.setPersistent(false);
        v.setRemoveWhenFarAway(false);
        v.setAI(false);
        v.setCanPickupItems(false);
        try { v.setProfession(p.prof); } catch (RuntimeException ignored) { }
        try { v.setVillagerType(p.type); } catch (RuntimeException ignored) { }
        try { v.setVillagerLevel(5); } catch (RuntimeException ignored) { }
        if (p.job == Job.CHILD) { v.setBaby(); v.setAgeLock(true); }
        v.setCustomName(nameOf(p));
        v.setCustomNameVisible(false);
        if (p.job == Job.GUARD) {
            DeepMobs.attr(v, Attribute.MAX_HEALTH, cfg("guard-health", 40));
            v.setHealth(cfg("guard-health", 40));
        }
        v.setRecipes(Trades.of(p, pl));
        p.v = v;
        p.at = s;
        p.goal = s;
        p.act = a;
        p.doing = a;
        p.pos = e.clone();
        p.yaw = (float) s.face();
        p.route.clear();
        byEntity.put(v.getUniqueId(), p);
        arrived(p);
    }

    String nameOf(Person p) {
        ChatColor c = p.job == Job.GUARD ? ChatColor.BLUE : p.job == Job.DOCKMASTER ? ChatColor.AQUA : p.job == Job.CHILD ? ChatColor.YELLOW : ChatColor.GREEN;
        return c + p.name + ChatColor.GRAY + " (" + p.job.title + ")";
    }

    /** Spot coordinates are block coordinates (relative): the citizen stands in the middle of that block. */
    Location loc(double[] rel, float yaw) { return new Location(pl.world, Halcyon.HX + rel[0] + 0.5, rel[1], Halcyon.HZ + rel[2] + 0.5, yaw, 0); }

    void removeAll() {
        for (Person p : people) {
            if (p.v != null) { byEntity.remove(p.v.getUniqueId()); if (p.v.isValid()) p.v.remove(); }
            p.v = null;
            if (p.bubble != null && p.bubble.isValid()) p.bubble.remove();
            p.bubble = null;
            p.route.clear();
            p.chasing = null;
        }
    }

    String summary() {
        int alive = 0, dead = 0, sleeping = 0;
        for (Person p : people) { if (p.alive()) { alive++; if (p.v.isSleeping()) sleeping++; } else if (p.deadUntil > System.currentTimeMillis()) dead++; }
        return people.size() + " citizens: " + alive + " out (" + sleeping + " asleep), " + dead + " dead. Building site: " + Math.max(0, planDone) + "/" + Halcyon.PLAN.size() + " blocks.";
    }

    // =========================================================================================== the day
    Act schedule(Person p) {
        long t = time();
        if (p.job == Job.DOCKMASTER) return Act.STAY;
        if (p.job == Job.GUARD) {
            if (p.onDuty(t)) return Act.PATROL;
            return Act.SLEEP;
        }
        if (t >= 13000 && t < 23500) return Act.SLEEP;
        if (t >= 23500 || t < 1000) return Act.BREAKFAST;
        if (p.job == Job.CHILD) return t >= 11500 ? Act.EVENING : Act.PLAY;
        if (t >= 6000 && t < 7200 && p.id % 2 == 0) return Act.LUNCH;
        if (t >= 11500) return p.job == Job.MERCHANT || p.job == Job.CURATOR ? Act.WORK : Act.EVENING;
        if (p.job == Job.IDLER) return Act.EVENING;
        return Act.WORK;
    }

    Spot target(Person p, Act a) {
        return switch (a) {
            case SLEEP -> p.job == Job.GUARD ? spots.get("barracks_bed" + p.guardBed) : p.job == Job.CHILD ? spots.get("home" + p.house) : spots.get("home" + p.house + "_bed" + p.bed);
            case BREAKFAST -> p.house < 0 ? spots.get("tavern_seat" + (p.id % 6)) : spots.get("home" + p.house + "_eat");
            case LUNCH -> spots.get("tavern_seat" + (p.id % 6));
            case EVENING, PLAY -> spots.get("plaza" + (random.nextInt(12) * 30));
            case STAY -> spots.get("dockmaster");
            case PATROL -> patrolSpot(p);
            case WORK -> workSpot(p);
            default -> p.at;
        };
    }

    Spot patrolSpot(Person p) {
        String[] posts = {"post_lab", "post_aq", "dock_gate", "west_gate", "ring0", "ring45", "ring90", "ring135", "ring180", "ring225", "ring270", "ring315",
                "farm_44_-50", "tree0", "site_n45", "mine0"};
        Spot s = spots.get(posts[random.nextInt(posts.length)]);
        return s != null ? s : spots.get("ring0");
    }

    Spot workSpot(Person p) {
        List<String> keys = new ArrayList<>();
        switch (p.job) {
            case FARMER -> spots.keySet().stream().filter(k -> k.startsWith("farm_")).forEach(keys::add);
            case WOODCUTTER -> { spots.keySet().stream().filter(k -> k.startsWith("tree")).forEach(keys::add); if (random.nextInt(4) == 0) return spots.get("logpile"); }
            case MINER -> spots.keySet().stream().filter(k -> k.startsWith("mine")).forEach(keys::add);
            case BUILDER -> { return builderSpot(p); }
            case SCIENTIST -> spots.keySet().stream().filter(k -> k.startsWith("lab_")).forEach(keys::add);
            case AQUARIST -> spots.keySet().stream().filter(k -> k.startsWith("aq_") && !k.equals("aq_curator")).forEach(keys::add);
            case MERCHANT -> { return spots.get("stall" + p.stall); }
            case COOK -> { return spots.get("tavern_cook"); }
            case CURATOR -> { return spots.get("aq_curator"); }
            default -> spots.keySet().stream().filter(k -> k.startsWith("plaza")).forEach(keys::add);
        }
        if (keys.isEmpty()) return spots.get("plaza0");
        java.util.Collections.sort(keys);
        return spots.get(keys.get(random.nextInt(keys.size())));
    }

    // =========================================================================================== every tick
    void tick() {
        ticks++;
        if (pl.world == null) return;
        long t = time();
        if (ticks % 200 == 0) spawnAll(); // the dead come back, chunks that loaded late
        for (Person p : people) {
            if (!p.alive()) {
                if (p.v != null) { byEntity.remove(p.v.getUniqueId()); p.v = null; }
                continue;
            }
            try { step(p, t); } catch (RuntimeException e) {
                if (ticks % 200 == 0) pl.getLogger().warning("[Halcyon] citizen " + p.name + ": " + e);
            }
            if (p.bubble != null) {
                if (--p.bubbleT <= 0 || !p.bubble.isValid()) { if (p.bubble.isValid()) p.bubble.remove(); p.bubble = null; }
                else p.bubble.teleport(p.v.getLocation().add(0, p.job == Job.CHILD ? 1.4 : 2.45, 0));
            }
        }
        if (ticks % 20 == 0) siteTick();
    }

    void step(Person p, long t) {
        p.t++;
        Villager v = p.v;
        if (v.isTrading()) { // someone's looking at their wares: stand still and face them
            if (v.getTrader() != null) face(p, v.getTrader().getLocation());
            return;
        }
        // guards: hunt anyone wanted in the city
        if (p.job == Job.GUARD && (p.act == Act.CHASE || p.t % 10 == 0)) {
            Player prey = prey(p);
            if (prey != null) { if (v.isSleeping()) v.wakeup(); chase(p, prey); return; }
            if (p.act == Act.CHASE) { p.act = null; p.chasing = null; backToStreets(p); }
        }
        if (p.act == Act.PANIC && System.currentTimeMillis() > p.panicUntil) { p.act = null; p.speed = 0.11; }
        if (!p.route.isEmpty()) { walk(p); return; }
        if (p.act == Act.PANIC) { cower(p); return; }
        // what should they be doing?
        if (p.t % 20 == 0) {
            Act want = schedule(p);
            boolean moveOn = want != p.act || (want == Act.WORK || want == Act.PATROL || want == Act.EVENING || want == Act.PLAY) && p.t >= p.until;
            if (moveOn) { go(p, want, target(p, want)); return; }
        }
        doing(p);
    }

    void go(Person p, Act a, Spot s) {
        if (p.v.isSleeping()) p.v.wakeup();
        try { p.v.setPose(Pose.STANDING, false); } catch (Throwable ignored) { }
        p.act = a;
        p.goal = s;
        hold(p, null);
        if (s == null || s == p.at) { arrived(p); return; }
        p.route.clear();
        p.route.addAll(route(p.at, s));
        p.speed = a == Act.PLAY ? 0.2 : p.job == Job.GUARD ? 0.13 : 0.11;
        if (p.route.isEmpty()) arrived(p);
    }

    /** One step along the route. */
    void walk(Person p) {
        double[] next = p.route.peekFirst();
        double dx = next[0] - p.pos[0], dy = next[1] - p.pos[1], dz = next[2] - p.pos[2];
        double d = Math.sqrt(dx * dx + dz * dz + dy * dy);
        double sp = p.speed;
        if (d <= sp) { p.pos = next.clone(); p.route.pollFirst(); if (p.route.isEmpty()) { p.at = p.goal; arrived(p); } }
        else { p.pos[0] += dx / d * sp; p.pos[1] += dy / d * sp; p.pos[2] += dz / d * sp; }
        if (Math.hypot(dx, dz) > 0.05) p.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double bob = p.act == Act.PLAY ? Math.abs(Math.sin(p.t * 0.5)) * 0.35 : 0;
        p.v.teleport(loc(new double[]{p.pos[0], p.pos[1] + bob, p.pos[2]}, p.yaw));
    }

    void arrived(Person p) {
        p.t = 0;
        p.doing = p.act;
        p.until = 20 * (p.act == Act.WORK ? 15 + random.nextInt(25) : p.act == Act.PATROL ? 4 + random.nextInt(8) : p.act == Act.PLAY ? 2 + random.nextInt(4) : 30 + random.nextInt(30));
        Spot s = p.at;
        if (s != null) { p.yaw = (float) s.face(); p.v.teleport(loc(p.pos, p.yaw)); }
        switch (p.act == null ? Act.STAY : p.act) {
            case SLEEP -> sleep(p);
            case BREAKFAST, LUNCH -> hold(p, new ItemStack(random.nextBoolean() ? Material.BREAD : Material.COOKED_COD));
            case PATROL -> hold(p, new ItemStack(Material.IRON_SWORD));
            case WORK -> hold(p, tool(p));
            case STAY -> hold(p, new ItemStack(Material.FILLED_MAP));
            default -> hold(p, null);
        }
    }

    ItemStack tool(Person p) {
        Material m = switch (p.job) {
            case FARMER -> Material.IRON_HOE;
            case WOODCUTTER -> p.at != null && p.at.key().equals("logpile") ? Material.OAK_LOG : Material.IRON_AXE;
            case MINER -> Material.IRON_PICKAXE;
            case BUILDER -> Material.BRICKS;
            case SCIENTIST -> Material.GLASS_BOTTLE;
            case AQUARIST -> Material.TROPICAL_FISH_BUCKET;
            case MERCHANT -> Material.EMERALD;
            case COOK -> Material.BREAD;
            case CURATOR -> Material.WRITABLE_BOOK;
            default -> Material.AIR;
        };
        return m == Material.AIR ? null : new ItemStack(m);
    }

    void hold(Person p, ItemStack it) {
        if (p.v.getEquipment() == null) return;
        p.v.getEquipment().setItemInMainHand(it);
        try { p.v.getEquipment().setDropChance(EquipmentSlot.HAND, 0); } catch (RuntimeException ignored) { }
    }

    void sleep(Person p) {
        if (p.job == Job.CHILD || p.job == Job.DOCKMASTER) { try { p.v.setPose(Pose.SLEEPING, true); } catch (Throwable ignored) { } return; }
        int[] head;
        if (p.job == Job.GUARD) { int[] b = Halcyon.barrackBeds()[p.guardBed]; head = new int[]{Halcyon.HX + b[0], Halcyon.HZ + b[1]}; }
        else head = Halcyon.HOUSES.get(p.house).bed(p.bed);
        Location bed = new Location(pl.world, head[0], Halcyon.G, head[1]);
        boolean ok = false;
        try { ok = bed.getBlock().getType().name().endsWith("_BED") && p.v.sleep(bed); } catch (Throwable ignored) { }
        if (!ok) try { p.v.setPose(Pose.SLEEPING, true); } catch (Throwable ignored) { }
    }

    // =========================================================================================== what they do where they are
    void doing(Person p) {
        Villager v = p.v;
        Location l = v.getLocation();
        World w = l.getWorld();
        Act a = p.act == null ? Act.STAY : p.act;
        switch (a) {
            case SLEEP -> { if (p.t % 60 == 0 && near(l, 16)) w.spawnParticle(Particle.NOTE, l.clone().add(0, 1.2, 0), 1, 0.2, 0.1, 0.2, 0); }
            case BREAKFAST, LUNCH -> {
                if (p.t < 200 && p.t % 8 == 0) {
                    ItemStack food = v.getEquipment() == null ? null : v.getEquipment().getItemInMainHand();
                    if (food != null && !food.getType().isAir()) w.spawnParticle(Particle.ITEM, l.clone().add(0, 1.5, 0).add(l.getDirection().multiply(0.3)), 4, 0.1, 0.1, 0.1, 0.03, food);
                    w.playSound(l, Sound.ENTITY_GENERIC_EAT, SoundCategory.NEUTRAL, 0.6f, 0.9f + random.nextFloat() * 0.3f);
                    nod(p, 25);
                }
                if (p.t == 200) hold(p, null);
                if (p.t % 400 == 100 && random.nextInt(2) == 0) say(p, Lines.pick(Lines.MEAL, random));
            }
            case WORK -> work(p);
            case PATROL -> {
                if (p.t % 40 == 0) lookAround(p);
                if (p.t % 300 == 150 && random.nextInt(3) == 0 && near(l, 12)) say(p, Lines.pick(Lines.GUARD, random));
            }
            case EVENING, PLAY -> {
                if (p.t % 30 == 0) faceNearest(p);
                if (p.t % 240 == 60 && random.nextInt(3) == 0 && near(l, 14)) say(p, Lines.pick(p.job == Job.CHILD ? Lines.CHILD : Lines.EVENING, random));
            }
            case STAY -> {
                if (p.t % 20 == 0) faceNearest(p);
                if (p.t % 400 == 200 && near(l, 8)) say(p, Lines.pick(Lines.DOCK, random));
            }
            default -> { }
        }
    }

    void work(Person p) {
        Location l = p.v.getLocation();
        World w = l.getWorld();
        boolean seen = near(l, 32);
        switch (p.job) {
            case FARMER -> {
                if (p.t % 6 == 0) nod(p, 35);
                if (p.t % 40 == 20) {
                    double[] e = p.pos;
                    for (int dz : new int[]{1, -1}) {
                        Block b = w.getBlockAt((int) Math.floor(Halcyon.HX + e[0] + (random.nextInt(3) - 1)), Halcyon.G, (int) Math.floor(Halcyon.HZ + e[2]) + dz);
                        if (!(b.getBlockData() instanceof Ageable ag) || !b.getType().name().matches("WHEAT|CARROTS|POTATOES|BEETROOTS")) continue;
                        if (ag.getAge() >= ag.getMaximumAge()) {
                            ag.setAge(0); b.setBlockData(ag, false);
                            w.playSound(b.getLocation(), Sound.BLOCK_CROP_BREAK, SoundCategory.BLOCKS, 0.7f, 1f);
                            w.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.3, 0.5), 10, 0.2, 0.2, 0.2, 0, b.getBlockData());
                        } else if (random.nextInt(3) == 0) {
                            ag.setAge(Math.min(ag.getMaximumAge(), ag.getAge() + 1)); b.setBlockData(ag, false);
                            w.spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 0.4, 0.5), 5, 0.3, 0.2, 0.3, 0);
                        }
                        break;
                    }
                }
            }
            case WOODCUTTER -> {
                if (p.at != null && p.at.key().equals("logpile")) {
                    if (p.t == 20) { w.playSound(l, Sound.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 0.8f, 0.8f); hold(p, null); }
                    break;
                }
                if (p.t % 8 == 0) nod(p, 30);
                if (p.t % 16 == 0 && seen) {
                    Location trunk = l.clone().add(l.getDirection().setY(0).normalize().multiply(1.1)).add(0, 1.0, 0);
                    w.playSound(trunk, Sound.BLOCK_WOOD_HIT, SoundCategory.BLOCKS, 1f, 0.8f + random.nextFloat() * 0.2f);
                    w.spawnParticle(Particle.BLOCK, trunk, 8, 0.15, 0.2, 0.15, 0, Material.OAK_LOG.createBlockData());
                }
                if (p.t % 200 == 100 && random.nextInt(3) == 0 && near(l, 12)) say(p, Lines.pick(Lines.WOOD, random));
            }
            case MINER -> {
                if (p.t % 5 == 0) nod(p, 40);
                if (p.at == null || !p.at.key().startsWith("mine")) break;
                int[] f = Halcyon.coalFaces()[Integer.parseInt(p.at.key().substring(4))];
                Block ore = w.getBlockAt(Halcyon.HX + f[0], f[1], Halcyon.HZ + f[2]);
                float prog = (p.t % 80) / 80f;
                if (p.t % 5 == 0) for (Player pl2 : w.getPlayers()) if (pl2.getLocation().distanceSquared(l) < 32 * 32) pl2.sendBlockDamage(ore.getLocation(), prog, p.v);
                if (p.t % 10 == 0 && seen) w.playSound(ore.getLocation(), Sound.BLOCK_STONE_HIT, SoundCategory.BLOCKS, 0.8f, 0.9f);
                if (p.t % 80 == 79) {
                    w.playSound(ore.getLocation(), Sound.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 1f, 0.9f);
                    w.spawnParticle(Particle.BLOCK, ore.getLocation().add(0.5, 0.5, 0.5), 20, 0.3, 0.3, 0.3, 0, Material.COAL_ORE.createBlockData());
                    w.spawnParticle(Particle.ITEM, ore.getLocation().add(0.5, 0.5, 0.5), 6, 0.2, 0.2, 0.2, 0.08, new ItemStack(Material.COAL));
                    for (Player pl2 : w.getPlayers()) if (pl2.getLocation().distanceSquared(l) < 32 * 32) pl2.sendBlockDamage(ore.getLocation(), 0f, p.v);
                }
                if (p.t % 300 == 150 && random.nextInt(3) == 0 && near(l, 12)) say(p, Lines.pick(Lines.MINE, random));
            }
            case BUILDER -> {
                if (p.t % 6 == 0) nod(p, 30);
                if (p.t % 50 == 25) build(p);
            }
            case SCIENTIST -> {
                if (p.t % 10 == 0) nod(p, 15);
                if (p.t % 30 == 0 && seen) {
                    Location front = l.clone().add(l.getDirection().setY(0).normalize().multiply(2.5)).add(0, 1.4, 0);
                    w.spawnParticle(Particle.ENCHANT, front, 12, 0.6, 0.6, 0.6, 0.6);
                    if (random.nextInt(3) == 0) w.playSound(l, random.nextBoolean() ? Sound.BLOCK_BREWING_STAND_BREW : Sound.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundCategory.BLOCKS, 0.6f, 1.2f);
                }
                if (p.t % 300 == 120 && random.nextInt(3) == 0 && near(l, 12)) say(p, Lines.pick(Lines.LAB, random));
            }
            case AQUARIST -> {
                if (p.t % 12 == 0) nod(p, 20);
                if (p.t % 60 == 30 && seen) {
                    Location front = l.clone().add(l.getDirection().setY(0).normalize().multiply(3)).add(0, 2, 0);
                    w.spawnParticle(Particle.BUBBLE_POP, front, 12, 0.6, 0.4, 0.6, 0.02);
                    w.playSound(front, Sound.ITEM_BUCKET_EMPTY_FISH, SoundCategory.NEUTRAL, 0.7f, 1.1f);
                }
                if (p.t % 300 == 200 && random.nextInt(3) == 0 && near(l, 12)) say(p, Lines.pick(Lines.AQUA, random));
            }
            case MERCHANT, CURATOR -> {
                if (p.t % 20 == 0) faceNearest(p);
                if (p.t % 300 == 50 && random.nextInt(2) == 0 && near(l, 10)) say(p, Lines.pick(p.job == Job.CURATOR ? Lines.CURATOR : Lines.MARKET, random));
            }
            case COOK -> {
                if (p.t % 10 == 0) nod(p, 25);
                if (p.t % 20 == 0 && seen) {
                    w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, l.clone().add(-1, 1.2, 0), 1, 0.1, 0.1, 0.1, 0.01);
                    if (random.nextInt(4) == 0) w.playSound(l, Sound.BLOCK_FURNACE_FIRE_CRACKLE, SoundCategory.BLOCKS, 0.7f, 1f);
                }
                if (p.t % 300 == 10 && random.nextInt(2) == 0 && near(l, 12)) say(p, Lines.pick(Lines.COOK, random));
            }
            default -> { }
        }
    }

    void nod(Person p, float deg) {
        float pitch = (p.t / 4) % 2 == 0 ? deg : -5;
        p.v.setRotation(p.yaw, pitch);
    }

    void face(Person p, Location at) {
        Location l = p.v.getLocation();
        float yaw = (float) Math.toDegrees(Math.atan2(-(at.getX() - l.getX()), at.getZ() - l.getZ()));
        p.yaw = yaw;
        p.v.setRotation(yaw, 0);
    }

    void faceNearest(Person p) {
        Player best = null;
        double bd = 8 * 8;
        for (Player q : p.v.getWorld().getPlayers()) { double d = q.getLocation().distanceSquared(p.v.getLocation()); if (d < bd) { bd = d; best = q; } }
        if (best != null) face(p, best.getLocation());
    }

    void lookAround(Person p) { p.v.setRotation(p.yaw + (random.nextFloat() - 0.5f) * 120, 0); }

    boolean near(Location l, double r) {
        for (Player q : l.getWorld().getPlayers()) if (q.getLocation().distanceSquared(l) < r * r) return true;
        return false;
    }

    /** A speech bubble over their head. */
    void say(Person p, String text) {
        if (p.bubble != null && p.bubble.isValid()) p.bubble.remove();
        Location at = p.v.getLocation().add(0, p.job == Job.CHILD ? 1.4 : 2.45, 0);
        p.bubble = at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.text(Component.text(text, NamedTextColor.WHITE));
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
            d.addScoreboardTag(BUBBLE_TAG);
            d.setSeeThrough(false);
            d.setTeleportDuration(1);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(150, 0, 0, 0));
        });
        p.bubbleT = 70;
    }

    // =========================================================================================== the building site
    /** Index of the first plan block not built yet (scanned once, then counted). */
    int planIndex() {
        if (planDone >= 0) return planDone;
        for (int i = 0; i < Halcyon.PLAN.size(); i++) {
            Object[] b = Halcyon.PLAN.get(i);
            if (pl.world.getBlockAt(Halcyon.HX + (int) b[0], (int) b[1], Halcyon.HZ + (int) b[2]).getType().isAir()) return planDone = i;
        }
        return planDone = Halcyon.PLAN.size();
    }

    Spot builderSpot(Person p) {
        int i = planIndex();
        double bx = 45, bz = -60;
        if (i < Halcyon.PLAN.size()) { Object[] b = Halcyon.PLAN.get(i); bx = (int) b[0]; bz = (int) b[2]; }
        Spot best = null;
        double bd = 1e9;
        for (Spot s : spots.values()) {
            if (!s.key().startsWith("site_")) continue;
            double[] e = s.end();
            double d = Math.hypot(e[0] - bx, e[2] - bz) + random.nextDouble() * 3;
            if (d < bd) { bd = d; best = s; }
        }
        return best;
    }

    void build(Person p) {
        int i = planIndex();
        if (i >= Halcyon.PLAN.size()) return;
        Object[] b = Halcyon.PLAN.get(i);
        Location at = new Location(pl.world, Halcyon.HX + (int) b[0], (int) b[1], Halcyon.HZ + (int) b[2]);
        if (at.distanceSquared(p.v.getLocation()) > 9 * 9) { p.until = 0; return; } // too far: the schedule moves them on
        for (Entity e : pl.world.getNearbyEntities(at.clone().add(0.5, 0.5, 0.5), 0.5, 0.5, 0.5)) if (e instanceof LivingEntity) return; // someone in the way
        Block blk = at.getBlock();
        if (!blk.getType().isAir()) { planDone = -1; return; }
        BlockData data = Paint.bd((String) b[3]);
        blk.setBlockData(data, false);
        planDone = i + 1;
        pl.world.playSound(at, data.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 1f, 0.9f);
        pl.world.spawnParticle(Particle.BLOCK, at.clone().add(0.5, 0.5, 0.5), 8, 0.3, 0.3, 0.3, 0, data);
        face(p, at);
        if (planDone >= Halcyon.PLAN.size()) {
            planCompleteAt = System.currentTimeMillis();
            say(p, "Done! Inspection's due.");
        }
    }

    /** A finished house stands for `site-inspection-minutes`, then comes down and the builders start over. */
    void siteTick() {
        if (planDone < Halcyon.PLAN.size() || planCompleteAt == 0) return;
        if (System.currentTimeMillis() - planCompleteAt < cfg("site-inspection-minutes", 4) * 60000) return;
        for (int i = Halcyon.PLAN.size() - 1; i >= 0; i--) {
            Object[] b = Halcyon.PLAN.get(i);
            Block blk = pl.world.getBlockAt(Halcyon.HX + (int) b[0], (int) b[1], Halcyon.HZ + (int) b[2]);
            if (!blk.getType().isAir()) {
                if (i % 6 == 0) pl.world.spawnParticle(Particle.BLOCK, blk.getLocation().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3, 0, blk.getBlockData());
                blk.setType(Material.AIR, false);
            }
        }
        pl.world.playSound(new Location(pl.world, Halcyon.HX + 45, Halcyon.G, Halcyon.HZ - 60), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.BLOCKS, 0.6f, 1.6f);
        planDone = 0;
        planCompleteAt = 0;
        for (Person p : people) if (p.job == Job.BUILDER && p.alive()) say(p, "Failed inspection. Again!");
    }

    // =========================================================================================== guards
    Player prey(Person p) {
        if (!p.onDuty(time()) && p.act != Act.CHASE) {
            // off duty: only a serious crime close by gets them out of bed
            for (Player q : p.v.getWorld().getPlayers()) if (city.wanted(q) >= 2 && huntable(q) && q.getLocation().distanceSquared(p.v.getLocation()) < 24 * 24) return q;
            return null;
        }
        Player best = null;
        double bd = cfg("guard-range", 64);
        bd *= bd;
        for (Player q : p.v.getWorld().getPlayers()) {
            if (city.wanted(q) <= 0 || !huntable(q)) continue;
            double d = q.getLocation().distanceSquared(p.v.getLocation());
            if (d < bd) { bd = d; best = q; }
        }
        return best;
    }

    boolean huntable(Player q) {
        if (q.isDead() || q.getGameMode() == GameMode.CREATIVE || q.getGameMode() == GameMode.SPECTATOR) return false;
        Location l = q.getLocation();
        return Halcyon.inside(l.getX(), l.getY(), l.getZ()) || Halcyon.grounds(l.getX(), l.getY(), l.getZ()) && l.getBlock().getType() != Material.WATER;
    }

    void chase(Person p, Player prey) {
        if (p.act != Act.CHASE) { say(p, Lines.pick(Lines.CHASE, random)); hold(p, new ItemStack(Material.IRON_SWORD)); }
        p.act = Act.CHASE;
        p.chasing = prey.getUniqueId();
        p.route.clear();
        Location me = p.v.getLocation(), to = prey.getLocation();
        double dist = me.distance(to);
        face(p, to);
        if (p.attackCd > 0) p.attackCd--;
        if (p.shotCd > 0) p.shotCd--;
        if (dist <= cfg("guard-reach", 2.4)) {
            if (p.attackCd <= 0) {
                p.attackCd = 18;
                prey.damage(cfg("guard-damage", 6), p.v);
                Vector push = to.toVector().subtract(me.toVector()).setY(0);
                if (push.lengthSquared() > 1e-4) Edition.vel(prey, push.normalize().multiply(0.5).setY(0.35));
                me.getWorld().playSound(me, Sound.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.HOSTILE, 1f, 1f);
            }
            return;
        }
        if (dist > 6 && dist < 20 && p.shotCd <= 0 && p.v.hasLineOfSight(prey)) {
            p.shotCd = 50;
            Location eye = p.v.getEyeLocation();
            Vector dir = prey.getEyeLocation().toVector().subtract(eye.toVector());
            if (dir.lengthSquared() > 1e-4) {
                Arrow a = p.v.launchProjectile(Arrow.class, dir.normalize().multiply(1.9));
                a.setDamage(cfg("guard-arrow-damage", 2));
                a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                a.addScoreboardTag(TAG);
                me.getWorld().playSound(me, Sound.ITEM_CROSSBOW_SHOOT, SoundCategory.HOSTILE, 1f, 1f);
            }
        }
        // run straight at them, sidestepping walls
        Vector dir = to.toVector().subtract(me.toVector()).setY(0);
        if (dir.lengthSquared() < 1e-4) return;
        dir.normalize();
        double sp = cfg("guard-run-speed", 0.27);
        for (int turn : new int[]{0, 35, -35, 70, -70, 110, -110}) {
            Vector d = dir.clone().rotateAroundY(Math.toRadians(turn)).multiply(sp);
            Location n = me.clone().add(d);
            double y = floorAt(n);
            if (Double.isNaN(y)) continue;
            n.setY(y);
            n.setYaw(p.yaw); n.setPitch(0);
            p.v.teleport(n);
            p.pos = new double[]{n.getX() - Halcyon.HX - 0.5, n.getY(), n.getZ() - Halcyon.HZ - 0.5};
            return;
        }
    }

    /** Where they'd stand at this column (a step up or down at most), or NaN if it's a wall. */
    double floorAt(Location n) {
        World w = n.getWorld();
        int x = n.getBlockX(), z = n.getBlockZ(), y = (int) Math.floor(n.getY() + 0.01);
        for (int dy : new int[]{0, 1, -1}) {
            int fy = y + dy;
            Block feet = w.getBlockAt(x, fy, z), head = w.getBlockAt(x, fy + 1, z), under = w.getBlockAt(x, fy - 1, z);
            if (open(feet) && open(head) && !open(under)) return fy;
        }
        return Double.NaN;
    }

    /** Can a body stand in this block? (not Block#isPassable: some servers' tests don't have it) */
    static boolean open(Block b) {
        Material m = b.getType();
        if (!m.isSolid()) return true;
        return b.getBlockData() instanceof org.bukkit.block.data.Openable o && o.isOpen();
    }

    /** After a chase: walk back to the nearest ring point and pick up where the day says. */
    void backToStreets(Person p) {
        double a = angleOf(p.pos[0], p.pos[2]);
        Spot ring = new Spot("back", a, List.of(ring(a)), a + 90);
        p.route.clear();
        p.route.add(ring(a));
        p.goal = ring;
        p.at = ring;
        p.speed = 0.13;
        p.act = Act.PATROL;
    }

    /** Someone did something: the guards on duty go for them (HalcyonCity.crime). */
    void alarm(Player p) {
        for (Person g : people) if (g.job == Job.GUARD && g.alive() && g.onDuty(time()) && g.act != Act.CHASE && huntable(p)) g.t = 9;
    }

    /** Did a guard (24 blocks) or anyone (12) see them? */
    boolean witnessed(Player p) {
        for (Person q : people) {
            if (!q.alive() || q.v.isSleeping()) continue;
            double r = q.job == Job.GUARD ? 24 : 12;
            if (q.v.getLocation().distanceSquared(p.getLocation()) < r * r && q.v.hasLineOfSight(p)) {
                if (q.job != Job.GUARD) { say(q, "Hey! Guards! GUARDS!"); scream(q); }
                return true;
            }
        }
        return false;
    }

    void scream(Person p) {
        Location l = p.v.getLocation();
        l.getWorld().playSound(l, Sound.ENTITY_VILLAGER_HURT, SoundCategory.NEUTRAL, 1.6f, 1.5f);
        l.getWorld().playSound(l, Sound.ENTITY_VILLAGER_NO, SoundCategory.NEUTRAL, 1.6f, 1.3f);
    }

    /** Run home (or to the nearest door) and stay there till it's over. */
    void panic(Person p, Location from) {
        if (p.job == Job.GUARD || p.job == Job.DOCKMASTER || !p.alive()) return;
        if (p.v.isSleeping()) return;
        if (p.act != Act.PANIC) say(p, Lines.pick(Lines.PANIC, random));
        p.act = Act.PANIC;
        p.panicUntil = System.currentTimeMillis() + (long) (cfg("panic-seconds", 15) * 1000);
        Spot home = p.house >= 0 ? spots.get("home" + p.house) : spots.get("tavern");
        if (p.route.isEmpty() && p.at != home) { p.route.addAll(route(p.at, home)); p.goal = home; }
        p.speed = 0.24;
        hold(p, null);
    }

    void cower(Person p) {
        if (p.t % 20 == 0) { p.v.setRotation(p.yaw + (random.nextFloat() - 0.5f) * 180, 20); if (random.nextInt(3) == 0) p.v.getWorld().playSound(p.v.getLocation(), Sound.ENTITY_VILLAGER_HURT, SoundCategory.NEUTRAL, 0.6f, 1.6f); }
    }

    // =========================================================================================== events: hurting, trading, stealing
    Person of(Entity e) { return e == null ? null : byEntity.get(e.getUniqueId()); }

    static Player playerBehind(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (damager instanceof Tameable t && t.getOwner() instanceof Player p) return p;
        if (damager instanceof org.bukkit.entity.TNTPrimed tnt && tnt.getSource() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHurt(EntityDamageEvent e) {
        Person p = of(e.getEntity());
        if (p == null) return;
        Player by = e instanceof EntityDamageByEntityEvent ed ? playerBehind(ed.getDamager()) : null;
        if (by == null) { e.setCancelled(true); return; } // drowning, falling, mobs, fire, their own guards' arrows: nothing hurts them
        if (by.getGameMode() == GameMode.CREATIVE && HalcyonCity.exempt(by)) return; // an admin testing
        Location l = p.v.getLocation();
        scream(p);
        l.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, l.clone().add(0, 2, 0), 3, 0.3, 0.2, 0.3, 0);
        if (p.job == Job.GUARD) {
            city.crime(by, 2, "assaulting a guard");
        } else {
            say(p, p.job == Job.CHILD ? "WAAAH! MOOOM!" : "AAAAAH! HELP! GUARDS!");
            city.crime(by, 1, "assaulting a citizen");
            for (Person q : people) if (q != p && q.alive() && q.v.getLocation().distanceSquared(l) < 20 * 20) { if (q.job == Job.GUARD) q.t = 9; else panic(q, l); }
            panic(p, l);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Person p = of(e.getEntity());
        if (p == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        byEntity.remove(e.getEntity().getUniqueId());
        if (p.bubble != null && p.bubble.isValid()) p.bubble.remove();
        p.v = null;
        p.route.clear();
        p.deadUntil = System.currentTimeMillis() + (long) (cfg("respawn-minutes", 10) * 60000);
        Player by = e.getEntity().getKiller();
        if (by != null) {
            city.crime(by, 2, p.job == Job.GUARD ? "killing a guard" : p.job == Job.CHILD ? "killing a child" : "murdering a citizen");
            for (Player q : e.getEntity().getWorld().getPlayers()) if (q.getLocation().distanceSquared(e.getEntity().getLocation()) < 64 * 64)
                q.sendMessage(ChatColor.RED + "[Halcyon] " + ChatColor.GRAY + p.name + " the " + p.job.title.toLowerCase() + " was killed by " + by.getName() + ".");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEntityEvent e) {
        Person p = of(e.getRightClicked());
        if (p == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player who = e.getPlayer();
        if (p.v.isSleeping() || p.act == Act.SLEEP) { who.sendActionBar(Component.text(p.name + " is asleep. Zzz...", NamedTextColor.GRAY)); return; }
        if (city.wanted(who) > 0) { say(p, "I'm not trading with a criminal!"); p.v.shakeHead(); return; }
        if (p.act == Act.PANIC) { say(p, "Not now!"); return; }
        if (p.v.getRecipes().isEmpty()) {
            say(p, Lines.pick(p.job == Job.GUARD ? Lines.GUARD : p.job == Job.CHILD ? Lines.CHILD : Lines.NITWIT, random));
            if (p.job != Job.GUARD) p.v.shakeHead();
            return;
        }
        if (p.v.isTrading()) { who.sendActionBar(Component.text(p.name + " is busy with someone else.", NamedTextColor.GRAY)); return; }
        face(p, who.getLocation());
        who.openMerchant(p.v, true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent e) { if (of(e.getEntity()) != null) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onMount(EntityMountEvent e) { if (of(e.getEntity()) != null) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) { if (of(e.getEntity()) != null) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) { if (of(e.getTarget()) != null) e.setCancelled(true); }
}
