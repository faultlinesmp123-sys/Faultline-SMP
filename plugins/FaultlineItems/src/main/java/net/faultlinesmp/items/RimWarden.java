package net.faultlinesmp.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;

import java.util.*;

/**
 * THE WARDEN OF THE RIM (2.0 round 10, config {@code rim-warden:}). The Farlands' boss: a giant of stone and glitched blocks
 * (about 6 blocks tall, drawn with block displays, so no resource pack is needed; Bedrock sees FaultlineBosses' block mirrors)
 * walking an invisible scaled zombie. Every minute while someone in survival is in the Farlands he has a
 * {@code spawn-chance} 5% to rise near one of them (then rests {@code cooldown-minutes} 30), or anyone can call him there with a
 * RIM SIGIL (Rim Trader, Elite Captains, Farlands vaults; admins /rimwarden sigil).
 * 6000 health (+30% per extra fighter), takes 15% less, can't be knocked back. Phase 2 below 65%, phase 3 below 30%.
 * Moves: GROUND POUND (a ring that throws you), BOULDER TOSS, GLITCH STEP (vanishes and hits from behind the farthest of you),
 * RIFT BEAM (phase 2+), FRACTURE (phase 2+: lines of stone spikes erupt under you), CALL OF THE RIM (phase 2+: monsters,
 * often Elites); phase 3 UNSTABLE: faster, and glitched blocks rain on everyone. Rewards are big; Index stat rim_wardens.
 */
final class RimWarden implements Listener, CommandExecutor, TabCompleter {

    static final String TAG = "faultline_rim_warden", PART = "faultline_rim_part", PERM = "items.adminbosses";
    private static final Particle.DustOptions CRIMSON = new Particle.DustOptions(Color.fromRGB(0xC21E3A), 1.8f),
            GLITCH = new Particle.DustOptions(Color.fromRGB(0xE040FB), 1.4f);

    private final FaultlineItems plugin;
    private final Random random = new Random();
    final NamespacedKey sigilKey;
    Fight fight;
    private long tick, restUntil;

    /** One block display of the rig: pivot (local, blocks), box offset from the pivot, size, and which joint moves it. */
    static final class Part {
        final BlockDisplay d; final float[] pivot, off, size; final String joint; final BlockData base;
        Part(BlockDisplay d, float[] pivot, float[] off, float[] size, String joint, BlockData base) {
            this.d = d; this.pivot = pivot; this.off = off; this.size = size; this.joint = joint; this.base = base;
        }
    }

    final class Fight {
        Zombie body; BossBar bar; final List<Part> parts = new ArrayList<>(); final List<Entity> temp = new ArrayList<>();
        int t, nextMove = 50, move, moveT, phase = 1, rising = 40, alone; Player target; double walk; Location last;
        float armL, armR, legSwing, lean, bodyDrop; boolean hidden; Vector beam; Location beamFrom;
        final Map<UUID, Double> damage = new HashMap<>(); final Set<UUID> fighters = new HashSet<>(); final Set<UUID> minions = new HashSet<>();
        final Set<UUID> hitThisMove = new HashSet<>();
        final List<Object[]> boulders = new ArrayList<>();   // {BlockDisplay, Vector from, Vector to, int t}
        final List<Object[]> lines = new ArrayList<>();      // {Location from, Vector dir, int t}
    }

    RimWarden(FaultlineItems plugin) {
        this.plugin = plugin;
        sigilKey = new NamespacedKey(plugin, "rim_sigil");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1L, 1L);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities())
                if (e.getScoreboardTags().contains(TAG) || e.getScoreboardTags().contains(PART)) e.remove();
        });
    }

    double cfg(String k, double d) { return plugin.getConfig().getDouble("rim-warden." + k, d); }

    // ================================================================= the sigil

    ItemStack sigil(int n) {
        ItemStack it = new ItemStack(Material.ECHO_SHARD, Math.max(1, n));
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Rim Sigil", NamedTextColor.RED).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("A shard of the edge of the world.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Right-click in the Farlands: the Warden", NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false),
                Component.text("of the Rim rises to meet you.", NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false),
                Component.text("Bring friends.", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)));
        m.setItemModel(NamespacedKey.minecraft("ender_eye"));
        m.addEnchant(org.bukkit.enchantments.Enchantment.LURE, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        m.getPersistentDataContainer().set(sigilKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isSigil(ItemStack it) { return it != null && it.hasItemMeta() && it.getPersistentDataContainer().has(sigilKey, PersistentDataType.BYTE); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        if (!isSigil(e.getItem())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!Farlands.in(p.getLocation()) && !p.hasPermission(PERM)) { p.sendMessage(ChatColor.RED + "The sigil is cold here. It only wakes in the Farlands."); return; }
        if (fight != null) { p.sendMessage(ChatColor.RED + "The Warden of the Rim is already awake."); return; }
        if (summon(p) != null && p.getGameMode() != GameMode.CREATIVE) e.getItem().setAmount(e.getItem().getAmount() - 1);
    }

    // ================================================================= spawning

    Fight summon(Player near) {
        if (fight != null) return null;
        Location at = ground(near.getLocation(), 9, 16);
        if (at == null) at = near.getLocation().clone();
        at.setDirection(near.getLocation().toVector().subtract(at.toVector()).setY(0).lengthSquared() < 1e-4 ? new Vector(0, 0, 1) : near.getLocation().toVector().subtract(at.toVector()));
        Fight f = new Fight();
        World w = at.getWorld();
        Zombie z = (Zombie) w.spawnEntity(at, EntityType.ZOMBIE, CreatureSpawnEvent.SpawnReason.CUSTOM, en -> en.addScoreboardTag(TAG));
        try { z.setAdult(); } catch (RuntimeException ignored) { }
        Emotes.q(() -> z.setShouldBurnInDay(false));
        Emotes.q(() -> z.setCanPickupItems(false));
        z.setPersistent(false);
        Emotes.q(() -> z.setRemoveWhenFarAway(false));
        Emotes.q(() -> z.setSilent(true));
        z.customName(Component.text("The Warden of the Rim", NamedTextColor.DARK_RED));
        z.setCustomNameVisible(false);
        int n = 0;
        for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(at) < 64 * 64 && Farlands.survival(p)) n++;
        BigHealth.set(z, cfg("health", 6000) * (1 + cfg("per-fighter", 0.3) * Math.max(0, n - 1)));
        MimicPlayer.attr(z, Attribute.SCALE, 2.6);
        MimicPlayer.attr(z, Attribute.ATTACK_DAMAGE, cfg("melee", 16));
        MimicPlayer.attr(z, Attribute.MOVEMENT_SPEED, 0.3);
        MimicPlayer.attr(z, Attribute.ARMOR, 16);
        MimicPlayer.attr(z, Attribute.ARMOR_TOUGHNESS, 8);
        MimicPlayer.attr(z, Attribute.KNOCKBACK_RESISTANCE, 1);
        MimicPlayer.attr(z, Attribute.FOLLOW_RANGE, 64);
        MimicPlayer.attr(z, Attribute.SPAWN_REINFORCEMENTS, 0);
        MimicPlayer.attr(z, Attribute.STEP_HEIGHT, 2.0);
        MimicPlayer.attr(z, Attribute.SAFE_FALL_DISTANCE, 256);
        z.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false));
        z.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, PotionEffect.INFINITE_DURATION, 0, false, false));
        z.getEquipment().clear();
        for (EquipmentSlot s : EquipmentSlot.values()) try { z.getEquipment().setDropChance(s, 0); } catch (RuntimeException ignored) { }
        f.body = z;
        f.last = at.clone();
        build(f, at);
        f.bar = Bukkit.createBossBar(ChatColor.DARK_RED + "The Warden of the Rim", BarColor.RED, BarStyle.SEGMENTED_20);
        fight = f;
        w.playSound(at, Sound.ENTITY_WARDEN_EMERGE, 4, 0.6f);
        w.playSound(at, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 3, 0.5f);
        w.spawnParticle(Particle.BLOCK, at.clone().add(0, 0.5, 0), 200, 2.5, 0.5, 2.5, 0, Material.STONE.createBlockData());
        for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(at) < 120 * 120)
            p.sendTitle(ChatColor.DARK_RED + "THE WARDEN OF THE RIM", ChatColor.GRAY + "The edge of the world wakes up.", 10, 60, 20);
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "⛰ The Warden of the Rim has risen in the Farlands near " + near.getName() + ".");
        return f;
    }

    Location ground(Location c, double min, double max) {
        World w = c.getWorld();
        for (int k = 0; k < 20; k++) {
            double a = random.nextDouble() * Math.PI * 2, d = min + random.nextDouble() * (max - min);
            int x = c.getBlockX() + (int) (Math.cos(a) * d), z = c.getBlockZ() + (int) (Math.sin(a) * d);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int dy = 6; dy >= -8; dy--) {
                org.bukkit.block.Block b = w.getBlockAt(x, c.getBlockY() + dy, z);
                if (!b.getRelative(0, -1, 0).getType().isSolid()) continue;
                boolean room = true;
                for (int h = 0; h < 6 && room; h++) for (int ox = -1; ox <= 1 && room; ox++) for (int oz = -1; oz <= 1; oz++)
                    if (!Emotes.open(b.getRelative(ox, h, oz))) { room = false; break; }
                if (room) return b.getLocation().add(0.5, 0, 0.5);
            }
        }
        return null;
    }

    // ================================================================= the rig

    private void part(Fight f, Location at, String block, String joint, float[] pivot, float[] off, float[] size) {
        BlockData bd = Bukkit.createBlockData(block);
        Location l = at.clone(); l.setYaw(0); l.setPitch(0);
        BlockDisplay d = at.getWorld().spawn(l, BlockDisplay.class, x -> {
            x.setBlock(bd);
            x.addScoreboardTag(PART);
            x.setPersistent(false);
            x.setTeleportDuration(2);
            x.setInterpolationDuration(2);
            x.setViewRange(2.5f);
            x.setDisplayWidth(8); x.setDisplayHeight(8);
            x.setTransformation(new Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(), new org.joml.Vector3f(0.001f), new org.joml.Quaternionf()));
        });
        f.parts.add(new Part(d, pivot, off, size, joint, bd));
    }

    private static float[] v(double a, double b, double c) { return new float[]{(float) a, (float) b, (float) c}; }

    /** About 6 blocks tall. Local frame: +x his left, +y up, +z forward. */
    void build(Fight f, Location at) {
        // legs (pivot at the hips)
        for (int s = -1; s <= 1; s += 2) {
            String j = s < 0 ? "legR" : "legL";
            part(f, at, "minecraft:cobblestone", j, v(s * 0.6, 2.1, 0), v(-0.48, -2.1, -0.48), v(0.96, 2.1, 0.96));
            part(f, at, "minecraft:mossy_cobblestone", j, v(s * 0.6, 2.1, 0), v(-0.55, -2.15, -0.62), v(1.1, 0.55, 1.24));   // feet
        }
        // pelvis, torso, chest plate, moss, a grass tuft on the shoulders
        part(f, at, "minecraft:stone_bricks", "body", v(0, 2.1, 0), v(-1.15, 0, -0.65), v(2.3, 0.7, 1.3));
        part(f, at, "minecraft:stone", "body", v(0, 2.1, 0), v(-1.35, 0.7, -0.8), v(2.7, 2.1, 1.6));
        part(f, at, "minecraft:mossy_stone_bricks", "body", v(0, 2.1, 0), v(-1.0, 1.0, 0.75), v(2.0, 1.5, 0.2));
        part(f, at, "minecraft:cracked_stone_bricks", "body", v(0, 2.1, 0), v(-0.4, 1.3, 0.9), v(0.8, 0.8, 0.12));
        part(f, at, "minecraft:moss_block", "body", v(0, 2.1, 0), v(-1.4, 2.75, -0.85), v(2.8, 0.18, 1.7));
        part(f, at, "minecraft:grass_block", "body", v(0, 2.1, 0), v(0.55, 2.8, -0.6), v(0.7, 0.45, 0.7));
        part(f, at, "minecraft:dirt", "body", v(0, 2.1, 0), v(-1.25, 2.8, -0.3), v(0.6, 0.35, 0.6));
        // the head and its glowing eyes
        part(f, at, "minecraft:chiseled_stone_bricks", "head", v(0, 4.2, 0.1), v(-0.7, 0, -0.65), v(1.4, 1.3, 1.3));
        part(f, at, "minecraft:mossy_cobblestone", "head", v(0, 4.2, 0.1), v(-0.75, 1.15, -0.7), v(1.5, 0.3, 1.4));
        part(f, at, "minecraft:shroomlight", "eyes", v(0, 4.2, 0.1), v(-0.45, 0.55, 0.62), v(0.3, 0.18, 0.08));
        part(f, at, "minecraft:shroomlight", "eyes", v(0, 4.2, 0.1), v(0.15, 0.55, 0.62), v(0.3, 0.18, 0.08));
        // arms (pivot at the shoulders) with heavy fists
        for (int s = -1; s <= 1; s += 2) {
            String j = s < 0 ? "armR" : "armL";
            part(f, at, "minecraft:stone_bricks", j, v(s * 1.75, 4.5, 0), v(-0.55, -0.15, -0.55), v(1.1, 0.75, 1.1));   // shoulder
            part(f, at, "minecraft:stone", j, v(s * 1.75, 4.5, 0), v(-0.42, -2.35, -0.42), v(0.84, 2.3, 0.84));
            part(f, at, "minecraft:polished_andesite", j, v(s * 1.75, 4.5, 0), v(-0.6, -3.35, -0.6), v(1.2, 1.1, 1.2));   // fist
        }
        // glitched shards orbiting him
        String[] g = {"minecraft:magenta_concrete", "minecraft:black_concrete", "minecraft:grass_block", "minecraft:crying_obsidian", "minecraft:dirt"};
        for (int i = 0; i < 6; i++) part(f, at, g[i % g.length], "orbit" + i, v(0, 3.5, 0), v(-0.2, -0.2, -0.2), v(0.4, 0.4, 0.4));
        for (Part p : f.parts) if (p.joint.equals("eyes") || p.joint.startsWith("orbit"))
            p.d.setBrightness(new Display.Brightness(15, 15));
    }

    /** Pose every part from the joint angles in the fight. Every 2 ticks (interpolated). */
    void pose(Fight f) {
        Location base = f.body.getLocation();
        float yaw = base.getYaw();
        Location tp = base.clone(); tp.setYaw(0); tp.setPitch(0);
        tp.add(0, -f.bodyDrop, 0);
        double t = f.t;
        float breathe = (float) Math.sin(t * 0.07) * 0.04f;
        for (Part p : f.parts) {
            Matrix4f m = new Matrix4f().rotateY((float) Math.toRadians(-yaw));
            float px = p.pivot[0], py = p.pivot[1], pz = p.pivot[2];
            float rx = 0, ry = 0;
            switch (p.joint) {
                case "legL" -> rx = -f.legSwing;
                case "legR" -> rx = f.legSwing;
                case "armL" -> { rx = f.armL; py += breathe; }
                case "armR" -> { rx = f.armR; py += breathe; }
                case "body", "head", "eyes" -> { rx = f.lean; py += breathe; }
                default -> { }
            }
            if (p.joint.startsWith("orbit")) {
                int i = p.joint.charAt(5) - '0';
                double a = t * 0.06 + i * Math.PI / 3;
                float r = 2.4f + (float) Math.sin(t * 0.11 + i) * 0.3f;
                m.translate((float) Math.cos(a) * r, 3.2f + (float) Math.sin(t * 0.09 + i * 1.7) * 1.2f, (float) Math.sin(a) * r);
                m.rotateY((float) (t * 0.1 + i)).rotateX((float) (t * 0.07 + i));
            } else {
                m.translate(px, py, pz);
                if (p.joint.equals("head") || p.joint.equals("eyes")) {
                    // the head sits on the torso: it follows the body's lean around the waist
                    m.translate(0, -2.1f, 0).rotateX(rx).translate(0, 2.1f, 0);
                    m.rotateY((float) Math.sin(t * 0.03) * 0.15f);
                } else if (p.joint.equals("body")) {
                    m.rotateX(rx);
                } else m.rotateX(rx).rotateY(ry);
            }
            m.translate(p.off[0], p.off[1], p.off[2]);
            float jx = 0, jy = 0, jz = 0;
            if (f.hidden) { m.scale(0.001f); }
            else {
                // the glitch: now and then a part slips a little sideways for a tick
                if ((f.t + p.hashCode()) % 37 == 0 || f.phase == 3 && random.nextInt(18) == 0) {
                    jx = (random.nextFloat() - 0.5f) * 0.4f; jz = (random.nextFloat() - 0.5f) * 0.4f;
                }
                m.translate(jx, jy, jz);
                m.scale(p.size[0], p.size[1], p.size[2]);
            }
            p.d.setInterpolationDelay(0);
            p.d.setTransformationMatrix(m);
            if (!p.d.getWorld().equals(tp.getWorld()) || p.d.getLocation().distanceSquared(tp) > 0.0004) p.d.teleport(tp);
        }
        // glitch flicker: a random body part shows a wrong block for a moment
        if (f.t % 9 == 0 && !f.parts.isEmpty()) {
            Part p = f.parts.get(random.nextInt(f.parts.size()));
            if (!p.joint.startsWith("orbit") && !p.joint.equals("eyes")) {
                p.d.setBlock(Bukkit.createBlockData(random.nextBoolean() ? "minecraft:magenta_concrete" : "minecraft:black_concrete"));
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.d.isValid()) p.d.setBlock(p.base); }, 3L);
            }
        }
    }

    // ================================================================= the fight

    private List<Player> near(Location c, double r) {
        List<Player> out = new ArrayList<>();
        for (Player p : c.getWorld().getPlayers())
            if (p.getLocation().distanceSquared(c) < r * r && Farlands.survival(p) && !p.isDead()) out.add(p);
        return out;
    }

    private void fightTick(Fight f) {
        Zombie z = f.body;
        World w = z.getWorld();
        f.t++;
        double max = z.getAttribute(Attribute.MAX_HEALTH).getValue();
        f.bar.setProgress(Math.max(0, Math.min(1, z.getHealth() / max)));
        for (Player p : w.getPlayers()) {
            boolean in = p.getLocation().distanceSquared(z.getLocation()) < 90 * 90;
            if (in && !f.bar.getPlayers().contains(p)) f.bar.addPlayer(p); else if (!in && f.bar.getPlayers().contains(p)) f.bar.removePlayer(p);
        }
        // walk cycle from how far he moved
        Location now = z.getLocation();
        double moved = now.getWorld() == f.last.getWorld() ? Math.hypot(now.getX() - f.last.getX(), now.getZ() - f.last.getZ()) : 0;
        f.last = now.clone();
        f.walk += moved * 1.4;
        float wantLeg = moved > 0.02 ? (float) Math.sin(f.walk) * 0.55f : 0;
        f.legSwing += (wantLeg - f.legSwing) * 0.4f;
        if (f.move == 0) {
            f.armL += ((-f.legSwing * 0.6f) - f.armL) * 0.3f;
            f.armR += ((f.legSwing * 0.6f) - f.armR) * 0.3f;
            f.lean += (0.08f - f.lean) * 0.2f;
        }
        if (f.rising > 0) {   // he climbs out of the ground
            f.rising--;
            f.bodyDrop = f.rising * 0.15f;
            if (f.rising % 4 == 0) w.spawnParticle(Particle.BLOCK, now.clone().add(0, 0.4, 0), 40, 1.6, 0.3, 1.6, 0, Material.STONE.createBlockData());
            z.setAI(false);
            if (f.t % 2 == 0) pose(f);
            if (f.rising == 0) { z.setAI(true); w.playSound(now, Sound.ENTITY_WARDEN_ROAR, 4, 0.5f); }
            return;
        }
        if (f.t % 2 == 0) pose(f);
        if (f.t % 6 == 0) w.spawnParticle(Particle.DUST, now.clone().add(0, 3, 0), 2, 1.2, 1.5, 1.2, 0, f.phase == 3 ? GLITCH : CRIMSON);
        List<Player> ps = near(now, 70);
        for (Player p : ps) f.fighters.add(p.getUniqueId());
        if (ps.isEmpty()) { if (++f.alone > 20 * 120) leave(f, false); return; }   // AUDIT: 2 minutes with nobody near (not 2 minutes into the fight)
        f.alone = 0;
        int phase = z.getHealth() < max * cfg("phase3-at", 0.3) ? 3 : z.getHealth() < max * cfg("phase2-at", 0.65) ? 2 : 1;
        if (phase != f.phase) {
            f.phase = phase;
            w.playSound(now, Sound.ENTITY_WARDEN_ROAR, 4, phase == 3 ? 0.7f : 0.55f);
            for (Player p : ps) p.sendTitle(phase == 3 ? ChatColor.LIGHT_PURPLE + "UNSTABLE" : ChatColor.DARK_RED + "THE RIM CRACKS",
                    phase == 3 ? ChatColor.GRAY + "The world is coming apart round him." : "", 5, 40, 10);
            if (phase == 3) {
                z.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 1, false, false));
                z.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, PotionEffect.INFINITE_DURATION, 0, false, false));
            }
        }
        if (f.target == null || !f.target.isValid() || f.target.isDead() || !ps.contains(f.target) || f.t % 100 == 0) f.target = ps.get(random.nextInt(ps.size()));
        if (z.getTarget() != f.target) z.setTarget(f.target);
        // left far behind (a wall, a pillar): he leaps
        if (f.move == 0 && f.t % 60 == 0 && f.target.getLocation().distanceSquared(now) > 26 * 26) {
            Vector to = f.target.getLocation().toVector().subtract(now.toVector());
            Safe.vel(z, to.clone().setY(0).normalize().multiply(1.6).setY(1.1));
            w.playSound(now, Sound.ENTITY_RAVAGER_ROAR, 2, 0.5f);
        }
        if (phase == 3 && f.t % 60 == 0) glitchRain(f, ps);
        tickBoulders(f, ps);
        tickLines(f, ps);
        if (f.move != 0) { moveTick(f, ps); return; }
        if (f.t < f.nextMove) return;
        int[] pool = phase == 1 ? new int[]{1, 2, 3} : new int[]{1, 2, 3, 4, 5, 6, 4, 5};
        f.move = pool[random.nextInt(pool.length)];
        if (f.move == 6 && f.minions.stream().filter(id -> Bukkit.getEntity(id) != null).count() >= 4) f.move = 1;
        f.moveT = 0;
        f.hitThisMove.clear();
        moveTick(f, ps);
    }

    private void endMove(Fight f) {
        f.move = 0;
        f.hidden = false;
        f.nextMove = f.t + (f.phase == 1 ? 60 : f.phase == 2 ? 45 : 30) + random.nextInt(20);
    }

    private void moveTick(Fight f, List<Player> ps) {
        Zombie z = f.body;
        World w = z.getWorld();
        Location at = z.getLocation();
        int t = f.moveT++;
        switch (f.move) {
            case 1 -> { // GROUND POUND: both fists up, slam, a ring that runs out and throws you
                if (t < 16) { f.armL += (-2.7f - f.armL) * 0.25f; f.armR += (-2.7f - f.armR) * 0.25f; f.lean += (-0.15f - f.lean) * 0.3f; z.setAI(false); }
                if (t == 2) w.playSound(at, Sound.BLOCK_GRINDSTONE_USE, 3, 0.4f);
                if (t == 16) {
                    f.armL = 0.6f; f.armR = 0.6f; f.lean = 0.35f;
                    w.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 3, 0.5f);
                    w.playSound(at, Sound.BLOCK_ANVIL_LAND, 3, 0.4f);
                    z.setAI(true);
                }
                if (t >= 16 && t <= 30) {
                    double r = (t - 16) * 0.9 + 1.5;
                    for (int i = 0; i < 40; i++) {
                        double a = i * Math.PI * 2 / 40;
                        Location l = at.clone().add(Math.cos(a) * r, 0.2, Math.sin(a) * r);
                        w.spawnParticle(Particle.BLOCK, l, 2, 0.1, 0.1, 0.1, 0, Material.STONE.createBlockData());
                    }
                    for (Player p : ps) {
                        double d = Math.hypot(p.getLocation().getX() - at.getX(), p.getLocation().getZ() - at.getZ());
                        if (Math.abs(d - r) < 1.0 && p.isOnGround() && Math.abs(p.getLocation().getY() - at.getY()) < 3 && f.hitThisMove.add(p.getUniqueId())) {
                            p.damage(cfg("moves.pound", 13), z);
                            Vector away = p.getLocation().toVector().subtract(at.toVector()).setY(0);
                            if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
                            Safe.vel(p, away.normalize().multiply(0.9).setY(0.95));
                        }
                    }
                }
                if (t > 34) endMove(f);
            }
            case 2 -> { // BOULDER TOSS
                if (t < 14) { f.armR += (-3.0f - f.armR) * 0.3f; f.lean += (-0.1f - f.lean) * 0.3f; }
                if (t == 2) { w.playSound(at, Sound.BLOCK_STONE_BREAK, 3, 0.5f); w.spawnParticle(Particle.BLOCK, at.clone().add(1.8, 0.3, 0), 30, 0.6, 0.2, 0.6, 0, Material.COBBLESTONE.createBlockData()); }
                if (t == 14) {
                    f.armR = 0.8f;
                    Player p = f.target;
                    Location from = at.clone().add(0, 5.5, 0);
                    Location to = p.getLocation().add(p.getVelocity().clone().setY(0).multiply(10));
                    BlockDisplay b = w.spawn(from, BlockDisplay.class, x -> {
                        x.setBlock(Bukkit.createBlockData(random.nextBoolean() ? "minecraft:mossy_cobblestone" : "minecraft:cobblestone"));
                        x.addScoreboardTag(PART); x.setPersistent(false); x.setTeleportDuration(1);
                        x.setTransformation(new Transformation(new org.joml.Vector3f(-1.1f, -1.1f, -1.1f), new org.joml.Quaternionf(), new org.joml.Vector3f(2.2f), new org.joml.Quaternionf()));
                    });
                    f.boulders.add(new Object[]{b, from.toVector(), to.toVector(), 0});
                    w.playSound(at, Sound.ENTITY_IRON_GOLEM_ATTACK, 3, 0.4f);
                }
                if (t > 22) endMove(f);
            }
            case 3 -> { // GLITCH STEP: vanish, reappear behind the farthest of you, and swing
                if (t == 0) {
                    f.hidden = true;
                    w.spawnParticle(Particle.DUST, at.clone().add(0, 3, 0), 120, 1.4, 2, 1.4, 0, GLITCH);
                    w.playSound(at, Sound.ENTITY_ENDERMAN_TELEPORT, 3, 0.5f);
                    z.setAI(false);
                    pose(f);
                }
                if (t == 12) {
                    Player far = f.target;
                    double best = -1;
                    for (Player p : ps) { double d = p.getLocation().distanceSquared(at); if (d > best) { best = d; far = p; } }
                    Vector back = far.getLocation().getDirection().setY(0);
                    if (back.lengthSquared() < 1e-4) back = new Vector(1, 0, 0);
                    Location to = far.getLocation().subtract(back.normalize().multiply(3.2));
                    Location spot = ground(to, 0, 2.5);
                    if (spot != null) to = spot;
                    to.setDirection(far.getLocation().toVector().subtract(to.toVector()).setY(0).lengthSquared() < 1e-4 ? back : far.getLocation().toVector().subtract(to.toVector()));
                    z.teleport(to);
                    f.last = to.clone();
                    f.target = far;
                    f.hidden = false;
                    w.spawnParticle(Particle.DUST, to.clone().add(0, 3, 0), 120, 1.4, 2, 1.4, 0, GLITCH);
                    w.playSound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 3, 0.4f);
                    f.armL = -2.2f; f.armR = -2.2f;
                }
                if (t == 18) {
                    z.setAI(true);
                    f.armL = 0.9f; f.armR = 0.9f;
                    Location c = z.getLocation();
                    Vector fwd = c.getDirection().setY(0).normalize();
                    w.playSound(c, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 3, 0.5f);
                    for (Player p : ps) {
                        Vector rel = p.getLocation().toVector().subtract(c.toVector()).setY(0);
                        if (rel.length() < 5 && rel.normalize().dot(fwd) > 0.2) {
                            p.damage(cfg("moves.glitch-step", 12), z);
                            Safe.vel(p, fwd.clone().multiply(1.4).setY(0.5));
                        }
                    }
                }
                if (t > 24) endMove(f);
            }
            case 4 -> { // RIFT BEAM: charge, lock, fire
                if (t == 0) { z.setAI(false); w.playSound(at, Sound.BLOCK_BEACON_POWER_SELECT, 3, 0.5f); }
                Location eye = at.clone().add(0, 4.8, 0);
                if (t <= 20) {
                    f.beamFrom = eye;
                    Vector d = f.target.getEyeLocation().toVector().subtract(eye.toVector());
                    f.beam = d.lengthSquared() < 1e-4 ? new Vector(1, 0, 0) : d.normalize();
                    z.setRotation((float) Math.toDegrees(Math.atan2(-f.beam.getX(), f.beam.getZ())), 0);
                }
                if (t < 30 && t % 2 == 0) for (double s = 1; s < 32; s += 1.0) w.spawnParticle(Particle.DUST, f.beamFrom.clone().add(f.beam.clone().multiply(s)), 1, 0, 0, 0, 0, t > 20 ? GLITCH : CRIMSON);
                if (t >= 30 && t < 40) {
                    for (double s = 1; s < 32; s += 0.5) w.spawnParticle(Particle.DUST, f.beamFrom.clone().add(f.beam.clone().multiply(s)), 2, 0.15, 0.15, 0.15, 0, GLITCH);
                    if (t == 30) w.playSound(at, Sound.ENTITY_WARDEN_SONIC_BOOM, 4, 0.6f);
                    for (Player p : ps) {
                        Vector rel = p.getLocation().add(0, 1, 0).toVector().subtract(f.beamFrom.toVector());
                        double along = rel.dot(f.beam);
                        if (along < 0 || along > 32 || p.getNoDamageTicks() > 0) continue;
                        if (!Farlands.sees(f.beamFrom, p.getEyeLocation())) continue;   // AUDIT: a wall blocks the beam
                        if (rel.clone().subtract(f.beam.clone().multiply(along)).length() < 1.4) {
                            p.damage(cfg("moves.beam", 17), z);
                            p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 80, 1));
                        }
                    }
                }
                if (t == 40) { z.setAI(true); endMove(f); }
            }
            case 5 -> { // FRACTURE: a crack runs at each of you, then stone spikes burst out along it
                if (t == 0) {
                    f.armL = -1.6f; f.armR = -1.6f;
                    for (Player p : ps) {
                        Vector d = p.getLocation().toVector().subtract(at.toVector()).setY(0);
                        if (d.lengthSquared() < 1e-4) continue;
                        f.lines.add(new Object[]{at.clone(), d.normalize(), 0});
                    }
                    w.playSound(at, Sound.BLOCK_DEEPSLATE_BREAK, 3, 0.4f);
                }
                if (t == 6) { f.armL = 0.7f; f.armR = 0.7f; }
                if (t > 50) endMove(f);
            }
            case 6 -> { // CALL OF THE RIM: monsters climb out of the ground (often Elites out here)
                if (t == 0) { f.armL = -2.8f; f.armR = -2.8f; w.playSound(at, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 3, 0.5f); }
                if (t == 20) {
                    EntityType[] kinds = {EntityType.ZOMBIE, EntityType.SKELETON, EntityType.HUSK, EntityType.STRAY, EntityType.VINDICATOR};
                    int n = 3 + (f.phase == 3 ? 1 : 0);
                    for (int i = 0; i < n; i++) {
                        Location l = ground(at, 4, 9);
                        if (l == null) continue;
                        Entity m = w.spawnEntity(l, kinds[random.nextInt(kinds.length)], CreatureSpawnEvent.SpawnReason.NATURAL, en -> { });
                        f.minions.add(m.getUniqueId());
                        if (m instanceof Mob mob) mob.setTarget(ps.get(random.nextInt(ps.size())));
                        w.spawnParticle(Particle.BLOCK, l.clone().add(0, 0.3, 0), 25, 0.4, 0.2, 0.4, 0, Material.DIRT.createBlockData());
                    }
                    f.armL = 0.3f; f.armR = 0.3f;
                }
                if (t > 30) endMove(f);
            }
            default -> endMove(f);
        }
    }

    private void tickBoulders(Fight f, List<Player> ps) {
        for (Iterator<Object[]> it = f.boulders.iterator(); it.hasNext(); ) {
            Object[] b = it.next();
            BlockDisplay d = (BlockDisplay) b[0];
            Vector from = (Vector) b[1], to = (Vector) b[2];
            int t = (int) b[3] + 1; b[3] = t;
            double u = t / 22.0;
            Vector pos = from.clone().multiply(1 - u).add(to.clone().multiply(u)).add(new Vector(0, Math.sin(u * Math.PI) * 9, 0));
            World w = d.getWorld();
            if (d.isValid()) d.teleport(pos.toLocation(w));
            if (t % 2 == 0) w.spawnParticle(Particle.BLOCK, pos.toLocation(w), 3, 0.4, 0.4, 0.4, 0, Material.COBBLESTONE.createBlockData());
            if (t >= 22) {
                Location l = to.toLocation(w);
                w.spawnParticle(Particle.EXPLOSION, l, 3, 0.8, 0.3, 0.8);
                w.spawnParticle(Particle.BLOCK, l, 80, 1.5, 0.4, 1.5, 0, Material.COBBLESTONE.createBlockData());
                w.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 2.5f, 0.6f);
                for (Player p : ps) {
                    double dist = p.getLocation().distance(l);
                    if (dist < 3.4) { p.damage(cfg("moves.boulder", 14) * (1 - dist / 5.0), f.body); Safe.vel(p, p.getLocation().toVector().subtract(l.toVector()).setY(0).normalize().multiply(0.8).setY(0.6)); }
                }
                d.remove();
                it.remove();
            }
        }
    }

    private void tickLines(Fight f, List<Player> ps) {
        for (Iterator<Object[]> it = f.lines.iterator(); it.hasNext(); ) {
            Object[] ln = it.next();
            Location from = (Location) ln[0];
            Vector dir = (Vector) ln[1];
            int t = (int) ln[2] + 1; ln[2] = t;
            World w = from.getWorld();
            double len = 18;
            if (t < 20) {
                if (t % 2 == 0) for (double s = 1; s < len; s += 0.7) {
                    Location l = from.clone().add(dir.clone().multiply(s));
                    w.spawnParticle(Particle.DUST, floor(l).add(0, 0.15, 0), 1, 0.05, 0, 0.05, 0, CRIMSON);
                }
            } else if (t == 20) {
                w.playSound(from, Sound.BLOCK_POINTED_DRIPSTONE_LAND, 3, 0.5f);
                for (double s = 2; s < len; s += 1.6) {
                    Location l = floor(from.clone().add(dir.clone().multiply(s)));
                    BlockDisplay spike = w.spawn(l, BlockDisplay.class, x -> {
                        x.setBlock(Bukkit.createBlockData(random.nextBoolean() ? "minecraft:stone" : "minecraft:tuff"));
                        x.addScoreboardTag(PART); x.setPersistent(false);
                        x.setInterpolationDuration(4);
                        x.setTransformation(new Transformation(new org.joml.Vector3f(-0.35f, -1f, -0.35f), new org.joml.Quaternionf(), new org.joml.Vector3f(0.7f, 0.01f, 0.7f), new org.joml.Quaternionf()));
                    });
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!spike.isValid()) return;
                        spike.setInterpolationDelay(0);
                        spike.setTransformation(new Transformation(new org.joml.Vector3f(-0.35f, -0.4f, -0.35f), new org.joml.Quaternionf((float) (random.nextGaussian() * 0.12), 0, (float) (random.nextGaussian() * 0.12), 1).normalize(),
                                new org.joml.Vector3f(0.7f, 2.6f + random.nextFloat(), 0.7f), new org.joml.Quaternionf()));
                    }, 1L);
                    f.temp.add(spike);
                    Bukkit.getScheduler().runTaskLater(plugin, () -> { spike.remove(); f.temp.remove(spike); }, 40L);
                    w.spawnParticle(Particle.BLOCK, l.clone().add(0, 0.3, 0), 10, 0.3, 0.2, 0.3, 0, Material.STONE.createBlockData());
                }
                for (Player p : ps) {
                    Vector rel = p.getLocation().toVector().subtract(from.toVector()).setY(0);
                    double along = rel.dot(dir);
                    if (along < 1 || along > len) continue;
                    if (rel.clone().subtract(dir.clone().multiply(along)).length() < 1.4) {
                        p.damage(cfg("moves.fracture", 12), f.body);
                        Safe.vel(p, new Vector(0, 1.1, 0));
                    }
                }
                it.remove();
            }
        }
    }

    private static Location floor(Location l) {
        World w = l.getWorld();
        Location c = l.clone();
        for (int i = 0; i < 6; i++) {
            if (c.getBlock().getType().isSolid()) { c.add(0, 1, 0); continue; }
            if (!c.clone().add(0, -1, 0).getBlock().getType().isSolid()) { c.add(0, -1, 0); continue; }
            break;
        }
        c.setY(c.getBlockY());
        return c;
    }

    private void glitchRain(Fight f, List<Player> ps) {
        World w = f.body.getWorld();
        for (Player p : ps) {
            Location target = p.getLocation().add(random.nextGaussian() * 1.5, 0, random.nextGaussian() * 1.5);
            for (int i = 0; i < 24; i++) {
                double a = i * Math.PI * 2 / 24;
                w.spawnParticle(Particle.DUST, target.clone().add(Math.cos(a) * 1.6, 0.15, Math.sin(a) * 1.6), 1, 0, 0, 0, 0, GLITCH);
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (fight != f) return;
                w.spawnParticle(Particle.BLOCK, target.clone().add(0, 0.5, 0), 40, 0.6, 0.6, 0.6, 0, Material.MAGENTA_CONCRETE.createBlockData());
                w.playSound(target, Sound.BLOCK_GLASS_BREAK, 2, 0.5f);
                for (Player q : ps) if (q.isValid() && q.getWorld() == w && q.getLocation().distance(target) < 1.8) q.damage(cfg("moves.glitch-rain", 8), f.body);
            }, 25L);
        }
    }

    // ================================================================= damage, death

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) {
        Fight f = fight;
        if (f == null || e.getEntity() != f.body) return;
        EntityDamageEvent.DamageCause c = e.getCause();
        if (c == EntityDamageEvent.DamageCause.FALL || c == EntityDamageEvent.DamageCause.SUFFOCATION || c == EntityDamageEvent.DamageCause.DROWNING
                || c == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION && !(e instanceof EntityDamageByEntityEvent be0 && TotemLimit.playerBehind(be0.getDamager()) != null)
                || f.rising > 0 || f.hidden) { e.setCancelled(true); return; }
        e.setDamage(e.getDamage() * (1 - cfg("damage-reduction", 0.15)));
        if (e instanceof EntityDamageByEntityEvent be) {
            Player by = TotemLimit.playerBehind(be.getDamager());
            if (by != null) f.damage.merge(by.getUniqueId(), e.getFinalDamage(), Double::sum);
            else if (!(be.getDamager() instanceof Player)) e.setCancelled(true);   // only players (and what they throw) hurt him
        }
        f.body.getWorld().spawnParticle(Particle.BLOCK, f.body.getLocation().add(0, 3, 0), 12, 1, 1.5, 1, 0, Material.STONE.createBlockData());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (e.getEntity().getScoreboardTags().contains(TAG) && e.getTarget() != null && !(e.getTarget() instanceof Player)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent e) { if (e.getEntity().getScoreboardTags().contains(TAG)) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCombust(EntityCombustEvent e) { if (e.getEntity().getScoreboardTags().contains(TAG)) e.setCancelled(true); }

    /** AUDIT: he never walks through a portal (his model would stay behind). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(EntityPortalEvent e) { if (e.getEntity().getScoreboardTags().contains(TAG)) e.setCancelled(true); }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Fight f = fight;
        if (f == null || e.getEntity() != f.body) return;
        e.getDrops().clear();
        e.getDrops().add(new ItemStack(Material.NETHERITE_INGOT, 2));
        e.getDrops().add(new ItemStack(Material.DIAMOND_BLOCK, 3));
        e.setDroppedExp((int) cfg("xp", 6000));
        UUID top = f.damage.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        int given = 0;
        for (UUID id : f.fighters) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !f.damage.containsKey(id)) continue;   // you have to have hit him
            ToiletRaid.rewards(plugin, p, "rim-warden.rewards", DEFAULT_REWARDS, random);
            if ((id.equals(top) || random.nextDouble() < cfg("accessory-chance", 0.25)) && plugin.darkLoot != null) {
                ItemStack a = plugin.darkLoot.accessory();
                if (a != null) for (ItemStack left : p.getInventory().addItem(a).values()) p.getWorld().dropItem(p.getLocation(), left);
            }
            if (random.nextDouble() < cfg("sigil-back-chance", 0.15))
                for (ItemStack left : p.getInventory().addItem(sigil(1)).values()) p.getWorld().dropItem(p.getLocation(), left);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "index stat " + p.getName() + " rim_wardens 1");
            given++;
        }
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "⛰ The Warden of the Rim has fallen! " + ChatColor.GRAY + given + " fighter" + (given == 1 ? "" : "s") + " share the spoils.");
        Location l = f.body.getLocation();
        l.getWorld().spawnParticle(Particle.BLOCK, l.clone().add(0, 3, 0), 300, 1.6, 2.5, 1.6, 0, Material.STONE.createBlockData());
        l.getWorld().playSound(l, Sound.ENTITY_WARDEN_DEATH, 4, 0.5f);
        leave(f, true);
    }

    static final List<String> DEFAULT_REWARDS = List.of(
            "givemythicbag 2 {player}", "givegoodiebag 4 {player}", "darkloot bag 2 {player}", "fenchant give {enchant} 2 1 {player}",
            "item NETHERITE_INGOT 2", "item DIAMOND_BLOCK 3", "item ENCHANTED_GOLDEN_APPLE 1", "item TOTEM_OF_UNDYING 1", "item EXPERIENCE_BOTTLE 32");

    void leave(Fight f, boolean died) {
        if (fight != f) return;
        fight = null;
        restUntil = tick + (long) (cfg("cooldown-minutes", 30) * 1200);
        f.bar.removeAll();
        for (Part p : f.parts) p.d.remove();
        for (Object[] b : f.boulders) ((Entity) b[0]).remove();
        for (Entity e : new ArrayList<>(f.temp)) e.remove();
        for (UUID id : f.minions) { Entity m = Bukkit.getEntity(id); if (m != null && !died) m.remove(); }
        if (!died && f.body.isValid()) {
            f.body.getWorld().spawnParticle(Particle.BLOCK, f.body.getLocation().add(0, 2, 0), 150, 1.5, 2, 1.5, 0, Material.STONE.createBlockData());
            f.body.remove();
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "⛰ The Warden of the Rim sinks back into the Farlands.");
        }
    }

    // ================================================================= ticks

    private void tickAll() {
        tick++;
        try {
            Fight f = fight;
            if (f != null) {
                if (!f.body.isValid() || f.body.isDead()) { if (!f.body.isDead()) leave(f, false); }
                else fightTick(f);
            } else if (tick % 1200 == 0 && tick > restUntil && plugin.getConfig().getBoolean("rim-warden.natural", true)) {
                // 5% a minute while someone's out in the Farlands
                List<Player> out = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) if (Farlands.survival(p) && !p.isDead() && Farlands.in(p.getLocation())) out.add(p);
                if (!out.isEmpty() && random.nextDouble() < cfg("spawn-chance", 0.05)) summon(out.get(random.nextInt(out.size())));
            }
        } catch (RuntimeException ex) {
            StackTraceElement at = Arrays.stream(ex.getStackTrace()).filter(st -> st.getClassName().startsWith("net.faultlinesmp")).findFirst().orElse(null);
            plugin.getLogger().warning("[RimWarden] " + ex + (at == null ? "" : " at " + at.getMethodName() + ":" + at.getLineNumber()));
        }
    }

    void shutdown() { Fight f = fight; if (f != null) leave(f, false); }

    // ================================================================= command

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "status" : a[0].toLowerCase(Locale.ROOT);
        if (!s.hasPermission(PERM)) {
            Fight f = fight;
            s.sendMessage(ChatColor.DARK_RED + "The Warden of the Rim " + ChatColor.GRAY + (f == null ? "sleeps under the Farlands. A Rim Sigil wakes him." : "is awake in the Farlands."));
            return true;
        }
        Player p = s instanceof Player pl ? pl : null;
        switch (sub) {
            case "summon" -> {
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : p;
                if (t == null) { s.sendMessage(ChatColor.RED + "/rimwarden summon [player]"); return true; }
                s.sendMessage(summon(t) != null ? ChatColor.GREEN + "The Warden of the Rim rises." : ChatColor.RED + "He's already out.");
            }
            case "kill" -> { Fight f = fight; if (f != null) leave(f, false); restUntil = 0; s.sendMessage(ChatColor.YELLOW + "Gone."); }
            case "sigil" -> {
                int n = 1;
                Player t = p;
                if (a.length > 1) try { n = Integer.parseInt(a[1]); } catch (NumberFormatException ex) { t = Bukkit.getPlayerExact(a[1]); }
                if (a.length > 2) t = Bukkit.getPlayerExact(a[2]);
                if (t == null) { s.sendMessage(ChatColor.RED + "/rimwarden sigil [n] [player]"); return true; }
                for (ItemStack left : t.getInventory().addItem(sigil(Math.max(1, Math.min(64, n)))).values()) t.getWorld().dropItem(t.getLocation(), left);
                s.sendMessage(ChatColor.GREEN + "Gave " + n + " Rim Sigil(s) to " + t.getName() + ".");
            }
            default -> {
                Fight f = fight;
                s.sendMessage(ChatColor.DARK_RED + (f == null ? "The Warden of the Rim isn't out" + (tick < restUntil ? " (resting " + ((restUntil - tick) / 1200 + 1) + " min)" : "") + "."
                        : "The Warden at " + Where.spot(f.body.getLocation()) + ", " + Math.round(f.body.getHealth()) + " hp, phase " + f.phase)
                        + ChatColor.GRAY + "  /rimwarden summon [player] | kill | sigil [n] [player]");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission(PERM)) return List.of();
        if (a.length == 1) return Legendary.filter(List.of("summon", "kill", "sigil", "status"), a[0]);
        if (a.length == 2 && !a[0].equalsIgnoreCase("sigil")) { List<String> l = new ArrayList<>(); for (Player p : Bukkit.getOnlinePlayers()) l.add(p.getName()); return Legendary.filter(l, a[1]); }
        return List.of();
    }
}
