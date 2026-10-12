package net.faultlinesmp.kingdoms;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * THE DISCORD SIDE (config discord:). A bot in your server, talked to over Discord's REST API (no extra plugin needed):
 *  - /kingdom link <name>: finds that member in the server (username, display name or nickname), DMs them a 6-digit code;
 *    /kingdom verify <code> links the accounts (one Discord account per Minecraft account).
 *  - Sync: a linked player's roles are read (on join, every discord.sync-minutes, /kingdoms sync): the role in
 *    discord.roles.1/2/3 = that kingdom (the lowest number if they have more than one); no role / not in the server
 *    any more = no kingdom (discord.remove-when-role-gone). Players pinned by an admin are left alone.
 * Every web call runs off the main thread; results are applied on it. A 429 (rate limit) skips that round.
 * The bot token is a secret: only in the server's config.yml.
 */
final class DiscordLink {

    record Pending(String discordId, String code, long expires) { }

    private final FaultlineKingdoms pl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final SecureRandom random = new SecureRandom();
    final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private volatile long lastWarn;

    DiscordLink(FaultlineKingdoms pl) {
        this.pl = pl;
        long every = Math.max(1, pl.getConfig().getLong("discord.sync-minutes", 5)) * 1200L;
        Bukkit.getScheduler().runTaskTimer(pl, this::syncAll, every, every);
    }

    String token() { return pl.getConfig().getString("discord.bot-token", "").trim(); }
    String guild() { return pl.getConfig().getString("discord.guild-id", "").trim(); }
    String api() { return pl.getConfig().getString("discord.api-base", "https://discord.com/api/v10"); }

    boolean on() { return !token().isEmpty() && guild().matches("\\d{5,25}"); }

    // ---------------------------------------------------------------- HTTP

    record Reply(int code, String body) { }

    Reply call(String method, String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(api() + path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bot " + token())
                .header("User-Agent", "DiscordBot (https://github.com/faultlinesmp123-sys/Faultline-SMP, 1.0)");
        if (json != null) b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        else b.method(method, HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 401 || r.statusCode() == 403) warn("Discord said " + r.statusCode() + " (check discord.bot-token, that the bot is in the server, and the Server Members Intent): " + r.body());
        return new Reply(r.statusCode(), r.body());
    }

    private void warn(String s) {
        if (System.currentTimeMillis() - lastWarn > 60000) { lastWarn = System.currentTimeMillis(); pl.getLogger().warning(s); }
    }

    /** The member's role IDs, or null if they're not in the server (404). Throws on other errors. */
    List<String> roles(String userId) throws Exception {
        Reply r = call("GET", "/guilds/" + guild() + "/members/" + userId, null);
        if (r.code() == 404) return null;
        if (r.code() != 200) throw new IllegalStateException("HTTP " + r.code());
        List<String> out = new ArrayList<>();
        JsonObject m = JsonParser.parseString(r.body()).getAsJsonObject();
        if (m.has("roles")) for (JsonElement e : m.getAsJsonArray("roles")) out.add(e.getAsString());
        return out;
    }

    /** Kingdom for a set of roles: the lowest-numbered kingdom whose role they have, 0 for none. */
    int kingdomFor(List<String> roles) {
        if (roles == null) return 0;
        for (int k = 1; k <= 3; k++) {
            String id = pl.getConfig().getString("discord.roles." + k, "").trim();
            if (!id.isEmpty() && roles.contains(id)) return k;
        }
        return 0;
    }

    // ---------------------------------------------------------------- linking

    void startLink(Player p, String query) {
        UUID id = p.getUniqueId();
        p.sendMessage(ChatColor.GRAY + "Looking for " + query + " in the Discord server...");
        Bukkit.getScheduler().runTaskAsynchronously(pl, () -> {
            String reply;
            try {
                Reply r = call("GET", "/guilds/" + guild() + "/members/search?limit=10&query=" + URLEncoder.encode(query.replace("@", ""), StandardCharsets.UTF_8), null);
                if (r.code() == 429) reply = ChatColor.RED + "Discord is busy, try again in a minute.";
                else if (r.code() != 200) reply = ChatColor.RED + "Couldn't search the Discord server (HTTP " + r.code() + "). Tell staff.";
                else {
                    JsonArray arr = JsonParser.parseString(r.body()).getAsJsonArray();
                    String q = query.replace("@", "").trim().toLowerCase();
                    JsonObject pick = null;
                    int exact = 0;
                    for (JsonElement e : arr) {
                        JsonObject m = e.getAsJsonObject(), u = m.getAsJsonObject("user");
                        if (q.equals(str(u, "username")) || q.equals(str(u, "global_name")) || q.equals(str(m, "nick"))) { pick = m; exact++; }
                    }
                    if (exact != 1) pick = exact == 0 && arr.size() == 1 ? arr.get(0).getAsJsonObject() : null;
                    if (pick == null) reply = arr.isEmpty() ? ChatColor.RED + "Nobody called \"" + query + "\" is in the Discord server. Use your Discord username (Settings > My Account)."
                            : ChatColor.RED + "More than one member matches \"" + query + "\": use your exact Discord username.";
                    else {
                        String uid = pick.getAsJsonObject("user").get("id").getAsString();
                        for (var en : pl.members.entrySet())
                            if (uid.equals(en.getValue().discordId) && !en.getKey().equals(id)) { uid = null; break; }
                        if (uid == null) reply = ChatColor.RED + "That Discord account is already linked to another player.";
                        else {
                            String code = String.format("%06d", random.nextInt(1_000_000));
                            long mins = Math.max(1, pl.getConfig().getLong("discord.link-code-minutes", 10));
                            String dm = "Your Faultline SMP link code for **" + p.getName() + "**: **" + code + "**\nType `/kingdom verify " + code + "` in game (it works for " + mins + " minutes). Didn't ask for this? Ignore it.";
                            if (dm(uid, dm)) {
                                pending.put(id, new Pending(uid, code, System.currentTimeMillis() + mins * 60000));
                                reply = ChatColor.GREEN + "The bot sent you a code in Discord DMs. Type " + ChatColor.WHITE + "/kingdom verify <code>" + ChatColor.GREEN + ".";
                            } else reply = ChatColor.RED + "Couldn't DM you: turn on \"Direct Messages\" from server members (right-click the server > Privacy Settings), then try again.";
                        }
                    }
                }
            } catch (Exception ex) {
                reply = ChatColor.RED + "Couldn't reach Discord right now. Try again later.";
                warn("Discord link failed: " + ex);
            }
            String fr = reply;
            Bukkit.getScheduler().runTask(pl, () -> { Player o = Bukkit.getPlayer(id); if (o != null) o.sendMessage(fr); });
        });
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString().toLowerCase() : null;
    }

    boolean dm(String userId, String text) throws Exception {
        Reply ch = call("POST", "/users/@me/channels", "{\"recipient_id\":\"" + userId + "\"}");
        if (ch.code() != 200) return false;
        String channel = JsonParser.parseString(ch.body()).getAsJsonObject().get("id").getAsString();
        JsonObject msg = new JsonObject();
        msg.addProperty("content", text);
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray());
        msg.add("allowed_mentions", allowed);
        return call("POST", "/channels/" + channel + "/messages", msg.toString()).code() == 200;
    }

    void verify(Player p, String code) {
        Pending pe = pending.get(p.getUniqueId());
        if (pe == null || pe.expires() < System.currentTimeMillis()) {
            pending.remove(p.getUniqueId());
            p.sendMessage(ChatColor.RED + "No code waiting (or it ran out). Start again: /kingdom link <your discord name>");
            return;
        }
        if (!pe.code().equals(code.trim())) { p.sendMessage(ChatColor.RED + "That's not the code."); return; }
        pending.remove(p.getUniqueId());
        for (var en : pl.members.entrySet())
            if (pe.discordId().equals(en.getValue().discordId) && !en.getKey().equals(p.getUniqueId())) { p.sendMessage(ChatColor.RED + "That Discord account is already linked to another player."); return; }
        pl.member(p).discordId = pe.discordId();
        pl.save();
        p.sendMessage(ChatColor.GREEN + "Discord linked! Checking your roles...");
        syncAsync(p.getUniqueId());
    }

    // ---------------------------------------------------------------- sync

    void syncAsync(UUID id) {
        if (!on()) return;
        FaultlineKingdoms.Member m = pl.members.get(id);
        if (m == null || m.discordId == null) return;
        String discordId = m.discordId;
        Bukkit.getScheduler().runTaskAsynchronously(pl, () -> {
            try { apply(id, discordId, roles(discordId)); }
            catch (Exception ex) { warn("Discord role check failed for " + m.name + ": " + ex.getMessage()); }
        });
    }

    /** Every online linked player, one call at a time, a quarter second apart (well under Discord's rate limits). */
    void syncAll() {
        if (!on()) return;
        List<UUID[]> todo = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            FaultlineKingdoms.Member m = pl.members.get(p.getUniqueId());
            if (m != null && m.discordId != null && !m.manual) { todo.add(new UUID[]{p.getUniqueId()}); ids.add(m.discordId); }
        }
        if (todo.isEmpty()) return;
        Bukkit.getScheduler().runTaskAsynchronously(pl, () -> {
            for (int i = 0; i < todo.size(); i++) {
                try { apply(todo.get(i)[0], ids.get(i), roles(ids.get(i))); Thread.sleep(250); }
                catch (InterruptedException ie) { return; }
                catch (Exception ex) { warn("Discord role sync stopped: " + ex.getMessage()); return; }
            }
        });
    }

    private void apply(UUID id, String discordId, List<String> roles) {
        int k = kingdomFor(roles);
        Bukkit.getScheduler().runTask(pl, () -> {
            FaultlineKingdoms.Member m = pl.members.get(id);
            if (m == null || m.manual || !discordId.equals(m.discordId)) return; // pinned by staff, or relinked meanwhile
            if (k == 0 && m.kingdom != 0 && !pl.getConfig().getBoolean("discord.remove-when-role-gone", true)) return;
            if (m.kingdom != k) pl.setKingdom(id, k, false, roles == null ? "not in the Discord server" : k == 0 ? "no kingdom role in Discord" : "your Discord role");
        });
    }
}
