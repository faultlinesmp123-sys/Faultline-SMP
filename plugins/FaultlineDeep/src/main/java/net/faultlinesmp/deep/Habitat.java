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
 *  10 (a Small kit in the middle of 4 tinted glass + 4 sea lanterns)): right-click under water, in ANY world (the Dark Ocean,
 *  an Overworld ocean, a lake...) = a glass dome inflates there (hemisphere of glass with prismarine ribs, a sea-lantern ring
 *  at its foot and a lantern crown, a glass floor where the floor is open, the water inside turned to air: a dry base) with
 *  TWO DOORS (dark oak, framed in dark prismarine): one on the side you placed it from, one opposite. Water never flows into
 *  a door. Each changed block's old state is kept in domes.yml. Other players can't break, blow up or push its shell; the
 *  OWNER can break their own walls (nothing drops): the sea still stays out, because no water may flow from outside into a
 *  dome's (or hallway's) space (`onFlow`), so a broken wall is an open window. Inside you build freely (chests, crafting
 *  tables, beds...). Owner (or `faultlinedeep.market`) sneak + right-clicks the shell with an empty hand = DISMANTLE: its
 *  hallways go first, then the sea comes back (only where the dome's own glass/air still is) and the kit is returned.
 *  COMBINING: inflate one of your domes within `habitat.domes.combine-gap` (12) blocks (glass to glass) of another of
 *  yours (floors within 6 of each other) = "Combine these domes?" (a pop-up dialog on Java, a title, chat [YES]/[NO], a
 *  Bedrock form; `/dome yes|no`, `/dome combine` asks again from inside a dome): yes = a glass HALLWAY (3 wide, 3 tall
 *  inside, a lantern every few blocks in the roof) is cut between them, through both walls. Hallways are saved with their
 *  old blocks too. Domes and hallways in the Dark Ocean count as dry (Market.dry): no pressure, no Abyss dark, your drops
 *  stay. `habitat.domes.max-per-player` 3; in the Dark Ocean never in the Bubble, the Den, the Drowned City, the Glass
 *  Reef's spire or the Maw; never overlapping another dome.
 */
final class Habitat implements Listener {
    static Habitat instance;
    final FaultlineDeep pl;
    final NamespacedKey coralItem, coralKey, kitKey;
    final Set<Long> corals = new HashSet<>();                // loaded air corals (packed positions)
    final Map<Integer, Dome> domes = new LinkedHashMap<>();
    final Map<Integer, Hall> halls = new LinkedHashMap<>();
    final Map<String, Map<Long, Piece>> shell = new HashMap<>(); // world -> shell block -> its dome / hallway
    final Map<UUID, int[]> asking = new HashMap<>();           // player -> {new dome, other dome, until (server tick)}
    final File file;
    int nextId = 1;
    int secs;

    /** A dome or a hallway: what it changed (to put back) and who owns it. */
    abstract static class Piece {
        int id;
        String world;
        UUID owner;
        final Map<Long, String> before = new LinkedHashMap<>();
        /** Its space, shell and floor included: no water may flow into it from outside. */
        abstract boolean area(int bx, int by, int bz);
    }

    static final class Dome extends Piece {
        int x, y, z, r;
        boolean inside(double px, double py, double pz) { return py >= y - 0.5 && (px - x - 0.5) * (px - x - 0.5) + (py - y) * (py - y) + (pz - z - 0.5) * (pz - z - 0.5) < (r - 0.6) * (r - 0.6); }
        /** The air it was given: block centres closer than r - 0.5, from its floor up. */
        boolean hollow(int bx, int by, int bz) { int dx = bx - x, dy = by - y, dz = bz - z; return dy >= 0 && dx * dx + dy * dy + dz * dz < (r - 0.5) * (r - 0.5); }
        @Override boolean area(int bx, int by, int bz) {
            int dx = bx - x, dy = by - y, dz = bz - z;
            if (dy < -1 || dy > r + 1 || Math.abs(dx) > r + 1 || Math.abs(dz) > r + 1) return false;
            return dy < 0 ? dx * dx + dz * dz < (r + 0.5) * (r + 0.5) : dx * dx + dy * dy + dz * dz < (r + 0.5) * (r + 0.5);
        }
        boolean shellCell(int bx, int by, int bz) { return area(bx, by, bz) && !hollow(bx, by, bz); }
    }

    static final class Hall extends Piece {
        int a, b;
        final Set<Long> air = new HashSet<>(), skin = new HashSet<>();
        @Override boolean area(int bx, int by, int bz) { long k = pack(bx, by, bz); return air.contains(k) || skin.contains(k); }
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
        m.setLore(List.of(ChatColor.GRAY + "Right-click under water (any sea or lake):", ChatColor.GRAY + "a glass dome with two doors inflates",
                ChatColor.GRAY + "there and pushes the water out.",
                ChatColor.AQUA + "Radius " + r + ChatColor.GRAY + " (" + (2 * r + 1) + " blocks across)",
                ChatColor.GRAY + "Put two of yours close together to join", ChatColor.GRAY + "them with a glass hallway.",
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
        // dismantling a dome (a hallway's glass packs away the dome you're nearest)
        Piece piece = shellPiece(clicked);
        if (piece != null && p.isSneaking() && hand.getType().isAir()) {
            e.setCancelled(true);
            Dome d = piece instanceof Dome dd ? dd : nearestEnd((Hall) piece, clicked);
            if (d == null) return;
            if (!d.owner.equals(p.getUniqueId()) && !p.hasPermission("faultlinedeep.market")) { p.sendActionBar(ChatColor.RED + "It isn't your dome."); return; }
            dismantle(d, p);
            return;
        }
        boolean coral = isCoral(hand);
        int r = kitRadius(hand);
        if (!coral && r == 0) return;
        e.setCancelled(true);
        Block at = clicked.getRelative(e.getBlockFace());
        if (r > 0) {
            String why = inflate(p, at, r);
            if (why != null) { p.sendMessage(ChatColor.RED + why); return; }
            if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
            return;
        }
        if (pl.world == null || !clicked.getWorld().equals(pl.world)) { p.sendActionBar(ChatColor.RED + "Air Coral only grows in the Dark Ocean."); return; }
        {
            if (!wet(at) || at.getType() != Material.WATER && at.getType() != Material.SEAGRASS) { p.sendActionBar(ChatColor.RED + "Plant it under water."); return; }
            if (Market.dry(at.getX() + 0.5, at.getY(), at.getZ() + 0.5)) { p.sendActionBar(ChatColor.RED + "There's air here already."); return; }
            at.setType(Material.BUBBLE_CORAL_BLOCK, false);
            addCoral(at);
            if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
            at.getWorld().playSound(at.getLocation(), Sound.BLOCK_CORAL_BLOCK_PLACE, 1f, 1.2f);
            at.getWorld().spawnParticle(Particle.BUBBLE_COLUMN_UP, at.getLocation().add(0.5, 1, 0.5), 20, 0.3, 0.5, 0.3, 0.1);
            pl.console("index discover " + p.getName() + " air_coral");
        }
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
            Location l = new Location(w, c[0] + 0.5, c[1] + 1, c[2] + 0.5);
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
    /** What domes and hallways are made of: dismantling only puts the sea back where one of these (or air) still is. */
    static final Set<Material> OURS = Set.of(Material.LIGHT_BLUE_STAINED_GLASS, Material.CYAN_STAINED_GLASS, Material.SEA_LANTERN, Material.PRISMARINE_BRICKS,
            Material.DARK_PRISMARINE, Material.DARK_OAK_DOOR);

    /** Water, air or a sea plant: room for glass. */
    static boolean open(Block b) { return b.getType().isAir() || CLEAR.contains(b.getType()) || Submersible.water(b); }

    Map<Long, Piece> shellOf(World w) { return shell.computeIfAbsent(w.getName(), k -> new HashMap<>()); }

    Piece shellPiece(Block b) {
        Map<Long, Piece> m = shell.get(b.getWorld().getName());
        return m == null ? null : m.get(pack(b.getX(), b.getY(), b.getZ()));
    }

    /** The world's shell map again from scratch: every dome's changed skin blocks and every hallway's glass, minus what a hallway opened. */
    void rebuildShell(String world) {
        Map<Long, Piece> m = new HashMap<>();
        for (Dome d : domes.values()) if (d.world.equals(world))
            for (long key : d.before.keySet()) { int[] p = Volcano.unpack(key); if (d.shellCell(p[0], p[1], p[2])) m.put(key, d); }
        for (Hall h : halls.values()) if (h.world.equals(world)) {
            for (long k : h.air) m.remove(k);
            for (long k : h.skin) m.put(k, h);
        }
        shell.put(world, m);
    }

    /** A cardinal direction from a yaw: {dx, dz}. */
    static int[] cardinal(float yaw) {
        int q = Math.floorMod(Math.round(yaw / 90f), 4); // 0 = south (+z), 1 = west (-x), 2 = north (-z), 3 = east (+x)
        return switch (q) { case 0 -> new int[]{0, 1}; case 1 -> new int[]{-1, 0}; case 2 -> new int[]{0, -1}; default -> new int[]{1, 0}; };
    }

    static org.bukkit.block.BlockFace face(int dx, int dz) {
        return dx > 0 ? org.bukkit.block.BlockFace.EAST : dx < 0 ? org.bukkit.block.BlockFace.WEST : dz > 0 ? org.bukkit.block.BlockFace.SOUTH : org.bukkit.block.BlockFace.NORTH;
    }

    String inflate(Player p, Block at, int r) {
        World w = at.getWorld();
        int x = at.getX(), y = at.getY(), z = at.getZ();
        if (!wet(at)) return "Put it down under water (any sea or lake).";
        if (y - 2 < w.getMinHeight() || y + r + 2 >= w.getMaxHeight()) return "There's no room for a dome here.";
        if (w.equals(pl.world) && (Math.hypot(x - 681, z + 951) < 160 || MermaidDen.weight(x, z) > 0 || DrownedCity.inside(x, z)
                || Math.hypot(x - GlassReef.CX, z - GlassReef.CZ) < GlassReef.PLAZA_R + 10 || Math.hypot(x - Trench.LX, z - Trench.LZ) < Trench.LAIR_R + 20))
            return "You can't put a dome here.";
        long mine = domes.values().stream().filter(d -> d.owner.equals(p.getUniqueId())).count();
        if (mine >= cfg("domes.max-per-player", 3) && !p.hasPermission("faultlinedeep.market")) return "You already have " + mine + " domes (sneak + right-click one's glass to pack it away).";
        for (Dome d : domes.values()) if (d.world.equals(w.getName()) && Math.hypot(d.x - x, d.z - z) < d.r + r + 2 && Math.abs(d.y - y) < d.r + r + 2) return "Too close to another dome.";
        for (Hall h : halls.values()) if (h.world.equals(w.getName())) for (long k : h.skin) {
            int[] c = Volcano.unpack(k);
            if (Math.abs(c[1] - y) <= r + 1 && Math.hypot(c[0] - x, c[2] - z) < r + 1.5) return "That's on top of a hallway.";
        }
        for (int dx = -r - 1; dx <= r + 1; dx += 4) for (int dz = -r - 1; dz <= r + 1; dz += 4)
            if (!w.isChunkLoaded((x + dx) >> 4, (z + dz) >> 4)) return "Not all of the ground there is loaded.";
        if (!w.isChunkLoaded((x + r + 1) >> 4, (z + r + 1) >> 4) || !w.isChunkLoaded((x - r - 1) >> 4, (z - r - 1) >> 4)) return "Not all of the ground there is loaded.";
        Dome d = new Dome();
        d.x = x; d.y = y; d.z = z; d.r = r; d.owner = p.getUniqueId(); d.world = w.getName();
        // BUG FIX (1.9.1): the shell overwrote whatever stood there (someone's chest = its contents gone). A container, furnace,
        // sign... anywhere in the dome refuses it; other solid blocks are simply kept as part of the wall.
        for (int dx = -r - 1; dx <= r + 1; dx++) for (int dz = -r - 1; dz <= r + 1; dz++) for (int dy = -1; dy <= r + 1; dy++)
            if (d.area(x + dx, y + dy, z + dz) && w.getBlockAt(x + dx, y + dy, z + dz).getState() instanceof org.bukkit.block.TileState)
                return "Something's in the way (a chest or another block with things in it).";
        d.id = nextId++;
        // the doors: one on the side you placed it from, one opposite
        int[] f = cardinal(p.getLocation().getYaw());
        int fx = -f[0], fz = -f[1];
        Set<Long> doors = new HashSet<>(), frames = new HashSet<>();
        for (int s = -1; s <= 1; s += 2) {
            int dx = fx * s * r, dz = fz * s * r;
            for (int dy = 0; dy <= 1; dy++) doors.add(pack(x + dx, y + dy, z + dz));
            frames.add(pack(x + dx, y + 2, z + dz));
            for (int side = -1; side <= 1; side += 2) for (int dy = 0; dy <= 1; dy++) frames.add(pack(x + dx + fz * side, y + dy, z + dz + fx * side));
        }
        for (int dx = -r - 1; dx <= r + 1; dx++) for (int dz = -r - 1; dz <= r + 1; dz++) for (int dy = -1; dy <= r + 1; dy++) {
            int bx = x + dx, by = y + dy, bz = z + dz;
            if (!d.area(bx, by, bz)) continue;
            Block b = w.getBlockAt(bx, by, bz);
            long key = pack(bx, by, bz);
            if (d.hollow(bx, by, bz)) { // the inside: the sea goes, anything else stays (dry)
                if (CLEAR.contains(b.getType())) change(d, b, Material.AIR.createBlockData());
                else if (b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged()) {
                    BlockData copy = b.getBlockData().clone();
                    ((org.bukkit.block.data.Waterlogged) copy).setWaterlogged(false);
                    change(d, b, copy);
                }
                continue;
            }
            if (doors.contains(key)) continue; // (after the shell, below)
            if (!open(b)) continue; // the ground (or anything built there) is the wall
            Material m;
            if (dy < 0) m = Material.LIGHT_BLUE_STAINED_GLASS;                              // the floor where it's open
            else if (frames.contains(key)) m = Material.DARK_PRISMARINE;
            else if (dy == 0) m = Material.SEA_LANTERN;                                      // the ring at its foot
            else if (dx == 0 && dz == 0) m = Material.SEA_LANTERN;                           // the crown
            else if (dx == 0 || dz == 0) m = Material.PRISMARINE_BRICKS;                      // four ribs
            else m = (dy + bx + bz) % 7 == 0 ? Material.CYAN_STAINED_GLASS : Material.LIGHT_BLUE_STAINED_GLASS;
            change(d, b, m.createBlockData());
        }
        for (int s = -1; s <= 1; s += 2) {
            int bx = x + fx * s * r, bz = z + fz * s * r;
            org.bukkit.block.BlockFace out = face(fx * s, fz * s);
            for (int dy = 0; dy <= 1; dy++) {
                Block b = w.getBlockAt(bx, y + dy, bz);
                org.bukkit.block.data.type.Door door = (org.bukkit.block.data.type.Door) Material.DARK_OAK_DOOR.createBlockData();
                door.setFacing(out);
                door.setHalf(dy == 0 ? org.bukkit.block.data.Bisected.Half.BOTTOM : org.bukkit.block.data.Bisected.Half.TOP);
                change(d, b, door);
            }
        }
        domes.put(d.id, d);
        rebuildShell(d.world);
        save();
        Location c = new Location(w, x + 0.5, y + 1, z + 0.5);
        w.playSound(c, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.BLOCKS, 1.5f, 0.8f);
        w.playSound(c, Sound.ITEM_BUCKET_EMPTY, SoundCategory.BLOCKS, 1.5f, 0.6f);
        w.spawnParticle(Particle.BUBBLE_POP, c, 200, r * 0.5, r * 0.4, r * 0.5, 0.2);
        p.sendTitle("", ChatColor.AQUA + "The dome fills with air", 5, 40, 10);
        pl.console("index discover " + p.getName() + " dome_kit");
        pl.console("index stat " + p.getName() + " domes 1");
        Bukkit.getScheduler().runTaskLater(pl, () -> { if (p.isOnline() && domes.get(d.id) == d) offerCombine(p, d); }, 30L);
        return null;
    }

    void change(Piece d, Block b, BlockData to) {
        d.before.putIfAbsent(pack(b.getX(), b.getY(), b.getZ()), b.getBlockData().getAsString());
        b.setBlockData(to, false);
    }

    /** Puts back what a dome or hallway changed, where its own blocks (or air) still are; a player's blocks stay. */
    void restore(Piece d) {
        World w = Bukkit.getWorld(d.world);
        if (w == null) return;
        List<Map.Entry<Long, String>> list = new ArrayList<>(d.before.entrySet());
        java.util.Collections.reverse(list);
        for (Map.Entry<Long, String> en : list) {
            int[] p = Volcano.unpack(en.getKey());
            Block b = w.getBlockAt(p[0], p[1], p[2]);
            if (!b.getType().isAir() && !OURS.contains(b.getType())) continue; // something a player put there stays (the sea flows round it)
            try { b.setBlockData(Bukkit.createBlockData(en.getValue()), false); } catch (IllegalArgumentException ex) { b.setType(Material.WATER, false); }
        }
    }

    void dismantle(Dome d, Player by) {
        for (Hall h : new ArrayList<>(halls.values())) if (h.a == d.id || h.b == d.id) { restore(h); halls.remove(h.id); }
        restore(d);
        domes.remove(d.id);
        rebuildShell(d.world);
        save();
        if (by != null) {
            by.getInventory().addItem(kit(d.r)).values().forEach(x -> by.getWorld().dropItemNaturally(by.getLocation(), x));
            by.playSound(by.getLocation(), Sound.ITEM_BUCKET_FILL, 1f, 0.8f);
            by.sendActionBar(ChatColor.AQUA + "You pack the dome away. The sea rushes back in.");
        }
    }

    Dome nearestEnd(Hall h, Block b) {
        Dome a = domes.get(h.a), c = domes.get(h.b);
        if (a == null) return c;
        if (c == null) return a;
        return Math.hypot(a.x - b.getX(), a.z - b.getZ()) <= Math.hypot(c.x - b.getX(), c.z - b.getZ()) ? a : c;
    }

    /** Market.dry: inside someone's dome or hallway in the Dark Ocean. */
    static boolean inDome(double x, double y, double z) {
        Habitat h = instance;
        if (h == null || h.pl.world == null) return false;
        String w = h.pl.world.getName();
        for (Dome d : h.domes.values()) if (d.world.equals(w) && d.inside(x, y, z)) return true;
        if (!h.halls.isEmpty()) {
            long k = pack((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            for (Hall hl : h.halls.values()) if (hl.world.equals(w) && hl.air.contains(k)) return true;
        }
        return false;
    }

    /** Inside any dome's or hallway's space (shell included), in any world. */
    boolean area(World w, int x, int y, int z) {
        String n = w.getName();
        for (Dome d : domes.values()) if (d.world.equals(n) && d.area(x, y, z)) return true;
        for (Hall h : halls.values()) if (h.world.equals(n) && h.area(x, y, z)) return true;
        return false;
    }

    // =========================================================================================== combining
    /** Your other dome nearest this one that a hallway could reach, or null. */
    Dome partner(Dome d) {
        Dome best = null;
        double bestGap = Double.MAX_VALUE;
        for (Dome o : domes.values()) {
            if (o == d || !o.world.equals(d.world) || !o.owner.equals(d.owner) || linked(d, o)) continue;
            double gap = Math.hypot(o.x - d.x, o.z - d.z) - o.r - d.r;
            if (gap < 0 || gap > cfg("domes.combine-gap", 12) || Math.abs(o.y - d.y) > 6) continue;
            if (gap < bestGap) { bestGap = gap; best = o; }
        }
        return best;
    }

    boolean linked(Dome a, Dome b) {
        for (Hall h : halls.values()) if (h.a == a.id && h.b == b.id || h.a == b.id && h.b == a.id) return true;
        return false;
    }

    void offerCombine(Player p, Dome d) {
        Dome o = partner(d);
        if (o == null) return;
        ask(p, d, o);
    }

    /** "Are you sure you want to combine this dome?": a pop-up dialog (Java 1.21.6+), a title, chat buttons, a Bedrock form. */
    void ask(Player p, Dome d, Dome o) {
        asking.put(p.getUniqueId(), new int[]{d.id, o.id, Bukkit.getCurrentTick() + 20 * 120});
        int gap = (int) Math.round(Math.hypot(o.x - d.x, o.z - d.z) - o.r - d.r);
        String q = "Are you sure you want to combine this dome?";
        String info = "Your other dome is " + gap + " block" + (gap == 1 ? "" : "s") + " away. Yes = a glass hallway is built between them.";
        p.sendTitle(ChatColor.AQUA + "" + ChatColor.BOLD + "Combine these domes?", ChatColor.GRAY + "Yes = a hallway joins them", 10, 100, 20);
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f);
        if (Edition.bedrock(p) && BedrockForms.ask(pl, p, "Combine these domes?", q + "\n\n" + info, "Yes", "No", yes -> answer(p, yes))) return;
        if (!Edition.bedrock(p)) dialog(p, q, info);
        net.kyori.adventure.text.Component yes = net.kyori.adventure.text.Component.text("[YES]", net.kyori.adventure.text.format.NamedTextColor.GREEN, net.kyori.adventure.text.format.TextDecoration.BOLD)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/dome yes"));
        net.kyori.adventure.text.Component no = net.kyori.adventure.text.Component.text("[NO]", net.kyori.adventure.text.format.NamedTextColor.RED, net.kyori.adventure.text.format.TextDecoration.BOLD)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/dome no"));
        p.sendMessage(ChatColor.AQUA + q + " " + ChatColor.GRAY + info);
        p.sendMessage(yes.append(net.kyori.adventure.text.Component.text("   ")).append(no));
    }

    /** Paper's dialog: a real pop-up in the middle of the screen. Older clients / servers without it just get the chat buttons. */
    void dialog(Player p, String q, String info) {
        try {
            UUID who = p.getUniqueId();
            net.kyori.adventure.text.event.ClickCallback.Options once = net.kyori.adventure.text.event.ClickCallback.Options.builder().uses(1).lifetime(java.time.Duration.ofMinutes(2)).build();
            io.papermc.paper.registry.data.dialog.ActionButton yes = io.papermc.paper.registry.data.dialog.ActionButton.create(
                    net.kyori.adventure.text.Component.text("Yes", net.kyori.adventure.text.format.NamedTextColor.GREEN),
                    net.kyori.adventure.text.Component.text("Build a glass hallway between them"), 120,
                    io.papermc.paper.registry.data.dialog.action.DialogAction.customClick((view, aud) -> Bukkit.getScheduler().runTask(pl, () -> { Player pp = Bukkit.getPlayer(who); if (pp != null) answer(pp, true); }), once));
            io.papermc.paper.registry.data.dialog.ActionButton no = io.papermc.paper.registry.data.dialog.ActionButton.create(
                    net.kyori.adventure.text.Component.text("No", net.kyori.adventure.text.format.NamedTextColor.RED),
                    net.kyori.adventure.text.Component.text("Keep them apart"), 120,
                    io.papermc.paper.registry.data.dialog.action.DialogAction.customClick((view, aud) -> Bukkit.getScheduler().runTask(pl, () -> { Player pp = Bukkit.getPlayer(who); if (pp != null) answer(pp, false); }), once));
            io.papermc.paper.dialog.Dialog dlg = io.papermc.paper.dialog.Dialog.create(b -> b.empty()
                    .base(io.papermc.paper.registry.data.dialog.DialogBase.builder(net.kyori.adventure.text.Component.text("Combine these domes?", net.kyori.adventure.text.format.NamedTextColor.AQUA))
                            .canCloseWithEscape(true)
                            .body(List.of(io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(net.kyori.adventure.text.Component.text(q)),
                                    io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(net.kyori.adventure.text.Component.text(info, net.kyori.adventure.text.format.NamedTextColor.GRAY))))
                            .build())
                    .type(io.papermc.paper.registry.data.dialog.type.DialogType.confirmation(yes, no)));
            p.showDialog(dlg);
        } catch (Throwable t) { // (no dialogs on this server: the chat buttons still work)
        }
    }

    void answer(Player p, boolean yes) {
        int[] a = asking.remove(p.getUniqueId());
        if (a == null || Bukkit.getCurrentTick() > a[2]) { p.sendMessage(ChatColor.GRAY + "Nothing to answer (or the question ran out: /dome combine asks again from inside a dome)."); return; }
        Dome d = domes.get(a[0]), o = domes.get(a[1]);
        if (!yes) { p.sendActionBar(ChatColor.GRAY + "The domes stay apart."); return; }
        if (d == null || o == null) { p.sendMessage(ChatColor.RED + "One of those domes is gone."); return; }
        if (!d.owner.equals(p.getUniqueId()) && !p.hasPermission("faultlinedeep.market")) return;
        String why = combine(d, o);
        if (why != null) { p.sendMessage(ChatColor.RED + why); return; }
        World w = Bukkit.getWorld(d.world);
        if (w != null) {
            Location mid = new Location(w, (d.x + o.x) / 2.0 + 0.5, (d.y + o.y) / 2.0 + 1, (d.z + o.z) / 2.0 + 0.5);
            w.playSound(mid, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.BLOCKS, 1.5f, 1.0f);
            w.spawnParticle(Particle.BUBBLE_POP, mid, 120, 2, 1, 2, 0.2);
        }
        p.sendTitle("", ChatColor.AQUA + "A hallway joins your domes", 5, 40, 10);
    }

    /** The hallway: 3 wide and 3 tall inside, from one dome's middle to the other's, through both walls; glass round it. */
    String combine(Dome a, Dome b) {
        World w = Bukkit.getWorld(a.world);
        if (w == null || !a.world.equals(b.world)) return "Those domes aren't in the same world.";
        if (linked(a, b)) return "Those domes are already joined.";
        double ax = a.x + 0.5, az = a.z + 0.5, bx = b.x + 0.5, bz = b.z + 0.5;
        double lx = bx - ax, lz = bz - az, len2 = lx * lx + lz * lz;
        if (len2 < 1) return "Those domes are on top of each other.";
        int x0 = Math.min(a.x, b.x) - 3, x1 = Math.max(a.x, b.x) + 3, z0 = Math.min(a.z, b.z) - 3, z1 = Math.max(a.z, b.z) + 3;
        int y0 = Math.min(a.y, b.y) - 2, y1 = Math.max(a.y, b.y) + 4;
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) for (int cz = z0 >> 4; cz <= z1 >> 4; cz++)
            if (!w.isChunkLoaded(cx, cz)) return "Not all of the ground between them is loaded.";
        Set<Long> air = new HashSet<>();
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            double px = x + 0.5 - ax, pz = z + 0.5 - az;
            double t = Math.max(0, Math.min(1, (px * lx + pz * lz) / len2));
            double ds = Math.hypot(px - t * lx, pz - t * lz);
            if (ds > 1.5) continue;
            int floor = (int) Math.round(a.y + (b.y - a.y) * t);
            for (int y = floor; y <= floor + 2; y++) {
                if (y < y0 || y > y1 || a.hollow(x, y, z) || b.hollow(x, y, z)) continue; // (the domes are already air)
                air.add(pack(x, y, z));
            }
        }
        for (Dome o : domes.values()) if (o != a && o != b && o.world.equals(a.world)) for (long k : air) {
            int[] c = Volcano.unpack(k);
            if (o.area(c[0], c[1], c[2])) return "Another dome is in the way.";
        }
        for (long k : air) {
            int[] c = Volcano.unpack(k);
            Block blk = w.getBlockAt(c[0], c[1], c[2]);
            if (blk.getState() instanceof org.bukkit.block.TileState) return "Something's in the way (a chest or another block with things in it).";
            if (blk.getType() == Material.BEDROCK || blk.getType().getHardness() < 0) return "Something unbreakable is in the way.";
            for (Hall h : halls.values()) if (h.world.equals(a.world) && (h.air.contains(k) || h.skin.contains(k))) return "Another hallway is in the way.";
        }
        // the glass: every face of the hallway's air that touches water / air / sea plants (not a dome's own air or wall)
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        Map<Long, Integer> skin = new LinkedHashMap<>(); // cell -> 0 wall, 1 floor, 2 roof
        for (long k : air) {
            int[] c = Volcano.unpack(k);
            for (int[] fc : faces) {
                int nx = c[0] + fc[0], ny = c[1] + fc[1], nz = c[2] + fc[2];
                long nk = pack(nx, ny, nz);
                if (air.contains(nk) || a.hollow(nx, ny, nz) || b.hollow(nx, ny, nz) || a.shellCell(nx, ny, nz) || b.shellCell(nx, ny, nz)) continue;
                if (!open(w.getBlockAt(nx, ny, nz))) continue; // the sea floor (or a block) closes it there
                skin.merge(nk, fc[1] < 0 ? 1 : fc[1] > 0 ? 2 : 0, Math::max);
            }
        }
        Hall h = new Hall();
        h.id = nextId++; h.a = a.id; h.b = b.id; h.world = a.world; h.owner = a.owner;
        h.air.addAll(air);
        for (long k : air) {
            int[] c = Volcano.unpack(k);
            Block blk = w.getBlockAt(c[0], c[1], c[2]);
            if (blk.getType().isAir()) continue;
            if (blk.getBlockData() instanceof org.bukkit.block.data.type.Door door) { // a door cut in half: take its other half too
                Block other = blk.getRelative(door.getHalf() == org.bukkit.block.data.Bisected.Half.BOTTOM ? org.bukkit.block.BlockFace.UP : org.bukkit.block.BlockFace.DOWN);
                if (other.getBlockData() instanceof org.bukkit.block.data.type.Door) { change(h, other, Material.AIR.createBlockData()); if (!air.contains(pack(other.getX(), other.getY(), other.getZ()))) skin.put(pack(other.getX(), other.getY(), other.getZ()), 0); }
            }
            change(h, blk, Material.AIR.createBlockData());
        }
        for (Map.Entry<Long, Integer> en : skin.entrySet()) {
            int[] c = Volcano.unpack(en.getKey());
            Block blk = w.getBlockAt(c[0], c[1], c[2]);
            Material m = en.getValue() == 1 ? Material.PRISMARINE_BRICKS
                    : en.getValue() == 2 && Math.floorMod(c[0] + c[2], 4) == 0 ? Material.SEA_LANTERN
                    : (c[0] + c[1] + c[2]) % 7 == 0 ? Material.CYAN_STAINED_GLASS : Material.LIGHT_BLUE_STAINED_GLASS;
            change(h, blk, m.createBlockData());
            h.skin.add(en.getKey());
        }
        halls.put(h.id, h);
        rebuildShell(h.world);
        save();
        return null;
    }

    // =========================================================================================== /dome
    boolean command(org.bukkit.command.CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) { for (String line : list()) sender.sendMessage(line); return true; }
        String sub = args.length > 0 ? args[0].toLowerCase(java.util.Locale.ROOT) : "list";
        switch (sub) {
            case "yes", "y" -> answer(p, true);
            case "no", "n" -> answer(p, false);
            case "combine", "join" -> {
                Dome here = null;
                for (Dome d : domes.values()) if (d.world.equals(p.getWorld().getName()) && d.area(p.getLocation().getBlockX(), p.getLocation().getBlockY(), p.getLocation().getBlockZ())) here = d;
                if (here == null) { p.sendMessage(ChatColor.RED + "Stand in one of your domes."); return true; }
                if (!here.owner.equals(p.getUniqueId()) && !p.hasPermission("faultlinedeep.market")) { p.sendMessage(ChatColor.RED + "It isn't your dome."); return true; }
                Dome o = partner(here);
                if (o == null) { p.sendMessage(ChatColor.GRAY + "None of your other domes is close enough (within " + (int) cfg("domes.combine-gap", 12) + " blocks, floors within 6) that isn't joined already."); return true; }
                ask(p, here, o);
            }
            default -> {
                int n = 0;
                for (Dome d : domes.values()) if (d.owner.equals(p.getUniqueId())) {
                    n++;
                    long joins = halls.values().stream().filter(h -> h.a == d.id || h.b == d.id).count();
                    p.sendMessage(ChatColor.AQUA + (d.r >= 10 ? "Large" : "Small") + " dome " + ChatColor.GRAY + "in " + d.world + " at " + d.x + " " + d.y + " " + d.z
                            + (joins > 0 ? ChatColor.DARK_AQUA + " (" + joins + " hallway" + (joins == 1 ? "" : "s") + ")" : ""));
                }
                if (n == 0) p.sendMessage(ChatColor.GRAY + "You have no domes. Craft a Dome Kit and right-click under water.");
                else p.sendMessage(ChatColor.DARK_GRAY + "/dome combine (inside a dome) joins it to your nearest other dome.");
            }
        }
        return true;
    }

    // =========================================================================================== protection
    boolean shellAt(Block b) { return shellPiece(b) != null; }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Piece piece = shellPiece(b);
        if (piece != null) {
            Player p = e.getPlayer();
            if (!piece.owner.equals(p.getUniqueId()) && !p.hasPermission("faultlinedeep.market")) {
                e.setCancelled(true);
                p.sendActionBar(ChatColor.AQUA + "The dome's glass holds." + ChatColor.GRAY + " (Only its owner can break it.)");
                return;
            }
            // the owner opens their own wall: nothing drops, and the sea still can't come in (onFlow)
            e.setDropItems(false);
            e.setExpToDrop(0);
            Map<Long, Piece> m = shell.get(b.getWorld().getName());
            if (m != null) {
                m.remove(pack(b.getX(), b.getY(), b.getZ()));
                if (b.getBlockData() instanceof org.bukkit.block.data.type.Door door) // (its other half goes with it)
                    m.remove(door.getHalf() == org.bukkit.block.data.Bisected.Half.BOTTOM ? pack(b.getX(), b.getY() + 1, b.getZ()) : pack(b.getX(), b.getY() - 1, b.getZ()));
            }
            p.sendActionBar(ChatColor.AQUA + "You open the wall." + ChatColor.GRAY + " The sea stays out.");
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
    public void onFlow(BlockFromToEvent e) { // no water from outside flows into a dome or hallway (a bucket poured inside still flows inside)
        if (domes.isEmpty()) return;
        Block to = e.getToBlock(), from = e.getBlock();
        World w = to.getWorld();
        if (area(w, to.getX(), to.getY(), to.getZ()) && !area(w, from.getX(), from.getY(), from.getZ())) e.setCancelled(true);
    }

    // =========================================================================================== domes.yml
    void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next", nextId);
        for (Dome d : domes.values()) {
            String p = "domes." + d.id + ".";
            y.set(p + "owner", d.owner.toString());
            y.set(p + "world", d.world);
            y.set(p + "at", d.x + "," + d.y + "," + d.z);
            y.set(p + "r", d.r);
            y.set(p + "before", befores(d));
        }
        for (Hall h : halls.values()) {
            String p = "halls." + h.id + ".";
            y.set(p + "owner", h.owner.toString());
            y.set(p + "world", h.world);
            y.set(p + "a", h.a);
            y.set(p + "b", h.b);
            y.set(p + "air", h.air.stream().map(String::valueOf).toList());
            y.set(p + "skin", h.skin.stream().map(String::valueOf).toList());
            y.set(p + "before", befores(h));
        }
        try { y.save(file); } catch (IOException e) { pl.getLogger().warning("Couldn't save domes.yml: " + e.getMessage()); }
    }

    static List<String> befores(Piece d) {
        List<String> b = new ArrayList<>();
        for (Map.Entry<Long, String> en : d.before.entrySet()) b.add(en.getKey() + "|" + en.getValue());
        return b;
    }

    static void readBefores(Piece d, List<String> list) {
        for (String b : list) {
            int bar = b.indexOf('|');
            if (bar > 0) d.before.put(Long.parseLong(b.substring(0, bar)), b.substring(bar + 1));
        }
    }

    void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextId = y.getInt("next", 1);
        String deep = pl.world != null ? pl.world.getName() : pl.worldName();
        ConfigurationSection s = y.getConfigurationSection("domes");
        if (s != null) for (String k : s.getKeys(false)) try {
            Dome d = new Dome();
            d.id = Integer.parseInt(k);
            d.owner = UUID.fromString(s.getString(k + ".owner"));
            d.world = s.getString(k + ".world", deep); // (1.9.x domes were all in the Dark Ocean)
            String[] at = s.getString(k + ".at", "0,0,0").split(",");
            d.x = Integer.parseInt(at[0]); d.y = Integer.parseInt(at[1]); d.z = Integer.parseInt(at[2]);
            d.r = s.getInt(k + ".r");
            readBefores(d, s.getStringList(k + ".before"));
            domes.put(d.id, d);
            nextId = Math.max(nextId, d.id + 1);
        } catch (RuntimeException ex) { pl.getLogger().warning("Skipped a broken dome in domes.yml (" + k + ")"); }
        ConfigurationSection hs = y.getConfigurationSection("halls");
        if (hs != null) for (String k : hs.getKeys(false)) try {
            Hall h = new Hall();
            h.id = Integer.parseInt(k);
            h.owner = UUID.fromString(hs.getString(k + ".owner"));
            h.world = hs.getString(k + ".world", deep);
            h.a = hs.getInt(k + ".a"); h.b = hs.getInt(k + ".b");
            for (String v : hs.getStringList(k + ".air")) h.air.add(Long.parseLong(v));
            for (String v : hs.getStringList(k + ".skin")) h.skin.add(Long.parseLong(v));
            readBefores(h, hs.getStringList(k + ".before"));
            halls.put(h.id, h);
            nextId = Math.max(nextId, h.id + 1);
        } catch (RuntimeException ex) { pl.getLogger().warning("Skipped a broken hallway in domes.yml (" + k + ")"); }
        Set<String> worlds = new HashSet<>();
        for (Dome d : domes.values()) worlds.add(d.world);
        for (Hall h : halls.values()) worlds.add(h.world);
        for (String w : worlds) rebuildShell(w);
    }

    /** /deep domes: every dome. */
    List<String> list() {
        List<String> out = new ArrayList<>();
        for (Dome d : domes.values()) out.add("#" + d.id + " " + Bukkit.getOfflinePlayer(d.owner).getName() + " in " + d.world + " at " + d.x + " " + d.y + " " + d.z + " r" + d.r);
        for (Hall h : halls.values()) out.add("  hallway #" + h.id + ": dome #" + h.a + " <-> #" + h.b);
        if (out.isEmpty()) out.add("No domes.");
        return out;
    }
}
