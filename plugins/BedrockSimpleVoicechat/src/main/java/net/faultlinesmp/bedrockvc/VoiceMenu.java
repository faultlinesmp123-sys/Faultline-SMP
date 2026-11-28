package net.faultlinesmp.bedrockvc;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The in-game voice chat menu: the same screens as Simple Voice Chat's own (the mod's V key): the main screen (mute,
 * deafen, hide icons, group, settings), Settings (voice chat volume, microphone amplification, activation type, voice
 * activation threshold, noise suppression / echo / auto gain, quality, test microphone), Adjust volumes (every player,
 * 0-200%) and Groups (join, create with a password and a type, members, leave).
 * Java players without the mod get chest menus; Bedrock players (PC, phones, Xbox, PlayStation, Switch) get native
 * Bedrock forms: real sliders, toggles and dropdowns, controller and touch friendly.
 * Everything changes Simple Voice Chat at once and is sent to the player's page (it applies and remembers it).
 * The page itself stays the microphone + speaker: Bedrock has no way to send voice to a Java server.
 */
final class VoiceMenu implements Listener {
    private final BedrockVoice pl;
    /** What a Java player types next in chat (a group name or password), instead of chatting it. */
    private final Map<UUID, Consumer<String>> prompts = new ConcurrentHashMap<>();
    /** A Java player's group being made: name, password, type. */
    private final Map<UUID, String[]> drafts = new HashMap<>();

    VoiceMenu(BedrockVoice pl) { this.pl = pl; }

    static final String[] ACTIVATION = {"Voice activation", "Push to talk (on the page)", "Hold sneak to talk"};
    static final String[] TYPES = {"normal", "open", "isolated"};
    static final String[] TYPE_NAMES = {"Normal", "Open", "Isolated"};
    static final int[] QUALITY = {16000, 24000, 48000};
    static final String[] QUALITY_NAMES = {"Low (16 kHz, less data)", "Normal (24 kHz)", "High (48 kHz)"};

    // ------------------------------------------------------------------ state shared by both menus

    private Session session(Player p) {
        Session s = pl.sessions.get(p.getUniqueId());
        return s != null && s.live() ? s : null;
    }

    private boolean hasMod(Player p) {
        VoicechatServerApi a = pl.api();
        VoicechatConnection c = a == null ? null : a.getConnectionOf(p.getUniqueId());
        return c != null && c.isInstalled();
    }

    private Group groupOf(Session s) {
        VoicechatConnection c = s == null ? null : s.connection();
        return c == null ? null : c.getGroup();
    }

    /** Players in the same group (online). */
    private List<Player> members(Group g) {
        List<Player> l = new ArrayList<>();
        VoicechatServerApi a = pl.api();
        if (g == null || a == null) return l;
        for (Player o : Bukkit.getOnlinePlayers()) {
            VoicechatConnection c = a.getConnectionOf(o.getUniqueId());
            if (c != null && c.getGroup() != null && c.getGroup().getId().equals(g.getId())) l.add(o);
        }
        return l;
    }

    /** Everyone who can be heard (has voice: the mod, or the page), the player themself left out. */
    private List<Player> others(Player me) {
        VoicechatServerApi a = pl.api();
        List<Player> l = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) if (o != me && me.canSee(o)) l.add(o);
        l.sort(Comparator.comparing((Player o) -> !voice(a, o)).thenComparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        return l;
    }

    private boolean voice(VoicechatServerApi a, Player o) {
        VoicechatConnection c = a == null ? null : a.getConnectionOf(o.getUniqueId());
        Session s = pl.sessions.get(o.getUniqueId());
        return c != null && (c.isInstalled() && c.isConnected()) || s != null && s.attached();
    }

    private int groupMembers(Group g) { return members(g).size(); }

    private List<Group> groups() {
        VoicechatServerApi a = pl.api();
        List<Group> l = new ArrayList<>();
        if (a == null) return l;
        for (Group g : a.getGroups()) if (!g.isHidden()) l.add(g);
        l.sort(Comparator.comparing(Group::getName, String.CASE_INSENSITIVE_ORDER));
        return l;
    }

    private void setMuted(Session s, boolean on) { s.muted = on; if (on) s.micEnd(); pl.pushStatus(s); }

    private void setDeaf(Session s, boolean on) { s.setDeafened(on); pl.pushStatus(s); }

    private static int pct(float v) { return Math.round(v * 100); }

    private String statusText(Player p, Session s, boolean bedrock) {
        String g = bedrock ? "§7" : ChatColor.GRAY.toString(), w = bedrock ? "§f" : ChatColor.WHITE.toString();
        if (hasMod(p)) return g + "You have the Simple Voice Chat mod: press " + w + "V" + g + " for its own menu.";
        if (s == null) return (bedrock ? "§c" : ChatColor.RED.toString()) + "Not connected." + g + " Connect your phone (or a browser) once: it is your microphone and speaker. After that it reconnects by itself.";
        StringBuilder b = new StringBuilder();
        b.append(s.attached() ? (bedrock ? "§a" : ChatColor.GREEN.toString()) + "Connected" : (bedrock ? "§e" : ChatColor.YELLOW.toString()) + "Page open, connecting...");
        if (!s.device.isEmpty()) b.append(g).append(" (").append(s.device).append(")");
        Group gr = groupOf(s);
        b.append("\n").append(g).append("Group: ").append(w).append(gr == null ? "none" : gr.getName());
        b.append("\n").append(g).append("Talking: ").append(w).append(ACTIVATION[s.activation]);
        if (s.muted) b.append("\n").append(bedrock ? "§c" : ChatColor.RED.toString()).append("Microphone muted");
        if (s.deafened) b.append("\n").append(bedrock ? "§c" : ChatColor.RED.toString()).append("Voice chat disabled (deafened)");
        if (s.micTest) b.append("\n").append(bedrock ? "§e" : ChatColor.YELLOW.toString()).append("Testing your microphone (only you hear it)");
        return b.toString();
    }

    private void click(Player p) { p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1f); }

    // ------------------------------------------------------------------ entry points

    void open(Player p) { if (Edition.bedrock(p) && BedrockForms.available()) bMain(p); else jMain(p); }

    void settings(Player p) { if (Edition.bedrock(p) && BedrockForms.available()) bSettings(p); else jSettings(p); }

    void volumes(Player p, int page) { if (Edition.bedrock(p) && BedrockForms.available()) bVolumes(p); else jVolumes(p, page); }

    void groups(Player p) { if (Edition.bedrock(p) && BedrockForms.available()) bGroups(p); else jGroups(p); }

    private boolean needSession(Player p, Session s) {
        if (s != null) return true;
        if (hasMod(p)) { p.sendMessage(pl.msg("has-mod")); return false; }
        p.sendMessage(pl.msg("not-connected"));
        return false;
    }

    // ================================================================== BEDROCK (forms)

    private static final String T = "textures/";

    private void bMain(Player p) {
        Session s = session(p);
        List<String> b = new ArrayList<>(), img = new ArrayList<>();
        List<Runnable> act = new ArrayList<>();
        if (s == null) {
            b.add("§lConnect voice chat\n§r§8your phone = mic + speaker"); img.add(T + "items/map_filled"); act.add(() -> pl.link(p));
        } else {
            b.add(s.muted ? "§cMicrophone: MUTED\n§8tap to unmute" : "§2Microphone: ON\n§8tap to mute");
            img.add(s.muted ? T + "items/dye_powder_red" : T + "items/dye_powder_lime");
            act.add(() -> { setMuted(s, !s.muted); bMain(p); });
            b.add(s.deafened ? "§cVoice chat: DISABLED\n§8tap to hear everyone" : "§2Voice chat: ON\n§8tap to disable (deafen)");
            img.add(s.deafened ? T + "blocks/barrier" : T + "blocks/jukebox_top");
            act.add(() -> { setDeaf(s, !s.deafened); bMain(p); });
            Group g = groupOf(s);
            b.add("Group\n§8" + (g == null ? "not in a group" : g.getName() + " (" + groupMembers(g) + ")")); img.add(T + "items/name_tag"); act.add(() -> bGroups(p));
            b.add("Adjust volumes\n§8each player 0-200%"); img.add(T + "blocks/noteblock"); act.add(() -> bVolumes(p));
            b.add("Settings\n§8volume, mic, push to talk"); img.add(T + "items/comparator"); act.add(() -> bSettings(p));
            b.add(s.hideIcons ? "Show talking names" : "Hide talking names"); img.add(T + "items/ender_eye");
            act.add(() -> { s.hideIcons = !s.hideIcons; bMain(p); });
            b.add(s.micTest ? "§eStop microphone test" : "Test microphone\n§8hear yourself"); img.add(T + "items/redstone_dust");
            act.add(() -> { s.micTest = !s.micTest; pl.pushSettings(s); bMain(p); });
            b.add("Connect another device"); img.add(T + "items/map_filled"); act.add(() -> pl.link(p));
        }
        b.add("Close"); img.add(null); act.add(() -> { });
        if (!BedrockForms.menu(pl, p, "Voice Chat", statusText(p, s, true), b, img, i -> { if (i >= 0 && i < act.size()) act.get(i).run(); }, null)) jMain(p);
    }

    private void bSettings(Player p) {
        Session s = session(p);
        if (!needSession(p, s)) return;
        int q = Math.max(0, Arrays.stream(QUALITY).boxed().toList().indexOf(s.quality));
        List<BedrockForms.Part> parts = List.of(
            BedrockForms.Part.label("§7Changes go to your phone at once."),
            BedrockForms.Part.slider("Voice chat volume (%)", 0, 200, 5, pct(s.master)),
            BedrockForms.Part.slider("Microphone amplification (%)", 0, 300, 5, s.micGain),
            BedrockForms.Part.dropdown("Microphone activation", List.of(ACTIVATION), s.activation),
            BedrockForms.Part.slider("Voice activation threshold (dB)", -80, 0, 1, s.threshold),
            BedrockForms.Part.toggle("Whisper (only people close by hear you)", s.whisper),
            BedrockForms.Part.toggle("Noise suppression", s.ns),
            BedrockForms.Part.toggle("Echo cancellation", s.ec),
            BedrockForms.Part.toggle("Automatic gain", s.agc),
            BedrockForms.Part.dropdown("Audio quality", List.of(QUALITY_NAMES), q),
            BedrockForms.Part.toggle("Test microphone (hear yourself)", s.micTest));
        boolean ok = BedrockForms.custom(pl, p, "Voice Chat Settings", parts, v -> {
            s.master = clampF(v.get(1), 0, 200, pct(s.master)) / 100f;
            s.micGain = Math.round(clampF(v.get(2), 0, 300, s.micGain));
            s.activation = clampI(v.get(3), 0, 2, s.activation);
            s.threshold = Math.round(clampF(v.get(4), -80, 0, s.threshold));
            s.whisper = Boolean.TRUE.equals(v.get(5));
            s.ns = Boolean.TRUE.equals(v.get(6));
            s.ec = Boolean.TRUE.equals(v.get(7));
            s.agc = Boolean.TRUE.equals(v.get(8));
            s.quality = QUALITY[clampI(v.get(9), 0, 2, 1)];
            s.micTest = Boolean.TRUE.equals(v.get(10));
            if (s.micTest) s.micEnd();
            pl.pushSettings(s);
            p.sendMessage(ChatColor.GREEN + "[Voice] " + ChatColor.GRAY + "Settings saved.");
            bMain(p);
        }, () -> bMain(p));
        if (!ok) jSettings(p);
    }

    private void bVolumes(Player p) {
        Session s = session(p);
        if (!needSession(p, s)) return;
        List<Player> list = others(p);
        if (list.size() > 60) list = list.subList(0, 60);
        List<String> b = new ArrayList<>(), img = new ArrayList<>();
        VoicechatServerApi a = pl.api();
        for (Player o : list) {
            float v = s.vols.getOrDefault(o.getUniqueId(), 1f);
            b.add(o.getName() + "\n§8" + pct(v) + "%" + (voice(a, o) ? "" : "  (no voice)"));
            img.add("https://mc-heads.net/avatar/" + o.getUniqueId() + "/64");
        }
        b.add("Reset everyone to 100%"); img.add(null);
        b.add("Back"); img.add(null);
        List<Player> fl = list;
        BedrockForms.menu(pl, p, "Adjust Volumes", list.isEmpty() ? "§7Nobody else is online." : "§7Tap a player to change how loud they are for you.", b, img, i -> {
            if (i < fl.size()) bVolume(p, fl.get(i));
            else if (i == fl.size()) { for (Player o : fl) setVol(s, o.getUniqueId(), 1f); bVolumes(p); }
            else bMain(p);
        }, () -> bMain(p));
    }

    private void bVolume(Player p, Player o) {
        Session s = session(p);
        if (s == null) return;
        UUID id = o.getUniqueId();
        String name = o.getName();
        BedrockForms.custom(pl, p, "Volume: " + name, List.of(
            BedrockForms.Part.slider("How loud " + name + " is for you (%)", 0, 200, 5, pct(s.vols.getOrDefault(id, 1f)))),
            v -> { setVol(s, id, clampF(v.get(0), 0, 200, 100) / 100f); bVolumes(p); }, () -> bVolumes(p));
    }

    private void bGroups(Player p) {
        Session s = session(p);
        if (!needSession(p, s)) return;
        Group mine = groupOf(s);
        List<String> b = new ArrayList<>(), img = new ArrayList<>();
        List<Runnable> act = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        if (mine != null) {
            List<Player> m = members(mine);
            content.append("§7You're in §f").append(mine.getName()).append("§7 (").append(typeName(mine)).append(")\n§7Members: §f");
            for (int i = 0; i < m.size(); i++) content.append(i == 0 ? "" : "§7, §f").append(m.get(i).getName());
            for (Player o : m) if (o != p) { b.add(o.getName() + "\n§8volume " + pct(s.vols.getOrDefault(o.getUniqueId(), 1f)) + "%"); img.add("https://mc-heads.net/avatar/" + o.getUniqueId() + "/64"); act.add(() -> bVolume(p, o)); }
            b.add("§cLeave group"); img.add(T + "blocks/barrier"); act.add(() -> { say(p, pl.groupOp(s, "leave", null, null, null, null), "You left the group."); bGroups(p); });
        } else {
            List<Group> gs = groups();
            content.append(gs.isEmpty() ? "§7No groups yet. Make one!" : "§7Join a group to talk to its members wherever they are.");
            for (Group g : gs) {
                b.add((g.hasPassword() ? "§6[locked] §r" : "") + g.getName() + "\n§8" + typeName(g) + ", " + groupMembers(g) + " in it");
                img.add(T + (g.hasPassword() ? "items/iron_ingot" : "items/name_tag"));
                act.add(() -> {
                    if (!g.hasPassword()) { say(p, pl.groupOp(s, "join", g.getId().toString(), "", null, null), "You joined " + g.getName() + "."); bGroups(p); return; }
                    BedrockForms.custom(pl, p, "Join " + g.getName(), List.of(BedrockForms.Part.input("Password", "password", "")),
                        v -> { say(p, pl.groupOp(s, "join", g.getId().toString(), String.valueOf(v.get(0)), null, null), "You joined " + g.getName() + "."); bGroups(p); }, () -> bGroups(p));
                });
            }
            b.add("§2Create group"); img.add(T + "items/book_writable"); act.add(() -> bCreate(p));
        }
        b.add("Back"); img.add(null); act.add(() -> bMain(p));
        BedrockForms.menu(pl, p, "Group", content.toString(), b, img, i -> { if (i >= 0 && i < act.size()) act.get(i).run(); }, () -> bMain(p));
    }

    private void bCreate(Player p) {
        Session s = session(p);
        if (s == null) return;
        BedrockForms.custom(pl, p, "Create Group", List.of(
            BedrockForms.Part.input("Name", "My group", ""),
            BedrockForms.Part.input("Password (empty = anyone can join)", "optional", ""),
            BedrockForms.Part.dropdown("Type", List.of("Normal: only the group hears you", "Open: the group + people near you", "Isolated: only the group, nobody nearby"), 0)),
            v -> {
                String name = String.valueOf(v.get(0)).trim();
                say(p, pl.groupOp(s, "create", null, String.valueOf(v.get(1)), name, TYPES[clampI(v.get(2), 0, 2, 0)]), "Group " + name + " made.");
                bGroups(p);
            }, () -> bGroups(p));
    }

    private static String typeName(Group g) {
        return g.getType() == Group.Type.OPEN ? "open" : g.getType() == Group.Type.ISOLATED ? "isolated" : "normal";
    }

    private static float clampF(Object o, float lo, float hi, float def) {
        if (!(o instanceof Number n) || !Float.isFinite(n.floatValue())) return def;
        return Math.max(lo, Math.min(hi, n.floatValue()));
    }

    private static int clampI(Object o, int lo, int hi, int def) {
        if (!(o instanceof Number n)) return def;
        return Math.max(lo, Math.min(hi, n.intValue()));
    }

    private void say(Player p, String err, String ok) {
        p.sendMessage(err == null ? ChatColor.GREEN + "[Voice] " + ChatColor.GRAY + ok : ChatColor.RED + "[Voice] " + ChatColor.GRAY + err);
    }

    private void setVol(Session s, UUID who, float v) {
        v = Math.max(0, Math.min(2f, v));
        if (s.vols.size() < 500 || s.vols.containsKey(who)) s.vols.put(who, v);
        pl.pushVolume(s, who, v);
    }

    // ================================================================== JAVA (chest menus)

    /** Marks our menus; `page` and `ids` carry what each slot means. */
    static final class Holder implements InventoryHolder {
        final String screen; final int page; final Map<Integer, UUID> ids = new HashMap<>();
        Inventory inv;
        Holder(String screen, int page) { this.screen = screen; this.page = page; }
        @Override public Inventory getInventory() { return inv; }
    }

    private Inventory make(Holder h, int size, String title) {
        Inventory inv = Bukkit.createInventory(h, size, title);
        h.inv = inv;
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < size; i++) inv.setItem(i, pane);
        return inv;
    }

    private static ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta im = it.getItemMeta();
        if (im != null) {
            im.setDisplayName(ChatColor.RESET + name);
            if (lore.length > 0) im.setLore(Arrays.stream(lore).map(x -> ChatColor.GRAY + x).toList());
            im.addItemFlags(ItemFlag.values());
            it.setItemMeta(im);
        }
        return it;
    }

    private static ItemStack head(Player o, String name, String... lore) {
        ItemStack it = item(Material.PLAYER_HEAD, name, lore);
        if (it.getItemMeta() instanceof SkullMeta sm) { sm.setOwningPlayer(o); it.setItemMeta(sm); }
        return it;
    }

    private static String onOff(boolean on) { return on ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"; }

    void jMain(Player p) {
        Session s = session(p);
        Holder h = new Holder("main", 0);
        Inventory inv = make(h, 45, "Voice Chat");
        List<String> st = new ArrayList<>(Arrays.asList(statusText(p, s, false).split("\n")));
        inv.setItem(4, head(p, ChatColor.GOLD + "Voice Chat", st.toArray(String[]::new)));
        if (s == null) {
            inv.setItem(22, item(Material.FILLED_MAP, ChatColor.GREEN + "" + ChatColor.BOLD + "Connect voice chat",
                "Your phone (or a browser) becomes", "your microphone and speaker.", "", ChatColor.YELLOW + "Click: get your link, code and QR map"));
        } else {
            inv.setItem(19, item(s.muted ? Material.GRAY_DYE : Material.LIME_DYE, s.muted ? ChatColor.RED + "Microphone: MUTED" : ChatColor.GREEN + "Microphone: ON", "Click to " + (s.muted ? "unmute" : "mute")));
            inv.setItem(21, item(s.deafened ? Material.BARRIER : Material.JUKEBOX, s.deafened ? ChatColor.RED + "Voice chat: DISABLED" : ChatColor.GREEN + "Voice chat: ON", "Click to " + (s.deafened ? "hear everyone again" : "disable (deafen)")));
            inv.setItem(23, item(Material.ENDER_EYE, "Talking names: " + (s.hideIcons ? ChatColor.RED + "HIDDEN" : ChatColor.GREEN + "SHOWN"), "Who's talking, in your action bar"));
            inv.setItem(25, item(s.micTest ? Material.REDSTONE_TORCH : Material.REDSTONE, "Test microphone: " + onOff(s.micTest), "Hear yourself on your phone.", "Nobody else hears it while it's on."));
            Group g = groupOf(s);
            inv.setItem(29, item(Material.NAME_TAG, ChatColor.AQUA + "Group", g == null ? "Not in a group" : "In " + ChatColor.WHITE + g.getName() + ChatColor.GRAY + " (" + groupMembers(g) + ")", "", ChatColor.YELLOW + "Click: join, create or leave"));
            inv.setItem(31, item(Material.NOTE_BLOCK, ChatColor.AQUA + "Adjust volumes", "How loud each player is for you"));
            inv.setItem(33, item(Material.COMPARATOR, ChatColor.AQUA + "Settings", "Volume, microphone, push to talk,", "noise suppression, quality"));
            inv.setItem(39, item(Material.MAP, "Connect another device", "A new link, code and QR map"));
        }
        inv.setItem(41, item(Material.OAK_DOOR, ChatColor.RED + "Close"));
        p.openInventory(inv);
    }

    private static final int[] NOTCH = {1, 2, 3, 4, 5, 6, 7};

    /** A slider row: a label, 7 notches to click, fine +/- on the right. */
    private void slider(Inventory inv, int row, Material icon, String name, int value, int min, int max, String unit, int fine) {
        int base = row * 9;
        inv.setItem(base, item(icon, ChatColor.AQUA + name + ": " + ChatColor.WHITE + value + unit, "Click a bar to set it,", "or the arrow on the right: left +" + fine + ", right -" + fine));
        for (int k = 0; k < 7; k++) {
            int v = min + Math.round((max - min) * k / 6f);
            boolean lit = value >= v;
            inv.setItem(base + NOTCH[k], item(lit ? Material.LIME_STAINED_GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE, (lit ? ChatColor.GREEN : ChatColor.GRAY) + "Set to " + v + unit));
        }
        inv.setItem(base + 8, item(Material.ARROW, ChatColor.YELLOW + "Fine: " + value + unit, "Left click +" + fine, "Right click -" + fine, "Shift click: default"));
    }

    void jSettings(Player p) {
        Session s = session(p);
        if (!needSession(p, s)) return;
        Holder h = new Holder("settings", 0);
        Inventory inv = make(h, 54, "Voice Chat Settings");
        slider(inv, 0, Material.JUKEBOX, "Voice chat volume", pct(s.master), 0, 200, "%", 5);
        slider(inv, 1, Material.BELL, "Microphone amplification", s.micGain, 0, 300, "%", 5);
        slider(inv, 2, Material.SCULK_SENSOR, "Voice activation threshold", s.threshold, -80, 0, " dB", 2);
        inv.setItem(27, item(Material.LEVER, ChatColor.AQUA + "Microphone activation", ChatColor.WHITE + ACTIVATION[s.activation], "", "Click to change"));
        inv.setItem(29, item(Material.SPONGE, "Noise suppression: " + onOff(s.ns)));
        inv.setItem(30, item(Material.ECHO_SHARD, "Echo cancellation: " + onOff(s.ec)));
        inv.setItem(31, item(Material.GOLD_NUGGET, "Automatic gain: " + onOff(s.agc)));
        inv.setItem(32, item(Material.FEATHER, "Whisper: " + onOff(s.whisper), "Only people close by hear you"));
        int q = Math.max(0, Arrays.stream(QUALITY).boxed().toList().indexOf(s.quality));
        inv.setItem(33, item(Material.AMETHYST_SHARD, ChatColor.AQUA + "Audio quality", ChatColor.WHITE + QUALITY_NAMES[q], "", "Click to change (the page reconnects)"));
        inv.setItem(35, item(s.micTest ? Material.REDSTONE_TORCH : Material.REDSTONE, "Test microphone: " + onOff(s.micTest), "Hear yourself on your phone"));
        inv.setItem(45, item(Material.ARROW, ChatColor.YELLOW + "Back"));
        inv.setItem(49, item(Material.NOTE_BLOCK, ChatColor.AQUA + "Adjust volumes"));
        inv.setItem(53, item(Material.TNT, ChatColor.RED + "Unlink my devices", "Forget every phone / browser you linked.", "Shift click to do it."));
        p.openInventory(inv);
    }

    void jVolumes(Player p, int page) {
        Session s = session(p);
        if (!needSession(p, s)) return;
        List<Player> list = others(p);
        int pages = Math.max(1, (list.size() + 44) / 45);
        page = Math.max(0, Math.min(pages - 1, page));
        Holder h = new Holder("volumes", page);
        Inventory inv = make(h, 54, "Adjust Volumes" + (pages > 1 ? " (" + (page + 1) + "/" + pages + ")" : ""));
        VoicechatServerApi a = pl.api();
        for (int i = 0; i < 45 && page * 45 + i < list.size(); i++) {
            Player o = list.get(page * 45 + i);
            float v = s.vols.getOrDefault(o.getUniqueId(), 1f);
            inv.setItem(i, head(o, (voice(a, o) ? ChatColor.GREEN : ChatColor.GRAY) + o.getName(), "Volume: " + ChatColor.WHITE + pct(v) + "%" + (voice(a, o) ? "" : ChatColor.DARK_GRAY + " (no voice)"),
                "", "Left click +10%", "Right click -10%", "Shift click: back to 100%", "Q: 0% (mute them)"));
            h.ids.put(i, o.getUniqueId());
        }
        inv.setItem(45, item(Material.ARROW, ChatColor.YELLOW + "Back"));
        if (page > 0) inv.setItem(48, item(Material.SPECTRAL_ARROW, "Previous page"));
        inv.setItem(49, item(Material.MILK_BUCKET, "Reset everyone to 100%"));
        if (page < pages - 1) inv.setItem(50, item(Material.SPECTRAL_ARROW, "Next page"));
        p.openInventory(inv);
    }

    void jGroups(Player p) {
        Session s = session(p);
        if (!needSession(p, s)) return;
        Group mine = groupOf(s);
        Holder h = new Holder("groups", 0);
        Inventory inv = make(h, 54, mine == null ? "Groups" : "Group: " + mine.getName());
        if (mine != null) {
            inv.setItem(4, item(Material.NAME_TAG, ChatColor.AQUA + mine.getName(), "Type: " + typeName(mine), mine.hasPassword() ? "Has a password" : "No password"));
            int i = 9;
            for (Player o : members(mine)) {
                if (i > 44) break;
                inv.setItem(i, head(o, ChatColor.WHITE + o.getName(), o == p ? "You" : "Volume: " + pct(s.vols.getOrDefault(o.getUniqueId(), 1f)) + "%", o == p ? "" : "Click: adjust volumes"));
                h.ids.put(i++, o.getUniqueId());
            }
            inv.setItem(49, item(Material.BARRIER, ChatColor.RED + "Leave group"));
        } else {
            int i = 0;
            for (Group g : groups()) {
                if (i > 44) break;
                inv.setItem(i, item(g.hasPassword() ? Material.TRIPWIRE_HOOK : Material.NAME_TAG, ChatColor.WHITE + g.getName(),
                    "Type: " + typeName(g), groupMembers(g) + " in it", g.hasPassword() ? ChatColor.GOLD + "Password: type it in chat" : "", ChatColor.YELLOW + "Click to join"));
                h.ids.put(i++, g.getId());
            }
            if (i == 0) inv.setItem(22, item(Material.PAPER, ChatColor.GRAY + "No groups yet"));
            inv.setItem(49, item(Material.WRITABLE_BOOK, ChatColor.GREEN + "Create group"));
        }
        inv.setItem(45, item(Material.ARROW, ChatColor.YELLOW + "Back"));
        p.openInventory(inv);
    }

    void jCreate(Player p) {
        String[] d = drafts.computeIfAbsent(p.getUniqueId(), k -> new String[]{"", "", "normal"});
        Holder h = new Holder("create", 0);
        Inventory inv = make(h, 27, "Create Group");
        inv.setItem(10, item(Material.NAME_TAG, ChatColor.AQUA + "Name: " + ChatColor.WHITE + (d[0].isEmpty() ? "(click to type it)" : d[0]), "Click, then type it in chat"));
        inv.setItem(12, item(Material.TRIPWIRE_HOOK, ChatColor.AQUA + "Password: " + ChatColor.WHITE + (d[1].isEmpty() ? "none" : "*".repeat(d[1].length())), "Click, then type it in chat", "Right click: no password"));
        int t = Math.max(0, Arrays.asList(TYPES).indexOf(d[2]));
        inv.setItem(14, item(Material.COMPASS, ChatColor.AQUA + "Type: " + ChatColor.WHITE + TYPE_NAMES[t],
            "Normal: only the group hears you", "Open: the group + people near you", "Isolated: only the group, nobody nearby", "", "Click to change"));
        inv.setItem(16, item(Material.LIME_CONCRETE, ChatColor.GREEN + "" + ChatColor.BOLD + "Create"));
        inv.setItem(18, item(Material.ARROW, ChatColor.YELLOW + "Back"));
        p.openInventory(inv);
    }

    private void prompt(Player p, String what, Consumer<String> then) {
        p.closeInventory();
        p.sendMessage(ChatColor.GOLD + "[Voice] " + ChatColor.GRAY + "Type the " + what + " in chat (" + ChatColor.WHITE + "cancel" + ChatColor.GRAY + " to stop).");
        prompts.put(p.getUniqueId(), then);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        Consumer<String> c = prompts.remove(e.getPlayer().getUniqueId());
        if (c == null) return;
        e.setCancelled(true);
        String text = e.getMessage().trim();
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(pl, () -> { if (p.isOnline()) c.accept(text.equalsIgnoreCase("cancel") ? null : text); });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { prompts.remove(e.getPlayer().getUniqueId()); drafts.remove(e.getPlayer().getUniqueId()); }

    /** getHolder(false): no block-state copy for chests (not every server build has it). */
    private static InventoryHolder holder(Inventory inv) {
        try { return inv.getHolder(false); } catch (Throwable t) { return inv.getHolder(); }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (holder(e.getInventory()) instanceof Holder) e.setCancelled(true); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(holder(e.getInventory()) instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getRawSlot();
        ClickType ct = e.getClick();
        Session s = session(p);
        boolean acted = true;
        switch (h.screen) {
            case "main" -> {
                if (slot == 41) { p.closeInventory(); return; }
                if (s == null) { if (slot == 22) { p.closeInventory(); pl.link(p); return; } return; }
                switch (slot) {
                    case 19 -> setMuted(s, !s.muted);
                    case 21 -> setDeaf(s, !s.deafened);
                    case 23 -> s.hideIcons = !s.hideIcons;
                    case 25 -> { s.micTest = !s.micTest; if (s.micTest) s.micEnd(); pl.pushSettings(s); }
                    case 29 -> { click(p); jGroups(p); return; }
                    case 31 -> { click(p); jVolumes(p, 0); return; }
                    case 33 -> { click(p); jSettings(p); return; }
                    case 39 -> { p.closeInventory(); pl.link(p); return; }
                    default -> acted = false;
                }
                if (acted) { click(p); jMain(p); }
            }
            case "settings" -> {
                if (s == null) { p.closeInventory(); return; }
                int row = slot / 9, col = slot % 9;
                if (row <= 2) {
                    int min = row == 2 ? -80 : 0, max = row == 0 ? 200 : row == 1 ? 300 : 0, fine = row == 2 ? 2 : 5;
                    int cur = row == 0 ? pct(s.master) : row == 1 ? s.micGain : s.threshold;
                    int def = row == 2 ? -50 : 100;
                    int nv;
                    if (col >= 1 && col <= 7) nv = min + Math.round((max - min) * (col - 1) / 6f);
                    else if (col == 8) nv = ct.isShiftClick() ? def : ct.isRightClick() ? cur - fine : cur + fine;
                    else return;
                    nv = Math.max(min, Math.min(max, nv));
                    if (row == 0) s.master = nv / 100f; else if (row == 1) s.micGain = nv; else s.threshold = nv;
                } else switch (slot) {
                    case 27 -> s.activation = (s.activation + (ct.isRightClick() ? 2 : 1)) % 3;
                    case 29 -> s.ns = !s.ns;
                    case 30 -> s.ec = !s.ec;
                    case 31 -> s.agc = !s.agc;
                    case 32 -> s.whisper = !s.whisper;
                    case 33 -> { int q = Arrays.stream(QUALITY).boxed().toList().indexOf(s.quality); s.quality = QUALITY[(Math.max(0, q) + 1) % 3]; }
                    case 35 -> { s.micTest = !s.micTest; if (s.micTest) s.micEnd(); }
                    case 45 -> { click(p); jMain(p); return; }
                    case 49 -> { click(p); jVolumes(p, 0); return; }
                    case 53 -> { if (ct.isShiftClick()) { p.closeInventory(); p.performCommand("vc forget"); } return; }
                    default -> { return; }
                }
                pl.pushSettings(s);
                click(p);
                jSettings(p);
            }
            case "volumes" -> {
                if (s == null) { p.closeInventory(); return; }
                if (slot == 45) { click(p); jMain(p); return; }
                if (slot == 48) { jVolumes(p, h.page - 1); return; }
                if (slot == 50) { jVolumes(p, h.page + 1); return; }
                if (slot == 49) { for (UUID id : h.ids.values()) setVol(s, id, 1f); click(p); jVolumes(p, h.page); return; }
                UUID id = h.ids.get(slot);
                if (id == null) return;
                float cur = s.vols.getOrDefault(id, 1f);
                float nv = ct == ClickType.DROP || ct == ClickType.CONTROL_DROP ? 0f : ct.isShiftClick() ? 1f : ct.isRightClick() ? cur - 0.1f : cur + 0.1f;
                setVol(s, id, Math.round(nv * 100) / 100f);
                click(p);
                jVolumes(p, h.page);
            }
            case "groups" -> {
                if (s == null) { p.closeInventory(); return; }
                if (slot == 45) { click(p); jMain(p); return; }
                Group mine = groupOf(s);
                if (mine != null) {
                    if (slot == 49) { say(p, pl.groupOp(s, "leave", null, null, null, null), "You left the group."); jGroups(p); return; }
                    if (h.ids.containsKey(slot) && !h.ids.get(slot).equals(p.getUniqueId())) { jVolumes(p, 0); }
                    return;
                }
                if (slot == 49) { click(p); jCreate(p); return; }
                UUID gid = h.ids.get(slot);
                if (gid == null) return;
                VoicechatServerApi a = pl.api();
                Group g = a == null ? null : a.getGroup(gid);
                if (g == null) { jGroups(p); return; }
                if (!g.hasPassword()) { say(p, pl.groupOp(s, "join", gid.toString(), "", null, null), "You joined " + g.getName() + "."); jGroups(p); return; }
                String gname = g.getName();
                prompt(p, "password for " + gname, pw -> {
                    Session s2 = session(p);
                    if (pw == null || s2 == null) { jGroups(p); return; }
                    say(p, pl.groupOp(s2, "join", gid.toString(), pw, null, null), "You joined " + gname + ".");
                    jGroups(p);
                });
            }
            case "create" -> {
                String[] d = drafts.computeIfAbsent(p.getUniqueId(), k -> new String[]{"", "", "normal"});
                switch (slot) {
                    case 10 -> prompt(p, "group name (up to 24 letters)", t -> { if (t != null) d[0] = BedrockVoice.clean(t, 24); jCreate(p); });
                    case 12 -> { if (ct.isRightClick()) { d[1] = ""; jCreate(p); } else prompt(p, "group password", t -> { if (t != null) d[1] = t.length() > 64 ? t.substring(0, 64) : t; jCreate(p); }); }
                    case 14 -> { int t = Arrays.asList(TYPES).indexOf(d[2]); d[2] = TYPES[(Math.max(0, t) + 1) % 3]; click(p); jCreate(p); }
                    case 16 -> {
                        if (s == null) { p.closeInventory(); p.sendMessage(pl.msg("not-connected")); return; }
                        String err = pl.groupOp(s, "create", null, d[1], d[0], d[2]);
                        say(p, err, "Group " + d[0] + " made.");
                        if (err == null) drafts.remove(p.getUniqueId());
                        jGroups(p);
                    }
                    case 18 -> { click(p); jGroups(p); }
                    default -> { }
                }
            }
            default -> { }
        }
    }
}
