package net.faultlinesmp.ships;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Input;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Trident;
import org.bukkit.entity.Creeper;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.entity.Snowball;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.block.BlockState;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Faultline Ships: craft a blueprint, lay it out on open water, place every block of the ship, then sail it.
 * Ships have health; at 0 they're wrecked until a Shipwright's Hammer fixes them (a timing minigame).
 */
public final class FaultlineShips extends JavaPlugin implements Listener {

    final Map<UUID, Ship> ships = new LinkedHashMap<>();
    final Map<UUID, Set<UUID>> crews = new HashMap<>();
    final Map<UUID, Repair> repairs = new HashMap<>();
    final Map<UUID, long[]> overboardAsk = new HashMap<>(); // a passenger who sneaked once on a ship under sail
    final Map<UUID, Long> lastSwing = new HashMap<>();
    final Map<UUID, Long> lastUse = new HashMap<>();
    final Set<UUID> arrowsCounted = new HashSet<>();
    Function<Player, Input> inputSource = Player::getCurrentInput; // swapped out by tests
    NamespacedKey hammerKey, cannonKey, ballKey, ballTag, shipIdKey, builtKey;
    final Map<ShipType, NamespacedKey> blueprintKeys = new HashMap<>();
    File dataFile;
    boolean dirty;
    Pirates pirates;
    long now;

    static FaultlineShips instance; // for ShipsApi (other plugins)

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        hammerKey = new NamespacedKey(this, "shipwright_hammer");
        builtKey = new NamespacedKey(this, "built_ship");
        cannonKey = new NamespacedKey(this, "ship_cannon");
        ballKey = new NamespacedKey(this, "cannonball");
        ballTag = new NamespacedKey(this, "cannonball_from");
        shipIdKey = ballTag;
        for (ShipType t : ShipType.values()) blueprintKeys.put(t, new NamespacedKey(this, t.name().toLowerCase() + "_blueprint"));
        dataFile = new File(getDataFolder(), "ships.yml");
        load();
        recipes();
        getServer().getPluginManager().registerEvents(this, this);
        pirates = new Pirates(this);
        getServer().getPluginManager().registerEvents(pirates, this);
        getServer().getPluginManager().registerEvents(pirates.ghost, this);
        for (World w : Bukkit.getWorlds()) for (var ch : w.getLoadedChunks()) { cleanup(ch.getEntities()); cleanDeck(ch); }
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, () -> { if (dirty) saveNow(); }, 100L, 100L);
        getLogger().info("Faultline Ships enabled: " + ships.size() + " ships.");
    }

    @Override
    public void onDisable() {
        if (pirates != null) pirates.shutdown();
        for (Ship s : ships.values()) {
            if (s.hold != null) s.cargo = s.hold.getContents();
            s.despawn();
        }
        saveNow();
    }

    double cfg(String key, double def) { return getConfig().getDouble(key, def); }

    static void attr(org.bukkit.entity.LivingEntity e, org.bukkit.attribute.Attribute a, double v) {
        org.bukkit.attribute.AttributeInstance i = e.getAttribute(a);
        if (i != null) i.setBaseValue(v);
    }

    /** Floodgate gives Bedrock players a UUID whose top half is 0 (same check as FaultlineBosses). */
    static boolean bedrock(Player p) { return p.getUniqueId().getMostSignificantBits() == 0; }

    /** Bedrock players can't see block displays: while a ship is built they're sent the real blocks instead. */
    void showFakes(Ship s, List<Integer> cells) {
        if (!s.building) return;
        List<BlockState> st = null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!bedrock(p) || !p.getWorld().getName().equals(s.world) || p.getLocation().distance(s.center()) > 96) continue;
            if (st == null) st = s.fakes(cells);
            if (!st.isEmpty()) p.sendBlockChanges(st);
        }
    }

    void showAllFakes(Ship s) {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < s.blocks.length; i++) all.add(i);
        showFakes(s, all);
    }

    /** The ship is finished (or gone): back to the real barriers, the stand-in takes over. */
    void clearFakes(Ship s) {
        World w = s.world();
        if (w == null) return;
        List<BlockState> real = new ArrayList<>();
        for (int i = 0; i < s.blocks.length; i++) real.add(s.blockOf(i).getState());
        for (Player p : w.getPlayers()) if (bedrock(p) && p.getLocation().distance(s.center()) < 128) p.sendBlockChanges(real);
    }

    // =====================================================================================================
    //  items
    // =====================================================================================================
    ItemStack blueprint(ShipType t) {
        ItemStack it = new ItemStack(Material.GLOBE_BANNER_PATTERN);
        ItemMeta m = it.getItemMeta();
        ChatColor c = switch (t) {
            case DINGHY -> ChatColor.GREEN;
            case SLOOP -> ChatColor.WHITE;
            case BRIGANTINE -> ChatColor.GOLD;
            case GALLEON -> ChatColor.AQUA;
            case PIRATE -> ChatColor.DARK_GRAY;
        };
        m.setDisplayName(c + "" + ChatColor.BOLD + t.title + " Blueprint");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "A " + t.size + " ship: " + (int) t.maxHp + " health, " + (t.seats.length) + " seats"
                + (t.cargo > 0 ? ", a " + t.cargo + "-slot hold" : "") + ".");
        lore.add(ChatColor.GREEN + "Right-click open water to lay it out,");
        lore.add(ChatColor.GREEN + "then place every glowing block.");
        lore.add(ChatColor.DARK_GRAY + "Needs " + t.cells.size() + " blocks:");
        for (var e : t.bill().entrySet()) lore.add(ChatColor.DARK_GRAY + "  " + e.getValue() + " " + e.getKey().label);
        if (t.deep) lore.add(ChatColor.DARK_GRAY + "Needs water at least 2 deep.");
        if (t == ShipType.DINGHY) lore.add(1, ChatColor.GRAY + "A rowboat for fishing: no cannons.");
        if (t == ShipType.GALLEON || t == ShipType.PIRATE) lore.add(1, ChatColor.GRAY + "A hold below deck: open the hatch.");
        m.setLore(lore);
        m.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        if (t == ShipType.GALLEON || t == ShipType.PIRATE) m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(blueprintKeys.get(t), PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    /** Admin item: a ship that's already built. Right-click open water and it's there, finished. */
    ItemStack builtShip(ShipType t) {
        ItemStack it = new ItemStack(Material.GLOBE_BANNER_PATTERN);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + t.title + ChatColor.GRAY + " (built)");
        m.setLore(List.of(ChatColor.GRAY + "A " + t.size + " ship, already built: " + (int) t.maxHp + " health, " + t.seats.length + " seats.",
                ChatColor.GREEN + "Right-click open water and it's there,",
                ChatColor.GREEN + "finished, anchored and ready to sail.",
                ChatColor.DARK_GRAY + "Admin item. Scrapping it gives this back."));
        m.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(builtKey, PersistentDataType.STRING, t.name());
        it.setItemMeta(m);
        return it;
    }

    ShipType builtOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        String t = it.getItemMeta().getPersistentDataContainer().get(builtKey, PersistentDataType.STRING);
        return t == null ? null : ShipType.of(t);
    }

    ItemStack hammer() {
        ItemStack it = new ItemStack(Material.STICK);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Shipwright's Hammer");
        m.setLore(List.of(ChatColor.GREEN + "Right-click a damaged or wrecked ship",
                ChatColor.GREEN + "to start patching it, then right-click",
                ChatColor.GREEN + "when the marker is in the green.",
                ChatColor.GRAY + "Each good hit uses 1 Planks.",
                ChatColor.DARK_GRAY + "Gold = perfect: it fixes more."));
        m.setItemModel(NamespacedKey.minecraft("mace"));
        m.setMaxStackSize(1);
        m.getPersistentDataContainer().set(hammerKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isHammer(ItemStack it) { return has(it, hammerKey); }
    boolean isCannon(ItemStack it) { return has(it, cannonKey); }
    boolean isBall(ItemStack it) { return has(it, ballKey); }
    boolean ours(ItemStack it) { return isHammer(it) || isCannon(it) || isBall(it) || blueprintOf(it) != null || builtOf(it) != null; }

    /** Built into a ship's cannon spots (the glowing cannons). */
    ItemStack cannon() {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.GRAY + "" + ChatColor.BOLD + "Ship Cannon");
        m.setLore(List.of(ChatColor.GREEN + "Right-click a ship's glowing cannon spot",
                ChatColor.GREEN + "to mount it (or place it like a block).",
                ChatColor.GRAY + "Fire it with a Cannonball: right-click",
                ChatColor.GRAY + "while on the ship, looking over the side."));
        m.setItemModel(new NamespacedKey("faultline", "ship_cannon"));
        m.getPersistentDataContainer().set(cannonKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    /** What the cannon displays show (no name or tag: it's never in an inventory). */
    ItemStack cannonModel() {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setItemModel(new NamespacedKey("faultline", "ship_cannon"));
        it.setItemMeta(m);
        return it;
    }

    ItemStack ball() {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "Cannonball");
        m.setLore(List.of(ChatColor.GREEN + "On a ship, right-click while looking",
                ChatColor.GREEN + "over the side: the nearest cannon on",
                ChatColor.GREEN + "that side fires it.",
                ChatColor.GRAY + "Blows up where it lands. Wrecks ships."));
        m.setItemModel(new NamespacedKey("faultline", "cannonball"));
        m.getPersistentDataContainer().set(ballKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    ShipType blueprintOf(ItemStack it) {
        for (var e : blueprintKeys.entrySet()) if (has(it, e.getValue())) return e.getKey();
        return null;
    }

    static boolean has(ItemStack it, NamespacedKey key) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    void recipes() {
        List<Material> boats = new ArrayList<>();
        for (Material m : Material.values()) if (!m.isLegacy() && m.isItem() && (m.name().endsWith("_BOAT") || m.name().endsWith("_RAFT"))) boats.add(m);
        List<Material> planks = new ArrayList<>();
        for (Material m : Material.values()) if (!m.isLegacy() && m.name().endsWith("_PLANKS")) planks.add(m);
        try {
            ShapedRecipe sloop = new ShapedRecipe(blueprintKeys.get(ShipType.SLOOP), blueprint(ShipType.SLOOP));
            sloop.shape(" P ", "PBP", " P ");
            sloop.setIngredient('P', Material.PAPER);
            sloop.setIngredient('B', new RecipeChoice.MaterialChoice(boats));
            add(sloop);
            ShapedRecipe brig = new ShapedRecipe(blueprintKeys.get(ShipType.BRIGANTINE), blueprint(ShipType.BRIGANTINE));
            brig.shape("GPG", "PSP", "GPG");
            brig.setIngredient('G', Material.GOLD_INGOT);
            brig.setIngredient('P', Material.PAPER);
            brig.setIngredient('S', new RecipeChoice.ExactChoice(blueprint(ShipType.SLOOP)));
            add(brig);
            ShapedRecipe gal = new ShapedRecipe(blueprintKeys.get(ShipType.GALLEON), blueprint(ShipType.GALLEON));
            gal.shape("DPD", "PBP", "DPD");
            gal.setIngredient('D', Material.DIAMOND);
            gal.setIngredient('P', Material.PAPER);
            gal.setIngredient('B', new RecipeChoice.ExactChoice(blueprint(ShipType.BRIGANTINE)));
            add(gal);
            ItemStack dinghy = blueprint(ShipType.DINGHY);
            ShapelessRecipe din = new ShapelessRecipe(blueprintKeys.get(ShipType.DINGHY), dinghy);
            din.addIngredient(Material.PAPER);
            din.addIngredient(Material.PAPER);
            din.addIngredient(new RecipeChoice.MaterialChoice(boats));
            Bukkit.removeRecipe(din.getKey());
            Bukkit.addRecipe(din);
            ShapedRecipe pir = new ShapedRecipe(blueprintKeys.get(ShipType.PIRATE), blueprint(ShipType.PIRATE));
            pir.shape("DPD", "PGP", "DSD");
            pir.setIngredient('D', Material.BLACK_DYE);
            pir.setIngredient('P', Material.PAPER);
            pir.setIngredient('G', new RecipeChoice.ExactChoice(blueprint(ShipType.GALLEON)));
            pir.setIngredient('S', Material.WITHER_SKELETON_SKULL);
            add(pir);
            ShapedRecipe can = new ShapedRecipe(cannonKey, cannon());
            can.shape("III", "IG ", "L L");
            can.setIngredient('I', Material.IRON_INGOT);
            can.setIngredient('G', Material.GUNPOWDER);
            List<Material> logs = new ArrayList<>();
            for (Material m : Material.values()) if (!m.isLegacy() && m.isItem() && ShipType.Need.LOG.accepts(m)) logs.add(m);
            can.setIngredient('L', new RecipeChoice.MaterialChoice(logs));
            add(can);
            ItemStack four = ball();
            four.setAmount(4);
            ShapelessRecipe balls = new ShapelessRecipe(ballKey, four);
            balls.addIngredient(Material.IRON_INGOT);
            balls.addIngredient(Material.GUNPOWDER);
            Bukkit.removeRecipe(ballKey);
            Bukkit.addRecipe(balls);
            ShapedRecipe ham = new ShapedRecipe(hammerKey, hammer());
            ham.shape("III", "IWI", " S ");
            ham.setIngredient('I', Material.IRON_INGOT);
            ham.setIngredient('W', new RecipeChoice.MaterialChoice(planks));
            ham.setIngredient('S', Material.STICK);
            add(ham);
        } catch (RuntimeException ex) {
            getLogger().warning("Couldn't add the ship recipes: " + ex);
        }
    }

    private void add(ShapedRecipe r) {
        try {
            Bukkit.removeRecipe(r.getKey());
            Bukkit.addRecipe(r);
        } catch (RuntimeException ex) {
            getLogger().warning("Couldn't add the recipe " + r.getKey() + ": " + ex);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        try {
            for (NamespacedKey k : blueprintKeys.values()) event.getPlayer().discoverRecipe(k);
            event.getPlayer().discoverRecipe(hammerKey);
            event.getPlayer().discoverRecipe(cannonKey);
            event.getPlayer().discoverRecipe(ballKey);
        } catch (RuntimeException ignored) { }
    }

    /** Hammers and blueprints only go into our own recipes (a hammer is a stick underneath). */
    @EventHandler
    public void onCraft(PrepareItemCraftEvent event) {
        boolean ours = event.getRecipe() != null && event.getRecipe().getResult() != null && blueprintOf(event.getRecipe().getResult()) != null;
        for (ItemStack it : event.getInventory().getMatrix()) { // our items are paper/sticks underneath: keep them out of other recipes
            if (isHammer(it) || isCannon(it) || isBall(it) || builtOf(it) != null || (!ours && blueprintOf(it) != null)) { event.getInventory().setResult(null); return; }
        }
    }

    // =====================================================================================================
    //  laying out and building
    // =====================================================================================================
    /** Where a blueprint would go: the stern at the water block you look at, bow pointing the way you face. */
    Ship plan(Player p, ShipType t) { return plan(p, t, p.getUniqueId()); }

    Ship plan(Player p, ShipType t, UUID owner) {
        RayTraceResult hit = p.rayTraceBlocks(10, FluidCollisionMode.ALWAYS);
        if (hit == null || hit.getHitBlock() == null) return null;
        Block b = hit.getHitBlock();
        if (b.getType() != Material.WATER || !Ship.air(b.getRelative(0, 1, 0))) return null;
        Ship s = new Ship(this, UUID.randomUUID(), t, owner);
        s.world = b.getWorld().getName();
        s.yaw = ((Math.round(p.getLocation().getYaw() / 90f) * 90) % 360 + 360) % 360;
        double back = -t.minX + 1;
        s.x = b.getX() + 0.5 + Math.round(Ship.fx(s.yaw)) * back;
        s.z = b.getZ() + 0.5 + Math.round(Ship.fz(s.yaw)) * back;
        s.y = b.getY();
        return s;
    }

    void layOut(Player p, ItemStack item, ShipType t) { layOut(p, item, t, false, p.getUniqueId()); }

    /** prebuilt: an admin's ready-made ship, finished at once (item may be null: /ship spawn). */
    Ship layOut(Player p, ItemStack item, ShipType t, boolean prebuilt, UUID owner) {
        int mine = 0;
        for (Ship s : ships.values()) if (s.owner.equals(p.getUniqueId())) mine++;
        int max = getConfig().getInt("max-ships-per-player", 3);
        if (mine >= max && !p.hasPermission("faultlineships.admin")) {
            p.sendMessage(ChatColor.RED + "You already have " + mine + " ships (the limit is " + max + "). Scrap one with /ship scrap first.");
            return null;
        }
        Ship s = plan(p, t, owner);
        if (s == null) { p.sendActionBar(Component.text("Look at open water to " + (prebuilt ? "put" : "lay out") + " the " + t.title + ".", NamedTextColor.YELLOW)); return null; }
        Set<Block> bad = new HashSet<>();
        if (!s.clear(s.x, s.z, s.yaw, true, bad)) {
            for (Block b : bad) p.spawnParticle(Particle.DUST, b.getLocation().add(0.5, 0.5, 0.5), 3, 0.2, 0.2, 0.2, 0, new Particle.DustOptions(Color.RED, 1.5f));
            p.sendMessage(ChatColor.RED + "Not enough open water for a " + t.title + " here (" + bad.size() + " blocks in the way, shown in red)."
                    + (t.deep ? ChatColor.GRAY + " It needs water 2 deep under the keel." : ""));
            return null;
        }
        if (item != null && p.getGameMode() != GameMode.CREATIVE) item.setAmount(item.getAmount() - 1);
        s.building = true;
        s.anchored = true;
        s.hp = s.maxHp();
        s.prebuilt = prebuilt;
        ships.put(s.id, s);
        s.spawn();
        if (prebuilt) { s.completeAll(); return s; }
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 1f, 0.8f);
        StringBuilder sb = new StringBuilder();
        for (var e : t.bill().entrySet()) sb.append(sb.length() == 0 ? "" : ", ").append(e.getValue()).append(" ").append(e.getKey().label);
        p.sendMessage(ChatColor.AQUA + "You laid out a " + t.title + ". " + ChatColor.GRAY + "Place the glowing blocks: " + sb
                + ". Sneak + right-click fills up to 16 at once. Any wood type works.");
        saveNow();
        return s;
    }

    /** The first ship cell along the player's view: an empty one to fill, or null. */
    Object[] aimedGhost(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().multiply(0.1);
        Location at = eye.clone();
        for (int i = 0; i < 60; i++) {
            at.add(dir);
            Block b = at.getBlock();
            for (Ship s : ships.values()) {
                if (!s.spawned() || !s.building || !b.getWorld().getName().equals(s.world)) continue;
                int c = s.cellAt(at.getX(), at.getY(), at.getZ());
                if (c < 0) continue;
                if (s.blocks[c] != null) return null;
                return new Object[]{s, c};
            }
            if (b.getType().isSolid() && b.getType() != Material.BARRIER) return null;
        }
        return null;
    }

    /** Fill one cell (and with sneak, up to 15 more like it nearby) with the block in hand. */
    boolean place(Player p, Ship s, int cell, ItemStack hand) {
        ShipType.Need need = s.type.cells.get(cell).need();
        Material m = hand.getType();
        if (need == ShipType.Need.CANNON ? !isCannon(hand) : (isCannon(hand) || !need.accepts(m))) {
            p.sendActionBar(Component.text("This spot needs " + need.label + ".", NamedTextColor.YELLOW));
            return false;
        }
        boolean creative = p.getGameMode() == GameMode.CREATIVE;
        List<Integer> todo = new ArrayList<>();
        todo.add(cell);
        if (p.isSneaking()) {
            ShipType.Cell c0 = s.type.cells.get(cell);
            List<Integer> near = new ArrayList<>();
            for (int i = 0; i < s.blocks.length; i++) if (i != cell && s.blocks[i] == null && s.type.cells.get(i).need() == need) near.add(i);
            near.sort((a, b) -> Double.compare(dist(s.type.cells.get(a), c0), dist(s.type.cells.get(b), c0)));
            for (int i = 0; i < near.size() && todo.size() < 16; i++) todo.add(near.get(i));
        }
        int done = 0;
        for (int c : todo) {
            if (!creative && !(need == ShipType.Need.CANNON ? takeItem(p, hand) : take(p, m))) break;
            s.fill(c, m);
            done++;
            if (!s.building) break;
        }
        if (done > 0) p.swingMainHand();
        if (s.building) s.refreshLabel();
        return done > 0;
    }

    static double dist(ShipType.Cell a, ShipType.Cell b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return dx * dx + dy * dy * 1.5 + dz * dz;
    }

    /** One of this exact custom item, from the hand. */
    static boolean takeItem(Player p, ItemStack like) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.isSimilar(like) && hand.getAmount() > 0) { hand.setAmount(hand.getAmount() - 1); return true; }
        return false;
    }

    /** Take one of this item, from the hand first. */
    static boolean take(Player p, Material m) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() == m && hand.getAmount() > 0) { hand.setAmount(hand.getAmount() - 1); return true; }
        var left = p.getInventory().removeItem(new ItemStack(m, 1));
        return left.isEmpty();
    }

    // =====================================================================================================
    //  lookups
    // =====================================================================================================
    Ship shipCellAt(Block b, Ship except) {
        for (Ship s : ships.values()) {
            if (s == except || s.world == null || !s.world.equals(b.getWorld().getName())) continue;
            if (Math.abs(b.getX() + 0.5 - s.x) > s.radius() + 1 || Math.abs(b.getZ() + 0.5 - s.z) > s.radius() + 1) continue;
            if (s.cellAt(b) >= 0) return s;
        }
        return null;
    }

    /** A ship whose space (deck to mast tops) this block is in. */
    Ship shipOver(Block b) {
        for (Ship s : ships.values()) {
            if (s.world == null || !s.world.equals(b.getWorld().getName())) continue;
            double px = b.getX() + 0.5 - s.x, pz = b.getZ() + 0.5 - s.z, fx = Ship.fx(s.yaw), fz = Ship.fz(s.yaw);
            double lx = px * fx + pz * fz, lz = -px * fz + pz * fx;
            if (lx >= s.type.minX - 0.5 && lx <= s.type.maxX + 4.5 && Math.abs(lz) <= s.type.halfWidth + 0.5
                    && b.getY() >= s.y && b.getY() <= s.y + s.type.height + 1) return s;
        }
        return null;
    }

    Ship shipOfEntity(Entity e) {
        if (e == null || !e.getScoreboardTags().contains(Ship.TAG)) return null;
        for (Ship s : ships.values()) {
            if (s.hitboxes.contains(e)) return s;
            if (s.seats != null) for (Entity seat : s.seats) if (e.equals(seat)) return s;
            if (e.equals(s.stand)) return s;
        }
        return null;
    }

    Ship nearest(Player p, double within) {
        Ship best = null;
        double bd = Double.MAX_VALUE;
        for (Ship s : ships.values()) {
            if (!p.getWorld().getName().equals(s.world)) continue;
            double d = p.getLocation().distance(new Location(p.getWorld(), s.x, p.getLocation().getY(), s.z)) - s.radius();
            if (d < within && d < bd) { bd = d; best = s; }
        }
        return best;
    }

    Ship ridingOn(Player p) {
        Entity v = p.getVehicle();
        return v == null ? null : shipOfEntity(v);
    }

    boolean mayCaptain(Player p, Ship s) {
        if (p.getUniqueId().equals(s.owner) || p.hasPermission("faultlineships.admin")) return true;
        Set<UUID> crew = crews.get(s.owner);
        return crew != null && crew.contains(p.getUniqueId());
    }

    // =====================================================================================================
    //  events
    // =====================================================================================================
    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player p = event.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        Action a = event.getAction();
        Block clicked = event.getClickedBlock();
        Ship onShip = clicked != null ? shipCellAt(clicked, null) : null;

        if (a == Action.LEFT_CLICK_BLOCK && onShip != null) { // punching an anchored ship
            event.setCancelled(true);
            swing(p, onShip);
            return;
        }
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;

        if (isHammer(hand)) {
            event.setCancelled(true);
            Ship target = onShip != null ? onShip : null;
            if (target == null) {
                Repair r = repairs.get(p.getUniqueId());
                if (r != null) target = r.ship;
            }
            hammer(p, target);
            return;
        }
        ShipType built = builtOf(hand);
        if (built != null) {
            event.setCancelled(true);
            if (used(p)) return;
            layOut(p, hand, built, true, p.getUniqueId());
            return;
        }
        ShipType bp = blueprintOf(hand);
        if (bp != null) {
            event.setCancelled(true);
            if (used(p)) return;
            layOut(p, hand, bp);
            return;
        }
        if (isBall(hand)) {
            event.setCancelled(true);
            if (used(p)) return;
            fire(p);
            return;
        }
        if (onShip != null && a == Action.RIGHT_CLICK_BLOCK && hand.getType().name().endsWith("_BANNER") && !onShip.building) {
            event.setCancelled(true);
            if (used(p)) return;
            hangBanner(p, onShip, hand);
            return;
        }
        if ((hand.getType().isBlock() && hand.getType() != Material.AIR) || isCannon(hand)) {
            Object[] g = aimedGhost(p);
            if (g != null) {
                event.setCancelled(true);
                if (used(p)) return;
                place(p, (Ship) g[0], (int) g[1], hand);
                return;
            }
        }
        if (onShip != null && a == Action.RIGHT_CLICK_BLOCK) {
            int c = onShip.cellAt(clicked);
            ShipType.Cell cell = onShip.type.cells.get(c);
            if (cell.need() == ShipType.Need.HATCH && !onShip.building) return;   // the hatch opens like any trapdoor
            if (cell.need() == ShipType.Need.TRAPDOOR) { event.setCancelled(true); if (!onShip.building && !p.isSneaking() && !used(p)) onShip.board(p, false, clicked.getLocation().add(0.5, 1, 0.5)); return; } // the crow's nest stays shut
            if (cell.need() == ShipType.Need.CHEST && onShip.blocks[c] != null && !onShip.building) {
                event.setCancelled(true);
                openHold(p, onShip);
                return;
            }
            if (onShip.building || p.isSneaking()) return;
            if (hand.getType() == Material.AIR || !hand.getType().isBlock()) {
                event.setCancelled(true);
                if (used(p)) return;
                boolean wheel = cell.need() == ShipType.Need.FENCE && cell.z() == 0 && onShip.type.seats[0][0] == cell.x() - 1 && onShip.type.seats[0][1] == cell.y();
                onShip.board(p, wheel, clicked.getLocation().add(0.5, 1, 0.5));
            }
        }
    }

    /** One use per click: holding the button repeats the event every few ticks. */
    boolean used(Player p) {
        Long last = lastUse.put(p.getUniqueId(), now);
        return last != null && now - last < 5;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Ship s = shipOfEntity(event.getRightClicked());
        if (s == null) return;
        event.setCancelled(true);
        Player p = event.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (isHammer(hand)) { hammer(p, s); return; }
        if (used(p)) return;
        if (isBall(hand)) { fire(p); return; }
        if (hand.getType().name().endsWith("_BANNER")) { hangBanner(p, s, hand); return; }
        if (p.isSneaking()) { openHold(p, s); return; }
        // in the water beside it: climb up onto the deck (a free seat isn't needed, and a full ship still takes you back)
        if (!s.anchored && s.deckOn() && (p.isInWater() || p.getLocation().getY() < s.y + s.type.deckY)) { s.climbAboard(p); return; }
        s.board(p, false, p.getLocation());
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttackHitbox(PrePlayerAttackEntityEvent event) {
        Ship s = shipOfEntity(event.getAttacked());
        if (s == null) return;
        event.setCancelled(true);
        swing(event.getPlayer(), s);
    }

    void swing(Player p, Ship s) {
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR || s.building || s.wrecked) return;
        if (mayCaptain(p, s)) { p.sendActionBar(Component.text("That's your own ship.", NamedTextColor.GRAY)); return; }
        Long last = lastSwing.get(p.getUniqueId());
        if (last != null && now - last < 10) return;
        lastSwing.put(p.getUniqueId(), now);
        String n = p.getInventory().getItemInMainHand().getType().name();
        double dmg = n.endsWith("_AXE") ? 8 : n.endsWith("_SWORD") ? 5 : n.equals("MACE") ? 6 : n.equals("TRIDENT") ? 5 : n.endsWith("_PICKAXE") ? 3 : 1;
        dmg *= cfg("damage.melee-multiplier", 1.0);
        Location eye = p.getEyeLocation();
        s.damage(dmg, p, eye.clone().add(eye.getDirection().multiply(2.5)));
    }

    void hangBanner(Player p, Ship s, ItemStack hand) {
        if (!mayCaptain(p, s)) { p.sendActionBar(Component.text("Only the owner and their crew can hang a banner.", NamedTextColor.RED)); return; }
        ItemStack one = hand.clone();
        one.setAmount(1);
        if (p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        ItemStack old = s.setBanner(one);
        if (old != null && p.getGameMode() != GameMode.CREATIVE)
            for (ItemStack left : p.getInventory().addItem(old).values()) p.getWorld().dropItem(p.getLocation(), left);
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, SoundCategory.PLAYERS, 1f, 0.8f);
        p.sendMessage(ChatColor.AQUA + "Your banner flies from the " + s.title() + "'s mast. " + ChatColor.GRAY + "/ship banner takes it down.");
    }

    // =====================================================================================================
    //  cannons
    // =====================================================================================================
    /** On (or by) a ship with a cannonball: the nearest ready cannon on the side you're looking at fires. */
    void fire(Player p) {
        Ship s = ridingOn(p);
        if (s == null) {
            for (Ship o : ships.values()) {
                if (!o.spawned() || !p.getWorld().getName().equals(o.world)) continue;
                Location l = p.getLocation();
                if (o.cellAt(l.getX(), l.getY() - 0.2, l.getZ()) >= 0 || o.cellAt(l.getX(), l.getY() - 1.2, l.getZ()) >= 0) { s = o; break; }
            }
        }
        if (s == null) { p.sendActionBar(Component.text("Cannonballs are fired from a ship's cannons: get on board.", NamedTextColor.YELLOW)); return; }
        if (s.building || s.wrecked) { p.sendActionBar(Component.text("The cannons can't fire right now.", NamedTextColor.RED)); return; }
        List<Integer> guns = s.cannons();
        if (guns.isEmpty()) { p.sendActionBar(Component.text("This ship has no cannons mounted.", NamedTextColor.YELLOW)); return; }
        Vector look = p.getEyeLocation().getDirection();
        double[] right = Ship.toWorld(0, 0, s.yaw, 0, 1);
        int side = look.getX() * right[0] + look.getZ() * right[1] >= 0 ? 1 : -1;
        long t = now;
        Integer best = null;
        double bd = Double.MAX_VALUE;
        boolean anyOnSide = false;
        for (int i : guns) {
            if (s.sideOf(i) != side) continue;
            anyOnSide = true;
            if (s.cannonReady.getOrDefault(i, 0L) > t) continue;
            double d = s.muzzle(i).distanceSquared(p.getLocation());
            if (d < bd) { bd = d; best = i; }
        }
        if (best == null) {
            p.sendActionBar(Component.text(anyOnSide ? "Reloading..." : "No cannon on that side.", NamedTextColor.YELLOW));
            return;
        }
        if (p.getGameMode() != GameMode.CREATIVE) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            hand.setAmount(hand.getAmount() - 1);
        }
        s.cannonReady.put(best, t + (long) (cfg("cannon.reload-seconds", 3) * 20));
        Location mz = s.muzzle(best);
        Vector out = s.outward(best);
        // aim: where you look, but no more than 35 degrees off straight out, and not below the water
        Vector aim = look.clone();
        Vector flat = new Vector(aim.getX(), 0, aim.getZ());
        if (flat.lengthSquared() < 1e-6) flat = out.clone();
        flat.normalize();
        double ang = Math.acos(Math.max(-1, Math.min(1, flat.dot(out))));
        double max = Math.toRadians(35);
        if (ang > max) {
            double cross = out.getX() * flat.getZ() - out.getZ() * flat.getX();
            double a = Math.signum(cross) * max, c = Math.cos(a), sn = Math.sin(a);
            flat = new Vector(out.getX() * c - out.getZ() * sn, 0, out.getX() * sn + out.getZ() * c);
        }
        double pitch = Math.max(Math.toRadians(-8), Math.min(Math.toRadians(30), Math.asin(Math.max(-1, Math.min(1, look.getY())))));
        Vector dir = flat.multiply(Math.cos(pitch)).setY(Math.sin(pitch)).normalize().multiply(cfg("cannon.speed", 2.4));
        launch(s, mz, dir, p);
    }

    /** A cannonball out of a ship's muzzle (players' and skeletons' alike). */
    Snowball launch(Ship from, Location mz, Vector vel, org.bukkit.projectiles.ProjectileSource shooter) {
        World w = mz.getWorld();
        Snowball ball = w.spawn(mz, Snowball.class, b -> {
            b.setItem(ball());
            if (shooter != null) b.setShooter(shooter);
            b.setVelocity(vel);
            b.getPersistentDataContainer().set(ballTag, PersistentDataType.STRING, from.id.toString());
        });
        w.playSound(mz, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 1.6f, 1.6f);
        w.playSound(mz, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST, SoundCategory.PLAYERS, 1.2f, 0.6f);
        w.spawnParticle(Particle.LARGE_SMOKE, mz, 14, 0.25, 0.2, 0.25, 0.05);
        w.spawnParticle(Particle.FLAME, mz, 10, 0.15, 0.15, 0.15, 0.04);
        w.spawnParticle(Particle.EXPLOSION, mz, 1, 0, 0, 0, 0);
        return ball;
    }

    boolean ballExploding; // impact() is setting off its own explosion: the explosion handlers leave the ships to it

    Ship ballShip(Entity e) {
        String id = e.getPersistentDataContainer().get(ballTag, PersistentDataType.STRING);
        if (id == null) return null;
        try { return ships.get(UUID.fromString(id)); } catch (IllegalArgumentException ex) { return null; }
    }

    boolean isBallEntity(Entity e) { return e instanceof Snowball && e.getPersistentDataContainer().has(ballTag, PersistentDataType.STRING); }

    /** A cannonball lands: an explosion that doesn't break blocks, and it hurts ships nearby (not the one that fired it). */
    void impact(Entity ball, Location at, Entity hit) {
        if (!ball.isValid()) return;
        Ship from = ballShip(ball);
        Entity source = ball instanceof Projectile pr && pr.getShooter() instanceof Entity en ? en : null;
        Player shooter = source instanceof Player pp ? pp : null;
        boolean pirate = from != null && from.ai != null;
        ball.remove();
        World w = at.getWorld();
        ballExploding = true;
        try { w.createExplosion(at, (float) cfg("cannon.explosion-power", 2.0), false, getConfig().getBoolean("cannon.break-blocks", false), source); }
        finally { ballExploding = false; }
        if (hit instanceof org.bukkit.entity.LivingEntity le && shipOfEntity(hit) == null && !(pirate && pirates.isPirate(hit)))
            le.damage(cfg("cannon.hit-damage", 10), source);
        for (Ship s : ships.values()) {
            if (s == from || !at.getWorld().getName().equals(s.world) || s.building || s.wrecked) continue;
            if (pirate && s.ai != null) continue; // skeletons don't shoot their own fleet
            if (at.distance(s.center()) > s.radius() + 6) continue;
            double near = Double.MAX_VALUE;
            for (int i : s.type.probes) {
                ShipType.Cell c = s.type.cells.get(i);
                double[] wp = Ship.toWorld(s.x, s.z, s.yaw, c.x(), c.z());
                double dx = wp[0] - at.getX(), dy = s.y + c.y() + 0.5 - at.getY(), dz = wp[1] - at.getZ();
                near = Math.min(near, dx * dx + dy * dy + dz * dz);
            }
            double d = Math.sqrt(near), r = cfg("cannon.ship-radius", 4);
            if (d < r) s.damage(cfg("cannon.ship-damage", 45) * (1 - d / r * 0.6) * cfg("damage.projectile-multiplier", 1.0), shooter, at);
        }
    }

    void openHold(Player p, Ship s) {
        if (s.type.cargo <= 0) { p.sendActionBar(Component.text("The " + s.type.title + " has no hold.", NamedTextColor.GRAY)); return; }
        if (s.ai != null) { // a skeleton ship's hold: plunder for whoever gets on board
            if (!s.anchored) { p.sendActionBar(Component.text("Get it to drop anchor first: pull alongside.", NamedTextColor.YELLOW)); return; }
            pirates.plunder(p, s);
            p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, SoundCategory.BLOCKS, 0.8f, 0.9f);
            return;
        }
        if (!mayCaptain(p, s)) { p.sendActionBar(Component.text("Only the owner and their crew can open the hold.", NamedTextColor.RED)); return; }
        p.openInventory(s.hold());
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, SoundCategory.BLOCKS, 0.8f, 0.9f);
    }

    /** Seats and the Bedrock stand-in are armor stands: nothing may break them or take what they wear. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onShipEntityHurt(EntityDamageEvent event) {
        Entity e = event.getEntity();
        if (!e.getScoreboardTags().contains(Ship.TAG)) return;
        event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent by) {
            Ship s = shipOfEntity(e);
            if (s == null) return;
            if (by.getDamager() instanceof Player p) swing(p, s);           // a Bedrock player hitting the stand-in
            else if (by.getDamager() instanceof Projectile pr && !arrowsCounted.contains(pr.getUniqueId())) {
                arrowsCounted.add(pr.getUniqueId());
                s.damage((pr instanceof AbstractArrow ar ? Math.max(1, ar.getDamage()) : 2) * cfg("damage.projectile-multiplier", 1.0),
                        pr.getShooter() instanceof Player sp ? sp : null, pr.getLocation());
            }
        }
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        Ship s = shipOfEntity(event.getDismounted());
        if (s == null || s.seats == null) return;
        int seat = -1;
        for (int i = 0; i < s.seats.length; i++) if (event.getDismounted().equals(s.seats[i])) seat = i;
        if (seat < 0) return;
        if (!s.anchored && s.deckOn() && !s.building) {
            // the deck is solid under way too: stand up where you sat (the deck carries you along)
            if (s.releasing || !event.isCancellable()) return;
            Location at = s.seatLoc(seat);
            at.setY(s.y + s.type.seats[seat][1] + 0.02);
            at.setYaw(p.getLocation().getYaw()); at.setPitch(p.getLocation().getPitch());
            s.updateDeck();
            Bukkit.getScheduler().runTask(this, () -> { if (p.isOnline() && !p.isInsideVehicle()) p.teleport(at); });
            return;
        }
        if (!s.anchored) {
            // Not anchored = no solid deck: stepping off would drop you through the ship. Anchor first (or go over the side).
            if (s.releasing || s.wrecked || s.building || s.ai != null || !event.isCancellable() || !p.isOnline() || p.isDead()) return;
            Player cap = s.rider(0);
            if (cap != null && cap != p && Math.abs(s.speed) > 0.01) { // someone else is sailing it: don't stop their ship
                // holding sneak asks every tick: only a second, separate press within 2 s means "over the side"
                long[] asked = overboardAsk.get(p.getUniqueId()); // {first press, last tick it was held}
                if (asked != null && now - asked[1] <= 2) { asked[1] = now; event.setCancelled(true); return; } // still the same press
                if (asked != null && now - asked[0] <= 40) { overboardAsk.remove(p.getUniqueId()); if (s.overboard(p, seat)) return; }
                event.setCancelled(true);
                overboardAsk.put(p.getUniqueId(), new long[]{now, now});
                p.sendActionBar(Component.text("The ship is under sail: the captain drops anchor with Jump. Sneak again to jump overboard.", NamedTextColor.YELLOW));
                return;
            }
            event.setCancelled(true);
            if (s.gettingOff.add(p.getUniqueId())) {
                s.anchorForLeave = true;
                p.sendActionBar(Component.text("Dropping anchor so you can step off...", NamedTextColor.YELLOW));
            }
            return;
        }
        Location at = s.seatLoc(seat);
        at.setY(s.y + s.type.seats[seat][1] + 0.01);
        at.setYaw(p.getLocation().getYaw()); at.setPitch(p.getLocation().getPitch());
        Bukkit.getScheduler().runTask(this, () -> { if (p.isOnline() && !p.isInsideVehicle()) p.teleport(at); });
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (event.getRightClicked().getScoreboardTags().contains(Ship.TAG)) event.setCancelled(true);
    }

    /** A hammer is a stick underneath: it doesn't burn. */
    @EventHandler(ignoreCancelled = true)
    public void onBurn(FurnaceBurnEvent event) {
        if (isHammer(event.getFuel())) event.setCancelled(true);
    }

    /** Arrows that land in an anchored ship (its barriers) hurt it too. */
    @EventHandler(ignoreCancelled = true)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (isBallEntity(event.getEntity())) {
            Ship own = ballShip(event.getEntity());
            if (event.getHitEntity() != null && own != null && shipOfEntity(event.getHitEntity()) == own) { event.setCancelled(true); return; }
            impact(event.getEntity(), event.getEntity().getLocation(), event.getHitEntity());
            return;
        }
        Block b = event.getHitBlock();
        if (b == null || !Ship.shipBlock(b.getType())) return;
        Ship s = shipCellAt(b, null);
        Projectile pr = event.getEntity();
        if (s == null || arrowsCounted.contains(pr.getUniqueId())) return;
        if (pr.getShooter() instanceof Player sp && mayCaptain(sp, s)) return;
        arrowsCounted.add(pr.getUniqueId());
        double dmg = pr instanceof Trident ? 6 : pr instanceof AbstractArrow ar ? Math.max(1, ar.getDamage()) * (ar.getFireTicks() > 0 ? 1.5 : 1) : 2;
        s.damage(dmg * cfg("damage.projectile-multiplier", 1.0), pr.getShooter() instanceof Player p ? p : null, b.getLocation().add(0.5, 0.5, 0.5));
    }

    @EventHandler
    public void onHoldClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Ship.Hold h) {
            h.ship.cargo = event.getInventory().getContents();
            dirty = true;
        }
    }

    /** Placing a real block into a ship cell fills it instead (or is refused). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block b = event.getBlockPlaced();
        Ship s = shipCellAt(b, null);
        if (s == null) {
            Ship over = shipOver(b);
            if (over != null) {
                event.setCancelled(true);
                event.getPlayer().sendActionBar(Component.text("You can't build on a ship: it would be left behind when it sails.", NamedTextColor.RED));
            }
            return;
        }
        event.setCancelled(true);
        int c = s.cellAt(b);
        if (s.building && s.blocks[c] == null) {
            Player p = event.getPlayer();
            Bukkit.getScheduler().runTask(this, () -> place(p, s, c, p.getInventory().getItemInMainHand()));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(org.bukkit.event.block.BlockBurnEvent event) {
        if (Ship.shipBlock(event.getBlock().getType()) && shipCellAt(event.getBlock(), null) != null) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonPush(org.bukkit.event.block.BlockPistonExtendEvent event) {
        for (Block b : event.getBlocks()) if (Ship.shipBlock(b.getType()) && shipCellAt(b, null) != null) { event.setCancelled(true); return; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonPull(org.bukkit.event.block.BlockPistonRetractEvent event) {
        for (Block b : event.getBlocks()) if (Ship.shipBlock(b.getType()) && shipCellAt(b, null) != null) { event.setCancelled(true); return; }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Ship s = shipCellAt(event.getBlock(), null);
        if (s != null && (s.anchored || s.building) && Ship.shipBlock(event.getBlock().getType())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        Entity e = event.getEntity();
        if (ballExploding || e instanceof Player) { event.blockList().removeIf(b -> Ship.shipBlock(b.getType()) && shipCellAt(b, null) != null); return; } // a cannonball (impact() hurt the ships)
        double base = switch (e.getType().name()) {
            case "TNT", "TNT_MINECART" -> 60;
            case "CREEPER" -> e instanceof Creeper c && c.isPowered() ? 80 : 40;
            case "FIREBALL" -> 18;
            case "WITHER_SKULL" -> 20;
            case "END_CRYSTAL" -> 70;
            case "WIND_CHARGE", "BREEZE_WIND_CHARGE" -> 0;
            default -> 30;
        };
        blast(event.getLocation(), base, event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (ballExploding) { event.blockList().removeIf(b -> Ship.shipBlock(b.getType()) && shipCellAt(b, null) != null); return; }
        blast(event.getBlock().getLocation().add(0.5, 0.5, 0.5), 40, event.blockList());
    }

    void blast(Location at, double base, List<Block> blocks) {
        blocks.removeIf(b -> Ship.shipBlock(b.getType()) && shipCellAt(b, null) != null);
        if (base <= 0) return;
        double r = cfg("damage.explosion-radius", 7);
        for (Ship s : ships.values()) {
            if (!at.getWorld().getName().equals(s.world) || s.building || s.wrecked) continue;
            if (at.distance(s.center()) > s.radius() + r + 4) continue;
            double best = Double.MAX_VALUE;
            for (int i : s.type.probes) {
                ShipType.Cell c = s.type.cells.get(i);
                double[] w = Ship.toWorld(s.x, s.z, s.yaw, c.x(), c.z());
                double dx = w[0] - at.getX(), dy = s.y + c.y() + 0.5 - at.getY(), dz = w[1] - at.getZ();
                best = Math.min(best, dx * dx + dy * dy + dz * dz);
            }
            double d = Math.sqrt(best);
            if (d < r) s.damage(base * (1 - d / r) * cfg("damage.explosion-multiplier", 1.0), null, at);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        repairs.remove(event.getPlayer().getUniqueId());
        lastSwing.remove(event.getPlayer().getUniqueId());
        lastUse.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) { cleanup(event.getChunk().getEntities()); cleanDeck(event.getChunk()); }

    // ---- the moving deck's barriers, remembered in each chunk's own data (saved with its blocks, so a crash never
    //      leaves stray barriers in the sea: whatever a chunk still lists when it loads, and no ship owns, goes) ----
    final NamespacedKey deckKey = new NamespacedKey(this, "deck");

    static long local(Block b) { return (b.getX() & 15) | ((long) (b.getZ() & 15) << 4) | ((long) (b.getY() + 4096) << 8); }

    void deckMark(Block b, boolean add) {
        try {
            var pdc = b.getChunk().getPersistentDataContainer();
            long[] have = pdc.getOrDefault(deckKey, org.bukkit.persistence.PersistentDataType.LONG_ARRAY, new long[0]);
            long me = local(b);
            java.util.LinkedHashSet<Long> set = new java.util.LinkedHashSet<>();
            for (long v : have) set.add(v);
            if (add ? !set.add(me) : !set.remove(me)) return;
            if (set.isEmpty()) pdc.remove(deckKey);
            else pdc.set(deckKey, org.bukkit.persistence.PersistentDataType.LONG_ARRAY, set.stream().mapToLong(Long::longValue).toArray());
        } catch (RuntimeException ignored) { }
    }

    void cleanDeck(org.bukkit.Chunk ch) {
        try {
            var pdc = ch.getPersistentDataContainer();
            long[] have = pdc.get(deckKey, org.bukkit.persistence.PersistentDataType.LONG_ARRAY);
            if (have == null) return;
            List<Long> keep = new ArrayList<>();
            for (long v : have) {
                int bx = (ch.getX() << 4) | (int) (v & 15), bz = (ch.getZ() << 4) | (int) ((v >> 4) & 15), by = (int) (v >> 8) - 4096;
                boolean live = false;
                for (Ship s : ships.values()) if (s.deck.containsKey(Ship.key(bx, by, bz))) { live = true; break; }
                if (live) { keep.add(v); continue; }
                Block b = ch.getWorld().getBlockAt(bx, by, bz);
                if (b.getType() == Material.BARRIER) b.setType(Material.AIR, false);
            }
            if (keep.isEmpty()) pdc.remove(deckKey);
            else pdc.set(deckKey, org.bukkit.persistence.PersistentDataType.LONG_ARRAY, keep.stream().mapToLong(Long::longValue).toArray());
        } catch (RuntimeException ignored) { }
    }

    /** Ship entities never save; anything left over (a crash) is removed and the ship respawns itself. */
    void cleanup(Entity[] list) {
        for (Entity e : list) {
            if (!e.getScoreboardTags().contains(Ship.TAG)) continue;
            boolean live = false;
            for (Ship s : ships.values()) {
                if (e.equals(s.root) || e.equals(s.label) || e.equals(s.stand) || s.hitboxes.contains(e)) { live = true; break; }
                if (s.displays != null) for (Entity d : s.displays) if (e.equals(d)) { live = true; break; }
                if (s.seats != null) for (Entity d : s.seats) if (e.equals(d)) { live = true; break; }
                if (live) break;
            }
            if (!live) e.remove();
        }
    }

    // =====================================================================================================
    //  ticking
    // =====================================================================================================
    void tick() {
        now++;
        if (now % 20 == 0) {
            List<Ship> gone = new ArrayList<>();
            for (Ship s : ships.values()) {
                World w = s.world();
                boolean want = false;
                if (w != null && w.isChunkLoaded((int) Math.floor(s.x) >> 4, (int) Math.floor(s.z) >> 4)) {
                    double range = cfg("spawn-range", 128) + (s.spawned() ? 32 : 0); // a margin so it doesn't flicker at the edge
                    for (Player p : w.getPlayers()) {
                        Location l = p.getLocation();
                        double dx = l.getX() - s.x, dz = l.getZ() - s.z;
                        if (dx * dx + dz * dz < range * range) { want = true; break; }
                    }
                }
                if (s.ai != null && !want && (s.ai.inv == null || s.ai.beaten || s.ai.inv.over || s.wrecked)) { gone.add(s); continue; } // nobody left to see it: it's gone
                if (want && !s.spawned()) s.spawn();
                else if (!want && s.spawned()) s.despawn();
                else if (s.spawned() && s.displays != null && !s.spawningBlocks()) { // a piece got removed somehow: rebuild
                    for (Entity d : s.displays) if (d == null || !d.isValid()) { s.spawn(); break; }
                }
            }
            for (Ship s : gone) pirates.discard(s);
        }
        // ships' blocks go in a batch a tick, nearest ship first, so a whole fleet turning up doesn't freeze anyone
        int budget = (int) cfg("spawn-blocks-per-tick", 120);
        if (budget > 0) {
            List<Ship> filling = new ArrayList<>();
            for (Ship s : ships.values()) if (s.spawningBlocks()) filling.add(s);
            if (filling.size() > 1) filling.sort(java.util.Comparator.comparingDouble(Ship::nearestPlayerSq));
            for (Ship s : filling) { if (budget <= 0) break; budget -= s.spawnMore(budget); }
        }
        pirates.tick();
        for (Ship s : ships.values()) s.tick();
        if (now % 20 == 5) bedrockUpkeep();
        if (now % 10 == 3) ghostParticles();
        if (now % 2 == 0) arrows();
        if (now % 10 == 0) previews();
        tickRepairs();
    }

    /** Bedrock players: see every stand-in in their world, and get the built blocks of ships under construction. */
    void bedrockUpkeep() {
        for (Ship s : ships.values()) {
            if (!s.spawned()) continue;
            if (s.stand != null && s.stand.isValid())
                for (Player p : s.world().getPlayers()) {
                    if (bedrock(p)) { if (!p.canSee(s.stand)) p.showEntity(this, s.stand); }
                    else if (p.canSee(s.stand)) p.hideEntity(this, s.stand);
                }
            if (s.flagStand != null && s.flagStand.isValid())
                for (Player p : s.world().getPlayers()) if (bedrock(p) && !p.canSee(s.flagStand)) p.showEntity(this, s.flagStand);
            if (s.building && now % 40 == 5) showAllFakes(s);
        }
        ArmorStand cap = pirates == null || pirates.ghost == null ? null : pirates.ghost.capStand;
        if (cap != null && cap.isValid())
            for (Player p : cap.getWorld().getPlayers()) if (bedrock(p) && !p.canSee(cap)) p.showEntity(this, cap);
    }

    /** Bedrock players can't see the glowing outline either: the empty cells near them sparkle instead. */
    void ghostParticles() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!bedrock(p)) continue;
            for (Ship s : ships.values()) {
                if (!s.building || !s.spawned() || !p.getWorld().getName().equals(s.world) || p.getLocation().distance(s.center()) > s.radius() + 16) continue;
                int shown = 0;
                for (int i = 0; i < s.blocks.length && shown < 80; i++) {
                    if (s.blocks[i] != null) continue;
                    Location c = s.blockOf(i).getLocation().add(0.5, 0.5, 0.5);
                    if (c.distanceSquared(p.getLocation()) > 14 * 14) continue;
                    p.spawnParticle(Particle.DUST, c, 1, 0.15, 0.15, 0.15, 0, new Particle.DustOptions(Color.fromRGB(150, 220, 255), 1.2f));
                    shown++;
                }
            }
        }
    }

    /** Arrows and tridents fly through display blocks: check them against sailing ships ourselves. */
    void arrows() {
        for (Ship s : ships.values()) {
            if (!s.spawned() || s.building) continue;
            double r = s.radius();
            for (Entity e : s.root.getNearbyEntities(r, s.type.height + 2, r)) {
                if (isBallEntity(e)) {
                    if (ballShip(e) == s) continue;
                    Location l = e.getLocation();
                    if (s.cellAt(l.getX(), l.getY(), l.getZ()) >= 0) impact(e, l, null);
                    continue;
                }
                if (!(e instanceof Projectile pr) || arrowsCounted.contains(e.getUniqueId())) continue;
                if (s.anchored || s.wrecked) continue; // anchored: arrows land in the barriers (onProjectileHit)
                if (e instanceof AbstractArrow ar && ar.isInBlock()) continue;
                Location l = e.getLocation();
                if (s.cellAt(l.getX(), l.getY(), l.getZ()) < 0) continue;
                if (pr.getShooter() instanceof Player shooter && mayCaptain(shooter, s)) continue;
                if (s.ai != null && pr.getShooter() instanceof Entity se && pirates.isPirate(se)) continue; // their own arrows
                arrowsCounted.add(e.getUniqueId());
                double dmg = e instanceof Trident ? 6 : e instanceof AbstractArrow ar ? Math.max(1, ar.getDamage()) * (ar.getFireTicks() > 0 ? 1.5 : 1) : 2;
                s.damage(dmg * cfg("damage.projectile-multiplier", 1.0), pr.getShooter() instanceof Player sp ? sp : null, l);
                if (!(e instanceof Trident)) e.remove();
                else e.setVelocity(new Vector(0, -0.2, 0));
            }
        }
        if (arrowsCounted.size() > 500) arrowsCounted.clear();
    }

    /** Holding a blueprint shows where the ship would go: white fits, red is in the way. */
    void previews() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            ShipType t = blueprintOf(p.getInventory().getItemInMainHand());
            if (t == null) continue;
            Ship s = plan(p, t);
            if (s == null) continue;
            Set<Block> bad = new HashSet<>();
            s.clear(s.x, s.z, s.yaw, true, bad);
            for (int i : t.probes) {
                ShipType.Cell c = t.cells.get(i);
                if (c.y() != t.deckY) continue;
                Block b = s.blockOf(i);
                p.spawnParticle(Particle.DUST, b.getLocation().add(0.5, 1.05, 0.5), 1, 0, 0, 0, 0,
                        new Particle.DustOptions(bad.isEmpty() ? Color.WHITE : Color.fromRGB(255, 170, 60), 1f));
            }
            int shown = 0;
            for (Block b : bad) {
                if (shown++ > 40) break;
                p.spawnParticle(Particle.DUST, b.getLocation().add(0.5, 0.5, 0.5), 1, 0.15, 0.15, 0.15, 0, new Particle.DustOptions(Color.RED, 1.4f));
            }
        }
    }

    // =====================================================================================================
    //  the hammer: a timing minigame
    // =====================================================================================================
    static final int BAR = 29;

    static final class Repair {
        Ship ship;
        double cursor;
        int dir = 1;
        double speed = 0.45;
        int zone, zoneLen = 8, combo;
        long lastClick, lockedUntil, lastActive;
        final double[] hist = new double[32]; // where the marker was each tick: a click is judged by what you saw
    }

    boolean repairing(Player p) { return repairs.containsKey(p.getUniqueId()); }

    void hammer(Player p, Ship s) {
        Repair r = repairs.get(p.getUniqueId());
        boolean held = r != null && now - r.lastClick <= 5; // holding the button down isn't a new swing
        if (r != null) r.lastClick = now;
        if (r != null && r.ship == s || r != null && s == null) {
            if (!held) strike(p, r);
            return;
        }
        if (s == null) return;
        if (s.building) { p.sendActionBar(Component.text("Finish building it first.", NamedTextColor.YELLOW)); return; }
        if (s.ai != null) { p.sendActionBar(Component.text("That's a skeleton ship: sink it, don't fix it.", NamedTextColor.RED)); return; }
        if (!s.wrecked && s.hp >= s.maxHp() && s.broken.isEmpty()) { p.sendActionBar(Component.text("The hull is in perfect shape.", NamedTextColor.GREEN)); return; }
        Repair n = new Repair();
        n.ship = s;
        n.lastClick = now;
        n.lastActive = now;
        n.lockedUntil = now + 3;
        java.util.Arrays.fill(n.hist, 0);
        newZone(n);
        repairs.put(p.getUniqueId(), n);
        p.playSound(p.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, SoundCategory.PLAYERS, 1f, 1f);
        p.sendMessage(ChatColor.GOLD + "Patching the " + s.title() + ". " + ChatColor.GRAY + "Right-click when the white marker is in the green (gold is perfect)."
                + (s.broken.isEmpty() ? "" : ChatColor.YELLOW + " " + s.broken.size() + " holes to patch: stand by them (nearest: " + s.nearestDamage(p.getLocation()) + ")."));
    }

    void newZone(Repair r) {
        r.zoneLen = Math.max(4, 8 - r.combo / 2);
        int pad = 2;
        r.zone = pad + (int) (Math.random() * (BAR - r.zoneLen - pad * 2));
        r.speed = Math.min(1.0, 0.45 + r.combo * 0.05);
    }

    /**
     * Your click reaches the server a few ticks after you saw the marker (your ping, plus the action bar's own delay),
     * so it's judged against where the marker was then, give or take a tick.
     */
    int seen(Player p, Repair r) {
        int perfect = r.zone + r.zoneLen / 2;
        int lag = (int) Math.max(1, Math.min(12, Math.round(p.getPing() / 50.0) + 1));
        int best = (int) Math.round(r.cursor);
        double bestScore = Double.MAX_VALUE;
        for (int back = lag - 1; back <= lag + 1; back++) {
            long t = now - back;
            if (t < 0) continue;
            int pos = (int) Math.round(r.hist[(int) (t % r.hist.length)]);
            boolean in = pos >= r.zone && pos < r.zone + r.zoneLen;
            double score = (in ? 0 : 100) + Math.abs(pos - perfect);
            if (score < bestScore) { bestScore = score; best = pos; }
        }
        return best;
    }

    void strike(Player p, Repair r) {
        if (now < r.lockedUntil) { p.sendActionBar(Component.text("Steady...", NamedTextColor.GRAY)); return; }
        r.lastActive = now;
        int at = seen(p, r);
        int perfect = r.zone + r.zoneLen / 2;
        Ship s = r.ship;
        Location fx = s.center();
        if (at >= r.zone && at < r.zone + r.zoneLen) {
            boolean best = at == perfect;
            if (!s.broken.isEmpty() && s.brokenNear(p.getLocation(), cfg("parts.reach", 6)).isEmpty()) { // the holes are elsewhere
                p.sendActionBar(Component.text("Nothing to patch here: the damage is at " + s.nearestDamage(p.getLocation()) + ".", NamedTextColor.YELLOW));
                r.lockedUntil = now + 10;
                return;
            }
            if (p.getGameMode() != GameMode.CREATIVE && !takePlanks(p, getConfig().getInt("repair.planks-per-hit", 1))) {
                p.sendActionBar(Component.text("You need Planks to patch the hull.", NamedTextColor.RED));
                r.lockedUntil = now + 10;
                return;
            }
            double amount = s.maxHp() * (best ? cfg("repair.perfect-percent", 12) : cfg("repair.hit-percent", 7)) / 100.0;
            boolean done = s.repair(amount, p) > 0;
            r.combo++;
            p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_PLACE, SoundCategory.PLAYERS, 0.6f, best ? 1.6f : 1.2f);
            if (best) p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.8f, 1.4f);
            p.getWorld().spawnParticle(Particle.BLOCK, fx, 18, s.type.halfWidth, 1, 2, 0, Material.OAK_PLANKS.createBlockData());
            if (best) p.getWorld().spawnParticle(Particle.CRIT, fx.clone().add(0, 1, 0), 20, 1, 0.5, 1, 0.2);
            p.sendTitle("", best ? ChatColor.GOLD + "" + ChatColor.BOLD + "PERFECT!" : ChatColor.GREEN + "Good hit", 0, 12, 4);
            if (done) {
                repairs.remove(p.getUniqueId());
                p.sendTitle(ChatColor.GREEN + "Repaired!", ChatColor.GRAY + "The " + s.title() + " is seaworthy.", 5, 40, 10);
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.8f, 1.2f);
                return;
            }
            newZone(r);
            r.lockedUntil = now + 2;
        } else {
            r.combo = 0;
            newZone(r);
            r.lockedUntil = now + 6;
            p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_HIT, SoundCategory.PLAYERS, 1f, 0.5f);
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, SoundCategory.PLAYERS, 0.5f, 0.8f);
            p.sendTitle("", ChatColor.RED + "Missed!", 0, 12, 4);
        }
    }

    static boolean takePlanks(Player p, int n) {
        if (n <= 0) return true;
        int have = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (it != null && it.getType().name().endsWith("_PLANKS")) have += it.getAmount();
        if (have < n) return false;
        int left = n;
        ItemStack[] inv = p.getInventory().getStorageContents();
        for (int i = 0; i < inv.length && left > 0; i++) {
            ItemStack it = inv[i];
            if (it == null || !it.getType().name().endsWith("_PLANKS")) continue;
            int t = Math.min(left, it.getAmount());
            it.setAmount(it.getAmount() - t);
            left -= t;
        }
        p.getInventory().setStorageContents(inv);
        return true;
    }

    void tickRepairs() {
        for (Iterator<Map.Entry<UUID, Repair>> it = repairs.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            Repair r = e.getValue();
            Ship s = r.ship;
            if (p == null || !ships.containsKey(s.id) || !isHammer(p.getInventory().getItemInMainHand())
                    || !p.getWorld().getName().equals(s.world) || p.getLocation().distance(s.center()) > s.radius() + 8
                    || now - r.lastActive > 20 * 12) {
                if (p != null) p.sendActionBar(Component.text("You stop patching the hull.", NamedTextColor.GRAY));
                it.remove();
                continue;
            }
            r.cursor += r.dir * r.speed;
            if (r.cursor >= BAR - 1) { r.cursor = BAR - 1; r.dir = -1; }
            if (r.cursor <= 0) { r.cursor = 0; r.dir = 1; }
            r.hist[(int) (now % r.hist.length)] = r.cursor;
            p.sendActionBar(bar(r));
        }
    }

    Component bar(Repair r) {
        int at = (int) Math.round(r.cursor), perfect = r.zone + r.zoneLen / 2;
        Component out = Component.text("🔨 ", NamedTextColor.GOLD);
        StringBuilder run = new StringBuilder();
        TextColor runColor = null;
        for (int i = 0; i < BAR; i++) {
            TextColor c = i == at ? NamedTextColor.WHITE : i == perfect ? NamedTextColor.GOLD
                    : (i >= r.zone && i < r.zone + r.zoneLen) ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY;
            if (runColor != null && !c.equals(runColor)) { out = out.append(Component.text(run.toString(), runColor)); run.setLength(0); }
            runColor = c;
            run.append('█');
        }
        out = out.append(Component.text(run.toString(), runColor));
        Ship s = r.ship;
        return out.append(Component.text("  " + (int) Math.round(100 * s.hp / s.maxHp()) + "%", NamedTextColor.WHITE))
                .append(Component.text(r.combo > 1 ? "  x" + r.combo : "", NamedTextColor.YELLOW));
    }

    // =====================================================================================================
    //  saving
    // =====================================================================================================
    void saveNow() {
        dirty = false;
        YamlConfiguration y = new YamlConfiguration();
        for (Ship s : ships.values()) {
            if (s.ai != null) continue; // skeleton ships are never saved
            String k = "ships." + s.id;
            y.set(k + ".type", s.type.name());
            y.set(k + ".owner", s.owner.toString());
            y.set(k + ".world", s.world);
            y.set(k + ".x", s.x); y.set(k + ".y", s.y); y.set(k + ".z", s.z); y.set(k + ".yaw", (double) s.yaw);
            y.set(k + ".hp", s.hp);
            y.set(k + ".building", s.building);
            if (s.prebuilt) y.set(k + ".prebuilt", true);
            y.set(k + ".anchored", s.anchored || s.anchorStep >= 0 && !s.barriers.isEmpty());
            y.set(k + ".wrecked", s.wrecked);
            // blocks by position, so a ship keeps its blocks when a plugin update changes the layouts
            y.set(k + ".format", 2);
            y.set(k + ".layout", 3);
            List<String> bl = new ArrayList<>();
            for (int i = 0; i < s.blocks.length; i++) {
                if (s.blocks[i] == null) continue;
                ShipType.Cell c = s.type.cells.get(i);
                bl.add(c.x() + "," + c.y() + "," + c.z() + "|" + s.blocks[i]);
            }
            y.set(k + ".blocks", bl);
            if (s.name != null) y.set(k + ".name", s.name);
            if (!s.broken.isEmpty()) {
                List<String> br = new ArrayList<>();
                for (int i : s.broken) { ShipType.Cell bc = s.type.cells.get(i); br.add(bc.x() + "," + bc.y() + "," + bc.z()); }
                y.set(k + ".broken", br);
            }
            if (s.sink > 0) y.set(k + ".sink", s.sink);
            if (s.banner != null) y.set(k + ".banner", Base64.getEncoder().encodeToString(s.banner.serializeAsBytes()));
            y.set(k + ".barriers", new ArrayList<>(s.barriers));
            ItemStack[] cargo = s.cargoNow();
            if (cargo != null) {
                List<String> cl = new ArrayList<>();
                for (ItemStack it : cargo) cl.add(it == null || it.getType().isAir() ? "" : Base64.getEncoder().encodeToString(it.serializeAsBytes()));
                y.set(k + ".cargo", cl);
            }
        }
        for (var e : crews.entrySet()) {
            List<String> l = new ArrayList<>();
            for (UUID u : e.getValue()) l.add(u.toString());
            y.set("crews." + e.getKey(), l);
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Couldn't save ships.yml: " + ex.getMessage());
        }
    }

    void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = y.getConfigurationSection("ships");
        if (sec != null) for (String id : sec.getKeys(false)) {
            try {
                ConfigurationSection c = sec.getConfigurationSection(id);
                ShipType t = ShipType.valueOf(c.getString("type"));
                Ship s = new Ship(this, UUID.fromString(id), t, UUID.fromString(c.getString("owner")));
                s.world = c.getString("world");
                s.x = c.getDouble("x"); s.y = c.getDouble("y"); s.z = c.getDouble("z"); s.yaw = (float) c.getDouble("yaw");
                s.hp = c.getDouble("hp", t.maxHp);
                s.building = c.getBoolean("building");
                s.prebuilt = c.getBoolean("prebuilt");
                s.anchored = c.getBoolean("anchored");
                s.wrecked = c.getBoolean("wrecked");
                List<String> bl = c.getStringList("blocks");
                Map<String, String> byPos = new HashMap<>();
                if (c.getInt("format", 1) >= 2) {
                    for (String e : bl) { int bar = e.indexOf('|'); byPos.put(e.substring(0, bar), e.substring(bar + 1)); }
                } else { // format 1 (FaultlineShips 1.0/1.1): blocks by index into the old layouts
                    List<int[]> old = oldLayout(t);
                    for (int i = 0; i < bl.size() && i < old.size(); i++) {
                        if (bl.get(i).isEmpty()) continue;
                        int[] o = old.get(i);
                        byPos.put(o[0] + "," + o[1] + "," + o[2], bl.get(i));
                    }
                }
                if (t == ShipType.GALLEON && c.getInt("layout", 2) < 3) { // 1.3 raised the Galleon's deck one block (a hold below)
                    Map<String, String> up = new HashMap<>();
                    for (var e : byPos.entrySet()) {
                        String[] q = e.getKey().split(",");
                        int yy = Integer.parseInt(q[1]);
                        up.put(q[0] + "," + (yy >= 1 ? yy + 1 : yy) + "," + q[2], e.getValue());
                    }
                    byPos = up;
                }
                int carried = 0;
                for (int i = 0; i < s.blocks.length; i++) {
                    ShipType.Cell cell = t.cells.get(i);
                    String v = byPos.get(cell.x() + "," + cell.y() + "," + cell.z());
                    if (v == null || (cell.need() == ShipType.Need.CANNON) != v.equals(Ship.CANNON_DATA)) continue;
                    if (cell.need() != ShipType.Need.CANNON) { // the block still has to be the right kind for that spot
                        try { if (!cell.need().accepts(Bukkit.createBlockData(v).getMaterial())) continue; } catch (IllegalArgumentException ex) { continue; }
                    }
                    s.blocks[i] = v; carried++;
                }
                // a finished ship gets the parts a layout update added (ladders, cannons, crow's nest...) for free
                if (!s.building) for (int i = 0; i < s.blocks.length; i++) {
                    if (s.blocks[i] == null) s.blocks[i] = s.blockFor(i, t.cells.get(i).need().ghost);
                }
                s.recount();
                if (carried < byPos.size()) getLogger().info("Ship " + id + ": " + (byPos.size() - carried) + " blocks aren't part of the " + t.title + " any more.");
                s.name = c.getString("name");
                for (String bp : c.getStringList("broken")) {
                    String[] q = bp.split(",");
                    int bi = t.cellAt(Integer.parseInt(q[0]), Integer.parseInt(q[1]), Integer.parseInt(q[2]));
                    if (bi >= 0 && s.blocks[bi] != null) s.broken.add(bi);
                }
                s.sink = c.getDouble("sink", 0);
                if (c.isString("banner")) s.banner = ItemStack.deserializeBytes(Base64.getDecoder().decode(c.getString("banner")));
                s.barriers.addAll(c.getStringList("barriers"));
                if (c.isList("cargo")) {
                    List<String> cl = c.getStringList("cargo");
                    s.cargo = new ItemStack[Math.max(t.cargo, cl.size())];
                    for (int i = 0; i < cl.size(); i++) if (!cl.get(i).isEmpty()) s.cargo[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(cl.get(i)));
                }
                // barriers left behind by a ship that isn't anchored (a crash mid-raise): put the water back
                if (!s.anchored && !s.barriers.isEmpty() && s.world() != null) s.clearBarriers();
                ships.put(s.id, s);
            } catch (RuntimeException ex) {
                getLogger().warning("Skipped broken ship " + id + ": " + ex);
            }
        }
        ConfigurationSection cr = y.getConfigurationSection("crews");
        if (cr != null) for (String o : cr.getKeys(false)) {
            Set<UUID> set = new HashSet<>();
            for (String u : cr.getStringList(o)) set.add(UUID.fromString(u));
            crews.put(UUID.fromString(o), set);
        }
    }

    /** The 1.0/1.1 layouts (cell positions in order), to read ships saved before positions were saved. */
    List<int[]> oldLayout(ShipType t) {
        List<int[]> out = new ArrayList<>();
        try (var in = getResource("layouts_v1.json")) {
            if (in == null) return out;
            var root = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            var cells = root.getAsJsonObject(t.name().toLowerCase()).getAsJsonArray("cells");
            for (var e : cells) {
                var a = e.getAsJsonArray();
                out.add(new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
            }
        } catch (IOException | RuntimeException ex) {
            getLogger().warning("Couldn't read the old ship layouts: " + ex);
        }
        return out;
    }

    /** A ship by name, by number (from /ship list), or the one you're on or next to. */
    /** Players' ships in a fixed order: what /ship list all numbers (skeleton ships come and go, so they're left out). */
    List<Ship> numbered() {
        List<Ship> out = new ArrayList<>();
        for (Ship s : ships.values()) if (s.ai == null) out.add(s);
        return out;
    }

    Ship find(CommandSender sender, String arg) {
        if (arg == null || arg.isEmpty()) {
            if (!(sender instanceof Player p)) return null;
            Ship s = ridingOn(p);
            return s != null ? s : nearest(p, 6);
        }
        String a = arg.startsWith("#") ? arg.substring(1) : arg;
        try {
            int n = Integer.parseInt(a);
            List<Ship> all = numbered();
            return n >= 1 && n <= all.size() ? all.get(n - 1) : null;
        } catch (NumberFormatException ignored) { }
        for (Ship s : ships.values()) if (s.name != null && ChatColor.stripColor(s.name).equalsIgnoreCase(arg)) return s;
        return null;
    }

    static String join(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) sb.append(i > from ? " " : "").append(args[i]);
        return sb.toString();
    }

    // =====================================================================================================
    //  /ship
    // =====================================================================================================
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "help";
        boolean admin = sender.hasPermission("faultlineships.admin");
        Player p = sender instanceof Player pp ? pp : null;
        switch (sub) {
            case "pirates" -> {
                if (!admin) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                return pirates.command(sender, args);
            }
            case "give" -> {
                if (!admin) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "/ship give <dinghy|sloop|brigantine|galleon|pirate|built_<ship>|hammer|cannon|cannonball> [amount] [player]"); return true; }
                ItemStack it;
                if (args[1].equalsIgnoreCase("hammer")) it = hammer();
                else if (args[1].equalsIgnoreCase("cannon")) it = cannon();
                else if (args[1].equalsIgnoreCase("cannonball") || args[1].equalsIgnoreCase("ball")) it = ball();
                else if (args[1].toLowerCase().startsWith("built_")) {
                    ShipType t = ShipType.of(args[1].substring(6));
                    if (t == null) { sender.sendMessage(ChatColor.RED + "Unknown ship: " + args[1].substring(6)); return true; }
                    it = builtShip(t);
                }
                else {
                    ShipType t = ShipType.of(args[1]);
                    if (t == null) { sender.sendMessage(ChatColor.RED + "Unknown ship: " + args[1]); return true; }
                    it = blueprint(t);
                }
                int amount = 1;
                if (args.length > 2) try { amount = Math.max(1, Math.min(64, Integer.parseInt(args[2]))); } catch (NumberFormatException ignored) { }
                Player to = args.length > 3 ? Bukkit.getPlayerExact(args[3]) : p;
                if (to == null) { sender.sendMessage(ChatColor.RED + "Player not found."); return true; }
                for (int i = 0; i < amount; i++) {
                    for (ItemStack left : to.getInventory().addItem(it.clone()).values()) to.getWorld().dropItem(to.getLocation(), left);
                }
                sender.sendMessage(ChatColor.GREEN + "Gave " + amount + " " + it.getItemMeta().getDisplayName() + ChatColor.GREEN + " to " + to.getName() + ".");
            }
            case "spawn" -> { // /ship spawn <type> [owner]: a finished ship where you're looking
                if (!admin) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (p == null) { sender.sendMessage("Players only (it goes where you look). From the console: /ship give built_<type> 1 <player>"); return true; }
                ShipType t = args.length > 1 ? ShipType.of(args[1]) : null;
                if (t == null) { sender.sendMessage(ChatColor.RED + "/ship spawn <dinghy|sloop|brigantine|galleon|pirate> [owner]"); return true; }
                UUID owner = p.getUniqueId();
                if (args.length > 2) {
                    Player o = Bukkit.getPlayerExact(args[2]);
                    OfflinePlayer off = o != null ? o : Bukkit.getOfflinePlayerIfCached(args[2]);
                    if (off == null) { sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]); return true; }
                    owner = off.getUniqueId();
                }
                Ship s = layOut(p, null, t, true, owner);
                if (s != null) sender.sendMessage(ChatColor.GREEN + "A finished " + t.title + " is on the water" + (args.length > 2 ? " for " + args[2] : "") + ".");
            }
            case "list" -> {
                boolean all = admin && args.length > 1 && args[1].equalsIgnoreCase("all");
                if (p == null && !all) { sender.sendMessage("Use /ship list all from the console."); return true; }
                int n = 0, num = 0;
                for (Ship s : numbered()) {
                    num++;
                    if (!all && !s.owner.equals(p.getUniqueId())) continue;
                    n++;
                    String state = s.building ? "being built (" + s.filled + "/" + s.blocks.length + ")" : s.wrecked ? ChatColor.RED + "wrecked" : s.anchored ? "anchored" : "afloat";
                    String own = all ? ChatColor.GRAY + " (" + Bukkit.getOfflinePlayer(s.owner).getName() + ")" : "";
                    sender.sendMessage(ChatColor.DARK_GRAY + "#" + num + " " + ChatColor.AQUA + s.title() + (s.name != null ? ChatColor.GRAY + " (" + s.type.title + ")" : "") + own
                            + ChatColor.GRAY + " at " + s.world + " " + (int) s.x + ", " + (int) s.y + ", " + (int) s.z
                            + ": " + state + ChatColor.GRAY + ", hull " + (int) Math.round(100 * s.hp / s.maxHp()) + "%");
                }
                if (n == 0) sender.sendMessage(ChatColor.GRAY + (all ? "There are no ships." : "You don't have any ships. Craft a blueprint: paper around a boat."));
                else if (admin) sender.sendMessage(ChatColor.DARK_GRAY + "Pick one with its name or #number: /ship wreck <name|#n>, /ship repair, /ship remove, /ship tp");
            }
            case "name", "rename" -> {
                if (p == null) { sender.sendMessage("Players only."); return true; }
                Ship s = ridingOn(p);
                if (s == null) s = nearest(p, 6);
                if (s == null) { sender.sendMessage(ChatColor.RED + "Stand on or next to your ship."); return true; }
                if (!s.owner.equals(p.getUniqueId()) && !admin) { sender.sendMessage(ChatColor.RED + "That's not your ship."); return true; }
                String nm = ChatColor.stripColor(join(args, 1)).trim();
                if (nm.isEmpty()) { s.name = null; sender.sendMessage(ChatColor.YELLOW + "Your " + s.type.title + " has no name now."); }
                else if (nm.length() > 24) { sender.sendMessage(ChatColor.RED + "Names can be 24 letters at most."); return true; }
                else {
                    for (Ship o : ships.values()) if (o != s && o.name != null && o.name.equalsIgnoreCase(nm)) { sender.sendMessage(ChatColor.RED + "There's already a ship called " + nm + "."); return true; }
                    s.name = nm;
                    sender.sendMessage(ChatColor.AQUA + "Your " + s.type.title + " is now the " + ChatColor.BOLD + nm + ChatColor.AQUA + ".");
                }
                s.refreshLabel();
                dirty = true;
            }
            case "banner" -> {
                if (p == null) { sender.sendMessage("Players only."); return true; }
                Ship s = ridingOn(p);
                if (s == null) s = nearest(p, 6);
                if (s == null) { sender.sendMessage(ChatColor.RED + "Stand on or next to your ship."); return true; }
                if (!mayCaptain(p, s)) { sender.sendMessage(ChatColor.RED + "Only the owner and their crew can do that."); return true; }
                if (s.banner == null) { sender.sendMessage(ChatColor.GRAY + "No banner flies on it. Right-click the ship with any banner to hang one."); return true; }
                ItemStack old = s.setBanner(null);
                for (ItemStack left : p.getInventory().addItem(old).values()) p.getWorld().dropItem(p.getLocation(), left);
                sender.sendMessage(ChatColor.YELLOW + "You took the banner down.");
            }
            case "crew" -> {
                if (p == null) { sender.sendMessage("Players only."); return true; }
                Set<UUID> crew = crews.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
                if (args.length < 2 || args[1].equalsIgnoreCase("list")) {
                    List<String> names = new ArrayList<>();
                    for (UUID u : crew) { String nm = Bukkit.getOfflinePlayer(u).getName(); names.add(nm == null ? u.toString() : nm); }
                    sender.sendMessage(ChatColor.AQUA + "Your crew (can steer your ships and open their holds): " + ChatColor.WHITE + (names.isEmpty() ? "nobody yet" : String.join(", ", names)));
                    return true;
                }
                if (args.length < 3) { sender.sendMessage(ChatColor.RED + "/ship crew <add|remove> <player>"); return true; }
                var target = Bukkit.getOfflinePlayer(args[2]);
                if (args[1].equalsIgnoreCase("add")) { crew.add(target.getUniqueId()); sender.sendMessage(ChatColor.GREEN + args[2] + " is now in your crew."); }
                else if (args[1].equalsIgnoreCase("remove")) { crew.remove(target.getUniqueId()); sender.sendMessage(ChatColor.YELLOW + args[2] + " is off your crew."); }
                dirty = true;
            }
            case "scrap" -> {
                if (p == null) { sender.sendMessage("Players only."); return true; }
                Ship s = nearest(p, 4);
                if (s == null) { sender.sendMessage(ChatColor.RED + "Stand on or next to the ship you want to scrap."); return true; }
                if (!s.owner.equals(p.getUniqueId()) && !admin) { sender.sendMessage(ChatColor.RED + "That's not your ship."); return true; }
                if (!s.anchored && !s.building) { sender.sendMessage(ChatColor.RED + "Drop the anchor first."); return true; }
                scrap(s, p.getLocation(), true);
                sender.sendMessage(ChatColor.YELLOW + "You took the " + s.title() + " apart: the blueprint, its blocks and its cargo are by you.");
            }
            case "anchor" -> {
                if (p == null) { sender.sendMessage("Players only."); return true; }
                Ship s = ridingOn(p);
                if (s == null || s.seatOf(p) != 0) { sender.sendMessage(ChatColor.RED + "Take the helm first."); return true; }
                if (s.anchored) { if (s.wrecked) sender.sendMessage(ChatColor.RED + "The ship is wrecked: repair it first."); else s.raiseAnchor(p); }
                else if (s.anchorStep < 0) {
                    double max = s.type.speed * getConfig().getDouble("ships." + s.type.size + ".speed-multiplier", 1.0);
                    if (Math.abs(s.speed) > max * 0.3) { s.anchorQueued = true; sender.sendMessage(ChatColor.YELLOW + "Braking to drop anchor..."); }
                    else s.dropAnchor(p);
                }
            }
            case "stop" -> {
                if (p == null) { sender.sendMessage("Players only."); return true; }
                Ship s = ridingOn(p);
                if (s == null || s.seatOf(p) != 0) { sender.sendMessage(ChatColor.RED + "Take the helm first."); return true; }
                s.allStop = true;
                sender.sendMessage(ChatColor.YELLOW + "All stop.");
            }
            case "info" -> {
                Ship s = find(sender, args.length > 1 ? join(args, 1) : null);
                if (s == null) { sender.sendMessage(ChatColor.GRAY + "No ship found."); return true; }
                String own = Bukkit.getOfflinePlayer(s.owner).getName();
                sender.sendMessage(ChatColor.AQUA + s.title() + (s.name != null ? ChatColor.GRAY + " (" + s.type.title + ")" : "") + ChatColor.GRAY + " owned by " + own
                        + ", hull " + (int) Math.ceil(s.hp) + "/" + (int) s.maxHp() + ", " + s.cannons().size() + " cannons"
                        + (s.anchored ? ", anchored (" + s.barriers.size() + " solid blocks)" : ", afloat")
                        + (s.wrecked ? ChatColor.RED + " (wrecked)" : "") + (s.building ? ChatColor.YELLOW + " (being built " + s.filled + "/" + s.blocks.length + ")" : ""));
            }
            case "repair", "remove", "wreck", "tp" -> {
                if (!admin) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                Ship s = find(sender, args.length > 1 ? join(args, 1) : null);
                if (s == null) { sender.sendMessage(ChatColor.RED + (args.length > 1 ? "No ship called " + join(args, 1) + " (see /ship list all)." : "No ship nearby: name one, or its #number from /ship list all.")); return true; }
                switch (sub) {
                    case "repair" -> {
                        if (s.building) { for (int i = 0; i < s.blocks.length; i++) if (s.blocks[i] == null) s.fill(i, s.type.cells.get(i).need().ghost); }
                        else s.repair(s.maxHp());
                        sender.sendMessage(ChatColor.GREEN + "Fully repaired the " + s.title() + ".");
                    }
                    case "wreck" -> {
                        if (s.building) { sender.sendMessage(ChatColor.RED + "It's still being built."); return true; }
                        if (s.world() == null) { sender.sendMessage(ChatColor.RED + "Its world isn't loaded."); return true; }
                        s.damage(s.hp + 1, null);
                        sender.sendMessage(ChatColor.YELLOW + "Wrecked the " + s.title() + ".");
                    }
                    case "tp" -> {
                        if (p == null) { sender.sendMessage("Players only."); return true; }
                        if (s.world() == null) { sender.sendMessage(ChatColor.RED + "Its world isn't loaded."); return true; }
                        p.teleport(new Location(s.world(), s.x, s.y + s.type.deckY + 1.1, s.z));
                    }
                    default -> {
                        scrap(s, p != null ? p.getLocation() : new Location(s.world(), s.x, s.y + 2, s.z), false);
                        sender.sendMessage(ChatColor.YELLOW + "Removed the " + s.title() + ".");
                    }
                }
            }
            default -> {
                sender.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Faultline Ships");
                sender.sendMessage(ChatColor.GRAY + "Craft a blueprint (Sloop: paper around a boat; Brigantine: gold + paper around a Sloop Blueprint;"
                        + " Galleon: diamonds + paper around a Brigantine Blueprint), right-click open water, and place every glowing block.");
                sender.sendMessage(ChatColor.GRAY + "Right-click the wheel to steer: W sail, S brake (then reverse), A/D steer, Sprint full sail, Jump drops anchor (then the deck can be walked, and climbed with the ladders).");
                sender.sendMessage(ChatColor.GRAY + "Cannons: right-click with a Cannonball while on board, looking over the side. Banners: right-click the ship with any banner.");
                sender.sendMessage(ChatColor.GRAY + "A wrecked ship needs a Shipwright's Hammer (3 iron, 1 planks, 2 iron, 1 stick).");
                sender.sendMessage(ChatColor.YELLOW + "/ship list" + ChatColor.GRAY + ", " + ChatColor.YELLOW + "/ship info" + ChatColor.GRAY + ", " + ChatColor.YELLOW + "/ship name <name>"
                        + ChatColor.GRAY + ", " + ChatColor.YELLOW + "/ship crew <add|remove|list> [player]" + ChatColor.GRAY + ", " + ChatColor.YELLOW + "/ship anchor" + ChatColor.GRAY + ", "
                        + ChatColor.YELLOW + "/ship stop" + ChatColor.GRAY + ", " + ChatColor.YELLOW + "/ship banner" + ChatColor.GRAY + ", " + ChatColor.YELLOW + "/ship scrap");
                if (admin) sender.sendMessage(ChatColor.DARK_GRAY + "Admin: /ship give <dinghy|sloop|brigantine|galleon|pirate|built_<ship>|hammer|cannon|cannonball> [amount] [player], /ship spawn <ship> [owner] (already built), /ship list all, /ship <wreck|repair|remove|tp> [name|#n]");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> opts = new ArrayList<>();
        if (args.length == 1) {
            opts.addAll(List.of("list", "info", "name", "crew", "scrap", "anchor", "stop", "banner"));
            if (sender.hasPermission("faultlineships.admin")) opts.addAll(List.of("give", "spawn", "repair", "wreck", "remove", "tp", "pirates"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            opts.addAll(List.of("dinghy", "sloop", "brigantine", "galleon", "pirate", "hammer", "cannon", "cannonball"));
            for (ShipType t : ShipType.values()) opts.add("built_" + t.name().toLowerCase());
        }
        else if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) { for (ShipType t : ShipType.values()) opts.add(t.name().toLowerCase()); }
        else if (args.length == 3 && args[0].equalsIgnoreCase("spawn")) return null; // player names
        else if (args.length == 2 && args[0].equalsIgnoreCase("pirates")) opts.addAll(List.of("ship", "invasion", "horn", "spawn", "egg", "stop"));
        else if (args.length == 3 && args[0].equalsIgnoreCase("pirates") && (args[1].equalsIgnoreCase("spawn") || args[1].equalsIgnoreCase("egg"))) { for (Pirates.Kind k : Pirates.Kind.values()) opts.add(k.name().toLowerCase()); }
        else if (args.length == 2 && List.of("wreck", "repair", "remove", "tp", "info").contains(args[0].toLowerCase())) {
            for (Ship sh : ships.values()) if (sh.name != null) opts.add(sh.name);
            for (int i = 1; i <= numbered().size(); i++) opts.add("#" + i);
        }
        else if (args.length == 2 && args[0].equalsIgnoreCase("crew")) opts.addAll(List.of("add", "remove", "list"));
        else return null;
        String last = args[args.length - 1].toLowerCase();
        opts.removeIf(o -> !o.startsWith(last));
        return opts;
    }

    /** Take a ship off the water: barriers back to water, and (refund) the blueprint, its blocks and its cargo. */
    void scrap(Ship s, Location at, boolean refund) {
        if (s.hold != null) { s.cargo = s.hold.getContents(); for (var v : new ArrayList<>(s.hold.getViewers())) v.closeInventory(); }
        s.despawn();
        s.clearBarriers();
        if (s.building) clearFakes(s);
        ships.remove(s.id);
        repairs.values().removeIf(r -> r.ship == s);
        if (refund) {
            World w = at.getWorld();
            List<ItemStack> out = new ArrayList<>();
            out.add(s.prebuilt ? builtShip(s.type) : blueprint(s.type)); // a ready-made ship gives itself back, not its blocks
            Map<Material, Integer> count = new LinkedHashMap<>();
            if (s.banner != null) out.add(s.banner);
            if (!s.prebuilt) for (String b : s.blocks) if (b != null) {
                if (b.equals(Ship.CANNON_DATA)) { out.add(cannon()); continue; }
                try { count.merge(Bukkit.createBlockData(b).getMaterial(), 1, Integer::sum); } catch (IllegalArgumentException ignored) { }
            }
            for (var e : count.entrySet()) {
                int left = e.getValue();
                while (left > 0) { int n = Math.min(left, e.getKey().getMaxStackSize()); out.add(new ItemStack(e.getKey(), n)); left -= n; }
            }
            if (s.cargo != null) for (ItemStack it : s.cargo) if (it != null && !it.getType().isAir()) out.add(it);
            for (ItemStack it : out) w.dropItem(at, it);
        }
        saveNow();
    }
}
