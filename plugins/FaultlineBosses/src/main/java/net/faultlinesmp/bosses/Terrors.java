package net.faultlinesmp.bosses;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * THE NIGHT OF TERRORS (Bosses 1.8.0, `terrors:`, `terrors.yml`): one night a year (`date` 10-31, in `zone`
 * America/New_York) every boss can turn up on its own. From dusk (main world time `start-time` 13000) to dawn: every
 * `spawn-seconds` (120) with no boss out, a random boss from `bosses` comes for a random survival player in the main
 * world's Overworld (`rushSummon`, the Boss Rush's own summons: their normal loot), up to `max-bosses` (8) in the night.
 * The main world carries the PDC flag `faultlinebosses:terrors` while it lasts: FaultlineCosmetics' mobs drop five times
 * the Candy. Once a year (the year is saved); admins `/terrors [start|stop|status]` any time.
 */
final class Terrors implements TabExecutor {
    static final NamespacedKey FLAG = new NamespacedKey("faultlinebosses", "terrors");
    static final List<String> DEFAULT = List.of("demoneye", "frostbeard", "dune", "frostmaw", "don", "jacob", "rocco", "sandworm", "lich", "frostwyrm", "golem");
    final FaultlineBosses pl;
    final Random random = new Random();
    final File file;
    boolean active, forced;
    int spawned, secs, lastYear;
    BossBar bar;

    Terrors(FaultlineBosses pl) {
        this.pl = pl;
        file = new File(pl.getDataFolder(), "terrors.yml");
        lastYear = YamlConfiguration.loadConfiguration(file).getInt("year", 0);
        World w = main();
        if (w != null) w.getPersistentDataContainer().remove(FLAG); // a crash mid-night never leaves it on
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20L, 20L);
    }

    double cfg(String k, double def) { return pl.getConfig().getDouble("terrors." + k, def); }

    static World main() { return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0); }

    boolean night(World w) { long t = w.getTime(); return t >= cfg("start-time", 13000) && t < 23500; }

    boolean today() {
        ZoneId zone;
        try { zone = ZoneId.of(pl.getConfig().getString("terrors.zone", "America/New_York")); } catch (RuntimeException e) { zone = ZoneId.of("America/New_York"); }
        ZonedDateTime now = ZonedDateTime.now(zone);
        String want = pl.getConfig().getString("terrors.date", "10-31");
        return String.format("%02d-%02d", now.getMonthValue(), now.getDayOfMonth()).equals(want);
    }

    void tick() {
        secs++;
        World w = main();
        if (w == null) return;
        if (!active) {
            if (pl.getConfig().getBoolean("terrors.enabled", true) && secs % 30 == 0 && today() && night(w) && ZonedDateTime.now().getYear() != lastYear) start(false);
            return;
        }
        if (!forced && !night(w)) { stop(true); return; }
        if (!forced && w.getTime() >= 13000) bar.setProgress(Math.max(0, Math.min(1, (23500 - w.getTime()) / 10500.0)));
        for (Player p : w.getPlayers()) bar.addPlayer(p);
        if (secs % 3 == 0) for (Player p : w.getPlayers()) {
            Location l = p.getLocation();
            p.spawnParticle(Particle.DUST, l.clone().add(0, 2, 0), 6, 6, 3, 6, 0, new Particle.DustOptions(org.bukkit.Color.fromRGB(160, 20, 20), 1.6f));
        }
        if (secs % (int) Math.max(20, cfg("spawn-seconds", 120)) != 0 || spawned >= cfg("max-bosses", 8) || pl.bossOutName() != null) return;
        List<Player> fair = new ArrayList<>();
        for (Player p : w.getPlayers()) if ((p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) && !p.isDead()) fair.add(p);
        if (fair.isEmpty()) return;
        Player target = fair.get(random.nextInt(fair.size()));
        List<String> pool = new ArrayList<>(pl.getConfig().getStringList("terrors.bosses"));
        if (pool.isEmpty()) pool.addAll(DEFAULT);
        Collections.shuffle(pool, random);
        for (String kind : pool) {
            if (pl.rushSummon(kind, target.getLocation(), target)) {
                spawned++;
                for (Player p : w.getPlayers()) {
                    p.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "[Night of Terrors] " + ChatColor.RED + pl.bossOutName() + ChatColor.GRAY + " has come for " + target.getName() + "!");
                    p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, SoundCategory.HOSTILE, 0.5f, 0.6f);
                }
                return;
            }
        }
    }

    boolean start(boolean admin) {
        World w = main();
        if (active || w == null) return false;
        active = true;
        forced = admin;
        spawned = 0;
        if (!admin) {
            lastYear = ZonedDateTime.now().getYear();
            YamlConfiguration y = new YamlConfiguration();
            y.set("year", lastYear);
            try { y.save(file); } catch (IOException e) { pl.getLogger().warning("Couldn't save terrors.yml: " + e.getMessage()); }
        }
        w.getPersistentDataContainer().set(FLAG, PersistentDataType.BYTE, (byte) 1);
        bar = Bukkit.createBossBar(ChatColor.DARK_RED + "" + ChatColor.BOLD + "THE NIGHT OF TERRORS", BarColor.RED, BarStyle.SEGMENTED_20);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(ChatColor.DARK_RED + "" + ChatColor.BOLD + "THE NIGHT OF TERRORS", ChatColor.GRAY + "every boss walks tonight", 10, 80, 30);
            p.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "The Night of Terrors has begun! " + ChatColor.GRAY
                    + "Until dawn any boss can come for anyone in the Overworld, and monsters drop five times the Candy.");
            p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.AMBIENT, 1f, 0.5f);
            p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, SoundCategory.AMBIENT, 1f, 0.5f);
        }
        return true;
    }

    void stop(boolean dawn) {
        if (!active) return;
        active = false;
        World w = main();
        if (w != null) w.getPersistentDataContainer().remove(FLAG);
        if (bar != null) bar.removeAll();
        bar = null;
        for (Player p : Bukkit.getOnlinePlayers())
            p.sendMessage(ChatColor.GOLD + (dawn ? "Dawn breaks. The Night of Terrors is over" : "The Night of Terrors is over") + ChatColor.GRAY + " (" + spawned + " boss" + (spawned == 1 ? "" : "es") + " came).");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("bosses.admin")) { sender.sendMessage(ChatColor.RED + "You don't have permission to do that."); return true; }
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        switch (sub) {
            case "start" -> sender.sendMessage(start(true) ? ChatColor.GREEN + "The Night of Terrors begins (until /terrors stop)." : ChatColor.RED + "It's already on.");
            case "stop" -> { stop(false); sender.sendMessage(ChatColor.GREEN + "Stopped."); }
            default -> sender.sendMessage(ChatColor.RED + "Night of Terrors: " + ChatColor.GRAY + (active ? "ON, " + spawned + "/" + (int) cfg("max-bosses", 8) + " bosses so far" : "off")
                    + ". Every " + pl.getConfig().getString("terrors.date", "10-31") + " night (" + pl.getConfig().getString("terrors.zone", "America/New_York") + ")"
                    + (lastYear > 0 ? ", last in " + lastYear : "") + ". " + ChatColor.YELLOW + "/terrors [start|stop|status]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>(List.of("start", "stop", "status"));
        if (args.length == 1) out.removeIf(s -> !s.startsWith(args[0].toLowerCase()));
        else out.clear();
        return out;
    }
}
