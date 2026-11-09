package net.faultlinesmp.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;

/**
 * The holiday events' admin command and the haunting's summon item (Items 1.2.78).
 * /holiday status | haunt [player] | haunt stop | krampus [player] | krampus kill | bell [n] [player] | lantern [n] [player]
 * (perm items.holiday, op). The CURSED JACK O'LANTERN (JACK_O_LANTERN + PDC faultlineitems:cursed_lantern, never placed,
 * /itemsmenu only) haunts the nearest village when held up (right-click), at night in the Halloween window (admins: any time).
 */
final class Holidays implements Listener, CommandExecutor, TabCompleter {

    private final FaultlineItems plugin;
    final NamespacedKey lanternKey;

    Holidays(FaultlineItems plugin) {
        this.plugin = plugin;
        lanternKey = new NamespacedKey(plugin, "cursed_lantern");
    }

    ItemStack lantern(int n) {
        ItemStack it = new ItemStack(Material.JACK_O_LANTERN, n);
        ItemMeta m = it.getItemMeta();
        m.displayName(Component.text("Cursed Jack o'Lantern", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("Hold it up (right-click) at night in October", NamedTextColor.GRAY),
                Component.text("near a village: the dead come back.", NamedTextColor.GRAY)));
        m.addEnchant(org.bukkit.enchantments.Enchantment.LURE, 1, true);
        m.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        m.getPersistentDataContainer().set(lanternKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    boolean isLantern(ItemStack it) { return it != null && it.hasItemMeta() && it.getPersistentDataContainer().has(lanternKey, PersistentDataType.BYTE); }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (isLantern(e.getItemInHand())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !isLantern(e.getItem())) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        HauntedVillage h = plugin.haunted;
        boolean admin = p.hasPermission("items.holiday");
        if (!admin && (!h.season() || !HauntedVillage.night(p.getWorld()))) { p.sendMessage(ChatColor.GOLD + "The lantern only stirs at night in the Halloween season."); return; }
        if (h.haunt != null) { p.sendMessage(ChatColor.RED + "A village is already haunted."); return; }
        if (!h.start(p.getLocation(), admin)) { p.sendMessage(ChatColor.RED + "There's no village close enough."); return; }
        if (p.getGameMode() != GameMode.CREATIVE) e.getItem().setAmount(e.getItem().getAmount() - 1);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("items.holiday")) { s.sendMessage(ChatColor.RED + "Admins only."); return true; }
        String sub = a.length == 0 ? "status" : a[0].toLowerCase(Locale.ROOT);
        String arg = a.length > 1 ? a[1] : null;
        Player self = s instanceof Player p ? p : null;
        switch (sub) {
            case "haunt" -> {
                if ("stop".equalsIgnoreCase(arg)) { plugin.haunted.stop(false); s.sendMessage(ChatColor.GREEN + "Stopped."); return true; }
                Player t = arg != null ? Bukkit.getPlayerExact(arg) : self;
                if (t == null) { s.sendMessage(ChatColor.RED + "/holiday haunt [player]"); return true; }
                s.sendMessage(plugin.haunted.start(t.getLocation(), true) ? ChatColor.GREEN + "Haunting the village near " + t.getName() + "." : ChatColor.RED + "A haunting is already on.");
            }
            case "krampus" -> {
                if ("kill".equalsIgnoreCase(arg) || "stop".equalsIgnoreCase(arg)) {
                    if (plugin.krampus.fight != null) plugin.krampus.leave(plugin.krampus.fight, false);
                    s.sendMessage(ChatColor.GREEN + "Krampus is gone."); return true;
                }
                Player t = arg != null ? Bukkit.getPlayerExact(arg) : self;
                if (t == null) { s.sendMessage(ChatColor.RED + "/holiday krampus [player]"); return true; }
                s.sendMessage(plugin.krampus.summon(t) != null ? ChatColor.GREEN + "Krampus is coming for " + t.getName() + "." : ChatColor.RED + "He's already out, or there's no room.");
            }
            case "bell", "lantern" -> {
                int n = 1;
                Player t = self;
                for (int i = 1; i < a.length; i++) { try { n = Math.max(1, Math.min(64, Integer.parseInt(a[i]))); } catch (NumberFormatException ex) { t = Bukkit.getPlayerExact(a[i]); } }
                if (t == null) { s.sendMessage(ChatColor.RED + "Who?"); return true; }
                Player to = t;
                to.getInventory().addItem(sub.equals("bell") ? plugin.krampus.bell(n) : lantern(n)).values().forEach(x -> to.getWorld().dropItem(to.getLocation(), x));
                s.sendMessage(ChatColor.GREEN + "Given.");
            }
            default -> {
                s.sendMessage(ChatColor.GOLD + "Halloween: " + ChatColor.WHITE + plugin.haunted.status());
                s.sendMessage(ChatColor.GOLD + "Christmas: " + ChatColor.WHITE + plugin.krampus.status());
                s.sendMessage(ChatColor.DARK_GRAY + "/holiday haunt [player|stop] · krampus [player|kill] · bell [n] [player] · lantern [n] [player]");
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return List.of("status", "haunt", "krampus", "bell", "lantern");
        if (a.length == 2 && a[0].equalsIgnoreCase("haunt")) return null;
        if (a.length == 2 && a[0].equalsIgnoreCase("krampus")) return null;
        return null;
    }
}
