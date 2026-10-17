package net.faultlinesmp.deep;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * THE MERMAID DEN'S PEOPLE (Deep 1.7.0): ten mermaids, each with a house of her own, and six guards (one inside each gate,
 * two by the throne). They're neutral: they sing, drift round their homes and leave you alone.
 *
 * Hurting one turns everyone near on you. Coralia only rises two ways:
 *  - kill all ten mermaids (player kills count; who killed them doesn't matter), or
 *  - drink an Omen IV (Bad Omen level IV exactly: an Ominous Bottle IV) inside the Den: the Den goes to WAR. Everyone
 *    living there attacks the raiders, then two waves of guards come in through the gates; wipe them all out.
 * Either way she rises over her throne (FaultlineBosses `coralia summon <world> <x> <y> <z>`). The dead come home after
 * `den.restock-minutes` (30). Kept in `slot` (never a world scan); saved in den.yml.
 */
final class Den {
    static final int MERMAIDS = 10, GUARDS = 6;

    final FaultlineDeep pl;
    final Random random = new Random();
    final DeepMobs.Beast[] slot = new DeepMobs.Beast[MERMAIDS + GUARDS];
    final boolean[] dead = new boolean[MERMAIDS];
    final long[] guardBack = new long[GUARDS];
    final Map<UUID, Long> told = new HashMap<>();
    long restockAt;
    War war;
    int ticks;
    final File file;

    /** A raid: who started it, the wave guards, the bar. */
    final class War {
        final Set<UUID> fighters = new HashSet<>();
        final List<DeepMobs.Beast> units = new ArrayList<>();
        final BossBar bar = Bukkit.createBossBar(ChatColor.LIGHT_PURPLE + "The Mermaid Den is at war", BarColor.PINK, BarStyle.SEGMENTED_10);
        int wave, total, quiet;
    }

    Den(FaultlineDeep pl) {
        this.pl = pl;
        file = new File(pl.getDataFolder(), "den.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 40L, 20L);
    }

    double cfg(String path, double def) { return pl.getConfig().getDouble("den." + path, def); }

    // =========================================================================================== where they live
    /** Mermaid i's home (inside her house) and guard j's post. */
    static int[] home(int i) {
        if (i < MERMAIDS) {
            MermaidDen.House h = MermaidDen.HOUSES.get(i * MermaidDen.HOUSES.size() / MERMAIDS);
            return new int[]{h.x(), h.z()};
        }
        int j = i - MERMAIDS, d = MermaidDen.R - 16;
        return switch (j) {
            case 0 -> new int[]{MermaidDen.MX + d, MermaidDen.MZ};
            case 1 -> new int[]{MermaidDen.MX - d, MermaidDen.MZ};
            case 2 -> new int[]{MermaidDen.MX, MermaidDen.MZ + d};
            case 3 -> new int[]{MermaidDen.MX, MermaidDen.MZ - d};
            case 4 -> new int[]{MermaidDen.MX - 7, MermaidDen.MZ - 14};
            default -> new int[]{MermaidDen.MX + 7, MermaidDen.MZ - 14};
        };
    }

    /** The lowest spot there with two blocks of water (null: not loaded / no room). */
    static Location waterAt(World w, int x, int z) {
        // the exact spot first, then outward in rings (a house's middle can be its own wall, a column or a clam): the
        // nearest column with two blocks of open water above the floor
        for (int r = 0; r <= 8; r += 2) {
            for (int dx = -r; dx <= r; dx += 2) for (int dz = -r; dz <= r; dz += 2) {
                if (r > 0 && Math.abs(dx) != r && Math.abs(dz) != r) continue; // only the ring's edge
                Location l = column(w, x + dx, z + dz);
                if (l != null) return l;
            }
        }
        return null;
    }

    /** Each home's water spot, found once (the search reads a few hundred blocks). */
    final Location[] spots = new Location[MERMAIDS + GUARDS];

    Location homeSpot(World w, int i, int[] h) {
        Location c = spots[i];
        if (c != null && c.getWorld() == w) return c;
        c = waterAt(w, h[0], h[1]);
        if (c != null) spots[i] = c;
        return c;
    }

    private static Location column(World w, int x, int z) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
        for (int y = MermaidDen.FLOOR - 4; y < MermaidDen.FLOOR + 24; y++) {
            if (swim(w.getBlockAt(x, y, z).getType()) && swim(w.getBlockAt(x, y + 1, z).getType()) && !swim(w.getBlockAt(x, y - 1, z).getType()))
                return new Location(w, x + 0.5, y, z + 0.5);
        }
        return null;
    }

    /** Water a mermaid can be in (seagrass and kelp are water too). */
    static boolean swim(Material m) {
        return m == Material.WATER || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS || m == Material.KELP || m == Material.KELP_PLANT || m == Material.BUBBLE_COLUMN;
    }

    static boolean inside(Location l, double extra) {
        return Math.hypot(l.getX() - MermaidDen.MX, l.getZ() - MermaidDen.MZ) < MermaidDen.RIM + extra;
    }

    boolean resident(DeepMobs.Beast m) {
        for (DeepMobs.Beast b : slot) if (b == m) return true;
        return war != null && war.units.contains(m);
    }

    int mermaidsLeft() { int n = 0; for (boolean d : dead) if (!d) n++; return n; }

    // =========================================================================================== every second
    void tick() {
        ticks++;
        World w = pl.world;
        if (w == null || !pl.getConfig().getBoolean("den.enabled", true)) return;
        long now = System.currentTimeMillis();
        if (restockAt > 0 && now >= restockAt && war == null) {
            java.util.Arrays.fill(dead, false);
            restockAt = 0;
            save();
            for (Player p : w.getPlayers()) if (inside(p.getLocation(), 40)) p.sendMessage(ChatColor.LIGHT_PURPLE + "The mermaids have come home to the Den.");
        }
        List<Player> here = new ArrayList<>();
        for (Player p : w.getPlayers()) if (p.getGameMode() != GameMode.SPECTATOR && inside(p.getLocation(), 60)) here.add(p);
        for (int i = 0; i < slot.length; i++) {
            DeepMobs.Beast b = slot[i];
            if (b != null && (!b.body.isValid() || b.body.isDead() || !pl.mobs.live.containsKey(b.body.getUniqueId()))) slot[i] = b = null;
            int[] h = home(i);
            if (b == null) {
                if (here.isEmpty()) continue;
                if (i < MERMAIDS ? dead[i] : guardBack[i - MERMAIDS] > now) continue;
                if (war != null) continue; // nobody new while the Den is at war
                boolean someone = false;
                for (Player p : here) if (Math.hypot(p.getLocation().getX() - h[0], p.getLocation().getZ() - h[1]) < 90) { someone = true; break; }
                if (!someone) continue;
                Location at = homeSpot(w, i, h);
                if (at == null) continue;
                slot[i] = spawn(i < MERMAIDS ? "mermaid" : "merguard", at);
                continue;
            }
            if (!b.angry() && b.body instanceof Mob mob) { // calm: drift home
                Location l = b.body.getLocation();
                double d = Math.hypot(l.getX() - h[0], l.getZ() - h[1]);
                if (d > (i < MERMAIDS ? 10 : 5) && ticks % 3 == 0) {
                    Location at = homeSpot(w, i, h);
                    if (at != null) try { mob.getPathfinder().moveTo(at, 1.0); } catch (RuntimeException | LinkageError ignored) { }
                }
            }
        }
        if (war != null) warTick(w, here);
        else { omens(here); if (pl.rep != null) pl.rep.tick(here); }
    }

    DeepMobs.Beast spawn(String kind, Location at) {
        DeepMobs.Kind k = DeepMobs.KINDS.get(kind);
        DeepMobs.Beast b = pl.mobs.spawn(k, at);
        if (b == null) return null;
        try { b.body.setRemoveWhenFarAway(false); } catch (RuntimeException ignored) { } // the Den keeps them (DeepMobs drops them when nobody's within 100)
        if (kind.equals("merguard") && b.body.getEquipment() != null) b.body.getEquipment().setItemInMainHand(new ItemStack(Material.TRIDENT));
        return b;
    }

    // =========================================================================================== hurting them
    /** Someone hurt one of the Den's people: everyone near turns on them. */
    void hurt(DeepMobs.Beast m, Player by) {
        if (!resident(m)) return;
        if (pl.rep != null && war == null) pl.rep.hurt(by);
        Location l = m.body.getLocation();
        int angered = 0;
        for (DeepMobs.Beast b : everyone()) if (b != m && b.body.getWorld().equals(l.getWorld()) && b.body.getLocation().distanceSquared(l) < 48 * 48) { pl.mobs.anger(b, by, 600); angered++; }
        Long last = told.get(by.getUniqueId());
        if (angered > 0 && (last == null || System.currentTimeMillis() - last > 60_000)) {
            told.put(by.getUniqueId(), System.currentTimeMillis());
            by.sendMessage(ChatColor.LIGHT_PURPLE + "The Den turns on you!");
            by.playSound(by, Sound.EVENT_RAID_HORN, SoundCategory.HOSTILE, 0.6f, 1.6f);
        }
    }

    List<DeepMobs.Beast> everyone() {
        List<DeepMobs.Beast> out = new ArrayList<>();
        for (DeepMobs.Beast b : slot) if (b != null) out.add(b);
        if (war != null) out.addAll(war.units);
        return out;
    }

    /** One of them died. Player-killed mermaids stay dead until the restock. */
    void died(DeepMobs.Beast m, Player killer) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < slot.length; i++) {
            if (slot[i] != m) continue;
            slot[i] = null;
            if (i >= MERMAIDS) {
                guardBack[i - MERMAIDS] = now + (long) (cfg("guard-respawn-seconds", 120) * 1000);
                if (killer != null && pl.rep != null && war == null) pl.rep.killedResident(killer, false);
                return;
            }
            if (killer == null) return; // drowned in lava or whatever: she comes back
            if (pl.rep != null && war == null) pl.rep.killedResident(killer, true);
            dead[i] = true;
            if (restockAt == 0) restockAt = now + (long) (cfg("restock-minutes", 30) * 60_000);
            save();
            int left = mermaidsLeft();
            World w = m.body.getWorld();
            for (Player p : w.getPlayers()) if (inside(p.getLocation(), 60))
                p.sendMessage(ChatColor.LIGHT_PURPLE + "A mermaid has fallen. " + ChatColor.GRAY + "(" + left + " of " + MERMAIDS + " left)");
            if (left == 0 && war == null) rise("All ten of her mermaids are dead.");
            return;
        }
        if (war != null) war.units.remove(m);
    }

    // =========================================================================================== the Omen IV raid
    void omens(List<Player> here) {
        for (Player p : here) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            if (!inside(p.getLocation(), 0)) continue;
            PotionEffect omen = p.getPotionEffect(PotionEffectType.BAD_OMEN);
            if (omen == null) continue;
            if (omen.getAmplifier() == 3) { p.removePotionEffect(PotionEffectType.BAD_OMEN); startWar(p); return; }
            Long last = told.get(p.getUniqueId());
            if (last == null || System.currentTimeMillis() - last > 60_000) {
                told.put(p.getUniqueId(), System.currentTimeMillis());
                p.sendMessage(ChatColor.LIGHT_PURPLE + "The mermaids glance at you and look away. " + ChatColor.GRAY + "(Only an Omen IV stirs the Den to war.)");
            }
        }
    }

    boolean startWar(Player by) {
        if (war != null || pl.world == null) return false;
        war = new War();
        World w = pl.world;
        for (Player p : w.getPlayers()) if (p.getGameMode() != GameMode.SPECTATOR && inside(p.getLocation(), 40)) {
            war.fighters.add(p.getUniqueId());
            war.bar.addPlayer(p);
            p.sendTitle(ChatColor.LIGHT_PURPLE + "The Den goes to war", ChatColor.GRAY + by.getName() + " brought an Omen IV", 10, 60, 20);
            p.playSound(p, Sound.EVENT_RAID_HORN, SoundCategory.HOSTILE, 1f, 1.2f);
        }
        war.fighters.add(by.getUniqueId());
        war.bar.addPlayer(by);
        if (pl.rep != null) pl.rep.war(by, war.fighters);
        nextWave(w);
        return true;
    }

    void nextWave(World w) {
        war.wave++;
        int n = (int) (war.wave == 1 ? cfg("raid.wave-1", 6) : cfg("raid.wave-2", 8));
        List<Player> fighters = fighters(w);
        for (int i = 0; i < n && !fighters.isEmpty(); i++) {
            int[] g = home(MERMAIDS + random.nextInt(4)); // in through a gate, then after a raider
            Location at = waterAt(w, g[0] + random.nextInt(7) - 3, g[1] + random.nextInt(7) - 3);
            if (at == null) { Player f = fighters.get(random.nextInt(fighters.size())); at = waterNear(w, f.getLocation()); }
            if (at == null) continue;
            DeepMobs.Beast b = spawn(i % 4 == 3 ? "mermaid" : "merguard", at);
            if (b != null) war.units.add(b);
        }
        for (Player p : fighters) p.sendMessage(ChatColor.LIGHT_PURPLE + "Wave " + war.wave + ": " + ChatColor.GRAY + "guards pour in through the gates!");
        war.total = everyone().size();
    }

    Location waterNear(World w, Location l) {
        for (int tries = 0; tries < 10; tries++) {
            double a = random.nextDouble() * Math.PI * 2, r = 10 + random.nextDouble() * 8;
            Location at = l.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
            if (at.getBlock().getType() == Material.WATER && at.clone().add(0, 1, 0).getBlock().getType() == Material.WATER) return at;
        }
        return null;
    }

    List<Player> fighters(World w) {
        List<Player> out = new ArrayList<>();
        for (UUID id : war.fighters) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.getWorld().equals(w) && !p.isDead() && DeepMobs.fair(p) && inside(p.getLocation(), 40)) out.add(p);
        }
        return out;
    }

    void warTick(World w, List<Player> here) {
        for (Player p : here) if (DeepMobs.fair(p) && inside(p.getLocation(), 0) && war.fighters.add(p.getUniqueId())) war.bar.addPlayer(p); // anyone who swims in is in it
        List<Player> fighters = fighters(w);
        war.units.removeIf(b -> !b.body.isValid() || b.body.isDead());
        List<DeepMobs.Beast> all = everyone();
        if (fighters.isEmpty()) {
            if (++war.quiet >= cfg("raid.give-up-seconds", 60)) endWar(false);
            return;
        }
        war.quiet = 0;
        for (DeepMobs.Beast b : all) { // every one of them goes for the nearest raider
            Player best = null; double bd = Double.MAX_VALUE;
            for (Player p : fighters) { double d = p.getLocation().distanceSquared(b.body.getLocation()); if (d < bd) { bd = d; best = p; } }
            if (best != null && (!b.angry() || ticks % 5 == 0)) pl.mobs.anger(b, best, 200);
        }
        // the ones who live here but haven't come out yet come out now (they're part of the war)
        if (war.wave == 1 && war.units.size() <= cfg("raid.next-wave-at", 2)) nextWave(w);
        all = everyone();
        boolean residentsLeft = false;
        for (int i = 0; i < MERMAIDS; i++) if (!dead[i] && slot[i] != null) residentsLeft = true;
        for (int i = MERMAIDS; i < slot.length; i++) if (slot[i] != null) residentsLeft = true;
        int left = all.size();
        war.total = Math.max(war.total, left);
        war.bar.setProgress(war.total == 0 ? 0 : Math.max(0, Math.min(1, left / (double) war.total)));
        war.bar.setTitle(ChatColor.LIGHT_PURPLE + "The Mermaid Den is at war " + ChatColor.GRAY + "(wave " + war.wave + "/2, " + left + " left)");
        if (war.wave >= 2 && war.units.isEmpty() && !residentsLeft) {
            java.util.Arrays.fill(dead, true); // the ones who weren't out are gone too: she comes for vengeance
            restockAt = System.currentTimeMillis() + (long) (cfg("restock-minutes", 30) * 60_000);
            save();
            endWar(true);
            rise("The Den has fallen.");
        }
    }

    void endWar(boolean won) {
        if (war == null) return;
        if (!won) for (UUID id : war.fighters) { Player p = Bukkit.getPlayer(id); if (p != null) p.sendMessage(ChatColor.GRAY + "The Den's war is over. The mermaids go back to their homes."); }
        war.bar.removeAll();
        for (DeepMobs.Beast b : war.units) pl.mobs.remove(b);
        for (DeepMobs.Beast b : slot) if (b != null) { b.angryAt = null; if (b.body instanceof Mob mob) mob.setTarget(null); }
        war = null;
    }

    // =========================================================================================== Coralia
    void rise(String why) {
        World w = pl.world;
        int[] t = MermaidDen.throne();
        for (Player p : w.getPlayers()) if (inside(p.getLocation(), 60)) {
            p.sendTitle(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Coralia rises", ChatColor.GRAY + why, 10, 70, 20);
            p.playSound(p, Sound.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.HOSTILE, 1f, 0.7f);
        }
        w.spawnParticle(Particle.BUBBLE_COLUMN_UP, t[0] + 0.5, t[1] + 2, t[2] + 0.5, 200, 2, 3, 2, 0.2);
        if (Bukkit.getPluginCommand("coralia") == null) { pl.getLogger().warning("[Den] FaultlineBosses isn't loaded: Coralia can't rise."); return; }
        Bukkit.getScheduler().runTaskLater(pl, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "coralia summon " + w.getName() + " " + (t[0] + 0.5) + " " + (t[1] + 4) + " " + (t[2] + 0.5)), 60L);
    }

    // =========================================================================================== saving
    void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        List<Integer> d = y.getIntegerList("dead");
        for (int i : d) if (i >= 0 && i < MERMAIDS) dead[i] = true;
        restockAt = y.getLong("restock-at", 0);
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        List<Integer> d = new ArrayList<>();
        for (int i = 0; i < MERMAIDS; i++) if (dead[i]) d.add(i);
        y.set("dead", d);
        y.set("restock-at", restockAt);
        try { y.save(file); } catch (IOException e) { pl.getLogger().warning("[Den] couldn't save den.yml: " + e); }
    }

    void reset() {
        java.util.Arrays.fill(dead, false);
        restockAt = 0;
        endWar(false);
        save();
    }

    void shutdown() {
        if (war != null) war.bar.removeAll();
        for (DeepMobs.Beast b : everyone()) pl.mobs.remove(b);
        save();
    }

    String info() {
        int alive = 0; for (DeepMobs.Beast b : slot) if (b != null) alive++;
        return "Mermaids left: " + mermaidsLeft() + "/" + MERMAIDS + "   out now: " + alive + "   war: " + (war == null ? "no" : "wave " + war.wave + ", " + everyone().size() + " left")
                + (restockAt > 0 ? "   restock in " + Math.max(0, (restockAt - System.currentTimeMillis()) / 60_000) + " min" : "");
    }

    /** One line per resident: where they are now (or their home, and why they're not out). */
    List<String> where() {
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < slot.length; i++) {
            int[] h = home(i);
            String who = (i < MERMAIDS ? "Mermaid " + (i + 1) : "Guard " + (i - MERMAIDS + 1));
            DeepMobs.Beast b = slot[i];
            String state;
            if (b != null) { Location l = b.body.getLocation(); state = "out at " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ() + (b.angry() ? " (ANGRY)" : ""); }
            else if (i < MERMAIDS && dead[i]) state = "dead (comes home with the restock)";
            else if (i >= MERMAIDS && guardBack[i - MERMAIDS] > now) state = "dead (back in " + (guardBack[i - MERMAIDS] - now) / 1000 + " s)";
            else state = "home " + h[0] + " " + h[1] + " (comes out when someone is within 90 blocks)";
            out.add(who + ": " + state);
        }
        return out;
    }
}
