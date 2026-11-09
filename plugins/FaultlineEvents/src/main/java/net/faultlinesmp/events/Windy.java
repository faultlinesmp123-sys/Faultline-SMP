package net.faultlinesmp.events;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.data.Ageable;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * WINDY (15 minutes by day). A wind blows from one side (it veers slowly; gusts every 20-45 s): out in the open it
 * nudges you a little downwind (sneak to brace, a tailwind on an elytra), carries arrows / tridents / pearls / snowballs
 * off line, and streaks the air. Everyone in the world has Luck I, and mob drops, ores, ripe crops and fish come x1.2
 * (stackable things only, never what a mob was holding or a block dropping itself = no dupes).
 */
final class Windy extends Ev {

    private double angle;            // radians, the way the wind BLOWS TO
    private double gustLeft, nextGust;
    private final Map<Projectile, Integer> flying = new HashMap<>();

    Windy(FaultlineEvents pl) { super(pl); }

    @Override String id() { return "WINDY"; }
    @Override String key() { return "windy"; }
    @Override String title() { return "Windy"; }
    @Override ChatColor color() { return ChatColor.AQUA; }
    @Override BarColor barColor() { return BarColor.BLUE; }
    @Override boolean night() { return false; }
    @Override int minutes() { return Math.max(1, i("minutes", 15)); }
    @Override String blurb() { return "a strong wind: Luck I, mob drops, ores, crops and fish x" + mult() + ", but the wind pushes you a little and carries your arrows."; }

    private String mult() { double m = d("loot-multiplier", 1.2); return m == Math.floor(m) ? String.valueOf((int) m) : String.valueOf(m); }

    @Override String barText() { return ChatColor.AQUA + "Wind from the " + from() + (gustLeft > 0 ? ChatColor.WHITE + " (GUST)" : "") + ChatColor.GRAY + " · Luck & Looting x" + mult(); }

    /** Compass name of where the wind comes FROM. */
    private String from() {
        double fx = -Math.cos(angle), fz = -Math.sin(angle);   // +x east, +z south
        String[] n = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"};
        double deg = Math.toDegrees(Math.atan2(fz, fx));
        return n[(int) Math.floorMod(Math.round(deg / 45), 8)];
    }

    @Override
    void start(boolean announce) {
        angle = pl.random.nextDouble() * Math.PI * 2;
        gustLeft = 0;
        nextGust = gustGap();
        WindPush.ready(pl.getLogger());
        if (announce) announce(ChatColor.AQUA + "" + ChatColor.BOLD + "The Wind Picks Up",
                ChatColor.WHITE + "Luck & Looting x" + mult() + ChatColor.GRAY + " · hold onto your hat",
                ChatColor.AQUA + "≋ " + ChatColor.BOLD + "The wind picks up!" + ChatColor.WHITE + " Luck I, and mob drops, ores, crops and fish x" + mult() + " for " + minutes() + " minutes. It'll push you around a little.",
                Sound.ENTITY_BREEZE_IDLE_AIR, 0.6f);
    }

    @Override
    void stop(boolean natural) {
        flying.clear();
        for (Player p : pl.players()) {
            PotionEffect l = p.getPotionEffect(PotionEffectType.LUCK);
            if (l != null && l.isAmbient() && l.getAmplifier() == 0 && l.getDuration() <= 200) p.removePotionEffect(PotionEffectType.LUCK);
        }
        if (natural) Bukkit.broadcastMessage(ChatColor.AQUA + "≋ " + ChatColor.GRAY + "The wind dies down.");
    }

    private double gustGap() {
        double lo = d("gusts.min-seconds", 20), hi = d("gusts.max-seconds", 45);
        return lo + pl.random.nextDouble() * Math.max(0, hi - lo);
    }

    private Vector dir() { return new Vector(Math.cos(angle), 0, Math.sin(angle)); }

    @Override
    void second() {
        angle += Math.toRadians((pl.random.nextDouble() - 0.5) * 6); // veers slowly
        if (gustLeft > 0) gustLeft--;
        else if ((nextGust -= 1) <= 0) {
            gustLeft = d("gusts.seconds", 3);
            nextGust = gustGap();
            for (Player p : pl.players()) if (exposed(p)) {
                p.playSound(p.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 0.35f, 0.6f);
                p.playSound(p.getLocation(), Sound.ITEM_ELYTRA_FLYING, 0.25f, 1.4f);
            }
        }
        if (b("luck", true)) for (Player p : pl.players()) {
            PotionEffect have = p.getPotionEffect(PotionEffectType.LUCK);
            if (have == null || have.getAmplifier() == 0 && have.getDuration() < 100)
                p.addPotionEffect(new PotionEffect(PotionEffectType.LUCK, 200, 0, true, false, true));
        }
    }

    /** Out under the sky, and not swimming, riding or flying. */
    private boolean exposed(Player p) {
        try { return exposed0(p); } catch (RuntimeException e) { return false; }
    }

    private boolean exposed0(Player p) {
        if (p.isInsideVehicle() || p.isFlying() || p.isSwimming() || p.isInWater() || p.isDead()) return false;
        Location l = p.getLocation();
        int top = l.getWorld().getHighestBlockYAt(l.getBlockX(), l.getBlockZ(), HeightMap.MOTION_BLOCKING);
        return l.getY() >= top - 0.5;
    }

    @Override
    void tick(long t) {
        Vector w = dir();
        double gust = gustLeft > 0 ? d("gusts.strength", 2.5) : 1;
        int every = Math.max(2, i("push-every-ticks", 10));
        if (t % every == 0) {
            double base = d("push", 0.035) * gust;
            for (Player p : pl.players()) {
                try {
                if (!exposed(p) || p.getGameMode() == org.bukkit.GameMode.SPECTATOR || Edition.bedrock(p)) continue;
                double f = base;
                if (p.isGliding()) f *= d("glide-factor", 3.0);
                else if (p.isSneaking()) f *= d("sneak-factor", 0.3);
                if (p.getLocation().getY() > 120) f *= 1.4; // windier up high
                WindPush.push(p, w.getX() * f, 0, w.getZ() * f, gustLeft > 0 && pl.random.nextInt(3) == 0);
                } catch (RuntimeException e) { pl.problem("push", e); }
            }
        }
        if (t % 4 == 0) {
            int n = i("particles", 5) * (gustLeft > 0 ? 2 : 1);
            for (Player p : pl.players()) {
                if (!exposed(p)) continue;
                Location l = p.getLocation();
                for (int k = 0; k < n; k++) {
                    Location at = l.clone().add(pl.random.nextGaussian() * 6 - w.getX() * 5, 0.5 + pl.random.nextDouble() * 3, pl.random.nextGaussian() * 6 - w.getZ() * 5);
                    p.spawnParticle(Particle.CLOUD, at, 0, w.getX(), 0.02, w.getZ(), 0.35 * gust);
                }
                if (pl.random.nextInt(3) == 0) p.spawnParticle(Particle.WHITE_ASH, l.clone().add(0, 2, 0), 6, 5, 2, 5, 0);
            }
        }
        double drift = d("projectile-drift", 0.006) * gust;
        for (Iterator<Map.Entry<Projectile, Integer>> it = flying.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Projectile pr = e.getKey();
            boolean stuck;
            try { stuck = pr instanceof AbstractArrow a && a.isInBlock(); } catch (RuntimeException ex) { stuck = false; }
            if (!pr.isValid() || e.getValue() <= 0 || stuck) { it.remove(); continue; }
            e.setValue(e.getValue() - 1);
            Vector v = pr.getVelocity().add(w.clone().multiply(drift));
            if (finite(v)) pr.setVelocity(v);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!on() || !pl.eventWorld(e.getEntity().getWorld()) || flying.size() > 300) return;
        flying.put(e.getEntity(), 80);
    }

    // ---------------------------------------------------------------- luck & looting

    private int more(int amount) {
        double m = amount * d("loot-multiplier", 1.2);
        int whole = (int) Math.floor(m);
        return whole + (pl.random.nextDouble() < m - whole ? 1 : 0);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onKill(EntityDeathEvent e) {
        LivingEntity v = e.getEntity();
        if (!on() || v instanceof Player || v.getKiller() == null || !pl.eventWorld(v.getWorld())) return;
        if (v.getScoreboardTags().stream().anyMatch(t -> t.startsWith("faultline"))) return; // bosses, minions, our creatures
        EntityEquipment eq = v.getEquipment();
        for (ItemStack it : e.getDrops()) {
            if (it == null || it.getMaxStackSize() <= 1 || it.hasItemMeta() && it.getItemMeta().hasDisplayName()) continue;
            if (eq != null && held(eq, it)) continue; // what it picked up isn't doubled
            it.setAmount(Math.min(it.getMaxStackSize(), more(it.getAmount())));
        }
        e.setDroppedExp(more(e.getDroppedExp()));
    }

    private static boolean held(EntityEquipment eq, ItemStack it) {
        for (EquipmentSlot s : EquipmentSlot.values()) {
            try { ItemStack h = eq.getItem(s); if (h != null && h.isSimilar(it)) return true; } catch (RuntimeException ignored) { }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlock(BlockDropItemEvent e) {
        if (!on() || !pl.eventWorld(e.getBlock().getWorld())) return;
        Material was = e.getBlockState().getType();
        boolean ore = was.name().endsWith("_ORE");
        boolean crop = e.getBlockState().getBlockData() instanceof Ageable a && a.getAge() == a.getMaximumAge()
                && (was == Material.WHEAT || was == Material.CARROTS || was == Material.POTATOES || was == Material.BEETROOTS
                || was == Material.NETHER_WART || was == Material.COCOA || was == Material.SWEET_BERRY_BUSH);
        boolean other = was == Material.MELON || was == Material.GLOWSTONE || was == Material.AMETHYST_CLUSTER;
        if (!ore && !crop && !other) return;
        for (Item drop : e.getItems()) {
            ItemStack it = drop.getItemStack();
            if (it.getType() == was || it.getMaxStackSize() <= 1) continue; // silk touch: the block itself
            if (crop && isSeed(it.getType())) continue;
            it.setAmount(Math.min(it.getMaxStackSize(), more(it.getAmount())));
            drop.setItemStack(it);
        }
    }

    private static boolean isSeed(Material m) { return m == Material.WHEAT_SEEDS || m == Material.BEETROOT_SEEDS; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (!on() || e.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(e.getCaught() instanceof Item item)) return;
        if (!pl.eventWorld(e.getPlayer().getWorld())) return;
        ItemStack it = item.getItemStack();
        if (it.getMaxStackSize() <= 1 || it.hasItemMeta() && !it.getItemMeta().getPersistentDataContainer().getKeys().isEmpty()) return; // our custom fish keep their one
        if (pl.random.nextDouble() < d("loot-multiplier", 1.2) - 1) {
            ItemStack extra = it.clone();
            extra.setAmount(1);
            give(e.getPlayer(), extra);
        }
    }
}
