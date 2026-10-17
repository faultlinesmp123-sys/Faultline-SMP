package net.faultlinesmp.index;

import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BOSS WEAKNESSES (Index 1.7.0): a creature entry's `weakness:` (index.yml) shows on its page only once that boss has
 * killed you (the Index "learns it from your death"). Before that the page says "??? (it has to beat you once)". Deaths are
 * matched to an entry by the killer's scoreboard tag (the entry's `detect-tag`, the projectile's shooter counts) or by its
 * name; FaultlineBosses also reports its model bosses' kills with `/index died <player> <boss name>` (their hits often
 * come from no entity). Saved in weakness.yml.
 */
final class Weakness implements Listener {
    final FaultlineIndex ix;
    final File file;
    final Map<UUID, Set<String>> known = new HashMap<>();
    static final Map<String, String> ALIASES = new HashMap<>();
    static {
        ALIASES.put("frostbeard", "mortimer_freeze"); ALIASES.put("mortimer", "mortimer_freeze");
        ALIASES.put("grubnash", "deep_goblin"); ALIASES.put("acid goblin", "deep_goblin");
        ALIASES.put("captain hollow", "ghost_captain"); ALIASES.put("titan", "abyssal_titan");
        ALIASES.put("kraken", "the_kraken"); ALIASES.put("demon eye", "the_demon_eye"); ALIASES.put("jacob", "diamond_jacob");
        ALIASES.put("rocco", "rocco_vendetta"); ALIASES.put("rotbeard", "captain_rotbeard"); ALIASES.put("great hog", "the_great_hog");
        ALIASES.put("bulwark", "the_bulwark"); ALIASES.put("dune devourer", "the_dune_devourer"); ALIASES.put("frostmaw", "the_frostmaw");
        ALIASES.put("lorenzo", "don_lorenzo"); ALIASES.put("sandworm", "sandworm_king"); ALIASES.put("wyrm", "frost_wyrm");
        ALIASES.put("scourge", "brine_scourge"); ALIASES.put("umbral", "umbral_leviathan"); ALIASES.put("inkjaw", "old_inkjaw");
        ALIASES.put("grandmaw", "grandmaw_clam"); ALIASES.put("lament", "the_lament"); ALIASES.put("rimeback", "deep_rimeback");
        ALIASES.put("drowned king", "deep_drownking");
    }

    Weakness(FaultlineIndex ix) {
        this.ix = ix;
        file = new File(ix.getDataFolder(), "weakness.yml");
        if (file.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            for (String k : y.getKeys(false)) try { known.put(UUID.fromString(k), new HashSet<>(y.getStringList(k))); } catch (IllegalArgumentException ignored) { }
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Set<String>> e : known.entrySet()) y.set(e.getKey().toString(), List.copyOf(e.getValue()));
        try { y.save(file); } catch (IOException e) { ix.getLogger().warning("Couldn't save weakness.yml: " + e.getMessage()); }
    }

    boolean knows(UUID id, String entry) { return known.getOrDefault(id, Set.of()).contains(entry); }

    /** An entry for a killer's name ("The Demon Eye", "Grimtusk, the Piglin Warlord", "★ Elite Zombie"...), or null. */
    FaultlineIndex.Entry byName(String name) {
        if (name == null) return null;
        String low = ChatColor.stripColor(name).toLowerCase(Locale.ROOT).trim();
        for (FaultlineIndex.Entry e : ix.entries) if (e.creature && e.weakness != null && e.name.equalsIgnoreCase(low)) return e;
        for (FaultlineIndex.Entry e : ix.entries) if (e.creature && e.weakness != null && low.contains(e.name.toLowerCase(Locale.ROOT).replaceFirst("^the ", ""))) return e;
        for (Map.Entry<String, String> a : ALIASES.entrySet()) if (low.contains(a.getKey())) {
            for (FaultlineIndex.Entry e : ix.entries) if (e.id.equals(a.getValue()) && e.weakness != null) return e;
        }
        return null;
    }

    FaultlineIndex.Entry byEntity(Entity en) {
        if (en == null) return null;
        for (String tag : en.getScoreboardTags())
            for (FaultlineIndex.Entry e : ix.entries) if (e.creature && e.weakness != null && tag.equals(e.tag)) return e;
        return byName(en.getCustomName());
    }

    void learn(Player p, FaultlineIndex.Entry e) {
        if (e == null || e.weakness == null || !known.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>()).add(e.id)) return;
        save();
        ix.achievements.addStat(p, "weaknesses", 1);
        ix.getServer().getScheduler().runTaskLater(ix, () -> {
            if (!p.isOnline()) return;
            p.sendMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "The Index learned from your death. " + ChatColor.GRAY + e.name + "'s weakness is written in its page now. "
                    + ChatColor.YELLOW + "/index");
            p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 0.7f);
        }, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!(p.getLastDamageCause() instanceof EntityDamageByEntityEvent by)) return;
        Entity d = by.getDamager();
        if (d instanceof Projectile pr) { ProjectileSource s = pr.getShooter(); if (s instanceof Entity se) d = se; }
        learn(p, byEntity(d));
    }
}
