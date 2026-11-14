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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * HALCYON'S PEOPLE (Deep 1.17.0, reworked in 1.18.0): every citizen is a real villager with a name, a bed, a job and a day.
 * They exist while the city is awake (someone near).
 *  - LIKE ANY VILLAGER when they're off: in the evening, at play and when idle they're let loose on the plaza with the game's
 *    own villager brain (they wander, gossip, gather round the meeting bell, the kids chase each other), kept to the plaza.
 *    Everywhere else they walk the city's streets along planned routes (the ring road, the avenues, the paths, the glass
 *    hallways to the Agri and Guard domes, the mine's stair tunnel), moved a little every tick.
 *  - THE DAY (the Dark Ocean world's clock): up at dawn and breakfast at home, work, lunch at the Kelp & Kettle, back to work,
 *    an evening on the plaza, home at dusk, then bed (they really sleep in their beds: the houses, the Agri barn's bunks,
 *    the miners' bunkhouse, the barracks). Guards work in two shifts; the night shift sleeps by day.
 *  - THE JOBS (real ones): FARMERS harvest ripe crops and tend the young ones in the Agri dome's fields; WOODCUTTERS fell the
 *    orchard's trees for real (the whole tree comes down, a sapling goes in, it grows back after `tree-regrow-minutes`) and
 *    carry the logs to the pile; MINERS dig the ore faces in the gallery (cracking them, then the ore is gone until it grows
 *    back: `ore-regrow-minutes`); DIVERS suit up in the dive room (a dive helmet on), climb down the moon pool and swim the
 *    flooded tunnel out to the crust's caves to mine the ore there, then swim back and change; BUILDERS put up the house on
 *    the site block by block; SCIENTISTS, AQUARISTS (both aquarium floors), the CURATOR, MERCHANTS, the COOK, the TEACHER
 *    (the school, with the kids at their desks in the morning), the DOCTOR (the clinic), the MAYOR (the Town Hall),
 *    GARDENERS (the greenhouse and the park). GUARDS patrol every dome, train on the dummies and the archery range.
 *  - TRADING: right-click anyone with a trade (vanilla style, by profession; the Dock Master sells the Submersible).
 *  - HURTING THEM: they scream, everyone near runs home, and you're WANTED (HalcyonCity). WANTED 1: the guards catch you and
 *    ARREST you (thrown out on the dock, the charge dropped). WANTED 2+: they fight to kill (melee, arrows). A guard you hit
 *    turns on you at once. Players in creative are ignored (they're told so).
 */
final class Citizens implements Listener {
    static final String TAG = "faultline_citizen", BUBBLE_TAG = "faultline_citizen_bubble";

    enum Job {
        FARMER("Farmer", Villager.Profession.FARMER), WOODCUTTER("Woodcutter", Villager.Profession.FLETCHER), MINER("Miner", Villager.Profession.TOOLSMITH),
        DIVER("Diver", Villager.Profession.FISHERMAN), BUILDER("Builder", Villager.Profession.MASON), SCIENTIST("Scientist", Villager.Profession.CLERIC),
        AQUARIST("Aquarist", Villager.Profession.FISHERMAN), MERCHANT("Merchant", Villager.Profession.LIBRARIAN), COOK("Cook", Villager.Profession.BUTCHER),
        CURATOR("Curator", Villager.Profession.LIBRARIAN), TEACHER("Teacher", Villager.Profession.LIBRARIAN), DOCTOR("Doctor", Villager.Profession.CLERIC),
        MAYOR("Mayor", Villager.Profession.CARTOGRAPHER), GARDENER("Gardener", Villager.Profession.FARMER), IDLER("Layabout", Villager.Profession.NITWIT),
        GUARD("City Guard", Villager.Profession.WEAPONSMITH), CHILD("Child", Villager.Profession.NONE), DOCKMASTER("Dock Master", Villager.Profession.CARTOGRAPHER);
        final String title;
        final Villager.Profession prof;
        Job(String title, Villager.Profession prof) { this.title = title; this.prof = prof; }
    }

    enum Act { SLEEP, BREAKFAST, WORK, LUNCH, EVENING, HOME, PATROL, PLAY, SCHOOL, STAY, PANIC, CHASE, DIVE }

    /** Where they sleep. */
    enum Bed { HOUSE, BARN, BUNK, BARRACKS }

    /** A place a citizen can stand: the ring junction (degrees) + the points from the ring out to it (rel x, y, rel z). */
    record Spot(String key, double angle, List<double[]> pts, double face) {
        double[] end() { return pts.get(pts.size() - 1); }
    }

    final class Person {
        final int id;
        final String name;
        final Job job;
        final int house, bed;          // house index (-1 none), bed 0/1 (HOUSE) or the bunk's index
        final Bed sleeps;
        int stall = -1;
        boolean night;                 // a night-shift guard
        Villager.Profession prof;
        Villager.Type type;
        Villager v;
        Spot at, goal;
        final Deque<double[]> route = new ArrayDeque<>();
        double speed = 0.11;
        Act act, doing;
        int t, until;
        long deadUntil, panicUntil;
        UUID chasing;
        int attackCd, shotCd;
        double[] pos;                  // rel x, y, rel z right now
        float yaw;
        TextDisplay bubble;
        int bubbleT;
        long alertUntil;               // a guard called by the alarm: hunts anywhere in the city
        boolean offStreets;            // chasing someone off the street network
        boolean free;                  // let loose with the vanilla villager brain (on the plaza)
        int tree = -1, ore = -1, carry;
        // diving
        int diveStage;                 // 0 none, 1 suiting, 2 swimming out, 3 mining, 4 swimming back, 5 changing
        final Deque<double[]> swim = new ArrayDeque<>();
        Block diveOre;
        int dug;
        Person(int id, String name, Job job, int house, int bed, Bed sleeps) { this.id = id; this.name = name; this.job = job; this.house = house; this.bed = bed; this.sleeps = sleeps; }
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
    // the real work they leave behind: trees felled, ore dug (and when it comes back)
    final long[] treeFelled, oreDug;
    final Map<Block, Material> diveDug = new HashMap<>();
    final Map<Block, Long> diveDugAt = new HashMap<>();
    final Set<Integer> treeClaimed = new HashSet<>(), oreClaimed = new HashSet<>();
    List<double[]> divePath;       // the flooded tunnel (rel), null = not worked out / no generator
    List<Block> diveOres = new ArrayList<>();

    static final String[] FIRST = {"Ada", "Bram", "Cora", "Dmitri", "Edda", "Finn", "Greta", "Hal", "Ines", "Jory", "Kesh", "Lio", "Mara", "Nils", "Oona",
            "Pell", "Quill", "Rhea", "Soren", "Tilda", "Ulf", "Vera", "Wim", "Xan", "Yara", "Zeb", "Orrin", "Maeve", "Tobin", "Sable", "Corin", "Hedda",
            "Nell", "Arlo", "Brin", "Isla", "Kip", "Luca", "Mira", "Tamsin", "Ezra", "Juno", "Rook", "Odile", "Pax", "Wren", "Bastian", "Iver", "Lark",
            "Mabel", "Fenn", "Thea", "Gideon", "Signe", "Hollis"};

    Citizens(FaultlineDeep pl, HalcyonCity city) {
        this.pl = pl;
        this.city = city;
        treeFelled = new long[Halcyon.TREES.size()];
        oreDug = new long[Halcyon.ORES.size()];
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

    void spot(String key, double angle, double face, List<double[]> base, double[]... more) {
        List<double[]> l = new ArrayList<>(base);
        l.addAll(List.of(more));
        spot(key, angle, face, l.toArray(new double[0][]));
    }

    static double[] at(int[] rel) { return pt(rel[0], rel[1]); }

    static final double FACE_N = 180, FACE_S = 0, FACE_E = -90, FACE_W = 90;

    void buildSpots() {
        // ---- homes: in, eat, bed sides
        for (Halcyon.House h : Halcyon.HOUSES) {
            double a = Halcyon.HOUSE_ANGLES[h.i()];
            double[] porch = rel(h.porch()), door = rel(h.door()), in = rel(h.world(4, 2)), eat = rel(h.world(4, 3));
            double faceIn = yawTo(door, in);
            spot("home" + h.i(), a, faceIn, porch, door, in);
            spot("home" + h.i() + "_eat", a, yawTo(eat, rel(h.table())), porch, door, in, eat);
            spot("home" + h.i() + "_bed0", a, faceIn, porch, door, in, rel(h.world(2, 4)));
            spot("home" + h.i() + "_bed1", a, faceIn, porch, door, in, rel(h.world(Halcyon.HW - 3, 4)));
        }
        // ---- the Bio-Center
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
        // ---- the Aquarium: aisles between the rows of tanks, both floors (the basement down the west stairs)
        List<double[]> aq = List.of(pt(44, 0), pt(51, 0), pt(55, 0));
        int[] aisles = {-16, -8, 0, 8, 16};
        for (int z : aisles) for (int x = 58; x <= 82; x += 8) {
            List<double[]> l = new ArrayList<>(aq);
            if (z != 0) l.add(pt(55, z));
            l.add(pt(x, z));
            spot("aq_" + z + "_" + x, 0, z < 0 ? FACE_N : FACE_S, l.toArray(new double[0][]));
        }
        List<double[]> down = new ArrayList<>(aq);
        down.add(pt(55, 7)); down.add(pt(54, 7)); down.add(pt(54, 8.6));
        for (int k = 0; k < 7; k++) { // each step: across it, out over the next one, then down onto it
            double fy = Halcyon.HF - k;   // the feet stand on the step at HF-(k+1)... on top of it = HF - k
            down.add(pt(54, fy, 9 + k - 0.45)); down.add(pt(54, fy, 9 + k + 0.45));
            if (k < 6) down.add(pt(54, fy, 9 + k + 0.6));
        }
        double bf = Halcyon.BASE_FLOOR + 1;
        down.add(pt(54, bf, 16)); down.add(pt(55, bf, 16));
        for (int z : aisles) for (int x = 58; x <= 90; x += 8) {
            List<double[]> l = new ArrayList<>(down);
            if (z != 16) l.add(pt(55, bf, z));
            l.add(pt(x, bf, z));
            spot("aqb_" + z + "_" + x, 0, z < 0 ? FACE_N : FACE_S, l.toArray(new double[0][]));
        }
        spot("aq_curator", 0, FACE_W, aq, pt(55, -5), pt(54, -5));
        // ---- the south avenue: the tavern, the Town Hall, the dock office
        double[] sAve = pt(0, 45), tavDoor = pt(0, 63);
        spot("tavern", 90, 270, sAve, tavDoor, pt(-6, 63), pt(-11, 63));
        double[][] seats = {{-15.5, 57.5}, {-15.5, 59.5}, {-15.5, 64.5}, {-15.5, 67.5}, {-10.5, 58}, {-10.5, 66}};
        for (int i = 0; i < seats.length; i++) spot("tavern_seat" + i, 90, 180, sAve, tavDoor, pt(-6, 63), pt(-11, 63), pt(-15.5, 62), pt(seats[i][0], seats[i][1]));
        spot("tavern_cook", 90, 90, sAve, tavDoor, pt(-6, 63), pt(-11, 63), pt(-11, 70.5), pt(-24.5, 70.5), pt(-24.5, 63));
        spot("townhall", 90, FACE_E, sAve, tavDoor, pt(6, 63), pt(11, 63), pt(23, 63));
        int[] dm = Halcyon.dockMaster();
        spot("dockmaster", 90, 270, sAve, tavDoor, pt(0, 85), pt(dm[0] - Halcyon.HX, dm[1] - Halcyon.HZ));
        spot("dock_gate", 90, 90, sAve, tavDoor, pt(0, 92));
        // ---- the school (lane z 75) and the clinic (lane z 78, then x 28)
        List<double[]> sch = List.of(sAve, pt(0, 75), pt(-35, 75), pt(-35, 73), pt(-38, 73));
        spot("school_teacher", 90, FACE_W, sch, pt(-50, 73));
        int d = 0;
        for (int x = -48; x <= -42; x += 3) for (int z : new int[]{73, 74}) spot("school_desk" + d++, 90, z == 73 ? FACE_N : FACE_S, sch, pt(x, z));
        spot("clinic", 90, FACE_N, sAve, pt(0, 78), pt(28, 78), pt(28, 68), pt(31, 68), pt(33, 64));
        spot("clinic_beds", 90, FACE_E, sAve, pt(0, 78), pt(28, 78), pt(28, 68), pt(31, 68), pt(42, 68));
        // ---- the greenhouse (lane x 50) and the park (lane x -77)
        List<double[]> gh = List.of(pt(44, 0), pt(50, 0), pt(50, 36), pt(55, 36), pt(58, 36));
        spot("gh0", 0, FACE_N, gh, pt(61, 36));
        spot("gh1", 0, FACE_S, gh, pt(64, 36));
        spot("gh2", 0, FACE_N, gh, pt(57, 36), pt(57, 32), pt(66, 32));
        spot("gh3", 0, FACE_S, gh, pt(57, 36), pt(57, 40), pt(72, 40));
        List<double[]> park = List.of(pt(-40, 0), pt(-77, 0), pt(-77, 20));
        for (int k = 0; k < 7; k++) { // round the path ring (r 9.75 from the pond), from where the lane meets it
            double a0 = Math.atan2(20 - Halcyon.PARK_Z, -77 - Halcyon.PARK_X);
            List<double[]> l = new ArrayList<>(park);
            for (int s = 1; s <= k + 1; s++) {
                double a = a0 - s * 0.38;
                l.add(pt(Halcyon.PARK_X + Math.cos(a) * 9.75, Halcyon.PARK_Z + Math.sin(a) * 9.75));
            }
            double[] e = l.get(l.size() - 1);
            spot("park" + k, 180, yawTo(e, pt(Halcyon.PARK_X, Halcyon.PARK_Z)), l.toArray(new double[0][]));
        }
        // ---- the market
        int[][] st = Halcyon.stalls();
        for (int i = 0; i < st.length; i++) {
            int sx = st[i][0], sz = st[i][1], side = st[i][2];
            spot("stall" + i, 180, side < 0 ? 90 : 270, pt(-40, 0), pt(sx + 3, 0), pt(sx + 3, sz + side), pt(sx, sz + side));
        }
        spot("west_gate", 180, 180, pt(-40, 0), pt(-92, 0));
        // ---- the plaza: spots round its edge (where they're let loose)
        for (int a = 0; a < 360; a += 30) {
            double r = Math.toRadians(a + 15);
            spot("plaza" + a, a + 15, a + 195, pt(Math.cos(r) * 23.5, Math.sin(r) * 23.5));
        }
        // ---- the building site: round its edge
        double sa = angleOf(45, -52);
        for (int x = 39; x <= 51; x += 3) spot("site_n" + x, sa, 0, pt(45, -52), pt(45, -55), pt(x, -55));
        for (int z = -58; z >= -65; z -= 3) {
            spot("site_w" + z, sa, 0, pt(45, -52), pt(45, -55), pt(39, -55), pt(39, z));
            spot("site_e" + z, sa, 0, pt(45, -52), pt(45, -55), pt(51, -55), pt(51, z));
        }
        agriSpots();
        guardSpots();
        mineSpots();
        // ---- guard posts round the main dome
        spot("post_lab", 270, 90, pt(0, -40), pt(0, -48));
        spot("post_aq", 0, 180, pt(44, 0), pt(48, 0));
        for (int a = 0; a < 360; a += 45) spot("ring" + a, a, a + 90);
    }

    /** From the ring through the glass hallway into the Agri dome, to its middle. */
    static final double AGRI_A = angleOf(-68, 59), GUARD_A = angleOf(68, 59);
    static final List<double[]> TO_AGRI = List.of(pt(-68, 59), pt(-110, 96), pt(-134, 117));
    static final List<double[]> TO_GUARD = List.of(pt(68, 59), pt(104, 91), pt(128, 113));

    void agriSpots() {
        int cx = (int) Halcyon.AGRI.cx(), cz = (int) Halcyon.AGRI.cz();
        // the fields: along a glowing path row
        for (int lz = 4; lz <= 40; lz += 4) for (int lx = -36; lx <= 36; lx += 6) {
            int x = cx + lx, z = cz + lz;
            if (Math.abs(lx) < 4) continue;
            if (!Halcyon.farmCell(x, z + 1) || !Halcyon.farmCell(x, z - 1) || !Halcyon.farmCell(x, z)) continue;
            spot("farm_" + lz + "_" + lx, AGRI_A, lx < 0 ? FACE_W : FACE_E, TO_AGRI, pt(cx, z), pt(x, z));
        }
        // the orchard: up the lane, along the gap past the tree's row, in beside its trunk
        for (int i = 0; i < Halcyon.TREES.size(); i++) {
            int[] tr = Halcyon.TREES.get(i);
            spot("tree" + i, AGRI_A, FACE_N, TO_AGRI, pt(cx, tr[1] + 3), pt(tr[0], tr[1] + 3), pt(tr[0], tr[1] + 1));
        }
        int[] pile = Halcyon.LOG_PILE;
        spot("logpile", AGRI_A, FACE_N, TO_AGRI, pt(cx, pile[1] + 1), pt(pile[0] + 2, pile[1] + 1));
        // the barn: in, and beside each bunk
        double[] barnIn = pt(cx + Halcyon.BARN_X0 + 2, cz - 33);
        List<double[]> barn = new ArrayList<>(TO_AGRI);
        barn.add(pt(cx, cz - 33)); barn.add(pt(cx + Halcyon.BARN_X0 - 1, cz - 33)); barn.add(barnIn);
        spot("barn", AGRI_A, FACE_E, barn.toArray(new double[0][]));
        int[][] beds = Halcyon.agriBeds();
        for (int i = 0; i < beds.length; i++) {
            boolean north = beds[i][2] == 0;
            spot("barn_bed" + i, AGRI_A, north ? FACE_N : FACE_S, barn, pt(beds[i][0] + 1, cz - 33), pt(beds[i][0] + 1, beds[i][1] + (north ? 2 : -2)));
        }
    }

    void guardSpots() {
        int cx = (int) Halcyon.GUARD.cx(), cz = (int) Halcyon.GUARD.cz();
        // the barracks: the lane west, up to its door, in, beside each bed
        List<double[]> bar = new ArrayList<>(TO_GUARD);
        bar.add(pt(cx - 8, cz + 1)); bar.add(pt(cx - 8, cz + 13)); bar.add(pt(cx - 4, cz + 13));
        spot("barracks", GUARD_A, FACE_E, bar.toArray(new double[0][]));
        int[][] beds = Halcyon.guardBeds();
        for (int i = 0; i < beds.length; i++) {
            boolean north = beds[i][2] == 0;
            spot("barracks_bed" + i, GUARD_A, north ? FACE_N : FACE_S, bar, pt(beds[i][0] + 1, cz + 13), pt(beds[i][0] + 1, beds[i][1] + (north ? 2 : -2)));
        }
        spot("armory", GUARD_A, FACE_N, TO_GUARD, pt(cx - 8, cz + 1), pt(cx - 8, cz + 12), pt(cx - 11, cz + 12), pt(cx - 18, cz + 10));
        // the yard: through the fence's gap, to a dummy; the range
        List<double[]> yard = new ArrayList<>(TO_GUARD);
        yard.add(pt(cx - 8, cz + 1)); yard.add(pt(cx - 8, cz - 8));
        for (int i = 0; i < Halcyon.DUMMIES.length; i++) spot("train_dummy" + i, GUARD_A, FACE_N, yard, pt(Halcyon.DUMMIES[i][0], cz - 8));
        for (int i = 0; i < Halcyon.TARGETS.length; i++) spot("train_range" + i, GUARD_A, FACE_E, yard, pt(cx - 8, Halcyon.TARGETS[i][1]));
        spot("tower", GUARD_A, FACE_S, TO_GUARD, pt(cx + 22, cz + 1), pt(cx + 22, cz + 9));
    }

    /** The mine: down the stair tunnel into the gallery; the ore faces, the dive room, the bunkhouse. */
    static final double MINE_A = 225;
    static final List<double[]> TO_MINE = List.of(pt(-45, -45), pt(-47, -45), pt(-47, -55), pt(-51, -55));

    static List<double[]> gallery() {
        List<double[]> dl = new ArrayList<>(TO_MINE);
        for (int i = 0; i <= Halcyon.DEPTH; i++) {
            dl.add(pt(-52 - i + 0.45, Halcyon.G - i, -55));
            dl.add(pt(-52 - i - 0.45, Halcyon.G - i, -55));
            if (i < Halcyon.DEPTH) dl.add(pt(-52 - i - 0.6, Halcyon.G - i, -55));
        }
        dl.add(pt(-69, Halcyon.GY + 1, -55));
        return dl;
    }

    void mineSpots() {
        double gy = Halcyon.GY + 1;
        List<double[]> gal = gallery();
        for (int i = 0; i < Halcyon.ORES.size(); i++) {
            int[] o = Halcyon.ORES.get(i);
            spot("ore" + i, MINE_A, yawTo(pt(o[3], o[4]), pt(o[0], o[2])), gal, pt(o[3], gy, -55), pt(o[3], gy, o[4]));
        }
        spot("dive", MINE_A, FACE_W, gal, pt(-85, gy, -55), pt(-85, gy, -56), pt(-88, gy, -56), pt(-91, gy, -56));
        spot("dive_bench", MINE_A, FACE_S, gal, pt(-85, gy, -55), pt(-85, gy, -56), pt(-88, gy, -56), pt(-89, gy, -53));
        List<double[]> bunk = new ArrayList<>(List.of(pt(-45, -45), pt(-47, -45), pt(-47, -63), pt(-45, -63), pt(-42, -63)));
        spot("bunk", MINE_A, FACE_E, bunk.toArray(new double[0][]));
        int[][] beds = Halcyon.bunkBeds();
        for (int i = 0; i < beds.length; i++) {
            boolean north = beds[i][2] == 0;
            spot("bunk_bed" + i, MINE_A, north ? FACE_N : FACE_S, bunk, pt(beds[i][0] + 1, -63), pt(beds[i][0] + 1, beds[i][1] + (north ? 2 : -2)));
        }
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
        List<Job> town = new ArrayList<>(List.of(Job.BUILDER, Job.BUILDER, Job.BUILDER, Job.SCIENTIST, Job.SCIENTIST, Job.SCIENTIST, Job.SCIENTIST,
                Job.AQUARIST, Job.AQUARIST, Job.AQUARIST, Job.MERCHANT, Job.MERCHANT, Job.MERCHANT, Job.MERCHANT, Job.MERCHANT, Job.MERCHANT,
                Job.COOK, Job.CURATOR, Job.TEACHER, Job.DOCTOR, Job.MAYOR, Job.GARDENER, Job.GARDENER, Job.GARDENER, Job.IDLER, Job.IDLER));
        Random r = new Random(7341);
        java.util.Collections.shuffle(town, r);
        int n = 0, stall = 0;
        Villager.Profession[] stallProf = {Villager.Profession.LIBRARIAN, Villager.Profession.CARTOGRAPHER, Villager.Profession.BUTCHER,
                Villager.Profession.LEATHERWORKER, Villager.Profession.SHEPHERD, Villager.Profession.ARMORER};
        Villager.Type[] types = {Villager.Type.PLAINS, Villager.Type.SWAMP, Villager.Type.TAIGA, Villager.Type.SNOW, Villager.Type.SAVANNA, Villager.Type.JUNGLE, Villager.Type.DESERT};
        for (Halcyon.House h : Halcyon.HOUSES) for (int b = 0; b < h.beds() && n < town.size(); b++) {
            Job j = town.get(n++);
            Person p = add(j, h.i(), b, Bed.HOUSE, types[r.nextInt(types.length)]);
            if (j == Job.MERCHANT) { p.stall = stall; p.prof = stallProf[stall % stallProf.length]; stall++; }
        }
        Job[] barn = {Job.FARMER, Job.FARMER, Job.FARMER, Job.FARMER, Job.FARMER, Job.WOODCUTTER, Job.WOODCUTTER, Job.WOODCUTTER};
        for (int i = 0; i < barn.length && i < Halcyon.agriBeds().length; i++) add(barn[i], -1, i, Bed.BARN, types[r.nextInt(types.length)]);
        Job[] bunk = {Job.MINER, Job.MINER, Job.MINER, Job.DIVER, Job.DIVER, Job.DIVER};
        for (int i = 0; i < bunk.length && i < Halcyon.bunkBeds().length; i++) add(bunk[i], -1, i, Bed.BUNK, types[r.nextInt(types.length)]);
        for (int g = 0; g < 8; g++) add(Job.GUARD, -1, g, Bed.BARRACKS, types[r.nextInt(types.length)]).night = g % 2 == 1;
        for (int c = 0; c < 4; c++) add(Job.CHILD, 1 + c * 3, 0, Bed.HOUSE, types[r.nextInt(types.length)]);
        Person dm = new Person(people.size(), "Captain " + FIRST[(people.size() + 5) % FIRST.length], Job.DOCKMASTER, -1, 0, Bed.HOUSE);
        dm.prof = Job.DOCKMASTER.prof;
        dm.type = Villager.Type.SWAMP;
        people.add(dm);
    }

    Person add(Job j, int house, int bed, Bed sleeps, Villager.Type type) {
        Person p = new Person(people.size(), FIRST[people.size() % FIRST.length], j, house, bed, sleeps);
        p.prof = j.prof;
        p.type = type;
        people.add(p);
        return p;
    }

    // =========================================================================================== spawning
    /** The Dark Ocean's clock (or the main world's, if its daylight cycle is switched off: their day must still turn). */
    long time() {
        if (pl.world == null) return 6000;
        org.bukkit.World w = pl.world;
        if (Boolean.FALSE.equals(w.getGameRuleValue(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE)) && !Bukkit.getWorlds().isEmpty()) w = Bukkit.getWorlds().get(0);
        return Math.floorMod(w.getTime(), 24000);
    }

    void spawnAll() {
        if (pl.world == null) return;
        if (!pl.world.isChunkLoaded(Halcyon.HX >> 4, Halcyon.HZ >> 4)) return;
        planDone = -1;
        restoreWork();
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
        try { v.setVillagerLevel(5); v.setVillagerExperience(250); } catch (RuntimeException ignored) { }
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
        p.free = false;
        p.diveStage = 0;
        p.swim.clear();
        byEntity.put(v.getUniqueId(), p);
        arrived(p);
    }

    String nameOf(Person p) {
        ChatColor c = p.job == Job.GUARD ? ChatColor.BLUE : p.job == Job.DOCKMASTER || p.job == Job.MAYOR ? ChatColor.AQUA : p.job == Job.CHILD ? ChatColor.YELLOW : ChatColor.GREEN;
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
            p.swim.clear();
            p.chasing = null;
            p.free = false;
        }
        treeClaimed.clear();
        oreClaimed.clear();
        // the divers' ore comes back now (nothing would remember it after a restart)
        for (var en : diveDug.entrySet()) if (en.getKey().getType() == Material.WATER) en.getKey().setType(en.getValue(), false);
        diveDug.clear();
        diveDugAt.clear();
    }

    String summary() {
        int alive = 0, dead = 0, sleeping = 0, free = 0, diving = 0, felled = 0, dug = 0;
        for (Person p : people) { if (p.alive()) { alive++; if (p.v.isSleeping()) sleeping++; if (p.free) free++; if (p.diveStage >= 2 && p.diveStage <= 4) diving++; } else if (p.deadUntil > System.currentTimeMillis()) dead++; }
        for (long t : treeFelled) if (t > 0) felled++;
        for (long t : oreDug) if (t > 0) dug++;
        return people.size() + " citizens: " + alive + " out (" + sleeping + " asleep, " + free + " loose on the plaza, " + diving + " diving), " + dead + " dead. Site: "
                + Math.max(0, planDone) + "/" + Halcyon.PLAN.size() + " blocks. Trees down: " + felled + ", ore faces dug: " + dug + ", dive ore dug: " + diveDug.size() + ".";
    }

    // =========================================================================================== the day
    Act schedule(Person p) {
        long t = time();
        if (p.job == Job.DOCKMASTER) return Act.STAY;
        if (p.job == Job.GUARD) return p.onDuty(t) ? Act.PATROL : Act.SLEEP;
        if (p.diveStage >= 1 && p.diveStage <= 4) return Act.DIVE; // never stop in the middle of a dive
        if (t >= 13000 && t < 23500) return Act.SLEEP;
        if (t >= 23500 || t < 1000) return Act.BREAKFAST;
        if (t >= 12000) return Act.HOME;
        if (p.job == Job.CHILD) return t < 6000 ? Act.SCHOOL : Act.PLAY;
        if (t >= 6000 && t < 7200 && p.id % 2 == 0 && p.job != Job.MERCHANT) return Act.LUNCH;
        if (t >= 9000) return p.job == Job.MERCHANT || p.job == Job.CURATOR || p.job == Job.DOCTOR ? (t >= 11000 ? Act.EVENING : Act.WORK) : Act.EVENING;
        if (p.job == Job.IDLER) return Act.EVENING;
        return Act.WORK;
    }

    Spot homeSpot(Person p) {
        return switch (p.sleeps) {
            case BARN -> spots.get("barn");
            case BUNK -> spots.get("bunk");
            case BARRACKS -> spots.get("barracks");
            default -> p.house >= 0 ? spots.get("home" + p.house) : spots.get("tavern");
        };
    }

    Spot bedSpot(Person p) {
        return switch (p.sleeps) {
            case BARN -> spots.get("barn_bed" + p.bed);
            case BUNK -> spots.get("bunk_bed" + p.bed);
            case BARRACKS -> spots.get("barracks_bed" + p.bed);
            default -> p.job == Job.CHILD ? spots.get("home" + p.house) : spots.get("home" + p.house + "_bed" + p.bed);
        };
    }

    /** {world x, world z} of their bed's head. */
    int[] bedHead(Person p) {
        int[] b = switch (p.sleeps) {
            case BARN -> Halcyon.agriBeds()[p.bed];
            case BUNK -> Halcyon.bunkBeds()[p.bed];
            case BARRACKS -> Halcyon.guardBeds()[p.bed];
            default -> null;
        };
        if (b != null) return new int[]{Halcyon.HX + b[0], Halcyon.HZ + b[1]};
        return p.house >= 0 ? Halcyon.HOUSES.get(p.house).bed(p.bed) : null;
    }

    Spot target(Person p, Act a) {
        return switch (a) {
            case SLEEP -> bedSpot(p);
            case BREAKFAST -> p.sleeps == Bed.HOUSE && p.house >= 0 ? spots.get("home" + p.house + "_eat") : p.sleeps == Bed.BARRACKS ? spots.get("barracks") : homeSpot(p);
            case HOME -> homeSpot(p);
            case LUNCH -> spots.get("tavern_seat" + (p.id % 6));
            case EVENING, PLAY -> spots.get("plaza" + (random.nextInt(12) * 30));
            case SCHOOL -> spots.get("school_desk" + (p.id % 6));
            case STAY -> spots.get("dockmaster");
            case PATROL -> patrolSpot(p);
            case WORK -> workSpot(p);
            case DIVE -> spots.get("dive");
            default -> p.at;
        };
    }

    static final String[] POSTS = {"post_lab", "post_aq", "dock_gate", "west_gate", "ring0", "ring45", "ring90", "ring135", "ring180", "ring225", "ring270", "ring315",
            "farm_8_-12", "logpile", "site_n45", "ore0", "tower", "armory", "barn", "bunk", "park2", "school_teacher", "gh0", "aq_0_74", "aqb_16_74"};

    Spot patrolSpot(Person p) {
        // a third of the time on duty: training (the dummies, the range)
        if (random.nextInt(3) == 0) {
            Spot s = spots.get(random.nextBoolean() ? "train_dummy" + random.nextInt(Halcyon.DUMMIES.length) : "train_range" + random.nextInt(Halcyon.TARGETS.length));
            if (s != null) return s;
        }
        Spot s = spots.get(POSTS[random.nextInt(POSTS.length)]);
        return s != null ? s : spots.get("ring0");
    }

    Spot workSpot(Person p) {
        List<String> keys = new ArrayList<>();
        switch (p.job) {
            case FARMER -> spots.keySet().stream().filter(k -> k.startsWith("farm_")).forEach(keys::add);
            case WOODCUTTER -> { return treeSpot(p); }
            case MINER -> { return oreSpot(p); }
            case DIVER -> { return divePath() != null && random.nextInt(4) != 0 ? spots.get("dive") : spots.get("dive_bench"); }
            case BUILDER -> { return builderSpot(p); }
            case SCIENTIST -> spots.keySet().stream().filter(k -> k.startsWith("lab_")).forEach(keys::add);
            case AQUARIST -> spots.keySet().stream().filter(k -> k.startsWith("aq_") || k.startsWith("aqb_")).filter(k -> !k.equals("aq_curator")).forEach(keys::add);
            case MERCHANT -> { return spots.get("stall" + p.stall); }
            case COOK -> { return spots.get("tavern_cook"); }
            case CURATOR -> { return spots.get("aq_curator"); }
            case TEACHER -> { return spots.get("school_teacher"); }
            case DOCTOR -> { return spots.get(random.nextInt(3) == 0 ? "clinic_beds" : "clinic"); }
            case MAYOR -> { return spots.get(random.nextInt(4) == 0 ? "plaza" + (random.nextInt(12) * 30) : "townhall"); }
            case GARDENER -> spots.keySet().stream().filter(k -> k.startsWith("gh") || k.startsWith("park")).forEach(keys::add);
            default -> spots.keySet().stream().filter(k -> k.startsWith("plaza")).forEach(keys::add);
        }
        if (keys.isEmpty()) return spots.get("plaza0");
        java.util.Collections.sort(keys);
        return spots.get(keys.get(random.nextInt(keys.size())));
    }

    /** A standing tree nobody else is on (else the log pile, with what they're carrying, or any tree to check on). */
    Spot treeSpot(Person p) {
        if (p.tree >= 0) { treeClaimed.remove(p.tree); p.tree = -1; }
        if (p.carry > 0) return spots.get("logpile");
        List<Integer> up = new ArrayList<>();
        for (int i = 0; i < Halcyon.TREES.size(); i++) if (treeFelled[i] == 0 && !treeClaimed.contains(i) && treeStanding(i)) up.add(i);
        if (up.isEmpty()) return spots.get("logpile");
        int i = up.get(random.nextInt(up.size()));
        p.tree = i;
        treeClaimed.add(i);
        return spots.get("tree" + i);
    }

    boolean treeStanding(int i) {
        int[] t = Halcyon.TREES.get(i);
        return pl.world.getBlockAt(Halcyon.HX + t[0], Halcyon.G, Halcyon.HZ + t[1]).getType().name().endsWith("_LOG");
    }

    Spot oreSpot(Person p) {
        if (p.ore >= 0) { oreClaimed.remove(p.ore); p.ore = -1; }
        List<Integer> up = new ArrayList<>();
        for (int i = 0; i < Halcyon.ORES.size(); i++) if (oreDug[i] == 0 && !oreClaimed.contains(i) && oreBlock(i).getType() != Material.AIR) up.add(i);
        if (up.isEmpty()) return spots.get("ore" + random.nextInt(Halcyon.ORES.size())); // nothing to dig: inspect the faces
        int i = up.get(random.nextInt(up.size()));
        p.ore = i;
        oreClaimed.add(i);
        return spots.get("ore" + i);
    }

    Block oreBlock(int i) { int[] o = Halcyon.ORES.get(i); return pl.world.getBlockAt(Halcyon.HX + o[0], o[1], Halcyon.HZ + o[2]); }

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
        if (ticks % 20 == 0) { siteTick(); regrowTick(); }
    }

    void step(Person p, long t) {
        p.t++;
        Villager v = p.v;
        if (v.isTrading()) { // someone's looking at their wares: stand still and face them
            if (v.getTrader() != null && !p.free) face(p, v.getTrader().getLocation());
            return;
        }
        // guards: hunt anyone wanted in the city
        if (p.job == Job.GUARD && (p.act == Act.CHASE || p.t % 10 == 0)) {
            Player prey = prey(p);
            if (prey != null) { if (v.isSleeping()) v.wakeup(); chase(p, prey); return; }
            if (p.act == Act.CHASE) { p.act = null; p.chasing = null; backToStreets(p); }
        }
        if (p.act == Act.PANIC && System.currentTimeMillis() > p.panicUntil) { p.act = null; p.speed = 0.11; }
        if (p.free) { loose(p); if (p.t % 20 == 0 && schedule(p) != p.act) go(p, schedule(p), target(p, schedule(p))); return; }
        if (p.diveStage >= 2 && p.diveStage <= 4) { diveTick(p); return; }
        if (!p.route.isEmpty()) { walk(p); return; }
        if (p.act == Act.PANIC) { cower(p); return; }
        // what should they be doing?
        if (p.t % 20 == 0) {
            Act want = schedule(p);
            boolean moveOn = want != p.act || (want == Act.WORK || want == Act.PATROL) && p.t >= p.until;
            if (want == Act.DIVE && p.act == Act.DIVE) moveOn = false;
            if (moveOn) { go(p, want, target(p, want)); return; }
        }
        doing(p);
    }

    void go(Person p, Act a, Spot s) {
        if (p.free) unloose(p); else p.route.clear();
        if (p.v.isSleeping()) p.v.wakeup();
        try { p.v.setPose(Pose.STANDING, false); } catch (Throwable ignored) { }
        if (p.job == Job.DIVER && p.diveStage == 5) { suit(p, false); p.diveStage = 0; }
        p.act = a;
        if (s == null) s = p.at;
        p.goal = s;
        hold(p, p.carry > 0 ? new ItemStack(Material.OAK_LOG) : null);
        if (s != p.at) p.route.addAll(route(p.at, s));
        p.speed = a == Act.PLAY ? 0.2 : p.job == Job.GUARD ? 0.13 : 0.11;
        if (p.route.isEmpty()) { p.at = s; arrived(p); }
    }

    /** One step along the route. */
    void walk(Person p) {
        double[] next = p.route.peekFirst();
        // never into a chunk that isn't loaded (a teleport there would load it on the main thread): wait at the edge
        if (!pl.world.isChunkLoaded((Halcyon.HX + (int) Math.floor(next[0] + 0.5)) >> 4, (Halcyon.HZ + (int) Math.floor(next[2] + 0.5)) >> 4)) return;
        double dx = next[0] - p.pos[0], dy = next[1] - p.pos[1], dz = next[2] - p.pos[2];
        double d = Math.sqrt(dx * dx + dz * dz + dy * dy);
        double sp = p.speed;
        if (d <= sp) { p.pos = next.clone(); p.route.pollFirst(); if (p.route.isEmpty()) { p.at = p.goal; arrived(p); return; } }
        else { p.pos[0] += dx / d * sp; p.pos[1] += dy / d * sp; p.pos[2] += dz / d * sp; }
        if (Math.hypot(dx, dz) > 0.05) p.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        double bob = p.act == Act.PLAY ? Math.abs(Math.sin(p.t * 0.5)) * 0.35 : 0;
        p.v.teleport(loc(new double[]{p.pos[0], p.pos[1] + bob, p.pos[2]}, p.yaw));
    }

    void arrived(Person p) {
        p.t = 0;
        p.doing = p.act;
        p.until = 20 * (p.act == Act.WORK ? 15 + random.nextInt(25) : p.act == Act.PATROL ? 6 + random.nextInt(10) : 30 + random.nextInt(30));
        Spot s = p.at;
        if (s != null) { p.yaw = (float) s.face(); p.v.teleport(loc(p.pos, p.yaw)); }
        if (p.job == Job.DIVER && p.act == Act.WORK && s != null && s.key().equals("dive") && divePath() != null) p.act = Act.DIVE;
        switch (p.act == null ? Act.STAY : p.act) {
            case SLEEP -> sleep(p);
            case BREAKFAST, LUNCH -> hold(p, new ItemStack(random.nextBoolean() ? Material.BREAD : Material.COOKED_COD));
            case PATROL -> hold(p, new ItemStack(s != null && s.key().startsWith("train_range") ? Material.BOW : Material.IRON_SWORD));
            case WORK -> hold(p, tool(p));
            case STAY -> hold(p, new ItemStack(Material.FILLED_MAP));
            case SCHOOL -> hold(p, new ItemStack(Material.BOOK));
            case DIVE -> { hold(p, null); p.diveStage = 1; say(p, Lines.pick(Lines.DIVE, random)); }
            case EVENING, PLAY -> { hold(p, null); if (s != null && s.key().startsWith("plaza")) loosen(p); }
            default -> hold(p, null);
        }
    }

    ItemStack tool(Person p) {
        Material m = switch (p.job) {
            case FARMER -> Material.IRON_HOE;
            case WOODCUTTER -> p.carry > 0 ? Material.OAK_LOG : Material.IRON_AXE;
            case MINER -> Material.IRON_PICKAXE;
            case DIVER -> Material.IRON_PICKAXE;
            case BUILDER -> Material.BRICKS;
            case SCIENTIST -> Material.GLASS_BOTTLE;
            case AQUARIST -> Material.TROPICAL_FISH_BUCKET;
            case MERCHANT -> Material.EMERALD;
            case COOK -> Material.BREAD;
            case CURATOR, TEACHER, MAYOR -> Material.WRITABLE_BOOK;
            case DOCTOR -> Material.POTION;
            case GARDENER -> Material.BONE_MEAL;
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
        int[] head = bedHead(p);
        boolean ok = false;
        if (head != null) {
            Location bed = new Location(pl.world, head[0], Halcyon.G, head[1]);
            try { ok = bed.getBlock().getType().name().endsWith("_BED") && p.v.sleep(bed); } catch (Throwable ignored) { }
        }
        if (!ok) try { p.v.setPose(Pose.SLEEPING, true); } catch (Throwable ignored) { }
    }

    // =========================================================================================== let loose: the game's own villager brain
    static final double LOOSE_R = 24.5;

    /** On the plaza in the evening / at play / idle: their AI on, the meeting bell and their bed in their memory. */
    void loosen(Person p) {
        if (!cfgBool("vanilla-evenings", true)) return;
        p.free = true;
        Villager v = p.v;
        try {
            v.setAI(true);
            v.setAware(true);
            v.setMemory(MemoryKey.MEETING_POINT, new Location(pl.world, Halcyon.HX + Halcyon.BELL[0], Halcyon.G, Halcyon.HZ + Halcyon.BELL[1]));
            int[] head = bedHead(p);
            if (head != null) v.setMemory(MemoryKey.HOME, new Location(pl.world, head[0], Halcyon.G, head[1]));
        } catch (Throwable ignored) { }
    }

    boolean cfgBool(String path, boolean def) { return pl.getConfig().getBoolean("halcyon.citizens." + path, def); }

    /** Kept to the plaza: anyone who wanders off it is brought back. */
    void loose(Person p) {
        Location l = p.v.getLocation();
        double rx = l.getX() - Halcyon.HX, rz = l.getZ() - Halcyon.HZ;
        p.pos = new double[]{rx - 0.5, l.getY(), rz - 0.5};
        if (p.t % 10 == 0 && (Math.hypot(rx, rz) > LOOSE_R + 1.5 || Math.abs(l.getY() - Halcyon.G) > 3 || !Halcyon.inside(l.getX(), l.getY() + 0.5, l.getZ()))) {
            double a = Math.atan2(rz, rx);
            double[] back = pt(Math.cos(a) * (LOOSE_R - 3), Math.sin(a) * (LOOSE_R - 3));
            p.v.teleport(loc(back, p.yaw));
            p.pos = back;
        }
        if (p.t % 300 == 120 && random.nextInt(4) == 0 && near(l, 14)) say(p, Lines.pick(p.job == Job.CHILD ? Lines.CHILD : Lines.EVENING, random));
    }

    /** Back to scripted walking: out to the plaza's edge, then along the streets. */
    void unloose(Person p) {
        p.free = false;
        try { p.v.setAI(false); } catch (Throwable ignored) { }
        Location l = p.v.getLocation();
        double rx = l.getX() - Halcyon.HX - 0.5, rz = l.getZ() - Halcyon.HZ - 0.5;
        p.pos = new double[]{rx, Halcyon.G, rz};
        double ang = Math.toDegrees(Math.atan2(rz, rx));
        double[] edge = pt(Math.cos(Math.toRadians(ang)) * 23.5, Math.sin(Math.toRadians(ang)) * 23.5);
        p.route.clear();
        p.route.add(edge);
        p.at = new Spot("loose", ang, List.of(ring(ang), edge), ang);
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
            case DIVE -> diveRoom(p);
            case PATROL -> {
                String k = p.at == null ? "" : p.at.key();
                if (k.startsWith("train_dummy")) trainDummy(p);
                else if (k.startsWith("train_range")) trainRange(p);
                else {
                    if (p.t % 40 == 0) lookAround(p);
                    if (p.t % 300 == 150 && random.nextInt(3) == 0 && near(l, 12)) say(p, Lines.pick(Lines.GUARD, random));
                }
            }
            case SCHOOL -> { if (p.t % 50 == 0) nod(p, 20); if (p.t % 400 == 200 && random.nextInt(3) == 0 && near(l, 10)) say(p, Lines.pick(Lines.CHILD, random)); }
            case EVENING, PLAY, HOME -> {
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
                if (p.t % 30 == 15) farm(p, w);
                if (p.t % 400 == 300 && random.nextInt(3) == 0 && near(l, 12)) say(p, Lines.pick(Lines.FARM, random));
            }
            case WOODCUTTER -> {
                if (p.at != null && p.at.key().equals("logpile")) {
                    if (p.t == 20 && p.carry > 0) {
                        w.playSound(l, Sound.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 0.9f, 0.8f);
                        w.spawnParticle(Particle.BLOCK, l.clone().add(-1, 1, -1), 12, 0.4, 0.3, 0.4, 0, Material.OAK_LOG.createBlockData());
                        p.carry = 0;
                        hold(p, null);
                        p.until = 60;
                    }
                    break;
                }
                chop(p, w, seen);
            }
            case MINER -> dig(p, w, seen);
            case DIVER -> { if (p.t % 10 == 0) nod(p, 20); }
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
                    Location front = l.clone().add(l.getDirection().setY(0).normalize().multiply(2.5)).add(0, 1.8, 0);
                    w.spawnParticle(Particle.BUBBLE_POP, front, 12, 0.6, 0.4, 0.6, 0.02);
                    w.spawnParticle(Particle.ITEM, front, 5, 0.3, 0.1, 0.3, 0.02, new ItemStack(Material.KELP));
                    w.playSound(front, Sound.ITEM_BUCKET_EMPTY_FISH, SoundCategory.NEUTRAL, 0.7f, 1.1f);
                }
                if (p.t % 300 == 200 && random.nextInt(3) == 0 && near(l, 12)) say(p, Lines.pick(Lines.AQUA, random));
            }
            case MERCHANT, CURATOR -> {
                if (p.t % 20 == 0) faceNearest(p);
                if (p.t % 300 == 50 && random.nextInt(2) == 0 && near(l, 10)) say(p, Lines.pick(p.job == Job.CURATOR ? Lines.CURATOR : Lines.MARKET, random));
            }
            case TEACHER -> {
                if (p.t % 40 == 0) { p.v.setRotation(p.yaw + 180 + (random.nextFloat() - 0.5f) * 60, 0); }
                if (p.t % 40 == 20) p.v.setRotation(p.yaw, 0);
                if (p.t % 300 == 80 && near(l, 14)) say(p, Lines.pick(Lines.SCHOOL, random));
            }
            case DOCTOR -> {
                if (p.t % 10 == 0) nod(p, 25);
                if (p.t % 40 == 0 && seen) w.spawnParticle(Particle.HAPPY_VILLAGER, l.clone().add(l.getDirection().setY(0).normalize().multiply(1.5)).add(0, 1.2, 0), 4, 0.3, 0.3, 0.3, 0);
                if (p.t % 300 == 140 && random.nextInt(2) == 0 && near(l, 12)) say(p, Lines.pick(Lines.DOCTOR, random));
            }
            case MAYOR -> {
                if (p.t % 20 == 0) faceNearest(p);
                if (p.t % 300 == 60 && random.nextInt(2) == 0 && near(l, 14)) say(p, Lines.pick(Lines.MAYOR, random));
            }
            case GARDENER -> {
                if (p.t % 8 == 0) nod(p, 40);
                if (p.t % 40 == 20 && seen) {
                    Location front = l.clone().add(l.getDirection().setY(0).normalize().multiply(1.2)).add(0, 0.4, 0);
                    w.spawnParticle(Particle.HAPPY_VILLAGER, front, 6, 0.4, 0.2, 0.4, 0);
                    w.playSound(front, Sound.ITEM_BONE_MEAL_USE, SoundCategory.BLOCKS, 0.6f, 1f);
                }
                if (p.t % 300 == 200 && random.nextInt(3) == 0 && near(l, 12)) say(p, Lines.pick(Lines.GARDEN, random));
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

    // ---- the farmers: harvest what's ripe (and replant), tend what isn't
    void farm(Person p, World w) {
        int x = (int) Math.floor(Halcyon.HX + p.pos[0] + 0.5), z = (int) Math.floor(Halcyon.HZ + p.pos[2] + 0.5);
        for (int tries = 0; tries < 4; tries++) {
            Block b = w.getBlockAt(x + random.nextInt(5) - 2, Halcyon.G, z + (random.nextBoolean() ? 1 : -1));
            if (!(b.getBlockData() instanceof Ageable ag) || !b.getType().name().matches("WHEAT|CARROTS|POTATOES|BEETROOTS")) continue;
            if (ag.getAge() >= ag.getMaximumAge()) {
                w.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.3, 0.5), 10, 0.2, 0.2, 0.2, 0, b.getBlockData());
                w.spawnParticle(Particle.ITEM, b.getLocation().add(0.5, 0.4, 0.5), 4, 0.15, 0.1, 0.15, 0.05, new ItemStack(cropItem(b.getType())));
                ag.setAge(0); b.setBlockData(ag, false);
                w.playSound(b.getLocation(), Sound.BLOCK_CROP_BREAK, SoundCategory.BLOCKS, 0.7f, 1f);
                w.playSound(b.getLocation(), Sound.ITEM_CROP_PLANT, SoundCategory.BLOCKS, 0.6f, 1f);
            } else {
                ag.setAge(Math.min(ag.getMaximumAge(), ag.getAge() + 1)); b.setBlockData(ag, false);
                w.spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 0.4, 0.5), 5, 0.3, 0.2, 0.3, 0);
            }
            face(p, b.getLocation().add(0.5, 0, 0.5));
            return;
        }
    }

    static Material cropItem(Material crop) {
        return switch (crop) { case CARROTS -> Material.CARROT; case POTATOES -> Material.POTATO; case BEETROOTS -> Material.BEETROOT; default -> Material.WHEAT; };
    }

    // ---- the woodcutters: chop, fell the whole tree, plant a sapling, carry the logs
    void chop(Person p, World w, boolean seen) {
        if (p.tree < 0 || p.at == null || !p.at.key().equals("tree" + p.tree)) { p.until = 0; return; }
        int[] t = Halcyon.TREES.get(p.tree);
        Location trunk = new Location(w, Halcyon.HX + t[0], Halcyon.G, Halcyon.HZ + t[1]);
        if (!treeStanding(p.tree)) { treeClaimed.remove(p.tree); p.tree = -1; p.until = 0; return; }
        p.until = Math.max(p.until, p.t + 40);
        if (p.t % 8 == 0) nod(p, 30);
        int hits = 10;
        if (p.t % 12 == 0 && p.t > 0) {
            int hit = p.t / 12;
            float prog = Math.min(0.95f, hit / (float) hits);
            for (Player q : w.getPlayers()) if (q.getLocation().distanceSquared(trunk) < 40 * 40) q.sendBlockDamage(trunk, prog, p.v);
            if (seen) {
                w.playSound(trunk, Sound.BLOCK_WOOD_HIT, SoundCategory.BLOCKS, 1f, 0.8f + random.nextFloat() * 0.2f);
                w.spawnParticle(Particle.BLOCK, trunk.clone().add(0.5, 1, 0.5), 8, 0.2, 0.3, 0.2, 0, trunk.getBlock().getBlockData());
            }
            if (hit >= hits) fell(p, w, trunk);
        }
        if (p.t % 200 == 100 && random.nextInt(3) == 0 && near(trunk, 12)) say(p, Lines.pick(Lines.WOOD, random));
    }

    void fell(Person p, World w, Location trunk) {
        int[] t = Halcyon.TREES.get(p.tree);
        List<Object[]> blocks = Halcyon.treeBlocks(t);
        int n = 0;
        for (int i = blocks.size() - 1; i >= 0; i--) {
            Object[] b = blocks.get(i);
            Block blk = w.getBlockAt(Halcyon.HX + (int) b[0], (int) b[1], Halcyon.HZ + (int) b[2]);
            Material m = blk.getType();
            if (!m.name().endsWith("_LOG") && !m.name().endsWith("_LEAVES")) continue;
            if (n++ % 3 == 0) w.spawnParticle(Particle.BLOCK, blk.getLocation().add(0.5, 0.5, 0.5), m.name().endsWith("_LOG") ? 12 : 4, 0.3, 0.3, 0.3, 0, blk.getBlockData());
            blk.setType(Material.AIR, false);
        }
        for (Player q : w.getPlayers()) if (q.getLocation().distanceSquared(trunk) < 40 * 40) q.sendBlockDamage(trunk, 0f, p.v);
        w.playSound(trunk, Sound.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 1.4f, 0.6f);
        w.playSound(trunk, Sound.BLOCK_AZALEA_LEAVES_BREAK, SoundCategory.BLOCKS, 1.2f, 0.7f);
        w.playSound(trunk, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.BLOCKS, 0.5f, 0.5f);
        trunk.getBlock().setBlockData(Paint.bd(Halcyon.sapling(t)), false);
        w.playSound(trunk, Sound.ITEM_CROP_PLANT, SoundCategory.BLOCKS, 0.8f, 1f);
        say(p, "Timber!");
        treeFelled[p.tree] = System.currentTimeMillis();
        treeClaimed.remove(p.tree);
        p.tree = -1;
        p.carry = 1;
        hold(p, new ItemStack(Material.OAK_LOG));
        p.until = p.t + 30; // then off to the pile
    }

    // ---- the miners: crack an ore face, dig it out (it grows back later)
    void dig(Person p, World w, boolean seen) {
        if (p.t % 5 == 0) nod(p, 40);
        if (p.ore < 0 || p.at == null || !p.at.key().equals("ore" + p.ore)) { if (p.t % 300 == 150 && random.nextInt(3) == 0 && near(p.v.getLocation(), 12)) say(p, Lines.pick(Lines.MINE, random)); return; }
        Block ore = oreBlock(p.ore);
        if (ore.getType() == Material.AIR) { oreClaimed.remove(p.ore); p.ore = -1; p.until = 0; return; }
        p.until = Math.max(p.until, p.t + 40);
        int need = 100;
        float prog = Math.min(0.95f, p.t / (float) need);
        Location ol = ore.getLocation();
        if (p.t % 5 == 0) for (Player q : w.getPlayers()) if (q.getLocation().distanceSquared(ol) < 32 * 32) q.sendBlockDamage(ol, prog, p.v);
        if (p.t % 10 == 0 && seen) {
            w.playSound(ol, Sound.BLOCK_STONE_HIT, SoundCategory.BLOCKS, 0.8f, 0.9f);
            w.spawnParticle(Particle.BLOCK, ol.clone().add(0.5, 0.5, 0.5), 4, 0.3, 0.3, 0.3, 0, ore.getBlockData());
        }
        if (p.t >= need) {
            w.playSound(ol, Sound.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 1f, 0.9f);
            w.spawnParticle(Particle.BLOCK, ol.clone().add(0.5, 0.5, 0.5), 24, 0.3, 0.3, 0.3, 0, ore.getBlockData());
            Material drop = oreDrop(ore.getType());
            w.spawnParticle(Particle.ITEM, ol.clone().add(0.5, 0.5, 0.5), 6, 0.2, 0.2, 0.2, 0.08, new ItemStack(drop));
            for (Player q : w.getPlayers()) if (q.getLocation().distanceSquared(ol) < 32 * 32) q.sendBlockDamage(ol, 0f, p.v);
            ore.setType(Material.AIR, false);
            oreDug[p.ore] = System.currentTimeMillis();
            oreClaimed.remove(p.ore);
            p.ore = -1;
            p.until = p.t + 20;
            if (random.nextInt(3) == 0) say(p, "Got a good one.");
        }
    }

    static Material oreDrop(Material ore) {
        String n = ore.name();
        if (n.contains("IRON")) return Material.RAW_IRON;
        if (n.contains("COPPER")) return Material.RAW_COPPER;
        if (n.contains("GOLD")) return Material.RAW_GOLD;
        if (n.contains("REDSTONE")) return Material.REDSTONE;
        if (n.contains("LAPIS")) return Material.LAPIS_LAZULI;
        if (n.contains("DIAMOND")) return Material.DIAMOND;
        return Material.COAL;
    }

    /** Trees grow back, ore faces fill in, the divers' ore returns. */
    void regrowTick() {
        long now = System.currentTimeMillis();
        long treeMs = (long) (cfg("tree-regrow-minutes", 6) * 60000), oreMs = (long) (cfg("ore-regrow-minutes", 5) * 60000), diveMs = (long) (cfg("dive-ore-regrow-minutes", 10) * 60000);
        for (int i = 0; i < treeFelled.length; i++) if (treeFelled[i] > 0 && now - treeFelled[i] > treeMs && growTree(i)) treeFelled[i] = 0;
        for (int i = 0; i < oreDug.length; i++) if (oreDug[i] > 0 && now - oreDug[i] > oreMs) {
            Block b = oreBlock(i);
            if (!pl.world.isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) continue;
            if (b.getType() == Material.AIR) { b.setType(Material.matchMaterial(Halcyon.oreAt(Halcyon.ORES.get(i))), false); }
            oreDug[i] = 0;
        }
        for (var it = diveDugAt.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            if (now - en.getValue() < diveMs) continue;
            Block b = en.getKey();
            if (b.getType() == Material.WATER) b.setType(diveDug.get(b), false);
            diveDug.remove(b);
            it.remove();
        }
    }

    /** A felled tree grows back (only into air, its sapling, or the leaves it shares): false if someone's standing in it. */
    boolean growTree(int i) {
        int[] t = Halcyon.TREES.get(i);
        World w = pl.world;
        if (!w.isChunkLoaded((Halcyon.HX + t[0]) >> 4, (Halcyon.HZ + t[1]) >> 4)) return false;
        Location base = new Location(w, Halcyon.HX + t[0] + 0.5, Halcyon.G, Halcyon.HZ + t[1] + 0.5);
        for (Entity e : w.getNearbyEntities(base.clone().add(0, 3, 0), 3.5, 4, 3.5)) if (e instanceof LivingEntity) return false;
        for (Object[] b : Halcyon.treeBlocks(t)) {
            Block blk = w.getBlockAt(Halcyon.HX + (int) b[0], (int) b[1], Halcyon.HZ + (int) b[2]);
            Material m = blk.getType();
            if (m.isAir() || m.name().endsWith("_SAPLING") || m == Material.SHORT_GRASS || m.name().endsWith("_LEAVES")) blk.setBlockData(Paint.bd((String) b[3]), false);
        }
        w.spawnParticle(Particle.HAPPY_VILLAGER, base.clone().add(0, 3, 0), 20, 1.5, 2, 1.5, 0);
        return true;
    }

    /** When the city wakes: any tree or ore face missing from before (a restart) comes back. */
    void restoreWork() {
        for (int i = 0; i < Halcyon.TREES.size(); i++) if (treeFelled[i] == 0 && !treeStanding(i)) {
            int[] t = Halcyon.TREES.get(i);
            Material m = pl.world.getBlockAt(Halcyon.HX + t[0], Halcyon.G, Halcyon.HZ + t[1]).getType();
            if (m.isAir() || m.name().endsWith("_SAPLING")) growTree(i);
        }
        for (int i = 0; i < Halcyon.ORES.size(); i++) if (oreDug[i] == 0) {
            Block b = oreBlock(i);
            if (pl.world.isChunkLoaded(b.getX() >> 4, b.getZ() >> 4) && b.getType() == Material.AIR) b.setType(Material.matchMaterial(Halcyon.oreAt(Halcyon.ORES.get(i))), false);
        }
    }

    // =========================================================================================== the divers
    static final NamespacedKey HELMET_MODEL = new NamespacedKey("faultline", "dive_helmet_2");

    /** The flooded tunnel from the moon pool out to the caves (rel, feet heights), worked out once; null without our generator. */
    List<double[]> divePath() {
        if (divePath != null) return divePath.isEmpty() ? null : divePath;
        divePath = new ArrayList<>();
        try {
            if (pl.world.getGenerator() instanceof DeepGenerator g) {
                DeepGenerator.Noise n = g.noiseFor(pl.world.getSeed());
                for (double[] q : Halcyon.divePath(g, n)) divePath.add(new double[]{q[0] - 0.5, q[1] - 0.9, q[2] - 0.5});
            }
        } catch (Throwable t) { pl.getLogger().warning("[Halcyon] couldn't work out the dive tunnel: " + t); }
        return divePath.isEmpty() ? null : divePath;
    }

    /** Ore in the rock round the tunnel's end (what the divers dig). */
    void findDiveOres() {
        diveOres.clear();
        List<double[]> path = divePath();
        if (path == null) return;
        double[] e = path.get(path.size() - 1);
        int ex = Halcyon.HX + (int) Math.floor(e[0] + 0.5), ey = (int) Math.floor(e[1] + 0.9), ez = Halcyon.HZ + (int) Math.floor(e[2] + 0.5);
        if (!pl.world.isChunkLoaded(ex >> 4, ez >> 4)) return;
        for (int dx = -5; dx <= 5; dx++) for (int dy = -4; dy <= 4; dy++) for (int dz = -5; dz <= 5; dz++) {
            if (dx * dx + dy * dy + dz * dz > 22) continue;
            if (!pl.world.isChunkLoaded((ex + dx) >> 4, (ez + dz) >> 4)) continue;
            Block b = pl.world.getBlockAt(ex + dx, ey + dy, ez + dz);
            if (b.getType().name().endsWith("_ORE")) diveOres.add(b);
        }
    }

    void suit(Person p, boolean on) {
        if (p.v.getEquipment() == null) return;
        ItemStack helm = null;
        if (on) {
            helm = new ItemStack(Material.PAPER);
            ItemMeta meta = helm.getItemMeta();
            try { meta.setItemModel(HELMET_MODEL); } catch (Throwable ignored) { }
            helm.setItemMeta(meta);
        }
        p.v.getEquipment().setHelmet(helm);
        try { p.v.getEquipment().setDropChance(EquipmentSlot.HEAD, 0); } catch (RuntimeException ignored) { }
        p.v.getWorld().playSound(p.v.getLocation(), on ? Sound.ITEM_ARMOR_EQUIP_IRON : Sound.ITEM_ARMOR_EQUIP_LEATHER, SoundCategory.NEUTRAL, 0.8f, 0.9f);
    }

    /** In the dive room: suit up, then down the moon pool; back from a dive, change and rest. */
    void diveRoom(Person p) {
        if (p.diveStage == 1) {
            if (p.t == 10) { suit(p, true); say(p, "Helmet sealed."); }
            if (p.t == 50) {
                List<double[]> path = divePath();
                if (path == null) { p.diveStage = 5; p.until = p.t + 100; return; }
                if (diveOres.isEmpty()) findDiveOres();
                p.swim.clear();
                double gy = Halcyon.GY + 1;
                p.swim.add(pt(-92, gy, -56));
                p.swim.add(pt(Halcyon.POOL_X, gy, Halcyon.POOL_Z));
                p.swim.add(pt(Halcyon.POOL_X, Halcyon.POOL_BOTTOM + 0.2, Halcyon.POOL_Z));
                p.swim.addAll(path);
                p.diveStage = 2;
                p.dug = 0;
                p.v.getWorld().playSound(p.v.getLocation(), Sound.ENTITY_PLAYER_SPLASH, SoundCategory.NEUTRAL, 1f, 1f);
            }
            return;
        }
        if (p.diveStage == 5) {
            if (p.t == 20) { suit(p, false); say(p, Lines.pick(Lines.DIVE, random)); }
            if (p.t >= 60) { p.diveStage = 0; p.act = null; }
            return;
        }
        if (p.t % 10 == 0) nod(p, 20);
    }

    void diveTick(Person p) {
        World w = p.v.getWorld();
        Location l = p.v.getLocation();
        if (p.t % 6 == 0 && l.getBlock().getType() == Material.WATER) w.spawnParticle(Particle.BUBBLE, l.clone().add(0, 1.7, 0), 3, 0.15, 0.1, 0.15, 0.02);
        if (p.diveStage == 2 || p.diveStage == 4) {
            double[] next = p.swim.peekFirst();
            if (next == null) {
                if (p.diveStage == 2) { p.diveStage = 3; p.t = 0; p.diveOre = null; }
                else { p.diveStage = 5; p.act = Act.DIVE; p.at = spots.get("dive"); p.t = 0; p.yaw = (float) p.at.face(); p.v.teleport(loc(p.pos, p.yaw)); }
                return;
            }
            if (!pl.world.isChunkLoaded((Halcyon.HX + (int) Math.floor(next[0] + 0.5)) >> 4, (Halcyon.HZ + (int) Math.floor(next[2] + 0.5)) >> 4)) return;
            double dx = next[0] - p.pos[0], dy = next[1] - p.pos[1], dz = next[2] - p.pos[2];
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz), sp = 0.13;
            if (d <= sp) { p.pos = next.clone(); p.swim.pollFirst(); }
            else { p.pos[0] += dx / d * sp; p.pos[1] += dy / d * sp; p.pos[2] += dz / d * sp; }
            if (Math.hypot(dx, dz) > 0.05) p.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) Math.max(-40, Math.min(40, Math.toDegrees(-Math.atan2(dy, Math.max(0.05, Math.hypot(dx, dz))))));
            Location to = loc(p.pos, p.yaw); to.setPitch(pitch);
            p.v.teleport(to);
            return;
        }
        // stage 3: mining at the tunnel's end (every ore in reach, a few of them), hovering and bobbing
        double bob = Math.sin(p.t * 0.08) * 0.15;
        if (p.diveOre == null || p.diveOre.getType() == Material.WATER) {
            p.diveOre = null;
            for (Block b : diveOres) if (b.getType().name().endsWith("_ORE") && b.getLocation().add(0.5, 0.5, 0.5).distanceSquared(l) < 5.2 * 5.2) { p.diveOre = b; break; }
            p.t = 1;
            if (p.diveOre == null || p.dug >= 3) { if (p.t > 0) diveBack(p); return; }
        }
        Location ol = p.diveOre.getLocation();
        face(p, ol.clone().add(0.5, 0.5, 0.5));
        Location me = loc(p.pos, p.yaw); me.add(0, bob, 0);
        p.v.teleport(me);
        int need = 120;
        if (p.t % 6 == 0) for (Player q : w.getPlayers()) if (q.getLocation().distanceSquared(ol) < 32 * 32) q.sendBlockDamage(ol, Math.min(0.95f, p.t / (float) need), p.v);
        if (p.t % 12 == 0) w.playSound(ol, Sound.BLOCK_DEEPSLATE_HIT, SoundCategory.BLOCKS, 0.7f, 0.8f);
        if (p.t >= need) {
            w.spawnParticle(Particle.BLOCK, ol.clone().add(0.5, 0.5, 0.5), 24, 0.3, 0.3, 0.3, 0, p.diveOre.getBlockData());
            w.spawnParticle(Particle.ITEM, ol.clone().add(0.5, 0.5, 0.5), 5, 0.2, 0.2, 0.2, 0.06, new ItemStack(oreDrop(p.diveOre.getType())));
            w.playSound(ol, Sound.BLOCK_DEEPSLATE_BREAK, SoundCategory.BLOCKS, 1f, 0.8f);
            for (Player q : w.getPlayers()) if (q.getLocation().distanceSquared(ol) < 32 * 32) q.sendBlockDamage(ol, 0f, p.v);
            diveDug.put(p.diveOre, p.diveOre.getType());
            diveDugAt.put(p.diveOre, System.currentTimeMillis());
            p.diveOre.setType(Material.WATER, false);
            p.diveOre = null;
            p.dug++;
            p.t = 0;
        }
        if (p.t > 900) diveBack(p); // never down there forever
    }

    void diveBack(Person p) {
        List<double[]> path = divePath();
        p.swim.clear();
        if (path != null) for (int i = path.size() - 1; i >= 0; i--) p.swim.add(path.get(i));
        double gy = Halcyon.GY + 1;
        p.swim.add(pt(Halcyon.POOL_X, Halcyon.POOL_BOTTOM + 0.2, Halcyon.POOL_Z));
        p.swim.add(pt(Halcyon.POOL_X + 1, gy - 1, Halcyon.POOL_Z));
        p.swim.add(pt(Halcyon.POOL_X + 1, gy + 0.2, Halcyon.POOL_Z));
        p.swim.add(pt(-92, gy, -56));
        p.swim.add(pt(-91, gy, -56));
        p.diveStage = 4;
    }

    // =========================================================================================== guards in training
    void trainDummy(Person p) {
        Location l = p.v.getLocation();
        if (p.t % 14 == 0) {
            nod(p, 30);
            Location d = l.clone().add(0, 1.4, -1.5);
            if (near(l, 24)) {
                l.getWorld().playSound(d, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.NEUTRAL, 0.7f, 1f);
                l.getWorld().spawnParticle(Particle.BLOCK, d, 6, 0.2, 0.3, 0.2, 0, Material.HAY_BLOCK.createBlockData());
                if (random.nextInt(3) == 0) l.getWorld().spawnParticle(Particle.SWEEP_ATTACK, d, 1, 0, 0, 0, 0);
            }
        }
        if (p.t % 300 == 100 && random.nextInt(2) == 0 && near(l, 14)) say(p, Lines.pick(Lines.TRAIN, random));
    }

    final List<Arrow> practice = new ArrayList<>();

    void trainRange(Person p) {
        if (p.t % 50 != 25) return;
        Location eye = p.v.getEyeLocation();
        int[] tgt = null;
        for (int[] t : Halcyon.TARGETS) if (Math.abs(Halcyon.HZ + t[1] - eye.getZ()) < 1.5) tgt = t;
        if (tgt == null || !near(eye, 40)) return;
        Vector dir = new Vector(Halcyon.HX + tgt[0] + 0.5, Halcyon.G + 1.5, Halcyon.HZ + tgt[1] + 0.5).subtract(eye.toVector());
        double dist = dir.length();
        dir.normalize().multiply(2.2).add(new Vector(0, dist * 0.004, 0)).add(new Vector((random.nextDouble() - 0.5) * 0.05, 0, (random.nextDouble() - 0.5) * 0.05));
        Arrow a = p.v.launchProjectile(Arrow.class, dir);
        a.setDamage(0);
        a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        a.addScoreboardTag(TAG);
        a.addScoreboardTag(TAG + "_practice");
        practice.add(a);
        eye.getWorld().playSound(eye, Sound.ENTITY_ARROW_SHOOT, SoundCategory.NEUTRAL, 0.8f, 1f);
        Bukkit.getScheduler().runTaskLater(pl, () -> { if (a.isValid()) a.remove(); practice.remove(a); }, 60);
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
        double range = cfg("guard-range", 160);
        if (range == 64) range = 160; // 1.17.x's default: the city has three domes now
        double bd = System.currentTimeMillis() < p.alertUntil ? 1e5 : range;
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
        Location me = p.v.getLocation(), to = prey.getLocation();
        double dist = me.distance(to);
        face(p, to);
        if (p.attackCd > 0) p.attackCd--;
        if (p.shotCd > 0) p.shotCd--;
        if (dist > cfg("guard-street-chase", 16)) { streetChase(p, prey); return; }
        p.route.clear();
        p.offStreets = true;
        if (dist <= cfg("guard-reach", 2.4)) {
            if (city.wanted(prey) == 1 && cfgBool("arrest-at-one-star", true)) { arrest(p, prey); return; }
            if (p.attackCd <= 0) {
                p.attackCd = 18;
                prey.damage(cfg("guard-damage", 6), p.v);
                Vector push = to.toVector().subtract(me.toVector()).setY(0);
                if (push.lengthSquared() > 1e-4) Edition.vel(prey, push.normalize().multiply(0.5).setY(0.35));
                me.getWorld().playSound(me, Sound.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.HOSTILE, 1f, 1f);
            }
            return;
        }
        if (city.wanted(prey) >= 2 && dist > 6 && dist < 20 && p.shotCd <= 0 && p.v.hasLineOfSight(prey)) {
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
            if (!Halcyon.inside(n.getX(), n.getY() + 0.5, n.getZ()) || !n.getWorld().isChunkLoaded(n.getBlockX() >> 4, n.getBlockZ() >> 4)) continue; // never out of the city
            double y = floorAt(n);
            if (Double.isNaN(y)) continue;
            n.setY(y);
            n.setYaw(p.yaw); n.setPitch(0);
            p.v.teleport(n);
            p.pos = new double[]{n.getX() - Halcyon.HX - 0.5, n.getY(), n.getZ() - Halcyon.HZ - 0.5};
            return;
        }
    }

    /** Far off: run the streets (the ring, the hallways, the mine's tunnel) to the spot nearest them, then close in. */
    void streetChase(Person p, Player prey) {
        if (p.offStreets) { // back onto the streets first
            p.offStreets = false;
            double a = angleOf(p.pos[0], p.pos[2]);
            p.route.clear();
            p.route.add(ring(a));
            p.at = new Spot("back", a, List.of(ring(a)), a);
            p.goal = p.at;
        }
        if (p.route.isEmpty()) {
            Spot s = nearestSpot(prey.getLocation());
            if (s != null && s != p.at) { p.route.addAll(route(p.at, s)); p.goal = s; }
            if (p.route.isEmpty()) { p.offStreets = true; return; }
        }
        double[] next = p.route.peekFirst();
        if (!pl.world.isChunkLoaded((Halcyon.HX + (int) Math.floor(next[0] + 0.5)) >> 4, (Halcyon.HZ + (int) Math.floor(next[2] + 0.5)) >> 4)) return;
        double dx = next[0] - p.pos[0], dy = next[1] - p.pos[1], dz = next[2] - p.pos[2];
        double d = Math.sqrt(dx * dx + dz * dz + dy * dy), sp = cfg("guard-run-speed", 0.27);
        if (d <= sp) { p.pos = next.clone(); p.route.pollFirst(); if (p.route.isEmpty()) p.at = p.goal; }
        else { p.pos[0] += dx / d * sp; p.pos[1] += dy / d * sp; p.pos[2] += dz / d * sp; }
        if (Math.hypot(dx, dz) > 0.05) p.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        p.v.teleport(loc(p.pos, p.yaw));
    }

    /** The street spot nearest a place (about the same height). */
    Spot nearestSpot(Location l) {
        Spot best = null;
        double bd = 1e18, rx = l.getX() - Halcyon.HX - 0.5, rz = l.getZ() - Halcyon.HZ - 0.5;
        for (Spot s : spots.values()) {
            double[] e = s.end();
            if (Math.abs(e[1] - l.getY()) > 4) continue;
            double d = (e[0] - rx) * (e[0] - rx) + (e[2] - rz) * (e[2] - rz);
            if (d < bd) { bd = d; best = s; }
        }
        return best;
    }

    /** Wanted 1: caught. Thrown out onto the dock, the charge dropped (a warning: the next one counts). */
    void arrest(Person p, Player prey) {
        say(p, Lines.pick(Lines.ARREST, random));
        Location dock = new Location(pl.world, Halcyon.HX + 0.5, Halcyon.G + 0.2, Halcyon.HZ + 112.5, 180, 0);
        prey.teleport(dock);
        prey.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 2, false, false, true));
        prey.playSound(prey.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, SoundCategory.HOSTILE, 1f, 0.7f);
        prey.sendMessage(ChatColor.RED + "[Halcyon] " + ChatColor.GRAY + "You were arrested by " + p.name + " and thrown out of the city. Behave next time: the guards won't be so gentle.");
        city.clearWanted(prey.getUniqueId());
        if (prey.isGlowing()) prey.removePotionEffect(PotionEffectType.GLOWING);
        p.act = null;
        p.chasing = null;
        for (Person g : people) if (g.job == Job.GUARD && g.chasing != null && g.chasing.equals(prey.getUniqueId())) { g.act = null; g.chasing = null; if (g.alive()) backToStreets(g); }
        backToStreets(p);
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
        if (!p.offStreets && p.at != null) { p.act = Act.PATROL; p.speed = 0.13; if (p.route.isEmpty()) arrived(p); return; } // already on the streets
        p.offStreets = false;
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
        for (Person g : people) if (g.job == Job.GUARD && g.alive() && g.onDuty(time()) && g.act != Act.CHASE && huntable(p)) { g.t = 9; g.alertUntil = System.currentTimeMillis() + 60_000; }
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
        if (p.v.isSleeping() || p.diveStage >= 2 && p.diveStage <= 4) return;
        if (p.free) unloose(p);
        if (p.act != Act.PANIC) say(p, Lines.pick(Lines.PANIC, random));
        p.act = Act.PANIC;
        p.panicUntil = System.currentTimeMillis() + (long) (cfg("panic-seconds", 15) * 1000);
        Spot home = homeSpot(p);
        if (p.at != home && (p.route.isEmpty() || p.at != null && p.at.key().equals("loose"))) { if (!p.at.key().equals("loose")) p.route.clear(); p.route.addAll(route(p.at, home)); p.goal = home; }
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
        if (by.getGameMode() == GameMode.CREATIVE || by.getGameMode() == GameMode.SPECTATOR) { // guards never react to creative mode
            by.sendActionBar(Component.text("Halcyon's guards ignore players in creative mode. Test it in survival.", NamedTextColor.YELLOW));
            return;
        }
        Location l = p.v.getLocation();
        scream(p);
        l.getWorld().spawnParticle(Particle.ANGRY_VILLAGER, l.clone().add(0, 2, 0), 3, 0.3, 0.2, 0.3, 0);
        if (p.job == Job.GUARD) {
            city.crime(by, 2, "assaulting a guard");
            if (p.v.isSleeping()) p.v.wakeup();
            if (p.free) unloose(p);
            p.route.clear();
            if (huntable(by)) chase(p, by);                          // turns on you right away
            for (Person q : people) if (q != p && q.job == Job.GUARD && q.alive() && q.v.getLocation().distanceSquared(l) < 40 * 40) q.t = 9;
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
