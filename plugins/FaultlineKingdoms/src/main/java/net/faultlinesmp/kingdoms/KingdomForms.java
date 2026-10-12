package net.faultlinesmp.kingdoms;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /kingdom for BEDROCK players as native menus (Floodgate/Geyser forms; config bedrock-forms): typing a Discord name and
 * a 6-digit code on a phone or console is painful, so linking is two text boxes; friendly fire is a Yes/No vote; the
 * member lists are a button each. Every button runs the same code as the chat command.
 */
final class KingdomForms {
    private final FaultlineKingdoms pl;

    KingdomForms(FaultlineKingdoms pl) { this.pl = pl; }

    boolean use(Player p) {
        return pl.getConfig().getBoolean("bedrock-forms", true) && Edition.bedrock(p) && BedrockForms.available();
    }

    /** Strips Bukkit colours into Bedrock's § codes (same codes, so just the text). */
    private static String c(String s) { return s.replace(ChatColor.COLOR_CHAR, '§'); }

    boolean home(Player p) {
        FaultlineKingdoms.Member m = pl.member(p);
        int k = m.kingdom;
        StringBuilder s = new StringBuilder();
        s.append("§lYour kingdom:§r ").append(c(pl.label(k))).append(m.manual ? " §7(set by staff)" : "").append("\n");
        s.append("§lDiscord:§r ").append(m.discordId != null ? "§2linked" : "§cnot linked").append("§r\n");
        if (k > 0) s.append("§lFriendly fire:§r ").append(pl.friendlyFire(k) ? "§cON" : "§2OFF").append(" §7(").append(c(pl.ffWhy(k))).append(")§r\n");
        if (FaultlineKingdoms.protectedNow(p)) s.append("§2🛡 You're under a grace period.§r\n");
        List<String> buttons = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        boolean waiting = pl.discord.pending.containsKey(p.getUniqueId());
        if (pl.discord.on() && m.discordId == null) {
            buttons.add("§lLink my Discord"); actions.add(() -> link(p));
            if (waiting) { buttons.add("§lEnter my code"); actions.add(() -> code(p)); }
        }
        if (m.discordId != null) { buttons.add("Unlink my Discord"); actions.add(() -> unlink(p)); }
        if (k > 0 && pl.getConfig().getBoolean("pvp.friendly-fire-vote.enabled", true)) {
            buttons.add("Vote: friendly fire\n§8your vote: " + (m.ffVote == null ? "none" : m.ffVote ? "on" : "off")); actions.add(() -> vote(p));
        }
        for (int i = 1; i <= 3; i++) {
            int kk = i;
            int n = 0; for (FaultlineKingdoms.Member x : pl.members.values()) if (x.kingdom == kk) n++;
            buttons.add(c(pl.label(kk)) + "\n§8" + n + " members"); actions.add(() -> list(p, kk));
        }
        return BedrockForms.menu(pl, p, "Kingdoms", s.toString().trim(), buttons, i -> { if (i < actions.size()) actions.get(i).run(); }, null);
    }

    void link(Player p) {
        if (!BedrockForms.input(pl, p, "Link your Discord", "Your Discord username (Settings > My Account):", "username",
                t -> { if (!t.isEmpty()) p.performCommand("kingdom link " + t); }))
            p.sendMessage(ChatColor.GOLD + "Type " + ChatColor.WHITE + "/kingdom link <your discord name>");
    }

    void code(Player p) {
        if (!BedrockForms.input(pl, p, "Enter your code", "The 6-digit code the bot sent you in Discord DMs:", "123456",
                t -> { if (!t.isEmpty()) p.performCommand("kingdom verify " + t.replaceAll("\\s", "")); }))
            p.sendMessage(ChatColor.GOLD + "Type " + ChatColor.WHITE + "/kingdom verify <code>");
    }

    void unlink(Player p) {
        BedrockForms.ask(pl, p, "Unlink Discord?", "You'll leave your kingdom until you link again.", "Unlink", "Cancel",
                y -> { if (y) p.performCommand("kingdom unlink"); else home(p); });
    }

    void vote(Player p) {
        int k = pl.member(p).kingdom;
        if (k == 0) return;
        BedrockForms.ask(pl, p, "Friendly fire", "Should members of " + c(pl.label(k)) + "§r be able to hurt each other?\n\nNow: "
                + (pl.friendlyFire(k) ? "§cON" : "§2OFF") + "§r (" + c(pl.ffWhy(k)) + ")",
                "Friendly fire ON", "Friendly fire OFF", y -> { pl.ffCommand(p, y ? "on" : "off"); home(p); });
    }

    void list(Player p, int k) {
        List<String> on = new ArrayList<>(), off = new ArrayList<>();
        for (var e : pl.members.entrySet()) {
            if (e.getValue().kingdom != k) continue;
            UUID id = e.getKey();
            (Bukkit.getPlayer(id) != null ? on : off).add(e.getValue().name);
        }
        String s = "§2Online (" + on.size() + "):§r " + (on.isEmpty() ? "§7nobody" : String.join(", ", on))
                + "\n\n§7Offline (" + off.size() + "):§r " + (off.isEmpty() ? "§7nobody" : String.join(", ", off));
        BedrockForms.menu(pl, p, pl.name(k), s, List.of("§l« Back"), i -> home(p), null);
    }
}
