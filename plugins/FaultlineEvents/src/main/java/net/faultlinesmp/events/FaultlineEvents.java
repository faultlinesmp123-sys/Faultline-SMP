package net.faultlinesmp.events;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * FaultlineEvents 2.0.0 (a rewrite; the old jar's source was never in the repo). Sky events in the main world:
 * the BLOOD MOON, FOG, LANTERN NIGHT and WINDY days. One at a time. Night events are rolled at dusk and end at dawn,
 * day events are rolled at dawn and run for their minutes. The running one is written on the main world as
 * {@code faultlineevents:active_event} (other plugins read it), with {@code faultlineevents:ends_at} for timed ones, so a
 * restart picks it back up.
 */
public final class FaultlineEvents extends JavaPlugin implements Listener, TabExecutor {

    static final NamespacedKey ACTIVE = NamespacedKey.fromString("faultlineevents:active_event");
    static final NamespacedKey ENDS = NamespacedKey.fromString("faultlineevents:ends_at");

    final Random random = new Random();
    final Map<String, Ev> events = new LinkedHashMap<>();
    Ev active;
    long endsAt;          // ms, 0 = at dawn
    long startedAt;
    long secondsLeft;     // timed events count down in server seconds (ends_at is only for restarts)
    private BossBar bar;
    private Boolean wasNight;
    long ticks;
    Realms realms; // 2.1.0: day and night in the Nether and the End

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (Ev e : List.of(new BloodMoon(this), new Fog(this), new LanternNight(this), new Windy(this))) {
            events.put(e.id(), e);
            getServer().getPluginManager().registerEvents(e, this);
        }
        getServer().getPluginManager().registerEvents(this, this);
        realms = new Realms(this);
        getServer().getPluginManager().registerEvents(realms, this);
        if (getCommand("fevent") != null) { getCommand("fevent").setExecutor(this); getCommand("fevent").setTabCompleter(this); }
        Bukkit.getScheduler().runTaskTimer(this, this::second, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> { try { realms.second(); } catch (RuntimeException e) { problem("realms", e); } }, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            ticks++;
            if (active != null) try { active.tick(ticks); } catch (RuntimeException e) { problem("tick", e); }
            realms.tick(ticks);
        }, 1L, 1L);
        Bukkit.getScheduler().runTask(this, this::resume);
    }

    @Override
    public void onDisable() {
        if (active != null) try { active.stop(false); } catch (RuntimeException ignored) { }
        if (bar != null) bar.removeAll();
        if (realms != null) try { realms.shutdown(); } catch (RuntimeException ignored) { }
        // the PDC stays: a restart resumes it
    }

    // ---------------------------------------------------------------- helpers

    World main() { return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0); }

    boolean eventWorld(World w) {
        if (w == null) return false;
        if (w.equals(main())) return true;
        return getConfig().getStringList("extra-worlds").contains(w.getName());
    }

    List<Player> players() {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (eventWorld(p.getWorld())) out.add(p);
        return out;
    }

    /** announcements: true = chat lines / titles / fanfare when events start, end and happen (2.1.1: off by default). */
    boolean loud() { return getConfig().getBoolean("announcements", false); }
    /** boss-bar: true = the event's boss bar (2.1.1: off by default; /fevent still tells what's on). */
    boolean showBar() { return getConfig().getBoolean("boss-bar", false); }
    /** A whole-server chat line, only when announcements are on. */
    void say(String msg) { if (loud()) Bukkit.broadcastMessage(msg); }

    static boolean survival(Player p) { return p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE; }

    static boolean night(World w) { long t = w.getTime(); return t >= 13000 && t < 23000; }

    void console(String cmd, Player p) {
        String c = cmd.replace("{player}", p.getName());
        if (c.startsWith("/")) c = c.substring(1);
        try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c); } catch (RuntimeException e) { getLogger().warning("command failed: " + c + " (" + e + ")"); }
    }

    void stat(Player p, String name, int n) {
        if (n <= 0 || Bukkit.getPluginManager().getPlugin("FaultlineIndex") == null) return;
        console("index stat {player} " + name + " " + n, p);
    }

    private final Map<String, Long> warned = new java.util.HashMap<>();
    void problem(String where, Throwable e) {
        long now = System.currentTimeMillis();
        if (now - warned.getOrDefault(where, 0L) < 60_000) return;
        warned.put(where, now);
        getLogger().warning("[" + (active == null ? "-" : active.id()) + "] " + where + ": " + e);
    }

    // ---------------------------------------------------------------- the clock

    private void resume() {
        World w = main();
        if (w == null) return;
        wasNight = night(w);
        PersistentDataContainer pdc = w.getPersistentDataContainer();
        String id = pdc.get(ACTIVE, PersistentDataType.STRING);
        if (id == null) return;
        Ev e = events.get(id);
        Long ends = pdc.get(ENDS, PersistentDataType.LONG);
        boolean ok = e != null && e.enabled() && (e.night() ? night(w) : ends != null && ends > System.currentTimeMillis());
        if (!ok) { pdc.remove(ACTIVE); pdc.remove(ENDS); return; }
        begin(e, ends == null ? 0 : ends, false);
        getLogger().info("Resumed " + e.title());
    }

    private void second() {
        World w = main();
        if (w == null) return;
        boolean n = night(w);
        try {
            if (active != null) {
                if (!active.night()) { secondsLeft--; endsAt = System.currentTimeMillis() + secondsLeft * 1000L; }
                boolean over = active.night() ? !n : secondsLeft <= 0;
                if (over) { end(true); }
                else { active.second(); updateBar(); }
            } else if (wasNight != null && n != wasNight && getConfig().getBoolean("enabled", true)) {
                roll(n ? "dusk" : "dawn");
            }
        } catch (RuntimeException e) { problem("second", e); }
        wasNight = n;
    }

    private void roll(String when) {
        World w = main();
        long survivors = Bukkit.getOnlinePlayers().stream().filter(p -> eventWorld(p.getWorld()) && survival(p)).count();
        if (survivors < getConfig().getInt("min-players", 1)) return;
        ConfigurationSection s = getConfig().getConfigurationSection("schedule." + when);
        if (s == null) return;
        long day = w.getFullTime() / 24000;
        int cooldown = getConfig().getInt("schedule.cooldown-days", 2);
        for (String key : s.getKeys(false)) {
            Ev e = byKey(key);
            if (e == null || !e.enabled()) continue;
            Long last = w.getPersistentDataContainer().get(lastKey(e), PersistentDataType.LONG);
            if (last != null && day - last < cooldown && day >= last) continue;
            if (random.nextDouble() < s.getDouble(key)) { start(e); return; }
        }
    }

    Ev byKey(String key) {
        for (Ev e : events.values()) if (e.key().equalsIgnoreCase(key) || e.id().equalsIgnoreCase(key)) return e;
        return null;
    }

    private NamespacedKey lastKey(Ev e) { return new NamespacedKey(this, "last_" + e.key().replace('-', '_')); }

    void start(Ev e) {
        if (active != null) end(false);
        World w = main();
        long ends = e.night() ? 0 : System.currentTimeMillis() + e.minutes() * 60_000L;
        w.getPersistentDataContainer().set(lastKey(e), PersistentDataType.LONG, w.getFullTime() / 24000);
        begin(e, ends, true);
        getLogger().info(e.title() + " started");
    }

    private void begin(Ev e, long ends, boolean announce) {
        active = e;
        endsAt = ends;
        startedAt = System.currentTimeMillis();
        secondsLeft = ends > 0 ? Math.max(1, (ends - startedAt) / 1000) : 0;
        World w = main();
        w.getPersistentDataContainer().set(ACTIVE, PersistentDataType.STRING, e.id());
        if (ends > 0) w.getPersistentDataContainer().set(ENDS, PersistentDataType.LONG, ends);
        else w.getPersistentDataContainer().remove(ENDS);
        if (showBar()) {
            bar = Bukkit.createBossBar(e.color() + e.title(), e.barColor(), BarStyle.SEGMENTED_10);
            for (Player p : players()) bar.addPlayer(p);
        }
        try { e.start(announce); } catch (RuntimeException ex) { problem("start", ex); }
        updateBar();
    }

    void end(boolean natural) {
        Ev e = active;
        if (e == null) return;
        try { e.stop(natural); } catch (RuntimeException ex) { problem("stop", ex); }
        active = null;
        endsAt = 0;
        World w = main();
        if (w != null) { w.getPersistentDataContainer().remove(ACTIVE); w.getPersistentDataContainer().remove(ENDS); }
        if (bar != null) { bar.removeAll(); bar = null; }
        getLogger().info(e.title() + (natural ? " ended" : " stopped"));
    }

    private void updateBar() {
        if (bar == null || active == null) return;
        double prog;
        String left;
        if (active.night()) {
            long t = main().getTime();
            prog = Math.max(0, Math.min(1, (23000 - t) / 10000.0));
            left = "until dawn";
        } else {
            long ms = Math.max(0, secondsLeft * 1000L);
            prog = Math.max(0, Math.min(1, ms / (active.minutes() * 60_000.0)));
            left = (ms / 60000) + ":" + String.format("%02d", (ms / 1000) % 60) + " left";
        }
        bar.setProgress(prog);
        bar.setTitle(active.color() + "" + ChatColor.BOLD + active.title() + ChatColor.GRAY + " · " + active.barText() + ChatColor.DARK_GRAY + " · " + left);
        for (Player p : players()) if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
        for (Player p : new ArrayList<>(bar.getPlayers())) if (!p.isOnline() || !eventWorld(p.getWorld())) bar.removePlayer(p);
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) { if (active != null) { active.joined(e.getPlayer()); updateBar(); } }
    @EventHandler public void onQuit(PlayerQuitEvent e) { if (active != null) active.left(e.getPlayer()); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) {
        if (active == null) return;
        if (eventWorld(e.getPlayer().getWorld())) active.joined(e.getPlayer()); else active.left(e.getPlayer());
        updateBar();
    }

    // ---------------------------------------------------------------- /fevent

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        boolean admin = s.hasPermission("faultlineevents.admin");
        String sub = a.length == 0 ? "status" : a[0].toLowerCase();
        switch (sub) {
            case "list" -> {
                s.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Faultline Events");
                for (Ev e : events.values()) {
                    TextComponent line = new TextComponent(e.color() + "" + ChatColor.BOLD + e.title() + ChatColor.GRAY + " (" + (e.night() ? "a whole night" : e.minutes() + " min by day") + ")" + (e.enabled() ? "" : ChatColor.RED + " [off]") + ChatColor.GRAY + ": " + e.blurb());
                    if (admin && s instanceof Player) {
                        TextComponent b = new TextComponent(ChatColor.GREEN + " [START]");
                        b.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/fevent start " + e.key()));
                        b.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("Start " + e.title() + " now")));
                        line.addExtra(b);
                    }
                    s.spigot().sendMessage(line);
                }
                s.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "The Nether" + ChatColor.GRAY + " and " + ChatColor.LIGHT_PURPLE + ChatColor.BOLD + "the End" + ChatColor.GRAY
                        + " have their own day and night. Nights there can bring an Ember Storm or a Soul Tide (Nether), a Void Storm or a Starfall (End). Sleep through one in a Blue Obsidian Bed.");
            }
            case "start" -> {
                if (!admin) { s.sendMessage(ChatColor.RED + "You don't have permission."); return true; }
                Ev e = a.length > 1 ? byKey(a[1]) : null;
                if (e == null) { s.sendMessage(ChatColor.YELLOW + "/fevent start <" + String.join("|", keys()) + ">"); return true; }
                World w = main();
                if (e.night() && !night(w)) {
                    if (!getConfig().getBoolean("admin-start-sets-night", true)) { s.sendMessage(ChatColor.RED + e.title() + " is a night event: wait for dusk."); return true; }
                    w.setTime(13000);
                }
                wasNight = night(w);
                start(e);
                s.sendMessage(ChatColor.GREEN + e.title() + " started.");
            }
            case "stop" -> {
                if (!admin) { s.sendMessage(ChatColor.RED + "You don't have permission."); return true; }
                if (active == null) { s.sendMessage(ChatColor.GRAY + "No event is running."); return true; }
                String t = active.title();
                end(false);
                s.sendMessage(ChatColor.GREEN + t + " stopped.");
            }
            case "champion" -> {
                if (!admin) { s.sendMessage(ChatColor.RED + "You don't have permission."); return true; }
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : s instanceof Player p ? p : null;
                if (t == null) { s.sendMessage(ChatColor.YELLOW + "/fevent champion [player]"); return true; }
                BloodMoon bm = (BloodMoon) events.get("BLOOD_MOON");
                Location at = bm.championSpot(t);
                if (at == null) at = t.getLocation().add(t.getLocation().getDirection().setY(0).normalize().multiply(6));
                bm.spawnChampion(at, t);
                s.sendMessage(ChatColor.DARK_RED + "The Crimson Champion walks.");
            }
            case "realm" -> {
                if (!admin) { s.sendMessage(ChatColor.RED + "You don't have permission."); return true; }
                if (a.length < 3) { s.sendMessage(ChatColor.YELLOW + "/fevent realm <nether|end> <day|night|ember-storm|soul-tide|void-storm|starfall>"); return true; }
                String why = realms.force(a[1], a[2]);
                s.sendMessage(why == null ? ChatColor.GREEN + "Done." : ChatColor.YELLOW + why);
            }
            default -> {
                String realm = s instanceof Player p ? realms.status(p.getWorld()) : null;
                if (realm != null) { s.sendMessage(realm); if (admin) s.sendMessage(ChatColor.DARK_GRAY + "/fevent realm <nether|end> <day|night|event>"); return true; }
                if (active == null) {
                    s.sendMessage(ChatColor.GRAY + "No event right now. One might start at the next dusk or dawn. " + ChatColor.DARK_GRAY + "(/fevent list)");
                } else {
                    s.sendMessage(active.color() + "" + ChatColor.BOLD + active.title() + ChatColor.GRAY + " is on: " + active.blurb());
                    if (!active.night()) s.sendMessage(ChatColor.GRAY + "Ends in " + Math.max(1, (secondsLeft + 59) / 60) + " min.");
                    else s.sendMessage(ChatColor.GRAY + "It lasts until dawn.");
                }
                if (admin) s.sendMessage(ChatColor.DARK_GRAY + "/fevent list | start <event> | stop | champion [player] | realm <nether|end> <day|night|event>");
            }
        }
        return true;
    }

    private List<String> keys() { List<String> k = new ArrayList<>(); for (Ev e : events.values()) k.add(e.key()); return k; }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> opts = new ArrayList<>();
        if (a.length == 1) { opts.add("list"); if (s.hasPermission("faultlineevents.admin")) { opts.add("start"); opts.add("stop"); opts.add("champion"); opts.add("realm"); } }
        else if (a.length == 2 && a[0].equalsIgnoreCase("start")) opts.addAll(keys());
        else if (a.length == 2 && a[0].equalsIgnoreCase("realm")) { opts.add("nether"); opts.add("end"); }
        else if (a.length == 3 && a[0].equalsIgnoreCase("realm")) { opts.add("day"); opts.add("night"); opts.addAll(a[1].equalsIgnoreCase("end") ? Realms.END_EVENTS : Realms.NETHER_EVENTS); }
        else if (a.length == 2 && a[0].equalsIgnoreCase("champion")) for (Player p : Bukkit.getOnlinePlayers()) opts.add(p.getName());
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase();
        List<String> out = new ArrayList<>();
        for (String o : opts) if (o.toLowerCase().startsWith(last)) out.add(o);
        Collections.sort(out);
        return out;
    }

    static Material material(String name) { try { return Material.valueOf(name.trim().toUpperCase()); } catch (RuntimeException e) { return null; } }
}
