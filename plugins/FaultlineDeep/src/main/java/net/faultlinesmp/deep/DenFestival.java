package net.faultlinesmp.deep;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * THE DEN FESTIVAL (Deep 1.15.0, config den.festival:). Once a month (the days in `days`, default the 1st and 2nd, zone
 * America/New_York; or /deep den festival start|stop|auto) the Mermaid Den celebrates: a boss bar for everyone within 220
 * blocks of the Den, lights and music over the lagoon (particles and chimes: nothing is built), and EVERY mermaid or guard
 * gives each visitor ONE GIFT that festival (right-click any resident; it comes before trades): a roll from `gifts`
 * ("MATERIAL:min:max:weight"), Glimmer Pearls (`pearls` min-max, they keep between seasons), `rep` reputation (+5) and a
 * small chance of a Dark Loot Bag. Not for players the Den HATES. Index stat den_festivals. Who got a gift is saved per
 * festival in den_festival.yml.
 */
final class DenFestival {

    private final FaultlineDeep pl;
    private final Random random = new Random();
    private final File file;
    private String forced = "auto";
    private String festival = "";
    private final Set<UUID> gifted = new HashSet<>();
    private BossBar bar;
    private int tick;

    DenFestival(FaultlineDeep pl) {
        this.pl = pl;
        file = new File(pl.getDataFolder(), "den_festival.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 60L, 10L);
    }

    LocalDate today() {
        try { return LocalDate.now(ZoneId.of(pl.getConfig().getString("den.festival.zone", "America/New_York"))); }
        catch (RuntimeException e) { return LocalDate.now(); }
    }

    boolean active() {
        if (!pl.getConfig().getBoolean("den.festival.enabled", true)) return false;
        if (forced.equals("on")) return true;
        if (forced.equals("off")) return false;
        List<Integer> days = pl.getConfig().getIntegerList("den.festival.days");
        if (days.isEmpty()) days = List.of(1, 2);
        return days.contains(today().getDayOfMonth());
    }

    String id() { LocalDate d = today(); return forced.equals("on") ? "forced-" + d : d.getYear() + "-" + d.getMonthValue(); }

    void load() {
        if (!file.exists()) return;
        YamlConfiguration y = SafeYaml.load(pl, file).yaml;
        forced = y.getString("forced", "auto");
        festival = y.getString("festival", "");
        for (String s : y.getStringList("gifted")) try { gifted.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("forced", forced);
        y.set("festival", festival);
        List<String> g = new ArrayList<>();
        for (UUID u : gifted) g.add(u.toString());
        y.set("gifted", g);
        try { SafeYaml.save(pl, y, file); } catch (IOException e) { pl.getLogger().warning("Couldn't save den_festival.yml: " + e.getMessage()); }
    }

    private void tick() {
        tick++;
        boolean on = active() && pl.world != null;
        if (on && !festival.equals(id())) { // a new festival: everyone can get a gift again
            festival = id();
            gifted.clear();
            save();
            Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "🎉 The Mermaid Den is holding its festival! " + ChatColor.GRAY
                    + "Every mermaid has a gift for you today (Dark Ocean, " + MermaidDen.MX + ", " + MermaidDen.MZ + ").");
        }
        if (!on) { if (bar != null) { bar.removeAll(); bar = null; } return; }
        if (bar == null) bar = Bukkit.createBossBar(ChatColor.LIGHT_PURPLE + "🎉 The Den Festival", BarColor.PINK, BarStyle.SOLID);
        Location c = new Location(pl.world, MermaidDen.MX + 0.5, MermaidDen.PLAZA + 6, MermaidDen.MZ + 0.5);
        for (Player p : pl.world.getPlayers()) {
            boolean near = Den.inside(p.getLocation(), 50);
            if (near && !bar.getPlayers().contains(p)) {
                bar.addPlayer(p);
                p.sendTitle(ChatColor.LIGHT_PURPLE + "The Den Festival", ChatColor.GRAY + (gifted.contains(p.getUniqueId()) ? "Enjoy the lights." : "The mermaids have a gift for you"), 10, 50, 15);
            } else if (!near && bar.getPlayers().contains(p)) bar.removePlayer(p);
        }
        bar.setTitle(ChatColor.LIGHT_PURPLE + "🎉 The Den Festival" + ChatColor.GRAY + " · right-click a mermaid for your gift");
        if (bar.getPlayers().isEmpty()) return;
        // lights over the lagoon: glowing bursts round the palace, notes, chimes
        if (tick % 2 == 0) for (int i = 0; i < 3; i++) {
            double a = random.nextDouble() * Math.PI * 2, r = 8 + random.nextDouble() * (MermaidDen.R - 20);
            Location l = c.clone().add(Math.cos(a) * r, 2 + random.nextDouble() * 18, Math.sin(a) * r);
            pl.world.spawnParticle(Particle.FIREWORK, l, 25, 0.1, 0.1, 0.1, 0.12);
            pl.world.spawnParticle(Particle.GLOW, l, 12, 1.2, 1.2, 1.2, 0);
        }
        if (tick % 3 == 0) pl.world.spawnParticle(Particle.NOTE, c.clone().add(random.nextGaussian() * 8, 3, random.nextGaussian() * 8), 3, 2, 1, 2, 1);
        if (tick % 8 == 0) {
            float pitch = new float[]{0.7f, 0.84f, 1.05f, 1.26f, 1.4f}[random.nextInt(5)];
            for (Player p : bar.getPlayers()) p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.AMBIENT, 0.9f, pitch);
        }
        if (tick % 40 == 0) for (Player p : bar.getPlayers()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.AMBIENT, 0.6f, 1.2f);
    }

    /** Right-clicked a resident: their festival gift, once per festival. True = handled (no trades this click). */
    boolean gift(Player p, DeepMobs.Beast m) {
        if (!active() || gifted.contains(p.getUniqueId()) || m.angry()) return false;
        if (pl.rep != null && pl.rep.tier(p.getUniqueId()) == DenRep.Tier.HATED) return false;
        gifted.add(p.getUniqueId());
        save();
        List<ItemStack> out = new ArrayList<>();
        ItemStack roll = roll();
        if (roll != null) out.add(roll);
        int pmin = pl.getConfig().getInt("den.festival.pearls-min", 2), pmax = Math.max(pmin, pl.getConfig().getInt("den.festival.pearls-max", 5));
        int pearls = pmin + random.nextInt(pmax - pmin + 1);
        if (pl.glimmer != null && pearls > 0) out.add(pl.glimmer.pearl(pearls));
        for (ItemStack it : out) p.getInventory().addItem(it).values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
        if (random.nextDouble() < pl.getConfig().getDouble("den.festival.bag-chance", 0.15)) pl.console("darkloot bag " + p.getName() + " 1");
        int rep = pl.getConfig().getInt("den.festival.rep", 5);
        if (pl.rep != null && rep != 0) pl.rep.add(p, rep, "(a festival gift)");
        pl.console("index stat " + p.getName() + " den_festivals 1");
        Location l = m.body.getLocation().add(0, 1.2, 0);
        l.getWorld().spawnParticle(Particle.HEART, l, 8, 0.4, 0.4, 0.4, 0);
        l.getWorld().spawnParticle(Particle.FIREWORK, l, 30, 0.2, 0.2, 0.2, 0.1);
        l.getWorld().playSound(l, Sound.ENTITY_ALLAY_ITEM_GIVEN, SoundCategory.NEUTRAL, 1f, 1.3f);
        p.sendMessage(ChatColor.LIGHT_PURPLE + "\"For the festival!\" " + ChatColor.GRAY + "She hands you "
                + (roll == null ? "" : roll.getAmount() + " " + nice(roll.getType()) + " and ") + pearls + " Glimmer Pearls.");
        return true;
    }

    static String nice(Material m) {
        StringBuilder b = new StringBuilder();
        for (String w : m.name().toLowerCase(Locale.ROOT).split("_")) b.append(b.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        return b.toString();
    }

    static final List<String> DEFAULT_GIFTS = List.of("PRISMARINE_CRYSTALS:4:12:30", "SEA_LANTERN:2:6:20", "NAUTILUS_SHELL:1:2:15",
            "GLOW_INK_SAC:4:10:15", "DIAMOND:1:3:10", "HEART_OF_THE_SEA:1:1:2", "TRIDENT:1:1:2", "SPONGE:2:4:6");

    ItemStack roll() {
        List<String> list = pl.getConfig().getStringList("den.festival.gifts");
        if (list.isEmpty()) list = DEFAULT_GIFTS;
        int total = 0;
        List<Object[]> parsed = new ArrayList<>();
        for (String s : list) {
            String[] p = s.split(":");
            Material mat = Material.matchMaterial(p[0].trim());
            if (mat == null || !mat.isItem()) continue;
            try {
                int min = Integer.parseInt(p[1].trim()), max = Integer.parseInt(p[2].trim()), w = Integer.parseInt(p[3].trim());
                if (w <= 0) continue;
                parsed.add(new Object[]{mat, min, Math.max(min, max), w});
                total += w;
            } catch (RuntimeException ignored) { }
        }
        if (total <= 0) return null;
        int r = random.nextInt(total);
        for (Object[] o : parsed) {
            r -= (int) o[3];
            if (r < 0) { int min = (int) o[1], max = (int) o[2]; return new ItemStack((Material) o[0], Math.min(64, min + random.nextInt(max - min + 1))); }
        }
        return null;
    }

    /** /deep den festival [start|stop|auto] */
    String admin(String what) {
        switch (what) {
            case "start", "on" -> forced = "on";
            case "stop", "off" -> forced = "off";
            case "auto" -> forced = "auto";
            default -> { return status(); }
        }
        save();
        return status();
    }

    String status() {
        return ChatColor.LIGHT_PURPLE + "Den Festival: " + ChatColor.WHITE + (active() ? "ON" : "off") + ChatColor.GRAY + " (mode " + forced + ", "
                + gifted.size() + " gifts given this festival; days " + pl.getConfig().getIntegerList("den.festival.days") + ")";
    }

    void shutdown() { if (bar != null) bar.removeAll(); save(); }
}
