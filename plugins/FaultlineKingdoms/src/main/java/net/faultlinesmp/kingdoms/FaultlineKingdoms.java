package net.faultlinesmp.kingdoms;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FAULTLINE KINGDOMS: every player is in Kingdom 1, 2, 3 or none.
 *  - Kingdoms come from DISCORD ROLES (DiscordLink): a player links their Discord account once (/kingdom link, a code
 *    DM'd by the bot, /kingdom verify), then their kingdom follows their role: checked on join and every
 *    discord.sync-minutes. Admins can pin someone by hand (/kingdoms set <player> <1|2|3|none>; "auto" un-pins).
 *  - GRACE PERIODS: /kingdoms grace all on [time] = no PvP at all; /kingdoms grace <1|2|3> on [time] = nobody can hurt
 *    that kingdom's members (and, with grace.protected-can-attack false, they can't hurt anyone either). Timed ones end
 *    on their own (announced); all of it survives restarts (data.yml).
 *  - pvp.friendly-fire false = members of one kingdom can't hurt each other.
 * Other plugins: FaultlineKingdoms.kingdomOf(uuid) (0 = none), FaultlineKingdoms.protectedNow(player).
 */
public final class FaultlineKingdoms extends JavaPlugin implements Listener {

    static FaultlineKingdoms instance;

    static final class Member {
        String name;
        int kingdom;      // 0 = none
        String discordId; // linked Discord account, or null
        boolean manual;   // set by an admin: role sync leaves it alone
        Boolean ffVote;   // their friendly-fire vote for their kingdom (null = none)
        final Map<Integer, Boolean> allyVotes = new java.util.concurrent.ConcurrentHashMap<>(); // 1.4.0: their treaty vote per other kingdom
    }

    final Map<UUID, Member> members = new java.util.concurrent.ConcurrentHashMap<>(); // read by the Discord calls off the main thread
    /** "all", "1", "2", "3" -> until (epoch ms), or -1 = until turned off. Missing = off. */
    final Map<String, Long> grace = new LinkedHashMap<>();
    /** A kingdom's friendly fire set by an admin (overrides the vote): "1".."3" -> on/off. */
    final Map<String, Boolean> ffForced = new LinkedHashMap<>();
    File dataFile;
    DiscordLink discord;
    DiscordGateway gateway;
    final KingdomForms forms = new KingdomForms(this);
    HeadTags headTags;
    KingdomChat chat;
    /** 1.4.0 treaties: "1-2" -> forced on/off by staff (else the vote). */
    final Map<String, Boolean> treatyForced = new LinkedHashMap<>();
    /** Treaties in force now ("1-2", "1-3", "2-3"), and when one was broken (PvP stays off a few minutes after). */
    final java.util.Set<String> treaties = new java.util.HashSet<>();
    final Map<String, Long> brokenAt = new HashMap<>();
    private final Map<UUID, Long> warned = new HashMap<>();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        headTags = new HeadTags(this); // 1.3.0: the kingdom (or "Outlaw") above everyone's name
        getServer().getPluginManager().registerEvents(headTags, this);
        chat = new KingdomChat(this); // 1.4.0: /kc, /ac, kingdom Discord channels
        getServer().getPluginManager().registerEvents(chat, this);
        checkTreaties(false);
        discord = new DiscordLink(this);
        gateway = new DiscordGateway(this, discord);
        gateway.start();
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        for (Player p : Bukkit.getOnlinePlayers()) { member(p); refreshName(p); }
        getLogger().info("Faultline Kingdoms enabled: " + members.size() + " players known, Discord sync " + (discord.on() ? "ON" : "OFF (set discord.bot-token / guild-id)") + ".");
    }

    @Override
    public void onDisable() {
        if (headTags != null) try { headTags.removeAll(); } catch (RuntimeException ignored) { }
        if (gateway != null) gateway.stop();
        if (chat != null) chat.stop();
        save();
    }

    // ================================================================= data

    void load() {
        members.clear(); grace.clear(); ffForced.clear(); treatyForced.clear(); treaties.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection ps = y.getConfigurationSection("players");
        if (ps != null) for (String id : ps.getKeys(false)) {
            try {
                Member m = new Member();
                m.name = ps.getString(id + ".name", "?");
                m.kingdom = ps.getInt(id + ".kingdom", 0);
                m.discordId = ps.getString(id + ".discord");
                m.manual = ps.getBoolean(id + ".manual", false);
                if (ps.isBoolean(id + ".ff-vote")) m.ffVote = ps.getBoolean(id + ".ff-vote");
                ConfigurationSection av = ps.getConfigurationSection(id + ".ally-votes");
                if (av != null) for (String k : av.getKeys(false)) try { m.allyVotes.put(Integer.parseInt(k), av.getBoolean(k)); } catch (NumberFormatException ignored) { }
                members.put(UUID.fromString(id), m);
            } catch (IllegalArgumentException ignored) { }
        }
        ConfigurationSection g = y.getConfigurationSection("grace");
        if (g != null) for (String k : g.getKeys(false)) grace.put(k, g.getLong(k));
        ConfigurationSection ff = y.getConfigurationSection("ff-forced");
        if (ff != null) for (String k : ff.getKeys(false)) ffForced.put(k, ff.getBoolean(k));
        ConfigurationSection tf = y.getConfigurationSection("treaty-forced");
        if (tf != null) for (String k : tf.getKeys(false)) treatyForced.put(k, tf.getBoolean(k));
        treaties.addAll(y.getStringList("treaties"));
    }

    synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : members.entrySet()) {
            String b = "players." + e.getKey() + ".";
            Member m = e.getValue();
            y.set(b + "name", m.name);
            y.set(b + "kingdom", m.kingdom);
            if (m.discordId != null) y.set(b + "discord", m.discordId);
            if (m.manual) y.set(b + "manual", true);
            if (m.ffVote != null) y.set(b + "ff-vote", m.ffVote);
            for (var v : m.allyVotes.entrySet()) y.set(b + "ally-votes." + v.getKey(), v.getValue());
        }
        for (var e : grace.entrySet()) y.set("grace." + e.getKey(), e.getValue());
        for (var e : ffForced.entrySet()) y.set("ff-forced." + e.getKey(), e.getValue());
        for (var e : treatyForced.entrySet()) y.set("treaty-forced." + e.getKey(), e.getValue());
        y.set("treaties", new ArrayList<>(treaties));
        try { y.save(dataFile); } catch (IOException ex) { getLogger().warning("Couldn't save data.yml: " + ex.getMessage()); }
    }

    Member member(Player p) {
        Member m = members.computeIfAbsent(p.getUniqueId(), k -> new Member());
        m.name = p.getName();
        return m;
    }

    public static int kingdomOf(UUID id) {
        if (instance == null) return 0;
        Member m = instance.members.get(id);
        return m == null ? 0 : m.kingdom;
    }

    /** The player's kingdom name ("Kingdom 1"), or null for none (LimboBlackMarket reads this by reflection). */
    public static String kingdomName(UUID id) {
        int k = kingdomOf(id);
        return instance == null || k == 0 ? null : instance.name(k);
    }

    String name(int k) { return k < 1 || k > 3 ? "No kingdom" : getConfig().getString("kingdoms." + k + ".name", "Kingdom " + k); }

    ChatColor color(int k) {
        if (k < 1 || k > 3) return ChatColor.GRAY;
        try { return ChatColor.valueOf(getConfig().getString("kingdoms." + k + ".color", "WHITE").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return ChatColor.WHITE; }
    }

    String label(int k) { return color(k) + name(k) + ChatColor.RESET; }

    /** Puts a player in a kingdom (0 = none), tells them, updates their tab name. */
    void setKingdom(UUID id, int k, boolean manual, String why) {
        Member m = members.computeIfAbsent(id, x -> new Member());
        boolean changed = m.kingdom != k;
        boolean ffBefore = k > 0 && friendlyFire(k), ffOldBefore = m.kingdom > 0 && friendlyFire(m.kingdom);
        int old = m.kingdom;
        if (changed) { m.ffVote = null; m.allyVotes.clear(); } // votes are for the kingdom you were in
        m.kingdom = k;
        m.manual = manual;
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            m.name = p.getName();
            refreshName(p);
            if (changed) p.sendMessage(k == 0 ? ChatColor.GRAY + "You're not in a kingdom any more" + (why == null ? "." : " (" + why + ").")
                    : ChatColor.GOLD + "You're in " + label(k) + ChatColor.GOLD + "!" + (why == null ? "" : ChatColor.GRAY + " (" + why + ")"));
        }
        if (changed) getLogger().info((m.name == null ? id.toString() : m.name) + " -> " + name(k) + (why == null ? "" : " (" + why + ")"));
        if (changed && old > 0) ffChanged(old, ffOldBefore);
        if (changed && k > 0) ffChanged(k, ffBefore);
        if (changed) checkTreaties(true);
        save();
    }

    void refreshName(Player p) {
        if (headTags != null) try { headTags.refresh(p); } catch (RuntimeException ex) { getLogger().warning("head tag for " + p.getName() + ": " + ex); }
        if (!getConfig().getBoolean("display.tab-prefix", true)) return;
        int k = kingdomOf(p.getUniqueId());
        String tab = k > 0 ? color(k) + "[" + name(k) + "] " + ChatColor.RESET + p.getName()
                : getConfig().getBoolean("display.tab-outlaw", true) && headTags != null ? headTags.line(p.getUniqueId()) + " " + ChatColor.RESET + p.getName() : null;
        try { p.setPlayerListName(tab); } catch (RuntimeException ignored) { }
    }

    // ================================================================= grace

    boolean graceOn(String key) {
        Long until = grace.get(key);
        return until != null && (until < 0 || until > System.currentTimeMillis());
    }

    /** Is this player protected right now (server-wide grace, or their kingdom's)? */
    public static boolean protectedNow(Player p) {
        if (instance == null) return false;
        int k = kingdomOf(p.getUniqueId());
        return instance.graceOn("all") || (k > 0 && instance.graceOn(String.valueOf(k)));
    }

    String graceName(String key) { return key.equals("all") ? "everyone" : label(Integer.parseInt(key)); }

    void setGrace(String key, long until, CommandSender by) {
        if (until == 0) grace.remove(key); else grace.put(key, until);
        save();
        String who = graceName(key);
        if (until == 0) Bukkit.broadcastMessage(ChatColor.RED + "⚔ The grace period for " + who + ChatColor.RED + " is over. PvP is on!");
        else Bukkit.broadcastMessage(ChatColor.GREEN + "🛡 Grace period for " + who + ChatColor.GREEN + (until < 0 ? " (until it's turned off)" : " for " + duration(until - System.currentTimeMillis()))
                + ": " + (key.equals("all") ? "nobody can hurt anybody." : "nobody can hurt them" + (getConfig().getBoolean("grace.protected-can-attack", false) ? "." : ", and they can't hurt anyone.")));
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (now / 1000 % 10 == 0) checkTreaties(true);
        for (String key : new ArrayList<>(grace.keySet())) {
            long until = grace.get(key);
            if (until > 0 && until <= now) setGrace(key, 0, null);
        }
        if (getConfig().getBoolean("grace.show-actionbar", true) && now / 1000 % 2 == 0) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!protectedNow(p)) continue;
                String key = graceOn("all") ? "all" : String.valueOf(kingdomOf(p.getUniqueId()));
                long until = grace.getOrDefault(key, 0L);
                p.sendActionBar(ChatColor.GREEN + "🛡 Grace period" + (until > 0 ? ChatColor.GRAY + " · " + duration(until - now) + " left" : ""));
            }
        }
    }

    static final Pattern TIME = Pattern.compile("(\\d+)\\s*([dhms])");

    /** "1h30m" -> ms; 0 if it can't be read. */
    static long parseTime(String s) {
        Matcher m = TIME.matcher(s.toLowerCase(Locale.ROOT));
        long ms = 0;
        int end = 0;
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            ms += n * switch (m.group(2)) { case "d" -> 86_400_000L; case "h" -> 3_600_000L; case "m" -> 60_000L; default -> 1000L; };
            end = m.end();
        }
        return end == 0 ? 0 : ms;
    }

    static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        long d = s / 86400, h = s / 3600 % 24, m = s / 60 % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + (s % 60) + "s";
        return s + "s";
    }

    // ================================================================= friendly fire (voted per kingdom)

    /** [yes, no] votes of a kingdom's current members. */
    int[] ffVotes(int k) {
        int yes = 0, no = 0;
        for (Member m : members.values()) if (m.kingdom == k && m.ffVote != null) { if (m.ffVote) yes++; else no++; }
        return new int[]{yes, no};
    }

    /**
     * Can members of kingdom k hurt each other? An admin's setting (/kingdoms ff) wins; else, with voting on, the majority
     * of the kingdom's votes once at least pvp.friendly-fire-vote.min-votes have voted (a tie keeps the default); else
     * pvp.friendly-fire.
     */
    boolean friendlyFire(int k) {
        Boolean forced = ffForced.get(String.valueOf(k));
        if (forced != null) return forced;
        boolean def = getConfig().getBoolean("pvp.friendly-fire", true);
        if (!getConfig().getBoolean("pvp.friendly-fire-vote.enabled", true)) return def;
        int[] v = ffVotes(k);
        if (v[0] + v[1] < Math.max(1, getConfig().getInt("pvp.friendly-fire-vote.min-votes", 3)) || v[0] == v[1]) return def;
        return v[0] > v[1];
    }

    String ffWhy(int k) {
        if (ffForced.containsKey(String.valueOf(k))) return "set by staff";
        if (!getConfig().getBoolean("pvp.friendly-fire-vote.enabled", true)) return "server setting";
        int[] v = ffVotes(k);
        int min = Math.max(1, getConfig().getInt("pvp.friendly-fire-vote.min-votes", 3));
        if (v[0] + v[1] < min) return v[0] + v[1] + "/" + min + " votes needed, server default";
        return v[0] + " on, " + v[1] + " off" + (v[0] == v[1] ? ", a tie: server default" : "");
    }

    /** Tells a kingdom's online members when their friendly fire flips. */
    void ffChanged(int k, boolean before) {
        boolean now = friendlyFire(k);
        if (now == before) return;
        String line = ChatColor.GOLD + "⚔ " + label(k) + ChatColor.GOLD + ": friendly fire is now "
                + (now ? ChatColor.RED + "ON" + ChatColor.GOLD + " (you can hurt each other)" : ChatColor.GREEN + "OFF" + ChatColor.GOLD + " (you can't hurt each other)")
                + ChatColor.GRAY + " (" + ffWhy(k) + ")";
        for (Player p : Bukkit.getOnlinePlayers()) if (kingdomOf(p.getUniqueId()) == k) p.sendMessage(line);
    }

    /** /kingdom ff [on|off|clear]: see or cast your vote. */
    void ffCommand(Player p, String what) {
        Member m = member(p);
        int k = m.kingdom;
        if (k == 0) { msg(p, ChatColor.RED + "You're not in a kingdom."); return; }
        if (what != null) {
            if (!getConfig().getBoolean("pvp.friendly-fire-vote.enabled", true)) { msg(p, ChatColor.RED + "Friendly fire voting is turned off."); return; }
            Boolean v = switch (what) { case "on", "yes", "true" -> Boolean.TRUE; case "off", "no", "false" -> Boolean.FALSE; default -> null; };
            if (v == null && !what.equals("clear")) { msg(p, ChatColor.RED + "/kingdom ff <on|off|clear>"); return; }
            boolean before = friendlyFire(k);
            m.ffVote = v;
            save();
            msg(p, v == null ? ChatColor.GRAY + "Your vote is cleared." : ChatColor.GREEN + "You voted friendly fire " + (v ? "ON" : "OFF") + " for " + label(k) + ChatColor.GREEN + ".");
            ffChanged(k, before);
        }
        boolean on = friendlyFire(k);
        msg(p, ChatColor.GOLD + "Friendly fire in " + label(k) + ChatColor.GOLD + ": " + (on ? ChatColor.RED + "ON" : ChatColor.GREEN + "OFF")
                + ChatColor.GRAY + " (" + ffWhy(k) + ")" + ChatColor.GRAY + " · your vote: "
                + (m.ffVote == null ? "none" : m.ffVote ? "on" : "off") + ChatColor.DARK_GRAY + " · /kingdom ff <on|off|clear>");
    }

    // ================================================================= treaties (1.4.0)

    static String pair(int a, int b) { return Math.min(a, b) + "-" + Math.max(a, b); }

    /** Are kingdoms a and b allied right now? */
    public boolean allied(int a, int b) { return a > 0 && b > 0 && a != b && treaties.contains(pair(a, b)); }

    /** For other plugins: are these two players in allied kingdoms? */
    public static boolean allies(UUID x, UUID y) { return instance != null && instance.allied(kingdomOf(x), kingdomOf(y)); }

    /** How long PvP stays off after a treaty between a and b ended (treaties.break-grace-minutes). */
    long treatyCooldown(int a, int b) {
        if (a <= 0 || b <= 0 || a == b) return 0;
        Long at = brokenAt.get(pair(a, b));
        if (at == null) return 0;
        long left = at + getConfig().getLong("treaties.break-grace-minutes", 5) * 60_000L - System.currentTimeMillis();
        return Math.max(0, left);
    }

    /** [yes, no] votes in kingdom a about an alliance with b. */
    int[] allyVotes(int a, int b) {
        int yes = 0, no = 0;
        for (Member m : members.values()) if (m.kingdom == a) { Boolean v = m.allyVotes.get(b); if (v != null) { if (v) yes++; else no++; } }
        return new int[]{yes, no};
    }

    /** Does kingdom a want an alliance with b? At least treaties.min-votes voted and more yes than no. */
    boolean wants(int a, int b) {
        int[] v = allyVotes(a, b);
        return v[0] + v[1] >= Math.max(1, getConfig().getInt("treaties.min-votes", 3)) && v[0] > v[1];
    }

    boolean treatyWanted(int a, int b) {
        if (!getConfig().getBoolean("treaties.enabled", true)) return false;
        Boolean forced = treatyForced.get(pair(a, b));
        if (forced != null) return forced;
        return wants(a, b) && wants(b, a);
    }

    String treatyWhy(int a, int b) {
        if (treatyForced.containsKey(pair(a, b))) return "set by staff";
        int[] x = allyVotes(a, b), y = allyVotes(b, a);
        int min = Math.max(1, getConfig().getInt("treaties.min-votes", 3));
        return name(a) + " " + x[0] + " yes / " + x[1] + " no, " + name(b) + " " + y[0] + " yes / " + y[1] + " no (" + min + "+ votes and a majority on both sides)";
    }

    /** Forms or ends treaties to match the votes; announced when told to. */
    void checkTreaties(boolean announce) {
        for (int a = 1; a <= 3; a++) for (int b = a + 1; b <= 3; b++) {
            String key = pair(a, b);
            boolean want = treatyWanted(a, b), have = treaties.contains(key);
            if (want == have) continue;
            if (want) { treaties.add(key); brokenAt.remove(key); }
            else { treaties.remove(key); brokenAt.put(key, System.currentTimeMillis()); }
            save();
            if (!announce) continue;
            String line = want
                    ? ChatColor.GOLD + "🤝 " + label(a) + ChatColor.GOLD + " and " + label(b) + ChatColor.GOLD + " signed a treaty! They can't hurt each other while it lasts."
                    : ChatColor.RED + "✂ The treaty between " + label(a) + ChatColor.RED + " and " + label(b) + ChatColor.RED + " is broken! PvP between them starts in "
                    + getConfig().getLong("treaties.break-grace-minutes", 5) + " minutes.";
            Bukkit.broadcastMessage(line);
            if (chat != null) { chat.queue(a, ChatColor.stripColor(line)); chat.queue(b, ChatColor.stripColor(line)); }
        }
    }

    /** /kingdom ally [k] [yes|no|clear] */
    void allyCommand(Player p, String[] args) {
        Member m = member(p);
        int k = m.kingdom;
        if (!getConfig().getBoolean("treaties.enabled", true)) { msg(p, ChatColor.RED + "Treaties are turned off."); return; }
        if (k == 0) { msg(p, ChatColor.RED + "You're not in a kingdom."); return; }
        if (args.length >= 2) {
            int other = parseKingdom(args[1]);
            if (other < 1 || other == k) { msg(p, ChatColor.RED + "/kingdom ally <the other kingdom: 1, 2 or 3> <yes|no|clear>"); return; }
            String what = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "yes";
            Boolean v = switch (what) { case "yes", "on", "true" -> Boolean.TRUE; case "no", "off", "false" -> Boolean.FALSE; default -> null; };
            if (v == null && !what.equals("clear")) { msg(p, ChatColor.RED + "/kingdom ally " + other + " <yes|no|clear>"); return; }
            if (v == null) m.allyVotes.remove(other); else m.allyVotes.put(other, v);
            save();
            msg(p, v == null ? ChatColor.GRAY + "Your treaty vote for " + label(other) + ChatColor.GRAY + " is cleared."
                    : ChatColor.GREEN + "You voted " + (v ? "FOR" : "AGAINST") + " a treaty with " + label(other) + ChatColor.GREEN + ".");
            String note = ChatColor.GRAY + p.getName() + " voted " + (v == null ? "nothing" : v ? "for" : "against") + " a treaty with " + label(other) + ChatColor.GRAY + ".";
            for (Player o : Bukkit.getOnlinePlayers()) if (o != p && kingdomOf(o.getUniqueId()) == k) o.sendMessage(note);
            checkTreaties(true);
        }
        for (int o = 1; o <= 3; o++) {
            if (o == k) continue;
            Boolean mine = m.allyVotes.get(o);
            msg(p, ChatColor.GOLD + "Treaty with " + label(o) + ChatColor.GOLD + ": " + (allied(k, o) ? ChatColor.GREEN + "IN FORCE" : ChatColor.RED + "none")
                    + ChatColor.GRAY + " (" + treatyWhy(k, o) + ") · your vote: " + (mine == null ? "none" : mine ? "yes" : "no"));
        }
        msg(p, ChatColor.DARK_GRAY + "/kingdom ally <1|2|3> <yes|no|clear> · both kingdoms have to want it");
    }

    // ================================================================= PvP

    /** The player really behind a hit: themselves, the shooter of an arrow/trident/potion, a TNT lighter, a pet's owner. */
    static Player playerBehind(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        if (damager instanceof AreaEffectCloud c && c.getSource() instanceof Player p) return p;
        if (damager instanceof TNTPrimed t && t.getSource() instanceof Player p) return p;
        if (damager instanceof Tameable pet && pet.getOwner() instanceof Player p) return p;
        return null;
    }

    /** Why attacker may not hurt victim right now, or null if they may. */
    String blocked(Player attacker, Player victim) {
        if (attacker.equals(victim)) return null;
        if (graceOn("all")) return "It's a grace period: no PvP.";
        int va = kingdomOf(victim.getUniqueId()), aa = kingdomOf(attacker.getUniqueId());
        if (va > 0 && graceOn(String.valueOf(va))) return victim.getName() + "'s kingdom (" + label(va) + ChatColor.RED + ") is under grace.";
        if (aa > 0 && graceOn(String.valueOf(aa)) && !getConfig().getBoolean("grace.protected-can-attack", false))
            return "Your kingdom is under grace: you can't attack players.";
        if (aa > 0 && aa == va && !friendlyFire(aa)) return victim.getName() + " is in your kingdom (friendly fire is off).";
        if (allied(aa, va)) return victim.getName() + " is in " + label(va) + ChatColor.RED + ", your ally (treaty).";
        long left = treatyCooldown(aa, va);
        if (left > 0) return "Your treaty with " + label(va) + ChatColor.RED + " just ended: PvP with them starts in " + duration(left) + ".";
        return null;
    }

    private void tell(Player p, String why) {
        Long last = warned.put(p.getUniqueId(), System.currentTimeMillis());
        if (last == null || System.currentTimeMillis() - last > 1500) p.sendActionBar(ChatColor.RED + why);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = playerBehind(e.getDamager());
        if (attacker == null) return;
        String why = blocked(attacker, victim);
        if (why != null) { e.setCancelled(true); tell(attacker, why); }
    }

    /** Splash potions (poison, harming, slowness) don't come as a damage event. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent e) {
        if (!(e.getPotion().getShooter() instanceof Player thrower)) return;
        for (LivingEntity hit : new ArrayList<>(e.getAffectedEntities()))
            if (hit instanceof Player v && blocked(thrower, v) != null) e.setIntensity(v, 0);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent e) {
        if (!(e.getEntity().getSource() instanceof Player thrower)) return;
        e.getAffectedEntities().removeIf(le -> le instanceof Player v && blocked(thrower, v) != null);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Member m = member(p);
        refreshName(p);
        if (discord.on()) {
            if (m.discordId != null) Bukkit.getScheduler().runTaskLater(this, () -> discord.syncAsync(p.getUniqueId()), 40L);
            else if (!m.manual) Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline()) p.sendMessage(ChatColor.GOLD + "Link your Discord to join your kingdom: " + ChatColor.WHITE + "/kingdom link <your discord name>");
            }, 100L);
        }
        if (protectedNow(p)) p.sendMessage(ChatColor.GREEN + "🛡 A grace period is on for you right now.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { warned.remove(e.getPlayer().getUniqueId()); Edition.forget(e.getPlayer().getUniqueId()); }

    // ================================================================= commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("kingdoms")) return admin(sender, args);
        if (command.getName().equalsIgnoreCase("kc") || command.getName().equalsIgnoreCase("ac")) {
            if (!(sender instanceof Player pp)) return msg(sender, "Players only.");
            return chat.command(pp, args, command.getName().equalsIgnoreCase("ac"));
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        Player p = sender instanceof Player pl ? pl : null;
        switch (sub) {
            case "link" -> {
                if (p == null) return msg(sender, "Players only.");
                if (!discord.on()) return msg(sender, ChatColor.RED + "Discord linking isn't set up on this server yet.");
                if (args.length < 2) return msg(sender, ChatColor.RED + "/kingdom link <your Discord username>");
                discord.startLink(p, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
            }
            case "verify" -> {
                if (p == null) return msg(sender, "Players only.");
                if (args.length < 2) return msg(sender, ChatColor.RED + "/kingdom verify <the code the bot sent you>");
                discord.verify(p, args[1]);
            }
            case "unlink" -> {
                if (p == null) return msg(sender, "Players only.");
                Member m = member(p);
                if (m.discordId == null) return msg(sender, ChatColor.GRAY + "Your Discord isn't linked.");
                m.discordId = null;
                if (!m.manual) setKingdom(p.getUniqueId(), 0, false, "Discord unlinked"); else save();
                msg(sender, ChatColor.YELLOW + "Your Discord is unlinked.");
            }
            case "ff", "friendlyfire" -> {
                if (p == null) return msg(sender, "Players only.");
                ffCommand(p, args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : null);
            }
            case "ally", "treaty", "treaties", "alliance" -> {
                if (p == null) return msg(sender, "Players only.");
                allyCommand(p, args);
            }
            case "list" -> {
                int k = args.length > 1 ? parseKingdom(args[1]) : -1;
                if (k < 1) return msg(sender, ChatColor.RED + "/kingdom list <1|2|3>");
                List<String> on = new ArrayList<>(), off = new ArrayList<>();
                for (var e : members.entrySet()) if (e.getValue().kingdom == k) (Bukkit.getPlayer(e.getKey()) != null ? on : off).add(e.getValue().name);
                msg(sender, label(k) + ChatColor.GRAY + " (" + (on.size() + off.size()) + "): " + ChatColor.GREEN + String.join(", ", on)
                        + (off.isEmpty() ? "" : (on.isEmpty() ? "" : ChatColor.GRAY + ", ") + ChatColor.GRAY + String.join(", ", off)));
            }
            default -> {
                if (p != null && forms.use(p) && forms.home(p)) return true; // Bedrock: a menu
                if (p != null) {
                    Member m = member(p);
                    msg(sender, ChatColor.GOLD + "Your kingdom: " + label(m.kingdom) + (m.manual ? ChatColor.GRAY + " (set by staff)" : "")
                            + ChatColor.GRAY + "  ·  Discord: " + (m.discordId != null ? ChatColor.GREEN + "linked" : ChatColor.RED + "not linked (/kingdom link <name>)"));
                }
                status(sender);
            }
        }
        return true;
    }

    void status(CommandSender s) {
        long now = System.currentTimeMillis();
        List<String> on = new ArrayList<>();
        for (String key : List.of("all", "1", "2", "3")) if (graceOn(key)) {
            long u = grace.get(key);
            on.add(graceName(key) + ChatColor.GRAY + (u < 0 ? " (until turned off)" : " (" + duration(u - now) + " left)"));
        }
        s.sendMessage(ChatColor.GOLD + "Grace periods: " + (on.isEmpty() ? ChatColor.RED + "none, PvP is on everywhere" : String.join(ChatColor.GRAY + ", ", on)));
        StringBuilder counts = new StringBuilder(ChatColor.GRAY + "Kingdoms:");
        for (int k = 1; k <= 3; k++) {
            int n = 0; for (Member m : members.values()) if (m.kingdom == k) n++;
            counts.append(" ").append(label(k)).append(ChatColor.GRAY).append(" ").append(n).append(friendlyFire(k) ? "" : " (no friendly fire)");
        }
        s.sendMessage(counts.toString());
        if (!treaties.isEmpty()) {
            List<String> t = new ArrayList<>();
            for (String key : treaties) { String[] ab = key.split("-"); t.add(label(Integer.parseInt(ab[0])) + ChatColor.GRAY + " + " + label(Integer.parseInt(ab[1]))); }
            s.sendMessage(ChatColor.GOLD + "Treaties: " + String.join(ChatColor.GRAY + ", ", t));
        }
        if (s.hasPermission("kingdoms.admin") && gateway != null) s.sendMessage(ChatColor.DARK_GRAY + "Discord /stats: " + gateway.status + ", chat bridge: " + gateway.bridgeStatus);
    }

    static int parseKingdom(String s) {
        String t = s.toLowerCase(Locale.ROOT).replace("kingdom", "").replace("k", "").trim();
        if (t.equals("none") || t.equals("0")) return 0;
        try { int k = Integer.parseInt(t); return k >= 1 && k <= 3 ? k : -1; } catch (NumberFormatException e) { return -1; }
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("kingdoms.admin")) return msg(sender, ChatColor.RED + "You don't have permission to do that.");
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "grace" -> { // /kingdoms grace <all|1|2|3> <on|off> [time]
                if (args.length < 3) return msg(sender, ChatColor.RED + "/kingdoms grace <all|1|2|3> <on|off> [time, e.g. 2h or 1h30m]");
                String key = args[1].equalsIgnoreCase("all") ? "all" : String.valueOf(parseKingdom(args[1]));
                if (!key.equals("all") && Integer.parseInt(key) < 1) return msg(sender, ChatColor.RED + "Which one? all, 1, 2 or 3.");
                boolean on = args[2].equalsIgnoreCase("on") || args[2].equalsIgnoreCase("true") || args[2].equalsIgnoreCase("start");
                if (!on && !args[2].equalsIgnoreCase("off") && !args[2].equalsIgnoreCase("false") && !args[2].equalsIgnoreCase("stop"))
                    return msg(sender, ChatColor.RED + "on or off?");
                if (!on) {
                    if (!graceOn(key)) return msg(sender, ChatColor.GRAY + "There's no grace period for " + graceName(key) + ChatColor.GRAY + ".");
                    setGrace(key, 0, sender);
                    return true;
                }
                long until = -1;
                if (args.length > 3) {
                    long ms = parseTime(String.join("", java.util.Arrays.copyOfRange(args, 3, args.length)));
                    if (ms <= 0) return msg(sender, ChatColor.RED + "Couldn't read that time: use like 30m, 2h, 1d, 1h30m.");
                    until = System.currentTimeMillis() + ms;
                }
                setGrace(key, until, sender);
            }
            case "set" -> { // /kingdoms set <player> <1|2|3|none|auto>
                if (args.length < 3) return msg(sender, ChatColor.RED + "/kingdoms set <player> <1|2|3|none|auto>");
                @SuppressWarnings("deprecation") OfflinePlayer op = Bukkit.getOfflinePlayer(args[1]);
                if (!op.hasPlayedBefore() && !op.isOnline()) return msg(sender, ChatColor.RED + "Never seen " + args[1] + " on this server.");
                if (args[2].equalsIgnoreCase("auto")) {
                    Member m = members.computeIfAbsent(op.getUniqueId(), x -> new Member());
                    m.manual = false;
                    if (m.name == null) m.name = op.getName();
                    save();
                    if (m.discordId != null && discord.on()) discord.syncAsync(op.getUniqueId());
                    return msg(sender, ChatColor.GREEN + op.getName() + "'s kingdom follows their Discord role again.");
                }
                int k = parseKingdom(args[2]);
                if (k < 0) return msg(sender, ChatColor.RED + "1, 2, 3, none or auto.");
                Member m = members.computeIfAbsent(op.getUniqueId(), x -> new Member());
                if (m.name == null) m.name = op.getName();
                setKingdom(op.getUniqueId(), k, true, "set by staff");
                msg(sender, ChatColor.GREEN + op.getName() + " is now in " + label(k) + ChatColor.GREEN + " (pinned: Discord roles won't change it; /kingdoms set " + op.getName() + " auto to undo).");
            }
            case "sync" -> {
                if (!discord.on()) return msg(sender, ChatColor.RED + "Discord isn't set up (discord.bot-token / guild-id in config.yml).");
                if (args.length > 1) {
                    Player t = Bukkit.getPlayerExact(args[1]);
                    if (t == null) return msg(sender, ChatColor.RED + "Not online.");
                    discord.syncAsync(t.getUniqueId());
                } else discord.syncAll();
                msg(sender, ChatColor.GREEN + "Checking Discord roles...");
            }
            case "ff" -> { // /kingdoms ff <1|2|3> <on|off|vote>
                int k = args.length > 2 ? parseKingdom(args[1]) : -1;
                if (k < 1) return msg(sender, ChatColor.RED + "/kingdoms ff <1|2|3> <on|off|vote>");
                boolean before = friendlyFire(k);
                switch (args[2].toLowerCase(Locale.ROOT)) {
                    case "on" -> ffForced.put(String.valueOf(k), true);
                    case "off" -> ffForced.put(String.valueOf(k), false);
                    case "vote", "auto" -> ffForced.remove(String.valueOf(k));
                    default -> { return msg(sender, ChatColor.RED + "on, off or vote."); }
                }
                save();
                msg(sender, ChatColor.GREEN + name(k) + ": friendly fire " + (friendlyFire(k) ? "ON" : "OFF") + " (" + ffWhy(k) + ").");
                ffChanged(k, before);
            }
            case "treaty" -> { // /kingdoms treaty <a> <b> <on|off|vote>
                int a = args.length > 3 ? parseKingdom(args[1]) : -1, b = args.length > 3 ? parseKingdom(args[2]) : -1;
                if (a < 1 || b < 1 || a == b) return msg(sender, ChatColor.RED + "/kingdoms treaty <1|2|3> <1|2|3> <on|off|vote>");
                switch (args[3].toLowerCase(Locale.ROOT)) {
                    case "on" -> treatyForced.put(pair(a, b), true);
                    case "off" -> treatyForced.put(pair(a, b), false);
                    case "vote", "auto" -> treatyForced.remove(pair(a, b));
                    default -> { return msg(sender, ChatColor.RED + "on, off or vote."); }
                }
                save();
                checkTreaties(true);
                msg(sender, ChatColor.GREEN + name(a) + " + " + name(b) + ": treaty " + (allied(a, b) ? "IN FORCE" : "none") + " (" + treatyWhy(a, b) + ").");
            }
            case "tp", "bring", "summon" -> { // /kingdoms tp <1|2|3|outlaws|all>[,more] [player]
                if (args.length < 2) return msg(sender, ChatColor.RED + "/kingdoms tp <1|2|3|outlaws|all> [player]  (several: 1,3)  - brings them to you, or to that player");
                java.util.Set<Integer> ks = new java.util.TreeSet<>();
                for (String part : args[1].toLowerCase(Locale.ROOT).split(",")) {
                    if (part.isBlank()) continue;
                    if (part.equals("all")) { ks.addAll(List.of(0, 1, 2, 3)); continue; }
                    if (part.startsWith("outlaw") || part.equals("none")) { ks.add(0); continue; }
                    int k = parseKingdom(part);
                    if (k < 1) return msg(sender, ChatColor.RED + "Unknown kingdom: " + part + " (1, 2, 3, outlaws or all)");
                    ks.add(k);
                }
                Player to;
                if (args.length >= 3) {
                    to = Bukkit.getPlayerExact(args[2]);
                    if (to == null) return msg(sender, ChatColor.RED + args[2] + " isn't online.");
                } else if (sender instanceof Player sp) to = sp;
                else return msg(sender, ChatColor.RED + "From the console, name who to bring them to: /kingdoms tp <kingdom> <player>");
                org.bukkit.Location at = to.getLocation();
                int n = 0;
                for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                    if (p.equals(to) || p.equals(sender) || !ks.contains(kingdomOf(p.getUniqueId()))) continue;
                    if (p.isDead()) continue;
                    if (p.isInsideVehicle()) p.leaveVehicle();
                    try { p.teleportAsync(at, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND); }
                    catch (RuntimeException | LinkageError e) { p.teleport(at, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND); }
                    p.sendMessage(ChatColor.GOLD + "You were brought to " + to.getName() + " by staff.");
                    n++;
                }
                StringBuilder who = new StringBuilder();
                for (int k : ks) { if (who.length() > 0) who.append(", "); who.append(k == 0 ? "Outlaws" : name(k)); }
                getLogger().info("[Tp] " + sender.getName() + " brought " + n + " (" + who + ") to " + to.getName());
                if (!sender.equals(to)) to.sendMessage(ChatColor.GOLD + "" + n + " player" + (n == 1 ? "" : "s") + " (" + who + ") were brought to you.");
                return msg(sender, n == 0 ? ChatColor.GRAY + "Nobody online in " + who + "."
                        : ChatColor.GREEN + "Brought " + n + " player" + (n == 1 ? "" : "s") + " (" + who + ") to " + (sender.equals(to) ? "you" : to.getName()) + ".");
            }
            case "spy" -> {
                if (!(sender instanceof Player sp)) return msg(sender, "Players only.");
                if (!chat.spyOff.remove(sp.getUniqueId())) { chat.spyOff.add(sp.getUniqueId()); return msg(sender, ChatColor.GRAY + "Kingdom chat spy OFF."); }
                return msg(sender, ChatColor.GREEN + "Kingdom chat spy ON: you see every kingdom's /kc.");
            }
            case "reload" -> {
                reloadConfig();
                for (Player p : Bukkit.getOnlinePlayers()) refreshName(p);
                if (gateway != null) gateway.stop();
                gateway = new DiscordGateway(this, discord);
                gateway.start();
                msg(sender, ChatColor.GREEN + "Reloaded. Discord sync " + (discord.on() ? "on." : "off (no token / guild id)."));
            }
            default -> {
                status(sender);
                msg(sender, ChatColor.DARK_GRAY + "/kingdoms grace <all|1|2|3> <on|off> [time] · set <player> <1|2|3|none|auto> · ff <1|2|3> <on|off|vote> · treaty <a> <b> <on|off|vote> · tp <1|2|3|outlaws|all> [player] · spy · sync [player] · reload");
            }
        }
        return true;
    }

    static boolean msg(CommandSender s, String m) { s.sendMessage(m); return true; }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("kingdoms")) {
            if (args.length == 1) return List.of("grace", "set", "ff", "treaty", "tp", "spy", "sync", "status", "reload");
            if (args.length == 2 && args[0].equalsIgnoreCase("tp")) return List.of("1", "2", "3", "outlaws", "all");
            if (args[0].equalsIgnoreCase("treaty")) return args.length <= 3 ? List.of("1", "2", "3") : args.length == 4 ? List.of("on", "off", "vote") : List.of();
            if (args.length == 2 && args[0].equalsIgnoreCase("ff")) return List.of("1", "2", "3");
            if (args.length == 3 && args[0].equalsIgnoreCase("ff")) return List.of("on", "off", "vote");
            if (args.length == 2 && args[0].equalsIgnoreCase("grace")) return List.of("all", "1", "2", "3");
            if (args.length == 3 && args[0].equalsIgnoreCase("grace")) return List.of("on", "off");
            if (args.length == 4 && args[0].equalsIgnoreCase("grace")) return List.of("30m", "1h", "2h", "1d");
            if (args.length == 3 && args[0].equalsIgnoreCase("set")) return List.of("1", "2", "3", "none", "auto");
            return null;
        }
        if (command.getName().equalsIgnoreCase("kc") || command.getName().equalsIgnoreCase("ac")) return null;
        if (args.length == 1) return List.of("link", "verify", "unlink", "list", "ff", "ally");
        if (args.length == 2 && args[0].equalsIgnoreCase("ally")) return List.of("1", "2", "3");
        if (args.length == 3 && args[0].equalsIgnoreCase("ally")) return List.of("yes", "no", "clear");
        if (args.length == 2 && args[0].equalsIgnoreCase("ff")) return List.of("on", "off", "clear");
        if (args.length == 2 && args[0].equalsIgnoreCase("list")) return List.of("1", "2", "3");
        return List.of();
    }
}
