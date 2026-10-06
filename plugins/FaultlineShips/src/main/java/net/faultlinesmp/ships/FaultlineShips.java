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
    final Map<UUID, Long> lastSwing = new HashMap<>();
    final Map<UUID, Long> lastUse = new HashMap<>();
    final Set<UUID> arrowsCounted = new HashSet<>();
    Function<Player, Input> inputSource = Player::getCurrentInput; // swapped out by tests
    NamespacedKey hammerKey;
    final Map<ShipType, NamespacedKey> blueprintKeys = new HashMap<>();
    File dataFile;
    boolean dirty;
    long now;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        hammerKey = new NamespacedKey(this, "shipwright_hammer");
        for (ShipType t : ShipType.values()) blueprintKeys.put(t, new NamespacedKey(this, t.name().toLowerCase() + "_blueprint"));
        dataFile = new File(getDataFolder(), "ships.yml");
        load();
        recipes();
        getServer().getPluginManager().registerEvents(this, this);
        for (World w : Bukkit.getWorlds()) for (var ch : w.getLoadedChunks()) cleanup(ch.getEntities());
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(this, () -> { if (dirty) saveNow(); }, 100L, 100L);
        getLogger().info("Faultline Ships enabled: " + ships.size() + " ships.");
    }

    @Override
    public void onDisable() {
        for (Ship s : ships.values()) {
            if (s.hold != null) s.cargo = s.hold.getContents();
            s.despawn();
        }
        saveNow();
    }

    double cfg(String key, double def) { return getConfig().getDouble(key, def); }

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
        ChatColor c = t == ShipType.SLOOP ? ChatColor.WHITE : t == ShipType.BRIGANTINE ? ChatColor.GOLD : ChatColor.AQUA;
        m.setDisplayName(c + "" + ChatColor.BOLD + t.title + " Blueprint");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "A " + t.size + " ship: " + (int) t.maxHp + " health, " + (t.seats.length) + " seats"
                + (t.cargo > 0 ? ", a " + t.cargo + "-slot hold" : "") + ".");
        lore.add(ChatColor.GREEN + "Right-click open water to lay it out,");
        lore.add(ChatColor.GREEN + "then place every glowing block.");
        lore.add(ChatColor.DARK_GRAY + "Needs " + t.cells.size() + " blocks:");
        for (var e : t.bill().entrySet()) lore.add(ChatColor.DARK_GRAY + "  " + e.getValue() + " " + e.getKey().label);
        if (t.deep) lore.add(ChatColor.DARK_GRAY + "Needs water at least 2 deep.");
        m.setLore(lore);
        m.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        if (t == ShipType.GALLEON) m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(blueprintKeys.get(t), PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
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
        Bukkit.removeRecipe(r.getKey());
        Bukkit.addRecipe(r);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        try {
            for (NamespacedKey k : blueprintKeys.values()) event.getPlayer().discoverRecipe(k);
            event.getPlayer().discoverRecipe(hammerKey);
        } catch (RuntimeException ignored) { }
    }

    /** Hammers and blueprints only go into our own recipes (a hammer is a stick underneath). */
    @EventHandler
    public void onCraft(PrepareItemCraftEvent event) {
        boolean ours = event.getRecipe() != null && event.getRecipe().getResult() != null && blueprintOf(event.getRecipe().getResult()) != null;
        for (ItemStack it : event.getInventory().getMatrix()) {
            if (isHammer(it) || (!ours && blueprintOf(it) != null)) { event.getInventory().setResult(null); return; }
        }
    }

    // =====================================================================================================
    //  laying out and building
    // =====================================================================================================
    /** Where a blueprint would go: the stern at the water block you look at, bow pointing the way you face. */
    Ship plan(Player p, ShipType t) {
        RayTraceResult hit = p.rayTraceBlocks(10, FluidCollisionMode.ALWAYS);
        if (hit == null || hit.getHitBlock() == null) return null;
        Block b = hit.getHitBlock();
        if (b.getType() != Material.WATER || !Ship.air(b.getRelative(0, 1, 0))) return null;
        Ship s = new Ship(this, UUID.randomUUID(), t, p.getUniqueId());
        s.world = b.getWorld().getName();
        s.yaw = ((Math.round(p.getLocation().getYaw() / 90f) * 90) % 360 + 360) % 360;
        double back = -t.minX + 1;
        s.x = b.getX() + 0.5 + Math.round(Ship.fx(s.yaw)) * back;
        s.z = b.getZ() + 0.5 + Math.round(Ship.fz(s.yaw)) * back;
        s.y = b.getY();
        return s;
    }

    void layOut(Player p, ItemStack item, ShipType t) {
        int mine = 0;
        for (Ship s : ships.values()) if (s.owner.equals(p.getUniqueId())) mine++;
        int max = getConfig().getInt("max-ships-per-player", 3);
        if (mine >= max && !p.hasPermission("faultlineships.admin")) {
            p.sendMessage(ChatColor.RED + "You already have " + mine + " ships (the limit is " + max + "). Scrap one with /ship scrap first.");
            return;
        }
        Ship s = plan(p, t);
        if (s == null) { p.sendActionBar(Component.text("Look at open water to lay out the " + t.title + ".", NamedTextColor.YELLOW)); return; }
        Set<Block> bad = new HashSet<>();
        if (!s.clear(s.x, s.z, s.yaw, true, bad)) {
            for (Block b : bad) p.spawnParticle(Particle.DUST, b.getLocation().add(0.5, 0.5, 0.5), 3, 0.2, 0.2, 0.2, 0, new Particle.DustOptions(Color.RED, 1.5f));
            p.sendMessage(ChatColor.RED + "Not enough open water for a " + t.title + " here (" + bad.size() + " blocks in the way, shown in red)."
                    + (t.deep ? ChatColor.GRAY + " It needs water 2 deep under the keel." : ""));
            return;
        }
        if (p.getGameMode() != GameMode.CREATIVE) item.setAmount(item.getAmount() - 1);
        s.building = true;
        s.anchored = true;
        s.hp = s.maxHp();
        ships.put(s.id, s);
        s.spawn();
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, SoundCategory.PLAYERS, 1f, 0.8f);
        StringBuilder sb = new StringBuilder();
        for (var e : t.bill().entrySet()) sb.append(sb.length() == 0 ? "" : ", ").append(e.getValue()).append(" ").append(e.getKey().label);
        p.sendMessage(ChatColor.AQUA + "You laid out a " + t.title + ". " + ChatColor.GRAY + "Place the glowing blocks: " + sb
                + ". Sneak + right-click fills up to 16 at once. Any wood type works.");
        saveNow();
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
        if (!need.accepts(m)) {
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
            if (!creative && !take(p, m)) break;
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
        ShipType bp = blueprintOf(hand);
        if (bp != null) {
            event.setCancelled(true);
            if (used(p)) return;
            layOut(p, hand, bp);
            return;
        }
        if (hand.getType().isBlock() && hand.getType() != Material.AIR) {
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
        if (p.isSneaking()) { openHold(p, s); return; }
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
        s.damage(dmg, p);
    }

    void openHold(Player p, Ship s) {
        if (s.type.cargo <= 0) { p.sendActionBar(Component.text("The " + s.type.title + " has no hold.", NamedTextColor.GRAY)); return; }
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
                        pr.getShooter() instanceof Player sp ? sp : null);
            }
        }
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
        Block b = event.getHitBlock();
        if (b == null || b.getType() != Material.BARRIER) return;
        Ship s = shipCellAt(b, null);
        Projectile pr = event.getEntity();
        if (s == null || arrowsCounted.contains(pr.getUniqueId())) return;
        if (pr.getShooter() instanceof Player sp && mayCaptain(sp, s)) return;
        arrowsCounted.add(pr.getUniqueId());
        double dmg = pr instanceof Trident ? 6 : pr instanceof AbstractArrow ar ? Math.max(1, ar.getDamage()) * (ar.getFireTicks() > 0 ? 1.5 : 1) : 2;
        s.damage(dmg * cfg("damage.projectile-multiplier", 1.0), pr.getShooter() instanceof Player p ? p : null);
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

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (event.getBlock().getType() == Material.BARRIER && shipCellAt(event.getBlock(), null) != null) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        Entity e = event.getEntity();
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
        blast(event.getBlock().getLocation().add(0.5, 0.5, 0.5), 40, event.blockList());
    }

    void blast(Location at, double base, List<Block> blocks) {
        blocks.removeIf(b -> b.getType() == Material.BARRIER && shipCellAt(b, null) != null);
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
            if (d < r) s.damage(base * (1 - d / r) * cfg("damage.explosion-multiplier", 1.0), null);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        repairs.remove(event.getPlayer().getUniqueId());
        lastSwing.remove(event.getPlayer().getUniqueId());
        lastUse.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) { cleanup(event.getChunk().getEntities()); }

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
                if (want && !s.spawned()) s.spawn();
                else if (!want && s.spawned()) s.despawn();
                else if (s.spawned() && s.displays != null) { // a piece got removed somehow: rebuild
                    for (Entity d : s.displays) if (d == null || !d.isValid()) { s.spawn(); break; }
                }
            }
        }
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
            if (s.building && now % 40 == 5) showAllFakes(s);
        }
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
            if (!s.spawned() || s.building || s.wrecked || s.anchored) continue;
            double r = s.radius();
            for (Entity e : s.root.getNearbyEntities(r, s.type.height + 2, r)) {
                if (!(e instanceof Projectile pr) || arrowsCounted.contains(e.getUniqueId())) continue;
                if (e instanceof AbstractArrow ar && ar.isInBlock()) continue;
                Location l = e.getLocation();
                if (s.cellAt(l.getX(), l.getY(), l.getZ()) < 0) continue;
                if (pr.getShooter() instanceof Player shooter && mayCaptain(shooter, s)) continue;
                arrowsCounted.add(e.getUniqueId());
                double dmg = e instanceof Trident ? 6 : e instanceof AbstractArrow ar ? Math.max(1, ar.getDamage()) * (ar.getFireTicks() > 0 ? 1.5 : 1) : 2;
                s.damage(dmg * cfg("damage.projectile-multiplier", 1.0), pr.getShooter() instanceof Player sp ? sp : null);
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
        double speed = 0.7;
        int zone, zoneLen = 7, combo;
        long lastClick, lockedUntil, lastActive;
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
        if (!s.wrecked && s.hp >= s.maxHp()) { p.sendActionBar(Component.text("The hull is in perfect shape.", NamedTextColor.GREEN)); return; }
        Repair n = new Repair();
        n.ship = s;
        n.lastClick = now;
        n.lastActive = now;
        n.lockedUntil = now + 6;
        newZone(n);
        repairs.put(p.getUniqueId(), n);
        p.playSound(p.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, SoundCategory.PLAYERS, 1f, 1f);
        p.sendMessage(ChatColor.GOLD + "Patching the " + s.type.title + ". " + ChatColor.GRAY + "Right-click when the white marker is in the green (gold is perfect).");
    }

    void newZone(Repair r) {
        r.zoneLen = Math.max(3, 7 - r.combo / 2);
        int pad = 2;
        r.zone = pad + (int) (Math.random() * (BAR - r.zoneLen - pad * 2));
        r.speed = Math.min(1.7, 0.7 + r.combo * 0.08);
    }

    void strike(Player p, Repair r) {
        if (now < r.lockedUntil) return;
        r.lastActive = now;
        int at = (int) Math.round(r.cursor);
        int perfect = r.zone + r.zoneLen / 2;
        Ship s = r.ship;
        Location fx = s.center();
        if (at >= r.zone && at < r.zone + r.zoneLen) {
            boolean best = at == perfect;
            if (p.getGameMode() != GameMode.CREATIVE && !takePlanks(p, getConfig().getInt("repair.planks-per-hit", 1))) {
                p.sendActionBar(Component.text("You need Planks to patch the hull.", NamedTextColor.RED));
                r.lockedUntil = now + 10;
                return;
            }
            double amount = s.maxHp() * (best ? cfg("repair.perfect-percent", 12) : cfg("repair.hit-percent", 7)) / 100.0;
            boolean done = s.repair(amount);
            r.combo++;
            p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_PLACE, SoundCategory.PLAYERS, 0.6f, best ? 1.6f : 1.2f);
            if (best) p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.8f, 1.4f);
            p.getWorld().spawnParticle(Particle.BLOCK, fx, 18, s.type.halfWidth, 1, 2, 0, Material.OAK_PLANKS.createBlockData());
            if (best) p.getWorld().spawnParticle(Particle.CRIT, fx.clone().add(0, 1, 0), 20, 1, 0.5, 1, 0.2);
            p.sendTitle("", best ? ChatColor.GOLD + "" + ChatColor.BOLD + "PERFECT!" : ChatColor.GREEN + "Good hit", 0, 12, 4);
            if (done) {
                repairs.remove(p.getUniqueId());
                p.sendTitle(ChatColor.GREEN + "Repaired!", ChatColor.GRAY + "The " + s.type.title + " is seaworthy.", 5, 40, 10);
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.8f, 1.2f);
                return;
            }
            newZone(r);
            r.lockedUntil = now + 4;
        } else {
            r.combo = 0;
            newZone(r);
            r.lockedUntil = now + 12;
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
            String k = "ships." + s.id;
            y.set(k + ".type", s.type.name());
            y.set(k + ".owner", s.owner.toString());
            y.set(k + ".world", s.world);
            y.set(k + ".x", s.x); y.set(k + ".y", s.y); y.set(k + ".z", s.z); y.set(k + ".yaw", (double) s.yaw);
            y.set(k + ".hp", s.hp);
            y.set(k + ".building", s.building);
            y.set(k + ".anchored", s.anchored || s.anchorStep >= 0 && !s.barriers.isEmpty());
            y.set(k + ".wrecked", s.wrecked);
            List<String> bl = new ArrayList<>();
            for (String b : s.blocks) bl.add(b == null ? "" : b);
            y.set(k + ".blocks", bl);
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
                s.anchored = c.getBoolean("anchored");
                s.wrecked = c.getBoolean("wrecked");
                List<String> bl = c.getStringList("blocks");
                for (int i = 0; i < s.blocks.length && i < bl.size(); i++) s.blocks[i] = bl.get(i).isEmpty() ? null : bl.get(i);
                s.recount();
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

    // =====================================================================================================
    //  /ship
    // =====================================================================================================
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "help";
        boolean admin = sender.hasPermission("faultlineships.admin");
        Player p = sender instanceof Player pp ? pp : null;
        switch (sub) {
            case "give" -> {
                if (!admin) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (args.length < 2) { sender.sendMessage(ChatColor.RED + "/ship give <sloop|brigantine|galleon|hammer> [amount] [player]"); return true; }
                ItemStack it;
                if (args[1].equalsIgnoreCase("hammer")) it = hammer();
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
            case "list" -> {
                if (p == null) { sender.sendMessage("Players only."); return true; }
                int n = 0;
                for (Ship s : ships.values()) {
                    if (!s.owner.equals(p.getUniqueId()) && !(admin && args.length > 1 && args[1].equalsIgnoreCase("all"))) continue;
                    n++;
                    String state = s.building ? "being built (" + s.filled + "/" + s.blocks.length + ")" : s.wrecked ? ChatColor.RED + "wrecked" : s.anchored ? "anchored" : "afloat";
                    sender.sendMessage(ChatColor.AQUA + s.type.title + ChatColor.GRAY + " at " + s.world + " " + (int) s.x + ", " + (int) s.y + ", " + (int) s.z
                            + ": " + state + ChatColor.GRAY + ", hull " + (int) Math.round(100 * s.hp / s.maxHp()) + "%");
                }
                if (n == 0) sender.sendMessage(ChatColor.GRAY + "You don't have any ships. Craft a blueprint: paper around a boat.");
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
                sender.sendMessage(ChatColor.YELLOW + "You took the " + s.type.title + " apart: the blueprint, its blocks and its cargo are by you.");
            }
            case "anchor" -> {
                if (p == null) { sender.sendMessage("Players only."); return true; }
                Ship s = ridingOn(p);
                if (s == null || s.seatOf(p) != 0) { sender.sendMessage(ChatColor.RED + "Take the helm first."); return true; }
                if (s.anchored) { if (s.wrecked) sender.sendMessage(ChatColor.RED + "The ship is wrecked: repair it first."); else s.raiseAnchor(p); }
                else if (s.anchorStep < 0) s.dropAnchor(p);
            }
            case "info" -> {
                if (p == null) { sender.sendMessage("Players only."); return true; }
                Ship s = ridingOn(p);
                if (s == null) s = nearest(p, 6);
                if (s == null) { sender.sendMessage(ChatColor.GRAY + "No ship nearby."); return true; }
                String own = Bukkit.getOfflinePlayer(s.owner).getName();
                sender.sendMessage(ChatColor.AQUA + s.type.title + ChatColor.GRAY + " owned by " + own + ", hull " + (int) Math.ceil(s.hp) + "/" + (int) s.maxHp()
                        + (s.wrecked ? ChatColor.RED + " (wrecked)" : "") + (s.building ? ChatColor.YELLOW + " (being built " + s.filled + "/" + s.blocks.length + ")" : ""));
            }
            case "repair", "remove", "wreck" -> {
                if (!admin) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (p == null) { sender.sendMessage("Players only."); return true; }
                Ship s = ridingOn(p);
                if (s == null) s = nearest(p, 6);
                if (s == null) { sender.sendMessage(ChatColor.RED + "No ship nearby."); return true; }
                if (sub.equals("repair")) {
                    if (s.building) { for (int i = 0; i < s.blocks.length; i++) if (s.blocks[i] == null) s.fill(i, s.type.cells.get(i).need().ghost); }
                    else { s.repair(s.maxHp()); }
                    sender.sendMessage(ChatColor.GREEN + "Fully repaired the " + s.type.title + ".");
                } else if (sub.equals("wreck")) {
                    s.damage(s.hp + 1, null);
                } else {
                    scrap(s, p.getLocation(), false);
                    sender.sendMessage(ChatColor.YELLOW + "Removed the " + s.type.title + ".");
                }
            }
            default -> {
                sender.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "Faultline Ships");
                sender.sendMessage(ChatColor.GRAY + "Craft a blueprint (Sloop: paper around a boat; Brigantine: gold + paper around a Sloop Blueprint;"
                        + " Galleon: diamonds + paper around a Brigantine Blueprint), right-click open water, and place every glowing block.");
                sender.sendMessage(ChatColor.GRAY + "Right-click the wheel to steer: W/S sail, A/D steer, Sprint full sail, Jump drops anchor (then the deck can be walked).");
                sender.sendMessage(ChatColor.GRAY + "A wrecked ship needs a Shipwright's Hammer (3 iron, 1 planks, 2 iron, 1 stick).");
                sender.sendMessage(ChatColor.YELLOW + "/ship list" + ChatColor.GRAY + ", " + ChatColor.YELLOW + "/ship info" + ChatColor.GRAY + ", "
                        + ChatColor.YELLOW + "/ship crew <add|remove|list> [player]" + ChatColor.GRAY + ", " + ChatColor.YELLOW + "/ship anchor" + ChatColor.GRAY + ", " + ChatColor.YELLOW + "/ship scrap");
                if (admin) sender.sendMessage(ChatColor.DARK_GRAY + "Admin: /ship give <sloop|brigantine|galleon|hammer> [amount] [player], /ship repair, /ship wreck, /ship remove");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> opts = new ArrayList<>();
        if (args.length == 1) {
            opts.addAll(List.of("list", "info", "crew", "scrap", "anchor"));
            if (sender.hasPermission("faultlineships.admin")) opts.addAll(List.of("give", "repair", "wreck", "remove"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) opts.addAll(List.of("sloop", "brigantine", "galleon", "hammer"));
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
            out.add(blueprint(s.type));
            Map<Material, Integer> count = new LinkedHashMap<>();
            for (String b : s.blocks) if (b != null) {
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
