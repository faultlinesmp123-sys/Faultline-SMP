package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Spider;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Items 1.3.47.
 * QUEEN'S SILK, carried anywhere in your inventory (or being a spider): COBWEBS DON'T HOLD YOU. A web you walk into parts
 * for you (it's taken out and woven back 2 s after you've left it, so nothing rubber-bands and no web is lost), webs you break
 * give 1-2 extra string, and the silk's shot weaves a bigger web.
 * THE BROODMOTHER'S EYE (accessory, `/giveaccessory broodeye`, 2.5% from the Queen Spider): Shift + F = SPIDER FORM until
 * something hits you: you shrink to a spider and the Queen's brood rig walks for you (others see a spider, never you), you
 * climb walls (walk into one), webs don't hold you, no fall damage, Night Vision, your bites poison. Any hit from a mob,
 * a player or a projectile knocks you back out (Shift + F also does). 20 s before you can change again.
 */
final class SpiderForm implements Listener {

    final FaultlineItems plugin;
    final NamespacedKey eyeKey, scaleKey;

    private static final class Form {
        QueenRig rig;
        Spider standin;
        long since;
    }

    private final Map<UUID, Form> forms = new HashMap<>();

    void liveIds(java.util.Set<UUID> live) { // 1.3.51: player spider-form rigs aren't leftovers
        for (Form f : forms.values()) if (f.rig != null) for (org.bukkit.entity.Entity e : f.rig.parts) live.add(e.getUniqueId());
    }
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final Set<UUID> silkCarriers = new HashSet<>();
    private final Map<Block, Long> parted = new HashMap<>(); // webs parted for a carrier -> when they were last in it
    /** Webs that are meant to vanish on their own (the silk shot's, the Queen's traps): parted for good, never woven back. */
    static final Set<Block> TEMP = new HashSet<>();
    private long ticks;

    SpiderForm(FaultlineItems plugin) {
        this.plugin = plugin;
        this.eyeKey = new NamespacedKey(plugin, "brood_eye");
        this.scaleKey = new NamespacedKey(plugin, "spider_form_scale");
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try { tick(); } catch (RuntimeException e) { plugin.getLogger().warning("[SpiderForm] " + e); }
        }, 1L, 1L);
    }

    ItemStack item() {
        return FaultlineItems.NewAccessoryItems.make(plugin, Material.SPIDER_EYE, ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Broodmother's Eye", eyeKey,
                ChatColor.GREEN + "Shift + F: become a spider",
                ChatColor.GRAY + "Climb walls, walk through webs,",
                ChatColor.GRAY + "see in the dark, poison bites, no fall damage.",
                ChatColor.RED + "Anything that hits you turns you back.",
                ChatColor.DARK_GRAY + "Taken from the Queen Spider (2.5%).");
    }

    /** A web parted for someone walking through (it'll be woven back): it isn't really gone. */
    boolean parted(Block b) { return parted.containsKey(b); }

    boolean isSpider(Player p) { return forms.containsKey(p.getUniqueId()); }

    // ============================================================================================ Queen's Silk: webs part for you
    boolean webproof(Player p) { return isSpider(p) || silkCarriers.contains(p.getUniqueId()); }

    private boolean carriesSilk(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (FaultlineItems.QueenSilkItem.isSilk(plugin, it)) return true;
        return false;
    }

    private void tick() {
        ticks++;
        if (ticks % 20 == 0) {
            silkCarriers.clear();
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getGameMode() != GameMode.SPECTATOR && carriesSilk(p)) silkCarriers.add(p.getUniqueId());
        }
        long now = ticks;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!webproof(p)) continue;
            Location l = p.getLocation();
            double half = p.getWidth() / 2 + 0.05;
            for (double dx : new double[]{-half, half}) for (double dz : new double[]{-half, half}) for (double dy : new double[]{0.1, p.getHeight() * 0.5, p.getHeight() - 0.1}) {
                Block b = l.getWorld().getBlockAt((int) Math.floor(l.getX() + dx), (int) Math.floor(l.getY() + dy), (int) Math.floor(l.getZ() + dz));
                if (b.getType() == Material.COBWEB && TEMP.contains(b)) { b.setType(Material.AIR, false); continue; }
                if (b.getType() == Material.COBWEB && !parted.containsKey(b)) {
                    parted.put(b, now);
                    b.setType(Material.AIR, false);
                    b.getWorld().spawnParticle(Particle.ITEM, b.getLocation().add(0.5, 0.5, 0.5), 4, 0.25, 0.25, 0.25, 0.02, new ItemStack(Material.COBWEB));
                } else if (parted.containsKey(b)) parted.put(b, now);
            }
        }
        if (ticks % 5 == 0) parted.entrySet().removeIf(e -> {
            if (now - e.getValue() < 40) return false;
            Block b = e.getKey();
            for (Player p : b.getWorld().getPlayers()) {
                Location l = p.getLocation();
                if (Math.abs(l.getX() - b.getX() - 0.5) < 0.9 && Math.abs(l.getZ() - b.getZ() - 0.5) < 0.9 && l.getY() < b.getY() + 1.2 && l.getY() + p.getHeight() > b.getY() - 0.2) return false;
            }
            if (b.getType().isAir()) b.setType(Material.COBWEB, false); // woven back
            return true;
        });
        // the spiders
        for (UUID id : new ArrayList<>(forms.keySet())) {
            Player p = Bukkit.getPlayer(id);
            Form f = forms.get(id);
            if (p == null || !p.isOnline()) { drop(id, f); continue; }
            if (!plugin.getAccessoryManager().hasEquipped(p, eyeKey)) { end(p, ChatColor.GRAY + "Without the Eye you can't stay a spider."); continue; }
            f.rig.tick();
            if (f.standin != null && f.standin.isValid() && ticks % 2 == 0) f.standin.teleport(p.getLocation());
            p.setFallDistance(0);
            climb(p);
            if (ticks % 40 == 0) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 400, 0, true, false, false));
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 400, 0, true, false, false));
                hideGear(p);
            }
        }
        if (ticks % 200 == 0) cooldown.values().removeIf(t -> t < System.currentTimeMillis());
    }

    /** Walk into a wall = climb it (sneak = cling). */
    private void climb(Player p) {
        Location l = p.getLocation();
        Vector look = l.getDirection().setY(0);
        if (look.lengthSquared() < 1e-4) return;
        look.normalize().multiply(0.55);
        Block feet = l.clone().add(look).getBlock(), head = l.clone().add(look).add(0, 0.9, 0).getBlock();
        boolean wall = feet.getType().isSolid() || head.getType().isSolid();
        if (!wall) return;
        boolean forward;
        try { forward = p.getCurrentInput().isForward(); } catch (Throwable e) { forward = p.isSprinting(); }
        if (forward) Safe.vel(p, new Vector(p.getVelocity().getX() * 0.5, 0.22, p.getVelocity().getZ() * 0.5));
        else if (p.isSneaking()) Safe.vel(p, new Vector(0, 0, 0));
        else return;
        if (ticks % 6 == 0) p.getWorld().playSound(l, Sound.ENTITY_SPIDER_STEP, 0.4f, 1.4f);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWebBreak(BlockBreakEvent e) {
        if (e.getBlock().getType() != Material.COBWEB || !webproof(e.getPlayer()) || e.getPlayer().getGameMode() == GameMode.CREATIVE) return;
        e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().add(0.5, 0.5, 0.5), new ItemStack(Material.STRING, 1 + (int) (Math.random() * 2)));
    }

    // ============================================================================================ spider form
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (!p.isSneaking()) return;
        if (!isSpider(p)) {
            if (!plugin.getAccessoryManager().hasEquipped(p, eyeKey)) return;
            if (plugin.getAccessoryManager().hasEquipped(p, plugin.getMoonStoneKey())) return; // the Moon Stone's bat form wins
        }
        e.setCancelled(true);
        toggle(p);
    }

    void toggle(Player p) {
        if (isSpider(p)) { end(p, ChatColor.GRAY + "You change back."); return; }
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) { p.sendMessage(ChatColor.RED + "Spider form only works in survival."); return; }
        if (plugin.getBatFormManager() != null && plugin.getBatFormManager().isBat(p)) { p.sendMessage(ChatColor.RED + "You're a bat right now."); return; }
        long left = cooldown.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis();
        if (left > 0) { p.sendMessage(ChatColor.RED + "You can't become a spider again for " + (left + 999) / 1000 + "s."); return; }
        start(p);
    }

    void start(Player p) {
        Form f = new Form();
        f.since = ticks;
        AttributeInstance sc = p.getAttribute(Attribute.SCALE);
        if (sc != null) {
            sc.getModifiers().stream().filter(m -> m.getKey().equals(scaleKey)).toList().forEach(sc::removeModifier);
            sc.addModifier(new AttributeModifier(scaleKey, plugin.getConfig().getDouble("spider-form.scale", 0.55) - 1, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 400, 0, true, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 400, 0, true, false, false));
        f.rig = new QueenRig(plugin, p, plugin.getConfig().getDouble("spider-form.rig-scale", 1.5), "queen/", -1);
        f.rig.play("wake", 20);
        for (ItemDisplay d : f.rig.parts) p.hideEntity(plugin, d); // not in your own face
        try {
            if (p.getWorld().spawnEntity(p.getLocation(), EntityType.SPIDER, false) instanceof Spider s2) { // what Bedrock players see
                s2.addScoreboardTag("faultline_spider_form_standin");
                s2.setPersistent(false); s2.setAI(false); s2.setSilent(true); s2.setCollidable(false); s2.setInvulnerable(true);
                s2.setVisibleByDefault(false);
                AttributeInstance a = s2.getAttribute(Attribute.SCALE);
                if (a != null) a.setBaseValue(0.8);
                for (Player o : p.getWorld().getPlayers()) if (o != p && Edition.bedrock(o)) o.showEntity(plugin, s2);
                f.standin = s2;
            }
        } catch (RuntimeException ignored) { }
        forms.put(p.getUniqueId(), f);
        hideGear(p);
        Location l = p.getLocation().add(0, 0.4, 0);
        l.getWorld().spawnParticle(Particle.DUST, l, 30, 0.4, 0.4, 0.4, 0, new Particle.DustOptions(Color.fromRGB(110, 40, 160), 1.2f));
        l.getWorld().spawnParticle(Particle.ITEM, l, 12, 0.3, 0.3, 0.3, 0.05, new ItemStack(Material.COBWEB));
        l.getWorld().playSound(l, Sound.ENTITY_SPIDER_AMBIENT, 1f, 0.7f);
        p.sendMessage(ChatColor.DARK_PURPLE + "You become a spider. " + ChatColor.GRAY + "(walk into walls to climb; anything that hits you turns you back)");
    }

    void end(Player p, String why) {
        Form f = forms.remove(p.getUniqueId());
        if (f == null) return;
        drop(p.getUniqueId(), f);
        AttributeInstance sc = p.getAttribute(Attribute.SCALE);
        if (sc != null) sc.getModifiers().stream().filter(m -> m.getKey().equals(scaleKey)).toList().forEach(sc::removeModifier);
        PotionEffect inv = p.getPotionEffect(PotionEffectType.INVISIBILITY);
        if (inv != null && inv.isAmbient()) p.removePotionEffect(PotionEffectType.INVISIBILITY);
        PotionEffect nv = p.getPotionEffect(PotionEffectType.NIGHT_VISION);
        if (nv != null && nv.isAmbient()) p.removePotionEffect(PotionEffectType.NIGHT_VISION);
        showGear(p);
        cooldown.put(p.getUniqueId(), System.currentTimeMillis() + (long) (plugin.getConfig().getDouble("spider-form.cooldown-seconds", 20) * 1000));
        if (why != null && p.isOnline()) {
            p.sendMessage(why);
            p.getWorld().spawnParticle(Particle.POOF, p.getLocation().add(0, 0.5, 0), 15, 0.3, 0.3, 0.3, 0.02);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_SPIDER_DEATH, 0.7f, 1.3f);
        }
    }

    private void drop(UUID id, Form f) {
        forms.remove(id);
        if (f.rig != null) f.rig.remove();
        if (f.standin != null && f.standin.isValid()) f.standin.remove();
    }

    void endAll() {
        for (Block b : parted.keySet()) if (b.getType().isAir()) b.setType(Material.COBWEB, false); // the server stopping: every parted web goes back
        parted.clear(); for (UUID id : new ArrayList<>(forms.keySet())) { Player p = Bukkit.getPlayer(id); if (p != null) end(p, null); else drop(id, forms.get(id)); } }

    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private void hideGear(Player p) {
        Map<EquipmentSlot, ItemStack> empty = new HashMap<>();
        for (EquipmentSlot s : SLOTS) empty.put(s, new ItemStack(Material.AIR));
        for (Player o : p.getWorld().getPlayers()) if (o != p) o.sendEquipmentChange(p, empty);
    }

    private void showGear(Player p) {
        if (!p.isOnline()) return;
        Map<EquipmentSlot, ItemStack> real = new HashMap<>();
        for (EquipmentSlot s : SLOTS) { ItemStack it = p.getInventory().getItem(s); real.put(s, it == null ? new ItemStack(Material.AIR) : it); }
        for (Player o : p.getWorld().getPlayers()) if (o != p) o.sendEquipmentChange(p, real);
    }

    /** Hit by anything that hits (a mob, a player, a projectile, an explosion): back to yourself. Falls never hurt a spider. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !isSpider(p)) return;
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL) return;
        if (e instanceof EntityDamageByEntityEvent || e.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION || e.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            Form f = forms.get(p.getUniqueId());
            if (f != null && ticks - f.since < 10) return;
            end(p, ChatColor.RED + "Something hit you: you're yourself again.");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && e.getEntity() instanceof Player p && isSpider(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBite(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && isSpider(p) && e.getEntity() instanceof LivingEntity v)
            v.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, 0));
        if (e.getEntity().getScoreboardTags().contains("faultline_spider_form_standin")) e.setCancelled(true);
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { end(e.getPlayer(), null); }
    @EventHandler public void onDeath(PlayerDeathEvent e) { end(e.getEntity(), null); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { end(e.getPlayer(), ChatColor.GRAY + "You change back."); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { // a crash mid-form: the size modifier is saved in the player file
        AttributeInstance sc = e.getPlayer().getAttribute(Attribute.SCALE);
        if (sc != null) sc.getModifiers().stream().filter(m -> m.getKey().equals(scaleKey)).toList().forEach(sc::removeModifier);
    }
}
