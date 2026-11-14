package net.faultlinesmp.events;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PiglinBarterEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 2.1.0: DAY AND NIGHT in the Nether and the End. Each of those worlds has its own clock (it only runs while someone is
 * there): {@code day-minutes} of day, then {@code night-minutes} of NIGHT. Night: more of that dimension's monsters come
 * for you, and one of its night events may start (the Nether: EMBER STORM or SOUL TIDE; the End: VOID STORM or STARFALL).
 * Dawn ends it. Sleeping through it needs a Blue Obsidian Bed (FaultlineItems): once {@code sleep-percent} of the
 * survival players there are asleep for a few seconds, the night passes.
 * The world's PDC carries {@code faultlineevents:realm_night} (0 / 1), {@code realm_event} and {@code realm_clock}.
 */
final class Realms implements Listener {

    static final NamespacedKey NIGHT = NamespacedKey.fromString("faultlineevents:realm_night");
    static final NamespacedKey EVENT = NamespacedKey.fromString("faultlineevents:realm_event");
    static final NamespacedKey CLOCK = NamespacedKey.fromString("faultlineevents:realm_clock");
    static final String MOB_TAG = "realm_mob", PICKUP_TAG = "faultline_realm_pickup", REST = "faultline_blue_bed_rest";
    static final List<String> NETHER_EVENTS = List.of("ember-storm", "soul-tide"), END_EVENTS = List.of("void-storm", "starfall");

    private final FaultlineEvents pl;
    private final Map<String, Realm> realms = new HashMap<>();

    Realms(FaultlineEvents pl) { this.pl = pl; }

    final class Realm {
        final World w;
        final boolean nether;
        int clock;            // seconds into the cycle
        boolean night;
        String event;         // null = a quiet night
        int asleepFor;
        BossBar bar;
        final Set<UUID> mobs = new HashSet<>();
        final List<Pickup> pickups = new ArrayList<>();
        final Map<UUID, Integer> there = new HashMap<>(); // seconds of this night each player was here

        Realm(World w) {
            this.w = w;
            nether = w.getEnvironment() == World.Environment.NETHER;
            PersistentDataContainer pdc = w.getPersistentDataContainer();
            Integer c = pdc.get(CLOCK, PersistentDataType.INTEGER);
            clock = c == null ? 0 : c;
            Byte n = pdc.get(NIGHT, PersistentDataType.BYTE);
            night = n != null && n != 0;
            event = night ? pdc.get(EVENT, PersistentDataType.STRING) : null;
            if (n == null) pdc.set(NIGHT, PersistentDataType.BYTE, (byte) 0);
        }

        String key() { return nether ? "nether" : "end"; }
        String name() { return nether ? "Nether" : "End"; }
        ChatColor color() { return nether ? ChatColor.RED : ChatColor.LIGHT_PURPLE; }
        int dayLen() { return Math.max(30, (int) (cfgD(key() + ".day-minutes", 12) * 60)); }
        int nightLen() { return Math.max(30, (int) (cfgD(key() + ".night-minutes", 8) * 60)); }
        List<Player> players() { return w.getPlayers(); }

        void save() {
            PersistentDataContainer pdc = w.getPersistentDataContainer();
            pdc.set(CLOCK, PersistentDataType.INTEGER, clock);
            pdc.set(NIGHT, PersistentDataType.BYTE, (byte) (night ? 1 : 0));
            if (event != null) pdc.set(EVENT, PersistentDataType.STRING, event); else pdc.remove(EVENT);
        }
    }

    record Pickup(BlockDisplay d, String kind, long until) { }

    private double cfgD(String path, double def) { return pl.getConfig().getDouble("realms." + path, def); }
    private int cfgI(String path, int def) { return pl.getConfig().getInt("realms." + path, def); }
    private boolean cfgB(String path, boolean def) { return pl.getConfig().getBoolean("realms." + path, def); }

    private boolean realmWorld(World w) {
        if (w.getEnvironment() != World.Environment.NETHER && w.getEnvironment() != World.Environment.THE_END) return false;
        return !pl.getConfig().getStringList("realms.skip-worlds").contains(w.getName());
    }

    Realm realm(World w) {
        if (w == null || !realmWorld(w)) return null;
        return realms.computeIfAbsent(w.getName(), k -> new Realm(w));
    }

    private Realm at(Entity e) { Realm r = realms.get(e.getWorld().getName()); return r != null && r.night ? r : null; }
    private boolean event(Entity e, String id) { Realm r = at(e); return r != null && id.equals(r.event); }

    static String title(String ev) {
        return switch (ev) { case "ember-storm" -> "Ember Storm"; case "soul-tide" -> "Soul Tide"; case "void-storm" -> "Void Storm"; case "starfall" -> "Starfall"; default -> ev; };
    }

    private static ChatColor evColor(String ev) {
        return switch (ev) { case "ember-storm" -> ChatColor.GOLD; case "soul-tide" -> ChatColor.AQUA; case "void-storm" -> ChatColor.DARK_PURPLE; default -> ChatColor.YELLOW; };
    }

    private static String blurb(String ev) {
        return switch (ev) {
            case "ember-storm" -> "embers scorch anyone in the open (Fire Resistance or anything over your head keeps you safe; nothing is set alight), the Nether's monsters are stronger and drop more.";
            case "soul-tide" -> "piglins give double when you barter, quartz and gold ore drop more, wither skeletons drop more skulls, and lost souls drift by: catch them.";
            case "void-storm" -> "endermen hunt you without being looked at and hit harder. They drop twice the pearls.";
            default -> "stars fall on the islands: reach one before it fades. More XP, and your elytra doesn't wear out.";
        };
    }

    // ---------------------------------------------------------------- the clock

    void second() {
        if (!cfgB("enabled", true)) { for (Realm r : new ArrayList<>(realms.values())) if (r.night) dawn(r, false, false); return; }
        for (World w : Bukkit.getWorlds()) {
            Realm r = realm(w);
            if (r == null) continue;
            try { second(r); } catch (RuntimeException e) { pl.problem("realm " + r.key(), e); }
        }
    }

    private void second(Realm r) {
        List<Player> here = r.players();
        if (here.isEmpty()) { if (r.bar != null) r.bar.removeAll(); return; } // the clock stands still with nobody there
        r.clock++;
        if (!r.night && r.clock >= r.dayLen()) dusk(r, null, true);
        else if (r.night && r.clock >= r.dayLen() + r.nightLen()) { dawn(r, true, false); return; }
        if (r.clock % 30 == 0) r.save();
        if (!r.night) { if (r.bar != null) { r.bar.removeAll(); r.bar = null; } return; }
        for (Player p : here) if (FaultlineEvents.survival(p)) r.there.merge(p.getUniqueId(), 1, Integer::sum);
        // sleepers
        int survivors = 0, asleep = 0;
        for (Player p : here) {
            if (!FaultlineEvents.survival(p)) continue;
            survivors++;
            if (p.isSleeping() || p.hasMetadata(REST)) asleep++;
        }
        int need = survivors == 0 ? 0 : Math.max(1, (int) Math.ceil(survivors * cfgD("sleep-percent", 0.5)));
        if (need > 0 && asleep >= need) {
            if (++r.asleepFor >= cfgI("sleep-seconds", 5)) { dawn(r, true, true); return; }
        } else r.asleepFor = 0;
        nightSecond(r, here);
        bar(r, here, asleep, need);
    }

    private void bar(Realm r, List<Player> here, int asleep, int need) {
        if (!pl.showBar()) {
            if (r.bar != null) { r.bar.removeAll(); r.bar = null; }
            if (asleep > 0) for (Player p : here) if (p.isSleeping() || p.hasMetadata(REST))
                p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, net.md_5.bungee.api.chat.TextComponent.fromLegacyText(ChatColor.AQUA + "" + asleep + "/" + need + " asleep"));
            return;
        }
        if (r.bar == null) r.bar = Bukkit.createBossBar("", r.nether ? BarColor.RED : BarColor.PURPLE, BarStyle.SEGMENTED_10);
        int left = Math.max(0, r.dayLen() + r.nightLen() - r.clock);
        r.bar.setProgress(Math.max(0, Math.min(1, left / (double) r.nightLen())));
        r.bar.setTitle(r.color() + "" + ChatColor.BOLD + r.name() + " Night" + (r.event == null ? "" : ChatColor.GRAY + " · " + evColor(r.event) + ChatColor.BOLD + title(r.event))
                + ChatColor.DARK_GRAY + " · " + ChatColor.GRAY + (left / 60) + ":" + String.format("%02d", left % 60) + " to dawn"
                + (asleep > 0 ? ChatColor.DARK_GRAY + " · " + ChatColor.AQUA + asleep + "/" + need + " asleep" : ""));
        for (Player p : here) if (!r.bar.getPlayers().contains(p)) r.bar.addPlayer(p);
        for (Player p : new ArrayList<>(r.bar.getPlayers())) if (!p.isOnline() || !p.getWorld().equals(r.w)) r.bar.removePlayer(p);
    }

    /** Night falls. forced = that event (or "night" for a quiet one); null = roll. */
    void dusk(Realm r, String forced, boolean announce) {
        if (r.night) dawn(r, false, false);
        r.clock = r.dayLen();
        r.night = true;
        r.asleepFor = 0;
        r.there.clear();
        r.event = null;
        if (forced != null) { if (!forced.equals("night")) r.event = forced; }
        else for (String ev : r.nether ? NETHER_EVENTS : END_EVENTS) {
            if (!cfgB(ev + ".enabled", true)) continue;
            if (pl.random.nextDouble() < cfgD(r.key() + ".events." + ev, 0.3)) { r.event = ev; break; }
        }
        r.save();
        if (!announce || !pl.loud()) return;
        String sub = r.event == null ? ChatColor.GRAY + "Its monsters stir. A Blue Obsidian Bed lets you sleep through it." : evColor(r.event) + title(r.event);
        for (Player p : r.players()) {
            p.sendTitle(r.color() + "" + ChatColor.BOLD + "Night falls over the " + r.name(), sub, 10, 70, 20);
            p.playSound(p.getLocation(), r.nether ? Sound.AMBIENT_NETHER_WASTES_MOOD : Sound.ENTITY_ENDER_DRAGON_GROWL, 0.7f, 0.6f);
            p.sendMessage(r.color() + "☾ Night falls over the " + r.name() + "." + (r.event == null ? ChatColor.GRAY + " More monsters come out. Sleep in a Blue Obsidian Bed to skip it."
                    : " " + evColor(r.event) + ChatColor.BOLD + title(r.event) + ChatColor.GRAY + ": " + blurb(r.event)));
        }
        pl.getLogger().info(r.name() + " night" + (r.event == null ? "" : " (" + title(r.event) + ")"));
    }

    void dawn(Realm r, boolean announce, boolean slept) {
        boolean was = r.night;
        r.night = false;
        r.clock = 0;
        r.asleepFor = 0;
        String ev = r.event;
        r.event = null;
        r.save();
        for (Pickup pk : r.pickups) if (pk.d.isValid()) pk.d.remove();
        r.pickups.clear();
        for (UUID id : r.mobs) { Entity e = Bukkit.getEntity(id); if (e instanceof LivingEntity le && le.isValid()) unbuff(le); }
        r.mobs.clear();
        if (r.bar != null) { r.bar.removeAll(); r.bar = null; }
        if (!was) return;
        for (Player p : r.players()) {
            if (slept && (p.isSleeping() || p.hasMetadata(REST))) { try { if (p.isSleeping()) p.wakeup(true); } catch (RuntimeException ignored) { } }
            if (announce && pl.loud()) {
                p.sendMessage(r.color() + "☀ " + (slept ? "The night passes over the " + r.name() + " while its people sleep." : "Dawn in the " + r.name() + ".")
                        + (ev == null ? "" : ChatColor.GRAY + " The " + title(ev) + " is over."));
                p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.2f);
            }
            if (FaultlineEvents.survival(p) && r.there.getOrDefault(p.getUniqueId(), 0) >= Math.min(120, r.nightLen() / 2) && !slept) pl.stat(p, "realm_nights", 1);
        }
        r.there.clear();
    }

    void shutdown() {
        for (Realm r : realms.values()) {
            r.save();
            for (Pickup pk : r.pickups) if (pk.d.isValid()) pk.d.remove();
            if (r.bar != null) r.bar.removeAll();
        }
    }

    // ---------------------------------------------------------------- the night

    private void nightSecond(Realm r, List<Player> here) {
        r.mobs.removeIf(id -> { Entity e = Bukkit.getEntity(id); return e == null || !e.isValid(); });
        r.pickups.removeIf(pk -> { if (!pk.d.isValid()) return true; if (System.currentTimeMillis() > pk.until) { pk.d.remove(); return true; } return false; });
        int every = Math.max(4, cfgI("extra-spawns.every-seconds", 20));
        for (Player p : here) {
            if (!FaultlineEvents.survival(p)) continue;
            int slot = Math.floorMod(p.getUniqueId().hashCode(), every);
            if (r.clock % every == slot && cfgB("extra-spawns.enabled", true)) spawn(r, p);
            if (r.event == null) continue;
            switch (r.event) {
                case "ember-storm" -> ember(p);
                case "soul-tide" -> { if (r.clock % Math.max(5, cfgI("soul-tide.souls.every-seconds", 25)) == slot % 5) pickup(r, p, "soul"); p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 0, true, false, true)); }
                case "void-storm" -> { if (r.clock % Math.max(5, cfgI("void-storm.hunt-every-seconds", 15)) == slot % 5) hunt(p); }
                case "starfall" -> { if (r.clock % Math.max(5, cfgI("starfall.stars.every-seconds", 25)) == slot % 5) pickup(r, p, "star"); }
                default -> { }
            }
        }
    }

    /** Twice a second: particles, and walking into a soul / a star. */
    void tick(long t) {
        if (t % 10 != 0) return;
        for (Realm r : realms.values()) {
            if (!r.night) continue;
            List<Player> here = r.players();
            if (here.isEmpty()) continue;
            try { fx(r, here); catch_(r, here); } catch (RuntimeException e) { pl.problem("realm fx", e); }
        }
    }

    private void fx(Realm r, List<Player> here) {
        for (Player p : here) {
            Location l = p.getLocation();
            int n = r.event == null ? 6 : 24;
            for (int k = 0; k < n; k++) {
                Location at = l.clone().add(pl.random.nextGaussian() * 7, 1 + pl.random.nextDouble() * 7, pl.random.nextGaussian() * 7);
                if (r.event == null) p.spawnParticle(r.nether ? Particle.ASH : Particle.PORTAL, at, 1, 0.2, 0.2, 0.2, 0.02);
                else switch (r.event) {
                    case "ember-storm" -> { if (k % 3 == 0) p.spawnParticle(Particle.LAVA, at, 1, 0, 0, 0, 0); else p.spawnParticle(Particle.FLAME, at, 0, 0.02, -0.25, 0.02, 1); }
                    case "soul-tide" -> p.spawnParticle(k % 4 == 0 ? Particle.SOUL : Particle.SOUL_FIRE_FLAME, at, 0, 0.03, 0.04, 0.03, 1);
                    case "void-storm" -> { if (k % 2 == 0) p.spawnParticle(Particle.REVERSE_PORTAL, at, 1, 0.3, 0.3, 0.3, 0.05); else p.spawnParticle(Particle.DUST, at, 1, 0.2, 0.2, 0.2, 0, new Particle.DustOptions(Color.fromRGB(110, 40, 170), 1.6f)); }
                    default -> p.spawnParticle(Particle.END_ROD, at.add(0, 8, 0), 0, 0.15, -0.5, 0.1, 1);
                }
            }
            if (r.event != null && r.event.equals("void-storm") && pl.random.nextInt(40) == 0)
                p.playSound(l.clone().add(pl.random.nextGaussian() * 12, 6, pl.random.nextGaussian() * 12), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.5f, 0.5f);
        }
        for (Pickup pk : r.pickups) if (pk.d.isValid()) {
            Location at = pk.d.getLocation().add(0, 0.3, 0);
            r.w.spawnParticle(pk.kind.equals("soul") ? Particle.SOUL_FIRE_FLAME : Particle.END_ROD, at, 3, 0.2, 0.3, 0.2, 0.01);
        }
    }

    private void catch_(Realm r, List<Player> here) {
        for (Pickup pk : new ArrayList<>(r.pickups)) {
            if (!pk.d.isValid()) continue;
            for (Player p : here) {
                if (!FaultlineEvents.survival(p) || p.getLocation().distanceSquared(pk.d.getLocation()) > 1.9 * 1.9) continue;
                r.pickups.remove(pk);
                Location at = pk.d.getLocation();
                pk.d.remove();
                boolean soul = pk.kind.equals("soul");
                p.playSound(at, soul ? Sound.PARTICLE_SOUL_ESCAPE : Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1f, soul ? 0.8f : 1.5f);
                p.getWorld().spawnParticle(soul ? Particle.SOUL : Particle.FIREWORK, at, 20, 0.3, 0.3, 0.3, 0.05);
                reward(p, soul ? "soul-tide.souls.rewards" : "starfall.stars.rewards", soul ? SOUL_REWARDS : STAR_REWARDS);
                pl.stat(p, soul ? "lost_souls" : "star_shards", 1);
                break;
            }
        }
    }

    static final List<String> SOUL_REWARDS = List.of("1.0;xp:25", "0.5;item:GOLD_NUGGET:6-14", "0.3;item:QUARTZ:4-10", "0.2;item:SOUL_LANTERN:1-2", "0.12;item:GOLD_INGOT:2-5", "0.03;item:WITHER_SKELETON_SKULL:1", "0.02;item:NETHERITE_SCRAP:1");
    static final List<String> STAR_REWARDS = List.of("1.0;xp:35", "0.5;item:ENDER_PEARL:2-5", "0.3;item:CHORUS_FRUIT:4-10", "0.2;item:AMETHYST_SHARD:4-8", "0.12;item:DIAMOND:1-2", "0.04;item:SHULKER_SHELL:1", "0.03;item:FIREWORK_ROCKET:8-16");

    /** "chance;xp:N" / "chance;item:MAT:a-b" / "chance;cmd:..." : every line rolls on its own. */
    private void reward(Player p, String path, List<String> def) {
        List<String> lines = pl.getConfig().getStringList("realms." + path);
        if (lines.isEmpty()) lines = def;
        for (String line : lines) {
            try {
                String[] a = line.split(";", 2);
                if (a.length < 2 || pl.random.nextDouble() >= Double.parseDouble(a[0].trim())) continue;
                String what = a[1].trim();
                if (what.startsWith("xp:")) p.giveExp(Integer.parseInt(what.substring(3).trim()));
                else if (what.startsWith("cmd:")) pl.console(what.substring(4).trim(), p);
                else {
                    ItemStack it = stack(what);
                    if (it != null) for (ItemStack rest : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), rest);
                }
            } catch (RuntimeException e) { pl.problem("reward " + line, e); }
        }
    }

    private ItemStack stack(String spec) {
        String s = spec.startsWith("item:") ? spec.substring(5) : spec;
        String[] p = s.split(":");
        Material m = FaultlineEvents.material(p[0]);
        if (m == null || !m.isItem()) return null;
        int lo = 1, hi = 1;
        if (p.length > 1) { String[] r = p[1].split("-"); lo = Integer.parseInt(r[0].trim()); hi = r.length > 1 ? Integer.parseInt(r[1].trim()) : lo; }
        int n = lo + (hi > lo ? pl.random.nextInt(hi - lo + 1) : 0);
        return n <= 0 ? null : new ItemStack(m, Math.min(n, m.getMaxStackSize()));
    }

    // ---------------------------------------------------------------- spawns and buffs

    /** A standable spot near the player, on their level (both dimensions have roofs / voids: never the heightmap). */
    private Location spot(Player p, double min, double max) {
        World w = p.getWorld();
        Location base = p.getLocation();
        for (int k = 0; k < 12; k++) {
            double a = pl.random.nextDouble() * Math.PI * 2, d = min + pl.random.nextDouble() * (max - min);
            int x = (int) Math.floor(base.getX() + Math.cos(a) * d), z = (int) Math.floor(base.getZ() + Math.sin(a) * d);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int dy = 6; dy >= -8; dy--) {
                Block floor = w.getBlockAt(x, base.getBlockY() + dy - 1, z);
                if (Ev.solid(floor) && floor.getType() != Material.MAGMA_BLOCK && floor.getType() != Material.BEDROCK
                        && Ev.open(floor.getRelative(0, 1, 0)) && Ev.open(floor.getRelative(0, 2, 0)))
                    return new Location(w, x + 0.5, floor.getY() + 1, z + 0.5);
            }
        }
        return null;
    }

    private void spawn(Realm r, Player p) {
        int near = 0;
        for (UUID id : r.mobs) { Entity e = Bukkit.getEntity(id); if (e != null && e.getWorld().equals(r.w) && e.getLocation().distanceSquared(p.getLocation()) < 40 * 40) near++; }
        if (near >= cfgI("extra-spawns.per-player", 3)) return;
        Location at = spot(p, cfgD("extra-spawns.min-distance", 16), cfgD("extra-spawns.max-distance", 28));
        if (at == null) return;
        EntityType type;
        if (r.nether) type = switch (pl.random.nextInt(6)) { case 0, 1 -> EntityType.BLAZE; case 2, 3 -> EntityType.MAGMA_CUBE; case 4 -> EntityType.WITHER_SKELETON; default -> EntityType.SKELETON; };
        else type = EntityType.ENDERMAN;
        try {
            Entity e = r.w.spawnEntity(at, type, CreatureSpawnEvent.SpawnReason.NATURAL);
            e.addScoreboardTag(MOB_TAG);
            r.mobs.add(e.getUniqueId());
        } catch (RuntimeException ex) { pl.problem("realm spawn", ex); }
    }

    private NamespacedKey mod(String what) { return new NamespacedKey(pl, "realm_" + what); }

    private void buff(LivingEntity e, double health, double damage) {
        add(e, Attribute.MAX_HEALTH, "health", health - 1);
        add(e, Attribute.ATTACK_DAMAGE, "damage", damage - 1);
        try { AttributeInstance h = e.getAttribute(Attribute.MAX_HEALTH); if (h != null) e.setHealth(h.getValue()); } catch (RuntimeException ignored) { }
    }

    private void add(LivingEntity e, Attribute attr, String what, double amount) {
        AttributeInstance a = e.getAttribute(attr);
        if (a == null || amount <= 0) return;
        NamespacedKey k = mod(what);
        for (AttributeModifier m : new ArrayList<>(a.getModifiers())) if (k.equals(m.getKey())) a.removeModifier(m);
        try { a.addModifier(new AttributeModifier(k, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1)); } catch (RuntimeException ignored) { }
    }

    private void unbuff(LivingEntity e) {
        for (Attribute attr : List.of(Attribute.MAX_HEALTH, Attribute.ATTACK_DAMAGE)) {
            AttributeInstance a = e.getAttribute(attr);
            if (a == null) continue;
            for (AttributeModifier m : new ArrayList<>(a.getModifiers())) if (m.getKey().getNamespace().equals(mod("x").getNamespace()) && m.getKey().getKey().startsWith("realm_")) a.removeModifier(m);
        }
    }

    /** The hostile nights make their dimension's monsters stronger. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL || !(e.getEntity() instanceof Enemy)) return;
        Realm r = at(e.getEntity());
        if (r == null || r.event == null) return;
        LivingEntity le = e.getEntity();
        for (String tag : le.getScoreboardTags()) if (tag.startsWith("faultline")) return;
        if (r.event.equals("ember-storm")) { buff(le, cfgD("ember-storm.mob-health", 1.25), cfgD("ember-storm.mob-damage", 1.25)); r.mobs.add(le.getUniqueId()); }
        else if (r.event.equals("void-storm") && le instanceof Enderman) { buff(le, cfgD("void-storm.enderman-health", 1.25), cfgD("void-storm.enderman-damage", 1.3)); r.mobs.add(le.getUniqueId()); }
    }

    // ---------------------------------------------------------------- Ember Storm

    /** 2.1.1: ANY block over your head counts (glass, leaves, slabs, carpet, fences...), up to ember-storm.roof-height blocks
     *  up (32; the Nether's caverns are tall), over any of the 4 corners of your hitbox or your middle. */
    private boolean roofed(Player p) {
        int h = Math.max(2, cfgI("ember-storm.roof-height", 32));
        org.bukkit.util.BoundingBox bb = p.getBoundingBox();
        double[][] at = {{p.getLocation().getX(), p.getLocation().getZ()}, {bb.getMinX() + 0.05, bb.getMinZ() + 0.05}, {bb.getMinX() + 0.05, bb.getMaxZ() - 0.05},
                {bb.getMaxX() - 0.05, bb.getMinZ() + 0.05}, {bb.getMaxX() - 0.05, bb.getMaxZ() - 0.05}};
        World w = p.getWorld();
        int y0 = (int) Math.floor(bb.getMaxY() - 0.01), top = Math.min(w.getMaxHeight() - 1, y0 + h);
        for (double[] c : at) {
            int x = (int) Math.floor(c[0]), z = (int) Math.floor(c[1]);
            for (int y = y0; y <= top; y++) if (roof(w.getBlockAt(x, y, z).getType())) return true;
        }
        return false;
    }

    private static boolean roof(Material m) {
        return !m.isAir() && m != Material.FIRE && m != Material.SOUL_FIRE && m != Material.LIGHT && m != Material.LAVA && m != Material.WATER && m != Material.BUBBLE_COLUMN;
    }

    private void ember(Player p) {
        if (roofed(p) || p.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE) || p.isInWater()) return;
        if (pl.random.nextDouble() >= cfgD("ember-storm.burn-chance", 0.12)) return;
        // 2.1.1: embers never set anything alight (no fire ticks, so nothing you carry, drop or touch burns): a scorch instead
        double dmg = cfgD("ember-storm.damage", 2.0);
        if (dmg > 0) {
            try { p.damage(dmg, org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.IN_FIRE).build()); }
            catch (RuntimeException | LinkageError e) { p.damage(dmg); }
        }
        p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 1, 0), 8, 0.3, 0.5, 0.3, 0.01);
        p.playSound(p.getLocation(), Sound.BLOCK_FIRE_AMBIENT, 0.8f, 1.4f);
        p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, net.md_5.bungee.api.chat.TextComponent.fromLegacyText(ChatColor.GOLD + "An ember lands on you! " + ChatColor.GRAY + "Get under a roof."));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity le = e.getEntity();
        Player k = le.getKiller();
        Realm r = at(le);
        if (r == null || k == null || !(le instanceof Enemy)) return;
        if (r.event == null) { e.setDroppedExp((int) Math.round(e.getDroppedExp() * cfgD("night-xp", 1.25))); return; }
        switch (r.event) {
            case "ember-storm" -> {
                e.setDroppedExp(e.getDroppedExp() * 2);
                if (pl.random.nextDouble() < cfgD("ember-storm.bonus-loot-chance", 0.25)) {
                    Material m = switch (pl.random.nextInt(4)) { case 0 -> Material.BLAZE_POWDER; case 1 -> Material.MAGMA_CREAM; case 2 -> Material.QUARTZ; default -> Material.GOLD_NUGGET; };
                    e.getDrops().add(new ItemStack(m, 1 + pl.random.nextInt(3)));
                }
                pl.stat(k, "ember_storm_kills", 1);
            }
            case "soul-tide" -> {
                if (le instanceof WitherSkeleton && pl.random.nextDouble() < cfgD("soul-tide.skull-bonus", 0.05)) e.getDrops().add(new ItemStack(Material.WITHER_SKELETON_SKULL));
            }
            case "void-storm" -> {
                if (le instanceof Enderman) {
                    e.setDroppedExp(e.getDroppedExp() * 2);
                    e.getDrops().add(new ItemStack(Material.ENDER_PEARL, 1 + (pl.random.nextBoolean() ? 1 : 0)));
                    pl.stat(k, "void_storm_kills", 1);
                }
            }
            default -> { }
        }
    }

    // ---------------------------------------------------------------- Soul Tide

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBarter(PiglinBarterEvent e) {
        if (!event(e.getEntity(), "soul-tide") || !cfgB("soul-tide.double-barter", true)) return;
        List<ItemStack> more = new ArrayList<>();
        for (ItemStack it : e.getOutcome()) more.add(it.clone());
        e.getOutcome().addAll(more);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOre(BlockDropItemEvent e) {
        Material was = e.getBlockState().getType();
        if (was != Material.NETHER_QUARTZ_ORE && was != Material.NETHER_GOLD_ORE) return;
        Realm r = realms.get(e.getBlock().getWorld().getName());
        if (r == null || !r.night || !"soul-tide".equals(r.event)) return;
        double mult = cfgD("soul-tide.ore-multiplier", 1.5);
        for (Item it : e.getItems()) {
            ItemStack s = it.getItemStack();
            if (s.getType() == was) continue; // silk touch
            double want = s.getAmount() * mult;
            int n = (int) Math.floor(want) + (pl.random.nextDouble() < want - Math.floor(want) ? 1 : 0);
            s.setAmount(Math.min(s.getMaxStackSize(), Math.max(1, n)));
            it.setItemStack(s);
        }
    }

    // ---------------------------------------------------------------- souls and stars

    private void pickup(Realm r, Player p, String kind) {
        int mine = 0;
        for (Pickup pk : r.pickups) if (pk.kind.equals(kind) && pk.d.isValid() && pk.d.getLocation().distanceSquared(p.getLocation()) < 40 * 40) mine++;
        if (mine >= cfgI((kind.equals("soul") ? "soul-tide.souls" : "starfall.stars") + ".per-player", 2)) return;
        Location at = spot(p, 8, 22);
        if (at == null) return;
        try {
            BlockDisplay d = (BlockDisplay) r.w.spawnEntity(at.clone().add(-0.25, 0.4, -0.25), EntityType.BLOCK_DISPLAY, false);
            d.setBlock((kind.equals("soul") ? Material.SOUL_LANTERN : Material.SEA_LANTERN).createBlockData());
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.5f, 0.5f, 0.5f), new Quaternionf()));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setGlowing(true);
            try { d.setGlowColorOverride(kind.equals("soul") ? Color.fromRGB(80, 220, 255) : Color.fromRGB(255, 240, 150)); } catch (RuntimeException ignored) { }
            d.setPersistent(false);
            d.addScoreboardTag(PICKUP_TAG);
            r.pickups.add(new Pickup(d, kind, System.currentTimeMillis() + cfgI("pickup-seconds", 75) * 1000L));
            if (kind.equals("star")) { // it falls
                for (int k = 0; k < 30; k++) r.w.spawnParticle(Particle.END_ROD, at.clone().add(k * 0.25, 1 + k * 1.2, k * 0.1), 2, 0.05, 0.3, 0.05, 0.01);
                for (Player q : r.players()) if (q.getLocation().distanceSquared(at) < 48 * 48) {
                    q.playSound(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.2f, 0.7f);
                    q.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, net.md_5.bungee.api.chat.TextComponent.fromLegacyText(ChatColor.YELLOW + "A star fell nearby!"));
                }
            } else p.playSound(at, Sound.PARTICLE_SOUL_ESCAPE, 1f, 0.6f);
        } catch (RuntimeException | LinkageError e) { pl.problem("pickup", e); }
    }

    // ---------------------------------------------------------------- Void Storm

    private void hunt(Player p) {
        int max = cfgI("void-storm.hunters", 2), angry = 0;
        List<Enderman> calm = new ArrayList<>();
        for (Entity e : p.getNearbyEntities(24, 12, 24)) {
            if (!(e instanceof Enderman en) || !en.isValid()) continue;
            if (p.equals(en.getTarget())) angry++; else if (en.getTarget() == null && en.hasAI()) calm.add(en);
        }
        if (angry >= max || calm.isEmpty()) return;
        Enderman en = calm.get(pl.random.nextInt(calm.size()));
        for (String tag : en.getScoreboardTags()) if (tag.startsWith("faultline")) return;
        try { en.setTarget(p); } catch (RuntimeException ignored) { return; }
        p.playSound(en.getLocation(), Sound.ENTITY_ENDERMAN_STARE, 1f, 0.7f);
    }

    // ---------------------------------------------------------------- Starfall

    @EventHandler(priority = EventPriority.HIGH)
    public void onXp(PlayerExpChangeEvent e) {
        if (e.getAmount() <= 0 || !event(e.getPlayer(), "starfall")) return;
        e.setAmount((int) Math.round(e.getAmount() * cfgD("starfall.xp-multiplier", 1.5)));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onWear(PlayerItemDamageEvent e) {
        if (e.getItem().getType() == Material.ELYTRA && event(e.getPlayer(), "starfall") && cfgB("starfall.elytra-no-wear", true)) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- /fevent realm

    String status(World w) {
        Realm r = realm(w);
        if (r == null) return null;
        if (!r.night) { int left = Math.max(0, r.dayLen() - r.clock); return r.color() + "It's day in the " + r.name() + ChatColor.GRAY + ". Night falls in " + Math.max(1, (left + 59) / 60) + " min."; }
        int left = Math.max(0, r.dayLen() + r.nightLen() - r.clock);
        return r.color() + "" + ChatColor.BOLD + r.name() + " Night" + ChatColor.GRAY + (r.event == null ? "" : ", " + evColor(r.event) + ChatColor.BOLD + title(r.event) + ChatColor.GRAY + ": " + blurb(r.event))
                + ChatColor.GRAY + " Dawn in " + Math.max(1, (left + 59) / 60) + " min (or sleep in a Blue Obsidian Bed).";
    }

    /** what = day / night / an event of that realm. Null = done, else why not. */
    String force(String which, String what) {
        boolean nether = which.equalsIgnoreCase("nether");
        if (!nether && !which.equalsIgnoreCase("end")) return "nether or end";
        World w = null;
        for (World x : Bukkit.getWorlds()) if (realmWorld(x) && (x.getEnvironment() == World.Environment.NETHER) == nether) { w = x; break; }
        if (w == null) return "That world isn't loaded.";
        Realm r = realm(w);
        what = what.toLowerCase();
        if (what.equals("day")) { dawn(r, true, false); return null; }
        if (what.equals("night")) { dusk(r, "night", true); return null; }
        if (!(nether ? NETHER_EVENTS : END_EVENTS).contains(what)) return "day, night, " + String.join(", ", nether ? NETHER_EVENTS : END_EVENTS);
        dusk(r, what, true);
        return null;
    }
}
