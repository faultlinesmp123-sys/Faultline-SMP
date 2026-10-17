package net.faultlinesmp.deep;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapCursor;
import org.bukkit.map.MapCursorCollection;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * THE ABYSSAL CHART (Deep 1.9.0): a map of the whole Dark Ocean (4096 blocks across, one pixel = 32 blocks) that fills in
 * as YOU explore it: fog everywhere you haven't been (each player's own, saved in abyss_map.yml), and once you've swum
 * somewhere it shows the depth of the floor, the Sulphur Sea and its islands, the Mermaid Den, the Trench and the Maw,
 * the Bubble, the ruins you passed, and markers for the places that matter (the Bubble, the Den, the Maw, the Sulphur
 * Works, the arrival point, the Kraken's Grave once it's open) and you. Every 1% more of the ocean you uncover is Index
 * stat chart_percent (achievement Cartographer of the Deep at 50).
 *  - craft: glow ink sacs + prismarine crystals round a map. /deep map [player]
 *  - it's one shared MapView (id in abyss_map.yml) with a per-player renderer, so everyone's copy shows their own fog
 *  - the terrain picture is worked out once from the generator's pure functions (no chunks loaded), off the main thread
 */
final class AbyssMap implements Listener {
    static final int CELL = 32, HALF = 2048, N = 128; // N x N cells = the map's pixels

    final FaultlineDeep pl;
    final NamespacedKey itemKey;
    final File file;
    final Map<UUID, BitSet> seen = new HashMap<>();
    final Map<UUID, Integer> version = new HashMap<>();
    volatile Color[] base;          // the explored look of each pixel (null until worked out)
    volatile boolean[] ruin;        // pixels holding a ruin
    MapView view;
    boolean dirty;
    int ticks;

    AbyssMap(FaultlineDeep pl) {
        this.pl = pl;
        itemKey = new NamespacedKey(pl, "abyss_map");
        file = new File(pl.getDataFolder(), "abyss_map.yml");
        load();
        try {
            ShapedRecipe r = new ShapedRecipe(new NamespacedKey(pl, "abyss_map"), item());
            r.shape("GPG", "PMP", "GPG");
            r.setIngredient('G', Material.GLOW_INK_SAC); r.setIngredient('P', Material.PRISMARINE_CRYSTALS); r.setIngredient('M', Material.MAP);
            Bukkit.addRecipe(r);
        } catch (RuntimeException ignored) { } // (already there after a reload)
        Bukkit.getScheduler().runTaskAsynchronously(pl, this::paint);
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 40L, 40L);
    }

    boolean enabled() { return pl.getConfig().getBoolean("abyss-map.enabled", true); }

    // =========================================================================================== saving
    void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        var sec = y.getConfigurationSection("seen");
        if (sec != null) for (String k : sec.getKeys(false)) {
            try { seen.put(UUID.fromString(k), BitSet.valueOf(Base64.getDecoder().decode(sec.getString(k, "")))); }
            catch (IllegalArgumentException ignored) { }
        }
        if (y.contains("view-id")) {
            try { view = Bukkit.getMap(y.getInt("view-id")); } catch (RuntimeException ignored) { }
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        if (view != null) y.set("view-id", view.getId());
        for (var e : seen.entrySet()) y.set("seen." + e.getKey(), Base64.getEncoder().encodeToString(e.getValue().toByteArray()));
        try { y.save(file); } catch (IOException e) { pl.getLogger().warning("Couldn't save abyss_map.yml: " + e.getMessage()); }
        dirty = false;
    }

    // =========================================================================================== the item
    MapView view() {
        if (view == null) {
            World w = pl.world != null ? pl.world : Bukkit.getWorlds().get(0);
            view = Bukkit.createMap(w);
            save();
        }
        if (view.getRenderers().stream().noneMatch(r -> r instanceof Renderer)) {
            for (MapRenderer r : new ArrayList<>(view.getRenderers())) view.removeRenderer(r);
            view.setTrackingPosition(false);
            view.setUnlimitedTracking(false);
            try { view.setLocked(true); } catch (RuntimeException ignored) { }
            view.addRenderer(new Renderer());
        }
        return view;
    }

    ItemStack item() {
        ItemStack s = new ItemStack(Material.FILLED_MAP);
        MapMeta m = (MapMeta) s.getItemMeta();
        m.setDisplayName(ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "Abyssal Chart");
        m.setLore(List.of(ChatColor.GRAY + "A chart of the Dark Ocean that fills in", ChatColor.GRAY + "as you explore it. Everyone sees",
                ChatColor.GRAY + "their own discoveries on it."));
        try { m.setMapView(view()); } catch (RuntimeException ignored) { } // (no worlds yet: the view is made on first use)
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        s.setItemMeta(m);
        return s;
    }

    boolean isChart(ItemStack s) {
        return s != null && s.getType() == Material.FILLED_MAP && s.hasItemMeta() && s.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    void give(Player p) {
        for (ItemStack left : p.getInventory().addItem(item()).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    // =========================================================================================== exploring
    static int cell(double v) { return (int) Math.floor((v + HALF) / CELL); }

    BitSet seen(UUID id) { return seen.computeIfAbsent(id, k -> new BitSet(N * N)); }

    /** Uncovers the cells round a spot; true if anything new showed up. */
    boolean explore(Player p, double x, double z) {
        int r = Math.max(0, pl.getConfig().getInt("abyss-map.reveal-cells", 2));
        BitSet b = seen(p.getUniqueId());
        int cx = cell(x), cz = cell(z), before = b.cardinality();
        for (int i = -r; i <= r; i++) for (int j = -r; j <= r; j++) {
            if (i * i + j * j > r * r + r) continue;
            int gx = cx + i, gz = cz + j;
            if (gx < 0 || gz < 0 || gx >= N || gz >= N) continue;
            b.set(gz * N + gx);
        }
        int after = b.cardinality();
        if (after == before) return false;
        version.merge(p.getUniqueId(), 1, Integer::sum);
        dirty = true;
        int was = before * 100 / (N * N), now = after * 100 / (N * N);
        for (int k = was; k < now; k++) pl.console("index stat " + p.getName() + " chart_percent 1");
        return true;
    }

    double percent(UUID id) { return seen(id).cardinality() * 100.0 / (N * N); }

    void tick() {
        ticks++;
        if (!enabled() || pl.world == null) return;
        for (Player p : pl.world.getPlayers()) explore(p, p.getLocation().getX(), p.getLocation().getZ());
        if (dirty && ticks % 150 == 0) save(); // every 5 minutes
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { if (dirty) save(); version.remove(e.getPlayer().getUniqueId()); }

    void shutdown() { save(); }

    // =========================================================================================== the picture
    static final Color FOG = new Color(10, 12, 22), FOG2 = new Color(16, 18, 30);

    /** Works out every pixel's colour from the generator (pure functions; run off the main thread). */
    void paint() {
        try {
            if (pl.world == null) return;
            long seed = pl.world.getSeed();
            DeepGenerator g = pl.generator;
            Color[] c = new Color[N * N];
            boolean[] ru = new boolean[N * N];
            for (int gz = 0; gz < N; gz++) for (int gx = 0; gx < N; gx++) {
                int x = gx * CELL - HALF + CELL / 2, z = gz * CELL - HALF + CELL / 2;
                c[gz * N + gx] = colour(g, seed, x, z);
            }
            for (boolean abyss : new boolean[]{false, true}) {
                int cell = abyss ? Ruins.ABYSS : Ruins.SUN;
                for (int a = -1000 / cell - 1; a <= 1000 / cell + 1; a++) for (int b = -1000 / cell - 1; b <= 1000 / cell + 1; b++) {
                    Ruins.Site s = Ruins.site(seed, a, b, abyss);
                    if (s == null) continue;
                    int px = cell(s.x()), pz = cell(s.z());
                    if (px >= 0 && pz >= 0 && px < N && pz < N) ru[pz * N + px] = true;
                }
            }
            ruin = ru;
            base = c;
            Bukkit.getScheduler().runTask(pl, () -> version.replaceAll((k, v) -> v + 1));
        } catch (RuntimeException | LinkageError e) {
            pl.getLogger().warning("[Abyssal Chart] couldn't draw the ocean: " + e);
        }
    }

    static Color colour(DeepGenerator g, long seed, int x, int z) {
        if (Math.abs(x) > HALF - 48 || Math.abs(z) > HALF - 48) return new Color(4, 4, 8);
        double radius = 2000;
        if (Math.abs(x) > radius || Math.abs(z) > radius) return new Color(6, 6, 12);     // past the border
        if (Town.e(x, Town.FLOOR + 1, z) < 1) {                                          // the Bubble
            if (x >= Town.PLOT_X0 && x <= Town.PLOT_X1 && z >= Town.PLOT_Z0 && z <= Town.PLOT_Z1) return new Color(52, 40, 64);
            return new Color(150, 220, 235);
        }
        if (Math.hypot(x - KrakenGrave.GX, z - KrakenGrave.GZ) < KrakenGrave.R) return new Color(70, 20, 40);
        if (Math.hypot(x - Trench.LX, z - Trench.LZ) < Trench.LAIR_R) return new Color(80, 10, 18);
        if (g.trenchColumn(seed, x, z)) return new Color(20, 6, 30);
        double den = MermaidDen.weight(x, z);
        int floor = g.seaFloor(seed, x, z);
        if (den > 0.95) {
            double d = Math.hypot(x - MermaidDen.MX, z - MermaidDen.MZ);
            if (d < MermaidDen.PALACE_R + 4) return new Color(240, 200, 120);
            return d > MermaidDen.R ? new Color(230, 110, 150) : new Color(40, 170, 175);
        }
        double sul = g.sulphur(seed, x, z);
        if (sul > 0.35) {
            if (floor >= DeepGenerator.SEA) return new Color(150, 140, 60);              // an island
            double t = Math.max(0, Math.min(1, (floor - (DeepGenerator.SEA - 60)) / 60.0));
            return mix(new Color(46, 60, 20), new Color(110, 130, 40), t);
        }
        // open sea: the deeper the floor, the darker the blue
        double t = Math.max(0, Math.min(1, (floor + 40) / 140.0));
        Color c = mix(new Color(10, 22, 70), new Color(40, 110, 170), t);
        if (den > 0.05) c = mix(c, new Color(200, 90, 130), den * 0.6);                    // the Den's outer cliff
        return c;
    }

    static Color mix(Color a, Color b, double t) {
        t = Math.max(0, Math.min(1, t));
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    /** The places that get a marker: x, z, type, name. */
    List<Object[]> landmarks() {
        List<Object[]> l = new ArrayList<>();
        l.add(new Object[]{0, 0, MapCursor.Type.TARGET_POINT, "Arrival"});
        l.add(new Object[]{Town.CX, Town.CZ, MapCursor.Type.MANSION, "The Bubble"});
        l.add(new Object[]{MermaidDen.MX, MermaidDen.MZ, MapCursor.Type.MONUMENT, "The Mermaid Den"});
        l.add(new Object[]{Sulphur.WORKS_X, Sulphur.WORKS_Z, MapCursor.Type.BANNER_LIME, "The Sulphur Works"});
        l.add(new Object[]{Trench.LX, Trench.LZ, MapCursor.Type.RED_X, "The Maw"});
        if (pl.grave != null && pl.grave.open()) l.add(new Object[]{KrakenGrave.GX, KrakenGrave.GZ, MapCursor.Type.BANNER_BLACK, "The Kraken's Grave"});
        return l;
    }

    static byte px(double v) { return (byte) Math.max(-128, Math.min(127, Math.round((v + HALF) / (double) (2 * HALF) * 256 - 128))); }

    final class Renderer extends MapRenderer {
        final Map<UUID, Integer> drawn = new HashMap<>();

        Renderer() { super(true); }

        @Override
        public void render(MapView map, MapCanvas canvas, Player p) {
            UUID id = p.getUniqueId();
            Color[] c = base;
            int v = version.getOrDefault(id, 0) + (c == null ? 0 : 1_000_000);
            Integer was = drawn.get(id);
            if (was == null || was != v) {
                drawn.put(id, v);
                BitSet b = seen(id);
                boolean[] ru = ruin;
                for (int z = 0; z < N; z++) for (int x = 0; x < N; x++) {
                    int i = z * N + x;
                    Color col;
                    if (!b.get(i)) col = ((x * 7 + z * 13) % 5 == 0) ? FOG2 : FOG;
                    else if (c == null) col = new Color(30, 60, 110);
                    else if (ru != null && ru[i]) col = new Color(170, 150, 110);
                    else col = c[i];
                    canvas.setPixelColor(x, z, col);
                }
            }
            MapCursorCollection cur = new MapCursorCollection();
            BitSet b = seen(id);
            for (Object[] lm : landmarks()) {
                int gx = cell((int) lm[0]), gz = cell((int) lm[1]);
                if (gx < 0 || gz < 0 || gx >= N || gz >= N || !b.get(gz * N + gx)) continue;
                cur.addCursor(new MapCursor(px((int) lm[0]), px((int) lm[1]), (byte) 8, (MapCursor.Type) lm[2], true, (String) lm[3]));
            }
            if (pl.world != null && p.getWorld() == pl.world) {
                float yaw = p.getLocation().getYaw();
                byte dir = (byte) (Math.floorMod(Math.round((yaw + 180) / 22.5f) + 8, 16));
                cur.addCursor(new MapCursor(px(p.getLocation().getX()), px(p.getLocation().getZ()), dir, MapCursor.Type.PLAYER, true));
            }
            canvas.setCursors(cur);
        }
    }

    String status(Player p) {
        return ChatColor.DARK_AQUA + "Abyssal Chart: " + ChatColor.WHITE + String.format(java.util.Locale.ROOT, "%.1f%%", percent(p.getUniqueId()))
                + ChatColor.GRAY + " of the Dark Ocean explored.";
    }
}
