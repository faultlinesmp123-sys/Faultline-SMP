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
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * THE SUBMERSIBLE (Deep 1.8.0, overhauled in 2.0): a little research sub for any water.
 *  - buy it from Halcyon's Dock Master (128 emeralds), right-click water to put it in, right-click it to climb in, sneak to
 *    climb out (you're put on top of it, never inside a wall). Sneak + punch it (owner or admin) = back into your inventory
 *    with its hull, plating and cargo. Sneak + right-click it from outside (owner) = the CARGO HOLD (27 slots).
 *  - DRIVING: it turns smoothly toward where you look (it doesn't snap), W thrusts forward, S brakes / reverses, A/D strafe,
 *    Jump rises, Sprint = full throttle. It has momentum, banks into turns, bobs at the surface, holds its depth when you let
 *    go and sinks to the floor when nobody's in it out of the water. Ramming a wall at speed dents the hull.
 *  - F = SONAR (12 s): a ping that shows every creature within 48 to you only (red = hostile, aqua = the rest) + a count.
 *    Q = the HEADLIGHT on/off (a light block ahead; the Abyss's dark lifts for you while it's on).
 *  - inside you breathe, bites hit the HULL instead of you, the Dark Ocean's pressure can't reach you
 *  - the HULL (100%): below `submersible.safe-y` (-40: the Trench) it cracks every second unless it has HADAL PLATING
 *    (right-click with 4 Netherite Ingots); at 0% it implodes (its cargo spills). Iron Ingots patch it (+10% each).
 * The hitbox is an Interaction the size of the hull (the vehicle is a tiny invisible stand: before 2.0 you had to find it to
 * click it, and an invisible armor stand can't be punched at all). The model's displays ride the stand with NO rotation of
 * their own (before, they kept the placing player's yaw and the model faced the wrong way).
 */
final class Submersible implements Listener {
    static final String TAG = "faultline_submersible", PART = "faultline_submersible_part", BSTAND = "faultline_submersible_bedrock", BOX = "faultline_submersible_box";

    final FaultlineDeep pl;
    final NamespacedKey itemKey, hullKey, plateKey, ownerKey, cargoKey, lightKey, lampKey;
    final Map<UUID, Sub> subs = new HashMap<>();
    final Map<UUID, Long> clicked = new HashMap<>();
    int ticks, lastErr = -99999;

    final class Sub implements InventoryHolder {
        final ArmorStand stand;
        ItemDisplay body, prop;
        Interaction box;
        ArmorStand bed;
        float yaw, pitch, roll;
        Vector vel = new Vector();
        double spin, bob;
        Location light;
        BlockData lightWas;
        Inventory hold;
        long sonarAt = -99999;
        Player lastRider;
        Sub(ArmorStand stand) { this.stand = stand; yaw = stand.getLocation().getYaw(); }
        Player rider() { for (Entity e : stand.getPassengers()) if (e instanceof Player p) return p; return null; }
        double hull() { return stand.getPersistentDataContainer().getOrDefault(hullKey, PersistentDataType.DOUBLE, 100.0); }
        void hull(double h) { stand.getPersistentDataContainer().set(hullKey, PersistentDataType.DOUBLE, Math.max(0, Math.min(100, h))); }
        boolean plated() { return stand.getPersistentDataContainer().has(plateKey, PersistentDataType.BYTE); }
        boolean lamp() { return stand.getPersistentDataContainer().getOrDefault(lampKey, PersistentDataType.BYTE, (byte) 1) == 1; }
        void lamp(boolean on) { stand.getPersistentDataContainer().set(lampKey, PersistentDataType.BYTE, (byte) (on ? 1 : 0)); }
        String owner() { return stand.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING); }
        @Override public Inventory getInventory() {
            if (hold == null) {
                hold = Bukkit.createInventory(this, 27, ChatColor.DARK_AQUA + "Submersible Cargo Hold");
                ItemStack[] got = decode(stand.getPersistentDataContainer().get(cargoKey, PersistentDataType.BYTE_ARRAY));
                if (got != null) for (int i = 0; i < Math.min(27, got.length); i++) hold.setItem(i, got[i]);
            }
            return hold;
        }
        Location center() { return stand.getLocation().add(0, CENTER, 0); }
    }

    /** The hull's middle sits this far above the vehicle stand's feet, and its box reaches HALF_W across and HALF_H up/down. */
    static final double CENTER = 1.4, HALF_W = 1.05, HALF_H = 1.05;

    Submersible(FaultlineDeep pl) {
        this.pl = pl;
        itemKey = new NamespacedKey(pl, "submersible");
        hullKey = new NamespacedKey(pl, "sub_hull");
        plateKey = new NamespacedKey(pl, "sub_plated");
        ownerKey = new NamespacedKey(pl, "sub_owner");
        cargoKey = new NamespacedKey(pl, "sub_cargo");
        lightKey = new NamespacedKey(pl, "sub_light");
        lampKey = new NamespacedKey(pl, "sub_lamp");
        try { Bukkit.removeRecipe(new NamespacedKey(pl, "submersible")); } catch (RuntimeException ignored) { }
        for (World w : Bukkit.getWorlds()) for (org.bukkit.Chunk c : w.getLoadedChunks()) for (Entity e : c.getEntities()) adopt(e);
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 1L, 1L);
    }

    double cfg(String path, double def) { return pl.getConfig().getDouble("submersible." + path, def); }

    static ItemStack[] decode(byte[] b) {
        if (b == null || b.length == 0) return null;
        try { return ItemStack.deserializeItemsFromBytes(b); } catch (RuntimeException e) { return null; }
    }

    static byte[] encode(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) copy[i] = items[i] == null || items[i].getType().isAir() ? new ItemStack(Material.AIR) : items[i].clone();
        return ItemStack.serializeItemsAsBytes(copy);
    }

    static boolean empty(ItemStack[] items) { if (items == null) return true; for (ItemStack i : items) if (i != null && !i.getType().isAir()) return false; return true; }

    ItemStack item(double hull, boolean plated) { return item(hull, plated, null); }

    ItemStack item(double hull, boolean plated, byte[] cargo) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + "Submersible");
        int stored = 0;
        ItemStack[] got = decode(cargo);
        if (got != null) for (ItemStack i : got) if (i != null && !i.getType().isAir()) stored++;
        List<String> lore = new ArrayList<>(List.of(ChatColor.GRAY + "Right-click water to put it in, right-click it to",
                ChatColor.GRAY + "climb in. W thrusts, it turns where you look.",
                ChatColor.GRAY + "F = sonar, Q = headlight, sneak = climb out.",
                ChatColor.GRAY + "You breathe inside; bites hit the hull, not you.",
                ChatColor.AQUA + "Hull: " + (int) hull + "%" + (plated ? ChatColor.LIGHT_PURPLE + "  (Hadal Plating)" : ""),
                ChatColor.DARK_GRAY + (plated ? "Safe at any depth." : "Cracks below y " + (int) cfg("safe-y", -40) + ": 4 Netherite Ingots = Hadal Plating."),
                ChatColor.DARK_GRAY + "Iron Ingots patch it. Sneak + right-click = cargo hold.",
                ChatColor.DARK_GRAY + "Sneak + punch it to pick it up."));
        if (stored > 0) lore.add(ChatColor.GOLD + "Cargo: " + stored + " stack" + (stored == 1 ? "" : "s"));
        m.setLore(lore);
        m.setItemModel(new NamespacedKey("faultline", "deep/submersible_icon"));
        m.setMaxStackSize(1);
        PersistentDataContainer c = m.getPersistentDataContainer();
        c.set(itemKey, PersistentDataType.BYTE, (byte) 1);
        c.set(hullKey, PersistentDataType.DOUBLE, hull);
        if (plated) c.set(plateKey, PersistentDataType.BYTE, (byte) 1);
        if (cargo != null && stored > 0) c.set(cargoKey, PersistentDataType.BYTE_ARRAY, cargo);
        it.setItemMeta(m);
        return it;
    }

    boolean isItem(ItemStack it) { return it != null && it.hasItemMeta() && it.getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE); }

    static boolean water(Block b) {
        Material m = b.getType();
        return m == Material.WATER || m == Material.BUBBLE_COLUMN || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS || m == Material.KELP || m == Material.KELP_PLANT
                || m == Material.LIGHT && b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged();
    }

    static boolean loaded(Location l) { return l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4); }

    // =========================================================================================== putting it in, finding it again
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !isItem(e.getItem())) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Location at = null;
        for (double d = 2; d <= 7; d += 0.5) {
            Location c = p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(d));
            if (!water(c.getBlock())) continue;
            Location st = c.clone().add(0, -CENTER, 0);
            if (!free(st)) st = c.clone().add(0, -CENTER + 1, 0);
            if (free(st) && water(st.clone().add(0, CENTER, 0).getBlock())) { at = st; break; }
        }
        if (at == null) { p.sendActionBar(net.kyori.adventure.text.Component.text("Aim it at open water (about 3 blocks wide and deep).", net.kyori.adventure.text.format.NamedTextColor.RED)); return; }
        ItemStack hand = e.getItem();
        PersistentDataContainer c = hand.getItemMeta().getPersistentDataContainer();
        Sub s = spawn(at, p.getLocation().getYaw(), c.getOrDefault(hullKey, PersistentDataType.DOUBLE, 100.0), c.has(plateKey, PersistentDataType.BYTE), p.getUniqueId(),
                c.get(cargoKey, PersistentDataType.BYTE_ARRAY));
        if (s != null && p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        p.playSound(at, Sound.ITEM_BUCKET_EMPTY, SoundCategory.PLAYERS, 1f, 0.6f);
    }

    Sub spawn(Location at, float yaw, double hull, boolean plated, UUID owner, byte[] cargo) {
        World w = at.getWorld();
        Location l = at.clone(); l.setYaw(yaw); l.setPitch(0);
        ArmorStand st = w.spawn(l, ArmorStand.class, a -> {
            a.setInvisible(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setBasePlate(false);
            a.setSmall(true);
            a.setPersistent(true);
            a.addScoreboardTag(TAG);
            a.getPersistentDataContainer().set(hullKey, PersistentDataType.DOUBLE, hull);
            if (plated) a.getPersistentDataContainer().set(plateKey, PersistentDataType.BYTE, (byte) 1);
            if (owner != null) a.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
            if (cargo != null) a.getPersistentDataContainer().set(cargoKey, PersistentDataType.BYTE_ARRAY, cargo);
        });
        Sub s = new Sub(st);
        s.yaw = yaw;
        s.body = part(w, l, "deep/submersible");
        s.prop = part(w, l, "deep/submersible__prop");
        st.addPassenger(s.body);
        st.addPassenger(s.prop);
        box(s);
        subs.put(st.getUniqueId(), s);
        pose(s);
        return s;
    }

    ItemDisplay part(World w, Location at, String model) {
        Location l = at.clone(); l.setYaw(0); l.setPitch(0); // NO rotation of its own: the pose is the whole rotation
        return w.spawn(l, ItemDisplay.class, d -> {
            ItemStack it = new ItemStack(Material.PAPER);
            ItemMeta m = it.getItemMeta();
            m.setItemModel(NamespacedKey.fromString("faultline:" + model));
            it.setItemMeta(m);
            d.setItemStack(it);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setPersistent(true);
            d.addScoreboardTag(PART);
            d.addScoreboardTag("faultline_no_bedrock_fx");
            d.setInterpolationDuration(2);
            d.setViewRange(2f);
            d.setDisplayWidth(5f);
            d.setDisplayHeight(4f);
            d.setBrightness(new org.bukkit.entity.Display.Brightness(12, 12));
        });
    }

    /** The click/punch box: an Interaction the size of the hull, kept on it every tick (not saved: remade on load). */
    void box(Sub s) {
        if (s.box != null && s.box.isValid()) return;
        Location l = s.stand.getLocation().add(0, CENTER - HALF_H, 0);
        s.box = l.getWorld().spawn(l, Interaction.class, i -> {
            i.setInteractionWidth((float) (HALF_W * 2 + 0.4));
            i.setInteractionHeight((float) (HALF_H * 2 + 0.2));
            i.setResponsive(true);
            i.setPersistent(false);
            i.addScoreboardTag(BOX);
            i.addScoreboardTag(PART);
        });
        Player r = s.rider();
        if (r != null) r.hideEntity(pl, s.box);
    }

    /** A sub that comes back with its chunk (it's a saved entity): know it again. */
    void adopt(Entity e) {
        if (e instanceof Interaction i && i.getScoreboardTags().contains(BOX) && !owned(i)) { i.remove(); return; }
        if (!(e instanceof ArmorStand st) || !st.getScoreboardTags().contains(TAG) || subs.containsKey(st.getUniqueId())) return;
        Sub s = new Sub(st);
        for (Entity p : st.getPassengers()) if (p instanceof ItemDisplay d) {
            ItemStack it = d.getItemStack();
            boolean isProp = it != null && it.hasItemMeta() && it.getItemMeta().hasItemModel() && it.getItemMeta().getItemModel().getKey().endsWith("__prop");
            if (isProp) s.prop = d; else s.body = d;
            d.setRotation(0, 0); // (subs from before 2.0 kept the placer's yaw on their displays)
        }
        if (s.body == null) { s.body = part(st.getWorld(), st.getLocation(), "deep/submersible"); st.addPassenger(s.body); }
        if (s.prop == null) { s.prop = part(st.getWorld(), st.getLocation(), "deep/submersible__prop"); st.addPassenger(s.prop); }
        // a headlight left in the water by a crash
        String lp = st.getPersistentDataContainer().get(lightKey, PersistentDataType.STRING);
        if (lp != null) {
            try {
                String[] a = lp.split(",");
                Block b = st.getWorld().getBlockAt(Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
                if (b.getType() == Material.LIGHT) b.setBlockData(Material.WATER.createBlockData(), false);
            } catch (RuntimeException ignored) { }
            st.getPersistentDataContainer().remove(lightKey);
        }
        box(s);
        subs.put(st.getUniqueId(), s);
    }

    boolean owned(Interaction i) { for (Sub s : subs.values()) if (i.equals(s.box)) return true; return false; }

    @EventHandler public void onLoad(EntitiesLoadEvent e) { for (Entity en : e.getEntities()) if (en instanceof ArmorStand) adopt(en); }

    @EventHandler public void onUnload(EntitiesUnloadEvent e) {
        for (Entity en : e.getEntities()) { Sub s = subs.remove(en.getUniqueId()); if (s != null) forget(s); }
    }

    void forget(Sub s) {
        unlight(s);
        saveHold(s);
        if (s.hold != null) for (org.bukkit.entity.HumanEntity h : new ArrayList<>(s.hold.getViewers())) h.closeInventory();
        if (s.bed != null && s.bed.isValid()) s.bed.remove();
        if (s.box != null && s.box.isValid()) s.box.remove();
    }

    Sub of(Entity e) {
        Sub s = subs.get(e.getUniqueId());
        if (s != null) return s;
        if (e.getVehicle() != null && !(e instanceof Player)) { s = subs.get(e.getVehicle().getUniqueId()); if (s != null) return s; }
        for (Sub x : subs.values()) if (e.equals(x.bed) || e.equals(x.box)) return x;
        return null;
    }

    /** Is this player inside a sub right now? (Ocean: no pressure, no dark) */
    boolean riding(Player p) { return p.getVehicle() != null && subs.containsKey(p.getVehicle().getUniqueId()); }

    boolean mayUse(Sub s, Player p) {
        String o = s.owner();
        return o == null || o.equals(p.getUniqueId().toString()) || p.hasPermission("faultlinedeep.admin") || !pl.getConfig().getBoolean("submersible.owner-only-cargo", true);
    }

    // =========================================================================================== climbing in, patching, cargo
    @EventHandler(priority = EventPriority.HIGH)
    public void onClickAt(PlayerInteractAtEntityEvent e) { click(e); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEntityEvent e) { click(e); }

    void click(PlayerInteractEntityEvent e) {
        Sub s = of(e.getRightClicked());
        if (s == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        long now = ticks;
        Long last = clicked.get(p.getUniqueId());
        if (last != null && now - last < 4) return; // (the client sends an "at" click and a plain one together)
        clicked.put(p.getUniqueId(), now);
        if (riding(p)) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() == Material.IRON_INGOT && s.hull() < 100) {
            int n = (int) Math.min(hand.getAmount(), Math.ceil((100 - s.hull()) / cfg("repair-per-ingot", 10)));
            hand.setAmount(hand.getAmount() - n);
            s.hull(s.hull() + n * cfg("repair-per-ingot", 10));
            p.playSound(s.stand.getLocation(), Sound.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 0.8f, 1.4f);
            s.stand.getWorld().spawnParticle(Particle.WAX_OFF, s.center(), 10, 0.8, 0.6, 0.8, 0);
            p.sendActionBar(net.kyori.adventure.text.Component.text("Hull patched: " + (int) s.hull() + "%", net.kyori.adventure.text.format.NamedTextColor.AQUA));
            return;
        }
        if (hand.getType() == Material.NETHERITE_INGOT && !s.plated()) {
            int need = (int) cfg("plating-ingots", 4);
            if (hand.getAmount() < need) { p.sendActionBar(net.kyori.adventure.text.Component.text("Hadal Plating takes " + need + " Netherite Ingots.", net.kyori.adventure.text.format.NamedTextColor.GRAY)); return; }
            hand.setAmount(hand.getAmount() - need);
            s.stand.getPersistentDataContainer().set(plateKey, PersistentDataType.BYTE, (byte) 1);
            p.playSound(s.stand.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, SoundCategory.PLAYERS, 1f, 0.8f);
            p.sendMessage(ChatColor.LIGHT_PURPLE + "Hadal Plating fitted: " + ChatColor.GRAY + "the Submersible is safe at any depth now.");
            return;
        }
        if (p.isSneaking()) {
            if (!mayUse(s, p)) { p.sendActionBar(net.kyori.adventure.text.Component.text("The hold is locked: it isn't your sub.", net.kyori.adventure.text.format.NamedTextColor.RED)); return; }
            p.openInventory(s.getInventory());
            p.playSound(s.stand.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, SoundCategory.PLAYERS, 1f, 0.9f);
            return;
        }
        if (s.rider() != null) { p.sendActionBar(net.kyori.adventure.text.Component.text("Someone's already inside.", net.kyori.adventure.text.format.NamedTextColor.GRAY)); return; }
        if (!s.stand.addPassenger(p)) return;
        s.lastRider = p;
        if (s.box != null) p.hideEntity(pl, s.box);
        p.playSound(s.stand.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, SoundCategory.PLAYERS, 1f, 0.7f);
        p.sendMessage(ChatColor.YELLOW + "Submersible: " + ChatColor.GRAY + "it turns where you look. W thrust, S brake, A/D slide, Jump up, Sprint full speed. "
                + ChatColor.AQUA + "F" + ChatColor.GRAY + " sonar, " + ChatColor.AQUA + "Q" + ChatColor.GRAY + " headlight, sneak to climb out.");
    }

    @EventHandler public void onHoldClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Sub s)) return;
        Bukkit.getScheduler().runTask(pl, () -> saveHold(s)); // saved on every change: a crash can't lose or copy it
    }

    @EventHandler public void onHoldDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder(false) instanceof Sub s)) return;
        Bukkit.getScheduler().runTask(pl, () -> saveHold(s));
    }

    @EventHandler public void onHoldClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder(false) instanceof Sub s) saveHold(s);
    }

    void saveHold(Sub s) {
        if (s.hold == null || !s.stand.isValid()) return;
        try {
            ItemStack[] items = s.hold.getContents();
            if (empty(items)) s.stand.getPersistentDataContainer().remove(cargoKey);
            else s.stand.getPersistentDataContainer().set(cargoKey, PersistentDataType.BYTE_ARRAY, encode(items));
        } catch (RuntimeException ex) {
            pl.getLogger().warning("[Submersible] couldn't save a cargo hold: " + ex);
        }
    }

    // =========================================================================================== punching, picking up
    @EventHandler(priority = EventPriority.HIGH)
    public void onPunch(io.papermc.paper.event.player.PrePlayerAttackEntityEvent e) {
        Sub s = of(e.getAttacked());
        if (s == null) return;
        e.setCancelled(true);
        pickUp(s, e.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Player) return;
        Sub s = of(e.getEntity());
        if (s == null) return;
        e.setCancelled(true);
    }

    void pickUp(Sub s, Player p) {
        if (!p.isSneaking() || riding(p)) {
            if (!riding(p)) p.sendActionBar(net.kyori.adventure.text.Component.text("Sneak + punch to pick it up.", net.kyori.adventure.text.format.NamedTextColor.GRAY));
            return;
        }
        String owner = s.owner();
        if (owner != null && !owner.equals(p.getUniqueId().toString()) && !p.hasPermission("faultlinedeep.admin")) {
            p.sendActionBar(net.kyori.adventure.text.Component.text("It isn't yours.", net.kyori.adventure.text.format.NamedTextColor.RED)); return;
        }
        if (s.rider() != null) { p.sendActionBar(net.kyori.adventure.text.Component.text("Someone's inside it.", net.kyori.adventure.text.format.NamedTextColor.GRAY)); return; }
        if (s.hold != null) for (org.bukkit.entity.HumanEntity h : new ArrayList<>(s.hold.getViewers())) h.closeInventory();
        saveHold(s);
        ItemStack it = item(s.hull(), s.plated(), s.stand.getPersistentDataContainer().get(cargoKey, PersistentDataType.BYTE_ARRAY));
        remove(s);
        p.getInventory().addItem(it).values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
        p.playSound(p, Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 1f, 0.8f);
    }

    void remove(Sub s) {
        subs.remove(s.stand.getUniqueId());
        forget(s);
        for (Entity e : new ArrayList<>(s.stand.getPassengers())) { if (e instanceof Player) s.stand.removePassenger(e); else e.remove(); }
        s.stand.remove();
    }

    /** Bites, shots and blasts on someone inside hit the hull instead (a boss's moves still reach you). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRiderHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !riding(p)) return;
        Sub s = subs.get(p.getVehicle().getUniqueId());
        switch (e.getCause()) {
            case DROWNING, SUFFOCATION, FALL, FLY_INTO_WALL -> e.setCancelled(true);
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK, PROJECTILE, ENTITY_EXPLOSION, BLOCK_EXPLOSION, POISON, MAGIC, THORNS -> {
                if (e instanceof EntityDamageByEntityEvent ee && ee.getDamager().getScoreboardTags().stream().anyMatch(t -> t.startsWith("faultline_wild") || t.startsWith("faultline_boss"))) return;
                e.setCancelled(true);
                s.hull(s.hull() - e.getDamage() * cfg("hull-per-damage", 1.0));
                p.playSound(p, Sound.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.4f, 1.6f);
                if (s.hull() <= 0) implode(s);
            }
            default -> { }
        }
    }

    /** Climbing out: put them on top of the hull (or beside it) a tick later, never inside it or a wall. */
    @EventHandler public void onDismount(EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Sub s = subs.get(e.getDismounted().getUniqueId());
        if (s == null) return;
        unlight(s);
        if (s.box != null && s.box.isValid()) p.showEntity(pl, s.box);
        Location base = s.stand.getLocation();
        if (!pl.isEnabled()) return;
        Bukkit.getScheduler().runTask(pl, () -> {
            if (!p.isOnline() || p.getVehicle() != null) return;
            for (double[] o : new double[][]{{0, CENTER + HALF_H + 0.2, 0}, {HALF_W + 1.0, 0.6, 0}, {-HALF_W - 1.0, 0.6, 0}, {0, 0.6, HALF_W + 1.0}, {0, 0.6, -HALF_W - 1.0}}) {
                Location t = base.clone().add(o[0], o[1], o[2]);
                if (!solid(t.getBlock()) && !solid(t.clone().add(0, 1, 0).getBlock())) {
                    t.setYaw(p.getLocation().getYaw()); t.setPitch(p.getLocation().getPitch());
                    p.teleport(t);
                    break;
                }
            }
            p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 200, 0, true, false, true));
        });
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { clicked.remove(e.getPlayer().getUniqueId()); if (riding(e.getPlayer())) e.getPlayer().leaveVehicle(); }

    /** F inside = sonar. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (!riding(p)) return;
        e.setCancelled(true);
        sonar(subs.get(p.getVehicle().getUniqueId()), p);
    }

    /** Q inside = the headlight on/off. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        if (!riding(p)) return;
        e.setCancelled(true);
        Sub s = subs.get(p.getVehicle().getUniqueId());
        s.lamp(!s.lamp());
        if (!s.lamp()) unlight(s);
        p.playSound(p, Sound.BLOCK_LEVER_CLICK, SoundCategory.PLAYERS, 1f, s.lamp() ? 1.4f : 0.8f);
        p.sendActionBar(net.kyori.adventure.text.Component.text("Headlight " + (s.lamp() ? "ON" : "OFF"), s.lamp() ? net.kyori.adventure.text.format.NamedTextColor.YELLOW : net.kyori.adventure.text.format.NamedTextColor.GRAY));
    }

    void sonar(Sub s, Player p) {
        long cd = (long) (cfg("sonar-cooldown-seconds", 12) * 20);
        if (ticks - s.sonarAt < cd) {
            p.sendActionBar(net.kyori.adventure.text.Component.text("Sonar recharging: " + (int) Math.ceil((cd - (ticks - s.sonarAt)) / 20.0) + " s", net.kyori.adventure.text.format.NamedTextColor.GRAY));
            return;
        }
        s.sonarAt = ticks;
        double r = cfg("sonar-radius", 48);
        Location c = s.center();
        p.playSound(p, Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.PLAYERS, 1f, 0.5f);
        p.playSound(p, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.6f, 2f);
        int hostile = 0, other = 0;
        double nearest = Double.MAX_VALUE;
        String nearestName = null;
        for (Entity en : c.getWorld().getNearbyEntities(c, r, r, r)) {
            if (!(en instanceof LivingEntity le) || en.equals(p) || en instanceof ArmorStand || en.isInvisible() && !en.getScoreboardTags().stream().anyMatch(t -> t.startsWith("faultline_deep_"))) continue;
            if (le instanceof Player op && (op.getGameMode() == GameMode.SPECTATOR)) continue;
            boolean bad = le instanceof Enemy || en.getScoreboardTags().stream().anyMatch(t -> t.startsWith("faultline_wild") || t.startsWith("faultline_boss"));
            if (bad) hostile++; else other++;
            double d = en.getLocation().distance(c);
            long delay = Math.max(1, (long) (d / 6));
            Location at = le.getLocation().add(0, le.getHeight() / 2, 0);
            Color col = bad ? Color.fromRGB(255, 60, 50) : Color.fromRGB(90, 230, 255);
            Bukkit.getScheduler().runTaskLater(pl, () -> {
                if (!p.isOnline()) return;
                for (int k = 0; k < 3; k++) Bukkit.getScheduler().runTaskLater(pl, () -> { if (p.isOnline()) p.spawnParticle(Particle.DUST, at, 14, 0.4, 0.5, 0.4, 0, new Particle.DustOptions(col, 2.2f), true); }, k * 10L);
                p.playSound(at, Sound.BLOCK_NOTE_BLOCK_BIT, SoundCategory.PLAYERS, 0.5f, bad ? 0.6f : 1.6f);
            }, delay);
            if (bad && d < nearest) { nearest = d; nearestName = en.getCustomName() != null ? ChatColor.stripColor(en.getCustomName()) : en.getType().name().toLowerCase().replace('_', ' '); }
        }
        // the ring of the ping itself
        for (int i = 0; i < 4; i++) {
            final double rr = 4 + i * 6;
            Bukkit.getScheduler().runTaskLater(pl, () -> {
                if (!p.isOnline()) return;
                for (int a = 0; a < 36; a++) {
                    double ang = Math.PI * 2 * a / 36;
                    p.spawnParticle(Particle.DUST, c.clone().add(Math.cos(ang) * rr, 0, Math.sin(ang) * rr), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(120, 255, 200), 1.4f), true);
                }
            }, i * 2L);
        }
        String msg = ChatColor.AQUA + "Sonar: " + ChatColor.RED + hostile + " hostile" + ChatColor.GRAY + ", " + ChatColor.AQUA + other + " other" + ChatColor.GRAY + " within " + (int) r
                + (nearestName != null ? ChatColor.GRAY + " | nearest threat: " + ChatColor.RED + nearestName + " (" + (int) nearest + " m)" : "");
        p.sendActionBar(msg);
    }

    // =========================================================================================== driving
    void tick() {
        ticks++;
        for (Sub s : new ArrayList<>(subs.values())) {
            if (!s.stand.isValid()) { subs.remove(s.stand.getUniqueId()); forget(s); continue; }
            try {
                box(s);
                Player p = s.rider();
                if (p != null) drive(s, p); else idle(s);
                if (s.box != null && s.box.isValid()) s.box.teleport(s.stand.getLocation().add(0, CENTER - HALF_H - 0.1, 0));
                pose(s);
                if (ticks % 2 == 0) bedrock(s);
            } catch (RuntimeException e) { // one sub's trouble never stops the others (logged at most once a minute)
                if (ticks - lastErr > 1200) { lastErr = ticks; pl.getLogger().warning("[Submersible] " + e + (e.getStackTrace().length > 0 ? " at " + e.getStackTrace()[0] : "")); }
            }
        }
    }

    void idle(Sub s) {
        unlight(s);
        s.roll *= 0.9f;
        s.pitch *= 0.95f;
        Location l = s.stand.getLocation();
        boolean wet = water(s.center().getBlock()) || water(l.getBlock());
        if (wet) {
            s.vel.multiply(0.9);
            // floating at the surface: it bobs; under water it holds its depth
            if (!water(s.center().add(0, 0.6, 0).getBlock())) { s.bob += 0.08; s.vel.setY(s.vel.getY() + Math.sin(s.bob) * 0.003); }
        } else {
            s.vel.multiply(0.95);
            s.vel.setY(Math.max(-0.6, s.vel.getY() - 0.04)); // out of the water: it drops
        }
        if (s.vel.lengthSquared() > 1e-6) move(s, s.vel, false);
    }

    void drive(Sub s, Player p) {
        org.bukkit.Input in;
        try { in = p.getCurrentInput(); } catch (RuntimeException | LinkageError e) { in = null; }
        Location eye = p.getEyeLocation();
        // the hull turns toward the pilot's view at a fixed rate (it never snaps round)
        float turn = (float) cfg("turn-rate", 7);
        float dy = wrap(eye.getYaw() - s.yaw);
        float step = Math.max(-turn, Math.min(turn, dy * 0.35f));
        s.yaw = wrap(s.yaw + step);
        float wantPitch = Math.max(-50, Math.min(50, eye.getPitch()));
        s.pitch += (wantPitch - s.pitch) * 0.12f;
        Vector fwd = new Vector(-Math.sin(Math.toRadians(s.yaw)) * Math.cos(Math.toRadians(s.pitch)), -Math.sin(Math.toRadians(s.pitch)), Math.cos(Math.toRadians(s.yaw)) * Math.cos(Math.toRadians(s.pitch)));
        Vector flat = new Vector(-Math.sin(Math.toRadians(s.yaw)), 0, Math.cos(Math.toRadians(s.yaw)));
        Vector right = new Vector(-flat.getZ(), 0, flat.getX());
        boolean sprint = in != null && in.isSprint();
        double sp = sprint ? cfg("sprint-speed", 0.55) : cfg("speed", 0.32);
        Vector thrust = new Vector();
        double side = 0;
        if (in != null) {
            if (in.isForward()) thrust.add(fwd.clone().multiply(sp));
            if (in.isBackward()) {
                if (s.vel.dot(fwd) > 0.05) thrust.add(s.vel.clone().multiply(-0.9)); // braking first
                else thrust.add(fwd.clone().multiply(-sp * 0.4));
            }
            if (in.isLeft()) { thrust.add(right.clone().multiply(-sp * 0.5)); side -= 1; }
            if (in.isRight()) { thrust.add(right.clone().multiply(sp * 0.5)); side += 1; }
            if (in.isJump()) thrust.add(new Vector(0, sp * 0.6, 0));
        }
        double accel = cfg("acceleration", 0.12);
        s.vel.multiply(1 - accel).add(thrust.multiply(accel));
        if (s.vel.lengthSquared() < 1e-5) s.vel.zero();
        // the hull banks into turns and slides
        float wantRoll = (float) Math.max(-25, Math.min(25, -step * 2.6 - side * 8));
        s.roll += (wantRoll - s.roll) * 0.15f;

        Location l = s.stand.getLocation();
        boolean surface = !water(s.center().add(0, 0.6, 0).getBlock());
        if (surface) { s.bob += 0.08; s.vel.setY(s.vel.getY() + Math.sin(s.bob) * 0.004); }
        double before = s.vel.length();
        boolean hit = move(s, s.vel, true);
        if (hit && before > cfg("ram-speed", 0.4)) {
            double dmg = (before - cfg("ram-speed", 0.4)) * cfg("ram-damage", 12);
            s.hull(s.hull() - dmg);
            l.getWorld().playSound(l, Sound.ENTITY_IRON_GOLEM_HURT, SoundCategory.PLAYERS, 1f, 0.6f);
            l.getWorld().spawnParticle(Particle.BLOCK, s.center(), 20, 0.8, 0.6, 0.8, 0, Material.IRON_BLOCK.createBlockData());
            if (s.hull() <= 0) { implode(s); return; }
        }
        s.spin += 4 + s.vel.length() * 140;
        p.setRemainingAir(p.getMaximumAir());
        if (ticks % 40 == 0) p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 100, 0, true, false, false));
        if (s.lamp()) {
            try { light(s, fwd); } catch (RuntimeException ignored) { }
            if (pl.ocean != null && pl.ocean.darkened.contains(p.getUniqueId())) pl.ocean.glowLit.put(p.getUniqueId(), pl.ocean.ticks + 30L);
        }
        l = s.stand.getLocation();
        // the hull and the depth
        boolean deep = l.getY() < cfg("safe-y", -40) && !s.plated();
        if (ticks % 20 == 0 && deep) {
            s.hull(s.hull() - cfg("crack-per-second", 3));
            p.playSound(p, Sound.BLOCK_GLASS_HIT, SoundCategory.PLAYERS, 1f, 0.5f);
            l.getWorld().spawnParticle(Particle.BUBBLE, s.center(), 12, 0.6, 0.4, 0.6, 0.05);
            if (s.hull() <= 0) { implode(s); return; }
            if (s.hull() < 25) p.playSound(p, Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.PLAYERS, 0.8f, 0.5f);
        }
        if (ticks % 5 == 0) hud(s, p, deep);
        if (ticks % 30 == 0 && s.vel.lengthSquared() > 0.01) l.getWorld().playSound(l, Sound.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 0.4f, 1.8f);
        if (ticks % 2 == 0 && s.vel.lengthSquared() > 0.005)
            l.getWorld().spawnParticle(surface ? Particle.SPLASH : Particle.BUBBLE, s.center().add(flat.clone().multiply(-2.3)), sprint ? 6 : 3, 0.15, 0.15, 0.15, 0.03);
    }

    void hud(Sub s, Player p, boolean deep) {
        Location l = s.stand.getLocation();
        int h = (int) Math.round(s.hull());
        ChatColor hc = h > 60 ? ChatColor.GREEN : h > 25 ? ChatColor.YELLOW : ChatColor.RED;
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < 10; i++) bar.append(i < (h + 5) / 10 ? hc + "|" : ChatColor.DARK_GRAY + "|");
        String[] dirs = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        String dir = dirs[(int) Math.floorMod(Math.round(s.yaw / 45f), 8)];
        double speed = s.vel.length() * 20;
        long cd = (long) (cfg("sonar-cooldown-seconds", 12) * 20);
        boolean ready = ticks - s.sonarAt >= cd;
        p.sendActionBar(ChatColor.YELLOW + "Hull " + bar + " " + hc + h + "%" + ChatColor.GRAY + "  y " + ChatColor.WHITE + l.getBlockY()
                + ChatColor.GRAY + "  " + ChatColor.WHITE + String.format("%.1f", speed) + ChatColor.GRAY + " b/s " + ChatColor.WHITE + dir
                + ChatColor.GRAY + "  Sonar " + (ready ? ChatColor.GREEN + "ready" : ChatColor.DARK_GRAY + "..." )
                + ChatColor.GRAY + "  Light " + (s.lamp() ? ChatColor.YELLOW + "on" : ChatColor.DARK_GRAY + "off")
                + (deep ? ChatColor.RED + "  TOO DEEP: cracking!" : s.plated() ? ChatColor.LIGHT_PURPLE + "  Hadal" : ""));
    }

    static float wrap(float a) { a %= 360; if (a > 180) a -= 360; if (a < -180) a += 360; return a; }

    /** Moves the sub by d (an axis at a time, so it slides along walls). It never leaves the water going up while driven.
     *  True when something stopped it. */
    boolean move(Sub s, Vector d, boolean driven) {
        Location l = s.stand.getLocation();
        Location n = l.clone();
        boolean hit = false;
        for (int axis = 0; axis < 3; axis++) {
            double v = axis == 0 ? d.getX() : axis == 1 ? d.getY() : d.getZ();
            if (Math.abs(v) < 1e-7) continue;
            Location t = n.clone().add(axis == 0 ? v : 0, axis == 1 ? v : 0, axis == 2 ? v : 0);
            boolean ok = free(t) && ends(t, s.yaw);
            // the surface: going up, the hull's middle must stay in the water (its top may break the surface)
            if (ok && axis == 1 && v > 0 && driven && !water(t.clone().add(0, CENTER, 0).getBlock())) ok = false;
            if (ok) n = t;
            else {
                if (axis != 1 || v < 0 || !driven) hit |= Math.abs(v) > 0.05;
                if (axis == 0) s.vel.setX(0); else if (axis == 1) s.vel.setY(0); else s.vel.setZ(0);
            }
        }
        n.setYaw(s.yaw); n.setPitch(0);
        if (n.distanceSquared(l) < 1e-8) { if (Math.abs(l.getYaw() - s.yaw) > 0.01) s.stand.setRotation(s.yaw, 0); return hit; }
        s.stand.teleport(n, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
        return hit;
    }

    /** Room for the hull (about 2.1 across, 2.1 tall round its middle) with the vehicle's feet at l. Unloaded = a wall. */
    static boolean free(Location l) {
        World w = l.getWorld();
        double[] hs = {-HALF_W, 0, HALF_W};
        double[] vs = {CENTER - HALF_H + 0.05, CENTER, CENTER + HALF_H - 0.05};
        for (double dx : hs) for (double dz : hs) for (double dy : vs) {
            Location c = l.clone().add(dx, dy, dz);
            if (!loaded(c)) return false;
            Block b = c.getBlock();
            if (b.getType().isAir() || water(b)) continue;
            if (solid(b)) return false;
        }
        return true;
    }

    static boolean solid(Block b) {
        if (b.getType().isAir() || water(b)) return false;
        try { return !b.isPassable(); } catch (RuntimeException e) { return b.getType().isSolid(); } // (a test server without isPassable)
    }

    /** The nose and the tail stick out past the square box: they need water / air too. */
    static boolean ends(Location l, float yaw) {
        double fx = -Math.sin(Math.toRadians(yaw)), fz = Math.cos(Math.toRadians(yaw));
        for (double d : new double[]{1.75, -2.1}) {
            Location c = l.clone().add(fx * d, CENTER, fz * d);
            if (!loaded(c)) return false;
            Block b = c.getBlock();
            if (!b.getType().isAir() && !water(b) && solid(b)) return false;
        }
        return true;
    }

    void implode(Sub s) {
        Location l = s.center();
        Player p = s.rider();
        World w = l.getWorld();
        w.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 2f, 0.6f);
        w.playSound(l, Sound.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 2f, 0.5f);
        w.spawnParticle(Particle.BUBBLE_POP, l, 120, 1.2, 1, 1.2, 0.2);
        w.spawnParticle(Particle.BLOCK, l, 60, 1, 1, 1, 0, Material.IRON_BLOCK.createBlockData());
        if (s.hold != null) for (org.bukkit.entity.HumanEntity h : new ArrayList<>(s.hold.getViewers())) h.closeInventory();
        saveHold(s);
        ItemStack[] cargo = decode(s.stand.getPersistentDataContainer().get(cargoKey, PersistentDataType.BYTE_ARRAY));
        s.stand.getPersistentDataContainer().remove(cargoKey);
        remove(s);
        if (cargo != null) for (ItemStack it : cargo) if (it != null && !it.getType().isAir()) w.dropItemNaturally(l, it);
        if (p != null) {
            p.damage(cfg("implode-damage", 10));
            p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "The Submersible's hull gave way!" + ChatColor.GRAY + " (Hadal Plating keeps it whole below y " + (int) cfg("safe-y", -40) + ".)");
        }
        w.dropItemNaturally(l, new ItemStack(Material.IRON_BLOCK, 2));
        w.dropItemNaturally(l, new ItemStack(Material.HEART_OF_THE_SEA, 1));
    }

    // =========================================================================================== the headlight
    void light(Sub s, Vector fwd) {
        Location want = null;
        Location from = s.center();
        for (double d = 4.5; d >= 2.5; d -= 0.5) {
            Block b = from.clone().add(fwd.clone().multiply(d)).getBlock();
            if (b.getLocation().equals(s.light)) { want = s.light; break; }
            if (b.getType() == Material.WATER && b.getBlockData() instanceof Levelled lv && lv.getLevel() == 0) { want = b.getLocation(); break; }
        }
        if (want != null && want.equals(s.light)) return;
        unlight(s);
        if (want == null) return;
        Block b = want.getBlock();
        s.lightWas = b.getBlockData();
        s.light = want;
        b.setBlockData(Material.LIGHT.createBlockData(bd -> {
            if (bd instanceof Levelled lv) lv.setLevel(15);
            if (bd instanceof org.bukkit.block.data.Waterlogged wl) wl.setWaterlogged(true);
        }), false);
        s.stand.getPersistentDataContainer().set(lightKey, PersistentDataType.STRING, want.getBlockX() + "," + want.getBlockY() + "," + want.getBlockZ());
    }

    void unlight(Sub s) {
        if (s.light == null) return;
        Block b = s.light.getBlock();
        if (b.getType() == Material.LIGHT) b.setBlockData(s.lightWas != null && s.lightWas.getMaterial() != Material.LIGHT ? s.lightWas : Material.WATER.createBlockData(), false);
        s.light = null; s.lightWas = null;
        if (s.stand.isValid()) s.stand.getPersistentDataContainer().remove(lightKey);
    }

    // =========================================================================================== drawing
    void pose(Sub s) {
        Quaternionf frame = new Quaternionf().rotationY((float) Math.toRadians(-s.yaw)).rotateX((float) Math.toRadians(s.pitch))
                .rotateZ((float) Math.toRadians(s.roll)).rotateY((float) Math.toRadians(-90));
        Quaternionf flip = new Quaternionf().rotationY((float) Math.PI);
        float sc = (float) cfg("model-scale", 2.0);
        Vector3f lift = new Vector3f(0, (float) cfg("model-lift", 0.9), 0);
        if (s.body != null && s.body.isValid()) {
            if (s.body.getLocation().getYaw() != 0 || s.body.getLocation().getPitch() != 0) s.body.setRotation(0, 0);
            s.body.setInterpolationDelay(0);
            s.body.setTransformation(new Transformation(new Vector3f(lift), new Quaternionf(frame).mul(flip), new Vector3f(sc, sc, sc), new Quaternionf()));
        }
        if (s.prop != null && s.prop.isValid()) {
            if (s.prop.getLocation().getYaw() != 0 || s.prop.getLocation().getPitch() != 0) s.prop.setRotation(0, 0);
            float[] pv = {-1.08f, 0f, 0f};
            List<DeepParts.Bone> bones = DeepParts.BONES.get("submersible");
            if (bones != null) for (DeepParts.Bone b : bones) if (b.name().equals("prop")) pv = new float[]{b.x(), b.y(), b.z()};
            Vector3f t = new Vector3f(pv[0], pv[1], pv[2]).mul(sc);
            frame.transform(t);
            t.add(lift);
            Quaternionf spin = new Quaternionf().rotateX((float) Math.toRadians(s.spin));
            s.prop.setInterpolationDelay(0);
            s.prop.setTransformation(new Transformation(t, new Quaternionf(frame).mul(spin).mul(flip), new Vector3f(sc, sc, sc), new Quaternionf()));
        }
    }

    /** Bedrock players can't see displays: a stand only they see, wearing the sub (FaultlineDeep.mcpack draws it). */
    void bedrock(Sub s) {
        if (pl.mobs == null || !pl.mobs.bedrockHere && !anyBedrock(s.stand.getWorld())) { if (s.bed != null && s.bed.isValid()) { s.bed.remove(); s.bed = null; } return; }
        Location l = s.stand.getLocation().add(0, 0.2, 0); l.setYaw(s.yaw); l.setPitch(0);
        if (s.bed == null || !s.bed.isValid()) {
            s.bed = l.getWorld().spawn(l, ArmorStand.class, a -> {
                a.setVisibleByDefault(false);
                a.setPersistent(false);
                a.setInvisible(true);
                a.setGravity(false);
                a.setSilent(true);
                a.setBasePlate(false);
                a.setMarker(false);
                a.addScoreboardTag(BSTAND);
                ItemStack it = new ItemStack(Material.PAPER);
                ItemMeta m = it.getItemMeta();
                m.setItemModel(NamespacedKey.fromString("faultline:deep/submersible"));
                it.setItemMeta(m);
                a.getEquipment().setHelmet(it);
            });
            DeepMobs.attr(s.bed, org.bukkit.attribute.Attribute.SCALE, cfg("model-scale", 2.0));
            for (Player p : l.getWorld().getPlayers()) if (Edition.bedrock(p)) p.showEntity(pl, s.bed);
        }
        s.bed.teleport(l);
    }

    static boolean anyBedrock(World w) { for (Player p : w.getPlayers()) if (Edition.bedrock(p)) return true; return false; }

    void shutdown() {
        for (Sub s : subs.values()) { Player p = s.rider(); if (p != null) p.leaveVehicle(); forget(s); }
        subs.clear();
    }

    /** /deep sub [player]: a Submersible for an admin. */
    void give(Player to) {
        to.getInventory().addItem(item(100, false)).values().forEach(x -> to.getWorld().dropItemNaturally(to.getLocation(), x));
    }
}
