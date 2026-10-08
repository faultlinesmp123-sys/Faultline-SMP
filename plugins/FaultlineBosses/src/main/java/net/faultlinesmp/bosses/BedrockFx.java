package net.faultlinesmp.bosses;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Bedrock players (Geyser) can't see display entities at all, and every boss move is drawn with them: Jacob's spear
 * walls, the Lost Explorer's slash lines and warnings, Don's spears, the Kraken's bubbles, Rocco's props, piglin
 * balloons... So for Bedrock players only, every such display is traced in particles where it really is, at its size:
 * a block display in that block's own crack particles (red warning glass reads red), a vanilla item in bits of that
 * item, a custom model in coloured dust (its glow colour, or a colour from its name: fire orange, frost blue, pink...).
 *
 * Bodies are left out (each boss already has a Bedrock stand-in mob), and so are ships and cosmetics (their own
 * Bedrock packs draw those). Java players get none of it.
 */
final class BedrockFx {
    private final FaultlineBosses pl;
    private final Random rnd = new Random();
    /** Per Bedrock player: the displays near them, refreshed every second (a world scan every 2 ticks would be wasteful). */
    private final Map<UUID, List<Display>> near = new HashMap<>();
    private int ticks;

    /** Boss bodies (they have stand-ins), cosmetics, the cave trader: not traced. */
    static final Pattern BODY = Pattern.compile("^(cosmetic/.*|jacob_armor_.*|jacob_blaze_.*|jacob_bird_.*|werner_.*|rocco_.*|vendetta_goon_.*"
            + "|swarm_.*|explorer_(leg|arm|body|head).*|explorer_tiger_.*|wanderer_.*|don_(leg|arm|body|head).*|demon_eye(_mouth)?"
            + "|mf_wizard.*|mf_snow_monster.*|mf_snowflake|mf_icicle_runner|kraken_mantle.*|kraken_beak|kraken_pupil"
            + "|vulture_.*|snow_owl_.*|dune_(head|body|tail).*|frostmaw_.*|ship/.*|ship_cannon|wild/.*|"
            // what they hold (it moves with the stand-in's hands, and would just be noise at their side)
            + "jacob_hammer|jacob_sword|explorer_blade|explorer_axe|below_pickaxe)$");

    /** A boss's weapon in its hand: it moves with the stand-in, so it isn't traced on its own. */
    static final String HELD_TAG = "faultline_held";

    BedrockFx(FaultlineBosses pl) { this.pl = pl; }

    void tick() {
        ticks++;
        if (ticks % 2 != 0) return;
        List<Player> bedrock = new ArrayList<>();
        for (Player p : pl.getServer().getOnlinePlayers()) if (FaultlineBosses.bedrock(p)) bedrock.add(p);
        if (bedrock.isEmpty()) { near.clear(); return; }
        if (ticks % 20 == 0) {
            near.clear();
            for (Player p : bedrock) {
                List<Display> list = new ArrayList<>();
                for (Entity e : p.getNearbyEntities(48, 32, 48)) if (e instanceof Display d && traced(d)) list.add(d);
                near.put(p.getUniqueId(), list);
            }
        }
        int cap = (int) pl.getConfig().getDouble("bedrock.fx-particles-per-player", 160);
        for (Player p : bedrock) {
            List<Display> list = near.get(p.getUniqueId());
            if (list == null || list.isEmpty()) continue;
            int budget = cap;
            for (Display d : list) {
                if (budget <= 0) break;
                if (!d.isValid() || !d.getWorld().equals(p.getWorld())) continue;
                budget -= draw(p, d);
            }
        }
    }

    /** Ours, a thing that's part of a move, and not something with its own Bedrock version. */
    static boolean traced(Display d) {
        if (d instanceof org.bukkit.entity.TextDisplay) return false;
        for (String t : d.getScoreboardTags()) if (t.equals("faultline_ship") || t.equals(HELD_TAG)) return false;
        if (d instanceof ItemDisplay id) {
            ItemStack it = id.getItemStack();
            if (it == null || it.getType().isAir()) return false;
            if (it.getType() == Material.LANTERN || it.getType() == Material.SOUL_LANTERN) return false; // decoration
            String model = modelName(it);
            if (model != null && BODY.matcher(model).matches()) return false;
        } else if (d instanceof BlockDisplay bd) {
            Material m = bd.getBlock().getMaterial();
            if (m.isAir() || m == Material.BARRIER || m == Material.LIGHT) return false;
        }
        Transformation tr = d.getTransformation();
        Vector3f sc = tr.getScale();
        return Math.max(sc.x, Math.max(sc.y, sc.z)) > 0.02f; // hidden (scaled to nothing)
    }

    static String modelName(ItemStack it) {
        if (!it.hasItemMeta() || !it.getItemMeta().hasItemModel()) return null;
        NamespacedKey k = it.getItemMeta().getItemModel();
        return k == null ? null : k.getKey();
    }

    /** Particles for one display, seen by one player. Returns how many it sent. */
    private int draw(Player p, Display d) {
        Transformation tr = d.getTransformation();
        Vector3f t = tr.getTranslation(), sc = tr.getScale();
        float size = Math.max(sc.x, Math.max(sc.y, sc.z));
        Location base = d.getLocation();
        // the translation is in the display's own frame (turned by its yaw)
        double yaw = Math.toRadians(base.getYaw()), cos = Math.cos(yaw), sin = Math.sin(yaw);
        double cx = base.getX() + t.x * cos - t.z * sin, cz = base.getZ() + t.x * sin + t.z * cos, cy = base.getY() + t.y;
        boolean block = d instanceof BlockDisplay;
        if (block) { // a block display's origin is its corner: the middle is half a scale further on
            double hx = sc.x / 2, hz = sc.z / 2;
            cx += hx * cos - hz * sin; cz += hx * sin + hz * cos; cy += sc.y / 2;
        }
        double hx = Math.max(0.15, sc.x * 0.45), hy = Math.max(0.15, sc.y * 0.45), hz = Math.max(0.15, sc.z * 0.45);
        int n = (int) Math.max(1, Math.min(6, Math.round(1 + size * 1.5)));
        World w = d.getWorld();
        if (block) {
            BlockData bd = ((BlockDisplay) d).getBlock();
            for (int i = 0; i < n; i++) p.spawnParticle(Particle.BLOCK, at(w, cx, cy, cz, hx, hy, hz), 1, 0, 0, 0, 0, bd);
            return n;
        }
        ItemStack it = ((ItemDisplay) d).getItemStack();
        String model = modelName(it);
        if (model == null) { // a vanilla item (a spear, a sword, TNT...): bits of it, plus a little dust so it's seen in the dark
            for (int i = 0; i < n; i++) p.spawnParticle(Particle.ITEM, at(w, cx, cy, cz, hx, hy, hz), 1, 0, 0, 0, 0, it);
            p.spawnParticle(Particle.DUST, at(w, cx, cy, cz, hx, hy, hz), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(220, 220, 225), 1.2f));
            return n + 1;
        }
        Color c = d.isGlowing() && d.getGlowColorOverride() != null ? d.getGlowColorOverride() : colorOf(model);
        Particle.DustOptions dust = new Particle.DustOptions(c, (float) Math.min(2.5, 0.9 + size * 0.4));
        for (int i = 0; i < n; i++) p.spawnParticle(Particle.DUST, at(w, cx, cy, cz, hx, hy, hz), 1, 0, 0, 0, 0, dust);
        return n;
    }

    private Location at(World w, double x, double y, double z, double hx, double hy, double hz) {
        return new Location(w, x + (rnd.nextDouble() * 2 - 1) * hx, y + (rnd.nextDouble() * 2 - 1) * hy, z + (rnd.nextDouble() * 2 - 1) * hz);
    }

    /** A colour that reads as the model: from its name. */
    static Color colorOf(String model) {
        String m = model.toLowerCase();
        if (m.contains("pink")) return Color.fromRGB(255, 110, 200);
        if (m.contains("frost") || m.contains("ice") || m.contains("snow") || m.contains("blizzard") || m.contains("air_bubble")) return Color.fromRGB(150, 220, 255);
        if (m.contains("fire") || m.contains("flame") || m.contains("ember") || m.contains("blaze") || m.contains("magma") || m.contains("lava")) return Color.fromRGB(255, 140, 30);
        if (m.contains("blood") || m.contains("tooth") || m.contains("demon") || m.contains("red")) return Color.fromRGB(200, 20, 30);
        if (m.contains("sand") || m.contains("dune")) return Color.fromRGB(220, 190, 120);
        if (m.contains("spider") || m.contains("poison") || m.contains("venom") || m.contains("green") || m.contains("eel")) return Color.fromRGB(90, 200, 70);
        if (m.contains("void") || m.contains("shadow") || m.contains("dark") || m.contains("black")) return Color.fromRGB(70, 30, 110);
        if (m.contains("gold") || m.contains("ball") || m.contains("crown")) return Color.fromRGB(255, 215, 60);
        if (m.contains("whale") || m.contains("bubble") || m.contains("water")) return Color.fromRGB(60, 130, 230);
        return Color.fromRGB(235, 235, 240); // blades, spears, slashes: white steel
    }
}
