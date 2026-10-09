package net.faultlinesmp.bosses;

import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkull;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;

import static net.faultlinesmp.bosses.FaultlineBosses.*;

/**
 * THE LICH: an undead sorcerer-king, 2,800 health, who haunts the Deep Dark. Break a Cursed Phylactery there to call
 * him. He floats, keeps his distance, and blinks around.
 *   Phase 1: Soul Bolts (homing skulls), Raise the Fallen (Revenants: zombies wearing YOUR faces), Death Ring (a ring of
 *            soul fire races out along the floor: be in the air when it passes), Blink Strike (he appears behind you).
 *   Phase 2 (60%): he hides his life in three Phylacteries (end crystals, beams to him): he can't be hurt until
 *            they're broken. + Darkness.
 *   Phase 3 (30%): + Life Drain (beams from everyone near him heal him: break line of sight or get away).
 */
final class Lich extends Wild.Boss {

    static final int BOLTS = 1, RAISE = 2, RING = 3, BLINK = 4, DARKNESS = 5, DRAIN = 6;
    static final String CRYSTAL_TAG = "faultline_lich_crystal", REVENANT_TAG = "faultline_revenant";

    final double k;
    final Wild.Part model;
    Location pos;
    float yaw;
    final List<EnderCrystal> crystals = new ArrayList<>();
    boolean shielded;
    // the current move
    Location mark;
    double ringR;
    final Set<UUID> hitThisMove = new HashSet<>();

    Lich(Wild w, Location at, Player by) {
        super(w, "lich", at, by);
        k = c("scale", 1.3);
        pos = Wild.freeSpot(at, 5, 9); // a free spot in the cave near you (not up on the surface)
        pos.setY(pos.getY() + 0.4);
        yaw = at.getYaw() + 180;
        model = rig.add("lich", k, pos);
        hitbox(pos, (int) Math.round(3 * k), "The Lich");
        hitbox(pos, (int) Math.round(3 * k), "The Lich");
        world.strikeLightningEffect(pos);
        world.spawnParticle(Particle.SOUL, pos.clone().add(0, 1.5, 0), 80, 1, 1.5, 1, 0.05);
        world.playSound(pos, Sound.ENTITY_WITHER_SPAWN, SoundCategory.HOSTILE, 3f, 0.6f);
        cd = 50;
    }

    @Override double defHealth() { return 2800; }
    @Override BarColor barColor() { return BarColor.PURPLE; }
    @Override String music() { return "minecraft:music_disc.13"; }
    @Override double musicLength() { return 178; }
    @Override Location center() { return pos.clone().add(0, 1.4 * k, 0); }
    @Override boolean valid() { return model.d != null && model.d.isValid(); }

    @Override String line(String what) {
        return switch (what) {
            case "spawn" -> ChatColor.DARK_PURPLE + "\"You broke my vessel. Then you will take its place.\"";
            case "phase2" -> ChatColor.DARK_PURPLE + "\"My life is not here. It never was.\" " + ChatColor.GRAY + "(Break the three Phylacteries!)";
            case "phase3" -> ChatColor.RED + "\"Give me your warmth... ALL of it.\"";
            case "death" -> ChatColor.DARK_PURPLE + "\"Impossible... I am... eternal...\"";
            case "leave" -> ChatColor.DARK_PURPLE + "The Lich " + ChatColor.GRAY + "fades back into the dark.";
            default -> null;
        };
    }

    // =====================================================================================================
    //  floating
    // =====================================================================================================
    @Override
    void bodyTick(List<Player> a) {
        Player t = pilot != null ? pilot : focus != null && focus.isOnline() && a.contains(focus) ? focus : a.isEmpty() ? null : a.get(0);
        if (attack != BLINK && t != null) {
            Vector to = t.getLocation().toVector().subtract(pos.toVector()).setY(0);
            double d = to.length();
            yaw = turn(yaw, yawOf(flat(to)), 10);
            double want = pilot != null ? 2.5 : c("keep-distance", 9);
            if (pilot != null) { if (d > want) pos.add(flat(to).multiply(Math.min(0.4, d - want))); }
            else if (d > want + 2) pos.add(flat(to).multiply(0.16));
            else if (d < want - 3) pos.add(flat(to).multiply(-0.16));
            else pos.add(new Vector(-to.getZ(), 0, to.getX()).normalize().multiply(0.06)); // drift sideways
        }
        double gy = ground(pos);
        pos.setY(gy + 0.5 + Math.sin(ticks * 0.07) * 0.25);
        model.pose(pos, yaw, 0, (float) Math.sin(ticks * 0.05) * 3, null);
        for (int i = 0; i < hitboxes.size(); i++) hitboxes.get(i).teleport(pos.clone().add(0, i * 1.5 * k, 0));
        if (ticks % 3 == 0) world.spawnParticle(Particle.SOUL_FIRE_FLAME, pos.clone().add(0, 0.2, 0), 2, 0.4, 0.1, 0.4, 0.01);
        if (ticks % 6 == 0) world.spawnParticle(Particle.DUST, center().add(0, 1.3 * k, 0), 2, 0.2, 0.1, 0.2, 0, new Particle.DustOptions(Color.fromRGB(90, 255, 130), 1.2f));
        // the phylacteries' beams
        crystals.removeIf(cr -> !cr.isValid());
        if (shielded) {
            for (EnderCrystal cr : crystals) cr.setBeamTarget(center().getBlock().getLocation());
            if (crystals.isEmpty()) {
                shielded = false;
                say(ChatColor.LIGHT_PURPLE + "\"No... NO! My vessels!\"");
                world.playSound(pos, Sound.ENTITY_WITHER_HURT, SoundCategory.HOSTILE, 3f, 0.5f);
            } else if (ticks % 10 == 0) world.spawnParticle(Particle.ENCHANT, center(), 30, 0.8, 1.2, 0.8, 0.5);
        }
    }

    @Override
    double damageTaken(double amount) {
        if (shielded) {
            if (ticks % 10 == 0) world.playSound(pos, Sound.BLOCK_AMETHYST_BLOCK_HIT, SoundCategory.HOSTILE, 2f, 0.6f);
            for (Player p : active()) if (p.getLocation().distanceSquared(pos) < 12 * 12) p.sendActionBar(legacy(ChatColor.LIGHT_PURPLE + "His life is in the Phylacteries: break them first!"));
            return 0;
        }
        return amount;
    }

    @Override
    void onPhase(int p) {
        if (p == 2) {
            shielded = true;
            for (int i = 0; i < 3; i++) {
                double ang = i * Math.PI * 2 / 3 + random.nextDouble();
                Location l = home.clone().add(Math.cos(ang) * c("crystal-distance", 12), 0, Math.sin(ang) * c("crystal-distance", 12));
                l.setY(ground(l) + 1);
                EnderCrystal cr = world.spawn(l, EnderCrystal.class, e -> {
                    e.setShowingBottom(false);
                    e.setPersistent(false);
                    e.addScoreboardTag(Wild.FX_TAG);
                    e.addScoreboardTag(CRYSTAL_TAG);
                    e.setCustomName(ChatColor.LIGHT_PURPLE + "Phylactery");
                });
                crystals.add(cr);
                world.spawnParticle(Particle.REVERSE_PORTAL, l, 60, 0.5, 1, 0.5, 0.1);
            }
        }
    }

    /** A Phylactery was hit by a player: it shatters. */
    void crystalBroken(EnderCrystal cr) {
        if (!crystals.remove(cr)) return;
        world.spawnParticle(Particle.EXPLOSION, cr.getLocation(), 2, 0.4, 0.4, 0.4, 0);
        world.spawnParticle(Particle.SOUL, cr.getLocation(), 40, 0.6, 0.8, 0.6, 0.05);
        world.playSound(cr.getLocation(), Sound.BLOCK_GLASS_BREAK, SoundCategory.HOSTILE, 2f, 0.5f);
        cr.remove();
        if (!crystals.isEmpty()) say(ChatColor.GRAY + "A Phylactery shatters. (" + crystals.size() + " left)");
    }

    // =====================================================================================================
    //  moves
    // =====================================================================================================
    @Override
    int pick(List<Player> a) {
        List<Integer> pool = new ArrayList<>(List.of(BOLTS, BOLTS, RING, BLINK));
        if (minions.size() < 3 + a.size()) pool.add(RAISE);
        if (phase >= 2) pool.add(DARKNESS);
        if (phase == 3) { pool.add(DRAIN); pool.add(DRAIN); }
        return pool.get(random.nextInt(pool.size()));
    }

    @Override
    void startMove(int move, Player t) {
        hitThisMove.clear(); ringR = 0;
        switch (move) {
            case RING -> { mark = pos.clone(); mark.setY(ground(mark)); world.playSound(pos, Sound.ENTITY_EVOKER_PREPARE_ATTACK, SoundCategory.HOSTILE, 3f, 0.6f); }
            case RAISE -> world.playSound(pos, Sound.ENTITY_EVOKER_PREPARE_SUMMON, SoundCategory.HOSTILE, 3f, 0.6f);
            case BLINK -> { mark = t != null ? t.getLocation().clone() : pos.clone(); world.playSound(pos, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 2f, 0.5f); }
            case DARKNESS -> world.playSound(pos, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 3f, 0.6f);
            case DRAIN -> world.playSound(pos, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.HOSTILE, 3f, 0.5f);
            default -> world.playSound(pos, Sound.ENTITY_WITHER_AMBIENT, SoundCategory.HOSTILE, 2f, 1.3f);
        }
    }

    @Override
    void moveTick(List<Player> a) {
        switch (attack) {
            case BOLTS -> bolts(a);
            case RAISE -> raise(a);
            case RING -> ring(a);
            case BLINK -> blink(a);
            case DARKNESS -> darkness(a);
            case DRAIN -> drain(a);
            default -> end();
        }
    }

    void bolts(List<Player> a) {
        int n = phase >= 2 ? 5 : 3;
        if (at % 8 == 4 && at / 8 < n) {
            Player t = a.isEmpty() ? null : a.get(random.nextInt(a.size()));
            if (t != null) {
                Location from = center().add(0, 1.0 * k, 0).add(dirOf(yaw));
                Vector v = t.getEyeLocation().toVector().subtract(from.toVector()).normalize().multiply(0.8);
                WitherSkull s = world.spawn(from, WitherSkull.class, sk -> {
                    sk.setYield(0);
                    sk.setIsIncendiary(false);
                    sk.setCharged(phase == 3);
                    sk.addScoreboardTag(Wild.FX_TAG);
                    sk.addScoreboardTag("faultline_lich_bolt");
                });
                s.setDirection(v);
                s.setAcceleration(v.clone().multiply(0.1));
                if (source() != null) s.setShooter(source());
                Player target = t;
                int[] life = {0};
                fx.add(() -> { // they home in a little
                    if (!s.isValid() || ++life[0] > 80) { if (s.isValid()) s.remove(); return true; }
                    if (target.isOnline() && target.getWorld().equals(world)) {
                        Vector want = target.getEyeLocation().toVector().subtract(s.getLocation().toVector()).normalize();
                        Vector cur = s.getVelocity().lengthSquared() > 1e-4 ? s.getVelocity().clone().normalize() : want;
                        Vector nv = cur.multiply(0.88).add(want.multiply(0.12)).normalize().multiply(0.75);
                        s.setVelocity(nv); s.setDirection(nv);
                    }
                    world.spawnParticle(Particle.SOUL, s.getLocation(), 2, 0.1, 0.1, 0.1, 0.01);
                    return false;
                });
            }
            world.playSound(pos, Sound.ENTITY_WITHER_SHOOT, SoundCategory.HOSTILE, 2f, 0.8f);
        }
        if (at > n * 8 + 8) end();
    }

    /** A Lich bolt lands. */
    static void boltLands(Wild w, ProjectileHitEvent e) {
        e.setCancelled(true);
        Entity pr = e.getEntity();
        pr.getWorld().spawnParticle(Particle.SOUL, pr.getLocation(), 20, 0.5, 0.5, 0.5, 0.03);
        pr.getWorld().playSound(pr.getLocation(), Sound.PARTICLE_SOUL_ESCAPE, SoundCategory.HOSTILE, 1.5f, 0.6f);
        Wild.Boss b = w.get("lich");
        if (e.getHitEntity() instanceof Player p && b != null) {
            b.hit(p, b.c("moves.soul-bolt", 7), pr.getLocation().toVector(), Guard.BLOCKABLE);
            if (survival(p)) p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 1));
        }
        pr.remove();
    }

    /** Revenants: zombies wearing the faces of the people fighting him. */
    void raise(List<Player> a) {
        if (at == 16) {
            int n = 2 + Math.min(4, a.size());
            for (int i = 0; i < n; i++) {
                Player face = a.isEmpty() ? null : a.get(i % a.size());
                Location l = pos.clone().add(random.nextGaussian() * 4, 0, random.nextGaussian() * 4);
                l.setY(ground(l));
                Zombie z = minion(l, Zombie.class, ChatColor.DARK_PURPLE + "Revenant" + (face != null ? " of " + face.getName() : ""), 30, m -> {
                    m.setShouldBurnInDay(false);
                    m.setAdult();
                    m.setCanPickupItems(false);
                    m.addScoreboardTag(REVENANT_TAG);
                    attr(m, Attribute.MOVEMENT_SPEED, 0.27);
                    attr(m, Attribute.ATTACK_DAMAGE, 6);
                    if (face != null) {
                        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                        SkullMeta sm = (SkullMeta) head.getItemMeta();
                        sm.setOwningPlayer(face);
                        head.setItemMeta(sm);
                        m.getEquipment().setHelmet(head);
                    }
                    m.getEquipment().setChestplate(new ItemStack(Material.CHAINMAIL_CHESTPLATE));
                    m.getEquipment().setHelmetDropChance(0); m.getEquipment().setChestplateDropChance(0);
                });
                if (face != null) z.setTarget(face);
                world.spawnParticle(Particle.SCULK_SOUL, l.clone().add(0, 0.5, 0), 20, 0.4, 0.6, 0.4, 0.02);
            }
        }
        if (at > 26) end();
    }

    /** A ring of soul fire runs out along the floor: jump it. */
    void ring(List<Player> a) {
        if (at < 18) { if (at % 3 == 0) warnRing(mark, 2, Color.fromRGB(90, 255, 200)); return; }
        ringR += 0.45;
        int n = (int) Math.max(16, ringR * 8);
        for (int i = 0; i < n; i++) {
            double ang = i * Math.PI * 2 / n;
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, mark.getX() + Math.cos(ang) * ringR, mark.getY() + 0.2, mark.getZ() + Math.sin(ang) * ringR, 1, 0, 0.05, 0, 0);
        }
        for (Player p : a) {
            Location l = p.getLocation();
            double d = Math.hypot(l.getX() - mark.getX(), l.getZ() - mark.getZ());
            if (Math.abs(d - ringR) < 0.7 && l.getY() - mark.getY() < 0.6 && hitThisMove.add(p.getUniqueId())) {
                hit(p, c("moves.death-ring", 10), mark.toVector(), Guard.UNBLOCKABLE);
                p.setFireTicks(60);
            }
        }
        if (ringR > 18) end();
    }

    /** He vanishes and reappears behind his target, then strikes. */
    void blink(List<Player> a) {
        if (at == 8) {
            world.spawnParticle(Particle.LARGE_SMOKE, center(), 30, 0.5, 1, 0.5, 0.02);
            Location behind = mark.clone().add(dirOf(mark.getYaw()).multiply(-2.5));
            behind.setY(ground(behind) + 0.5);
            pos = behind;
            yaw = mark.getYaw();
            world.playSound(pos, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.HOSTILE, 2f, 0.7f);
            world.spawnParticle(Particle.REVERSE_PORTAL, center(), 40, 0.5, 1, 0.5, 0.1);
        }
        if (at == 20) {
            world.playSound(pos, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 2f, 0.5f);
            Location front = pos.clone().add(dirOf(yaw).multiply(2));
            world.spawnParticle(Particle.SWEEP_ATTACK, front.clone().add(0, 1, 0), 3, 0.6, 0.3, 0.6, 0);
            for (Player p : near(front, 2.6, 3)) hit(p, c("moves.blink-strike", 12), pos.toVector(), Guard.BLOCKABLE);
        }
        if (at > 28) end();
    }

    void darkness(List<Player> a) {
        if (at == 10) for (Player p : a) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 140, 0));
            p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, SoundCategory.HOSTILE, 1f, 0.5f);
        }
        if (at > 20) end();
    }

    /** Beams from everyone in reach: they lose health, he gains it. */
    void drain(List<Player> a) {
        double r = c("drain-radius", 14);
        Location c = center().add(0, 0.6 * k, 0);
        for (Player p : a) {
            if (p.getLocation().distanceSquared(c) > r * r || !p.hasLineOfSight(hitboxes.get(0))) continue;
            Vector d = p.getEyeLocation().toVector().subtract(c.toVector());
            double len = d.length();
            d.normalize();
            if (at % 2 == 0) for (double t = 0; t < len; t += 0.6)
                world.spawnParticle(Particle.DUST, c.clone().add(d.clone().multiply(t)), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(120, 255, 140), 1f));
            if (at % 20 == 10) {
                hit(p, c("moves.drain", 3), c.toVector(), Guard.UNBLOCKABLE);
                hp = Math.min(maxHp, hp + c("drain-heal", 25));
            }
        }
        if (at > 100) end();
    }

    @Override
    void deathAnim(int t) {
        pos = pos.clone().add(0, 0.04, 0);
        model.pose(pos, yaw + t * 6, 0, 0, null);
        if (t % 2 == 0) world.spawnParticle(Particle.SOUL, center(), 10, 0.5, 1, 0.5, 0.05);
        if (t % 15 == 0) world.playSound(pos, Sound.ENTITY_WITHER_DEATH, SoundCategory.HOSTILE, 1.5f, 1.4f);
    }

    @Override
    void removeEverything() {
        for (EnderCrystal cr : crystals) if (cr.isValid()) cr.remove();
        crystals.clear();
        super.removeEverything();
    }

    @Override void drops(Player p, Location at) { maybeDrop(p, at, "lich_staff", c("rewards.staff-chance", 0.25)); }

    @Override
    List<MoveSlot> morphMoves() {
        List<MoveSlot> m = new ArrayList<>();
        m.add(new MoveSlot(BOLTS, "Soul Bolts", Material.WITHER_SKELETON_SKULL));
        m.add(new MoveSlot(RAISE, "Raise the Fallen", Material.ZOMBIE_HEAD));
        m.add(new MoveSlot(RING, "Death Ring", Material.SOUL_CAMPFIRE));
        m.add(new MoveSlot(BLINK, "Blink Strike", Material.ENDER_PEARL));
        if (phase >= 2) m.add(new MoveSlot(DARKNESS, "Darkness", Material.SCULK_SHRIEKER));
        if (phase == 3) m.add(new MoveSlot(DRAIN, "Life Drain", Material.GHAST_TEAR));
        return m;
    }
}
