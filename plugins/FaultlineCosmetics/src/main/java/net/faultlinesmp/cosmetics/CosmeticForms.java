package net.faultlinesmp.cosmetics;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/**
 * /cosmetics for BEDROCK players as native menus (Floodgate/Geyser forms; config bedrock-forms) instead of the 54-slot
 * chest menu, which is cramped on a phone. Home (what you wear, one button per slot, take everything off, the Seasonal
 * Shop when one is open) -> a slot (take it off, your cosmetics for that slot, locked ones say how to get them) -> wear.
 * The shop: each cosmetic with its price -> "Buy it?". Same rules as the chest menu (FaultlineCosmetics.toggle / buy).
 */
final class CosmeticForms {
    private final FaultlineCosmetics pl;

    CosmeticForms(FaultlineCosmetics pl) { this.pl = pl; }

    boolean use(Player p) {
        return pl.getConfig().getBoolean("bedrock-forms", true) && Edition.bedrock(p) && BedrockForms.available();
    }

    /** The config's &-colour codes as Bedrock § codes. */
    static String c(String s) { return s == null ? "" : s.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1"); }

    boolean home(Player p) {
        EnumMap<FaultlineCosmetics.Slot, FaultlineCosmetics.Cosmetic> worn = pl.worn(p.getUniqueId());
        FaultlineCosmetics.Data d = pl.data(p.getUniqueId());
        StringBuilder s = new StringBuilder("§lYou own " + d.unlocked.size() + " of " + pl.cosmetics.size() + " cosmetics.§r\n\n");
        List<String> buttons = new ArrayList<>();
        List<Runnable> act = new ArrayList<>();
        for (FaultlineCosmetics.Slot slot : FaultlineCosmetics.Slot.values()) {
            FaultlineCosmetics.Cosmetic w = worn.get(slot);
            s.append("§7").append(slot.label).append(":§r ").append(w == null ? "§8nothing" : c(w.name())).append("§r\n");
            long have = pl.cosmetics.values().stream().filter(x -> x.slot() == slot && d.unlocked.contains(x.id())).count();
            buttons.add("§l" + slot.label + "§r\n§8" + (w == null ? "nothing on" : "wearing " + strip(w.name())) + " · " + have + " owned");
            act.add(() -> slot(p, slot));
        }
        if (!worn.isEmpty()) { buttons.add("§cTake everything off"); act.add(() -> { pl.takeOff(p, null); home(p); }); }
        Seasons.Season season = pl.seasons.current();
        if (season != null) { buttons.add(c(season.color()) + "§l" + strip(season.name()) + " Shop"); act.add(() -> shop(p)); }
        return BedrockForms.menu(pl, p, "Cosmetics", s.toString().trim(), buttons, i -> { if (i < act.size()) act.get(i).run(); }, null);
    }

    void slot(Player p, FaultlineCosmetics.Slot slot) {
        FaultlineCosmetics.Data d = pl.data(p.getUniqueId());
        FaultlineCosmetics.Cosmetic on = pl.worn(p.getUniqueId()).get(slot);
        List<String> buttons = new ArrayList<>();
        List<Runnable> act = new ArrayList<>();
        buttons.add("§l« Back"); act.add(() -> home(p));
        if (on != null) { buttons.add("§cTake it off"); act.add(() -> { pl.takeOff(p, slot); slot(p, slot); }); }
        List<FaultlineCosmetics.Cosmetic> locked = new ArrayList<>();
        for (FaultlineCosmetics.Cosmetic x : pl.cosmetics.values()) {
            if (x.slot() != slot) continue;
            if (!d.unlocked.contains(x.id())) { locked.add(x); continue; }
            boolean wearing = on != null && on.id().equals(x.id());
            buttons.add((wearing ? "§2✔ " : "") + c(x.name()) + (wearing ? "\n§8tap to take off" : ""));
            act.add(() -> { pl.toggle(p, x); slot(p, slot); });
        }
        for (FaultlineCosmetics.Cosmetic x : locked) {
            buttons.add("§8🔒 " + strip(x.name()));
            act.add(() -> locked(p, x, slot));
        }
        String content = "Tap one to wear it." + (locked.isEmpty() ? "" : " Locked ones show how to get them.");
        BedrockForms.menu(pl, p, slot.label, content, buttons, i -> { if (i < act.size()) act.get(i).run(); }, null);
    }

    void locked(Player p, FaultlineCosmetics.Cosmetic x, FaultlineCosmetics.Slot back) {
        String how = pl.howToGet(x);
        Seasons.Season season = pl.seasons.current();
        boolean buyable = x.season() != null && season != null && x.season().equals(season.id());
        List<String> buttons = new ArrayList<>(List.of("§l« Back"));
        if (buyable) buttons.add("Open the " + strip(season.name()) + " Shop");
        BedrockForms.menu(pl, p, strip(x.name()), "§7Locked.§r\n\n" + (how.isEmpty() ? "§7Not available right now." : "How to get it: " + c(how)),
                buttons, i -> { if (i == 1 && buyable) shop(p); else slot(p, back); }, null);
    }

    boolean shop(Player p) {
        Seasons.Season s = pl.seasons.current();
        if (s == null) { FaultlineCosmetics.msg(p, "&7The Seasonal Shop is closed right now."); return home(p); }
        FaultlineCosmetics.Data d = pl.data(p.getUniqueId());
        int have = pl.seasons.count(p, s);
        List<FaultlineCosmetics.Cosmetic> items = new ArrayList<>();
        List<String> buttons = new ArrayList<>(List.of("§l« Back"));
        for (FaultlineCosmetics.Cosmetic x : pl.cosmetics.values()) {
            if (!s.id().equals(x.season())) continue;
            items.add(x);
            boolean owned = d.unlocked.contains(x.id());
            buttons.add(owned ? "§2✔ " + strip(x.name()) + "\n§8you own this" : c(x.name()) + "\n" + (have >= x.price() ? "§2" : "§c") + x.price() + " " + strip(s.tokenName()));
        }
        String content = "§lYour " + strip(s.tokenName()) + ":§r " + have + "\n§7Hostile mobs drop them during " + strip(s.name())
                + ". The shop closes " + Seasons.when(s).split(" - ")[1] + ".";
        return BedrockForms.menu(pl, p, strip(s.name()) + " Shop", content, buttons, i -> {
            if (i == 0) { home(p); return; }
            if (i - 1 >= items.size()) return;
            FaultlineCosmetics.Cosmetic x = items.get(i - 1);
            if (d.unlocked.contains(x.id())) { shop(p); return; }
            BedrockForms.ask(pl, p, "Buy " + strip(x.name()) + "?", x.price() + " " + strip(s.tokenName()) + " (you have " + pl.seasons.count(p, s) + ").\n"
                    + "It's yours forever.", "Buy it", "Cancel", y -> {
                if (!y) { shop(p); return; }
                if (pl.buy(p, s, x) != FaultlineCosmetics.Bought.CLOSED) shop(p);
            });
        }, null);
    }

    /** A name without its colour codes (for buttons that colour it themselves). */
    static String strip(String s) { return s == null ? "" : s.replaceAll("[&§][0-9a-fk-orA-FK-OR]", ""); }
}
