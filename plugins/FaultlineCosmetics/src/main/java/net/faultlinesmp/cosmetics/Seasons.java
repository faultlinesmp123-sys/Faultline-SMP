package net.faultlinesmp.cosmetics;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.*;

/**
 * Seasonal cosmetics that come back every year. While a season runs (Halloween: October, Winter: December), hostile
 * mobs killed by players sometimes drop that season's currency (Candy / Presents), and the Seasonal Shop in /cosmetics
 * sells that season's cosmetics for it. Out of season they can't be bought, but whoever has one keeps it forever.
 */
final class Seasons implements Listener {

    /** One season, from config.yml (seasons.<id>), with defaults in the code so a server's old config still works. */
    record Season(String id, String name, String color, MonthDay start, MonthDay end, String token, String tokenName,
                  String tokenModel, double chance, int min, int max) {
        boolean contains(MonthDay d) {
            return start.isAfter(end) ? !d.isBefore(start) || !d.isAfter(end) : !d.isBefore(start) && !d.isAfter(end);
        }
    }

    private final FaultlineCosmetics plugin;
    private final Random random = new Random();
    final LinkedHashMap<String, Season> seasons = new LinkedHashMap<>();

    Seasons(FaultlineCosmetics plugin) {
        this.plugin = plugin;
        load();
    }

    private static final Object[][] DEFAULTS = {
            {"halloween", "Halloween", "&6", "10-01", "11-02", "candy", "Candy", "season_candy", 0.08, 1, 2},
            {"winter", "Winter", "&b", "12-01", "01-06", "present", "Present", "season_present", 0.08, 1, 2}};

    void load() {
        seasons.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("seasons");
        Set<String> ids = new LinkedHashSet<>();
        for (Object[] d : DEFAULTS) ids.add((String) d[0]);
        if (root != null) ids.addAll(root.getKeys(false));
        for (String id : ids) {
            Object[] d = Arrays.stream(DEFAULTS).filter(x -> x[0].equals(id)).findFirst()
                    .orElse(new Object[]{id, id, "&e", "01-01", "01-01", id, id, "season_" + id, 0.08, 1, 2});
            ConfigurationSection c = root == null ? null : root.getConfigurationSection(id);
            try {
                seasons.put(id, new Season(id, str(c, "name", (String) d[1]), str(c, "color", (String) d[2]),
                        day(str(c, "start", (String) d[3])), day(str(c, "end", (String) d[4])), str(c, "token", (String) d[5]),
                        str(c, "token-name", (String) d[6]), str(c, "token-model", (String) d[7]),
                        c == null ? (double) d[8] : c.getDouble("drop-chance", (double) d[8]),
                        c == null ? (int) d[9] : c.getInt("drop-min", (int) d[9]), c == null ? (int) d[10] : c.getInt("drop-max", (int) d[10])));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Season " + id + " has a bad date (use MM-DD), skipped: " + e.getMessage());
            }
        }
    }

    private static String str(ConfigurationSection c, String key, String def) { return c == null ? def : c.getString(key, def); }

    private static MonthDay day(String s) {
        String[] p = s.trim().split("-");
        return MonthDay.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
    }

    static MonthDay today() { return MonthDay.from(LocalDate.now(ZoneId.systemDefault())); }

    /** The season running now (or the one an admin forced with /cosmetic season), else null. */
    Season current() {
        String forced = plugin.getConfig().getString("force-season", "");
        if (forced != null && !forced.isBlank()) {
            if (forced.equalsIgnoreCase("off")) return null;
            Season s = seasons.get(forced.toLowerCase(Locale.ROOT));
            if (s != null) return s;
        }
        MonthDay d = today();
        for (Season s : seasons.values()) if (s.contains(d)) return s;
        return null;
    }

    /** The next season to start, and how many days until it does. */
    Map.Entry<Season, Integer> next() {
        LocalDate now = LocalDate.now(ZoneId.systemDefault());
        Season best = null; int bestDays = Integer.MAX_VALUE;
        for (Season s : seasons.values()) {
            LocalDate start = s.start().atYear(now.getYear());
            if (start.isBefore(now) || start.isEqual(now)) start = start.plusYears(1);
            int days = (int) java.time.temporal.ChronoUnit.DAYS.between(now, start);
            if (days < bestDays) { bestDays = days; best = s; }
        }
        return best == null ? null : Map.entry(best, bestDays);
    }

    static String when(Season s) {
        return monthName(s.start()) + " " + s.start().getDayOfMonth() + " - " + monthName(s.end()) + " " + s.end().getDayOfMonth();
    }

    private static String monthName(MonthDay d) {
        String m = d.getMonth().name();
        return m.charAt(0) + m.substring(1, 3).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ the currency

    NamespacedKey key(Season s) { return new NamespacedKey(plugin, s.token()); }

    ItemStack token(Season s, int amount) {
        ItemStack it = new ItemStack(Material.PAPER, Math.max(1, amount));
        ItemMeta m = it.getItemMeta();
        m.displayName(FaultlineCosmetics.text(s.color() + "&l" + s.tokenName()));
        m.lore(List.of(FaultlineCosmetics.text("&7" + s.name() + " currency."),
                FaultlineCosmetics.text("&7Spend it in the &eSeasonal Shop &7(/cosmetics)."),
                FaultlineCosmetics.text("&8Only drops during " + s.name() + " (" + when(s) + ").")));
        m.setItemModel(new NamespacedKey("faultline", s.tokenModel()));
        m.getPersistentDataContainer().set(key(s), PersistentDataType.BYTE, (byte) 1);
        m.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        it.setItemMeta(m);
        return it;
    }

    boolean isToken(ItemStack it, Season s) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key(s), PersistentDataType.BYTE);
    }

    int count(Player p, Season s) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (isToken(it, s)) n += it.getAmount();
        return n;
    }

    /** Takes that many tokens out of their inventory (only if they have them all). */
    boolean take(Player p, Season s, int amount) {
        if (count(p, s) < amount) return false;
        ItemStack[] items = p.getInventory().getStorageContents();
        int left = amount;
        for (int i = 0; i < items.length && left > 0; i++) {
            if (!isToken(items[i], s)) continue;
            int use = Math.min(left, items[i].getAmount());
            items[i].setAmount(items[i].getAmount() - use);
            if (items[i].getAmount() <= 0) items[i] = null;
            left -= use;
        }
        p.getInventory().setStorageContents(items);
        return true;
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || !(dead instanceof Enemy) || killer.getGameMode() == GameMode.CREATIVE) return;
        Season s = current();
        if (s == null || random.nextDouble() >= s.chance()) return;
        int n = s.min() + random.nextInt(Math.max(1, s.max() - s.min() + 1));
        e.getDrops().add(token(s, n));
    }

    /** Makes sure every player sees what's going on the first time they join during a season. */
    void announce(Player p) {
        Season s = current();
        if (s == null) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            FaultlineCosmetics.msg(p, s.color() + "&l" + s.name().toUpperCase(Locale.ROOT) + " IS HERE! &7Hostile mobs drop "
                    + s.color() + s.tokenName() + "&7. Spend it on " + s.name() + " cosmetics in &e/cosmetics&7 (until "
                    + when(s).split(" - ")[1] + ").");
            p.playSound(p, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.3f);
        }, 100L);
    }

    static String colorless(String s) { return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', s)); }
}
