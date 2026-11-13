package net.faultlinesmp.deep;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Drowned;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * HALCYON'S EXHIBITS (Deep 1.17.0), drawn only while someone is close:
 *  - THE AQUARIUM: every creature of the Dark Ocean (bar the Drowned City's people, who are people, and the two mini bosses,
 *    who are in the Bio-Center) swims in one of its ten tanks: each is the creature's own model and moving bones
 *    (DeepParts, the same animation as in the wild), swimming slow circles; a sign over each tank names who's in it.
 *    Bedrock players see each one as a stand wearing its model.
 *  - THE BESTIARY on the two lecterns by the door: every creature (what it does, and where it came from:
 *    halcyon_lore.yml), in two volumes. Right-click to read; sneak + right-click to take a copy.
 *  - THE BIO-CENTER: a specimen of every boss, tiny, in its jar: the sea bosses' real rigs from FaultlineBosses (by
 *    reflection: FaultlineBosses.specimen), Grubnash and the Rimeback as creatures, the Drowned King as a little drowned,
 *    Diamond Jacob as a little diamond knight, the Krakens as their mantles; the Abyssal Titan in the great tank. A plaque
 *    on each, and the Research Notes on the lectern (every boss's lore).
 */
final class Exhibits implements Listener {
    static final String TAG = "faultline_halcyon_exhibit";

    final FaultlineDeep pl;
    final HalcyonCity city;
    final YamlConfiguration lore;
    final NamespacedKey bookKey;
    final List<Fish> fish = new ArrayList<>();
    final List<Entity> aquaStuff = new ArrayList<>(), labStuff = new ArrayList<>();
    final List<String> specimenIds = new ArrayList<>();
    boolean aquaOn, labOn;
    long aquaSeen, labSeen;
    int ticks;
    List<ItemStack> volumes;
    ItemStack notes;

    final class Fish {
        final DeepMobs.Kind k;
        final Location anchor;
        final double radius, phase, speed;
        final float scale;
        ItemDisplay body;
        final List<ItemDisplay> parts = new ArrayList<>();
        List<DeepParts.Bone> bones = List.of();
        ArmorStand stand;
        double clock;
        Fish(DeepMobs.Kind k, Location anchor, double radius, double phase, float scale) {
            this.k = k; this.anchor = anchor; this.radius = radius; this.phase = phase; this.scale = scale;
            speed = 0.012 + (phase % 1) * 0.01;
            clock = phase * 3;
        }
    }

    Exhibits(FaultlineDeep pl, HalcyonCity city) {
        this.pl = pl;
        this.city = city;
        bookKey = new NamespacedKey(pl, "halcyon_book");
        YamlConfiguration y = new YamlConfiguration();
        try (var in = pl.getResource("halcyon_lore.yml")) {
            if (in != null) y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception e) { pl.getLogger().warning("[Halcyon] couldn't read halcyon_lore.yml: " + e); }
        lore = y;
    }

    double cfg(String path, double def) { return pl.getConfig().getDouble("halcyon." + path, def); }

    static Location rel(World w, double x, double y, double z) { return new Location(w, Halcyon.HX + x, y, Halcyon.HZ + z); }

    // =========================================================================================== on and off
    void tick() {
        ticks++;
        if (pl.world == null) return;
        if (ticks % 20 == 0) {
            long now = System.currentTimeMillis();
            Location aq = rel(pl.world, 70, Halcyon.G, 0), lab = rel(pl.world, 0, Halcyon.G, -70);
            boolean nearAq = false, nearLab = false;
            for (Player p : pl.world.getPlayers()) {
                Location l = p.getLocation();
                if (l.distanceSquared(aq) < 70 * 70) nearAq = true;
                if (l.distanceSquared(lab) < 60 * 60) nearLab = true;
            }
            if (nearAq) aquaSeen = now;
            if (nearLab) labSeen = now;
            if (nearAq && !aquaOn && pl.world.isChunkLoaded((Halcyon.HX + 70) >> 4, Halcyon.HZ >> 4)) aquaOn();
            if (aquaOn && now - aquaSeen > 30_000) aquaOff();
            if (nearLab && !labOn && pl.world.isChunkLoaded(Halcyon.HX >> 4, (Halcyon.HZ - 70) >> 4)) labOn();
            if (labOn && now - labSeen > 30_000) labOff();
            if (ticks % 40 == 0) for (Fish f : fish) if (f.stand != null && f.stand.isValid())
                for (Player p : pl.world.getPlayers()) if (Edition.bedrock(p) && !p.canSee(f.stand)) p.showEntity(pl, f.stand);
        }
        if (aquaOn) swim(fish, rel(pl.world, 70, Halcyon.G, 0));
        if (labOn) { swim(labFish, rel(pl.world, 0, Halcyon.G, -70)); labTick(); }
    }

    void removeAll() { aquaOff(); labOff(); }

    // =========================================================================================== the Aquarium
    static List<DeepMobs.Kind> aquariumKinds() {
        List<DeepMobs.Kind> l = new ArrayList<>();
        for (DeepMobs.Kind k : DeepMobs.KINDS.values()) {
            if (DeepMobs.PLAIN.contains(k.id()) || k.id().equals("goblin") || k.id().equals("rimeback")) continue;
            l.add(k);
        }
        return l;
    }

    void aquaOn() {
        aquaOn = true;
        World w = pl.world;
        List<DeepMobs.Kind> kinds = aquariumKinds();
        int tanks = Halcyon.TANKS.length, per = (int) Math.ceil(kinds.size() / (double) tanks);
        for (int t = 0; t < tanks; t++) {
            int[] b = Halcyon.TANKS[t];
            List<DeepMobs.Kind> in = kinds.subList(Math.min(kinds.size(), t * per), Math.min(kinds.size(), (t + 1) * per));
            for (int i = 0; i < in.size(); i++) {
                double fx = (i % 3 + 0.5) / 3, fy = ((i / 3) % 3 + 0.5) / 3, fz = i % 2 == 0 ? 0.38 : 0.62;
                double x = b[0] + fx * (b[3] - b[0] + 1), y = b[1] + 0.6 + fy * (b[4] - b[1] - 0.6), z = b[2] + fz * (b[5] - b[2] + 1);
                DeepMobs.Kind k = in.get(i);
                float s = (float) (Math.min(k.modelScale(), cfg("aquarium.max-scale", 1.5)) * cfg("aquarium.scale", 0.85));
                Fish f = new Fish(k, rel(w, x, y, z), 0.5 + (i % 3) * 0.15, t * 1.7 + i * 0.83, s);
                spawnFish(f, aquaStuff);
                fish.add(f);
            }
            // the tank's sign: who's in it
            boolean north = b[2] < 0 && b[5] < -10, south = b[2] > 10, island = !north && !south;
            double sx = (b[0] + b[3]) / 2.0 + 0.5, sz = north ? b[5] + 1.6 : south ? b[2] - 0.6 : (b[5] + 1.6);
            StringBuilder names = new StringBuilder();
            for (DeepMobs.Kind k : in) names.append(names.length() == 0 ? "" : "\n").append(k.name());
            String zones = zonesOf(in);
            Location at = rel(w, sx, b[4] + 1.6, sz);
            int tankNo = t + 1;
            aquaStuff.add(w.spawn(at, TextDisplay.class, d -> {
                d.text(Component.text("Tank " + tankNo, NamedTextColor.AQUA, TextDecoration.BOLD)
                        .append(Component.text(" · " + zones, NamedTextColor.GRAY)).append(Component.newline())
                        .append(Component.text(names.toString(), NamedTextColor.WHITE)));
                d.setBillboard(Display.Billboard.VERTICAL);
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
                d.setLineWidth(220);
                d.setBackgroundColor(org.bukkit.Color.fromARGB(140, 0, 20, 40));
                Transformation tr = d.getTransformation();
                d.setTransformation(new Transformation(tr.getTranslation(), tr.getLeftRotation(), new Vector3f(0.6f, 0.6f, 0.6f), tr.getRightRotation()));
            }));
        }
        Location curator = rel(w, 55.5, Halcyon.G + 3.2, 0.5);
        aquaStuff.add(w.spawn(curator, TextDisplay.class, d -> {
            d.text(Component.text("THE HALCYON AQUARIUM", NamedTextColor.AQUA, TextDecoration.BOLD).append(Component.newline())
                    .append(Component.text("Every creature of the Dark Ocean.", NamedTextColor.GRAY)).append(Component.newline())
                    .append(Component.text("The Bestiary is on the lecterns.", NamedTextColor.DARK_AQUA)));
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
        }));
    }

    static String zonesOf(List<DeepMobs.Kind> in) {
        java.util.LinkedHashSet<String> z = new java.util.LinkedHashSet<>();
        for (DeepMobs.Kind k : in) z.add(zoneName(k));
        return String.join(", ", z);
    }

    static String zoneName(DeepMobs.Kind k) {
        if (k.id().startsWith("mer")) return "the Mermaid Den";
        return switch (k.zone()) {
            case SUNLIT -> "the Sunlit Sea";
            case CAVES -> "the Crust's caves";
            case ABYSS -> "the Abyss";
            case SULPHUR -> "the Sulphur Sea";
            case TRENCH -> "the Trench";
            default -> k.zone().name().toLowerCase();
        };
    }

    void spawnFish(Fish f, List<Entity> into) {
        World w = f.anchor.getWorld();
        Location flat = f.anchor.clone(); flat.setYaw(0); flat.setPitch(0);
        f.body = display(flat, DeepMobs.modelItem(f.k.id()), f.k);
        f.bones = DeepParts.BONES.getOrDefault(f.k.id(), List.of());
        for (DeepParts.Bone b : f.bones) f.parts.add(display(flat, DeepMobs.modelItem(f.k.id() + "__" + b.name()), f.k));
        f.stand = w.spawn(f.anchor, ArmorStand.class, a -> {
            a.setVisibleByDefault(false);
            a.setPersistent(false);
            a.setInvisible(true);
            a.setGravity(false);
            a.setSilent(true);
            a.setBasePlate(false);
            a.setMarker(false);
            a.setInvulnerable(true);
            a.addScoreboardTag(TAG);
            a.getEquipment().setHelmet(DeepMobs.modelItem(f.k.id()));
        });
        DeepMobs.attr(f.stand, Attribute.SCALE, Math.max(0.0625, Math.min(16, f.scale * 0.8)));
        for (Player p : w.getPlayers()) if (Edition.bedrock(p)) p.showEntity(pl, f.stand);
        into.add(f.body); into.addAll(f.parts); into.add(f.stand);
    }

    ItemDisplay display(Location l, ItemStack item, DeepMobs.Kind k) {
        return l.getWorld().spawn(l, ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.addScoreboardTag("faultline_no_bedrock_fx");
            d.setInterpolationDuration(4);
            d.setViewRange(1f);
            d.setShadowRadius(0);
            boolean glow = DeepMobs.GLOW.contains(k.id());
            d.setBrightness(new Display.Brightness(glow ? 15 : 12, glow ? 15 : 12));
        });
    }

    /** Every 4 ticks (every 10 with nobody within 24): each one swims its little circle, its bones moving. */
    void swim(List<Fish> fish, Location aq) {
        if (ticks % 4 != 0 || fish.isEmpty()) return;
        boolean close = false;
        for (Player p : pl.world.getPlayers()) if (p.getLocation().distanceSquared(aq) < 30 * 30) close = true;
        if (!close && ticks % 20 != 0) return;
        int gap = close ? 4 : 20;
        for (Fish f : fish) {
            if (f.body == null || !f.body.isValid()) continue;
            double a = f.phase + ticks * f.speed;
            boolean upright = DeepMobs.UPRIGHT.contains(f.k.id());
            double r = upright ? f.radius * 0.3 : f.radius;
            Vector3f off = new Vector3f((float) (Math.cos(a) * r), (float) (Math.sin(a * 0.7) * 0.2), (float) (Math.sin(a) * r));
            float yaw = upright ? (float) (a * 10 % 360) : (float) Math.toDegrees(Math.atan2(-(-Math.sin(a)), Math.cos(a))) ;
            f.clock += gap / 20.0;
            Quaternionf frame = new Quaternionf().rotationY((float) Math.toRadians(-yaw)).rotateY((float) Math.toRadians(-90));
            Quaternionf flip = new Quaternionf().rotationY((float) Math.PI);
            List<DeepParts.Anim> anims = DeepParts.ANIMS.getOrDefault(f.k.id(), List.of());
            Quaternionf bodyRot = new Quaternionf(); Vector3f bodyPos = new Vector3f();
            DeepMobs.animate(anims, "body", f.clock, 1f, bodyRot, bodyPos);
            float[] bp = DeepParts.BODY_PIVOT.getOrDefault(f.k.id(), new float[]{0, 0, 0});
            Vector3f pivot = new Vector3f(bp[0], bp[1], bp[2]);
            Vector3f rootOff = new Vector3f(pivot).negate();
            bodyRot.transform(rootOff).add(pivot).add(bodyPos);
            place(f.body, frame, rootOff, new Quaternionf(bodyRot), f.scale, off, flip, gap);
            for (int i = 0; i < f.parts.size() && i < f.bones.size(); i++) {
                DeepParts.Bone b = f.bones.get(i);
                Quaternionf rot = new Quaternionf(); Vector3f o = new Vector3f();
                DeepMobs.animate(anims, b.name(), f.clock, 1f, rot, o);
                Vector3f at = new Vector3f(b.x(), b.y(), b.z()).add(o).sub(pivot);
                bodyRot.transform(at).add(pivot).add(bodyPos);
                place(f.parts.get(i), frame, at, new Quaternionf(bodyRot).mul(rot), f.scale, off, flip, gap);
            }
            if (f.stand != null && f.stand.isValid() && ticks % 20 == 0) {
                Location sl = f.anchor.clone().add(off.x, off.y - 0.6, off.z); sl.setYaw(yaw);
                f.stand.teleport(sl);
            }
        }
    }

    static void place(ItemDisplay d, Quaternionf frame, Vector3f local, Quaternionf rot, float s, Vector3f off, Quaternionf flip, int gap) {
        if (d == null || !d.isValid()) return;
        Vector3f t = new Vector3f(local).mul(s);
        frame.transform(t);
        t.add(off);
        Quaternionf q = new Quaternionf(frame).mul(rot).mul(flip);
        if (!Float.isFinite(t.x + t.y + t.z + q.x + q.y + q.z + q.w)) return;
        d.setInterpolationDelay(0);
        if (d.getInterpolationDuration() != gap) d.setInterpolationDuration(gap);
        d.setTransformation(new Transformation(t, q, new Vector3f(s, s, s), new Quaternionf()));
    }

    void aquaOff() {
        aquaOn = false;
        for (Entity e : aquaStuff) if (e != null && e.isValid()) e.remove();
        aquaStuff.clear();
        fish.clear();
    }

    // =========================================================================================== the Bio-Center
    static final java.util.Set<String> RIGS = java.util.Set.of("scourge", "umbral", "coralia", "inkjaw", "grandmaw", "lament", "titan");

    void labOn() {
        labOn = true;
        World w = pl.world;
        for (int i = 0; i < Halcyon.JARS.length && i < Halcyon.JAR_KINDS.length; i++) {
            int[] j = Halcyon.JARS[i];
            String kind = Halcyon.JAR_KINDS[i];
            Location c = rel(w, j[0] + 0.5, (Halcyon.JAR_Y0 + Halcyon.JAR_Y1 + 1) / 2.0, j[1] + 0.5);
            specimen(kind, c);
            // the plaque, in front of the jar
            double px = Math.abs(j[0]) == 17 ? j[0] - Math.signum(j[0]) * 3.0 + 0.5 : j[0] + 0.5, pz = Math.abs(j[0]) == 17 ? j[1] + 0.5 : j[1] + 3.6;
            plaque(rel(w, px, Halcyon.JAR_Y1 + 1.8, pz), kind);
        }
        specimen("titan", rel(w, Halcyon.TANK_X + 0.5, Halcyon.G + 1.2, Halcyon.TANK_Z + 0.5));
        plaque(rel(w, Halcyon.TANK_X + 0.5, Halcyon.G + 12.6, Halcyon.TANK_Z + 0.5), "titan");
        labStuff.add(w.spawn(rel(w, 0.5, Halcyon.G + 6.2, Halcyon.LAB_Z1 + 0.6), TextDisplay.class, d -> {
            d.text(Component.text("HALCYON BIO-CENTER", NamedTextColor.GREEN, TextDecoration.BOLD).append(Component.newline())
                    .append(Component.text("Specimens of the deep. Do not tap the glass.", NamedTextColor.GRAY)));
            d.setBillboard(Display.Billboard.FIXED);
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setRotation(0, 0);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(160, 0, 30, 10));
            Transformation tr = d.getTransformation();
            d.setTransformation(new Transformation(tr.getTranslation(), tr.getLeftRotation(), new Vector3f(1.6f, 1.6f, 1.6f), tr.getRightRotation()));
        }));
    }

    void plaque(Location at, String kind) {
        String name = lore.getString("bosses." + kind + ".name", kind), text = lore.getString("bosses." + kind + ".plaque", "");
        labStuff.add(at.getWorld().spawn(at, TextDisplay.class, d -> {
            d.text(Component.text(name, NamedTextColor.GREEN, TextDecoration.BOLD).append(Component.newline()).append(Component.text(text, NamedTextColor.GRAY)));
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setLineWidth(160);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(150, 0, 25, 10));
            Transformation tr = d.getTransformation();
            d.setTransformation(new Transformation(tr.getTranslation(), tr.getLeftRotation(), new Vector3f(0.55f, 0.55f, 0.55f), tr.getRightRotation()));
        }));
    }

    void specimen(String kind, Location c) {
        World w = c.getWorld();
        double scale = cfg("specimens." + kind + ".scale", switch (kind) {
            case "scourge", "umbral" -> 0.12; case "lament" -> 0.2; case "coralia" -> 0.3; case "inkjaw" -> 0.3; case "grandmaw" -> 0.26; case "titan" -> 0.16;
            case "goblin" -> 0.5; case "rimeback" -> 0.2; default -> 0.5; });
        double radius = cfg("specimens." + kind + ".radius", 0.7);
        Location at = c.clone().add(0, cfg("specimens." + kind + ".y", 0), 0);
        if (RIGS.contains(kind)) {
            String id = bossSpecimen(kind, at, scale, radius);
            if (id != null) { specimenIds.add(id); return; }
            if (kind.equals("titan")) return; // FaultlineBosses isn't here: an empty tank
        }
        switch (kind) {
            case "goblin", "rimeback" -> {
                DeepMobs.Kind k = DeepMobs.KINDS.get(kind);
                if (k == null) return;
                Fish f = new Fish(k, at, kind.equals("rimeback") ? 0.4 : 0.0, 0, (float) scale);
                spawnFish(f, labStuff);
                labFish.add(f);
            }
            case "king" -> {
                Entity e = w.spawnEntity(at.clone().add(0, -1, 0), EntityType.DROWNED, false);
                if (e instanceof Drowned d) {
                    d.setAI(false); d.setSilent(true); d.setInvulnerable(true); d.setGravity(false); d.setPersistent(false); d.setRemoveWhenFarAway(false);
                    d.addScoreboardTag(TAG);
                    DeepMobs.attr(d, Attribute.SCALE, scale);
                    d.getEquipment().setHelmet(new ItemStack(Material.GOLDEN_HELMET));
                    d.getEquipment().setItemInMainHand(new ItemStack(Material.TRIDENT));
                    d.getEquipment().setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
                    for (EquipmentSlot s : EquipmentSlot.values()) try { d.getEquipment().setDropChance(s, 0); } catch (RuntimeException ignored) { }
                    d.setCustomName(ChatColor.GOLD + "The Drowned King (specimen)");
                    d.setCustomNameVisible(false);
                    labStuff.add(d);
                } else e.remove();
            }
            case "jacob" -> labStuff.add(w.spawn(at.clone().add(0, -1.0, 0), ArmorStand.class, a -> {
                a.setSmall(true); a.setArms(true); a.setBasePlate(false); a.setGravity(false); a.setInvulnerable(true); a.setPersistent(false);
                a.addScoreboardTag(TAG);
                a.getEquipment().setHelmet(new ItemStack(Material.DIAMOND_HELMET));
                a.getEquipment().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
                a.getEquipment().setLeggings(new ItemStack(Material.DIAMOND_LEGGINGS));
                a.getEquipment().setBoots(new ItemStack(Material.DIAMOND_BOOTS));
                ItemStack hammer = new ItemStack(Material.STICK);
                ItemMeta m = hammer.getItemMeta();
                m.setItemModel(NamespacedKey.fromString("faultline:jacob_hammer"));
                hammer.setItemMeta(m);
                a.getEquipment().setItemInMainHand(hammer);
                a.setRightArmPose(new org.bukkit.util.EulerAngle(Math.toRadians(-100), 0, 0));
                for (EquipmentSlot s : EquipmentSlot.values()) try { a.addEquipmentLock(s, ArmorStand.LockType.REMOVING_OR_CHANGING); } catch (RuntimeException ignored) { }
            }));
            case "kraken", "abyssal" -> {
                boolean big = kind.equals("abyssal");
                ItemStack mantle = new ItemStack(Material.PAPER);
                ItemMeta m = mantle.getItemMeta();
                m.setItemModel(NamespacedKey.fromString("faultline:kraken_mantle_2"));
                mantle.setItemMeta(m);
                float s = (float) (big ? scale * 1.2 : scale);
                labStuff.add(w.spawn(at, ItemDisplay.class, d -> {
                    d.setItemStack(mantle);
                    d.setPersistent(false);
                    d.addScoreboardTag(TAG);
                    d.addScoreboardTag("faultline_no_bedrock_fx");
                    d.setInterpolationDuration(20);
                    d.setBrightness(new Display.Brightness(big ? 6 : 12, big ? 6 : 12));
                    d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
                    if (big) { d.setGlowColorOverride(org.bukkit.Color.fromRGB(80, 0, 120)); d.setGlowing(true); }
                }));
            }
            default -> { }
        }
    }

    final List<Fish> labFish = new ArrayList<>();

    String bossSpecimen(String kind, Location at, double scale, double radius) {
        try {
            var b = Bukkit.getPluginManager().getPlugin("FaultlineBosses");
            if (b == null || !b.isEnabled()) return null;
            Class<?> c = Class.forName("net.faultlinesmp.bosses.FaultlineBosses", true, b.getClass().getClassLoader());
            return (String) c.getMethod("specimen", String.class, Location.class, double.class, double.class).invoke(null, kind, at, scale, radius);
        } catch (Throwable e) { return null; }
    }

    void removeSpecimen(String id) {
        try {
            var b = Bukkit.getPluginManager().getPlugin("FaultlineBosses");
            if (b == null || !b.isEnabled()) return;
            Class<?> c = Class.forName("net.faultlinesmp.bosses.FaultlineBosses", true, b.getClass().getClassLoader());
            c.getMethod("removeSpecimen", String.class).invoke(null, id);
        } catch (Throwable ignored) { }
    }

    void labOff() {
        labOn = false;
        for (String id : specimenIds) removeSpecimen(id);
        specimenIds.clear();
        for (Entity e : labStuff) if (e != null && e.isValid()) e.remove();
        labStuff.clear();
        labFish.clear();
    }

    /** The lab's creature specimens (Grubnash, the Rimeback) and the Krakens turn slowly. */
    void labTick() {
        if (!labOn || ticks % 4 != 0) return;
        for (Entity e : labStuff) if (e instanceof ItemDisplay d && !d.getScoreboardTags().contains("faultline_no_turn") && d.getItemStack() != null
                && d.getItemStack().hasItemMeta() && d.getItemStack().getItemMeta().hasItemModel() && d.getItemStack().getItemMeta().getItemModel().getKey().startsWith("kraken")) {
            Transformation tr = d.getTransformation();
            Quaternionf q = new Quaternionf().rotationY((float) Math.toRadians(ticks * 1.5 % 360));
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(4);
            d.setTransformation(new Transformation(new Vector3f(0, (float) (Math.sin(ticks * 0.05) * 0.1), 0), q, tr.getScale(), new Quaternionf()));
        }
    }

    // =========================================================================================== the books
    List<ItemStack> bestiary() {
        if (volumes != null) return volumes;
        List<Component> pages = new ArrayList<>();
        List<String> order = new ArrayList<>(DeepMobs.KINDS.keySet());
        for (String id : order) {
            DeepMobs.Kind k = DeepMobs.KINDS.get(id);
            String temper = switch (k.temper()) { case PASSIVE -> "harmless"; case NEUTRAL -> "leave it be"; case AGGRESSIVE -> "dangerous"; };
            pages.add(Component.text(k.name(), NamedTextColor.DARK_AQUA, TextDecoration.BOLD).append(Component.newline())
                    .append(Component.text(zoneName(k) + " · " + temper, NamedTextColor.DARK_GRAY)).append(Component.newline()).append(Component.newline())
                    .append(Component.text(k.about(), NamedTextColor.BLACK)));
            pages.add(Component.text(k.name(), NamedTextColor.DARK_AQUA, TextDecoration.BOLD).append(Component.newline()).append(Component.newline())
                    .append(Component.text("Where it came from:", NamedTextColor.DARK_GREEN)).append(Component.newline())
                    .append(Component.text(lore.getString("origins." + id, "Nobody knows. Yet."), NamedTextColor.BLACK)));
        }
        Component intro = Component.text(lore.getString("intro.bestiary", "THE HALCYON BESTIARY"), NamedTextColor.BLACK);
        List<ItemStack> out = new ArrayList<>();
        int perVol = 96, vols = (int) Math.ceil(pages.size() / (double) perVol);
        for (int v = 0; v < vols; v++) {
            List<Component> pp = new ArrayList<>();
            pp.add(intro.append(Component.newline()).append(Component.newline()).append(Component.text("Volume " + roman(v + 1) + " of " + roman(vols), NamedTextColor.DARK_GRAY)));
            pp.addAll(pages.subList(v * perVol, Math.min(pages.size(), (v + 1) * perVol)));
            out.add(book("The Halcyon Bestiary, Vol. " + roman(v + 1), "The Curators of Halcyon", pp, "bestiary_" + (v + 1)));
        }
        return volumes = out;
    }

    static String roman(int n) { return n == 1 ? "I" : n == 2 ? "II" : n == 3 ? "III" : String.valueOf(n); }

    ItemStack notes() {
        if (notes != null) return notes;
        List<Component> pp = new ArrayList<>();
        pp.add(Component.text(lore.getString("intro.notes", "BIO-CENTER RESEARCH NOTES"), NamedTextColor.BLACK));
        var sec = lore.getConfigurationSection("bosses");
        if (sec != null) for (String k : sec.getKeys(false)) {
            String text = lore.getString("bosses." + k + ".notes", "");
            for (String chunk : split(text, 230)) pp.add(Component.text(chunk, NamedTextColor.BLACK));
        }
        return notes = book("Bio-Center Research Notes", "Halcyon Bio-Center", pp, "notes");
    }

    /** Splits text into book pages on paragraph / word breaks. */
    static List<String> split(String text, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String para : text.split("\n\n")) {
            if (cur.length() > 0 && cur.length() + para.length() + 2 > max) { out.add(cur.toString()); cur.setLength(0); }
            if (para.length() > max) {
                for (String word : para.split(" ")) {
                    if (cur.length() + word.length() + 1 > max) { out.add(cur.toString()); cur.setLength(0); }
                    cur.append(cur.length() == 0 ? "" : " ").append(word);
                }
            } else cur.append(cur.length() == 0 ? "" : "\n\n").append(para);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    ItemStack book(String title, String author, List<Component> pages, String id) {
        ItemStack it = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta m = (BookMeta) it.getItemMeta();
        m.setTitle(title.length() > 32 ? title.substring(0, 32) : title);
        m.setAuthor(author);
        // (not BookMeta#pages: it returns a different type on 1.21.11 and 26.2, so a jar built on one breaks on the other)
        List<String> legacy = new ArrayList<>();
        for (Component c : pages) legacy.add(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection().serialize(c));
        m.setPages(legacy);
        m.getPersistentDataContainer().set(bookKey, PersistentDataType.STRING, id);
        it.setItemMeta(m);
        return it;
    }

    /** The lecterns: read (right-click) or take a copy (sneak + right-click). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLectern(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || b.getType() != Material.LECTERN) return;
        if (pl.world == null || !b.getWorld().equals(pl.world)) return;
        int dx = b.getX() - Halcyon.HX, dz = b.getZ() - Halcyon.HZ;
        ItemStack book = null;
        int[] lab = Halcyon.labLectern();
        if (b.getX() == lab[0] && b.getZ() == lab[1] && b.getY() == Halcyon.G) book = notes();
        for (int i = 0; i < 2; i++) { int[] a = Halcyon.aquaLecternRel(i); if (dx == a[0] && dz == a[1] && b.getY() == Halcyon.G) { List<ItemStack> v = bestiary(); book = v.get(Math.min(i, v.size() - 1)); } }
        if (book == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (p.isSneaking()) {
            p.getInventory().addItem(book.clone()).values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
            p.sendActionBar(Component.text("You took a copy of " + ((BookMeta) book.getItemMeta()).getTitle() + ".", NamedTextColor.AQUA));
            pl.console("index discover " + p.getName() + " halcyon_bestiary");
        } else p.openBook(book);
    }

    /** Nothing hurts the exhibits (the Drowned King's specimen is a real drowned; the stands are real stands). */
    @EventHandler(ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) { if (e.getEntity().getScoreboardTags().contains(TAG)) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onStand(org.bukkit.event.player.PlayerArmorStandManipulateEvent e) { if (e.getRightClicked().getScoreboardTags().contains(TAG)) e.setCancelled(true); }
}
