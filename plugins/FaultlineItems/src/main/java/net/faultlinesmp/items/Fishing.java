package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * FISHING TOURNAMENTS + RECORDS (Items 1.2.18, config fishing:, records in fishing.yml).
 * Every fish anyone catches gets a weight (each species its own range, big ones rare, 1% "monster" x1.5). Beat the
 * all-time record for that species and the whole server hears about it, and that fish becomes a Trophy Fish (its weight,
 * who and when on it). A TOURNAMENT (/fishing start [minutes], or every fishing.auto-every-hours) scores each player's
 * single best fish in points (weight / the species' top weight x 100, so a big Tropical Fish counts like a big Salmon);
 * a boss bar shows time and the leader; at the end the top 3 get fishing.rewards.*, the winner index stat fishing_wins.
 * /fishing [top|records], admin /fishing start [minutes] | stop.
 *
 * HADAL FISHING (1.2.59): in the Dark Ocean (FaultlineDeep tells us which waters the hook is in, by reflection:
 * FaultlineDeep.fishZone) a catch is often one of its own fish instead: each water has its own species (the Sunlit Sea,
 * the Abyss below y 35, the Sulphur Sea, the Mermaid Den, and the Trench: a line dropped anywhere over the rift reaches
 * it). They're real food (cod / salmon / tropical fish underneath) with their own model faultline:fish/<id> and PDC
 * faultlineitems:fish_species, weighed and recorded like the rest (records by species id), Index stat hadal_fish, and the
 * Trench's legendary The Blind King (index stat blind_king).
 */
final class Fishing implements Listener, CommandExecutor, TabCompleter {

    /** A species: id (the vanilla ones are their Material's name), min/max kg, the item underneath, the waters it lives
     *  in (null = vanilla), how common it is there, and a line about it. */
    record Species(String id, String name, double min, double max, Material base, String zones, int weight, String about) {
        boolean hadal() { return zones != null; }
        boolean in(String zone) { return zones != null && ("," + zones + ",").contains("," + zone + ","); }
    }

    static final Map<String, Species> SPECIES = new LinkedHashMap<>();
    static void sp(String id, String name, double min, double max, Material base, String zones, int weight, String about) {
        SPECIES.put(id, new Species(id, name, min, max, base, zones, weight, about));
    }
    static {
        sp("COD", "Cod", 0.4, 4.5, Material.COD, null, 0, null);
        sp("SALMON", "Salmon", 0.8, 9.0, Material.SALMON, null, 0, null);
        sp("PUFFERFISH", "Pufferfish", 0.3, 2.5, Material.PUFFERFISH, null, 0, null);
        sp("TROPICAL_FISH", "Tropical Fish", 0.05, 0.6, Material.TROPICAL_FISH, null, 0, null);
        // the Dark Ocean (1.2.58)
        sp("gloomfin", "Gloomfin", 0.5, 6, Material.COD, "SUNLIT", 10, "A grey-blue fish of the Sunlit Sea's open water.");
        sp("shadow_mackerel", "Shadow Mackerel", 0.4, 3.5, Material.COD, "SUNLIT", 10, "Schools under the dark surface, striped black.");
        sp("pearl_snapper", "Pearl Snapper", 1, 8, Material.SALMON, "SUNLIT,DEN", 5, "Pink, pearly scales. Mermaids prize it.");
        sp("lanternjaw", "Lanternjaw", 0.3, 4, Material.COD, "ABYSS", 10, "Its glowing barbel lights the Abyss for a heartbeat.");
        sp("grenadier", "Abyssal Grenadier", 1, 12, Material.SALMON, "ABYSS", 8, "A rat-tailed deep fish with huge eyes.");
        sp("ghost_eel", "Ghost Eel", 2, 15, Material.SALMON, "ABYSS,TRENCH", 5, "See-through. You can watch its heart beat.");
        sp("acid_gar", "Acid Gar", 1, 10, Material.SALMON, "SULPHUR", 8, "Armoured in green scales the acid can't eat.");
        sp("sulphur_sprat", "Sulphur Sprat", 0.1, 0.8, Material.COD, "SULPHUR", 10, "Tiny, yellow, and tastes like a struck match.");
        sp("mermaid_tetra", "Mermaid's Tetra", 0.05, 0.5, Material.TROPICAL_FISH, "DEN", 10, "The Den's pet fish: blue and gold.");
        sp("coral_princess", "Coral Princess", 0.2, 2.4, Material.TROPICAL_FISH, "DEN", 6, "Frilled fins like a ballgown.");
        sp("hadal_snailfish", "Hadal Snailfish", 0.5, 5, Material.COD, "TRENCH", 10, "Pale and soft: the deepest fish there is.");
        sp("coelacanth", "Abyss Coelacanth", 10, 90, Material.SALMON, "TRENCH", 5, "Older than the Titan. Lobed fins like little legs.");
        sp("titan_isopod", "Titan Isopod", 2, 20, Material.COD, "TRENCH", 6, "Not a fish. Bites the hook anyway.");
        sp("blind_king", "The Blind King", 50, 300, Material.SALMON, "TRENCH", 1, "The Trench's legend: eyeless, ancient, enormous.");
    }

    /** The species of a vanilla fish Material, or null. */
    static Species vanilla(Material m) { Species s = SPECIES.get(m.name()); return s != null && !s.hadal() ? s : null; }

    record Catch(UUID who, String name, Species species, double kg, double points) { }
    record Record(double kg, String name, long when) { }

    private final FaultlineItems plugin;
    private final File file;
    final NamespacedKey weightKey;
    private final Random random = new Random();
    final Map<String, Record> records = new HashMap<>();
    final NamespacedKey speciesKey;

    // the tournament
    long endsAt, lengthMs;
    final Map<UUID, Catch> best = new HashMap<>();
    BossBar bar;
    private long nextAuto;

    Fishing(FaultlineItems plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "fishing.yml");
        weightKey = new NamespacedKey(plugin, "fish_weight");
        speciesKey = new NamespacedKey(plugin, "fish_species");
        load();
        scheduleAuto();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    boolean running() { return endsAt > 0; }

    void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String id : SPECIES.keySet()) {
            String b = "records." + id;
            if (y.contains(b + ".kg")) records.put(id, new Record(y.getDouble(b + ".kg"), y.getString(b + ".name", "?"), y.getLong(b + ".when")));
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : records.entrySet()) {
            String b = "records." + e.getKey();
            y.set(b + ".kg", e.getValue().kg()); y.set(b + ".name", e.getValue().name()); y.set(b + ".when", e.getValue().when());
        }
        try { y.save(file); } catch (IOException ex) { plugin.getLogger().warning("Couldn't save fishing.yml: " + ex.getMessage()); }
    }

    private void scheduleAuto() {
        double h = plugin.getConfig().getDouble("fishing.auto-every-hours", 0);
        nextAuto = h > 0 ? System.currentTimeMillis() + (long) (h * 3600000) : 0;
    }

    static String name(Species s) { return s.name(); }

    static String kg(double v) { return String.format(Locale.ROOT, "%.2f kg", v); }

    /** A weight for a fresh catch: mostly small, big ones rare, now and then a monster. */
    double weigh(Species s) {
        double w = s.min() + (s.max() - s.min()) * Math.pow(random.nextDouble(), 2.2);
        if (random.nextDouble() < plugin.getConfig().getDouble("fishing.monster-chance", 0.01)) w *= 1.5;
        return Math.round(w * 100) / 100.0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(e.getCaught() instanceof Item item)) return;
        if (!plugin.getConfig().getBoolean("fishing.enabled", true)) return;
        ItemStack fish = item.getItemStack();
        Species s = vanilla(fish.getType());
        if (s == null) return;
        String zone = darkWaters(e.getHook() != null ? e.getHook().getLocation() : item.getLocation());
        Species h = zone == null ? null : hadal(zone);
        if (h != null) {
            s = h;
            item.setItemStack(hadalItem(h, fish.getAmount()));
            index(e.getPlayer(), "hadal_fish");
            if (h.id().equals("blind_king")) {
                index(e.getPlayer(), "blind_king");
                Bukkit.broadcastMessage(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + e.getPlayer().getName() + ChatColor.AQUA
                        + " pulled THE BLIND KING out of the Trench!");
            }
        }
        caught(e.getPlayer(), item, s, weigh(s));
    }

    // ============================================================================================ hadal fishing
    /** FaultlineDeep's name for the waters a hook is in, or null (not the Dark Ocean, or no FaultlineDeep). */
    static String darkWaters(org.bukkit.Location l) {
        if (Bukkit.getPluginManager().getPlugin("FaultlineDeep") == null) return null;
        try {
            Object r = Class.forName("net.faultlinesmp.deep.FaultlineDeep").getMethod("fishZone", org.bukkit.Location.class).invoke(null, l);
            return r instanceof String z ? z : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) { return null; }
    }

    /** One of the Dark Ocean's fish for those waters (often: hadal.chance, always in the Trench), or null = a plain fish. */
    Species hadal(String zone) {
        if (!plugin.getConfig().getBoolean("fishing.hadal.enabled", true) || zone.equals("BUBBLE")) return null;
        double chance = zone.equals("TRENCH") ? 1 : plugin.getConfig().getDouble("fishing.hadal.chance", 0.7);
        if (random.nextDouble() >= chance) return null;
        int total = 0;
        for (Species s : SPECIES.values()) if (s.in(zone)) total += s.weight();
        if (total <= 0) return null;
        int r = random.nextInt(total);
        for (Species s : SPECIES.values()) if (s.in(zone) && (r -= s.weight()) < 0) return s;
        return null;
    }

    ItemStack hadalItem(Species s, int amount) {
        ItemStack it = new ItemStack(s.base(), Math.max(1, amount));
        ItemMeta m = it.getItemMeta();
        m.setDisplayName((s.id().equals("blind_king") ? ChatColor.GOLD + "" + ChatColor.BOLD : ChatColor.AQUA + "") + s.name());
        m.setLore(new ArrayList<>(List.of(ChatColor.GRAY + s.about(), ChatColor.DARK_AQUA + "A fish of the Dark Ocean")));
        m.setItemModel(new NamespacedKey("faultline", "fish/" + s.id()));
        m.getPersistentDataContainer().set(speciesKey, PersistentDataType.STRING, s.id());
        it.setItemMeta(m);
        return it;
    }

    void index(Player p, String stat) {
        if (Bukkit.getPluginManager().getPlugin("FaultlineIndex") != null)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + p.getName() + " " + stat + " 1");
    }

    void caught(Player p, Item item, Species species, double kg) {
        double points = Math.round(kg / species.max() * 1000) / 10.0;
        p.sendActionBar(ChatColor.AQUA + "You caught a " + ChatColor.WHITE + kg(kg) + " " + name(species)
                + (running() ? ChatColor.GRAY + " (" + points + " points)" : ""));
        Record rec = records.get(species.id());
        boolean record = rec == null || kg > rec.kg();
        if (record) {
            records.put(species.id(), new Record(kg, p.getName(), System.currentTimeMillis()));
            save();
            if (rec != null || plugin.getConfig().getBoolean("fishing.announce-first-records", false))
                Bukkit.broadcastMessage(ChatColor.AQUA + "🎣 NEW RECORD: " + ChatColor.WHITE + p.getName() + ChatColor.AQUA + " caught a "
                        + ChatColor.WHITE + kg(kg) + " " + name(species) + ChatColor.GRAY + (rec != null ? " (old record " + kg(rec.kg()) + " by " + rec.name() + ")" : ""));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.4f);
        }
        if (item != null && (record || (running() && plugin.getConfig().getBoolean("fishing.tag-fish-in-tournament", true)))) {
            ItemStack fish = item.getItemStack().clone();
            ItemMeta m = fish.getItemMeta();
            if (record) m.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Trophy " + name(species));
            List<String> lore = new ArrayList<>(List.of(ChatColor.GRAY + kg(kg) + (record ? ChatColor.GOLD + " (server record)" : ""),
                    ChatColor.DARK_GRAY + "Caught by " + p.getName() + ", " + new SimpleDateFormat("d MMM yyyy").format(new Date())));
            if (species.hadal()) { lore.add(ChatColor.GRAY + species.about()); lore.add(ChatColor.DARK_AQUA + "A fish of the Dark Ocean"); }
            m.setLore(lore);
            m.getPersistentDataContainer().set(weightKey, PersistentDataType.DOUBLE, kg);
            fish.setItemMeta(m);
            item.setItemStack(fish);
        }
        if (running()) {
            Catch c = best.get(p.getUniqueId());
            if (c == null || points > c.points()) {
                best.put(p.getUniqueId(), new Catch(p.getUniqueId(), p.getName(), species, kg, points));
                if (bar != null && !bar.getPlayers().contains(p)) bar.addPlayer(p);
            }
        }
    }

    List<Catch> standings() {
        List<Catch> l = new ArrayList<>(best.values());
        l.sort((a, b) -> Double.compare(b.points(), a.points()));
        return l;
    }

    boolean start(int minutes) {
        if (running()) return false;
        best.clear();
        lengthMs = minutes * 60000L;
        endsAt = System.currentTimeMillis() + lengthMs;
        bar = Bukkit.createBossBar(ChatColor.AQUA + "Fishing Tournament", BarColor.BLUE, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) bar.addPlayer(p);
        Bukkit.broadcastMessage(ChatColor.AQUA + "🎣 A FISHING TOURNAMENT has begun! " + ChatColor.GRAY + minutes
                + " minutes: your single best fish counts (points = weight against that species' biggest). /fishing top");
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_SPLASH, 1f, 0.8f);
        return true;
    }

    void stop(boolean award) {
        if (!running()) return;
        endsAt = 0;
        if (bar != null) { bar.removeAll(); bar = null; }
        List<Catch> top = standings();
        if (!award || top.isEmpty()) {
            Bukkit.broadcastMessage(ChatColor.AQUA + "🎣 The fishing tournament is over" + (top.isEmpty() ? ": nobody caught anything." : "."));
            best.clear();
            return;
        }
        StringBuilder s = new StringBuilder(ChatColor.AQUA + "🎣 The fishing tournament is over!");
        String[] keys = {"first", "second", "third"};
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            Catch c = top.get(i);
            s.append("\n ").append(ChatColor.GOLD).append(i + 1).append(". ").append(ChatColor.WHITE).append(c.name()).append(ChatColor.GRAY)
                    .append(" ").append(kg(c.kg())).append(" ").append(name(c.species())).append(" (").append(c.points()).append(" points)");
            Player p = Bukkit.getPlayer(c.who());
            for (String cmd : plugin.getConfig().getStringList("fishing.rewards." + keys[i]))
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", c.name()));
            if (i == 0 && Bukkit.getPluginManager().getPlugin("FaultlineIndex") != null)
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + c.name() + " fishing_wins 1");
            if (p != null) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        Bukkit.broadcastMessage(s.toString());
        best.clear();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (!running() && nextAuto > 0 && now >= nextAuto) {
            if (Bukkit.getOnlinePlayers().size() >= plugin.getConfig().getInt("fishing.auto-min-players", 2))
                start(plugin.getConfig().getInt("fishing.minutes", 15));
            scheduleAuto();
        }
        if (!running()) return;
        if (now >= endsAt) { stop(true); return; }
        long left = (endsAt - now) / 1000;
        List<Catch> top = standings();
        bar.setTitle(ChatColor.AQUA + "Fishing Tournament " + ChatColor.WHITE + String.format("%d:%02d", left / 60, left % 60)
                + (top.isEmpty() ? "" : ChatColor.GRAY + "  ·  leader " + ChatColor.WHITE + top.get(0).name() + ChatColor.GRAY + " " + kg(top.get(0).kg()) + " " + name(top.get(0).species())));
        bar.setProgress(Math.max(0, Math.min(1, (endsAt - now) / (double) Math.max(1, lengthMs))));
        for (Player p : Bukkit.getOnlinePlayers()) if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
    }

    void shutdown() { if (running()) stop(false); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? (running() ? "top" : "records") : args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("items.fishing.admin");
        switch (sub) {
            case "start" -> {
                if (!admin) { sender.sendMessage(ChatColor.RED + "You don't have permission to do that."); return true; }
                int min = plugin.getConfig().getInt("fishing.minutes", 15);
                if (args.length > 1) try { min = Math.max(1, Math.min(180, Integer.parseInt(args[1]))); } catch (NumberFormatException ignored) { }
                if (!start(min)) sender.sendMessage(ChatColor.RED + "A tournament is already on (/fishing stop).");
            }
            case "stop" -> {
                if (!admin) { sender.sendMessage(ChatColor.RED + "You don't have permission to do that."); return true; }
                if (!running()) sender.sendMessage(ChatColor.RED + "No tournament is on.");
                else stop(args.length > 1 && args[1].equalsIgnoreCase("award"));
            }
            case "top" -> {
                if (!running()) { sender.sendMessage(ChatColor.GRAY + "No tournament is on. /fishing records for the all-time biggest."); return true; }
                List<Catch> top = standings();
                sender.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "🎣 Tournament standings");
                for (int i = 0; i < Math.min(10, top.size()); i++) { Catch c = top.get(i);
                    sender.sendMessage(ChatColor.GRAY + " " + (i + 1) + ". " + ChatColor.WHITE + c.name() + ChatColor.GRAY + " " + kg(c.kg()) + " " + name(c.species()) + " (" + c.points() + ")"); }
                if (top.isEmpty()) sender.sendMessage(ChatColor.GRAY + " Nobody has caught anything yet.");
            }
            default -> {
                sender.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "🎣 Server fishing records");
                boolean dark = false;
                for (Species sp : SPECIES.values()) {
                    if (sp.hadal() && !dark) { dark = true; sender.sendMessage(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + " The Dark Ocean"); }
                    Record r = records.get(sp.id());
                    if (sp.hadal() && r == null && !(args.length > 1 && args[1].equalsIgnoreCase("all"))) continue; // undiscovered: a secret
                    sender.sendMessage(ChatColor.GRAY + " " + name(sp) + ": " + (r == null ? "none yet" : ChatColor.WHITE + kg(r.kg()) + ChatColor.GRAY + " by " + r.name()));
                }
                if (admin) sender.sendMessage(ChatColor.DARK_GRAY + "Admin: /fishing start [minutes] | stop [award]");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return sender.hasPermission("items.fishing.admin") ? List.of("top", "records", "start", "stop") : List.of("top", "records");
        return List.of();
    }
}
