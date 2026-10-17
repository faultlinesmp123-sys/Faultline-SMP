package net.faultlinesmp.deep;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
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
 * THE SUBMERSIBLE (Deep 1.8.0): a little research sub for any water (the Dark Ocean above all).
 *  - craft it (iron blocks, glass, copper blocks, a Heart of the Sea), right-click water to put it in, right-click it to
 *    climb in, sneak to climb out. Sneak + punch it (its owner, or an admin) = back into your inventory, hull and all
 *  - W goes where you look (up and down too), S backs up, A/D slide sideways, Jump rises, Sprint is faster. It can't
 *    leave the water: at the surface it bobs
 *  - inside you breathe, creatures' bites hit the HULL instead of you, the Dark Ocean's pressure can't reach you, and
 *    its HEADLIGHT lights the way (a light block ahead, and the Abyss's dark lifts for you)
 *  - the HULL (100%): below `submersible.safe-y` (-40: the Trench) it cracks every second unless it has HADAL PLATING
 *    (right-click it with 4 Netherite Ingots); at 0% it implodes. Right-click it with Iron Ingots to patch it (+10% each)
 * Its model is a DeepMobs-style rig: faultline:deep/submersible + the prop bone (tools/deep_assets.py), and for Bedrock a
 * stand only they see wearing it. Everything is a saved entity (the vehicle stand carries the hull in its PDC).
 */
final class Submersible implements Listener {
    static final String TAG = "faultline_submersible", PART = "faultline_submersible_part", BSTAND = "faultline_submersible_bedrock";

    final FaultlineDeep pl;
    final NamespacedKey itemKey, hullKey, plateKey, ownerKey;
    final Map<UUID, Sub> subs = new HashMap<>();
    int ticks, lastErr = -99999;

    final class Sub {
        final ArmorStand stand;
        ItemDisplay body, prop;
        ArmorStand bed;
        float yaw, pitch;
        Vector vel = new Vector();
        double spin;
        Location light;
        BlockData lightWas;
        Sub(ArmorStand stand) { this.stand = stand; yaw = stand.getLocation().getYaw(); }
        Player rider() { for (Entity e : stand.getPassengers()) if (e instanceof Player p) return p; return null; }
        double hull() { return stand.getPersistentDataContainer().getOrDefault(hullKey, PersistentDataType.DOUBLE, 100.0); }
        void hull(double h) { stand.getPersistentDataContainer().set(hullKey, PersistentDataType.DOUBLE, Math.max(0, Math.min(100, h))); }
        boolean plated() { return stand.getPersistentDataContainer().has(plateKey, PersistentDataType.BYTE); }
    }

    Submersible(FaultlineDeep pl) {
        this.pl = pl;
        itemKey = new NamespacedKey(pl, "submersible");
        hullKey = new NamespacedKey(pl, "sub_hull");
        plateKey = new NamespacedKey(pl, "sub_plated");
        ownerKey = new NamespacedKey(pl, "sub_owner");
        ShapedRecipe r = new ShapedRecipe(new NamespacedKey(pl, "submersible"), item(100, false));
        r.shape("IGI", "CHC", "III");
        r.setIngredient('I', Material.IRON_BLOCK); r.setIngredient('G', Material.GLASS); r.setIngredient('C', Material.COPPER_BLOCK); r.setIngredient('H', Material.HEART_OF_THE_SEA);
        try { Bukkit.addRecipe(r); } catch (RuntimeException ignored) { } // (already there after a reload)
        for (World w : Bukkit.getWorlds()) for (org.bukkit.Chunk c : w.getLoadedChunks()) for (Entity e : c.getEntities()) adopt(e);
        Bukkit.getScheduler().runTaskTimer(pl, this::tick, 1L, 1L);
    }

    double cfg(String path, double def) { return pl.getConfig().getDouble("submersible." + path, def); }

    ItemStack item(double hull, boolean plated) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + "Submersible");
        List<String> lore = new ArrayList<>(List.of(ChatColor.GRAY + "Right-click water to put it in, right-click it to", ChatColor.GRAY + "climb in. W goes where you look; Jump rises.",
                ChatColor.GRAY + "You breathe inside; bites hit the hull, not you.", ChatColor.AQUA + "Hull: " + (int) hull + "%" + (plated ? ChatColor.LIGHT_PURPLE + "  (Hadal Plating)" : ""),
                ChatColor.DARK_GRAY + (plated ? "Safe at any depth." : "Cracks below y " + (int) cfg("safe-y", -40) + " (the Trench): 4 Netherite Ingots = Hadal Plating."),
                ChatColor.DARK_GRAY + "Iron Ingots patch the hull. Sneak + punch it to pick it up."));
        m.setLore(lore);
        m.setItemModel(new NamespacedKey("faultline", "deep/submersible_icon"));
        m.setMaxStackSize(1);
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        m.getPersistentDataContainer().set(hullKey, PersistentDataType.DOUBLE, hull);
        if (plated) m.getPersistentDataContainer().set(plateKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isItem(ItemStack it) { return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE); }

    static boolean water(Block b) {
        Material m = b.getType();
        return m == Material.WATER || m == Material.BUBBLE_COLUMN || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS || m == Material.KELP || m == Material.KELP_PLANT
                || m == Material.LIGHT && b.getBlockData() instanceof org.bukkit.block.data.Waterlogged wl && wl.isWaterlogged();
    }

    // =========================================================================================== putting it in, finding it again
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !isItem(e.getItem())) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Location at = null;
        for (double d = 1.5; d <= 5; d += 0.5) {
            Location c = p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(d));
            if (water(c.getBlock()) && water(c.clone().add(0, 1, 0).getBlock()) || water(c.getBlock()) && water(c.clone().add(0, -1, 0).getBlock())) { at = c; break; }
        }
        if (at == null) { p.sendActionBar(net.kyori.adventure.text.Component.text("Aim it at deep enough water (2 blocks).", net.kyori.adventure.text.format.NamedTextColor.RED)); return; }
        ItemStack hand = e.getItem();
        PersistentDataContainer c = hand.getItemMeta().getPersistentDataContainer();
        Sub s = spawn(at, p.getLocation().getYaw(), c.getOrDefault(hullKey, PersistentDataType.DOUBLE, 100.0), c.has(plateKey, PersistentDataType.BYTE), p.getUniqueId());
        if (s != null && p.getGameMode() != GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        p.playSound(at, Sound.ITEM_BUCKET_EMPTY, SoundCategory.PLAYERS, 1f, 0.6f);
    }

    Sub spawn(Location at, float yaw, double hull, boolean plated, UUID owner) {
        World w = at.getWorld();
        Location l = at.clone(); l.setYaw(yaw); l.setPitch(0);
        ArmorStand st = w.spawn(l, ArmorStand.class, a -> {
            a.setInvisible(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setBasePlate(false);
            a.setSmall(true); // (its rider sits low, inside the hull)
            a.setPersistent(true);
            a.addScoreboardTag(TAG);
            a.getPersistentDataContainer().set(hullKey, PersistentDataType.DOUBLE, hull);
            if (plated) a.getPersistentDataContainer().set(plateKey, PersistentDataType.BYTE, (byte) 1);
            if (owner != null) a.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
        });
        Sub s = new Sub(st);
        s.yaw = yaw;
        s.body = part(w, l, "deep/submersible");
        s.prop = part(w, l, "deep/submersible__prop");
        st.addPassenger(s.body);
        st.addPassenger(s.prop);
        subs.put(st.getUniqueId(), s);
        pose(s);
        return s;
    }

    ItemDisplay part(World w, Location l, String model) {
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
            d.setTeleportDuration(1);
            d.setInterpolationDuration(1);
            d.setViewRange(2f);
            d.setBrightness(new org.bukkit.entity.Display.Brightness(12, 12));
        });
    }

    /** A sub that comes back with its chunk (it's a saved entity): know it again. */
    void adopt(Entity e) {
        if (!(e instanceof ArmorStand st) || !st.getScoreboardTags().contains(TAG) || subs.containsKey(st.getUniqueId())) return;
        Sub s = new Sub(st);
        for (Entity p : st.getPassengers()) if (p instanceof ItemDisplay d) {
            ItemStack it = d.getItemStack();
            boolean isProp = it != null && it.hasItemMeta() && it.getItemMeta().hasItemModel() && it.getItemMeta().getItemModel().getKey().endsWith("__prop");
            if (isProp) s.prop = d; else s.body = d;
        }
        if (s.body == null) { s.body = part(st.getWorld(), st.getLocation(), "deep/submersible"); st.addPassenger(s.body); }
        if (s.prop == null) { s.prop = part(st.getWorld(), st.getLocation(), "deep/submersible__prop"); st.addPassenger(s.prop); }
        subs.put(st.getUniqueId(), s);
    }

    @EventHandler public void onLoad(EntitiesLoadEvent e) { for (Entity en : e.getEntities()) adopt(en); }

    @EventHandler public void onUnload(EntitiesUnloadEvent e) {
        for (Entity en : e.getEntities()) { Sub s = subs.remove(en.getUniqueId()); if (s != null) { unlight(s); if (s.bed != null && s.bed.isValid()) s.bed.remove(); } }
    }

    Sub of(Entity e) {
        Sub s = subs.get(e.getUniqueId());
        if (s != null) return s;
        if (e.getVehicle() != null) return subs.get(e.getVehicle().getUniqueId());
        for (Sub x : subs.values()) if (e.equals(x.bed)) return x;
        return null;
    }

    /** Is this player inside a sub right now? (Ocean: no pressure, no dark) */
    boolean riding(Player p) { return p.getVehicle() != null && subs.containsKey(p.getVehicle().getUniqueId()); }

    // =========================================================================================== climbing in, patching, picking up
    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractAtEntityEvent e) {
        Sub s = of(e.getRightClicked());
        if (s == null || e.getHand() != EquipmentSlot.HAND) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() == Material.IRON_INGOT && s.hull() < 100) {
            int n = (int) Math.min(hand.getAmount(), Math.ceil((100 - s.hull()) / cfg("repair-per-ingot", 10)));
            hand.setAmount(hand.getAmount() - n);
            s.hull(s.hull() + n * cfg("repair-per-ingot", 10));
            p.playSound(s.stand.getLocation(), Sound.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 0.8f, 1.4f);
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
        if (s.rider() != null) { p.sendActionBar(net.kyori.adventure.text.Component.text("Someone's already inside.", net.kyori.adventure.text.format.NamedTextColor.GRAY)); return; }
        s.stand.addPassenger(p);
        p.playSound(s.stand.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_CLOSE, SoundCategory.PLAYERS, 1f, 0.7f);
        p.sendMessage(ChatColor.YELLOW + "Submersible: " + ChatColor.GRAY + "W goes where you look, S backs up, A/D slide, Jump rises, Sprint is faster. Sneak to climb out.");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHit(EntityDamageByEntityEvent e) {
        Sub s = of(e.getEntity());
        if (s == null || e.getEntity() instanceof Player) return;
        e.setCancelled(true);
        if (!(e.getDamager() instanceof Player p) || !p.isSneaking()) return;
        String owner = s.stand.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (owner != null && !owner.equals(p.getUniqueId().toString()) && !p.hasPermission("faultlinedeep.admin")) {
            p.sendActionBar(net.kyori.adventure.text.Component.text("It isn't yours.", net.kyori.adventure.text.format.NamedTextColor.RED)); return;
        }
        if (s.rider() != null) return;
        ItemStack it = item(s.hull(), s.plated());
        remove(s);
        p.getInventory().addItem(it).values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
        p.playSound(p, Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 1f, 0.8f);
    }

    void remove(Sub s) {
        subs.remove(s.stand.getUniqueId());
        unlight(s);
        for (Entity e : new ArrayList<>(s.stand.getPassengers())) { if (e instanceof Player) s.stand.removePassenger(e); else e.remove(); }
        if (s.bed != null && s.bed.isValid()) s.bed.remove();
        s.stand.remove();
    }

    /** Bites, shots and blasts on someone inside hit the hull instead (a boss's moves still reach you). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onRiderHurt(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !riding(p)) return;
        Sub s = subs.get(p.getVehicle().getUniqueId());
        switch (e.getCause()) {
            case DROWNING, SUFFOCATION -> e.setCancelled(true);
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

    @EventHandler public void onQuit(PlayerQuitEvent e) { if (riding(e.getPlayer())) e.getPlayer().leaveVehicle(); }

    // =========================================================================================== driving
    void tick() {
        ticks++;
        for (Sub s : new ArrayList<>(subs.values())) {
            if (!s.stand.isValid()) { subs.remove(s.stand.getUniqueId()); unlight(s); continue; }
            try {
                Player p = s.rider();
                if (p != null) drive(s, p); else idle(s);
                pose(s);
                if (ticks % 2 == 0) bedrock(s);
            } catch (RuntimeException e) { // one sub's trouble never stops the others (logged at most once a minute)
                if (ticks - lastErr > 1200) { lastErr = ticks; pl.getLogger().warning("[Submersible] " + e); }
            }
        }
    }

    void idle(Sub s) {
        unlight(s);
        s.vel.multiply(0.8);
        Location l = s.stand.getLocation();
        if (!water(l.getBlock()) && !water(l.clone().add(0, -1, 0).getBlock()) && !l.clone().add(0, -1, 0).getBlock().getType().isSolid())
            move(s, l.clone().add(0, -0.3, 0)); // out of the water with nothing under it: it drops
        else if (s.vel.lengthSquared() > 1e-4) move(s, l.clone().add(s.vel));
    }

    void drive(Sub s, Player p) {
        org.bukkit.Input in;
        try { in = p.getCurrentInput(); } catch (RuntimeException | LinkageError e) { in = null; } // (a server without the input API: it just drifts)
        Location eye = p.getEyeLocation();
        float yaw = eye.getYaw(), pitch = eye.getPitch();
        Vector look = eye.getDirection(), flat = new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw))), right = new Vector(-flat.getZ(), 0, flat.getX());
        double sp = in != null && in.isSprint() ? cfg("sprint-speed", 0.55) : cfg("speed", 0.32);
        Vector want = new Vector();
        if (in == null) { /* no input: coast */ }
        else if (in.isForward()) want.add(look.clone().multiply(sp));
        if (in != null && in.isBackward()) want.add(look.clone().multiply(-sp * 0.45));
        if (in != null && in.isLeft()) want.add(right.clone().multiply(-sp * 0.5));
        if (in != null && in.isRight()) want.add(right.clone().multiply(sp * 0.5));
        if (in != null && in.isJump()) want.add(new Vector(0, sp * 0.6, 0));
        s.vel.multiply(0.82).add(want.multiply(0.18));
        Location l = s.stand.getLocation();
        Location n = l.clone().add(s.vel);
        // the water's surface: it can come up to it, never out
        if (!water(n.getBlock())) { if (n.getY() > l.getY()) { n.setY(l.getY()); s.vel.setY(Math.min(0, s.vel.getY())); } }
        move(s, n);
        s.yaw = yaw;
        s.pitch += (Math.max(-40, Math.min(40, pitch * 0.6f)) - s.pitch) * 0.2f;
        s.spin += 6 + s.vel.length() * 120;
        p.setRemainingAir(p.getMaximumAir());
        if (ticks % 40 == 0) p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 100, 0, true, false, false));
        try { light(s, l, look); } catch (RuntimeException ignored) { } // (the headlight is a nicety)
        if (pl.ocean != null && pl.ocean.darkened.contains(p.getUniqueId())) pl.ocean.glowLit.put(p.getUniqueId(), pl.ocean.ticks + 30L);
        // the hull and the depth
        if (ticks % 20 == 0) {
            boolean deep = l.getY() < cfg("safe-y", -40) && !s.plated();
            if (deep) {
                s.hull(s.hull() - cfg("crack-per-second", 3));
                p.playSound(p, Sound.BLOCK_GLASS_HIT, SoundCategory.PLAYERS, 1f, 0.5f);
                l.getWorld().spawnParticle(Particle.BUBBLE, l.clone().add(0, 0.8, 0), 12, 0.6, 0.4, 0.6, 0.05);
                if (s.hull() <= 0) { implode(s); return; }
            }
            ChatColor hc = s.hull() > 60 ? ChatColor.GREEN : s.hull() > 25 ? ChatColor.YELLOW : ChatColor.RED;
            p.sendActionBar(ChatColor.YELLOW + "Submersible " + ChatColor.GRAY + "| Hull " + hc + (int) s.hull() + "%" + ChatColor.GRAY + " | y " + l.getBlockY()
                    + (deep ? ChatColor.RED + " | TOO DEEP: the hull is cracking!" : s.plated() ? ChatColor.LIGHT_PURPLE + " | Hadal Plating" : ""));
        }
        if (ticks % 30 == 0 && s.vel.lengthSquared() > 0.01) l.getWorld().playSound(l, Sound.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 0.5f, 1.8f);
        if (ticks % 3 == 0 && s.vel.lengthSquared() > 0.01) l.getWorld().spawnParticle(Particle.BUBBLE, l.clone().add(flat.clone().multiply(-1.4)), 3, 0.15, 0.15, 0.15, 0.02);
    }

    /** Moves the sub (and whoever rides it) unless the way is blocked (an axis at a time, so it slides along walls). */
    void move(Sub s, Location to) {
        Location l = s.stand.getLocation();
        Vector d = to.toVector().subtract(l.toVector());
        Location n = l.clone();
        for (int axis = 0; axis < 3; axis++) {
            Location t = n.clone().add(axis == 0 ? d.getX() : 0, axis == 1 ? d.getY() : 0, axis == 2 ? d.getZ() : 0);
            if (free(t)) n = t; else { if (axis == 0) s.vel.setX(0); else if (axis == 1) s.vel.setY(0); else s.vel.setZ(0); }
        }
        if (n.distanceSquared(l) < 1e-6) return;
        n.setYaw(s.yaw); n.setPitch(0);
        s.stand.teleport(n, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
    }

    /** Room for the hull (about 2 x 1.2 x 2) at l. */
    static boolean free(Location l) {
        World w = l.getWorld();
        for (double dx : new double[]{-0.8, 0.8}) for (double dz : new double[]{-0.8, 0.8}) for (double dy : new double[]{0.0, 1.2}) {
            Block b = w.getBlockAt(l.clone().add(dx, dy, dz));
            if (b.getType().isSolid()) return false;
        }
        return true;
    }

    void implode(Sub s) {
        Location l = s.stand.getLocation();
        Player p = s.rider();
        World w = l.getWorld();
        w.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 2f, 0.6f);
        w.playSound(l, Sound.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 2f, 0.5f);
        w.spawnParticle(Particle.BUBBLE_POP, l, 120, 1.2, 1, 1.2, 0.2);
        w.spawnParticle(Particle.BLOCK, l, 60, 1, 1, 1, 0, Material.IRON_BLOCK.createBlockData());
        remove(s);
        if (p != null) {
            p.damage(cfg("implode-damage", 10));
            p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "The Submersible's hull gave way!" + ChatColor.GRAY + " (Hadal Plating keeps it whole below y " + (int) cfg("safe-y", -40) + ".)");
        }
        w.dropItemNaturally(l, new ItemStack(Material.IRON_BLOCK, 2));
        w.dropItemNaturally(l, new ItemStack(Material.HEART_OF_THE_SEA, 1));
    }

    // =========================================================================================== the headlight
    void light(Sub s, Location l, Vector look) {
        Location want = null;
        for (double d = 2.5; d >= 1.5; d -= 0.5) {
            Location c = l.clone().add(0, 0.8, 0).add(look.clone().multiply(d));
            Block b = c.getBlock();
            if (b.getType() == Material.WATER && b.getBlockData() instanceof Levelled lv && lv.getLevel() == 0) { want = b.getLocation(); break; }
            if (b.getType() == Material.LIGHT) { want = b.getLocation(); break; }
        }
        if (want != null && want.equals(s.light)) return;
        unlight(s);
        if (want == null) return;
        Block b = want.getBlock();
        s.lightWas = b.getBlockData();
        s.light = want;
        BlockData lightData = Material.LIGHT.createBlockData(bd -> {
            if (bd instanceof Levelled lv) lv.setLevel(15);
            if (bd instanceof org.bukkit.block.data.Waterlogged wl) wl.setWaterlogged(true);
        });
        b.setBlockData(lightData, false);
    }

    void unlight(Sub s) {
        if (s.light == null) return;
        Block b = s.light.getBlock();
        if (b.getType() == Material.LIGHT) b.setBlockData(s.lightWas != null ? s.lightWas : Material.WATER.createBlockData(), false);
        s.light = null; s.lightWas = null;
    }

    // =========================================================================================== drawing
    void pose(Sub s) {
        Quaternionf frame = new Quaternionf().rotationY((float) Math.toRadians(-s.yaw)).rotateX((float) Math.toRadians(s.pitch)).rotateY((float) Math.toRadians(-90));
        Quaternionf flip = new Quaternionf().rotationY((float) Math.PI);
        float sc = (float) cfg("model-scale", 2.0);
        Vector3f lift = new Vector3f(0, (float) cfg("model-lift", 0.9), 0);
        if (s.body != null && s.body.isValid()) {
            Vector3f t = new Vector3f(lift);
            s.body.setInterpolationDelay(0);
            s.body.setTransformation(new Transformation(t, new Quaternionf(frame).mul(flip), new Vector3f(sc, sc, sc), new Quaternionf()));
        }
        if (s.prop != null && s.prop.isValid()) {
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
        for (Sub s : subs.values()) { unlight(s); if (s.bed != null && s.bed.isValid()) s.bed.remove(); Player p = s.rider(); if (p != null) p.leaveVehicle(); }
        subs.clear();
    }

    /** /deep sub [player]: a Submersible for an admin. */
    void give(Player to) {
        to.getInventory().addItem(item(100, false)).values().forEach(x -> to.getWorld().dropItemNaturally(to.getLocation(), x));
    }
}
