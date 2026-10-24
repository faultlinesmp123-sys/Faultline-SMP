package net.limbosmp.blackmarket;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * HEAD BOUNTIES (1.1.0): put player heads (the server's currency) on someone's name. The heads are taken from you and
 * held; whoever kills that player gets every head on them. Unclaimed after {@code bounty.expire-days} they go back.
 *  - /bounty place <player> [amount]: the player heads in your main hand (only real ones: tagged with a tier by HeadTagger)
 *  - /bounty list, /bounty info <player>, /bounty cancel <player> (your own heads back, after a lock), /bounty claim
 *  - no payout for killing yourself, a kill the anti-farm cooldown flags, someone on the same IP (an alt), or (unless
 *    {@code bounty.same-kingdom-claims}) your own kingdom; then the bounty just stays up
 *  - the killer's own heads on that bounty go back to them; whatever doesn't fit in an inventory waits for /bounty claim
 *  - saved in bounties.yml (whole items, so a head keeps its owner, tier and lore)
 */
public class Bounties implements Listener, TabExecutor {

    record Stake(UUID poster, long time, ItemStack heads) { }

    static final class Bounty {
        String name;
        final List<Stake> stakes = new ArrayList<>();
        int heads() { int n = 0; for (Stake s : stakes) n += s.heads.getAmount(); return n; }
    }

    private final LimboBlackMarket plugin;
    private final File file;
    final Map<UUID, Bounty> bounties = new LinkedHashMap<>();
    final Map<UUID, List<ItemStack>> pending = new HashMap<>();

    public Bounties(LimboBlackMarket plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bounties.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 20L * 60, 20L * 60 * 10);
    }

    private boolean enabled() { return plugin.getConfig().getBoolean("bounty.enabled", true); }

    // ============================================================================================ saving
    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection b = y.getConfigurationSection("bounties");
        if (b != null) for (String k : b.getKeys(false)) {
            try {
                Bounty bo = new Bounty();
                bo.name = b.getString(k + ".name", "?");
                ConfigurationSection st = b.getConfigurationSection(k + ".stakes");
                if (st != null) for (String i : st.getKeys(false)) {
                    ItemStack it = st.getItemStack(i + ".item");
                    if (it == null) continue;
                    bo.stakes.add(new Stake(UUID.fromString(st.getString(i + ".poster", "")), st.getLong(i + ".time"), it));
                }
                if (!bo.stakes.isEmpty()) bounties.put(UUID.fromString(k), bo);
            } catch (RuntimeException e) { plugin.getLogger().warning("[Bounty] skipping a broken bounty " + k + ": " + e); }
        }
        ConfigurationSection p = y.getConfigurationSection("pending");
        if (p != null) for (String k : p.getKeys(false)) {
            try {
                List<ItemStack> l = new ArrayList<>();
                List<?> raw = p.getList(k);
                if (raw != null) for (Object o : raw) if (o instanceof ItemStack it) l.add(it);
                if (!l.isEmpty()) pending.put(UUID.fromString(k), l);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : bounties.entrySet()) {
            String k = "bounties." + e.getKey();
            y.set(k + ".name", e.getValue().name);
            int i = 0;
            for (Stake s : e.getValue().stakes) {
                String si = k + ".stakes." + (i++);
                y.set(si + ".poster", s.poster.toString()); y.set(si + ".time", s.time); y.set(si + ".item", s.heads);
            }
        }
        for (var e : pending.entrySet()) y.set("pending." + e.getKey(), new ArrayList<>(e.getValue()));
        try { y.save(file); } catch (IOException e) { plugin.getLogger().log(Level.SEVERE, "Couldn't save bounties.yml", e); }
    }

    // ============================================================================================ heads
    /** A head that counts as currency: a PLAYER_HEAD (with the tier tag HeadTagger puts on heads from deaths, by default). */
    boolean currency(ItemStack s) {
        if (s == null || s.getType() != Material.PLAYER_HEAD || s.getAmount() <= 0) return false;
        if (!plugin.getConfig().getBoolean("bounty.tagged-heads-only", true)) return true;
        return s.hasItemMeta() && s.getItemMeta().getPersistentDataContainer().has(plugin.getHeadTierKey(), PersistentDataType.STRING);
    }

    /** Gives items, and whatever doesn't fit waits for /bounty claim. True if anything had to wait. */
    boolean deliver(UUID to, List<ItemStack> items) {
        Player p = Bukkit.getPlayer(to);
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack it : items) {
            if (it == null || it.getAmount() <= 0) continue;
            if (p == null) { left.add(it); continue; }
            left.addAll(p.getInventory().addItem(it.clone()).values());
        }
        if (left.isEmpty()) return false;
        pending.computeIfAbsent(to, k -> new ArrayList<>()).addAll(left);
        return true;
    }

    static String plural(int n) { return n + (n == 1 ? " head" : " heads"); }

    static OfflinePlayer find(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) return cached;
        for (OfflinePlayer o : Bukkit.getOfflinePlayers()) if (name.equalsIgnoreCase(o.getName())) return o;
        return null;
    }

    // ============================================================================================ claiming
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!enabled()) return;
        Player victim = event.getEntity();
        Bounty b = bounties.get(victim.getUniqueId());
        if (b == null) return;
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        String why = whyNot(killer, victim);
        if (why != null) { killer.sendMessage(ChatColor.GRAY + "[Bounty] No payout for " + victim.getName() + ": " + why); return; }
        bounties.remove(victim.getUniqueId());
        List<ItemStack> prize = new ArrayList<>(), own = new ArrayList<>();
        int n = 0;
        for (Stake s : b.stakes) {
            if (s.poster.equals(killer.getUniqueId())) own.add(s.heads); // your own heads just come back
            else { prize.add(s.heads); n += s.heads.getAmount(); }
        }
        prize.addAll(own);
        boolean waited = deliver(killer.getUniqueId(), prize);
        save();
        if (n > 0) {
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "[Bounty] " + ChatColor.GOLD + killer.getName() + ChatColor.GRAY + " claimed the bounty on "
                    + ChatColor.WHITE + victim.getName() + ChatColor.GRAY + ": " + ChatColor.GOLD + plural(n) + ChatColor.GRAY + "!");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + killer.getName() + " bounties_claimed 1");
        } else killer.sendMessage(ChatColor.GRAY + "[Bounty] You collected your own bounty on " + victim.getName() + ": your heads are back.");
        if (waited) killer.sendMessage(ChatColor.YELLOW + "[Bounty] Your inventory is full: the rest is waiting. " + ChatColor.WHITE + "/bounty claim");
    }

    /** Why this kill doesn't pay out (null = it does). */
    String whyNot(Player killer, Player victim) {
        if (plugin.getAntiFarmManager().isFarmedKill(killer.getUniqueId(), victim.getUniqueId()))
            return "you killed them too recently (anti-farm).";
        if (!plugin.getConfig().getBoolean("bounty.same-ip-claims", false) && sameIp(killer, victim)) return "same connection as them.";
        if (!plugin.getConfig().getBoolean("bounty.same-kingdom-claims", false)) {
            String a = plugin.getKingdomDataReader().getKingdom(killer.getUniqueId());
            if (a != null && a.equals(plugin.getKingdomDataReader().getKingdom(victim.getUniqueId()))) return "they're in your kingdom.";
        }
        return null;
    }

    static boolean sameIp(Player a, Player b) {
        InetSocketAddress x = a.getAddress(), y = b.getAddress();
        if (x == null || y == null || x.getAddress() == null || y.getAddress() == null) return false;
        if (x.getAddress().isLoopbackAddress() || x.getAddress().isSiteLocalAddress()) return false; // Geyser on the same box
        return x.getAddress().equals(y.getAddress());
    }

    // ============================================================================================ expiry, joining
    void expire() {
        long days = plugin.getConfig().getLong("bounty.expire-days", 7);
        if (days <= 0) return;
        long cut = System.currentTimeMillis() - days * 86_400_000L;
        boolean changed = false;
        for (var it = bounties.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            for (var si = e.getValue().stakes.iterator(); si.hasNext(); ) {
                Stake s = si.next();
                if (s.time >= cut) continue;
                si.remove(); changed = true;
                deliver(s.poster, List.of(s.heads));
                Player p = Bukkit.getPlayer(s.poster);
                if (p != null) p.sendMessage(ChatColor.GRAY + "[Bounty] Your bounty on " + e.getValue().name + " ran out: " + plural(s.heads.getAmount()) + " back. " + ChatColor.WHITE + "/bounty claim");
            }
            if (e.getValue().stakes.isEmpty()) it.remove();
        }
        if (changed) save();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bounty b = bounties.get(p.getUniqueId());
        if (b != null) {
            b.name = p.getName();
            p.sendMessage(ChatColor.DARK_RED + "[Bounty] " + ChatColor.GRAY + "There's a bounty on you: " + ChatColor.GOLD + plural(b.heads()) + ChatColor.GRAY + ". Watch your back.");
        }
        if (pending.containsKey(p.getUniqueId()))
            p.sendMessage(ChatColor.YELLOW + "[Bounty] You have heads waiting for you. " + ChatColor.WHITE + "/bounty claim");
    }

    // ============================================================================================ the command
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "list";
        Player self = sender instanceof Player p ? p : null;
        if (!enabled() && !sub.equals("claim")) { sender.sendMessage(ChatColor.GRAY + "Bounties are switched off."); return true; }
        switch (sub) {
            case "place", "set", "add" -> {
                if (self == null) { sender.sendMessage("Players only."); return true; }
                if (args.length < 2) { sender.sendMessage(ChatColor.YELLOW + "/bounty place <player> [amount]" + ChatColor.GRAY + " (hold the heads)"); return true; }
                OfflinePlayer target = find(args[1]);
                if (target == null || (!target.isOnline() && !target.hasPlayedBefore())) { sender.sendMessage(ChatColor.RED + "Nobody called " + args[1] + " has played here."); return true; }
                if (target.getUniqueId().equals(self.getUniqueId())) { sender.sendMessage(ChatColor.RED + "You can't put a bounty on yourself."); return true; }
                ItemStack hand = self.getInventory().getItemInMainHand();
                if (!currency(hand)) {
                    sender.sendMessage(ChatColor.RED + "Hold the player heads you want to put up" + (plugin.getConfig().getBoolean("bounty.tagged-heads-only", true) ? " (real ones, from a player's death)." : "."));
                    return true;
                }
                int amount = hand.getAmount();
                if (args.length > 2) {
                    try { amount = Math.max(1, Math.min(hand.getAmount(), Integer.parseInt(args[2]))); }
                    catch (NumberFormatException e) { sender.sendMessage(ChatColor.YELLOW + "/bounty place <player> [amount]"); return true; }
                }
                int min = Math.max(1, plugin.getConfig().getInt("bounty.min-heads", 1));
                if (amount < min) { sender.sendMessage(ChatColor.RED + "A bounty needs at least " + plural(min) + "."); return true; }
                ItemStack put = hand.clone(); put.setAmount(amount);
                hand.setAmount(hand.getAmount() - amount);
                self.getInventory().setItemInMainHand(hand.getAmount() <= 0 ? null : hand);
                Bounty b = bounties.computeIfAbsent(target.getUniqueId(), k -> new Bounty());
                b.name = target.getName() != null ? target.getName() : args[1];
                b.stakes.add(new Stake(self.getUniqueId(), System.currentTimeMillis(), put));
                save();
                Bukkit.broadcastMessage(ChatColor.DARK_RED + "[Bounty] " + ChatColor.WHITE + self.getName() + ChatColor.GRAY + " put " + ChatColor.GOLD + plural(amount)
                        + ChatColor.GRAY + " on " + ChatColor.WHITE + b.name + ChatColor.GRAY + "'s head. Total: " + ChatColor.GOLD + plural(b.heads()) + ChatColor.GRAY + ".");
                if (target.getPlayer() != null) target.getPlayer().sendMessage(ChatColor.DARK_RED + "[Bounty] " + ChatColor.GRAY + "Someone wants you dead. Watch your back.");
            }
            case "list", "top" -> {
                if (bounties.isEmpty()) { sender.sendMessage(ChatColor.GRAY + "No bounties right now. " + ChatColor.WHITE + "/bounty place <player>"); return true; }
                sender.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Bounties");
                List<Map.Entry<UUID, Bounty>> l = new ArrayList<>(bounties.entrySet());
                l.sort(Comparator.comparingInt((Map.Entry<UUID, Bounty> e) -> e.getValue().heads()).reversed());
                int i = 0;
                for (var e : l) {
                    if (++i > 10) break;
                    boolean on = Bukkit.getPlayer(e.getKey()) != null;
                    sender.sendMessage(ChatColor.GRAY + "" + i + ". " + (on ? ChatColor.GREEN : ChatColor.WHITE) + e.getValue().name + ChatColor.GRAY + ": "
                            + ChatColor.GOLD + plural(e.getValue().heads()) + ChatColor.DARK_GRAY + (on ? " (online)" : ""));
                }
            }
            case "info" -> {
                if (args.length < 2) { sender.sendMessage(ChatColor.YELLOW + "/bounty info <player>"); return true; }
                OfflinePlayer target = find(args[1]);
                Bounty b = target == null ? null : bounties.get(target.getUniqueId());
                if (b == null) { sender.sendMessage(ChatColor.GRAY + "No bounty on " + args[1] + "."); return true; }
                sender.sendMessage(ChatColor.DARK_RED + "Bounty on " + b.name + ": " + ChatColor.GOLD + plural(b.heads()));
                Map<String, Integer> tiers = new LinkedHashMap<>();
                for (Stake s : b.stakes) {
                    String t = s.heads.hasItemMeta() ? s.heads.getItemMeta().getPersistentDataContainer().get(plugin.getHeadTierKey(), PersistentDataType.STRING) : null;
                    tiers.merge(t == null ? "untagged" : plugin.getMarketConfig().getDisplayName(t), s.heads.getAmount(), Integer::sum);
                }
                for (var t : tiers.entrySet()) sender.sendMessage(ChatColor.GRAY + " - " + ChatColor.translateAlternateColorCodes('&', t.getKey()) + ChatColor.GRAY + ": " + t.getValue());
            }
            case "cancel", "remove" -> {
                if (self == null) { sender.sendMessage("Players only."); return true; }
                if (args.length < 2) { sender.sendMessage(ChatColor.YELLOW + "/bounty cancel <player>"); return true; }
                OfflinePlayer target = find(args[1]);
                Bounty b = target == null ? null : bounties.get(target.getUniqueId());
                if (b == null) { sender.sendMessage(ChatColor.GRAY + "No bounty on " + args[1] + "."); return true; }
                long lock = plugin.getConfig().getLong("bounty.cancel-lock-minutes", 30) * 60_000L;
                List<ItemStack> back = new ArrayList<>();
                long now = System.currentTimeMillis(), soonest = Long.MAX_VALUE;
                for (var it = b.stakes.iterator(); it.hasNext(); ) {
                    Stake s = it.next();
                    if (!s.poster.equals(self.getUniqueId())) continue;
                    if (now - s.time < lock) { soonest = Math.min(soonest, s.time + lock - now); continue; }
                    back.add(s.heads); it.remove();
                }
                if (back.isEmpty()) {
                    sender.sendMessage(soonest == Long.MAX_VALUE ? ChatColor.GRAY + "You haven't put anything on " + b.name + "."
                            : ChatColor.GRAY + "You can take it back in " + ((soonest + 59_999) / 60_000) + " min (a bounty stays up a while).");
                    return true;
                }
                if (b.stakes.isEmpty()) bounties.remove(target.getUniqueId());
                int n = 0; for (ItemStack s : back) n += s.getAmount();
                boolean waited = deliver(self.getUniqueId(), back);
                save();
                sender.sendMessage(ChatColor.GREEN + "Took back " + plural(n) + " from the bounty on " + b.name + "." + (waited ? ChatColor.YELLOW + " Some are waiting: /bounty claim" : ""));
            }
            case "claim" -> {
                if (self == null) { sender.sendMessage("Players only."); return true; }
                List<ItemStack> l = pending.remove(self.getUniqueId());
                if (l == null || l.isEmpty()) { sender.sendMessage(ChatColor.GRAY + "Nothing waiting for you."); return true; }
                boolean waited = deliver(self.getUniqueId(), l);
                save();
                sender.sendMessage(waited ? ChatColor.YELLOW + "Your inventory filled up: the rest is still waiting." : ChatColor.GREEN + "Collected everything that was waiting.");
            }
            default -> sender.sendMessage(ChatColor.YELLOW + "/bounty <place <player> [amount]|list|info <player>|cancel <player>|claim>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) return filter(List.of("place", "list", "info", "cancel", "claim"), args[0]);
        if (args.length == 2 && !args[0].equalsIgnoreCase("list") && !args[0].equalsIgnoreCase("claim")) {
            List<String> names = new ArrayList<>();
            if (args[0].equalsIgnoreCase("place")) for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            else for (Bounty b : bounties.values()) names.add(b.name);
            return filter(names, args[1]);
        }
        return List.of();
    }

    static List<String> filter(List<String> l, String start) {
        List<String> out = new ArrayList<>();
        for (String s : l) if (s != null && s.toLowerCase(Locale.ROOT).startsWith(start.toLowerCase(Locale.ROOT))) out.add(s);
        return out;
    }
}
