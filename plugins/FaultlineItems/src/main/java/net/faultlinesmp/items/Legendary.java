package net.faultlinesmp.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.util.*;

/**
 * LEGENDARY WEAPONS (Items 1.3.38, config legendary:, data legendaries.yml). Three one-of-a-kind weapons: each recipe can
 * be crafted ONCE on the whole server, and only by an admin (items.legendary.craft, op). Admins can also take them from
 * /adminmenu or /legendary give. Item PDC faultlineitems:legendary = id (+ legendary_<id> for the Index).
 *  - STORMBREAKER (mace; mace + 2 breeze rods + 3 wind charges + heavy core): right-click = launch up and slam down with a
 *    wind blast: 2 hearts within 1 block, 1 heart within 2, half a heart within 3-4, nothing past that; everyone hit is
 *    thrown ~4 blocks up. 30 s cooldown. Passive: every mace hit gives you a small hop.
 *  - VOID PIERCER (netherite sword; sword + 4 end crystals + 2 dragon heads + elytra): right-click = blink up to 12 blocks
 *    where you look, a purple blast where you leave and land (3 damage within 3.5), Speed II for 10 s. 35 s cooldown.
 *  - INFERNAL REAPER (netherite sword; sword + 4 nether stars + 2 magma cream + wither skeleton skull): endless Fire
 *    Resistance while you carry it; its hits burn like Fire Aspect III (12 s) and that burn ignores Fire Resistance
 *    (1 a second, generic damage). Right-click = EARTHSHATTER: spikes burst up round you (radius 6), 2 hearts each,
 *    25% to freeze them in place for 1 s. 25 s cooldown.
 */
final class Legendary implements Listener, CommandExecutor, TabCompleter {

    enum Kind {
        STORMBREAKER("stormbreaker", "Stormbreaker", Material.MACE, TextColor.color(0x7FE8FF)),
        VOID_PIERCER("void_piercer", "Void Piercer", Material.NETHERITE_SWORD, TextColor.color(0xB45CFF)),
        INFERNAL_REAPER("infernal_reaper", "Infernal Reaper", Material.NETHERITE_SWORD, TextColor.color(0xFF5A1F));
        final String id, title; final Material base; final TextColor color;
        Kind(String id, String title, Material base, TextColor color) { this.id = id; this.title = title; this.base = base; this.color = color; }
        static Kind of(String s) { for (Kind k : values()) if (k.id.equalsIgnoreCase(s) || k.title.replace(" ", "").equalsIgnoreCase(s.replace(" ", ""))) return k; return null; }
    }

    static final String CRAFT = "items.legendary.craft", ADMIN = "items.legendary.admin", FX_TAG = "faultline_legend_fx";
    private final FaultlineItems plugin;
    private final File file;
    final NamespacedKey key;
    private final Map<Kind, NamespacedKey> recipes = new EnumMap<>(Kind.class);
    private final Map<Kind, String> crafted = new EnumMap<>(Kind.class);
    private final Map<String, Long> cooldowns = new HashMap<>();
    /** Stormbreaker slams in progress: player -> tick it launched. */
    private final Map<UUID, Long> slamming = new HashMap<>();
    /** No fall damage until this tick (after a slam). */
    private final Map<UUID, Long> safeFall = new HashMap<>();
    /** Infernal burn: victim -> [until tick, attacker uuid msb, lsb]. */
    private final Map<UUID, long[]> burning = new HashMap<>();
    /** Frozen by Earthshatter: entity -> where, until tick. */
    private final Map<UUID, Object[]> frozen = new HashMap<>();
    private final List<Object[]> fx = new ArrayList<>(); // [BlockDisplay, removeAt]
    private long tick;
    private boolean hitting;

    Legendary(FaultlineItems plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "legendaries.yml");
        key = new NamespacedKey(plugin, "legendary");
        load();
        addRecipes();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(FX_TAG)) e.remove();
        });
    }

    private boolean on() { return plugin.getConfig().getBoolean("legendary.enabled", true); }
    private double cfg(String path, double def) { return plugin.getConfig().getDouble("legendary." + path, def); }

    // ================================================================= data

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = SafeYaml.load(plugin, file).yaml;
        for (Kind k : Kind.values()) { String by = y.getString("crafted." + k.id + ".by"); if (by != null) crafted.put(k, by); }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : crafted.entrySet()) { y.set("crafted." + e.getKey().id + ".by", e.getValue()); }
        SafeYaml.write(plugin, file, y.saveToString());
    }

    // ================================================================= items + recipes

    ItemStack item(Kind k) {
        ItemStack it = new ItemStack(k.base);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text(k.title, k.color).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(line("LEGENDARY: only one exists.", NamedTextColor.GOLD));
        switch (k) {
            case STORMBREAKER -> {
                lore.add(line("Right-click: leap up and slam down", NamedTextColor.AQUA));
                lore.add(line("with a huge wind blast (4 blocks).", NamedTextColor.AQUA));
                lore.add(line("Up close 2 hearts, 1 block out 1 heart,", NamedTextColor.GRAY));
                lore.add(line("further half a heart. Throws them up.", NamedTextColor.GRAY));
                lore.add(line("Passive: every hit gives you a little hop.", NamedTextColor.GRAY));
                lore.add(line("Cooldown " + (int) cfg("stormbreaker.cooldown-seconds", 30) + "s", NamedTextColor.DARK_GRAY));
                m.addEnchant(Enchantment.UNBREAKING, 3, true);
                m.addEnchant(Enchantment.MENDING, 1, true);
                m.addEnchant(Enchantment.DENSITY, 1, true);
            }
            case VOID_PIERCER -> {
                lore.add(line("Right-click: blink where you look", NamedTextColor.LIGHT_PURPLE));
                lore.add(line("in a purple blast, then Speed II for 10s.", NamedTextColor.LIGHT_PURPLE));
                lore.add(line("Cooldown " + (int) cfg("void-piercer.cooldown-seconds", 35) + "s", NamedTextColor.DARK_GRAY));
                m.addEnchant(Enchantment.SHARPNESS, 5, true);
                m.addEnchant(Enchantment.UNBREAKING, 3, true);
                m.addEnchant(Enchantment.MENDING, 1, true);
            }
            case INFERNAL_REAPER -> {
                lore.add(line("Fire Resistance while you carry it.", NamedTextColor.GOLD));
                lore.add(line("Fire Aspect III that burns through", NamedTextColor.RED));
                lore.add(line("Fire Resistance.", NamedTextColor.RED));
                lore.add(line("Right-click: EARTHSHATTER. Spikes burst up", NamedTextColor.YELLOW));
                lore.add(line("round you (2 hearts), 1 in 4 frozen for 1s.", NamedTextColor.YELLOW));
                lore.add(line("Cooldown " + (int) cfg("infernal-reaper.cooldown-seconds", 25) + "s", NamedTextColor.DARK_GRAY));
                m.addEnchant(Enchantment.SHARPNESS, 5, true);
                m.addEnchant(Enchantment.UNBREAKING, 3, true);
                m.addEnchant(Enchantment.MENDING, 1, true);
            }
        }
        m.lore(lore);
        m.setUnbreakable(true);
        m.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        m.getPersistentDataContainer().set(key, PersistentDataType.STRING, k.id);
        m.getPersistentDataContainer().set(new NamespacedKey(plugin, "legendary_" + k.id), PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private static Component line(String s, TextColor c) { return Component.text(s, c).decoration(TextDecoration.ITALIC, false); }

    Kind kind(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        String id = it.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        return id == null ? null : Kind.of(id);
    }

    /** 2.0: the legendary weapons can't be crafted any more (the owner hands them out from /adminmenu or /legendary give).
     *  Their old recipes are taken out of the server (a /faultline reload of an older jar left them registered). */
    private void addRecipes() {
        for (Kind k : Kind.values()) {
            NamespacedKey rk = new NamespacedKey(plugin, "legendary_" + k.id + "_recipe");
            recipes.put(k, rk);
            try { Bukkit.removeRecipe(rk); } catch (RuntimeException ignored) { }
        }
    }

    private Kind recipeKind(Recipe r) {
        if (!(r instanceof Keyed kd)) return null;
        for (var e : recipes.entrySet()) if (e.getValue().equals(kd.getKey())) return e.getKey();
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepare(PrepareItemCraftEvent e) {
        Kind k = recipeKind(e.getRecipe());
        if (k == null) return;
        boolean ok = on() && e.getView().getPlayer().hasPermission(CRAFT) && !crafted.containsKey(k);
        for (ItemStack it : e.getInventory().getMatrix()) if (guarded(it)) ok = false; // never a legendary into a legendary
        e.getInventory().setResult(ok ? item(k) : null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(CraftItemEvent e) {
        Kind k = recipeKind(e.getRecipe());
        if (k == null) return;
        boolean bad = false;
        for (ItemStack it : e.getInventory().getMatrix()) if (guarded(it)) bad = true;
        if (bad || !(e.getWhoClicked() instanceof Player p) || !on() || !p.hasPermission(CRAFT) || crafted.containsKey(k)) {
            e.setCancelled(true);
            e.getInventory().setResult(null);
            if (e.getWhoClicked() instanceof Player p2) p2.sendMessage(ChatColor.RED + (crafted.containsKey(k) ? k.title + " has already been crafted. There is only one." : "Only admins can forge legendary weapons."));
            return;
        }
        if (e.isShiftClick()) { // one, never a stack (the cursor path below)
            e.setCancelled(true);
            p.sendMessage(ChatColor.YELLOW + "Click the result (no shift) to forge it.");
            return;
        }
        if (e.getCursor() != null && !e.getCursor().getType().isAir()) { e.setCancelled(true); return; }
        crafted.put(k, p.getName() + " (" + p.getUniqueId() + ") " + new java.util.Date());
        save();
        e.setCurrentItem(item(k));
        Bukkit.broadcastMessage(ChatColor.GOLD + "⚔ " + ChatColor.YELLOW + p.getName() + " has forged " + ChatColor.BOLD + k.title + ChatColor.YELLOW + ". The only one there will ever be.");
        p.getWorld().playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1, 1);
        plugin.getLogger().info("[Legendary] " + p.getName() + " crafted " + k.id);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCrafter(org.bukkit.event.block.CrafterCraftEvent e) {
        if (recipeKind(e.getRecipe()) != null) { e.setCancelled(true); return; }
        if (e.getBlock().getState(false) instanceof org.bukkit.block.Crafter c)
            for (ItemStack it : c.getInventory().getContents()) if (guarded(it)) { e.setCancelled(true); return; }
    }

    /** 1.3.39: our one-of-a-kind items are never a crafting ingredient (a legendary into another legendary or the mace
     *  repair recipe, the Chrono Spine into a spyglass, the Overclocked Chip into a recovery compass...). */
    boolean guarded(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return false;
        var pdc = it.getPersistentDataContainer();
        return pdc.has(key, PersistentDataType.STRING)
                || pdc.has(new NamespacedKey(plugin, "chrono_spine"), PersistentDataType.BYTE)
                || pdc.has(new NamespacedKey(plugin, "overclocked_chip"), PersistentDataType.BYTE)
                || pdc.has(new NamespacedKey(plugin, "golden_plunger"), PersistentDataType.BYTE)
                || pdc.has(new NamespacedKey(plugin, "rim_sigil"), PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onGuard(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) if (guarded(it)) { e.getInventory().setResult(null); return; }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onGuardCraft(CraftItemEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) if (guarded(it)) { e.setCancelled(true); return; }
    }

    // ================================================================= cooldowns

    private boolean ready(Player p, Kind k, double seconds) {
        String c = p.getUniqueId() + ":" + k.id;
        Long until = cooldowns.get(c);
        if (until != null && until > tick) {
            p.sendActionBar(ChatColor.RED + k.title + ": " + String.format(Locale.ROOT, "%.1f", (until - tick) / 20.0) + "s");
            return false;
        }
        cooldowns.put(c, tick + (long) (seconds * 20));
        return true;
    }

    // ================================================================= abilities

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !on()) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Kind k = kind(e.getItem());
        if (k == null) return;
        Player p = e.getPlayer();
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null && e.getClickedBlock().getType().isInteractable() && !p.isSneaking()) return;
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        e.setCancelled(true);
        switch (k) {
            case STORMBREAKER -> { if (!slamming.containsKey(p.getUniqueId()) && ready(p, k, cfg("stormbreaker.cooldown-seconds", 30))) launch(p); }
            case VOID_PIERCER -> { if (ready(p, k, cfg("void-piercer.cooldown-seconds", 35))) blink(p); }
            case INFERNAL_REAPER -> { if (ready(p, k, cfg("infernal-reaper.cooldown-seconds", 25))) earthshatter(p); }
        }
    }

    private void launch(Player p) {
        slamming.put(p.getUniqueId(), tick);
        safeFall.put(p.getUniqueId(), tick + 200);
        Safe.vel(p, p.getLocation().getDirection().setY(0).multiply(0.4).setY(cfg("stormbreaker.launch", 1.5)));
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BREEZE_JUMP, 1.5f, 0.8f);
        p.getWorld().spawnParticle(Particle.GUST, p.getLocation(), 3, 0.4, 0.1, 0.4, 0);
    }

    /** Called every tick for a slamming player: after the peak, dive; on the ground (or water, or 6 s) = the blast. */
    private void slamTick(Player p, long started) {
        long t = tick - started;
        if (t > 8 && p.getVelocity().getY() < 0.1 && p.getVelocity().getY() > -1.6) Safe.vel(p, p.getVelocity().setY(-1.6));
        if (t % 2 == 0) p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 2, 0.2, 0.2, 0.2, 0.01);
        boolean down = t > 5 && (((org.bukkit.entity.Entity) p).isOnGround() || p.isInWater() || p.isGliding());
        if (down || t > 120) { slamming.remove(p.getUniqueId()); blast(p); }
    }

    private void blast(Player p) {
        Location at = p.getLocation();
        World w = at.getWorld();
        safeFall.put(p.getUniqueId(), tick + 10);
        w.playSound(at, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 2.5f, 0.6f);
        w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1.3f);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
        w.spawnParticle(Particle.GUST_EMITTER_LARGE, at, 1);
        for (int i = 0; i < 48; i++) {
            double a = Math.PI * 2 * i / 48;
            w.spawnParticle(Particle.CLOUD, at.clone().add(Math.cos(a) * 4, 0.2, Math.sin(a) * 4), 1, 0, 0.05, 0, 0.02);
        }
        double reach = cfg("stormbreaker.radius", 4);
        for (Entity e : w.getNearbyEntities(at, reach + 1, reach + 1, reach + 1)) {
            if (!(e instanceof LivingEntity le) || e == p || e instanceof ArmorStand || le.isInvisible() || le.isDead()) continue;
            if (e instanceof Player o && (o.getGameMode() == GameMode.CREATIVE || o.getGameMode() == GameMode.SPECTATOR)) continue;
            if (e instanceof Tameable t && p.equals(t.getOwner())) continue;
            double d = le.getLocation().distance(at);
            if (d > reach + 0.5) continue;
            double dmg = d <= 1.5 ? cfg("stormbreaker.damage-1", 4) : d <= 2.5 ? cfg("stormbreaker.damage-2", 2) : cfg("stormbreaker.damage-far", 1);
            hitting = true;
            try { le.damage(dmg, p); } finally { hitting = false; }
            Vector away = le.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (away.lengthSquared() < 1e-4) away = new Vector(0, 0, 0); else away.normalize().multiply(0.35);
            Vector v = away.setY(cfg("stormbreaker.throw", 0.95));
            Bukkit.getScheduler().runTask(plugin, () -> { if (le.isValid()) Safe.vel(le, v); });
        }
    }

    private void blink(Player p) {
        Location from = p.getLocation();
        World w = from.getWorld();
        Vector dir = p.getEyeLocation().getDirection().normalize();
        double max = cfg("void-piercer.distance", 12);
        double dist = max; // walk the look line until something solid (eye height and feet height)
        for (double d = 0.25; d <= max; d += 0.25) {
            Location eye = p.getEyeLocation().add(dir.clone().multiply(d)), feet = from.clone().add(dir.clone().multiply(d)).add(0, 0.1, 0);
            if (eye.getBlock().getType().isSolid() || feet.getBlock().getType().isSolid()) { dist = Math.max(0, d - 0.8); break; }
        }
        Location to = null;
        for (double d = dist; d >= 1; d -= 0.5) {
            Location c = from.clone().add(dir.clone().multiply(d));
            if (Emotes.open(c.getBlock()) && Emotes.open(c.clone().add(0, 1, 0).getBlock())) { to = c; break; }
        }
        if (to == null) { p.sendActionBar(ChatColor.RED + "No room to blink."); cooldowns.remove(p.getUniqueId() + ":" + Kind.VOID_PIERCER.id); return; }
        to.setYaw(from.getYaw()); to.setPitch(from.getPitch());
        purple(p, from);
        p.teleport(to, io.papermc.paper.entity.TeleportFlag.EntityState.RETAIN_PASSENGERS);
        p.setFallDistance(0);
        purple(p, to);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, (int) (cfg("void-piercer.speed-seconds", 10) * 20), 1));
    }

    private static final Particle.DustOptions PURPLE = new Particle.DustOptions(Color.fromRGB(0xA040FF), 2.2f);

    private void purple(Player p, Location at) {
        World w = at.getWorld();
        w.playSound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 1.5f, 0.6f);
        w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.6f);
        w.spawnParticle(Particle.DUST, at.clone().add(0, 1, 0), 60, 1.4, 1, 1.4, 0, PURPLE);
        w.spawnParticle(Particle.REVERSE_PORTAL, at.clone().add(0, 1, 0), 80, 0.6, 0.8, 0.6, 0.3);
        w.spawnParticle(Particle.WITCH, at.clone().add(0, 1, 0), 30, 1, 1, 1, 0.1);
        double r = cfg("void-piercer.blast-radius", 3.5);
        for (Entity e : w.getNearbyEntities(at, r, r, r)) {
            if (!(e instanceof LivingEntity le) || e == p || e instanceof ArmorStand || le.isInvisible()) continue;
            if (e instanceof Player o && (o.getGameMode() == GameMode.CREATIVE || o.getGameMode() == GameMode.SPECTATOR)) continue;
            if (e instanceof Tameable t && p.equals(t.getOwner())) continue;
            hitting = true;
            try { le.damage(cfg("void-piercer.blast-damage", 3), p); } finally { hitting = false; }
        }
    }

    private void earthshatter(Player p) {
        Location at = p.getLocation();
        World w = at.getWorld();
        w.playSound(at, Sound.ENTITY_WARDEN_SONIC_BOOM, 0.8f, 1.6f);
        w.playSound(at, Sound.BLOCK_POINTED_DRIPSTONE_FALL, 2f, 0.5f);
        double radius = cfg("infernal-reaper.spike-radius", 6);
        for (int ring = 1; ring <= (int) radius; ring++) {
            int rr = ring;
            Bukkit.getScheduler().runTaskLater(plugin, () -> spikeRing(at, rr), ring);
        }
        Set<UUID> hit = new HashSet<>();
        for (Entity e : w.getNearbyEntities(at, radius, 3, radius)) {
            if (!(e instanceof LivingEntity le) || e == p || e instanceof ArmorStand || le.isInvisible() || !hit.add(e.getUniqueId())) continue;
            if (e instanceof Player o && (o.getGameMode() == GameMode.CREATIVE || o.getGameMode() == GameMode.SPECTATOR)) continue;
            if (e instanceof Tameable t && p.equals(t.getOwner())) continue;
            double d = Math.hypot(e.getLocation().getX() - at.getX(), e.getLocation().getZ() - at.getZ());
            if (d > radius) continue;
            int delay = Math.max(1, (int) d);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!le.isValid() || !p.isOnline()) return;
                hitting = true;
                try { le.damage(cfg("infernal-reaper.spike-damage", 4), p); } finally { hitting = false; }
                Safe.vel(le, new Vector(0, 0.35, 0));
                if (Math.random() < cfg("infernal-reaper.freeze-chance", 0.25)) {
                    frozen.put(le.getUniqueId(), new Object[]{le.getLocation(), tick + (long) (cfg("infernal-reaper.freeze-seconds", 1) * 20)});
                    le.getWorld().spawnParticle(Particle.SNOWFLAKE, le.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
                    if (le instanceof Player o) o.sendActionBar(ChatColor.AQUA + "Frozen!");
                }
            }, delay + 2);
        }
    }

    private void spikeRing(Location c, int r) {
        World w = c.getWorld();
        int n = Math.max(6, r * 7);
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n + Math.random() * 0.3;
            Location l = c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r);
            Block ground = null;
            for (int dy = 2; dy >= -3; dy--) {
                Block b = l.clone().add(0, dy, 0).getBlock();
                if (b.getType().isSolid() && !b.getRelative(0, 1, 0).getType().isSolid()) { ground = b; break; }
            }
            if (ground == null) continue;
            Location base = ground.getLocation().add(0.5, 1, 0.5);
            BlockData bd = Math.random() < 0.5 ? Material.BASALT.createBlockData() : Material.BLACKSTONE.createBlockData();
            w.spawnParticle(Particle.BLOCK, base, 8, 0.3, 0.1, 0.3, 0, ground.getBlockData());
            w.spawnParticle(Particle.FLAME, base, 4, 0.2, 0.2, 0.2, 0.02);
            float h = 1.2f + (float) Math.random() * 1.2f, wdt = 0.35f + (float) Math.random() * 0.2f;
            BlockDisplay d = (BlockDisplay) w.spawnEntity(base, EntityType.BLOCK_DISPLAY, false);
            d.addScoreboardTag(FX_TAG);
            d.setPersistent(false);
            d.setBlock(bd);
            Quaternionf tilt = new Quaternionf().rotateY((float) a).rotateX((float) ((Math.random() - 0.5) * 0.4));
            d.setTransformation(new Transformation(new Vector3f(-wdt / 2, -h, -wdt / 2), tilt, new Vector3f(wdt, h, wdt), new Quaternionf()));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!d.isValid()) return;
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(3);
                d.setTransformation(new Transformation(new Vector3f(-wdt / 2, 0, -wdt / 2), tilt, new Vector3f(wdt, h, wdt), new Quaternionf()));
            }, 1);
            fx.add(new Object[]{d, tick + 30});
        }
        w.playSound(c.clone().add(r, 0, 0), Sound.BLOCK_BASALT_BREAK, 1.2f, 0.6f);
    }

    // ================================================================= hits

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (hitting || !on() || !(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity le)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK && e.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;
        Kind k = kind(p.getInventory().getItemInMainHand());
        if (k == Kind.STORMBREAKER && e.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            double hop = cfg("stormbreaker.hop", 0.42);
            Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) Safe.vel(p, p.getVelocity().setY(Math.max(p.getVelocity().getY(), hop))); });
        } else if (k == Kind.INFERNAL_REAPER) {
            int secs = (int) cfg("infernal-reaper.burn-seconds", 12);
            le.setFireTicks(Math.max(le.getFireTicks(), secs * 20));
            burning.put(le.getUniqueId(), new long[]{tick + secs * 20L, p.getUniqueId().getMostSignificantBits(), p.getUniqueId().getLeastSignificantBits()});
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p)) return;
        Long until = safeFall.get(p.getUniqueId());
        if (until != null && until >= tick) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        slamming.remove(id); safeFall.remove(id); frozen.remove(id);
    }

    // ================================================================= tick

    private void tick() {
        tick++;
        try {
            for (UUID id : new ArrayList<>(slamming.keySet())) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || p.isDead()) { slamming.remove(id); continue; }
                slamTick(p, slamming.get(id));
            }
            for (var it = frozen.entrySet().iterator(); it.hasNext(); ) {
                var en = it.next();
                Entity e = Bukkit.getEntity(en.getKey());
                if (e == null || !e.isValid() || (long) en.getValue()[1] < tick) { it.remove(); continue; }
                Location hold = (Location) en.getValue()[0];
                if (e.getWorld() != hold.getWorld()) { it.remove(); continue; }
                if (e.getLocation().distanceSquared(hold) > 0.01) {
                    Location to = hold.clone(); to.setYaw(e.getLocation().getYaw()); to.setPitch(e.getLocation().getPitch());
                    e.teleport(to);
                }
                Safe.vel(e, new Vector(0, 0, 0));
            }
            for (var it = fx.iterator(); it.hasNext(); ) {
                Object[] f = it.next();
                BlockDisplay d = (BlockDisplay) f[0];
                if ((long) f[1] <= tick || !d.isValid()) { if (d.isValid()) d.remove(); it.remove(); }
            }
            if (tick % 20 == 0) {
                for (var it = burning.entrySet().iterator(); it.hasNext(); ) {
                    var en = it.next();
                    Entity e = Bukkit.getEntity(en.getKey());
                    if (!(e instanceof LivingEntity le) || !le.isValid() || le.isDead() || en.getValue()[0] < tick || le.isInWater()) { it.remove(); continue; }
                    // Fire Aspect III that ignores Fire Resistance: plain damage a second while it lasts (no knockback)
                    if (le.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE) || le.getFireTicks() <= 0) {
                        le.setFireTicks(Math.max(le.getFireTicks(), 40));
                        hitting = true;
                        try { le.damage(cfg("infernal-reaper.burn-damage", 1)); } finally { hitting = false; }
                    }
                }
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getGameMode() == GameMode.SPECTATOR || !carries(p, Kind.INFERNAL_REAPER)) continue;
                    PotionEffect cur = p.getPotionEffect(PotionEffectType.FIRE_RESISTANCE);
                    if (cur == null || (cur.getDuration() != PotionEffect.INFINITE_DURATION && cur.getDuration() < 60))
                        p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 80, 0, true, false, true));
                }
                if (tick % 1200 == 0) { cooldowns.values().removeIf(t -> t < tick); safeFall.values().removeIf(t -> t < tick); }
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[Legendary] tick: " + ex);
        }
    }

    boolean carries(Player p, Kind k) {
        for (ItemStack it : p.getInventory().getContents()) if (kind(it) == k) return true;
        return kind(p.getItemOnCursor()) == k;
    }

    void disable() {
        for (Object[] f : fx) { BlockDisplay d = (BlockDisplay) f[0]; if (d.isValid()) d.remove(); }
        fx.clear();
    }

    // ================================================================= /legendary

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission(ADMIN)) { s.sendMessage(ChatColor.RED + "Admins only."); return true; }
        String sub = a.length == 0 ? "list" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                s.sendMessage(ChatColor.GOLD + "Legendary weapons (each can be crafted once):");
                for (Kind k : Kind.values())
                    s.sendMessage(ChatColor.YELLOW + " " + k.title + ChatColor.GRAY + " (" + k.id + "): " + (crafted.containsKey(k) ? ChatColor.RED + "crafted by " + crafted.get(k) : ChatColor.GREEN + "not crafted yet"));
            }
            case "give" -> {
                if (a.length < 2 || Kind.of(a[1]) == null) { s.sendMessage(ChatColor.RED + "/legendary give <" + ids() + "> [player]"); return true; }
                Player t = a.length >= 3 ? Bukkit.getPlayerExact(a[2]) : s instanceof Player p ? p : null;
                if (t == null) { s.sendMessage(ChatColor.RED + "Player not found."); return true; }
                Kind k = Kind.of(a[1]);
                for (ItemStack left : t.getInventory().addItem(item(k)).values()) t.getWorld().dropItem(t.getLocation(), left);
                s.sendMessage(ChatColor.GREEN + "Gave " + k.title + " to " + t.getName() + ".");
                plugin.getLogger().info("[Legendary] " + s.getName() + " gave " + k.id + " to " + t.getName());
            }
            case "reset" -> {
                if (a.length < 2 || Kind.of(a[1]) == null) { s.sendMessage(ChatColor.RED + "/legendary reset <" + ids() + ">"); return true; }
                Kind k = Kind.of(a[1]);
                crafted.remove(k);
                save();
                s.sendMessage(ChatColor.GREEN + k.title + " can be crafted again (once).");
            }
            default -> s.sendMessage(ChatColor.RED + "/legendary [list | give <id> [player] | reset <id>]");
        }
        return true;
    }

    private static String ids() { StringJoiner j = new StringJoiner("|"); for (Kind k : Kind.values()) j.add(k.id); return j.toString(); }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission(ADMIN)) return List.of();
        if (a.length == 1) return filter(List.of("list", "give", "reset"), a[0]);
        if (a.length == 2 && (a[0].equalsIgnoreCase("give") || a[0].equalsIgnoreCase("reset"))) {
            List<String> l = new ArrayList<>(); for (Kind k : Kind.values()) l.add(k.id); return filter(l, a[1]);
        }
        if (a.length == 3 && a[0].equalsIgnoreCase("give")) { List<String> l = new ArrayList<>(); for (Player p : Bukkit.getOnlinePlayers()) l.add(p.getName()); return filter(l, a[2]); }
        return List.of();
    }

    static int parseInt(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (RuntimeException e) { return def; } }

    static List<String> filter(List<String> l, String pre) {
        List<String> out = new ArrayList<>();
        for (String s : l) if (s.toLowerCase(Locale.ROOT).startsWith(pre.toLowerCase(Locale.ROOT))) out.add(s);
        return out;
    }
}
