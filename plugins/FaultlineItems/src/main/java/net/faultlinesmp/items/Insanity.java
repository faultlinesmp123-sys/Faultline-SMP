package net.faultlinesmp.items;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.*;

/**
 * INSANITY (`insanity:`): the Farlands get into your head. Out there (not at a lit Rim Lighthouse) it rises every second
 * by `rise-per-second` x (0.15 + Rim Depth): about 3 minutes to 100% at the very edge, ~15 minutes just past the walls. It
 * falls `fall-per-second` once you're out (and 3x faster at a lighthouse); kept in the player PDC `faultlineitems:insanity`.
 * Shown on the Rim Depth bar. What you get, building on each other:
 *   20%+ whispers, footsteps behind you, something breathing.
 *   40%+ HALLUCINATIONS of players: a Mannequin with a real player's face (sometimes your own) only you can see, standing at
 *        the edge of sight and watching; gone in smoke when you get close or stare at it. Fake chat: "X joined the game",
 *        "X left the game", whispers, death messages.
 *   60%+ fake blocks only you see (torches, wither roses, skulls, a doorway of obsidian), creepers hissing behind you, a hurt
 *        flinch with no damage, your own name whispered.
 *   80%+ the hallucinations RUN at you and vanish in your face with a scream, the world flickers (blocks round you turn
 *        black for a moment), a heartbeat, Darkness pulses.
 *   100% INSANE: all of it far more often, Nausea, and every `insane-damage-seconds` 15 your mind hurts you
 *        (`insane-damage` 2).
 * The LUCID LOCKET accessory (CLOCK, `/giveaccessory lucidlocket`, recipe echo shards / amethyst round a clock) stops it:
 * worn, insanity never rises and drains 3/s.
 */
final class Insanity implements Listener {

    final FaultlineItems plugin;
    final NamespacedKey key, locketKey;
    private final Random rnd = new Random();
    private final Map<UUID, Double> level = new HashMap<>();
    private final Map<UUID, Ghost> ghosts = new HashMap<>();
    private final Map<UUID, List<Location>> fakes = new HashMap<>();
    private final Map<UUID, Integer> hurtAt = new HashMap<>();
    private final Set<UUID> toldInsane = new HashSet<>();
    private int tick;

    static final String GHOST_TAG = "faultline_hallucination";
    private static final String[] WHISPERS = {"minecraft:ambient.cave", "minecraft:entity.warden.ambient", "minecraft:entity.enderman.stare",
            "minecraft:ambient.soul_sand_valley.mood", "minecraft:ambient.basalt_deltas.mood", "minecraft:entity.vex.ambient"};
    private static final String[] WORDS = {"behind you", "turn around", "it's watching", "you can't leave", "why are you here",
            "don't look", "we see you", "come closer", "it's not real", "keep walking", "they're all gone", "help me"};
    private static final Material[] FAKE_TOPS = {Material.REDSTONE_TORCH, Material.WITHER_ROSE, Material.SKELETON_SKULL, Material.SOUL_TORCH, Material.CANDLE};
    private static final Material[] FAKE_GROUND = {Material.OBSIDIAN, Material.BLACK_CONCRETE, Material.SOUL_SOIL, Material.NETHERRACK, Material.BONE_BLOCK};

    Insanity(FaultlineItems plugin) {
        this.plugin = plugin;
        key = new NamespacedKey(plugin, "insanity");
        locketKey = new NamespacedKey(plugin, "lucid_locket");
        try {
            NamespacedKey rk = new NamespacedKey(plugin, "lucid_locket_recipe");
            if (Bukkit.getRecipe(rk) == null) {
                ShapedRecipe r = new ShapedRecipe(rk, locket());
                r.shape("EAE", "ACA", "EAE");
                r.setIngredient('E', Material.ECHO_SHARD);
                r.setIngredient('A', Material.AMETHYST_SHARD);
                r.setIngredient('C', new RecipeChoice.MaterialChoice(Material.CLOCK));
                Bukkit.addRecipe(r);
            }
        } catch (RuntimeException e) { plugin.getLogger().warning("Lucid Locket recipe: " + e.getMessage()); }
        Bukkit.getScheduler().runTaskTimer(plugin, this::run, 40, 10);
        Bukkit.getScheduler().runTask(plugin, () -> {   // a crash's leftovers
            for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(GHOST_TAG)) e.remove();
        });
    }

    /** The first Lucid Lockets pointed at a model the pack doesn't have (a missing texture): the vanilla clock now. */
    private void fixLockets(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null || it.getType() != Material.CLOCK || !it.getPersistentDataContainer().has(locketKey, PersistentDataType.BYTE)) continue;
            var m = it.getItemMeta();
            if (m.hasItemModel() && m.getItemModel().equals(NamespacedKey.minecraft("clock"))) continue;
            m.setItemModel(NamespacedKey.minecraft("clock"));
            m.setEnchantmentGlintOverride(true);
            it.setItemMeta(m);
        }
    }

    double cfg(String k, double d) { return plugin.getConfig().getDouble("insanity." + k, d); }

    ItemStack locket() {
        ItemStack it = FaultlineItems.NewAccessoryItems.make(plugin, Material.CLOCK, ChatColor.AQUA + "" + ChatColor.BOLD + "Lucid Locket", locketKey,
                ChatColor.AQUA + "Keeps your mind your own.",
                ChatColor.GRAY + "Worn, the Farlands can't drive you",
                ChatColor.GRAY + "insane: no Insanity, no hallucinations.");
        var m = it.getItemMeta();
        m.setItemModel(NamespacedKey.minecraft("clock"));   // no custom model in the pack
        m.setEnchantmentGlintOverride(true);
        it.setItemMeta(m);
        return it;
    }

    boolean wearing(Player p) {
        try { return plugin.getAccessoryManager().hasEquipped(p, locketKey); } catch (RuntimeException e) { return false; }
    }

    double get(Player p) {
        return level.computeIfAbsent(p.getUniqueId(), k -> {
            Double v = p.getPersistentDataContainer().get(key, PersistentDataType.DOUBLE);
            return v == null ? 0 : v;
        });
    }

    void set(Player p, double v) {
        v = Math.max(0, Math.min(100, v));
        level.put(p.getUniqueId(), v);
        if (tick % 10 == 0) p.getPersistentDataContainer().set(key, PersistentDataType.DOUBLE, v);
    }

    /** For the Rim Depth bar. */
    String label(Player p) {
        if (!plugin.getConfig().getBoolean("insanity.enabled", true)) return "";
        int pct = (int) Math.round(get(p));
        if (wearing(p)) return ChatColor.AQUA + " · Sane (Lucid Locket)";
        ChatColor c = pct >= 100 ? ChatColor.DARK_RED : pct >= 80 ? ChatColor.RED : pct >= 40 ? ChatColor.GOLD : ChatColor.GRAY;
        String n = pct >= 100 ? ChatColor.MAGIC + "100" + c : String.valueOf(pct);
        return ChatColor.DARK_GRAY + " · " + c + "Insanity " + n + "%";
    }

    // ================================================================= the tick (every 10 ticks)

    private void run() {
        tick++;
        boolean on = plugin.getConfig().getBoolean("insanity.enabled", true);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (tick % 20 == 0) fixLockets(p);
            try { one(p, on); }
            catch (RuntimeException ex) { if (tick % 120 == 0) plugin.getLogger().warning("[Insanity] " + ex); }
        }
        for (Iterator<Map.Entry<UUID, Ghost>> it = ghosts.entrySet().iterator(); it.hasNext(); ) {
            Ghost g = it.next().getValue();
            if (!g.tick()) { g.remove(); it.remove(); }
        }
    }

    private void one(Player p, boolean on) {
        boolean out = on && plugin.farlands != null && Farlands.survival(p) && plugin.farlands.inCached(p);
        double v = get(p);
        boolean whole = tick % 2 == 0;   // once a second
        if (!out) {
            if (v > 0 && whole) set(p, v - cfg("fall-per-second", 1.5));
            clearOf(p);
            if (get(p) < 100) toldInsane.remove(p.getUniqueId());
            return;
        }
        if (wearing(p)) {
            if (whole && v > 0) set(p, v - 3);
            clearOf(p);
            toldInsane.remove(p.getUniqueId());
            return;
        }
        if (whole) {
            boolean lantern = plugin.farlandsDread != null && plugin.farlandsDread.nearLantern(p);
            if (OuterRim.safe(p) || lantern) v -= cfg("fall-per-second", 1.5) * (lantern ? 2 : 3);
            else {
                double d = OuterRim.depth(p.getLocation());
                v += cfg("rise-per-second", 0.6) * (0.15 + d);
                if (plugin.farlands.hushed(p)) v += 0.3;   // the silence before a mimic
            }
            set(p, v);
            v = get(p);
        }
        if (!whole || OuterRim.safe(p)) return;
        if (plugin.farlandsDread != null && (plugin.farlandsDread.still(p) || plugin.farlandsDread.nearLantern(p))) return;   // silence / a lantern's light
        double rate = v >= 100 ? 2.2 : 1;
        if (v >= 20 && rnd.nextDouble() < 0.07 * rate) whisper(p, v);
        if (v >= 40 && !ghosts.containsKey(p.getUniqueId()) && rnd.nextDouble() < 0.035 * rate) ghost(p, v >= 80 && rnd.nextDouble() < 0.45);
        if (v >= 40 && rnd.nextDouble() < 0.012 * rate) fakeChat(p);
        if (v >= 60 && rnd.nextDouble() < 0.03 * rate) fakeBlocks(p);
        if (v >= 60 && rnd.nextDouble() < 0.025 * rate) phantomThreat(p);
        if (v >= 80) {
            if (tick % 4 == 0) p.playSound(p, "minecraft:entity.warden.heartbeat", SoundCategory.AMBIENT, 0.7f, 0.8f + (float) (v - 80) / 50f);
            if (rnd.nextDouble() < 0.02 * rate) flicker(p);
            if (rnd.nextDouble() < 0.02 * rate) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 50, 0, true, false, false));
        }
        if (v >= 100) insane(p);
        else toldInsane.remove(p.getUniqueId());
    }

    // ================================================================= effects

    private Location behind(Player p, double dist) {
        Vector back = p.getLocation().getDirection().setY(0);
        if (back.lengthSquared() < 1e-4) back = new Vector(1, 0, 0);
        return p.getLocation().add(back.normalize().multiply(-dist));
    }

    private void whisper(Player p, double v) {
        int kind = rnd.nextInt(4);
        if (kind == 0) {   // footsteps right behind you
            Location l = behind(p, 2 + rnd.nextDouble() * 3);
            Block g = l.getBlock().getRelative(0, -1, 0);
            Sound step = g.getBlockData().getSoundGroup().getStepSound();
            for (int i = 0; i < 3; i++) {
                int d = i * 6;
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) p.playSound(l, step, SoundCategory.PLAYERS, 0.6f, 0.9f); }, d);
            }
        } else if (kind == 1) {
            p.playSound(behind(p, 1.5), "minecraft:entity.player.breath", SoundCategory.PLAYERS, 0.5f, 0.6f);
        } else {
            p.playSound(behind(p, 4 + rnd.nextDouble() * 8), WHISPERS[rnd.nextInt(WHISPERS.length)], SoundCategory.AMBIENT, 0.6f, 0.4f + rnd.nextFloat() * 0.4f);
        }
    }

    private PlayerProfile face(Player p) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (rnd.nextDouble() < 0.2 || online.size() < 2) return p.getPlayerProfile();   // sometimes it's YOU
        Player o = online.get(rnd.nextInt(online.size()));
        return o.getPlayerProfile();
    }

    private Location groundAhead(Player p, double min, double max, boolean ahead) {
        World w = p.getWorld();
        Vector fwd = p.getLocation().getDirection().setY(0);
        if (fwd.lengthSquared() < 1e-4) fwd = new Vector(0, 0, 1);
        fwd.normalize();
        for (int i = 0; i < 14; i++) {
            double a = ahead ? (rnd.nextDouble() - 0.5) * 1.6 : rnd.nextDouble() * Math.PI * 2;
            Vector dir = ahead ? fwd.clone().rotateAroundY(a) : new Vector(Math.cos(a), 0, Math.sin(a));
            double r = min + rnd.nextDouble() * (max - min);
            int x = (int) Math.floor(p.getX() + dir.getX() * r), z = (int) Math.floor(p.getZ() + dir.getZ() * r);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int y = p.getLocation().getBlockY() + 8; y >= p.getLocation().getBlockY() - 8; y--) {
                Block f = w.getBlockAt(x, y - 1, z), b = w.getBlockAt(x, y, z), h = w.getBlockAt(x, y + 1, z);
                if (f.getType().isSolid() && b.getType().isAir() && h.getType().isAir()) return new Location(w, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    private void ghost(Player p, boolean rush) {
        Location at = groundAhead(p, rush ? 14 : 18, rush ? 22 : 30, true);
        if (at == null) return;
        try {
            Mannequin m = (Mannequin) at.getWorld().spawnEntity(at, EntityType.MANNEQUIN, false);
            m.addScoreboardTag(GHOST_TAG);
            m.addScoreboardTag("faultline_no_bedrock_fx");
            m.setPersistent(false);
            m.setGravity(false);
            m.setSilent(true);
            m.setInvulnerable(true);
            m.setCollidable(false);
            m.setVisibleByDefault(false);
            try { m.setImmovable(true); } catch (RuntimeException ignored) { }
            try { m.setProfile(ResolvableProfile.resolvableProfile(face(p))); } catch (RuntimeException ignored) { }
            try { m.setDescription(null); } catch (RuntimeException ignored) { }
            p.showEntity(plugin, m);
            ghosts.put(p.getUniqueId(), new Ghost(p, m, rush));
        } catch (RuntimeException | LinkageError e) { /* no mannequins on this server: the other effects still run */ }
    }

    final class Ghost {
        final Player p;
        final Mannequin m;
        final boolean rush;
        int life, stare;

        Ghost(Player p, Mannequin m, boolean rush) { this.p = p; this.m = m; this.rush = rush; }

        /** false = done. */
        boolean tick() {
            life += 10;
            if (!p.isOnline() || !m.isValid() || !p.getWorld().equals(m.getWorld())) return false;
            Location ml = m.getLocation(), pe = p.getEyeLocation();
            Vector to = pe.toVector().subtract(ml.toVector().add(new Vector(0, 1.6, 0)));
            double dist = to.length();
            Location face = ml.clone();
            if (dist > 0.1) face.setDirection(to);
            if (rush && life > 30) {   // it comes for you
                Vector step = to.clone().setY(0);
                if (step.lengthSquared() > 1e-4) step.normalize().multiply(Math.min(dist, 4.5));
                face.add(step);
                m.teleport(face);
                if (dist < 3) {
                    p.playSound(p, "minecraft:entity.ghast.scream", SoundCategory.HOSTILE, 1f, 0.5f);
                    p.playSound(p, "minecraft:entity.warden.roar", SoundCategory.HOSTILE, 0.7f, 1.4f);
                    try { p.playHurtAnimation(0); } catch (RuntimeException | LinkageError ignored) { }
                    p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 40, 0, true, false, false));
                    smoke();
                    return false;
                }
                return life < 20 * 12;
            }
            m.setRotation(face.getYaw(), face.getPitch());
            Vector look = pe.getDirection();
            Vector at = ml.toVector().add(new Vector(0, 1, 0)).subtract(pe.toVector());
            boolean staring = at.lengthSquared() > 0 && look.dot(at.clone().normalize()) > 0.985;
            stare = staring ? stare + 10 : 0;
            if (dist < 9 || stare >= 20) { smoke(); return false; }
            return life < 20 * 20;
        }

        void smoke() {
            Location l = m.getLocation().add(0, 1, 0);
            p.spawnParticle(Particle.LARGE_SMOKE, l, 25, 0.3, 0.7, 0.3, 0.01);
            p.playSound(l, "minecraft:ambient.cave", SoundCategory.AMBIENT, 0.9f, 1.4f);
        }

        void remove() { if (m.isValid()) m.remove(); }
    }

    /** Real lines real players said (name, message), newest last; the chat is async, so it's locked. */
    private final LinkedList<String[]> heard = new LinkedList<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent e) {
        String text;
        try { text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.message()); }
        catch (RuntimeException | LinkageError ex) { return; }
        if (text.isBlank() || text.length() > 120) return;
        synchronized (heard) {
            heard.add(new String[]{e.getPlayer().getName(), e.getPlayer().getUniqueId().toString(), text});
            while (heard.size() > 200) heard.removeFirst();
        }
    }

    /** One real line someone else said (sometimes something YOU said, said back to you by someone else). */
    private String[] realLine(Player p) {
        synchronized (heard) {
            if (heard.isEmpty()) return null;
            List<String[]> pool = new ArrayList<>(heard);
            for (int i = 0; i < 8; i++) {
                String[] l = pool.get(rnd.nextInt(pool.size()));
                if (!l[1].equals(p.getUniqueId().toString()) || rnd.nextInt(4) == 0) return l;
            }
            return pool.get(pool.size() - 1);
        }
    }

    private void fakeChat(Player p) {
        List<OfflinePlayer> all = new ArrayList<>();
        for (OfflinePlayer o : Bukkit.getOfflinePlayers()) if (o.getName() != null && !o.getUniqueId().equals(p.getUniqueId())) all.add(o);
        String name = all.isEmpty() ? p.getName() : all.get(rnd.nextInt(all.size())).getName();
        String[] real = realLine(p);
        // REAL WORDS: a real player's real message, out of place (or your own words in someone else's mouth)
        if (real != null && rnd.nextInt(2) == 0) {
            String who = real[1].equals(p.getUniqueId().toString()) ? name : real[0];
            switch (rnd.nextInt(3)) {
                case 0 -> p.sendMessage(Component.text("<" + who + "> " + real[2]));
                case 1 -> p.sendMessage(Component.text(who + " whispers to you: " + real[2], NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, true));
                default -> p.sendMessage(Component.text("<" + who + "> " + real[2].toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "") + "...", NamedTextColor.DARK_GRAY));
            }
            return;
        }
        switch (rnd.nextInt(5)) {
            case 0 -> p.sendMessage(Component.translatable("multiplayer.player.joined", NamedTextColor.YELLOW, Component.text(name)));
            case 1 -> p.sendMessage(Component.translatable("multiplayer.player.left", NamedTextColor.YELLOW, Component.text(name)));
            case 2 -> p.sendMessage(Component.text(name + " whispers to you: " + WORDS[rnd.nextInt(WORDS.length)], NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, true));
            case 3 -> p.sendMessage(Component.translatable("death.attack.fall", Component.text(name)));
            default -> p.sendMessage(Component.text("<" + name + "> " + WORDS[rnd.nextInt(WORDS.length)]));
        }
    }

    private void fakeBlocks(Player p) {
        List<Location> mine = fakes.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        if (mine.size() > 40) return;
        int n = 2 + rnd.nextInt(4);
        boolean doorway = rnd.nextInt(5) == 0;
        Location base = groundAhead(p, 6, 14, true);
        if (base == null) return;
        List<Location> now = new ArrayList<>();
        if (doorway) {   // a black doorway standing in the open, there and gone
            Vector side = p.getLocation().getDirection().setY(0).crossProduct(new Vector(0, 1, 0));
            if (side.lengthSquared() < 1e-4) side = new Vector(1, 0, 0);
            side.normalize();
            for (int dy = 0; dy < 4; dy++) for (int s = -1; s <= 1; s++) {
                if (s == 0 && dy < 3) continue;
                Location l = base.clone().add(side.clone().multiply(s)).add(0, dy, 0);
                if (l.getBlock().getType().isAir()) { p.sendBlockChange(l, Material.OBSIDIAN.createBlockData()); now.add(l.getBlock().getLocation()); }
            }
        } else for (int i = 0; i < n; i++) {
            Location l = groundAhead(p, 4, 12, rnd.nextBoolean());
            if (l == null) continue;
            p.sendBlockChange(l, FAKE_TOPS[rnd.nextInt(FAKE_TOPS.length)].createBlockData());
            now.add(l.getBlock().getLocation());
        }
        mine.addAll(now);
        Bukkit.getScheduler().runTaskLater(plugin, () -> restore(p, now), 60 + rnd.nextInt(80));
    }

    private void flicker(Player p) {   // the ground round you goes black for a moment
        List<Location> now = new ArrayList<>();
        Location c = p.getLocation();
        for (int i = 0; i < 26; i++) {
            int x = c.getBlockX() + rnd.nextInt(13) - 6, z = c.getBlockZ() + rnd.nextInt(13) - 6;
            for (int y = c.getBlockY() + 2; y >= c.getBlockY() - 4; y--) {
                Block b = c.getWorld().getBlockAt(x, y, z);
                if (b.getType().isSolid() && b.getRelative(0, 1, 0).getType().isAir()) {
                    BlockData d = FAKE_GROUND[rnd.nextInt(FAKE_GROUND.length)].createBlockData();
                    p.sendBlockChange(b.getLocation(), d);
                    now.add(b.getLocation());
                    break;
                }
            }
        }
        p.playSound(p, "minecraft:block.beacon.deactivate", SoundCategory.AMBIENT, 0.8f, 0.5f);
        fakes.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>()).addAll(now);
        Bukkit.getScheduler().runTaskLater(plugin, () -> restore(p, now), 18 + rnd.nextInt(14));
    }

    private void restore(Player p, List<Location> ls) {
        List<Location> mine = fakes.get(p.getUniqueId());
        if (mine != null) mine.removeAll(ls);
        if (!p.isOnline()) return;
        for (Location l : ls) if (l.getWorld() == p.getWorld() && l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) p.sendBlockChange(l, l.getBlock().getBlockData());
    }

    private void phantomThreat(Player p) {
        switch (rnd.nextInt(4)) {
            case 0 -> p.playSound(behind(p, 1.5), "minecraft:entity.creeper.primed", SoundCategory.HOSTILE, 1f, 0.5f);
            case 1 -> { try { p.playHurtAnimation((float) rnd.nextInt(360)); } catch (RuntimeException | LinkageError ignored) { } p.playSound(p, "minecraft:entity.player.hurt", SoundCategory.PLAYERS, 0.8f, 1f); }
            case 2 -> p.sendActionBar(Component.text("..." + p.getName() + "...", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, true));
            default -> p.playSound(behind(p, 3), "minecraft:entity.zombie.ambient", SoundCategory.HOSTILE, 0.9f, 0.6f);
        }
    }

    private void insane(Player p) {
        if (toldInsane.add(p.getUniqueId())) {
            p.showTitle(Title.title(Component.text("YOU ARE NOT ALONE", NamedTextColor.DARK_RED).decoration(TextDecoration.BOLD, true),
                    Component.text("You can't tell what's real any more.", NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofSeconds(1))));
            p.playSound(p, "minecraft:entity.warden.emerge", SoundCategory.HOSTILE, 1f, 0.6f);
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 120, 0, true, false, false));
        int every = (int) (cfg("insane-damage-seconds", 15) * 20);
        int now = Bukkit.getCurrentTick();
        if (now - hurtAt.getOrDefault(p.getUniqueId(), -100000) >= every) {
            hurtAt.put(p.getUniqueId(), now);
            double dmg = cfg("insane-damage", 2);
            if (dmg > 0) {
                p.damage(dmg);
                p.sendActionBar(Component.text("Your mind gives way.", NamedTextColor.DARK_RED));
            }
        }
    }

    // ================================================================= cleanup

    void clearOf(Player p) {
        Ghost g = ghosts.remove(p.getUniqueId());
        if (g != null) g.remove();
        List<Location> f = fakes.remove(p.getUniqueId());
        if (f != null && !f.isEmpty()) restore(p, new ArrayList<>(f));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Double v = level.remove(p.getUniqueId());
        if (v != null) p.getPersistentDataContainer().set(key, PersistentDataType.DOUBLE, v);
        Ghost g = ghosts.remove(p.getUniqueId());
        if (g != null) g.remove();
        fakes.remove(p.getUniqueId());
        toldInsane.remove(p.getUniqueId());
        hurtAt.remove(p.getUniqueId());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) { clearOf(e.getPlayer()); }

    /** Dying clears your head. */
    @EventHandler
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent e) {
        set(e.getEntity(), 0);
        e.getEntity().getPersistentDataContainer().set(key, PersistentDataType.DOUBLE, 0d);
        clearOf(e.getEntity());
    }

    void shutdown() {
        for (Ghost g : ghosts.values()) g.remove();
        ghosts.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Double v = level.get(p.getUniqueId());
            if (v != null) p.getPersistentDataContainer().set(key, PersistentDataType.DOUBLE, v);
        }
    }

    /** Admin: set someone's insanity. */
    void force(Player p, double v) { set(p, v); p.getPersistentDataContainer().set(key, PersistentDataType.DOUBLE, Math.max(0, Math.min(100, v))); }
}
