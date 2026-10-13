package net.faultlinesmp.items;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Camel;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MOUNT STATS (Items 1.2.26, config mount-stats:): right-click a horse, donkey, mule, skeleton/zombie horse, llama or
 * camel with a STICK to see its speed (blocks/second), jump height (blocks), health, owner, and for llamas how many
 * chest slots it carries, each with a 5-star rating against the best a vanilla horse can be. The click doesn't mount
 * or open it. Bedrock players get it as a menu.
 */
final class MountStats implements Listener {

    /** The best vanilla horse: 14.23 blocks/second, 5.29 blocks of jump. */
    static final double MAX_SPEED = 0.3375 * 42.16, MAX_JUMP = 5.29;

    private final FaultlineItems plugin;

    MountStats(FaultlineItems plugin) { this.plugin = plugin; }

    /** Blocks a horse jumps with this jump strength (the community-measured fit). */
    static double jumpHeight(double j) {
        return Math.max(0, -0.1817584952 * j * j * j + 3.689713992 * j * j + 2.128599134 * j - 0.343930367);
    }

    static double attr(LivingEntity e, Attribute a) {
        try {
            AttributeInstance i = e.getAttribute(a);
            return i == null ? 0 : i.getValue();
        } catch (RuntimeException | LinkageError ex) { return 0; }
    }

    static String stars(double value, double max) {
        int n = (int) Math.max(1, Math.min(5, Math.round(value / max * 5)));
        return ChatColor.GOLD + "★".repeat(n) + ChatColor.DARK_GRAY + "★".repeat(5 - n);
    }

    static String nice(Entity e) {
        String s = e.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    List<String> lines(LivingEntity m) {
        List<String> out = new ArrayList<>();
        out.add(ChatColor.GOLD + "" + ChatColor.BOLD + (m.getCustomName() != null ? ChatColor.stripColor(m.getCustomName()) + " (" + nice(m) + ")" : nice(m)));
        double speed = attr(m, Attribute.MOVEMENT_SPEED) * 42.16;
        if (m instanceof Camel) speed = attr(m, Attribute.MOVEMENT_SPEED) * 42.16; // camels walk; a dash is extra
        out.add(ChatColor.GRAY + "Speed: " + ChatColor.WHITE + String.format(Locale.ROOT, "%.1f", speed) + " blocks/s " + stars(speed, MAX_SPEED));
        if (m instanceof AbstractHorse h && !(m instanceof Camel)) {
            double js = attr(h, Attribute.JUMP_STRENGTH);
            if (js <= 0) try { js = h.getJumpStrength(); } catch (RuntimeException | LinkageError ignored) { }
            double jump = jumpHeight(js);
            out.add(ChatColor.GRAY + "Jump: " + ChatColor.WHITE + String.format(Locale.ROOT, "%.1f", jump) + " blocks " + stars(jump, MAX_JUMP));
        }
        double hp = attr(m, Attribute.MAX_HEALTH);
        out.add(ChatColor.GRAY + "Health: " + ChatColor.WHITE + String.format(Locale.ROOT, "%.0f", m.getHealth()) + "/" + String.format(Locale.ROOT, "%.0f", hp)
                + " (" + String.format(Locale.ROOT, "%.1f", hp / 2) + " hearts) " + stars(hp, 30));
        if (m instanceof Llama l) out.add(ChatColor.GRAY + "Strength: " + ChatColor.WHITE + l.getStrength() + " (" + l.getStrength() * 3 + " chest slots)");
        if (m instanceof Tameable t) {
            String owner = !t.isTamed() ? ChatColor.YELLOW + "wild" : t.getOwner() == null || t.getOwner().getName() == null ? "someone" : t.getOwner().getName();
            out.add(ChatColor.GRAY + "Owner: " + ChatColor.WHITE + owner);
        }
        return out;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !plugin.getConfig().getBoolean("mount-stats.enabled", true)) return;
        Player p = e.getPlayer();
        var hand = p.getInventory().getItemInMainHand();
        if (hand.getType() != Material.STICK) return;
        if (hand.hasItemMeta() && (hand.getItemMeta().hasDisplayName() || !hand.getItemMeta().getPersistentDataContainer().getKeys().isEmpty())) return; // the Shipwright's Hammer and other custom sticks
        if (!(e.getRightClicked() instanceof AbstractHorse m)) return; // horses, donkeys, mules, skeleton/zombie horses, llamas, camels
        e.setCancelled(true);
        List<String> l = lines(m);
        if (Edition.bedrock(p)) {
            String body = String.join("\n", l.subList(1, l.size())).replace(ChatColor.COLOR_CHAR, '§');
            if (BedrockForms.menu(plugin, p, ChatColor.stripColor(l.get(0)), body, List.of("OK"), i -> { }, null)) return;
        }
        for (String s : l) p.sendMessage(s);
    }
}
