package net.faultlinesmp.deep;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * THE GLIMMERTIDE (Deep 1.12.0): the Dark Ocean's own season. Twice... four times a year (`glimmertide.windows`, default
 * the first two weeks of Nov / Feb / May / Aug, zone America/New_York), or `/deep season start|stop|auto`:
 *  - GLIMMER PEARLS (PAPER, model faultline:glimmer_pearl, PDC faultlinedeep:glimmer_pearl): Dark Ocean creatures a player
 *    kills drop 1-2 (`drop-chance` 15%, bosses and mini bosses tagged faultline_* with 300+ max health always 8-12), and
 *  - GLIMMER SHOALS: every `shoal-seconds` (120) each player in the water of the Dark Ocean has `shoal-chance` (35%) of a
 *    shoal of glowing orbs drifting in 10-22 blocks away for a minute: swim into an orb = a pearl.
 *  - the Bioluminescent Bloom comes `bloom-multiplier` (3x) as often.
 *  - THE GLIMMER SHOP (/glimmer, a chest menu / a Bedrock form; only while the season's on): pearls for deep gear
 *    (`glimmertide.shop`: "id;MATERIAL;price;Name;console command|another", {player} = the buyer).
 *  - a boss bar in the Dark Ocean with the days left; /glimmer top = this season's best collectors (glimmertide.yml).
 *  Pearls keep between seasons. Index stat glimmer_pearls (+1 each), achievements Glimmer Collector / Tidecaller.
 */
final class Glimmertide implements Listener {
    record Offer(String id, Material icon, int price, String name, List<String> commands) { }

    static final List<String> DEFAULT_SHOP = List.of(
            "dark_loot_bag;BUNDLE;15;Dark Loot Bag;darkloot bag 1 {player}",
            "air_coral;BUBBLE_CORAL_BLOCK;10;4 Air Coral;deep give coral 4 {player}",
            "dome_small;GLASS;25;Small Dome Kit;deep give dome_small 1 {player}",
            "dome_large;TINTED_GLASS;60;Large Dome Kit;deep give dome_large 1 {player}",
            "chart;FILLED_MAP;20;Abyssal Chart;deep give chart 1 {player}",
            "sub;IRON_TRAPDOOR;80;Submersible;deep sub {player}",
            "nautilus;NAUTILUS_SHELL;12;4 Nautilus Shells;give {player} nautilus_shell 4",
            "heart;HEART_OF_THE_SEA;40;Heart of the Sea;give {player} heart_of_the_sea 1",
            "conduit;CONDUIT;70;Conduit;give {player} conduit 1",
            "trident;TRIDENT;120;Trident;give {player} trident 1",
            "sponges;SPONGE;18;8 Sponges;give {player} sponge 8",
            "lanterns;SEA_LANTERN;10;16 Sea Lanterns;give {player} sea_lantern 16",
            "mythic;ENDER_CHEST;50;Mythic Goodie Bag;givemythicbag 1 {player}",
            "worm;STRING;90;Bait Worm (the Abyssal Kraken);givekraken worm 1 {player}",
            "gamble;ECHO_SHARD;100;Dark Loot gamble (5% rare);darkloot rare {player} 0.05|darkloot bag 2 {player}");

    final FaultlineDeep pl;
    final NamespacedKey key;
    final File file;
    final Random random = new Random();
    final Map<UUID, Integer> collected = new HashMap<>();   // this season
    final Map<UUID, Long> nextShoal = new HashMap<>();
    final List<Shoal> shoals = new ArrayList<>();
    String forced = "auto";                                 // auto / on / off
    String season = "";                                     // which season the counts are for ("2026-11")
    boolean wasActive;
    BossBar bar;
    int ticks;

    final class Shoal {
        final World w;
        final UUID forWho;
        final List<ItemDisplay> orbs = new ArrayList<>();
        final List<Location> at = new ArrayList<>();
        final Location centre;
        int age;
        Shoal(World w, Location centre, UUID forWho) { this.w = w; this.centre = centre; this.forWho = forWho; }
    }

    Glimmertide(FaultlineDeep pl) {
        this.pl = pl;
        key = new NamespacedKey(pl, "glimmer_pearl");
        file = new File(pl.getDataFolder(), "glimmertide.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20L, 5L);
    }

    double cfg(String k, double def) { return pl.getConfig().getDouble("glimmertide." + k, def); }

    // =========================================================================================== when
    LocalDate today() { return LocalDate.now(zone()); }

    ZoneId zone() {
        try { return ZoneId.of(pl.getConfig().getString("glimmertide.zone", "America/New_York")); } catch (RuntimeException e) { return ZoneId.of("America/New_York"); }
    }

    List<String> windows() {
        List<String> l = pl.getConfig().getStringList("glimmertide.windows");
        return l.isEmpty() ? List.of("11-01..11-14", "02-01..02-14", "05-01..05-14", "08-01..08-14") : l;
    }

    /** The window today falls in ({start, end} as month-days), or null. */
    MonthDay[] window(LocalDate d) {
        MonthDay md = MonthDay.from(d);
        for (String w : windows()) {
            try {
                String[] p = w.split("\\.\\.");
                MonthDay a = MonthDay.parse("--" + p[0].trim()), b = MonthDay.parse("--" + p[1].trim());
                boolean in = a.isAfter(b) ? !md.isBefore(a) || !md.isAfter(b) : !md.isBefore(a) && !md.isAfter(b);
                if (in) return new MonthDay[]{a, b};
            } catch (RuntimeException ignored) { }
        }
        return null;
    }

    boolean active() {
        if (!pl.getConfig().getBoolean("glimmertide.enabled", true)) return false;
        if (forced.equals("on")) return true;
        if (forced.equals("off")) return false;
        return window(today()) != null;
    }

    /** Days left (1 = the last day), or -1 when it's forced on. */
    int daysLeft() {
        if (forced.equals("on")) return -1;
        LocalDate d = today();
        MonthDay[] w = window(d);
        if (w == null) return 0;
        LocalDate end = w[1].atYear(d.getYear());
        if (end.isBefore(d)) end = end.plusYears(1);
        return (int) (end.toEpochDay() - d.toEpochDay()) + 1;
    }

    String seasonId() { LocalDate d = today(); return forced.equals("on") ? "forced-" + d.getYear() + "-" + d.getMonthValue() : d.getYear() + "-" + d.getMonthValue(); }

    // =========================================================================================== saving
    void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        forced = y.getString("forced", "auto");
        season = y.getString("season", "");
        var s = y.getConfigurationSection("collected");
        if (s != null) for (String k : s.getKeys(false)) try { collected.put(UUID.fromString(k), s.getInt(k)); } catch (IllegalArgumentException ignored) { }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("forced", forced);
        y.set("season", season);
        for (var e : collected.entrySet()) y.set("collected." + e.getKey(), e.getValue());
        try { y.save(file); } catch (IOException e) { pl.getLogger().warning("Couldn't save glimmertide.yml: " + e.getMessage()); }
    }

    // =========================================================================================== the pearl
    ItemStack pearl(int n) {
        ItemStack it = new ItemStack(Material.PAPER, Math.max(1, Math.min(64, n)));
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Glimmer Pearl");
        m.setLore(List.of(ChatColor.GRAY + "A pearl that glows from the inside.", ChatColor.GRAY + "Found during the Glimmertide, the",
                ChatColor.GRAY + "Dark Ocean's season. Spend them at", ChatColor.GRAY + "the Glimmer Shop: " + ChatColor.WHITE + "/glimmer"));
        m.setItemModel(new NamespacedKey("faultline", "glimmer_pearl"));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isPearl(ItemStack s) { return s != null && s.getType() == Material.PAPER && s.hasItemMeta() && s.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE); }

    int count(Player p) { int n = 0; for (ItemStack s : p.getInventory().getStorageContents()) if (isPearl(s)) n += s.getAmount(); return n; }

    boolean take(Player p, int n) {
        if (count(p) < n) return false;
        ItemStack[] inv = p.getInventory().getStorageContents();
        for (int i = 0; i < inv.length && n > 0; i++) {
            if (!isPearl(inv[i])) continue;
            int use = Math.min(n, inv[i].getAmount());
            inv[i].setAmount(inv[i].getAmount() - use);
            n -= use;
            if (inv[i].getAmount() <= 0) inv[i] = null;
        }
        p.getInventory().setStorageContents(inv);
        return true;
    }

    void give(Player p, int n) {
        if (n <= 0) return;
        for (ItemStack left : p.getInventory().addItem(pearl(n)).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
        collected.merge(p.getUniqueId(), n, Integer::sum);
        pl.console("index stat " + p.getName() + " glimmer_pearls " + n);
        pl.console("index discover " + p.getName() + " glimmer_pearl");
    }

    // =========================================================================================== the season
    void tick() {
        ticks++;
        boolean on = active();
        if (on != wasActive) { wasActive = on; if (on) begin(); else end(); }
        if (!on || pl.world == null) return;
        World w = pl.world;
        if (ticks % 20 == 0) {
            if (bar != null) {
                int d = daysLeft();
                bar.setTitle(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "The Glimmertide" + ChatColor.GRAY + (d > 0 ? "  " + d + (d == 1 ? " day left" : " days left") : "") + ChatColor.DARK_GRAY + "  /glimmer");
                for (Player p : w.getPlayers()) if (!bar.getPlayers().contains(p)) bar.addPlayer(p);
                for (Player p : new ArrayList<>(bar.getPlayers())) if (!p.getWorld().equals(w)) bar.removePlayer(p);
            }
            long every = (long) (cfg("shoal-seconds", 120) * 4);
            for (Player p : w.getPlayers()) {
                if (!DeepMobs.fair(p) || !p.isInWater()) continue;
                long next = nextShoal.computeIfAbsent(p.getUniqueId(), k -> ticks + every);
                if (ticks < next) continue;
                nextShoal.put(p.getUniqueId(), ticks + every);
                if (random.nextDouble() < cfg("shoal-chance", 0.35)) shoal(p);
            }
        }
        shoalTick();
    }

    void begin() {
        if (!seasonId().equals(season)) { season = seasonId(); collected.clear(); save(); }
        bar = Bukkit.createBossBar(ChatColor.LIGHT_PURPLE + "The Glimmertide", BarColor.PINK, BarStyle.SOLID);
        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "The Glimmertide has begun! " + ChatColor.GRAY
                + "The Dark Ocean glows: its creatures drop Glimmer Pearls and shoals of light drift through the water. " + ChatColor.WHITE + "/glimmer");
        if (pl.world != null) for (Player p : pl.world.getPlayers()) {
            p.sendTitle(ChatColor.LIGHT_PURPLE + "The Glimmertide", ChatColor.GRAY + "The sea begins to glow...", 10, 60, 20);
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 0.7f);
        }
    }

    void end() {
        if (bar != null) { bar.removeAll(); bar = null; }
        clearShoals();
        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "The Glimmertide is over. " + ChatColor.GRAY + "The sea goes dark again. (Your pearls keep for the next one.)");
        save();
    }

    void shutdown() { if (bar != null) bar.removeAll(); clearShoals(); save(); }

    // =========================================================================================== shoals
    void shoal(Player p) {
        Location l = p.getLocation();
        World w = l.getWorld();
        for (int tries = 0; tries < 8; tries++) {
            double a = random.nextDouble() * Math.PI * 2, d = 10 + random.nextDouble() * 12;
            Location c = l.clone().add(Math.cos(a) * d, random.nextGaussian() * 3, Math.sin(a) * d);
            if (!w.isChunkLoaded(c.getBlockX() >> 4, c.getBlockZ() >> 4) || c.getBlock().getType() != Material.WATER) continue;
            Shoal s = new Shoal(w, c, p.getUniqueId());
            int n = 4 + random.nextInt(4);
            for (int i = 0; i < n; i++) {
                Location o = c.clone().add(random.nextGaussian() * 2, random.nextGaussian(), random.nextGaussian() * 2);
                if (o.getBlock().getType() != Material.WATER) o = c.clone();
                o.setYaw(0); o.setPitch(0);
                ItemDisplay orb = w.spawn(o, ItemDisplay.class, disp -> {
                    disp.setItemStack(pearl(1));
                    disp.setBillboard(Display.Billboard.CENTER);
                    disp.setBrightness(new Display.Brightness(15, 15));
                    disp.setGlowing(true);
                    disp.setGlowColorOverride(Color.fromRGB(255, 150, 230));
                    disp.setPersistent(false);
                    disp.addScoreboardTag("faultline_glimmer_orb");
                    disp.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.6f, 0.6f, 0.6f), new Quaternionf()));
                });
                s.orbs.add(orb);
                s.at.add(o);
            }
            shoals.add(s);
            p.playSound(c, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f);
            p.sendActionBar(ChatColor.LIGHT_PURPLE + "A glimmer shoal drifts nearby... swim through it!");
            return;
        }
    }

    void shoalTick() {
        for (Iterator<Shoal> it = shoals.iterator(); it.hasNext(); ) {
            Shoal s = it.next();
            s.age += 5;
            for (int i = s.orbs.size() - 1; i >= 0; i--) {
                ItemDisplay orb = s.orbs.get(i);
                Location o = s.at.get(i);
                if (!orb.isValid()) { s.orbs.remove(i); s.at.remove(i); continue; }
                // drift and bob
                o.add(Math.sin((s.age + i * 17) * 0.05) * 0.04, Math.sin((s.age + i * 11) * 0.08) * 0.03, Math.cos((s.age + i * 13) * 0.05) * 0.04);
                if (o.getBlock().getType() == Material.WATER) orb.teleport(o); else o.setY(orb.getLocation().getY());
                if (s.age % 10 == 0) s.w.spawnParticle(Particle.END_ROD, o, 1, 0.1, 0.1, 0.1, 0.01); // (Bedrock sees these)
                Player got = null;
                for (Player p : s.w.getPlayers()) if (DeepMobs.fair(p) && p.getLocation().add(0, 0.9, 0).distanceSquared(o) < 2.0 * 2.0) { got = p; break; }
                if (got != null) {
                    orb.remove();
                    s.orbs.remove(i); s.at.remove(i);
                    give(got, 1);
                    got.playSound(o, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1f, 1.6f);
                    s.w.spawnParticle(Particle.DUST, o, 12, 0.3, 0.3, 0.3, 0, new Particle.DustOptions(Color.fromRGB(255, 150, 230), 1.2f));
                }
            }
            if (s.orbs.isEmpty() || s.age > cfg("shoal-life-seconds", 60) * 20) {
                for (ItemDisplay orb : s.orbs) if (orb.isValid()) orb.remove();
                it.remove();
            }
        }
    }

    void clearShoals() {
        for (Shoal s : shoals) for (ItemDisplay o : s.orbs) if (o.isValid()) o.remove();
        shoals.clear();
    }

    // =========================================================================================== drops
    @EventHandler
    public void onKill(EntityDeathEvent e) {
        if (!active() || pl.world == null || !e.getEntity().getWorld().equals(pl.world)) return;
        LivingEntity v = e.getEntity();
        Player killer = v.getKiller();
        if (killer == null || v instanceof Player) return;
        boolean ours = pl.mobs != null && pl.mobs.live.containsKey(v.getUniqueId());
        boolean big = v.getScoreboardTags().stream().anyMatch(t -> t.startsWith("faultline_")) && v.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null
                && v.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getBaseValue() >= 300;
        if (big) { give(killer, 8 + random.nextInt(5)); killer.sendActionBar(ChatColor.LIGHT_PURPLE + "A shower of Glimmer Pearls!"); return; }
        if (!ours && !(v instanceof Enemy)) return;
        if (random.nextDouble() >= cfg("drop-chance", 0.15)) return;
        give(killer, 1 + (random.nextDouble() < 0.3 ? 1 : 0));
    }

    // =========================================================================================== the shop
    List<Offer> offers() {
        List<String> raw = pl.getConfig().getStringList("glimmertide.shop");
        if (raw.isEmpty()) raw = DEFAULT_SHOP;
        List<Offer> out = new ArrayList<>();
        for (String s : raw) {
            String[] p = s.split(";", 5);
            if (p.length < 5) continue;
            Material m = Material.matchMaterial(p[1].trim());
            int price;
            try { price = Math.max(1, Integer.parseInt(p[2].trim())); } catch (NumberFormatException e) { continue; }
            out.add(new Offer(p[0].trim(), m == null || !m.isItem() ? Material.PAPER : m, price, p[3].trim(), List.of(p[4].split("\\|"))));
        }
        return out;
    }

    static final class Shop implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    void openShop(Player p) {
        if (!active()) { p.sendMessage(ChatColor.GRAY + "The Glimmer Shop only opens during the Glimmertide (the Dark Ocean's season)." + next()); return; }
        List<Offer> list = offers();
        int have = count(p);
        if (Edition.bedrock(p)) {
            List<String> buttons = new ArrayList<>();
            for (Offer o : list) buttons.add(o.name + "\n§d" + o.price + " pearls");
            if (BedrockForms.menu(pl, p, "Glimmer Shop", "You have " + have + " Glimmer Pearls.", buttons, i -> { if (i >= 0 && i < list.size()) buy(p, list.get(i)); }, null)) return;
        }
        Shop holder = new Shop();
        int rows = Math.min(6, (list.size() + 9) / 9 + 1);
        Inventory inv = Bukkit.createInventory(holder, rows * 9, ChatColor.LIGHT_PURPLE + "Glimmer Shop");
        holder.inv = inv;
        for (int i = 0; i < list.size() && i < rows * 9 - 9; i++) {
            Offer o = list.get(i);
            ItemStack it = new ItemStack(o.icon);
            ItemMeta m = it.getItemMeta();
            m.setDisplayName(ChatColor.AQUA + o.name);
            m.setLore(List.of(ChatColor.LIGHT_PURPLE + "" + o.price + " Glimmer Pearls", have >= o.price ? ChatColor.YELLOW + "Click to buy" : ChatColor.RED + "Not enough pearls"));
            it.setItemMeta(m);
            inv.setItem(i, it);
        }
        ItemStack info = pearl(Math.max(1, Math.min(64, have)));
        ItemMeta m = info.getItemMeta();
        m.setDisplayName(ChatColor.LIGHT_PURPLE + "You have " + have + " Glimmer Pearls");
        int d = daysLeft();
        m.setLore(List.of(ChatColor.GRAY + "Kill Dark Ocean creatures and swim", ChatColor.GRAY + "through glimmer shoals for more.",
                ChatColor.DARK_GRAY + (d > 0 ? d + " day" + (d == 1 ? "" : "s") + " of the season left." : "")));
        info.setItemMeta(m);
        inv.setItem(rows * 9 - 5, info);
        p.openInventory(inv);
    }

    void buy(Player p, Offer o) {
        if (!active()) { p.sendMessage(ChatColor.GRAY + "The Glimmertide is over: the shop's shut."); return; }
        if (!take(p, o.price)) { p.sendMessage(ChatColor.RED + "You need " + o.price + " Glimmer Pearls (you have " + count(p) + ")."); return; }
        for (String c : o.commands) pl.console(c.trim().replace("{player}", p.getName()));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        p.sendMessage(ChatColor.LIGHT_PURPLE + "Bought: " + ChatColor.AQUA + o.name + ChatColor.GRAY + " (" + o.price + " pearls).");
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Shop shop)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        List<Offer> list = offers();
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= list.size() || slot >= shop.inv.getSize() - 9) return;
        buy(p, list.get(slot));
        p.closeInventory();
        Bukkit.getScheduler().runTask(pl, () -> { if (p.isOnline()) openShop(p); });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Shop) e.setCancelled(true); }

    String next() {
        LocalDate d = today();
        for (int i = 1; i <= 366; i++) if (window(d.plusDays(i)) != null) {
            LocalDate n = d.plusDays(i);
            return " Next one: " + n.getMonth().name().charAt(0) + n.getMonth().name().substring(1).toLowerCase(Locale.ROOT) + " " + n.getDayOfMonth() + ".";
        }
        return "";
    }

    // =========================================================================================== /glimmer
    boolean command(org.bukkit.command.CommandSender sender, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (sub.equals("top")) {
            List<Map.Entry<UUID, Integer>> l = new ArrayList<>(collected.entrySet());
            l.sort((a, b) -> b.getValue() - a.getValue());
            sender.sendMessage(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Glimmertide collectors" + ChatColor.GRAY + " (" + (season.isEmpty() ? "no season yet" : season) + ")");
            int i = 0;
            for (var e : l) { if (++i > 10) break; String n = Bukkit.getOfflinePlayer(e.getKey()).getName(); sender.sendMessage(ChatColor.GRAY + "" + i + ". " + ChatColor.WHITE + (n == null ? "?" : n) + ChatColor.GRAY + ": " + ChatColor.LIGHT_PURPLE + e.getValue()); }
            if (l.isEmpty()) sender.sendMessage(ChatColor.GRAY + "Nobody yet.");
            return true;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage(status()); return true; }
        openShop(p);
        return true;
    }

    String status() {
        return ChatColor.LIGHT_PURPLE + "The Glimmertide: " + ChatColor.WHITE + (active() ? "ON" + (daysLeft() > 0 ? " (" + daysLeft() + " days left)" : " (forced)") : "off" + next())
                + ChatColor.GRAY + "  mode " + forced + ", shoals out " + shoals.size();
    }

    /** /deep season [start|stop|auto] */
    void admin(org.bukkit.command.CommandSender sender, String what) {
        switch (what) {
            case "start", "on" -> forced = "on";
            case "stop", "off" -> forced = "off";
            case "auto" -> forced = "auto";
            case "shoal" -> { if (sender instanceof Player p) shoal(p); return; }
            default -> { sender.sendMessage(status()); sender.sendMessage(ChatColor.YELLOW + "/deep season [start|stop|auto|shoal]"); return; }
        }
        save();
        sender.sendMessage(status());
    }
}
