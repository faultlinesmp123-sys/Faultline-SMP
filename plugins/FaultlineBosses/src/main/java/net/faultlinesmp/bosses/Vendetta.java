package net.faultlinesmp.bosses;

import net.faultlinesmp.bosses.FaultlineBosses.Guard;
import net.faultlinesmp.bosses.FaultlineBosses.Pose;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Vindicator;
import org.bukkit.event.Event.Result;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

import static net.faultlinesmp.bosses.FaultlineBosses.*;

/**
 * ROCCO VENDETTA (inspired by Ricardo, Limbus Company): the second hardest boss. Summoned with a Vendetta Contract.
 *   3 phases over 5,000 health. Every hit on him adds a Vengeance Tattoo (max 50, each +1% damage); at 50 he can use
 *   COMPLETE AND TOTAL EXTERMINATION!!! (a one-shot slam on a random player) and the tattoos reset.
 *   His kicks and punches stun for 3 seconds. At half health his little brother WERNER (300 health) joins the fight.
 * He drops the VENDETTA FIST: every one of his moves (on cooldowns) plus three of its own, his Vengeance Tattoos, and
 * Werner, who comes to help when its holder drops below half health.
 *
 * Everything for this fight lives here; FaultlineBosses ticks it, cleans it up, and hooks it into boss form.
 */
final class Vendetta implements Listener {

    static final String ROCCO_TAG = "faultline_rocco", WERNER_TAG = "faultline_werner", FAM_TAG = "faultline_vendetta_fam",
            FAM_ALLY_TAG = "faultline_vendetta_fam_ally";
    static final List<String> TAGS = List.of(ROCCO_TAG, WERNER_TAG, FAM_TAG, FAM_ALLY_TAG);
    static final String[] COSMETICS = {"rocco_chains", "rocco_book", "rocco_coat"};

    final FaultlineBosses pl;
    final Random random;
    Rocco rocco;
    final List<Werner> allies = new ArrayList<>();
    long cooldownUntil;
    int now;
    final NamespacedKey itemKey, moveKey, tattooKey, stunKey, ownerKey;

    Vendetta(FaultlineBosses pl) {
        this.pl = pl;
        this.random = pl.random;
        itemKey = new NamespacedKey(pl, "vendetta_item");
        moveKey = new NamespacedKey(pl, "vendetta_move");
        tattooKey = new NamespacedKey(pl, "vendetta_tattoos");
        stunKey = new NamespacedKey(pl, "vendetta_stun");
        ownerKey = new NamespacedKey(pl, "vendetta_owner");
        ShapedRecipe r = new ShapedRecipe(new NamespacedKey(pl, "vendetta_contract"), item("contract"));
        r.shape("GNG", "NBN", "GNG");
        r.setIngredient('G', Material.GOLD_BLOCK);
        r.setIngredient('N', Material.NETHERITE_SCRAP);
        r.setIngredient('B', Material.BOOK);
        addRecipeSafely(r);
    }

    double c(String path, double def) { return pl.getConfig().getDouble("rocco." + path, def); }

    /**
     * Where a location faces, flattened. BUG FIX: looking straight up or down flattens to a zero vector, and normalizing
     * that gave NaN coordinates (a contract used looking at your feet tried to spawn him at NaN, NaN, NaN).
     */
    static Vector flatDir(Location l) {
        Vector d = l.getDirection().setY(0);
        if (d.lengthSquared() < 1e-4) d = new Vector(-Math.sin(Math.toRadians(l.getYaw())), 0, Math.cos(Math.toRadians(l.getYaw())));
        return d.lengthSquared() < 1e-4 ? new Vector(0, 0, 1) : d.normalize();
    }

    static Vector flat(Vector v, Vector fallback) {
        Vector d = v.clone().setY(0);
        return d.lengthSquared() < 1e-4 ? fallback.clone() : d.normalize();
    }

    static boolean ours(Entity e) {
        for (String t : TAGS) if (e.getScoreboardTags().contains(t)) return true;
        return false;
    }

    static boolean isBody(Entity e) {
        Set<String> t = e.getScoreboardTags();
        return t.contains(ROCCO_TAG) || t.contains(WERNER_TAG);
    }

    // =====================================================================================================
    //  every tick (from FaultlineBosses)
    // =====================================================================================================
    void tick() {
        now++;
        pl.bossPart("Rocco Vendetta", "stuns", this::stunTick);
        if (rocco != null) rocco.tick();
        for (Werner w : new ArrayList<>(allies)) pl.bossPart("Werner (ally)", "tick", w::tick);
        allies.removeIf(w -> w.gone);
        if (now % 20 == 0) pl.bossPart("Rocco Vendetta", "fam", this::famTick);
        if (now % 10 == 0) pl.bossPart("Vendetta Fist", "hud", this::fistHud);
        pl.bossPart("Vendetta Fist", "moves", this::fistTick);
    }

    void shutdown() {
        if (rocco != null) rocco.removeEverything();
        rocco = null;
        for (Werner w : allies) w.remove();
        allies.clear();
        for (UUID id : new ArrayList<>(stunned.keySet())) { Entity e = Bukkit.getEntity(id); if (e instanceof LivingEntity le) unstun(le); }
        stunned.clear();
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (ours(e)) e.remove();
    }

    // =====================================================================================================
    //  items: the Vendetta Contract (summons him) and the Vendetta Fist (his weapon)
    // =====================================================================================================
    ItemStack item(String which) {
        ItemStack s = new ItemStack(Material.PAPER);
        ItemMeta m = s.getItemMeta();
        if (which.equals("fist")) {
            m.setDisplayName(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Vendetta Fist");
            m.setLore(List.of(ChatColor.GRAY + "Rocco Vendetta's knuckles. Every move he had.",
                    ChatColor.GREEN + "Right-click: use the selected move",
                    ChatColor.GREEN + "Sneak + right-click: next move",
                    ChatColor.LIGHT_PURPLE + "Vengeance Tattoos: " + ChatColor.GRAY + "get hit to gain them (max 50)",
                    ChatColor.LIGHT_PURPLE + "Little Brother: " + ChatColor.GRAY + "Werner helps when you drop below half health",
                    ChatColor.DARK_GRAY + "Dropped by Rocco Vendetta."));
            m.setItemModel(new NamespacedKey("faultline", "vendetta_fist"));
            m.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(new NamespacedKey(pl, "vendetta_fist_damage"), 8, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
            m.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(new NamespacedKey(pl, "vendetta_fist_speed"), -1.2, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
            m.setEnchantmentGlintOverride(true);
            m.getPersistentDataContainer().set(moveKey, PersistentDataType.INTEGER, 0);
        } else {
            which = "contract";
            m.setDisplayName(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Vendetta Contract");
            m.setLore(List.of(ChatColor.GRAY + "Sign it on open, flat ground", ChatColor.GRAY + "and the Vendetta family comes to collect.",
                    ChatColor.DARK_PURPLE + "Summons Rocco Vendetta."));
            m.setItemModel(new NamespacedKey("faultline", "vendetta_contract"));
        }
        m.setMaxStackSize(1);
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, which);
        s.setItemMeta(m);
        return s;
    }

    String itemType(ItemStack s) {
        if (s == null || !s.hasItemMeta()) return null;
        return s.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
    }

    void summon(Location at, Player by) {
        if (rocco != null) return;
        Location spot = at.clone();
        spot.setX(spot.getBlockX() + 0.5); spot.setZ(spot.getBlockZ() + 0.5); spot.setY(spot.getBlockY());
        rocco = new Rocco(spot, by);
        Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Rocco Vendetta " + ChatColor.GRAY + "came to collect"
                + (by != null ? " from " + by.getName() : "") + "...");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = event.getPlayer();
        String type = itemType(p.getInventory().getItemInMainHand());
        if (type == null) return;
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getType().isInteractable() && !p.isSneaking()) return; // doors and chests still work
        event.setUseInteractedBlock(Result.DENY);
        event.setUseItemInHand(Result.DENY);
        if (type.equals("fist")) { fistUse(p); return; }
        if (rocco != null) { p.sendActionBar(legacy(ChatColor.GRAY + "Rocco Vendetta is already here.")); return; }
        if (p.getWorld().getDifficulty() == Difficulty.PEACEFUL) { p.sendActionBar(legacy(ChatColor.RED + "He won't come on Peaceful.")); return; }
        if (System.currentTimeMillis() < cooldownUntil) { p.sendActionBar(legacy(ChatColor.GRAY + "The family is still licking its wounds... try again in a few minutes.")); return; }
        String problem = pl.flatProblem(p.getLocation());
        if (problem != null) { p.sendMessage(ChatColor.RED + problem.replace("He ", "Rocco ") + ChatColor.GRAY + " (The contract wasn't used.)"); return; }
        if (p.getGameMode() != GameMode.CREATIVE) p.getInventory().getItemInMainHand().setAmount(p.getInventory().getItemInMainHand().getAmount() - 1);
        summon(p.getLocation().add(flatDir(p.getLocation()).multiply(6)), p);
    }

    // =====================================================================================================
    //  stuns: can't move, can't jump, can't attack
    // =====================================================================================================
    final Map<UUID, Integer> stunned = new HashMap<>(), stunImmune = new HashMap<>();

    boolean isStunned(Entity e) { return e != null && stunned.containsKey(e.getUniqueId()); }

    void stun(LivingEntity e, int ticks) {
        if (ticks <= 0 || e.isDead() || MORPHED.contains(e.getUniqueId())) return; // never the admin playing a boss
        if (now < stunImmune.getOrDefault(e.getUniqueId(), 0)) return; // just got out of one: can't be chain-stunned forever
        // BUG FIX: a hit on someone already stunned used to EXTEND the stun, so back-to-back kicks kept you stunned forever.
        // A stun now runs out on time; the next one can only land after the immunity window.
        if (stunned.containsKey(e.getUniqueId())) return;
        stunned.put(e.getUniqueId(), now + ticks);
        for (Attribute a : List.of(Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH)) {
            AttributeInstance ai = e.getAttribute(a);
            if (ai != null && ai.getModifier(stunKey) == null) ai.addTransientModifier(new AttributeModifier(stunKey, -1.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
        }
        e.getWorld().playSound(e.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.5f);
        if (e instanceof Player p) p.sendActionBar(legacy(ChatColor.YELLOW + "" + ChatColor.BOLD + "STUNNED"));
    }

    void unstun(LivingEntity e) {
        for (Attribute a : List.of(Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH)) {
            AttributeInstance ai = e.getAttribute(a);
            if (ai != null && ai.getModifier(stunKey) != null) ai.removeModifier(stunKey);
        }
    }

    void stunTick() {
        for (Iterator<Map.Entry<UUID, Integer>> it = stunned.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> en = it.next();
            Entity e = Bukkit.getEntity(en.getKey());
            if (!(e instanceof LivingEntity le) || le.isDead() || !le.isValid() || now >= en.getValue()) {
                if (e instanceof LivingEntity le2) unstun(le2);
                stunImmune.put(en.getKey(), now + (int) c("stun-immunity-ticks", 30));
                it.remove();
                continue;
            }
            if (now % 4 == 0) { // stars around the head
                Location head = le.getEyeLocation().add(0, 0.45, 0);
                double a = now * 0.4;
                for (int i = 0; i < 3; i++) {
                    double b = a + i * 2.094;
                    le.getWorld().spawnParticle(Particle.DUST, head.clone().add(Math.cos(b) * 0.4, 0, Math.sin(b) * 0.4), 1, 0, 0, 0, 0,
                            new Particle.DustOptions(Color.fromRGB(255, 230, 90), 0.9f));
                }
            }
            if (le instanceof org.bukkit.entity.Mob mob) mob.setTarget(null);
            if (le instanceof Player sp && (en.getValue() - now) % 20 == 0) sp.sendActionBar(legacy(ChatColor.YELLOW + "" + ChatColor.BOLD + "STUNNED"));
        }
        stunImmune.values().removeIf(v -> v < now);
    }

    /** A stunned player can't hit anything (and neither can their arrows). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onStunnedAttack(EntityDamageByEntityEvent event) {
        Entity d = event.getDamager();
        Entity who = d instanceof Projectile pr && pr.getShooter() instanceof Entity s ? s : d;
        if (isStunned(who)) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (stunned.remove(p.getUniqueId()) != null) unstun(p);
    }

    // =====================================================================================================
    //  a rigged body (Rocco and Werner): 6 skin pieces + gold chains (+ Rocco's book) on the torso
    // =====================================================================================================
    abstract class Body {
        final World world;
        Vector pos;
        float yaw;
        final float scale;
        final Slime hitbox;
        final ItemDisplay[] parts;
        final List<ItemDisplay> worn = new ArrayList<>();
        Pose pose = standPose(), shown = standPose();
        int ticks, flinch, hitFlash, attack = -1, t, recover = 20, lastAttack = -1;
        double lift, fallV;
        boolean flashing, keepAlive = true, walking;
        float stride, moveAmt, flinchSide;
        Vector lastDraw;
        final Color flashColor;
        final Map<UUID, Integer> hitCd = new HashMap<>();

        Body(Location at, String skin, float scale, String tag, String name, double hitboxScale, boolean book, Color flash) {
            world = at.getWorld();
            pos = at.toVector();
            this.scale = scale;
            this.flashColor = flash;
            hitbox = (Slime) world.spawnEntity(at, EntityType.SLIME, false);
            hitbox.setSize(2);
            pl.setupHitbox(hitbox, 1000, tag, name);
            AttributeInstance hs = hitbox.getAttribute(Attribute.SCALE);
            if (hs != null) hs.setBaseValue(hitboxScale);
            String[] pieces = {"_leg_r", "_leg_l", "_body", "_arm_r", "_arm_l", "_head"};
            parts = new ItemDisplay[6];
            for (int i = 0; i < 6; i++) { parts[i] = pl.spawnDisplay(at, skin + pieces[i], scale, 2, Display.Billboard.FIXED); parts[i].setViewRange(6f); }
            worn.add(pl.spawnDisplay(at, "cosmetic/rocco_chains", scale, 2, Display.Billboard.FIXED));
            if (book) worn.add(pl.spawnDisplay(at, "cosmetic/rocco_book", scale, 2, Display.Billboard.FIXED));
            for (ItemDisplay d : worn) d.setViewRange(6f);
        }

        Location loc() { return pos.toLocation(world, yaw, 0); }
        Vector fwd() { return new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw))); }
        Vector chest() { return pos.clone().add(new Vector(0, 1.2 * scale, 0)); }

        void face(Vector target, float maxStep) {
            Vector d = target.clone().subtract(pos).setY(0);
            if (d.lengthSquared() < 0.01) return;
            float want = (float) Math.toDegrees(Math.atan2(-d.getX(), d.getZ()));
            float diff = ((want - yaw) % 360 + 540) % 360 - 180;
            yaw += Math.max(-maxStep, Math.min(maxStep, diff));
        }

        void faceNow(Vector target) { face(target, 360); }

        double floorY(double x, double z, double fromY) {
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z), y = (int) Math.floor(fromY);
            y = Math.max(world.getMinHeight() + 1, Math.min(world.getMaxHeight() - 1, y));
            if (world.getBlockAt(bx, y, bz).getType().isSolid()) {
                for (int k = 0; k < 6 && y < world.getMaxHeight() - 1; k++, y++) if (!world.getBlockAt(bx, y + 1, bz).getType().isSolid()) return y + 1;
                return fromY;
            }
            for (int k = 0; k < 48 && y > world.getMinHeight(); k++, y--) if (world.getBlockAt(bx, y - 1, bz).getType().isSolid()) return y;
            return fromY;
        }

        /** Walks a step (up at most a block); returns false at a wall. */
        boolean step(Vector delta) {
            Vector next = pos.clone().add(delta.clone().setY(0));
            double floor = floorY(next.getX(), next.getZ(), pos.getY() + 0.5);
            if (floor - pos.getY() > 1.2) return false;
            pos.setX(next.getX()); pos.setZ(next.getZ());
            clamp();
            settle();
            return true;
        }

        void settle() {
            double floor = floorY(pos.getX(), pos.getZ(), pos.getY() + 0.5);
            if (floor >= pos.getY() - 0.01) { pos.setY(floor); fallV = 0; return; }
            fallV = Math.min(fallV + 0.08, 1.2);
            pos.setY(Math.max(floor, pos.getY() - fallV));
        }

        /**
         * Teleports (blinks) to a spot on the ground. BUG FIX: a blink behind someone standing against a wall put him
         * inside the wall, then on top of it. If the spot isn't open, or its floor is far off, he lands at `fallback`.
         */
        void blinkTo(Vector to, Vector fallback) {
            afterimage();
            world.spawnParticle(Particle.DUST, loc().add(0, 1, 0), 14, 0.3, 0.8, 0.3, 0, new Particle.DustOptions(Color.fromRGB(150, 50, 200), 1.3f));
            Vector want = to.clone();
            clamp0(want);
            double floor = floorY(want.getX(), want.getZ(), want.getY() + 1.5);
            boolean open = world.getBlockAt(want.getBlockX(), (int) Math.floor(floor), want.getBlockZ()).isPassable()
                    && world.getBlockAt(want.getBlockX(), (int) Math.floor(floor) + 1, want.getBlockZ()).isPassable();
            if (!open || Math.abs(floor - to.getY()) > 2.5) { want = fallback.clone(); clamp0(want); floor = floorY(want.getX(), want.getZ(), want.getY() + 1); }
            pos = want; pos.setY(floor); fallV = 0;
            world.playSound(loc(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.1f, 1.3f);
        }

        void clamp0(Vector v) { }

        Vector behind(LivingEntity target, double dist) {
            return target.getLocation().toVector().subtract(flatDir(target.getLocation()).multiply(dist));
        }

        abstract void clamp();

        /** A fight line, at the bottom of the screen like the Kraken's (titles in the middle got in the way). */
        void say(String text, int stay) {
            for (Player p : world.getPlayers()) if (p.getLocation().toVector().distanceSquared(pos) < 80 * 80) p.sendActionBar(legacy(text));
        }


        boolean inFront(LivingEntity e, double range, double cone) {
            Vector to = e.getLocation().toVector().subtract(pos).setY(0);
            if (to.lengthSquared() > range * range) return false;
            if (to.lengthSquared() < 0.5) return true;
            return fwd().dot(to.normalize()) > cone;
        }

        /** A glowing purple ghost of the body that lingers a moment (blinks, dodges, counters). */
        void afterimage() {
            for (ItemDisplay pd : parts) {
                if (pd == null || !pd.isValid()) continue;
                ItemDisplay g = world.spawn(pd.getLocation(), ItemDisplay.class, x -> {
                    x.setItemStack(pd.getItemStack());
                    x.setTransformation(pd.getTransformation());
                    x.setGlowing(true);
                    x.setGlowColorOverride(Color.fromRGB(150, 50, 200));
                    x.setBrightness(new Display.Brightness(15, 15));
                    x.setViewRange(6f);
                    x.setPersistent(false);
                    x.addScoreboardTag(DISPLAY_TAG);
                });
                Bukkit.getScheduler().runTaskLater(pl, () -> { if (g.isValid()) g.remove(); }, 8L);
            }
        }

        void render(LivingEntity look) {
            Location root = loc().add(0, lift, 0);
            if (hitbox.isValid()) hitbox.teleport(root.clone().add(0, 0.05, 0));
            Pose p = pose.copy();
            float breath = (float) Math.sin(ticks * 0.08);
            p.add(BODY, breath * 1.5f, 0, 0).add(ARM_R, 0, 0, -breath * 2).add(ARM_L, 0, 0, breath * 2);
            if (look != null && look.isValid() && look.getWorld().equals(world)) {
                Vector to = look.getEyeLocation().toVector().subtract(pos.clone().add(new Vector(0, 1.6 * scale, 0)));
                float want = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
                float rel = ((want - yaw) % 360 + 540) % 360 - 180;
                float pitch = (float) -Math.toDegrees(Math.atan2(to.getY(), Math.max(0.5, Math.hypot(to.getX(), to.getZ()))));
                p.add(HEAD, Math.max(-30, Math.min(30, pitch)), -Math.max(-50, Math.min(50, rel)), 0);
            }
            // locomotion: the legs follow the ground actually covered (no more sliding feet), with a bob and a sway
            double moved = lastDraw == null ? 0 : Math.hypot(pos.getX() - lastDraw.getX(), pos.getZ() - lastDraw.getZ());
            lastDraw = pos.clone();
            if (moved < 1.5) stride += (float) (moved * 2.5 / scale);   // a blink doesn't count as steps
            moveAmt += ((float) Math.min(1, moved < 1.5 ? moved / (0.3 * scale) : 0) - moveAmt) * 0.3f;
            if ((walking || attack < 0) && moveAmt > 0.04f) {
                float sw = (float) Math.sin(stride), a = Math.min(1, moveAmt * 1.2f);
                float legSwing = 26 + 18 * a;
                p.r[LEG_R][0] += (sw * legSwing - p.r[LEG_R][0]) * a;
                p.r[LEG_L][0] += (-sw * legSwing - p.r[LEG_L][0]) * a;
                p.r[LEG_R][2] *= 1 - a; p.r[LEG_L][2] *= 1 - a;
                p.add(BODY, 7 * a, sw * 6 * a, 0).add(ARM_R, -sw * 9 * a, 0, 0).add(ARM_L, sw * 9 * a, 0, 0).add(HEAD, -4 * a, 0, 0);
                p.drop += (float) -Math.abs(Math.cos(stride)) * 0.08f * a;
            }
            walking = false;
            // a hit rocks him back, twisting away from the side it came from
            if (flinch > 0) { p.add(BODY, -flinch * 2.4f, flinchSide * flinch * 2.6f, flinchSide * flinch * 1.2f).add(HEAD, -flinch * 2.2f, flinchSide * flinch * 2f, 0); flinch--; }
            // moves are keyframed already: follow them closely; idling and walking ease more softly
            shown = Pose.lerp(shown, p, attack >= 0 ? 0.62f : 0.38f);
            pl.renderRig(parts, null, root, yaw, scale, shown);
            for (ItemDisplay d : worn) attach(d, root, shown);
            boolean want = hitFlash > 0;
            if (hitFlash > 0) hitFlash--;
            if (want != flashing) {
                flashing = want;
                for (ItemDisplay pd : parts) if (pd != null && pd.isValid()) { pd.setGlowColorOverride(flashColor); pd.setGlowing(want); }
            }
        }

        /** Something worn on the torso (a cosmetic model, centered on the torso) follows the body piece. */
        void attach(ItemDisplay d, Location root, Pose ps) {
            if (d == null || !d.isValid()) return;
            Quaternionf qYaw = new Quaternionf().rotateY((float) -Math.toRadians(yaw));
            Quaternionf qBody = pl.euler(ps.r[BODY]);
            Vector3f c = qBody.transform(new Vector3f(0, 6, 0)).add(0, 12, 0).mul(scale / 16f);
            qYaw.transform(c);
            Location at = root.clone().add(c.x, c.y + ps.drop, c.z);
            at.setYaw(0); at.setPitch(0);
            d.teleport(at);
            d.setInterpolationDelay(0);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(qYaw).mul(qBody).mul(pl.facing(0)), new Vector3f(scale, scale, scale), new Quaternionf()));
        }

        /** Hits a player: shields count (BLOCKABLE fully blocked = no stun), then the stun and knockback. */
        boolean hit(Player p, double dmg, Guard guard, int stunTicks, double knock) {
            if (hitCd.containsKey(p.getUniqueId()) || !survival(p)) return false; // (never the admin playing him)
            hitCd.put(p.getUniqueId(), 8);
            Vector from = chest();
            boolean blocked = guard == Guard.BLOCKABLE && facingBlock(p, from);
            pl.hurt(p, dmg * damageScale(), hitbox, from, guard);
            if (blocked) return false;
            if (stunTicks > 0) stun(p, stunTicks);
            if (knock > 0) {
                Vector kb = p.getLocation().toVector().subtract(pos).setY(0);
                if (kb.lengthSquared() > 0.01) p.setVelocity(kb.normalize().multiply(knock).setY(0.3 + knock * 0.15));
            }
            return true;
        }

        /** Hits any living thing (an ally Werner hitting monsters). Players go through hit(). */
        void hitAny(LivingEntity e, double dmg, Guard guard, int stunTicks, double knock, Player credit) {
            if (e instanceof Player p && credit == null) { hit(p, dmg, guard, stunTicks, knock); return; }
            if (hitCd.containsKey(e.getUniqueId())) return;
            hitCd.put(e.getUniqueId(), 8);
            if (credit != null) e.damage(dmg * damageScale(), credit); else e.damage(dmg * damageScale(), hitbox);
            if (stunTicks > 0) stun(e, stunTicks);
            if (knock > 0) {
                Vector kb = e.getLocation().toVector().subtract(pos).setY(0);
                if (kb.lengthSquared() > 0.01) e.setVelocity(kb.normalize().multiply(knock).setY(0.3));
            }
        }

        double damageScale() { return 1; }

        /** Rocks the body away from whoever hit it. */
        void flinchFrom(Entity by) {
            flinch = 6; hitFlash = 3;
            if (by == null) { flinchSide = 0; return; }
            Vector to = by.getLocation().toVector().subtract(pos).setY(0);
            Vector left = new Vector(-fwd().getZ(), 0, fwd().getX());
            flinchSide = to.lengthSquared() < 1e-4 ? 0 : -(float) Math.signum(to.dot(left));
        }

        void tickCommon() {
            ticks++;
            hitCd.replaceAll((k, v) -> v - 1);
            hitCd.values().removeIf(v -> v <= 0);
            if (keepAlive && hitbox.isValid() && hitbox.getHealth() < 900) hitbox.setHealth(1000);
        }

        void removeBody() {
            for (ItemDisplay d : parts) if (d != null && d.isValid()) d.remove();
            for (ItemDisplay d : worn) if (d.isValid()) d.remove();
            if (hitbox.isValid()) hitbox.remove();
        }
    }

    // ---- the poses themselves live in VendettaAnims (tools/anim/preview.sh renders them) ----

    // =====================================================================================================
    //  ROCCO VENDETTA
    // =====================================================================================================
    static final int ARRIVE = 0, FIGHT = 1, TRANSITION = 2, DEFEAT = 3, GONE = 4;
    static final int M_KICK = 1, M_PUNCH = 2, M_PAYBACK = 3, M_ASSEMBLE = 4, M_BEHIND = 5, M_COUPONS = 6, M_EXTERMINATE = 7, M_VENGEANCE = 8, M_TESTS = 9,
            M_COUNTER = 10;

    final class Rocco extends Body {
        final Location home;
        double hp, maxHp;
        int state = ARRIVE, st, phase = 1, tattoos, tattooCd, sworn, vengeance, lonely, assembleCd, chaseT, failStreak;
        boolean guarding, wernerCalled, rewarded;
        final Set<UUID> fighters = new HashSet<>();
        final List<LivingEntity> fam = new ArrayList<>();
        final List<LivingEntity> targets = new ArrayList<>(); // No More Tests
        Werner werner;
        LivingEntity victim; Vector lock;  // Extermination
        Player focus;                      // who this move is aimed at
        Player counterOn;                  // Payback's victim
        int scaledFor = 1, paybackCd, extermCd;
        boolean leftNext;                  // punches alternate hands
        double rage = 1;                  // Werner died: he hits harder
        final BossBar bar;
        final Set<UUID> listeners = new HashSet<>();
        long musicStart = -1;

        Rocco(Location at, Player by) {
            super(at, "rocco", (float) c("scale", 1.2), ROCCO_TAG, "Rocco Vendetta", c("hitbox-scale", 2.25), true, Color.fromRGB(190, 80, 255));
            pos.setY(floorY(pos.getX(), pos.getZ(), pos.getY() + 2)); // BUG FIX: on a slope he hung in the air or stood in the hill
            home = pos.toLocation(world);
            if (by != null) { fighters.add(by.getUniqueId()); faceNow(by.getLocation().toVector()); }
            maxHp = hp = c("health", 4000);
            pl.proxy(hitbox, parts[2], EntityType.VINDICATOR, scale);
            bar = Bukkit.createBossBar(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Rocco Vendetta", BarColor.PURPLE, BarStyle.SEGMENTED_20);
            lift = 0;
            world.strikeLightningEffect(at);
            world.spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 1, 0), 60, 0.6, 1, 0.6, 0.02);
        }

        @Override void clamp() { clamp0(pos); }

        /** Keeps a point inside his arena (Werner uses it too). */
        @Override void clamp0(Vector v) {
            double r = c("arena-radius", 40);
            v.setX(Math.max(home.getX() - r, Math.min(home.getX() + r, v.getX())));
            v.setZ(Math.max(home.getZ() - r, Math.min(home.getZ() + r, v.getZ())));
        }

        /**
         * Built for a group (7 is the recommended size): his health grows with every fighter, and grows again if more
         * people join mid-fight (it never shrinks, and the bar keeps its fill).
         */
        void scaleFor(int n) {
            n = Math.max(1, Math.min(n, (int) c("max-scaled-fighters", 12)));
            if (n <= scaledFor) return;
            double frac = hp / maxHp;
            scaledFor = n;
            maxHp = c("health", 4000) * (1 + c("health-per-extra-fighter", 0.35) * (n - 1));
            hp = maxHp * frac;
        }

        @Override double damageScale() {
            return (1 + tattoos * c("tattoo-damage", 0.01)) * (vengeance > 0 ? 1 + c("vengeance.damage-bonus", 0.25) : 1) * rage;
        }

        List<Player> active() {
            List<Player> a = new ArrayList<>();
            for (Player p : world.getPlayers()) {
                if (!survival(p) || p.isDead()) continue;
                if (p.getLocation().toVector().distanceSquared(pos) > 56 * 56) continue;
                a.add(p);
            }
            return a;
        }

        Player nearest(List<Player> a) {
            Player best = null; double bd = Double.MAX_VALUE;
            for (Player p : a) { double d = p.getLocation().toVector().distanceSquared(pos); if (d < bd) { bd = d; best = p; } }
            return best;
        }

        void shout(String big, String small, int stay) {
            say(big + (small.isEmpty() ? "" : "  " + small), stay);
        }

        double speed() {
            double s = phase == 3 ? c("phase3-speed", 0.42) : phase == 2 ? c("phase2-speed", 0.34) : c("walk-speed", 0.28);
            return vengeance > 0 ? s * (1 + c("vengeance.speed-bonus", 0.4)) : s;
        }

        // ---------- music: his battle theme (3:10), looped for the whole fight ----------
        void playMusic() {
            musicStart = System.currentTimeMillis();
            if (!pl.getConfig().getBoolean("rocco.music.enabled", true)) return;
            for (Player p : world.getPlayers()) if (p.getLocation().toVector().distanceSquared(pos) < 96 * 96) listen(p);
        }

        void listen(Player p) {
            p.stopSound(org.bukkit.SoundCategory.MUSIC);
            p.playSound(p, "faultline:rocco.music", org.bukkit.SoundCategory.RECORDS, (float) c("music.volume", 1.0), 1f);
            listeners.add(p.getUniqueId());
        }

        void stopMusic() {
            for (UUID id : listeners) { Player p = Bukkit.getPlayer(id); if (p != null) p.stopSound("faultline:rocco.music", org.bukkit.SoundCategory.RECORDS); }
            listeners.clear();
            musicStart = -1;
        }

        void musicTick() {
            if (musicStart < 0 || !pl.getConfig().getBoolean("rocco.music.enabled", true)) return;
            if (System.currentTimeMillis() - musicStart > c("music.length-seconds", 190) * 1000) { stopMusic(); playMusic(); return; }
            for (Player p : world.getPlayers()) // someone who walks up mid-fight hears it too
                if (!listeners.contains(p.getUniqueId()) && survival(p) && p.getLocation().toVector().distanceSquared(pos) < 64 * 64) listen(p);
        }

        // ---------- the update ----------
        void tick() {
            tickCommon(); st++;
            if (!hitbox.isValid() && state < DEFEAT) { leave(null); return; } // his area unloaded
            List<Player> a = active();
            for (Player p : a) fighters.add(p.getUniqueId());
            if (a.isEmpty() && state == FIGHT && pl.pilot(this) == null) { if (++lonely > 600) { leave(ChatColor.DARK_PURPLE + "Rocco Vendetta " + ChatColor.GRAY + "lost interest and left."); return; } }
            else lonely = 0;
            if (sworn > 0) sworn--;
            if (paybackCd > 0) paybackCd--;
            if (extermCd > 0) extermCd--;
            if (state == FIGHT && ticks % 100 == 0) scaleFor(a.size());
            if (vengeance > 0) { vengeance--; if (ticks % 3 == 0) world.spawnParticle(Particle.DUST, loc().add(0, 1.2 * scale, 0), 2, 0.4, 0.8, 0.4, 0, new Particle.DustOptions(Color.fromRGB(200, 30, 60), 1.1f)); }
            if (tattooCd > 0) tattooCd--;
            if (assembleCd > 0) assembleCd--;
            try {
                switch (state) {
                    case ARRIVE -> arriveTick(a);
                    case FIGHT -> fightTick(a);
                    case TRANSITION -> transitionTick(a);
                    case DEFEAT -> defeatTick();
                    default -> { }
                }
                failStreak = 0;
            } catch (RuntimeException e) {
                pl.logBossPart("Rocco Vendetta", "state " + state + ", move " + attack + " (tick " + t + ")", e);
                attack = -1; recover = 20; guarding = false; lift = 0;
                if (++failStreak > 100) { leave(ChatColor.DARK_PURPLE + "Rocco Vendetta " + ChatColor.GRAY + "vanished."); return; }
            }
            if (rocco != this) return;
            if (werner != null) { pl.bossPart("Werner", "tick", werner::tick); if (werner.gone) werner = null; }
            fam.removeIf(m -> !m.isValid() || m.isDead());
            pl.bossPart("Rocco Vendetta", "drawing", () -> render(state == FIGHT ? nearest(a) : null));
            if (ticks % 5 == 0) pl.bossPart("Rocco Vendetta", "boss bar", this::updateBar);
            if (ticks % 20 == 0 && state != DEFEAT) pl.bossPart("Rocco Vendetta", "music", this::musicTick);
        }

        void arriveTick(List<Player> a) {
            Player n = nearest(a);
            if (n != null) face(n.getLocation().toVector(), 10);
            pose = VendettaAnims.roccoArrive(st);
            if (st == 2) {
                for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(home) < 96 * 96)
                    p.showTitle(Title.title(legacy(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "ROCCO VENDETTA"), legacy(ChatColor.GRAY + "Big brother of the Vendetta family"),
                            Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(2500), java.time.Duration.ofMillis(700))));
                world.playSound(home, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 2f, 0.6f);
            }
            if (st == 50) say(ChatColor.LIGHT_PURPLE + "\"You signed. Now you pay.\"", 50);
            settle();
            if (st >= 70) { state = FIGHT; st = 0; recover = 10; scaleFor(a.size()); playMusic(); }
        }

        void fightTick(List<Player> a) {
            Player pilotP = pl.pilot(this);
            if (a.isEmpty() && pilotP == null) { pose = Pose.lerp(pose, foldArmsPose(), 0.2f); return; }
            Player target = pilotP != null ? pl.pilotTarget(pilotP, a) : focus != null && a.contains(focus) ? focus : nearest(a);
            if (target == null) return;
            if (pilotP != null && attack < 0) { // boss form: follows the admin and uses the move they pick
                Vector to = pilotP.getLocation().toVector().subtract(pos).setY(0);
                face(pos.clone().add(pilotP.getLocation().getDirection().setY(0).multiply(5)), 15);
                if (to.length() > 0.3) { step(to.normalize().multiply(Math.min(to.length(), speed() * 1.6))); walking = true; }
                pose = VendettaAnims.roccoStance(ticks);
                int q = pl.takeMove(this);
                if (q < 0) return;
                startMove(q);
            }
            if (attack < 0) {
                face(target.getLocation().toVector(), 14);
                if (recover > 0) { recover--; pose = VendettaAnims.roccoStance(ticks); settle(); return; }
                startMove(pickMove());
                // with a crowd he spreads the pain: about 2 moves in 5 go after someone other than the closest
                // and a move stays on whoever it started on (Watch Your Back! used to switch victims halfway through)
                focus = a.size() > 1 && random.nextDouble() < c("spread-chance", 0.4) ? a.get(random.nextInt(a.size())) : target;
                target = focus;
            }
            switch (attack) {
                case M_KICK -> kick(target, a);
                case M_PUNCH -> punch(target, a);
                case M_PAYBACK -> payback();
                case M_ASSEMBLE -> assemble(a);
                case M_BEHIND -> watchYourBack(target);
                case M_COUPONS -> coupons(a);
                case M_EXTERMINATE -> exterminate(a);
                case M_VENGEANCE -> swearVengeance();
                case M_TESTS -> noMoreTests(a);
                case M_COUNTER -> counterTick();
                default -> end(10);
            }
            t++;
        }

        int pickMove() {
            if (tattoos >= c("max-tattoos", 50) && phase >= 2 && extermCd <= 0) return M_EXTERMINATE;
            List<Integer> pool = new ArrayList<>(List.of(M_KICK, M_PUNCH, M_KICK, M_PUNCH, M_BEHIND));
            if (paybackCd <= 0) pool.add(M_PAYBACK);
            if (assembleCd <= 0 && fam.size() < c("assemble.max-alive", 8)) pool.add(M_ASSEMBLE);
            if (phase >= 2) pool.addAll(List.of(M_COUPONS, M_VENGEANCE, M_TESTS, M_TESTS));
            if (phase >= 3) pool.addAll(List.of(M_BEHIND, M_COUPONS, M_TESTS));
            if (vengeance > 0) pool.removeIf(m -> m == M_VENGEANCE);
            int pick;
            do pick = pool.get(random.nextInt(pool.size())); while (pick == lastAttack && random.nextInt(3) > 0);
            return pick;
        }

        void startMove(int m) {
            if (m == M_EXTERMINATE && tattoos < c("max-tattoos", 50) && pl.pilot(this) == null) m = M_KICK;
            attack = m; lastAttack = m; t = 0; chaseT = 0; guarding = false; targets.clear(); victim = null; lock = null;
        }

        void end(int rest) {
            attack = -1; t = 0; guarding = false; lift = 0;
            recover = (int) Math.round(rest * (phase == 3 ? 0.6 : phase == 2 ? 0.8 : 1.0) * (vengeance > 0 ? 0.7 : 1));
        }

        /** Closes in on a melee target; true once he's in reach. Gives up (and blinks behind you) after 3 seconds. */
        boolean closeIn(Player target, double reach) {
            Vector to = target.getLocation().toVector().subtract(pos).setY(0);
            if (to.length() <= reach) return true;
            face(target.getLocation().toVector(), 18);
            if (!step(to.normalize().multiply(speed())) || ++chaseT > 60) { attack = M_BEHIND; t = -1; return false; } // (t++ makes it 0)
            pose = VendettaAnims.roccoGuardWalk(ticks);
            walking = true;
            t--; // the move's clock waits while he walks
            return false;
        }

        // ---------- 1) Kick: a heavy front kick. Stuns for 3 seconds. ----------
        void kick(Player target, List<Player> a) {
            if (t == 0 && !closeIn(target, 3.0)) return;
            pose = VendettaAnims.roccoKick(t);
            if (t < 8) face(target.getLocation().toVector(), 20);
            if (t == 7 || t == 8) step(fwd().multiply(0.22 * scale)); // he drives into it
            if (t == 9) {
                world.playSound(loc(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.6f, 0.6f);
                world.playSound(loc(), Sound.ENTITY_IRON_GOLEM_ATTACK, 1.2f, 0.8f);
                world.spawnParticle(Particle.SWEEP_ATTACK, loc().add(fwd().multiply(1.5 * scale)).add(0, 0.9 * scale, 0), 1);
                for (Player p : a) if (inFront(p, 3.6, 0.3)) hit(p, c("kick-damage", 12), Guard.HEAVY, (int) c("stun-ticks", 60), 1.1);
            }
            if (t >= 24) end(14);
        }

        // ---------- 2) Punch: a step-in straight. Stuns for 3 seconds. ----------
        void punch(Player target, List<Player> a) {
            if (t == 0 && !closeIn(target, 2.8)) return;
            if (t == 0) leftNext = !leftNext;
            pose = VendettaAnims.roccoPunch(t, leftNext);
            if (t < 6) face(target.getLocation().toVector(), 20);
            if (t == 6 || t == 7) step(fwd().multiply(0.3 * scale)); // a step in behind the punch
            if (t == 7) {
                world.playSound(loc(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.6f, 0.6f);
                world.spawnParticle(Particle.CRIT, loc().add(fwd().multiply(1.3 * scale)).add(0, 1.4 * scale, 0), 12, 0.2, 0.2, 0.2, 0.25);
                for (Player p : a) if (inFront(p, 3.2, 0.4)) hit(p, c("punch-damage", 11), Guard.BLOCKABLE, (int) c("stun-ticks", 60), 0.8);
            }
            if (t >= 18) end(12);
        }

        // ---------- 3) Payback: a counter stance. Hit him and he punishes you. ----------
        void payback() {
            guarding = t < 40;
            pose = VendettaAnims.roccoGuard(t);
            if (t == 0) { world.playSound(loc(), Sound.ITEM_SHIELD_BLOCK, 1.4f, 0.5f); say(ChatColor.LIGHT_PURPLE + "\"Go on. Hit me.\"", 30); }
            if (guarding && t % 3 == 0) world.spawnParticle(Particle.DUST, loc().add(0, 1.3 * scale, 0), 3, 0.5, 0.7, 0.5, 0, new Particle.DustOptions(Color.fromRGB(200, 30, 60), 1f));
            if (t >= 40) { paybackCd = (int) (c("payback-cooldown-seconds", 8) * 20); end(10); }
        }

        /** Hit during Payback: he's behind you in a blink, and the uppercut lands a moment later. */
        void counter(Player p) {
            guarding = false;
            paybackCd = (int) (c("payback-cooldown-seconds", 8) * 20);
            blinkTo(behind(p, 1.6), p.getLocation().toVector());
            faceNow(p.getLocation().toVector());
            world.playSound(loc(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.6f, 0.5f);
            p.sendActionBar(legacy(ChatColor.RED + "PAYBACK! " + ChatColor.GRAY + "Don't hit him while he's guarding."));
            counterOn = p;
            attack = M_COUNTER; t = 0;
        }

        void counterTick() {
            pose = VendettaAnims.roccoUppercut(t);
            Player p = counterOn;
            if (p != null && t < 5) faceNow(p.getLocation().toVector());
            if (t == 5 && p != null && p.isValid()) {
                world.spawnParticle(Particle.EXPLOSION, p.getLocation().add(0, 1, 0), 1);
                world.playSound(loc(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.8f, 0.5f);
                hitCd.remove(p.getUniqueId());
                if (p.getLocation().toVector().distanceSquared(pos) < 4 * 4)
                    hit(p, c("payback-damage", 14) + tattoos * c("payback-per-tattoo", 0.25), Guard.UNBLOCKABLE, (int) c("stun-ticks", 60), 1.3);
            }
            if (t >= 18) { counterOn = null; end(16); }
        }

        // ---------- 4) Assemble: whistles, and the family shows up ----------
        void assemble(List<Player> a) {
            pose = VendettaAnims.roccoWhistle(t);
            if (t == 0) say(ChatColor.LIGHT_PURPLE + "\"Assemble!\"", 30);
            if (t == 7) { world.playSound(loc(), Sound.ENTITY_VILLAGER_CELEBRATE, 1.6f, 0.6f); world.playSound(loc(), Sound.ENTITY_BREEZE_WHIRL, 1.4f, 1.8f); }
            if (t == 12) {
                int n = (int) Math.min(c("assemble.base", 2) + a.size(), c("assemble.max-alive", 8) - fam.size());
                for (int i = 0; i < n; i++) {
                    double ang = Math.PI * 2 * i / Math.max(1, n) + random.nextDouble();
                    Vector at = pos.clone().add(new Vector(Math.cos(ang) * 3, 0, Math.sin(ang) * 3));
                    at.setY(floorY(at.getX(), at.getZ(), pos.getY() + 2));
                    LivingEntity m = spawnFam(at.toLocation(world), null);
                    if (m == null) continue;
                    fam.add(m);
                    Player tgt = a.isEmpty() ? null : a.get(random.nextInt(a.size()));
                    if (tgt != null && m instanceof org.bukkit.entity.Mob mob) mob.setTarget(tgt);
                }
                assembleCd = (int) (c("assemble.cooldown-seconds", 30) * 20);
            }
            if (t >= 26) end(10);
        }

        // ---------- 5) Watch Your Back!: behind you, a hit, behind you again, another hit ----------
        void watchYourBack(Player target) {
            if (t == 0) { say(ChatColor.LIGHT_PURPLE + "\"Watch your back!\"", 25); blinkTo(behind(target, 2.0), target.getLocation().toVector()); }
            if (t == 14) blinkTo(behind(target, 2.0), target.getLocation().toVector());
            if (t < 6 || (t >= 14 && t < 20)) faceNow(target.getLocation().toVector());
            pose = t < 14 ? VendettaAnims.roccoPunch(t + 1, false) : VendettaAnims.roccoPunch(t - 13, true);
            if (t == 6 || t == 20) {
                world.playSound(loc(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.5f, 0.7f);
                hitCd.remove(target.getUniqueId());
                if (target.getLocation().toVector().distanceSquared(pos) < 3.6 * 3.6) hit(target, c("back-damage", 10), Guard.BLOCKABLE, 0, 0.7);
            }
            if (t >= 32) end(14);
        }

        // ---------- 6) MY HAIR COUPONS!!!: a charged blast. Run! Caught = half your health and a short stun. ----------
        void coupons(List<Player> a) {
            double r = c("coupons.radius", 7);
            int charge = (int) c("coupons.charge-ticks", 50);
            if (t == 0) { shout(ChatColor.DARK_RED + "" + ChatColor.BOLD + "MY HAIR COUPOOOOOOOOOOONS!!!", ChatColor.GRAY + "get away from him!", 40); world.playSound(loc(), Sound.ENTITY_RAVAGER_ROAR, 2f, 0.7f); }
            if (t < charge) {
                pose = VendettaAnims.roccoCharge(t, t / (float) charge);
                double rr = r * Math.min(1, (t + 6) / (double) charge);
                if (t % 2 == 0) ring(pos, rr, t > charge - 12 ? Color.fromRGB(255, 40, 40) : Color.fromRGB(200, 60, 220), 28);
                if (t % 10 == 0) world.playSound(loc(), Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 2f, 0.5f + t / (float) charge);
                world.spawnParticle(Particle.DUST, loc().add(0, 1.2 * scale, 0), 3, 0.5, 0.8, 0.5, 0, new Particle.DustOptions(Color.fromRGB(255, 200, 80), 1.3f));
            } else if (t == charge) {
                pose = VendettaAnims.roccoGroundSmash(0);
                world.playSound(loc(), Sound.ENTITY_GENERIC_EXPLODE, 2.5f, 0.6f);
                world.spawnParticle(Particle.EXPLOSION_EMITTER, loc().add(0, 0.5, 0), 1);
                for (int i = 0; i < 3; i++) ring(pos, r * (0.4 + i * 0.3), Color.fromRGB(255, 200, 80), 40);
                for (Player p : a) {
                    Vector rel = p.getLocation().toVector().subtract(pos);
                    if (Math.abs(rel.getY()) > 4 || rel.setY(0).length() > r) continue;
                    p.setHealth(Math.max(0.5, p.getHealth() / 2));          // half your health, whatever your armor
                    p.playHurtAnimation(0);
                    world.playSound(p.getLocation(), Sound.ENTITY_PLAYER_HURT, 1.2f, 0.8f);
                    stun(p, (int) c("coupons.stun-ticks", 20));
                    Vector kb = rel.lengthSquared() > 0.01 ? rel.normalize() : new Vector(1, 0, 0);
                    p.setVelocity(kb.multiply(1.0).setY(0.5));
                }
            } else if (t < charge + 24) pose = VendettaAnims.roccoGroundSmash(t - charge);
            else end(20);
        }

        // ---------- 7) COMPLETE AND TOTAL EXTERMINATION!!! (50 tattoos): a leap and a one-shot slam ----------
        void exterminate(List<Player> a) {
            int dodge = (int) c("extermination.dodge-ticks", 20);
            if (t == 0) {
                victim = a.isEmpty() ? null : a.get(random.nextInt(a.size()));
                Player pilotP = pl.pilot(this);
                if (pilotP != null) victim = pl.pilotTarget(pilotP, a);
                if (victim == null || victim == pilotP) { end(10); return; }
                shout(ChatColor.DARK_RED + "" + ChatColor.BOLD + "COMPLETE AND TOTAL", ChatColor.DARK_RED + "" + ChatColor.BOLD + "EXTERMINATION!!!", 60);
                world.playSound(loc(), Sound.ENTITY_WITHER_SPAWN, 1.5f, 0.8f);
            }
            if (victim == null || !victim.isValid()) { end(10); return; }
            if (t < 14) pose = VendettaAnims.roccoLeapStart(t);
            else if (t < 70 + dodge) pose = VendettaAnims.roccoAirborne(t, (t - 14) / (float) (56 + dodge));
            if (t >= 14 && t < 30) { // the leap
                lift = Math.sin((t - 14) / 16.0 * Math.PI / 2) * c("extermination.height", 22);
                if (t == 14) { world.playSound(loc(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 2f, 0.5f); world.spawnParticle(Particle.EXPLOSION, loc(), 3, 0.5, 0.1, 0.5, 0); }
            }
            Vector center = lock != null ? lock : victim.getLocation().toVector();
            if (t >= 14 && t < 70 + dodge) { // the target follows the victim, then locks: get out of the red ring
                if (t % 2 == 0) ring(center, 2.6, lock != null ? Color.fromRGB(255, 20, 20) : Color.fromRGB(200, 60, 220), 20);
                if (t % 8 == 0) world.playSound(center.toLocation(world), Sound.BLOCK_NOTE_BLOCK_BELL, 2f, lock != null ? 1.8f : 0.8f);
                if (t >= 30) { pos.setX(center.getX()); pos.setZ(center.getZ()); }
            }
            if (t == 70) {
                lock = victim.getLocation().toVector();
                // BUG FIX: a stun from Werner (or a kick) could pin the victim in the locked ring: certain death, no dodge.
                if (stunned.remove(victim.getUniqueId()) != null) unstun(victim);
                stunImmune.put(victim.getUniqueId(), now + dodge + 5);
                if (victim instanceof Player vp) vp.sendActionBar(legacy(ChatColor.RED + "" + ChatColor.BOLD + "MOVE! " + ChatColor.GRAY + "Get out of the red ring!"));
            }
            if (t == 70 + dodge) { // the slam
                pos = lock.clone(); pos.setY(floorY(pos.getX(), pos.getZ(), lock.getY() + 2));
                lift = 0; pose = VendettaAnims.roccoSlamLand(0);
                world.playSound(loc(), Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.5f);
                world.spawnParticle(Particle.EXPLOSION_EMITTER, loc(), 2, 0.5, 0.1, 0.5, 0);
                world.spawnParticle(Particle.BLOCK, loc().add(0, 0.1, 0), 80, 2.5, 0.1, 2.5, 0, loc().subtract(0, 1, 0).getBlock().getBlockData());
                for (Player p : a) {
                    double d = p.getLocation().toVector().subtract(pos).setY(0).length();
                    if (d <= 2.6 && Math.abs(p.getLocation().getY() - pos.getY()) < 4) {
                        p.setHealth(0); // no matter what: armor, shields and totems don't help
                        Bukkit.broadcastMessage(ChatColor.DARK_RED + p.getName() + ChatColor.GRAY + " was exterminated by Rocco Vendetta.");
                    } else if (d < 7) hit(p, c("extermination.splash-damage", 8), Guard.UNBLOCKABLE, 0, 1.2);
                }
                tattoos = 0;
                extermCd = (int) (c("extermination.cooldown-seconds", 40) * 20);
            }
            if (t > 70 + dodge) pose = VendettaAnims.roccoSlamLand((t - 70 - dodge) * 40f / 32f); // the opening to punish him
            if (t >= 102 + dodge) end(20);
        }

        // ---------- 8) Swear Vengeance: dodges everything for 5 seconds, and 3 buffs ----------
        void swearVengeance() {
            if (t == 0) {
                shout(ChatColor.DARK_RED + "" + ChatColor.BOLD + "I SWEAR VENGEANCE!", ChatColor.GRAY + "he dodges everything for 5 seconds", 40);
                world.playSound(loc(), Sound.ENTITY_EVOKER_PREPARE_WOLOLO, 2f, 0.6f);
                sworn = (int) c("vengeance.dodge-ticks", 100);
                vengeance = (int) c("vengeance.buff-ticks", 300);
            }
            pose = VendettaAnims.roccoSwear(t);
            if (t >= 26) end(6);
        }

        void dodge() {
            afterimage();
            Vector side = new Vector(-fwd().getZ(), 0, fwd().getX()).multiply(random.nextBoolean() ? 1.3 : -1.3);
            step(side);
            world.playSound(loc(), Sound.ENTITY_BREEZE_JUMP, 1f, 1.4f);
        }

        // ---------- 9) No More Tests: three people, three hits ----------
        void noMoreTests(List<Player> a) {
            if (t == 0) {
                say(ChatColor.LIGHT_PURPLE + "\"No more tests.\"", 40);
                List<Player> pool = new ArrayList<>(a);
                Collections.shuffle(pool, random);
                for (int i = 0; i < 3 && !pool.isEmpty(); i++) targets.add(pool.get(i % pool.size()));
                if (targets.isEmpty()) { end(10); return; }
            }
            int i = t / 12, k = t % 12;
            if (i >= 3) { if (t > 44) end(16); else pose = VendettaAnims.roccoStance(ticks); return; }
            pose = i == 2 ? VendettaAnims.roccoUppercut(k) : VendettaAnims.roccoPunch(k + 2, i == 1);
            LivingEntity tg = targets.get(i % targets.size());
            if (!tg.isValid() || tg.isDead()) return;
            if (k == 0) {
                Vector front = tg.getLocation().toVector().add(flatDir(tg.getLocation()).multiply(1.8));
                blinkTo(front, tg.getLocation().toVector()); faceNow(tg.getLocation().toVector());
            }
            if (k == 5) {
                world.playSound(loc(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.6f, 0.5f);
                if (tg instanceof Player p) { hitCd.remove(p.getUniqueId()); hit(p, c("tests-damage", 13), Guard.HEAVY, 0, 0.9); }
            }
        }

        void ring(Vector c0, double r, Color col, int n) {
            Location c = c0.toLocation(world);
            c.setY(floorY(c0.getX(), c0.getZ(), c0.getY() + 1) + 0.15);
            for (int i = 0; i < n; i++) {
                double ang = Math.PI * 2 * i / n;
                world.spawnParticle(Particle.DUST, c.clone().add(Math.cos(ang) * r, 0, Math.sin(ang) * r), 1, 0, 0, 0, 0, new Particle.DustOptions(col, 1.4f));
            }
        }

        // ---------- getting hit ----------
        void onHit(EntityDamageEvent event, Player by) {
            if (state != FIGHT) { event.setCancelled(true); return; }
            if (sworn > 0) { event.setCancelled(true); dodge(); return; }
            if (guarding && by != null && attack == M_PAYBACK) { event.setCancelled(true); counter(by); return; }
            if (attack == M_COUNTER && t < 5) { event.setCancelled(true); return; } // mid-blink: the counter can't be interrupted
            double dmg = event.getDamage();
            event.setDamage(0.001); // the hit lands (the Index credits it); his real health is tracked here
            if (by != null) fighters.add(by.getUniqueId());
            if (vengeance > 0) dmg *= 1 - c("vengeance.damage-reduction", 0.25);
            hp -= dmg * (1 - c("defense", 0.15));
            if (attack != M_EXTERMINATE) flinchFrom(by); else hitFlash = 3;
            if (tattooCd <= 0 && tattoos < c("max-tattoos", 50)) {
                tattoos++; tattooCd = (int) c("tattoo-cooldown-ticks", 10);
                if (tattoos == (int) c("max-tattoos", 50)) {
                    say(ChatColor.DARK_RED + "" + ChatColor.BOLD + "50 VENGEANCE TATTOOS", 40);
                    world.playSound(loc(), Sound.ENTITY_WARDEN_ROAR, 1.6f, 1.2f);
                }
            }
            if (!wernerCalled && hp <= maxHp * c("werner.at", 0.5)) {
                wernerCalled = true;
                Bukkit.getScheduler().runTask(pl, () -> { if (rocco == this && state == FIGHT) callWerner(); });
            }
            if (phase == 1 && hp <= maxHp * 2 / 3) { hp = maxHp * 2 / 3; startTransition(2); }
            else if (phase == 2 && hp <= maxHp / 3) { hp = maxHp / 3; startTransition(3); }
            else if (hp <= 0) { hp = 0; defeat(); }
        }

        void callWerner() {
            Vector at = pos.clone().add(fwd().multiply(-3));
            at.setY(floorY(at.getX(), at.getZ(), pos.getY() + 2));
            werner = new Werner(at.toLocation(world), this, null);
            world.strikeLightningEffect(at.toLocation(world));
            shout(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Little Brother Werner", ChatColor.GRAY + "\"Big brother! I'm here!\"", 50);
        }

        void startTransition(int next) {
            state = TRANSITION; st = 0; attack = -1; guarding = false; lift = 0;
            phase = next;
            shout(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "PHASE " + next,
                    ChatColor.LIGHT_PURPLE + (next == 2 ? "\"Enough playing. Let's settle this the family way.\"" : "\"You made me bleed... now EVERYONE pays.\""), 60);
            world.playSound(loc(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 2f, 0.5f);
        }

        void transitionTick(List<Player> a) {
            pose = VendettaAnims.roccoPhaseChange(st);
            if (st % 3 == 0) world.spawnParticle(Particle.DUST, loc().add(0, 1.2 * scale, 0), 6, 0.6, 1, 0.6, 0, new Particle.DustOptions(Color.fromRGB(150, 50, 200), 1.5f));
            if (st == 20) { // a shockwave pushes everyone back
                world.playSound(loc(), Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.8f);
                for (Player p : a) {
                    Vector away = p.getLocation().toVector().subtract(pos).setY(0);
                    if (away.lengthSquared() > 64 || away.lengthSquared() < 0.01) continue;
                    p.setVelocity(away.normalize().multiply(1.1).setY(0.45));
                }
            }
            if (st >= 60) { state = FIGHT; st = 0; recover = 10; }
        }

        // ---------- defeat ----------
        void defeat() {
            state = DEFEAT; st = 0; attack = -1; guarding = false; lift = 0; sworn = 0; keepAlive = false;
            stopMusic();
            bar.removeAll();
            for (LivingEntity m : fam) if (m.isValid()) m.remove();
            fam.clear();
            if (werner != null) { werner.leave(ChatColor.LIGHT_PURPLE + "Werner " + ChatColor.GRAY + "drags his brother out of the fight."); }
            say(ChatColor.LIGHT_PURPLE + "\"...Heh. You fight like family.\"", 80);
        }

        void defeatTick() {
            pose = VendettaAnims.roccoDefeat(st);
            if (st == 100) {
                world.playSound(loc(), Sound.ENTITY_PLAYER_DEATH, 2f, 0.6f);
                world.spawnParticle(Particle.DUST, loc().add(0, 1, 0), 80, 0.6, 1, 0.6, 0, new Particle.DustOptions(Color.fromRGB(150, 50, 200), 2f));
                Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Rocco Vendetta " + ChatColor.GRAY + "has fallen!");
                for (Player p : world.getPlayers()) if (p.getLocation().toVector().distanceSquared(pos) < 80 * 80)
                    p.showTitle(Title.title(legacy(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "ROCCO VENDETTA HAS FALLEN"), legacy(ChatColor.GRAY + "The debt is paid."),
                            Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(3000), java.time.Duration.ofMillis(800))));
                if (!rewarded) { rewarded = true; rewards(); }
                Bukkit.getScheduler().runTask(pl, () -> { if (hitbox.isValid()) hitbox.setHealth(0); }); // the kill (for the Index)
            }
            if (st > 140) {
                removeEverything();
                if (rocco == this) rocco = null;
                cooldownUntil = System.currentTimeMillis() + (long) (c("cooldown-minutes", 15) * 60000);
            }
        }

        void rewards() {
            for (UUID id : fighters) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.getWorld().equals(world)) continue;
                Location at = p.getLocation();
                int bags = (int) c("rewards.mythic-bags-min", 50) + random.nextInt((int) c("rewards.mythic-bags-extra", 101));
                pl.console("givemythicbag " + bags + " " + p.getName(), p);
                pl.console("givegoodiebag " + (int) c("rewards.goodie-bags", 10) + " " + p.getName(), p);
                if (random.nextDouble() < c("rewards.fist-chance", 1.0)) pl.dropLocked(at, p, item("fist"));
                giveCosmetic(p, 1.0);
                int xp = (int) c("rewards.xp", 3000);
                for (int left = xp; left > 0; ) { int n = Math.min(left, 100); left -= n; world.spawn(at, org.bukkit.entity.ExperienceOrb.class).setExperience(n); }
                pl.console("index discover " + p.getName() + " rocco_vendetta", p);
                p.sendMessage(ChatColor.DARK_PURPLE + "Rocco's Vendetta Fist is yours. Sneak + right-click it to pick a move.");
            }
        }

        void leave(String message) {
            if (message != null) Bukkit.broadcastMessage(message);
            removeEverything();
            if (rocco == this) rocco = null;
        }

        void updateBar() {
            bar.setProgress(Math.max(0, Math.min(1, hp / maxHp)));
            int max = (int) c("max-tattoos", 50);
            bar.setTitle(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + "Rocco Vendetta" + ChatColor.GRAY + "  Phase " + phase
                    + (tattoos >= max ? ChatColor.DARK_RED + "" + ChatColor.BOLD : ChatColor.LIGHT_PURPLE + "") + "  ✠ " + tattoos + "/" + max
                    + (sworn > 0 ? ChatColor.RED + "  DODGING" : vengeance > 0 ? ChatColor.RED + "  VENGEANCE" : ""));
            boolean show = state == FIGHT || state == TRANSITION || state == ARRIVE;
            for (Player p : world.getPlayers()) {
                boolean near = show && p.getLocation().toVector().distanceSquared(pos) < 90 * 90;
                if (near && !bar.getPlayers().contains(p)) bar.addPlayer(p);
                else if (!near && bar.getPlayers().contains(p)) bar.removePlayer(p);
            }
        }

        void removeEverything() {
            if (state == DEFEAT && !rewarded) { rewarded = true; try { rewards(); } catch (RuntimeException e) { pl.logBossPart("Rocco Vendetta", "rewards", e); } }
            bar.removeAll();
            stopMusic();
            if (werner != null) { werner.remove(); werner = null; }
            for (LivingEntity m : fam) if (m.isValid()) m.remove();
            fam.clear();
            removeBody();
            state = GONE;
        }
    }

    /** One of the three cosmetics, one the player doesn't have yet (FaultlineCosmetics). */
    void giveCosmetic(Player p, double chance) {
        if (random.nextDouble() >= chance) return;
        if (Bukkit.getPluginManager().getPlugin("FaultlineCosmetics") == null) return;
        List<String> pool = new ArrayList<>(List.of(COSMETICS));
        Collections.shuffle(pool, random);
        // ask FaultlineCosmetics (without depending on it) which ones they're missing
        String pick = pool.get(0);
        try {
            Class<?> fc = Class.forName("net.faultlinesmp.cosmetics.FaultlineCosmetics");
            java.lang.reflect.Method has = fc.getMethod("has", UUID.class, String.class);
            for (String id : pool) if (!(boolean) has.invoke(null, p.getUniqueId(), id)) { pick = id; break; }
        } catch (Throwable ignored) { }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "cosmetic unlock " + p.getName() + " " + pick);
    }

    // =====================================================================================================
    //  the family: Vendetta Enforcers (Assemble). His are hostile; the Fist's fight for its holder.
    // =====================================================================================================
    LivingEntity spawnFam(Location at, Player owner) {
        Vindicator v = at.getWorld().spawn(at, Vindicator.class, m -> {
            m.setPersistent(false);
            m.setRemoveWhenFarAway(false);
            m.setCanJoinRaid(false);
            m.setPatrolLeader(false);
            m.addScoreboardTag(owner == null ? FAM_TAG : FAM_ALLY_TAG);
            m.setCustomName((owner == null ? ChatColor.DARK_PURPLE : ChatColor.LIGHT_PURPLE) + "Vendetta Enforcer");
            m.setCustomNameVisible(owner != null);
            AttributeInstance hp = m.getAttribute(Attribute.MAX_HEALTH);
            if (hp != null) { hp.setBaseValue(c("assemble.health", 30)); m.setHealth(hp.getValue()); }
            ItemStack chest = new ItemStack(Material.LEATHER_CHESTPLATE), legs = new ItemStack(Material.LEATHER_LEGGINGS), boots = new ItemStack(Material.LEATHER_BOOTS);
            for (ItemStack s : List.of(chest, legs, boots)) { LeatherArmorMeta lm = (LeatherArmorMeta) s.getItemMeta(); lm.setColor(s == chest ? Color.fromRGB(112, 40, 146) : Color.fromRGB(30, 26, 34)); s.setItemMeta(lm); }
            m.getEquipment().setChestplate(chest); m.getEquipment().setLeggings(legs); m.getEquipment().setBoots(boots);
            m.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SWORD));
            for (EquipmentSlot s : EquipmentSlot.values()) { try { m.getEquipment().setDropChance(s, 0f); } catch (IllegalArgumentException ignored) { } }
            if (owner != null) m.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.getUniqueId() + ":" + (now + (int) c("fist.assemble-seconds", 30) * 20));
        });
        at.getWorld().spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
        return v;
    }

    /** Every second: friendly Enforcers pick monsters near their owner, and leave when their time is up. */
    void famTick() {
        for (World w : Bukkit.getWorlds()) for (Vindicator v : w.getEntitiesByClass(Vindicator.class)) {
            if (!v.getScoreboardTags().contains(FAM_ALLY_TAG)) continue;
            String data = v.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            String[] parts = data == null ? new String[0] : data.split(":");
            Player owner = parts.length == 2 ? Bukkit.getPlayer(UUID.fromString(parts[0])) : null;
            int until = parts.length == 2 ? Integer.parseInt(parts[1]) : 0;
            if (owner == null || now > until || !owner.getWorld().equals(w) || owner.getLocation().distanceSquared(v.getLocation()) > 48 * 48) {
                w.spawnParticle(Particle.LARGE_SMOKE, v.getLocation().add(0, 1, 0), 12, 0.3, 0.6, 0.3, 0.02);
                v.remove();
                continue;
            }
            if (v.getTarget() instanceof Monster m && m.isValid() && !m.getScoreboardTags().contains(FAM_ALLY_TAG)) continue;
            LivingEntity best = null; double bd = 16 * 16;
            for (Entity e : owner.getNearbyEntities(16, 8, 16)) {
                if (!(e instanceof Monster m) || m.getScoreboardTags().contains(FAM_ALLY_TAG) || m.isDead()) continue;
                double d = m.getLocation().distanceSquared(owner.getLocation());
                if (d < bd) { bd = d; best = m; }
            }
            v.setTarget(best);
            if (best == null && v.getLocation().distanceSquared(owner.getLocation()) > 6 * 6) v.getPathfinder().moveTo(owner.getLocation(), 1.2);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFamTarget(EntityTargetLivingEntityEvent event) {
        Entity e = event.getEntity();
        LivingEntity tg = event.getTarget();
        if (tg == null) return;
        if (e.getScoreboardTags().contains(FAM_ALLY_TAG) && (tg instanceof Player || tg.getScoreboardTags().contains(FAM_ALLY_TAG) || (tg instanceof org.bukkit.entity.Tameable tm && tm.isTamed()))) event.setCancelled(true);
        if (e.getScoreboardTags().contains(FAM_TAG) && ours(tg)) event.setCancelled(true);
        if (isBody(tg) || tg.getScoreboardTags().contains(PROXY_TAG)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFamFriendlyFire(EntityDamageByEntityEvent event) {
        Entity d = event.getDamager();
        if (d.getScoreboardTags().contains(FAM_ALLY_TAG) && (event.getEntity() instanceof Player || event.getEntity().getScoreboardTags().contains(FAM_ALLY_TAG))) event.setCancelled(true);
        if (d.getScoreboardTags().contains(FAM_TAG) && ours(event.getEntity())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity e = event.getEntity();
        if (!ours(e)) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        if (e.getScoreboardTags().contains(FAM_TAG) || e.getScoreboardTags().contains(FAM_ALLY_TAG)) { // Werner's passive: a member died
            if (rocco != null && rocco.werner != null && e.getScoreboardTags().contains(FAM_TAG)) rocco.werner.memberDied();
            for (Werner w : allies) if (e.getScoreboardTags().contains(FAM_ALLY_TAG) && w.world.equals(e.getWorld())) w.memberDied();
        }
    }

    // =====================================================================================================
    //  WERNER (little brother, 300 health): with Rocco from half health, or helping a Vendetta Fist holder
    // =====================================================================================================
    static final int W_PLEXUS = 1, W_DOME = 2, W_GUT = 3, W_AWAITS = 4, W_SEIZE = 5, W_COUNTER = 6;

    final class Werner extends Body {
        final Rocco brother;         // boss side (null for an ally)
        final Player owner;          // ally side (null on the boss side)
        double hp, maxHp;
        int life, members, gutBuff, awaitsCd, seizeCd, failStreak, pending = -1, chase;
        boolean guarding, gone;
        Player counterOn;
        final BossBar bar;

        Werner(Location at, Rocco brother, Player owner) {
            super(at, "werner", (float) c("werner.scale", 1.0), WERNER_TAG, "Werner", c("werner.hitbox-scale", 1.9), false, Color.fromRGB(255, 255, 255));
            this.brother = brother; this.owner = owner;
            maxHp = hp = c("werner.health", 300);
            if (brother != null) { // he's built for a group too (7 recommended)
                int n = Math.max(1, Math.min(brother.active().size(), (int) c("max-scaled-fighters", 12)));
                maxHp = hp = c("werner.health", 300) * (1 + c("werner.health-per-extra-fighter", 0.5) * (n - 1));
            }
            life = owner != null ? (int) (c("fist.werner-seconds", 30) * 20) : Integer.MAX_VALUE;
            if (owner == null) pl.proxy(hitbox, parts[2], EntityType.VINDICATOR, scale);
            else hitbox.remove(); // the helper can't be hurt (and nothing aims at him)
            bar = owner == null ? Bukkit.createBossBar(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Werner", BarColor.PINK, BarStyle.SOLID) : null;
            if (owner != null) faceNow(owner.getLocation().toVector().add(owner.getLocation().getDirection().multiply(5)));
            world.spawnParticle(Particle.LARGE_SMOKE, at.clone().add(0, 1, 0), 40, 0.5, 1, 0.5, 0.02);
        }

        @Override void clamp() { clamp0(pos); }
        @Override void clamp0(Vector v) { if (brother != null) brother.clamp0(v); }

        @Override double damageScale() {
            return (1 + members * c("werner.member-bonus", 0.15)) * (gutBuff > 0 ? 1 + c("werner.gut-crush-bonus", 0.25) : 1);
        }

        void memberDied() {
            members++;
            world.spawnParticle(Particle.DUST, loc().add(0, 1.2, 0), 12, 0.4, 0.7, 0.4, 0, new Particle.DustOptions(Color.fromRGB(200, 30, 60), 1.3f));
            world.playSound(loc(), Sound.ENTITY_PILLAGER_CELEBRATE, 1.2f, 0.7f);
        }

        /** Who he goes after: Rocco's fighters, or the monsters near his owner. */
        List<LivingEntity> enemies() {
            List<LivingEntity> out = new ArrayList<>();
            if (owner == null) { if (brother != null) out.addAll(brother.active()); return out; }
            for (Entity e : owner.getNearbyEntities(16, 8, 16)) if (e instanceof Monster m && !m.getScoreboardTags().contains(FAM_ALLY_TAG) && !m.isDead()) out.add(m);
            return out;
        }

        void tick() {
            if (gone) return;
            tickCommon();
            if (owner != null && (!owner.isOnline() || owner.isDead() || !owner.getWorld().equals(world) || --life <= 0)) { leave(null); return; }
            if (brother == null && owner == null) { leave(null); return; }
            if (gutBuff > 0) gutBuff--;
            if (awaitsCd > 0) awaitsCd--;
            if (seizeCd > 0) seizeCd--;
            List<LivingEntity> foes = enemies();
            LivingEntity target = null; double bd = Double.MAX_VALUE;
            for (LivingEntity e : foes) { double d = e.getLocation().toVector().distanceSquared(pos); if (d < bd) { bd = d; target = e; } }
            try {
                if (target == null) {
                    if (owner != null) { // follows his owner around
                        Vector to = owner.getLocation().toVector().subtract(pos).setY(0);
                        if (to.length() > 3) { face(owner.getLocation().toVector(), 15); step(to.normalize().multiply(Math.min(to.length() - 2, 0.35))); walking = true; }
                        else settle();
                        pose = VendettaAnims.wernerStance(ticks);
                        if (to.length() > 24) { pos = owner.getLocation().toVector(); settle(); }
                    } else pose = VendettaAnims.wernerStance(ticks);
                    attack = -1; pending = -1;
                } else fight(target, foes);
                failStreak = 0;
            } catch (RuntimeException e) {
                pl.logBossPart("Werner", "move " + attack + " (tick " + t + ")", e);
                attack = -1; recover = 20; guarding = false;
                if (++failStreak > 100) { leave(null); return; }
            }
            pl.bossPart("Werner", "drawing", () -> render(null));
            if (bar != null && ticks % 5 == 0) {
                bar.setProgress(Math.max(0, Math.min(1, hp / maxHp)));
                bar.setTitle(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Werner" + (members > 0 ? ChatColor.RED + "  +" + Math.round(members * c("werner.member-bonus", 0.15) * 100) + "% damage" : ""));
                for (Player p : world.getPlayers()) {
                    boolean near = p.getLocation().toVector().distanceSquared(pos) < 64 * 64;
                    if (near && !bar.getPlayers().contains(p)) bar.addPlayer(p); else if (!near && bar.getPlayers().contains(p)) bar.removePlayer(p);
                }
            }
        }

        /**
         * His next move, picked ONCE. BUG FIX: he used to re-roll a move every tick while walking to you, and the only
         * picks that didn't need him to walk were his counter and his slam, so whenever you kept your distance he
         * started his counter guard over and over. Now the counter only comes up when someone is right on him, with a
         * 12 second cooldown, and every other move walks in first.
         */
        int choose(List<LivingEntity> foes) {
            boolean close = false;
            for (LivingEntity e : foes) if (e.getLocation().toVector().distanceSquared(pos) < 4 * 4) close = true;
            List<Integer> pool = new ArrayList<>(List.of(W_PLEXUS, W_PLEXUS, W_DOME, W_GUT));
            if (owner == null && close && seizeCd <= 0) pool.add(W_SEIZE);
            if (awaitsCd <= 0) pool.add(W_AWAITS);
            return pool.get(random.nextInt(pool.size()));
        }

        void fight(LivingEntity target, List<LivingEntity> foes) {
            if (attack < 0) {
                face(target.getLocation().toVector(), 16);
                if (recover > 0) { recover--; pose = VendettaAnims.wernerStance(ticks); settle(); return; }
                if (pending < 0) { pending = choose(foes); chase = 0; }
                Vector to = target.getLocation().toVector().subtract(pos).setY(0);
                double reach = pending == W_AWAITS ? 3.2 : 2.6;
                if (pending != W_SEIZE && to.length() > reach) { // walk in first
                    if (!step(to.normalize().multiply(c("werner.speed", 0.36))) || ++chase > 70)
                        blinkTo(target.getLocation().toVector().subtract(to.normalize().multiply(1.5)), target.getLocation().toVector());
                    walking = true;
                    pose = VendettaAnims.wernerStance(ticks);
                    return;
                }
                attack = pending; pending = -1; t = 0; guarding = false;
                if (attack == W_SEIZE) seizeCd = (int) (c("werner.seize-cooldown-seconds", 12) * 20);
            }
            Player credit = owner;
            if (t < 6 && attack != W_SEIZE && attack != W_COUNTER) face(target.getLocation().toVector(), 20);
            switch (attack) {
                case W_PLEXUS -> { // Aim for the Solar Plexus: a dipping body blow that stuns for a second
                    pose = VendettaAnims.wernerPlexus(t);
                    if (t == 0 && owner == null) say(ChatColor.LIGHT_PURPLE + "Werner: " + ChatColor.WHITE + "\"Aim for the solar plexus!\"", 25);
                    if (t == 5) step(fwd().multiply(0.35));
                    if (t == 6) {
                        world.playSound(loc(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.3f, 0.9f);
                        for (LivingEntity e : foes) if (inFront(e, 3, 0.4)) hitAny(e, c("werner.plexus-damage", 8), Guard.BLOCKABLE, (int) c("werner.plexus-stun-ticks", 20), 0.4, credit);
                    }
                    if (t >= 16) endW(14);
                }
                case W_DOME -> { // Right in the Dome: grabs your collar and headbutts you; Weakness II (a shield blocks it)
                    pose = VendettaAnims.wernerDome(t);
                    if (t == 0 && owner == null) say(ChatColor.LIGHT_PURPLE + "Werner: " + ChatColor.WHITE + "\"Right in the dome!\"", 25);
                    if (t == 8) {
                        world.playSound(loc(), Sound.BLOCK_ANVIL_LAND, 0.6f, 1.6f);
                        for (LivingEntity e : foes) if (inFront(e, 2.8, 0.5)) {
                            boolean blocked = e instanceof Player p && credit == null && facingBlock(p, chest());
                            hitAny(e, c("werner.dome-damage", 7), Guard.BLOCKABLE, 0, 0.5, credit);
                            if (!blocked) e.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, (int) c("werner.weakness-ticks", 160), 1));
                        }
                    }
                    if (t >= 18) endW(14);
                }
                case W_GUT -> { // Gut Crush: two hooks to the body, then he pumps himself up (+25% damage for 10s)
                    pose = VendettaAnims.wernerGut(t);
                    if (t == 0 && owner == null) say(ChatColor.LIGHT_PURPLE + "Werner: " + ChatColor.WHITE + "\"Gut crush!\"", 25);
                    if (t == 5 || t == 12) {
                        world.playSound(loc(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.3f, 1.0f);
                        for (LivingEntity e : foes) if (inFront(e, 3, 0.4)) { hitCd.remove(e.getUniqueId()); hitAny(e, c("werner.gut-damage", 6), Guard.BLOCKABLE, 0, 0.3, credit); }
                    }
                    if (t == 18) { gutBuff = (int) c("werner.gut-buff-ticks", 200); world.playSound(loc(), Sound.ENTITY_IRON_GOLEM_REPAIR, 1f, 0.8f); }
                    if (t >= 26) endW(12);
                }
                case W_AWAITS -> { // Vengeance Awaits You All!: a hop and a two-fisted slam that punishes debuffs
                    pose = VendettaAnims.wernerAwaits(t);
                    if (t == 0) {
                        if (owner == null) say(ChatColor.LIGHT_PURPLE + "Werner: " + ChatColor.DARK_RED + "" + ChatColor.BOLD + "\"VENGEANCE AWAITS YOU ALL!\"", 35);
                        world.playSound(loc(), Sound.ENTITY_RAVAGER_ROAR, 1.4f, 1.2f);
                    }
                    if (t < 16 && t % 2 == 0) ring(4);
                    if (t == 16) {
                        world.playSound(loc(), Sound.ENTITY_GENERIC_EXPLODE, 1.4f, 1.1f);
                        world.spawnParticle(Particle.EXPLOSION, loc(), 3, 1, 0.1, 1, 0);
                        for (LivingEntity e : foes) {
                            if (e.getLocation().toVector().distanceSquared(pos) > 4.5 * 4.5) continue;
                            int debuffs = 0; boolean weak = false;
                            for (PotionEffect pe : e.getActivePotionEffects()) {
                                PotionEffectType ty = pe.getType();
                                if (ty.equals(PotionEffectType.WEAKNESS)) weak = true;
                                if (ty.getCategory() == org.bukkit.potion.PotionEffectTypeCategory.HARMFUL) debuffs++;
                            }
                            if (isStunned(e)) debuffs++;
                            double dmg = c("werner.awaits-damage", 10) * (1 + debuffs * c("werner.awaits-per-debuff", 0.25));
                            hitCd.remove(e.getUniqueId());
                            if (weak && e instanceof Player wp && credit == null) {
                                // "half your health" goes around armor (armor used to shrink it)
                                if (!survival(wp)) continue;
                                wp.setHealth(Math.max(0.5, wp.getHealth() / 2));
                                wp.playHurtAnimation(0);
                                stun(wp, (int) c("werner.awaits-stun-ticks", 60));
                            } else {
                                if (weak) dmg = Math.max(dmg, e.getHealth() / 2 / Math.max(0.1, damageScale()));
                                hitAny(e, dmg, Guard.UNBLOCKABLE, (int) c("werner.awaits-stun-ticks", 60), 0.6, credit);
                            }
                            e.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 1));
                            e.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 100, 1));
                        }
                        awaitsCd = (int) (c("werner.awaits-cooldown-seconds", 15) * 20);
                    }
                    if (t >= 30) endW(18);
                }
                case W_SEIZE -> { // Counter - Seize ya Chance: an open guard; hit him and he counters with Weakness II
                    guarding = t < 34;
                    pose = VendettaAnims.wernerSeize(t);
                    if (t == 0) { say(ChatColor.LIGHT_PURPLE + "Werner: " + ChatColor.WHITE + "\"Go ahead. Seize your chance.\"", 25); world.playSound(loc(), Sound.ITEM_SHIELD_BLOCK, 1.2f, 0.8f); }
                    if (t >= 34) endW(10);
                }
                case W_COUNTER -> { // the counter itself: a spinning backfist
                    pose = VendettaAnims.wernerCounter(t);
                    Player p = counterOn;
                    if (p != null && t < 5) faceNow(p.getLocation().toVector());
                    if (t == 5 && p != null && p.isValid()) {
                        world.playSound(loc(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.5f, 0.8f);
                        hitCd.remove(p.getUniqueId());
                        if (p.getLocation().toVector().distanceSquared(pos) < 4 * 4) {
                            hit(p, c("werner.seize-damage", 9), Guard.UNBLOCKABLE, 0, 0.8);
                            p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, (int) c("werner.weakness-ticks", 160), 1));
                        }
                    }
                    if (t >= 14) { counterOn = null; endW(14); }
                }
                default -> endW(10);
            }
            t++;
        }

        void ring(double r) {
            Location c = loc().add(0, 0.15, 0);
            for (int i = 0; i < 20; i++) { double a = Math.PI * 2 * i / 20; world.spawnParticle(Particle.DUST, c.clone().add(Math.cos(a) * r, 0, Math.sin(a) * r), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(230, 60, 160), 1.2f)); }
        }

        void endW(int rest) { attack = -1; t = 0; guarding = false; recover = rest; }

        /** Hit during his guard: once, then the guard is over (one counter per guard, never a chain). */
        void counter(Player p) {
            guarding = false;
            blinkTo(behind(p, 1.5), p.getLocation().toVector()); faceNow(p.getLocation().toVector());
            p.sendActionBar(legacy(ChatColor.LIGHT_PURPLE + "Seized! " + ChatColor.GRAY + "Don't hit Werner while he's guarding."));
            counterOn = p;
            attack = W_COUNTER; t = 0;
        }

        void onHit(EntityDamageEvent event, Player by) {
            if (owner != null || gone) { event.setCancelled(true); return; }
            if (guarding && by != null && attack == W_SEIZE) { event.setCancelled(true); counter(by); return; }
            if (attack == W_COUNTER && t < 5) { event.setCancelled(true); return; } // mid-blink
            double dmg = event.getDamage();
            event.setDamage(0.001);
            hp -= dmg;
            flinchFrom(by);
            if (by != null && brother != null) brother.fighters.add(by.getUniqueId());
            if (hp <= 0) died();
        }

        void died() {
            if (gone) return;
            keepAlive = false;
            world.playSound(loc(), Sound.ENTITY_PLAYER_DEATH, 1.6f, 1.1f);
            world.spawnParticle(Particle.DUST, loc().add(0, 1, 0), 60, 0.5, 1, 0.5, 0, new Particle.DustOptions(Color.fromRGB(230, 60, 160), 1.8f));
            Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Werner " + ChatColor.GRAY + "has fallen!");
            if (brother != null) {
                brother.rage = 1 + c("werner.death-rage", 0.15);
                brother.say(ChatColor.DARK_RED + "" + ChatColor.BOLD + "\"WERNER!!!\"", 40);
                for (UUID id : brother.fighters) {
                    Player p = Bukkit.getPlayer(id);
                    if (p == null) continue;
                    giveCosmetic(p, c("werner.cosmetic-chance", 0.33)); // his coat, book, or chains
                    pl.console("index discover " + p.getName() + " werner", p);
                }
            }
            Entity hb = hitbox;
            Bukkit.getScheduler().runTask(pl, () -> { if (hb.isValid()) ((LivingEntity) hb).setHealth(0); }); // the kill (for the Index)
            gone = true;
            Bukkit.getScheduler().runTaskLater(pl, this::remove, 2L);
        }

        void leave(String message) {
            if (message != null) Bukkit.broadcastMessage(message);
            world.spawnParticle(Particle.LARGE_SMOKE, loc().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.02);
            remove();
        }

        void remove() {
            gone = true;
            if (bar != null) bar.removeAll();
            removeBody();
        }
    }

    // =====================================================================================================
    //  damage to Rocco and Werner
    // =====================================================================================================
    @EventHandler(priority = EventPriority.HIGH)
    public void onBodyDamage(EntityDamageEvent event) {
        Entity e = event.getEntity();
        if (!isBody(e)) return;
        if (!byPlayer(event)) { event.setCancelled(true); return; }
        Player by = event instanceof EntityDamageByEntityEvent ev ? pl.playerFrom(ev.getDamager()) : null;
        if (rocco != null && rocco.hitbox.equals(e)) { rocco.onHit(event, by); return; }
        if (rocco != null && rocco.werner != null && rocco.werner.hitbox.equals(e)) { rocco.werner.onHit(event, by); return; }
        event.setCancelled(true);
    }

    // =====================================================================================================
    //  THE VENDETTA FIST: every one of his moves (cooldowns), three of its own, Vengeance Tattoos, and Werner
    // =====================================================================================================
    record FistMove(String name, int cooldown, String desc) {}
    static final List<FistMove> FIST_MOVES = List.of(
            new FistMove("Kick", 6, "a heavy kick: stuns for 3 seconds"),
            new FistMove("Punch", 5, "a straight punch: stuns for 3 seconds"),
            new FistMove("Payback", 20, "3s counter stance: the next hit on you is thrown back"),
            new FistMove("Assemble", 60, "two Vendetta Enforcers fight for you (30s)"),
            new FistMove("Watch Your Back!", 12, "blink behind your target, hit, and again"),
            new FistMove("MY HAIR COUPONS!!!", 45, "charge, then a blast: half their health"),
            new FistMove("Swear Vengeance", 60, "untouchable for 5s, then 3 buffs"),
            new FistMove("No More Tests", 15, "hit up to 3 enemies"),
            new FistMove("COMPLETE AND TOTAL EXTERMINATION!!!", 30, "needs 50 tattoos: a leap and a slam"),
            new FistMove("Mercy of the Big Brother", 30, "a small buff (no damage)"),
            new FistMove("TRICKED ME, DID YOU??!!", 40, "a big buff (no damage)"),
            new FistMove("Ah, You Were One of the Fam", 600, "a big shield, costs health (once per fight)"));

    final Map<UUID, long[]> fistCd = new HashMap<>();
    final Map<UUID, Integer> payback = new HashMap<>(), sworn = new HashMap<>(), tattooCd = new HashMap<>();
    final Map<UUID, Long> wernerCd = new HashMap<>();
    /** A move that plays out over several ticks: (player, move, clock, target). */
    final class FistAct { final Player p; final int move; int t; LivingEntity target; Vector spot; FistAct(Player p, int move) { this.p = p; this.move = move; } }
    final List<FistAct> acts = new ArrayList<>();

    boolean holdingFist(Player p) { return "fist".equals(itemType(p.getInventory().getItemInMainHand())); }

    int tattoos(Player p) { return p.getPersistentDataContainer().getOrDefault(tattooKey, PersistentDataType.INTEGER, 0); }
    void setTattoos(Player p, int n) { p.getPersistentDataContainer().set(tattooKey, PersistentDataType.INTEGER, Math.max(0, Math.min((int) c("max-tattoos", 50), n))); }

    final Map<UUID, Integer> lastFistUse = new HashMap<>();

    /**
     * BUG FIX: right-clicking a MOB with the Fist is an entity click, not an item use, so Kick and Punch (which you aim at
     * a mob in reach) never went off. Entity clicks use the Fist too.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onFistEntity(org.bukkit.event.player.PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player p = event.getPlayer();
        if (!holdingFist(p)) return;
        event.setCancelled(true);
        fistUse(p);
    }

    void fistUse(Player p) {
        // one use per click: a click on a mob can also arrive as an item use the same tick (it cycled moves twice)
        if (lastFistUse.getOrDefault(p.getUniqueId(), -10) >= now - 1) return;
        lastFistUse.put(p.getUniqueId(), now);
        ItemStack it = p.getInventory().getItemInMainHand();
        ItemMeta m = it.getItemMeta();
        int sel = Math.floorMod(m.getPersistentDataContainer().getOrDefault(moveKey, PersistentDataType.INTEGER, 0), FIST_MOVES.size());
        if (p.isSneaking()) { // next move
            sel = (sel + 1) % FIST_MOVES.size();
            m.getPersistentDataContainer().set(moveKey, PersistentDataType.INTEGER, sel);
            it.setItemMeta(m);
            p.playSound(p, Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
            showMove(p, sel);
            return;
        }
        long[] cds = fistCd.computeIfAbsent(p.getUniqueId(), k -> new long[FIST_MOVES.size()]);
        long left = cds[sel] - System.currentTimeMillis();
        if (left > 0) { p.sendActionBar(legacy(ChatColor.GRAY + FIST_MOVES.get(sel).name() + ": " + ChatColor.RED + (left / 1000 + 1) + "s")); return; }
        for (FistAct a : acts) if (a.p.equals(p)) return; // one at a time
        if (sel == 8 && tattoos(p) < c("max-tattoos", 50)) { p.sendActionBar(legacy(ChatColor.RED + "You need " + (int) c("max-tattoos", 50) + " Vengeance Tattoos (you have " + tattoos(p) + "). Get hit!")); return; }
        if (!startFist(p, sel)) return;
        cds[sel] = System.currentTimeMillis() + (long) (c("fist.cooldown." + sel, FIST_MOVES.get(sel).cooldown()) * 1000);
    }

    void showMove(Player p, int sel) {
        FistMove mv = FIST_MOVES.get(sel);
        long[] cds = fistCd.get(p.getUniqueId());
        long left = cds == null ? 0 : cds[sel] - System.currentTimeMillis();
        p.sendActionBar(legacy(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + mv.name() + ChatColor.GRAY + "  " + mv.desc()
                + (left > 0 ? ChatColor.RED + "  (" + (left / 1000 + 1) + "s)" : ChatColor.GREEN + "  ready")));
    }

    /** Every half second while holding the Fist: the tattoo count. */
    void fistHud() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!holdingFist(p)) continue;
            boolean busy = false;
            for (FistAct a : acts) if (a.p.equals(p)) busy = true;
            if (busy) continue;
            if (now % 40 == 0) {
                int sel = Math.floorMod(p.getInventory().getItemInMainHand().getItemMeta().getPersistentDataContainer().getOrDefault(moveKey, PersistentDataType.INTEGER, 0), FIST_MOVES.size());
                FistMove mv = FIST_MOVES.get(sel);
                p.sendActionBar(legacy(ChatColor.DARK_PURPLE + mv.name() + ChatColor.GRAY + "  |  " + ChatColor.LIGHT_PURPLE + "✠ " + tattoos(p) + "/" + (int) c("max-tattoos", 50)
                        + ChatColor.DARK_GRAY + "  (sneak + right-click: next move)"));
            }
        }
        payback.replaceAll((k, v) -> v - 10); payback.values().removeIf(v -> v <= 0);
        sworn.replaceAll((k, v) -> v - 10); sworn.values().removeIf(v -> v <= 0);
        tattooCd.replaceAll((k, v) -> v - 10); tattooCd.values().removeIf(v -> v <= 0);
    }

    /** The monster/creature a player is looking at (16 blocks), never another player or a pet. */
    LivingEntity aimed(Player p, double range) {
        org.bukkit.util.RayTraceResult r = p.getWorld().rayTrace(p.getEyeLocation(), p.getEyeLocation().getDirection(), range, org.bukkit.FluidCollisionMode.NEVER, true, 0.6,
                e -> e instanceof LivingEntity le && fistCanHit(p, le)); // walls block it
        return r != null && r.getHitEntity() instanceof LivingEntity le ? le : null;
    }

    boolean fistCanHit(Player p, LivingEntity e) {
        if (e.equals(p) || e.isDead() || e instanceof Player || e instanceof org.bukkit.entity.ArmorStand) return false;
        if (e.getScoreboardTags().contains(DISPLAY_TAG) || e.getScoreboardTags().contains(FAM_ALLY_TAG) || e.getScoreboardTags().contains(PROXY_TAG)) return false;
        if (e instanceof org.bukkit.entity.Tameable t && t.isTamed()) return false;
        return !(e instanceof org.bukkit.entity.Villager) && !(e instanceof org.bukkit.entity.WanderingTrader);
    }

    List<LivingEntity> near(Player p, double r) {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity e : p.getNearbyEntities(r, r / 2 + 2, r)) if (e instanceof LivingEntity le && fistCanHit(p, le)) out.add(le);
        out.sort(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(p.getLocation())));
        return out;
    }

    /**
     * A boss (or a boss's part): Fist damage to it is capped and it can't be stunned. BUG FIX: any "faultline_" tag counted,
     * so Rocco's own Enforcers (and other plain minions) were treated as bosses: capped, and immune to Kick/Punch stuns.
     */
    static boolean bossLike(Entity e) {
        for (String t : e.getScoreboardTags()) if (t.startsWith("faultline_") && !t.equals(FAM_TAG) && !t.equals(FAM_ALLY_TAG) && !t.equals("faultline_summon")) return true;
        return false;
    }

    /** Damage from the Fist: bosses (their hitboxes) take a capped amount, so nothing one-shots a boss. */
    void fistDamage(Player p, LivingEntity e, double dmg) {
        boolean boss = bossLike(e);
        if (boss) dmg = Math.min(dmg, c("fist.boss-damage-cap", 60));
        e.damage(dmg, p);
    }

    boolean startFist(Player p, int sel) {
        World w = p.getWorld();
        switch (sel) {
            case 0, 1 -> { // Kick / Punch
                LivingEntity e = aimed(p, 4);
                if (e == null) { p.sendActionBar(legacy(ChatColor.GRAY + "Nothing in reach.")); return false; }
                fistDamage(p, e, sel == 0 ? c("fist.kick-damage", 10) : c("fist.punch-damage", 9));
                if (!bossLike(e)) stun(e, (int) c("stun-ticks", 60));
                Vector kb = e.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
                if (kb.lengthSquared() > 0.01) e.setVelocity(kb.normalize().multiply(sel == 0 ? 1.0 : 0.6).setY(0.3));
                w.playSound(p.getLocation(), sel == 0 ? Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK : Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.2f, 0.7f);
                w.spawnParticle(Particle.CRIT, e.getLocation().add(0, 1, 0), 12, 0.3, 0.3, 0.3, 0.2);
                p.swingMainHand();
            }
            case 2 -> { payback.put(p.getUniqueId(), (int) c("fist.payback-ticks", 60)); w.playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 0.6f); p.sendActionBar(legacy(ChatColor.LIGHT_PURPLE + "Payback stance: the next hit on you comes back.")); }
            case 3 -> {
                for (int i = 0; i < (int) c("fist.assemble-count", 2); i++) {
                    Location at = p.getLocation().add(random.nextGaussian() * 1.5, 0, random.nextGaussian() * 1.5);
                    spawnFam(at, p);
                }
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_VILLAGER_CELEBRATE, 1.2f, 0.6f);
            }
            case 4 -> {
                LivingEntity e = aimed(p, 16);
                if (e == null) { p.sendActionBar(legacy(ChatColor.GRAY + "Look at an enemy first.")); return false; }
                FistAct a = new FistAct(p, 4); a.target = e; acts.add(a);
            }
            case 5 -> { acts.add(new FistAct(p, 5)); p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 2)); }
            case 6 -> {
                sworn.put(p.getUniqueId(), (int) c("fist.vengeance-dodge-ticks", 100));
                int buff = (int) c("fist.vengeance-buff-ticks", 300);
                p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, buff, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, buff, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, buff, 0));
                w.playSound(p.getLocation(), Sound.ENTITY_EVOKER_PREPARE_WOLOLO, 1.2f, 0.7f);
                p.sendActionBar(legacy(ChatColor.DARK_RED + "" + ChatColor.BOLD + "I SWEAR VENGEANCE!"));
            }
            case 7 -> {
                List<LivingEntity> foes = near(p, 8);
                if (foes.isEmpty()) { p.sendActionBar(legacy(ChatColor.GRAY + "No enemies close enough.")); return false; }
                FistAct a = new FistAct(p, 7); acts.add(a);
            }
            case 8 -> {
                LivingEntity e = aimed(p, 24);
                if (e == null) { p.sendActionBar(legacy(ChatColor.GRAY + "Look at an enemy first.")); return false; }
                FistAct a = new FistAct(p, 8); a.target = e; acts.add(a);
                setTattoos(p, 0);
                p.setVelocity(new Vector(0, 1.6, 0));
                w.playSound(p.getLocation(), Sound.ENTITY_WITHER_SHOOT, 1f, 0.6f);
                p.sendActionBar(legacy(ChatColor.DARK_RED + "" + ChatColor.BOLD + "COMPLETE AND TOTAL EXTERMINATION!!!"));
            }
            case 9 -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 160, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 160, 0));
                w.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
                p.sendActionBar(legacy(ChatColor.LIGHT_PURPLE + "Mercy of the Big Brother."));
            }
            case 10 -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 120, 1));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 120, 1));
                w.playSound(p.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1f, 1.3f);
                p.sendActionBar(legacy(ChatColor.DARK_RED + "" + ChatColor.BOLD + "TRICKED ME, DID YOU??!!"));
            }
            case 11 -> {
                double cost = c("fist.fam-health-cost", 6);
                if (p.getHealth() <= cost + 1) { p.sendActionBar(legacy(ChatColor.RED + "Not enough health to pay for it.")); return false; }
                p.setHealth(p.getHealth() - cost);
                p.playHurtAnimation(0);
                p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, (int) c("fist.fam-shield-ticks", 600), (int) c("fist.fam-shield-level", 3)));
                w.playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 0.6f, 1.2f);
                p.sendActionBar(legacy(ChatColor.GOLD + "Ah, you were one of the fam."));
            }
            default -> { return false; }
        }
        return true;
    }

    void fistTick() {
        for (FistAct a : new ArrayList<>(acts)) {
            Player p = a.p;
            if (!p.isOnline() || p.isDead() || a.t > 200) { acts.remove(a); continue; }
            World w = p.getWorld();
            switch (a.move) {
                case 4 -> { // Watch Your Back!: behind it, hit, behind it again, hit
                    LivingEntity e = a.target;
                    if (e == null || !e.isValid() || e.isDead() || !e.getWorld().equals(w)) { acts.remove(a); continue; }
                    if (a.t == 0 || a.t == 10) {
                        Vector dir = e.getLocation().getDirection().setY(0);
                        if (dir.lengthSquared() < 0.01) dir = new Vector(1, 0, 0);
                        Location to = e.getLocation().subtract(dir.normalize().multiply(1.6));
                        to.setDirection(e.getLocation().toVector().subtract(to.toVector()));
                        if (to.getBlock().isPassable() && to.clone().add(0, 1, 0).getBlock().isPassable()) p.teleport(to);
                        w.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.4f);
                    }
                    if (a.t == 3 || a.t == 13) { fistDamage(p, e, c("fist.back-damage", 8)); p.swingMainHand(); w.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 0.8f); }
                    if (a.t >= 14) acts.remove(a);
                }
                case 5 -> { // MY HAIR COUPONS!!!: 2 seconds of charge, then the blast
                    double r = c("fist.coupons-radius", 6);
                    if (a.t == 0) p.sendActionBar(legacy(ChatColor.DARK_RED + "" + ChatColor.BOLD + "MY HAIR COUPOOOOOOOOOOONS!!!"));
                    if (a.t < 40 && a.t % 2 == 0) {
                        double rr = r * (a.t + 4) / 44.0;
                        for (int i = 0; i < 20; i++) { double ang = Math.PI * 2 * i / 20; w.spawnParticle(Particle.DUST, p.getLocation().add(Math.cos(ang) * rr, 0.15, Math.sin(ang) * rr), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(255, 200, 80), 1.2f)); }
                    }
                    if (a.t == 40) {
                        w.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 0.7f);
                        w.spawnParticle(Particle.EXPLOSION_EMITTER, p.getLocation(), 1);
                        for (LivingEntity e : near(p, r)) {
                            fistDamage(p, e, Math.max(4, e.getHealth() / 2));
                            stun(e, 20);
                        }
                        acts.remove(a);
                    }
                }
                case 7 -> { // No More Tests: three enemies, three hits
                    if (a.t % 8 == 0) {
                        int i = a.t / 8;
                        List<LivingEntity> foes = near(p, 8);
                        if (i >= 3 || foes.isEmpty()) { acts.remove(a); continue; }
                        LivingEntity e = foes.get(i % foes.size());
                        Location to = e.getLocation().add(flat(e.getLocation().toVector().subtract(p.getLocation().toVector()), flatDir(p.getLocation())).multiply(-1.4));
                        to.setDirection(e.getLocation().toVector().subtract(to.toVector()));
                        if (to.getBlock().isPassable() && to.clone().add(0, 1, 0).getBlock().isPassable()) p.teleport(to);
                        fistDamage(p, e, c("fist.tests-damage", 10));
                        p.swingMainHand();
                        w.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.2f, 0.6f);
                    }
                }
                case 8 -> { // EXTERMINATION: up, then down onto the target
                    LivingEntity e = a.target;
                    if (e == null || !e.isValid() || !e.getWorld().equals(w)) { acts.remove(a); continue; }
                    if (a.t < 24 && a.t % 3 == 0) {
                        for (int i = 0; i < 16; i++) { double ang = Math.PI * 2 * i / 16; w.spawnParticle(Particle.DUST, e.getLocation().add(Math.cos(ang) * 1.8, 0.15, Math.sin(ang) * 1.8), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(255, 30, 30), 1.4f)); }
                    }
                    if (a.t == 24) {
                        Location to = e.getLocation().clone();
                        to.setDirection(p.getLocation().getDirection());
                        p.teleport(to.add(0, 0.2, 0));
                        p.setFallDistance(0);
                        w.playSound(to, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.5f);
                        w.spawnParticle(Particle.EXPLOSION_EMITTER, to, 1);
                        boolean boss = bossLike(e);
                        fistDamage(p, e, boss ? c("fist.extermination-boss-damage", 250) : Math.max(1000, e.getHealth() + 1));
                        for (LivingEntity o : near(p, 4)) if (!o.equals(e)) fistDamage(p, o, 10);
                        acts.remove(a);
                    }
                }
                default -> acts.remove(a);
            }
            a.t++;
        }
    }

    /** The holder's side: Vengeance Tattoos, Payback, Swear Vengeance dodges, and Werner coming to help. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHolderHurt(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (sworn.containsKey(p.getUniqueId())) { event.setCancelled(true); p.getWorld().playSound(p.getLocation(), Sound.ENTITY_BREEZE_JUMP, 0.8f, 1.5f); return; }
        if (payback.containsKey(p.getUniqueId()) && event instanceof EntityDamageByEntityEvent ev) {
            Entity d = ev.getDamager();
            Entity src = d instanceof Projectile pr && pr.getShooter() instanceof Entity s ? s : d;
            if (src instanceof LivingEntity le && !(le instanceof Player)) {
                payback.remove(p.getUniqueId());
                event.setCancelled(true);
                fistDamage(p, le, event.getDamage() * c("fist.payback-multiplier", 1.5));
                if (!bossLike(le)) stun(le, 40);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.2f, 0.6f);
                p.sendActionBar(legacy(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "PAYBACK!"));
                return;
            }
        }
        if (!holdingFist(p)) return;
        if (!tattooCd.containsKey(p.getUniqueId())) {
            int n = tattoos(p);
            if (n < c("max-tattoos", 50)) { setTattoos(p, n + 1); tattooCd.put(p.getUniqueId(), (int) c("tattoo-cooldown-ticks", 10)); }
            if (n + 1 == (int) c("max-tattoos", 50)) p.sendActionBar(legacy(ChatColor.DARK_RED + "" + ChatColor.BOLD + "50 VENGEANCE TATTOOS: " + ChatColor.GRAY + "Extermination is ready."));
        }
        // Little Brother: at half health, Werner comes to help
        double max = p.getAttribute(Attribute.MAX_HEALTH) != null ? p.getAttribute(Attribute.MAX_HEALTH).getValue() : 20;
        double after = p.getHealth() - event.getFinalDamage();
        if (after > 0 && after <= max / 2 && System.currentTimeMillis() > wernerCd.getOrDefault(p.getUniqueId(), 0L)) {
            for (Werner w : allies) if (w.owner != null && w.owner.equals(p)) return;
            wernerCd.put(p.getUniqueId(), System.currentTimeMillis() + (long) (c("fist.werner-cooldown-seconds", 300) * 1000));
            Location at = p.getLocation().add(flatDir(p.getLocation()).multiply(-2));
            Bukkit.getScheduler().runTask(pl, () -> {
                if (!p.isOnline() || p.isDead()) return;
                allies.add(new Werner(at, null, p));
                p.sendMessage(ChatColor.LIGHT_PURPLE + "Werner: " + ChatColor.WHITE + "\"Big brother sent me. I've got your back.\"");
            });
        }
    }

    // =====================================================================================================
    //  boss form: the hotbar
    // =====================================================================================================
    List<MoveSlot> morphMoves() {
        List<MoveSlot> m = new ArrayList<>();
        if (rocco == null) return m;
        m.add(new MoveSlot(M_KICK, "Kick", Material.LEATHER_BOOTS));
        m.add(new MoveSlot(M_PUNCH, "Punch", Material.IRON_INGOT));
        m.add(new MoveSlot(M_PAYBACK, "Payback", Material.SHIELD));
        m.add(new MoveSlot(M_ASSEMBLE, "Assemble", Material.VINDICATOR_SPAWN_EGG));
        m.add(new MoveSlot(M_BEHIND, "Watch Your Back!", Material.ENDER_PEARL));
        if (rocco.phase >= 2) {
            m.add(new MoveSlot(M_COUPONS, "MY HAIR COUPONS!!!", Material.TNT));
            m.add(new MoveSlot(M_VENGEANCE, "Swear Vengeance", Material.REDSTONE));
            m.add(new MoveSlot(M_TESTS, "No More Tests", Material.IRON_SWORD));
            if (rocco.tattoos >= c("max-tattoos", 50)) m.set(0, new MoveSlot(M_EXTERMINATE, "EXTERMINATION!!!", Material.NETHER_STAR));
        }
        return m;
    }
}
