package net.faultlinesmp.deep;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Living down there (Deep 1.9.0).
 *  AIR CORAL (PAPER, model faultline:air_coral, PDC faultlinedeep:air_coral; recipe: a tube coral block on 4 prismarine
 *  crystals round a nautilus shell): right-click a block under water in the Dark Ocean = a BUBBLE CORAL BLOCK that breathes:
 *  anyone within `habitat.coral.radius` 3.5 gets `refill` 60 air a second and bubbles stream up from it. Recorded in the
 *  chunk PDC (`faultlinedeep:air_corals`, "x,y,z;..."), so only ours breathe; breaking one gives the item back.
 *  DOME KITS (PAPER, model faultline:dome_kit_small / dome_kit_large, PDC faultlinedeep:dome_kit = radius; Small 6, Large
 *  10 (a Small kit in the middle of 4 tinted glass + 4 sea lanterns)): right-click the sea floor = a glass dome inflates
 *  there (hemisphere of glass with a sea-lantern ring at its foot, a glass floor where the floor is open, the water inside
 *  turned to air: a dry base). Each changed block's old state is kept in domes.yml. The shell can't be broken, blown up or
 *  pushed (by anyone: one hole floods it); the owner (or `faultlinedeep.market`) sneak + right-clicks the shell with an empty
 *  hand to DISMANTLE it: the sea comes back (only where the dome's own glass/air still is) and the kit is returned.
 *  Domes count as dry (Market.dry): no pressure, no Abyss dark, your drops stay. `habitat.domes.max-per-player` 3; never
 *  in the Bubble, the Den, the Drowned City, the Glass Reef's spire, over the sea or overlapping another dome.
 */
final class Habitat implements Listener {
    static Habitat instance;
    final FaultlineDeep pl;
    final NamespacedKey coralItem, coralKey, kitKey;
    final Set<Long> corals = new HashSet<>();                // loaded air corals (packed positions)
    final Map<Integer, Dome> domes = new LinkedHashMap<>();
    final Map<Long, Integer> shell = new HashMap<>();        // shell block -> dome id
    final File file;
    int nextId = 1;
    int secs;

    static final class Dome {
        int id, x, y, z, r;
        UUID owner;
        final Map<Long, String> before = new LinkedHashMap<>();
        boolean inside(double px, double py, double pz) { return py >= y - 0.5 && (px - x - 0.5) * (px - x - 0.5) + (py - y) * (py - y) + (pz - z - 0.5) * (pz - z - 0.5) < (r - 0.6) * (r - 0.6); }
    }

    Habitat(FaultlineDeep pl) {
        this.pl = pl;
        instance = this;
        coralItem = new NamespacedKey(pl, "air_coral");
        coralKey = new NamespacedKey(pl, "air_corals");
        kitKey = new NamespacedKey(pl, "dome_kit");
        file = new File(pl.getDataFolder(), "domes.yml");
        load();
        try {
            ShapedRecipe c = new ShapedRecipe(new NamespacedKey(pl, "air_coral"), coral(1));
            c.shape(" T ", "PNP", " P ");
            c.setIngredient('T', Material.TUBE_CORAL_BLOCK); c.setIngredient('P', Material.PRISMARINE_CRYSTALS); c.setIngredient('N', Material.NAUTILUS_SHELL);
            Bukkit.addRecipe(c);
            ShapedRecipe s = new ShapedRecipe(new NamespacedKey(pl, "dome_kit_small"), kit(6));
            s.shape("GGG", "GSG", "LHL");
            s.setIngredient('G', Material.GLASS); s.setIngredient('S', Material.SPONGE); s.setIngredient('L', Material.SEA_LANTERN); s.setIngredient('H', Material.NAUTILUS_SHELL);
            Bukkit.addRecipe(s);
            ShapedRecipe l = new ShapedRecipe(new NamespacedKey(pl, "dome_kit_large"), kit(10));
            l.shape("LTL", "TKT", "LTL");
            l.setIngredient('L', Material.SEA_LANTERN); l.setIngredient('T', Material.TINTED_GLASS); l.setIngredient('K', new RecipeChoice.ExactChoice(kit(6)));
            Bukkit.addRecipe(l);
        } catch (RuntimeException ignored) { } // (already there after a reload)
        if (pl.world != null) for (Chunk ch : pl.world.getLoadedChunks()) loadCorals(ch);
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 20L, 20L);
    }

    double cfg(String k, double def) { return pl.getConfig().getDouble("habitat." + k, def); }

    static long pack(int x, int y, int z) { return Volcano.pack(x, y, z); }

    // =========================================================================================== items
    ItemStack coral(int n) {
        ItemStack it = new ItemStack(Material.PAPER, n);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.AQUA + "Air Coral");
        m.setLore(List.of(ChatColor.GRAY + "A coral that breathes out air.", ChatColor.GRAY + "Plant it under water in the Dark Ocean:",
                ChatColor.GRAY + "anyone close by can breathe.", ChatColor.DARK_GRAY + "Break it to pick it up again."));
        m.setItemModel(new NamespacedKey("faultline", "air_coral"));
        m.getPersistentDataContainer().set(coralItem, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    ItemStack kit(int r) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        boolean big = r >= 10;
        m.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + (big ? "Large" : "Small") + " Dome Kit");
        m.setLore(List.of(ChatColor.GRAY + "Right-click the sea floor: a glass dome", ChatColor.GRAY + "inflates there and pushes the sea out.",
                ChatColor.AQUA + "Radius " + r + ChatColor.GRAY + " (" + (2 * r + 1) + " blocks across)",
                ChatColor.DARK_GRAY + "Sneak + right-click its glass with an empty", ChatColor.DARK_GRAY + "hand to pack it away again."));
        m.setItemModel(new NamespacedKey("faultline", big ? "dome_kit_large" : "dome_kit_small"));
        m.setMaxStackSize(1);
        m.getPersistentDataContainer().set(kitKey, PersistentDataType.INTEGER, r);
        it.setItemMeta(m);
        return it;
    }

    boolean isCoral(ItemStack it) { return it != null && it.getType() == Material.PAPER && it.getPersistentDataContainer().has(coralItem, PersistentDataType.BYTE); }

    int kitRadius(ItemStack it) { return it == null || it.getType() != Material.PAPER ? 0 : it.getPersistentDataContainer().getOrDefault(kitKey, PersistentDataType.INTEGER, 0); }

    static boolean wet(Block b) { return Submersible.water(b) || b.getBlockData() instanceof org.bukkit.block.data.Waterlogged w && w.isWaterlogged(); }

    // =========================================================================================== placing
    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        Block clicked = e.getClickedBlock();
        ItemStack hand = p.getInventory().getItemInMainHand();
        // dismantling a dome
        Integer id = shell.get(pack(clicked.getX(), clicked.getY(), clicked.getZ()));
        if (id != null && p.isSneaking() && hand.getType().isAir()) {
            e.setCancelled(true);
            Dome d = domes.get(id);
            if (d == null) return;
            if (!d.owner.equals(p.getUniqueId()) && !p.hasPermission("faultlinedeep.market")) { p.sendActionBar(ChatColor.RED + "It isn't your dome."); return; }
            dismantle(d, p);
            return;
        }
        boolean coral = isCoral(hand);
        int r = kitRadius(hand);
        if (!coral && r == 0) return;
        e.setCancelled(true);
        if (pl.world == null || !clicked.getWorld().equals(pl.world)) { p.sendActionBar(ChatColor.RED + "Only in the Dark Ocean."); return; }
        Block at = clicked.getRelative(e.getBlockFace());
        if (coral) {
            if (!wet(at) || at.getType() != Material.WATER && at.getType() != Material.SEAGRASS) { p.sendActionBar(ChatColor.RED + "Plant it under water."); return; }
            if (Market.dry(at.getX() + 0.5, at.getY(), at.getZ() + 0.5)) { p.sendActionBar(ChatColor.RED + "There's air here already."); return; }
            at.setType(Material.BUBBLE_CORAL_BLOCK, false);
            addCoral(at);
            if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
            at.getWorld().playSound(at.getLocation(), Sound.BLOCK_CORAL_BLOCK_PLACE, 1f, 1.2f);
            at.getWorld().spawnParticle(Particle.BUBBLE_COLUMN_UP, at.getLocation().add(0.5, 1, 0.5), 20, 0.3, 0.5, 0.3, 0.1);
            pl.console("index discover " + p.getName() + " air_coral");
            return;
        }
        String why = inflate(p, at, r);
        if (why != null) { p.sendMessage(ChatColor.RED + why); return; }
        if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
    }

    // =========================================================================================== air coral
    void addCoral(Block b) {
        corals.add(pack(b.getX(), b.getY(), b.getZ()));
        Chunk c = b.getChunk();
        String old = c.getPersistentDataContainer().getOrDefault(coralKey, PersistentDataType.STRING, "");
        String me = b.getX() + "," + b.getY() + "," + b.getZ();
        c.getPersistentDataContainer().set(coralKey, PersistentDataType.STRING, old.isEmpty() ? me : old + ";" + me);
    }

    boolean removeCoral(Block b) {
        if (!corals.remove(pack(b.getX(), b.getY(), b.getZ()))) return false;
        Chunk c = b.getChunk();
        String me = b.getX() + "," + b.getY() + "," + b.getZ();
        List<String> keep = new ArrayList<>();
        for (String s : c.getPersistentDataContainer().getOrDefault(coralKey, PersistentDataType.STRING, "").split(";")) if (!s.isEmpty() && !s.equals(me)) keep.add(s);
        if (keep.isEmpty()) c.getPersistentDataContainer().remove(coralKey);
        else c.getPersistentDataContainer().set(coralKey, PersistentDataType.STRING, String.join(";", keep));
        return true;
    }

    void loadCorals(Chunk c) {
        String s = c.getPersistentDataContainer().get(coralKey, PersistentDataType.STRING);
        if (s == null) return;
        for (String part : s.split(";")) {
            String[] xyz = part.split(",");
            if (xyz.length == 3) try { corals.add(pack(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]))); } catch (NumberFormatException ignored) { }
        }
    }

    @EventHandler
    public void onLoad(ChunkLoadEvent e) { if (pl.world != null && e.getWorld().equals(pl.world)) loadCorals(e.getChunk()); }

    @EventHandler
    public void onUnload(ChunkUnloadEvent e) {
        if (pl.world == null || !e.getWorld().equals(pl.world)) return;
        int cx = e.getChunk().getX(), cz = e.getChunk().getZ();
        corals.removeIf(k -> { int[] p = Volcano.unpack(k); return p[0] >> 4 == cx && p[2] >> 4 == cz; });
    }

    void tick() {
        secs++;
        World w = pl.world;
        if (w == null || corals.isEmpty()) return;
        double r = cfg("coral.radius", 3.5);
        for (long k : new ArrayList<>(corals)) {
            int[] c = Volcano.unpack(k);
            Block b = w.getBlockAt(c[0], c[1], c[2]);
            if (b.getType() != Material.BUBBLE_CORAL_BLOCK && b.getType() != Material.DEAD_BUBBLE_CORAL_BLOCK) { removeCoral(b); continue; }
            Location l = b.getLocation().add(0.5, 1, 0.5);
            boolean anyone = false;
            for (Player p : w.getPlayers()) {
                double d = p.getLocation().distanceSquared(l);
                if (d < 32 * 32) anyone = true;
                if (d < r * r) p.setRemainingAir(Math.min(p.getMaximumAir(), p.getRemainingAir() + (int) cfg("coral.refill", 60)));
            }
            if (anyone) w.spawnParticle(Particle.BUBBLE_COLUMN_UP, l, 8, 0.25, 0.6, 0.25, 0.05);
        }
    }

    // =========================================================================================== domes
    static final Set<Material> CLEAR = Set.of(Material.WATER, Material.BUBBLE_COLUMN, Material.SEAGRASS, Material.TALL_SEAGRASS, Material.KELP, Material.KELP_PLANT, Material.SEA_PICKLE);

    String inflate(Player p, Block at, int r) {
        World w = at.getWorld();
        int x = at.getX(), y = at.getY(), z = at.getZ();
        if (!wet(at)) return "Put it down under water, on the sea floor.";
        if (y + r + 2 >= DeepGenerator.SEA) return "Too close to the surface: the dome has to be under water.";
        if (Math.hypot(x - 681, z + 951) < 160 || MermaidDen.weight(x, z) > 0 || DrownedCity.inside(x, z) || Math.hypot(x - GlassReef.CX, z - GlassReef.CZ) < GlassReef.PLAZA_R + 10
                || Math.hypot(x - Trench.LX, z - Trench.LZ) < Trench.LAIR_R + 20)
            return "You can't put a dome here.";
        long mine = domes.values().stream().filter(d -> d.owner.equals(p.getUniqueId())).count();
        if (mine >= cfg("domes.max-per-player", 3) && !p.hasPermission("faultlinedeep.market")) return "You already have " + mine + " domes (sneak + right-click one's glass to pack it away).";
        for (Dome d : domes.values()) if (Math.hypot(d.x - x, d.z - z) < d.r + r + 2 && Math.abs(d.y - y) < d.r + r + 2) return "Too close to another dome.";
        for (int dx = -r - 1; dx <= r + 1; dx += 4) for (int dz = -r - 1; dz <= r + 1; dz += 4)
            if (!w.isChunkLoaded((x + dx) >> 4, (z + dz) >> 4)) return "Not all of the ground there is loaded.";
        Dome d = new Dome();
        d.id = nextId++; d.x = x; d.y = y; d.z = z; d.r = r; d.owner = p.getUniqueId();
        List<long[]> glass = new ArrayList<>(), air = new ArrayList<>(), floor = new ArrayList<>();
        double in = r - 0.5, out = r + 0.5;
        for (int dx = -r - 1; dx <= r + 1; dx++) for (int dz = -r - 1; dz <= r + 1; dz++) {
            double h = Math.hypot(dx, dz);
            if (h < out) floor.add(new long[]{x + dx, y - 1, z + dz});
            for (int dy = 0; dy <= r + 1; dy++) {
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist < in) air.add(new long[]{x + dx, y + dy, z + dz});
                else if (dist < out) glass.add(new long[]{x + dx, y + dy, z + dz, dy});
            }
        }
        // the floor: open water / plants under the dome become glass, so it's sealed from below
        for (long[] f : floor) {
            Block b = w.getBlockAt((int) f[0], (int) f[1], (int) f[2]);
            if (wet(b) || b.getType().isAir()) { change(d, b, Material.LIGHT_BLUE_STAINED_GLASS.createBlockData()); shell.put(pack(b.getX(), b.getY(), b.getZ()), d.id); }
        }
        for (long[] g : glass) {
            Block b = w.getBlockAt((int) g[0], (int) g[1], (int) g[2]);
            if (b.getType().isSolid() && !wet(b) && g[3] == 0) continue; // the ground keeps its own edge
            BlockData bd = g[3] == 0 ? Material.SEA_LANTERN.createBlockData() : (g[3] + g[0] + g[2]) % 7 == 0 ? Material.CYAN_STAINED_GLASS.createBlockData()
                    : Material.LIGHT_BLUE_STAINED_GLASS.createBlockData();
            change(d, b, bd);
            shell.put(pack(b.getX(), b.getY(), b.getZ()), d.id);
        }
        for (long[] a : air) {
            Block b = w.getBlockAt((int) a[0], (int) a[1], (int) a[2]);
            if (CLEAR.contains(b.getType())) change(d, b, Material.AIR.createBlockData());
            else if (b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged()) {
                BlockData copy = b.getBlockData().clone();
                ((org.bukkit.block.data.Waterlogged) copy).setWaterlogged(false);
                change(d, b, copy);
            }
        }
        domes.put(d.id, d);
        save();
        Location c = new Location(w, x + 0.5, y + 1, z + 0.5);
        w.playSound(c, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.BLOCKS, 1.5f, 0.8f);
        w.playSound(c, Sound.ITEM_BUCKET_EMPTY, SoundCategory.BLOCKS, 1.5f, 0.6f);
        w.spawnParticle(Particle.BUBBLE_POP, c, 200, r * 0.5, r * 0.4, r * 0.5, 0.2);
        p.sendTitle("", ChatColor.AQUA + "The dome fills with air", 5, 40, 10);
        pl.console("index discover " + p.getName() + " dome_kit");
        pl.console("index stat " + p.getName() + " domes 1");
        return null;
    }

    void change(Dome d, Block b, BlockData to) {
        d.before.putIfAbsent(pack(b.getX(), b.getY(), b.getZ()), b.getBlockData().getAsString());
        b.setBlockData(to, false);
    }

    void dismantle(Dome d, Player by) {
        World w = pl.world;
        if (w == null) return;
        for (Map.Entry<Long, String> en : d.before.entrySet()) {
            int[] p = Volcano.unpack(en.getKey());
            Block b = w.getBlockAt(p[0], p[1], p[2]);
            Material m = b.getType();
            boolean ours = m.isAir() || m == Material.LIGHT_BLUE_STAINED_GLASS || m == Material.CYAN_STAINED_GLASS || m == Material.SEA_LANTERN;
            if (!ours) continue; // something a player put there stays (the sea flows round it)
            try { b.setBlockData(Bukkit.createBlockData(en.getValue()), false); } catch (IllegalArgumentException ex) { b.setType(Material.WATER, false); }
        }
        shell.values().removeIf(v -> v == d.id);
        domes.remove(d.id);
        save();
        if (by != null) {
            by.getInventory().addItem(kit(d.r)).values().forEach(x -> by.getWorld().dropItemNaturally(by.getLocation(), x));
            by.playSound(by.getLocation(), Sound.ITEM_BUCKET_FILL, 1f, 0.8f);
            by.sendActionBar(ChatColor.AQUA + "You pack the dome away. The sea rushes back in.");
        }
    }

    /** Market.dry: inside someone's dome. */
    static boolean inDome(double x, double y, double z) {
        Habitat h = instance;
        if (h == null) return false;
        for (Dome d : h.domes.values()) if (d.inside(x, y, z)) return true;
        return false;
    }

    // =========================================================================================== protection
    boolean shellAt(Block b) { return shell.containsKey(pack(b.getX(), b.getY(), b.getZ())); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (shellAt(b)) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(ChatColor.AQUA + "The dome's glass holds." + ChatColor.GRAY + " (Owner: sneak + right-click with an empty hand to pack it away.)");
            return;
        }
        if (b.getType() == Material.BUBBLE_CORAL_BLOCK || b.getType() == Material.DEAD_BUBBLE_CORAL_BLOCK) {
            if (removeCoral(b)) {
                e.setDropItems(false);
                if (e.getPlayer().getGameMode() != GameMode.CREATIVE) b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), coral(1));
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlast(EntityExplodeEvent e) { e.blockList().removeIf(this::shellAt); }

    @EventHandler(ignoreCancelled = true)
    public void onBlast(BlockExplodeEvent e) { e.blockList().removeIf(this::shellAt); }

    @EventHandler(ignoreCancelled = true)
    public void onPush(BlockPistonExtendEvent e) { if (e.getBlocks().stream().anyMatch(this::shellAt)) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onPull(BlockPistonRetractEvent e) { if (e.getBlocks().stream().anyMatch(this::shellAt)) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) { // no water flowing in through the foot of the dome
        if (pl.world == null || !e.getBlock().getWorld().equals(pl.world) || domes.isEmpty()) return;
        Block to = e.getToBlock();
        if (inDome(to.getX() + 0.5, to.getY() + 0.5, to.getZ() + 0.5)) e.setCancelled(true);
    }

    // =========================================================================================== domes.yml
    void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next", nextId);
        for (Dome d : domes.values()) {
            String p = "domes." + d.id + ".";
            y.set(p + "owner", d.owner.toString());
            y.set(p + "at", d.x + "," + d.y + "," + d.z);
            y.set(p + "r", d.r);
            List<String> b = new ArrayList<>();
            for (Map.Entry<Long, String> en : d.before.entrySet()) b.add(en.getKey() + "|" + en.getValue());
            y.set(p + "before", b);
        }
        try { y.save(file); } catch (IOException e) { pl.getLogger().warning("Couldn't save domes.yml: " + e.getMessage()); }
    }

    void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextId = y.getInt("next", 1);
        ConfigurationSection s = y.getConfigurationSection("domes");
        if (s == null) return;
        for (String k : s.getKeys(false)) try {
            Dome d = new Dome();
            d.id = Integer.parseInt(k);
            d.owner = UUID.fromString(s.getString(k + ".owner"));
            String[] at = s.getString(k + ".at", "0,0,0").split(",");
            d.x = Integer.parseInt(at[0]); d.y = Integer.parseInt(at[1]); d.z = Integer.parseInt(at[2]);
            d.r = s.getInt(k + ".r");
            for (String b : s.getStringList(k + ".before")) {
                int bar = b.indexOf('|');
                if (bar > 0) d.before.put(Long.parseLong(b.substring(0, bar)), b.substring(bar + 1));
            }
            domes.put(d.id, d);
            // the shell = every changed block that's on the dome's skin
            for (long key : d.before.keySet()) {
                int[] p = Volcano.unpack(key);
                double dist = Math.sqrt(Math.pow(p[0] - d.x, 2) + Math.pow(p[1] - d.y, 2) + Math.pow(p[2] - d.z, 2));
                if (p[1] == d.y - 1 || p[1] >= d.y && dist >= d.r - 0.5) shell.put(key, d.id); // (and the glass floor)
            }
            nextId = Math.max(nextId, d.id + 1);
        } catch (RuntimeException ex) { pl.getLogger().warning("Skipped a broken dome in domes.yml (" + k + ")"); }
    }

    /** /deep domes: every dome. */
    List<String> list() {
        List<String> out = new ArrayList<>();
        for (Dome d : domes.values()) out.add("#" + d.id + " " + Bukkit.getOfflinePlayer(d.owner).getName() + " at " + d.x + " " + d.y + " " + d.z + " r" + d.r);
        if (out.isEmpty()) out.add("No domes.");
        return out;
    }
}
