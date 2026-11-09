package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1.3.4: carrying a DRAGON HEAD (anywhere in your inventory, hotbar, off hand or worn) gives Strength II and Speed II
 * (`dragon-head.effects`, "TYPE:level"). Topped up every second (2 s long, ambient, icon only), so it runs out within 2 s
 * of the head leaving you. Never cuts a stronger effect; a weaker longer potion comes back after (the game keeps it hidden).
 */
final class DragonHeadBuff {
    static final List<String> DEFAULT = List.of("STRENGTH:2", "SPEED:2");
    private final FaultlineItems plugin;

    DragonHeadBuff(FaultlineItems plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
    }

    record Eff(PotionEffectType type, int amp) { }

    List<Eff> effects() {
        List<String> raw = plugin.getConfig().isList("dragon-head.effects") ? plugin.getConfig().getStringList("dragon-head.effects") : DEFAULT;
        List<Eff> out = new ArrayList<>();
        for (String s : raw) {
            String[] p = s.split(":");
            String name = p[0].trim().toLowerCase(Locale.ROOT);
            if (name.equals("swiftness")) name = "speed";
            PotionEffectType t = Registry.EFFECT.get(NamespacedKey.minecraft(name));
            if (t == null) continue;
            int lvl = 1;
            try { if (p.length > 1) lvl = Math.max(1, Integer.parseInt(p[1].trim())); } catch (NumberFormatException ignored) { }
            out.add(new Eff(t, lvl - 1));
        }
        return out;
    }

    static boolean carrying(Player p) {
        PlayerInventory inv = p.getInventory();
        for (ItemStack it : inv.getContents()) if (it != null && it.getType() == Material.DRAGON_HEAD) return true; // (storage, armour, off hand)
        ItemStack off = inv.getItemInOffHand(), head = inv.getHelmet();
        return off.getType() == Material.DRAGON_HEAD || head != null && head.getType() == Material.DRAGON_HEAD;
    }

    /** One of ours: the level we give, ambient, no longer than we give it for. */
    private static boolean ours(PotionEffect e, Eff want) {
        return e != null && e.getAmplifier() == want.amp && e.isAmbient() && e.getDuration() <= 40;
    }

    void tick() {
        boolean on = plugin.getConfig().getBoolean("dragon-head.enabled", true);
        List<Eff> effs = effects();
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean has = on && p.getGameMode() != GameMode.SPECTATOR && carrying(p);
            for (Eff e : effs) {
                if (!has) continue; // (ours just runs out)
                PotionEffect cur = p.getPotionEffect(e.type);
                if (cur == null || ours(cur, e) || cur.getAmplifier() < e.amp)
                    p.addPotionEffect(new PotionEffect(e.type, 40, e.amp, true, false, true));
            }
        }
    }
}
