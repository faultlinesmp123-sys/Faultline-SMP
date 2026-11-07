package net.faultlinesmp.items;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * EMOTES (Items 1.2.70, config emotes:, crash data emotes.yml). /emote opens the emote menu (a Bedrock form for Bedrock
 * players), /emote <id> plays one. The player's body is played by a MANNEQUIN wearing their skin and armour (the real
 * player can't be posed by a plugin): poses (crouch, crawl, glide, lie down), arm swings, item-use arm poses (both arms
 * out = a charged crossbow, hand to the eyes = a spyglass, arm out = a brush; the items are invisible: faultline:gui/blank),
 * head pitch, hops, slides, spins and scale, all keyframed per tick, with beat-synced note-block music, particles and
 * floating text. Java players watch from a CINEMATIC CAMERA (spectating an invisible display that flies a per-emote shot:
 * orbits, push-ins, low angles; never inside a block); Bedrock players (Geyser has no spectator camera) stay put, hidden.
 * Solo: griddy, aura (Aura Farming), sturdy (Get Sturdy), birdbrain. DUO (like the paired emotes in battleground games):
 * dap (Dap Up) and hug: you WAIT with your hand out and a prompt over your head; anyone right-clicks you, clicks [JOIN] in
 * chat (/emote join <you>), picks the same duo emote near you, or answers the Bedrock form = their body snaps in front of
 * yours and you both play it in sync. Anti-glitch: any damage to the mannequin (or to the player) ends the emote AND lands
 * on the real player (same source, knockback); you're put back exactly where you stood in your old game mode; no start in
 * combat (Camera's 10 s), in the air, in water, riding, gliding, vanished, in /camera, racing, without headroom; spectator
 * hotbar teleports and clicking other entities are blocked; any other teleport ends it first; quitting, a crash or a
 * restart restores you; the mannequin's copied armour never drops (drop chance 0 + death drops cleared) and it's never saved.
 */
final class Emotes implements Listener, CommandExecutor, TabCompleter {

    static final String TAG = "faultline_emote", CAM_TAG = "faultline_emote_cam", PROP_TAG = "faultline_emote_prop";

    enum Kind {
        GRIDDY("griddy", "Griddy", Material.LEATHER_BOOTS, false, true, "Heel taps, arms pumping, then the goggles."),
        AURA("aura", "Aura Farming", Material.NETHER_STAR, false, true, "Calm. Unbothered. The aura does the talking."),
        STURDY("sturdy", "Get Sturdy", Material.ANVIL, false, true, "Low bounce, side slide, ground-shaking stomps."),
        BIRD("birdbrain", "Birdbrain", Material.FEATHER, false, true, "Head bobs, wing flaps, pecks and hops."),
        DAP("dap", "Dap Up", Material.GOLD_INGOT, true, true, "DUO: hold your hand out and wait for someone to dap you up."),
        HUG("hug", "Hug", Material.POPPY, true, true, "DUO: arms out, wait for someone to hug.");

        final String id, title, desc; final Material icon; final boolean duo, loops;
        Kind(String id, String title, Material icon, boolean duo, boolean loops, String desc) {
            this.id = id; this.title = title; this.icon = icon; this.duo = duo; this.loops = loops; this.desc = desc;
        }
        static Kind of(String s) {
            s = s.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
            for (Kind k : values()) if (k.id.equals(s)) return k;
            return switch (s) {
                case "aurafarming", "aurafarm", "farm" -> AURA;
                case "getsturdy" -> STURDY;
                case "bird", "brain" -> BIRD;
                case "dapup", "daps" -> DAP;
                case "hugs" -> HUG;
                default -> null;
            };
        }
    }

    /** One player's emote. Everything the frame wants this tick is written into the "want" fields, then applied. */
    final class Run {
        final Player p; final UUID id; Kind kind;
        Mannequin m; ItemDisplay cam; TextDisplay label;
        Location home; GameMode mode; boolean camera, hid, attached;
        Location base; float baseYaw;           // the mannequin's spot and facing
        int t; boolean waiting; Run partner; boolean leader;
        Location lastGood; float camStart; double camDist = -1;
        // wants (reset every frame)
        double fwd, side, lift, scale; float yaw, pitch; Pose pose; String hold; boolean riptide;
        // what's on it now
        Pose curPose = Pose.STANDING; String curHold = ""; boolean curRip; double curScale = 1;
        // the bendy body (1.2.72): seen by Java players with the emote pack; everyone else sees the mannequin
        EmoteRig rig; org.bukkit.entity.Interaction hit; TextDisplay tag;
        final Set<UUID> rigSees = new HashSet<>();
        int swM = -1, swO = -1;
        Run(Player p, Kind k) { this.p = p; this.id = p.getUniqueId(); this.kind = k; }
        Vector facing() { return dir(baseYaw); }
        Location at(double up) { return base.clone().add(0, up, 0); }
    }

    record Prop(Display d, Vector vel, int life, int[] age) { }

    private final FaultlineItems plugin;
    private final File file;
    final Map<UUID, Run> runs = new HashMap<>();
    private final Map<UUID, Run> byMannequin = new HashMap<>();
    private final Map<UUID, Run> byHitbox = new HashMap<>();   // the rig's Interaction (rig viewers can't see the mannequin)
    final Set<UUID> packLoaded = new HashSet<>();             // FaultlineEmotes.zip loaded in their game
    static final UUID PACK_ID = UUID.fromString("7a3e9c51-2b84-4f0d-9e6a-c1d58f2b7e40");
    static final String DEFAULT_PACK_URL = "https://raw.githubusercontent.com/faultlinesmp123-sys/Faultline-SMP/main/FaultlineEmotes.zip";
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final List<Prop> props = new ArrayList<>();
    private final Set<UUID> moving = new HashSet<>();     // our own teleports / spectate changes
    private final NamespacedKey camPref, viewPref;
    private long now;                                    // server ticks (cooldowns)

    Emotes(FaultlineItems plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "emotes.yml");
        this.camPref = new NamespacedKey(plugin, "emote_camera");
        this.viewPref = new NamespacedKey(plugin, "emote_view");
        // leftovers from before (never saved, but a crash mid-chunk-save can't hurt): one sweep at startup
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (ours(e)) e.remove();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private double cfg(String path, double def) { return plugin.getConfig().getDouble("emotes." + path, def); }
    static boolean ours(Entity e) {
        Set<String> t = e.getScoreboardTags();
        return t.contains(TAG) || t.contains(CAM_TAG) || t.contains(PROP_TAG) || t.contains(EmoteRig.TAG);
    }
    boolean emoting(Player p) { return runs.containsKey(p.getUniqueId()); }

    // ================================================================ commands + menu

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Only players can emote."); return true; }
        if (!plugin.getConfig().getBoolean("emotes.enabled", true)) { p.sendMessage(ChatColor.RED + "Emotes are turned off."); return true; }
        if (args.length == 0) { menu(p); return true; }
        String a = args[0].toLowerCase(Locale.ROOT);
        switch (a) {
            case "stop" -> { if (!stop(p, true)) p.sendMessage(ChatColor.GRAY + "You're not emoting."); return true; }
            case "list" -> {
                p.sendMessage(ChatColor.AQUA + "Emotes: " + ChatColor.WHITE + String.join(", ", ids()) + ChatColor.GRAY + "  (/emote <name>, /emote stop)");
                return true;
            }
            case "camera" -> {
                p.sendMessage(ChatColor.GRAY + "The emote camera was removed: you can walk around while you emote (F5 to watch yourself, sneak to stop).");
                return true;
            }
            case "view" -> {
                boolean classic = args.length > 1 ? args[1].equalsIgnoreCase("classic") : !viewClassic(p);
                p.getPersistentDataContainer().set(viewPref, PersistentDataType.BYTE, (byte) (classic ? 1 : 0));
                p.sendMessage(ChatColor.AQUA + "You see emotes " + (classic ? "the classic way (no bending)." : "with the bendy body (needs the emote pack)."));
                for (Run r : runs.values()) viewers(r);
                return true;
            }
            case "join" -> {
                Player host = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                Run r = host == null ? null : runs.get(host.getUniqueId());
                if (r == null || !r.waiting) { p.sendMessage(ChatColor.RED + "Nobody's waiting for you there."); return true; }
                join(r, p);
                return true;
            }
            default -> {
                Kind k = Kind.of(a);
                if (k == null || disabled(k)) { p.sendMessage(ChatColor.RED + "No emote called " + a + ". " + ChatColor.GRAY + "Try: " + String.join(", ", ids())); return true; }
                play(p, k);
                return true;
            }
        }
    }

    private List<String> ids() {
        List<String> l = new ArrayList<>();
        for (Kind k : Kind.values()) if (!disabled(k)) l.add(k.id);
        return l;
    }

    boolean disabled(Kind k) { return plugin.getConfig().getStringList("emotes.disabled").contains(k.id); }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> all = new ArrayList<>(ids());
            all.addAll(List.of("stop", "list", "camera", "view", "join"));
            for (String x : all) if (x.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(x);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("camera")) {
            out.addAll(List.of("on", "off"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("view")) {
            out.addAll(List.of("rig", "classic"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("join")) {
            for (Run r : runs.values()) if (r.waiting) out.add(r.p.getName());
        }
        return out;
    }

    /** 1.2.74: the shader body is OFF by default (it drew as loose cubes in a real 26.2 game and hid the armour).
     *  New key, so a server config still saying 1.2.72's `rig: true` doesn't turn it back on. */
    boolean bendy() { return plugin.getConfig().getBoolean("emotes.bendy-body", false); }

    boolean cameraPref(Player p) {
        if (true) return false; // 1.2.74: the emote camera was removed (the owner wants to walk around while emoting)
        Byte b = p.getPersistentDataContainer().get(camPref, PersistentDataType.BYTE);
        return b == null ? plugin.getConfig().getBoolean("emotes.camera", true) : b == 1;
    }

    static final class Menu implements InventoryHolder {
        Inventory inv;
        final Map<Integer, Kind> slots = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    static final int STOP_SLOT = 22, CAM_SLOT = 26;
    static final int[] EMOTE_SLOTS = {10, 11, 12, 13, 15, 16};

    void menu(Player p) {
        List<Kind> ks = new ArrayList<>();
        for (Kind k : Kind.values()) if (!disabled(k)) ks.add(k);
        if (Edition.bedrock(p)) {
            List<String> b = new ArrayList<>();
            for (Kind k : ks) b.add((k.duo ? "§d" : "§b") + k.title + (k.duo ? " §7(duo)" : ""));
            b.add("§cStop emoting");
            boolean sent = BedrockForms.menu(plugin, p, "Emotes", "Pick an emote. Duo emotes wait for someone to join you.", b, i -> {
                if (i >= 0 && i < ks.size()) play(p, ks.get(i));
                else if (i == ks.size()) stop(p, true);
            }, null);
            if (sent) return;
        }
        Menu h = new Menu();
        Inventory inv = Bukkit.createInventory(h, 27, ChatColor.DARK_PURPLE + "Emotes");
        h.inv = inv;
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta pm = pane.getItemMeta(); pm.setDisplayName(" "); pane.setItemMeta(pm);
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);
        for (int i = 0; i < ks.size() && i < EMOTE_SLOTS.length; i++) {
            Kind k = ks.get(i);
            ItemStack it = new ItemStack(k.icon);
            ItemMeta m = it.getItemMeta();
            m.setDisplayName((k.duo ? ChatColor.LIGHT_PURPLE : ChatColor.AQUA) + "" + ChatColor.BOLD + k.title);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + k.desc);
            if (k.duo) {
                lore.add(ChatColor.DARK_GRAY + "You wait; someone right-clicks you,");
                lore.add(ChatColor.DARK_GRAY + "clicks [JOIN], or picks it near you.");
            }
            lore.add(ChatColor.DARK_GRAY + "Loops until you stop (sneak / /emote stop).");
            lore.add(ChatColor.YELLOW + "/emote " + k.id);
            m.setLore(lore);
            m.setEnchantmentGlintOverride(emoting(p) && runs.get(p.getUniqueId()).kind == k);
            it.setItemMeta(m);
            inv.setItem(EMOTE_SLOTS[i], it);
            h.slots.put(EMOTE_SLOTS[i], k);
        }
        ItemStack stop = new ItemStack(Material.BARRIER);
        ItemMeta sm = stop.getItemMeta(); sm.setDisplayName(ChatColor.RED + "Stop emoting"); stop.setItemMeta(sm);
        inv.setItem(STOP_SLOT, stop);
        ItemStack c = new ItemStack(Material.OAK_SIGN);
        ItemMeta cm = c.getItemMeta();
        cm.setDisplayName(ChatColor.GOLD + "How it works");
        cm.setLore(List.of(ChatColor.GRAY + "Walk around while you emote.", ChatColor.GRAY + "F5 to watch yourself. Sneak to stop."));
        c.setItemMeta(cm);
        inv.setItem(CAM_SLOT, c);
        p.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getView().getTopInventory()) return;
        int s = e.getRawSlot();
        Kind k = h.slots.get(s);
        if (k != null) { p.closeInventory(); play(p, k); }
        else if (s == STOP_SLOT) { p.closeInventory(); stop(p, true); }
        else if (s == CAM_SLOT) { /* just the help sign */ }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onMenuDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    // ================================================================ starting

    /** Why this player can't start an emote right now (null = they can). */
    String refuse(Player p) {
        if (p.isDead()) return "Not while you're dead.";
        if (p.getGameMode() == GameMode.SPECTATOR) return "Not in spectator mode.";
        if (p.hasMetadata("vanished")) return "Not while vanished (it would show you).";
        if (plugin.camera != null && plugin.camera.inCamera(p)) return "Not in camera mode.";
        if (p.isInsideVehicle()) return "Get off what you're riding first.";
        if (p.isGliding() || p.isFlying() || p.isSleeping() || p.isSwimming() || p.isRiptiding()) return "Stand still on the ground first.";
        if (p.getLocation().getBlock().isLiquid() || p.getEyeLocation().getBlock().isLiquid()) return "Not in water.";
        if (plugin.camera != null) {
            String why = plugin.camera.refuse(p); // combat, races, falling
            if (why != null && !why.startsWith("You're already")) return why;
        }
        Block feet = p.getLocation().getBlock();
        if (!onGround(p)) return "Stand on the ground first.";
        if (feet.getRelative(0, 1, 0).getType().isOccluding() || feet.getType().isOccluding()) return "Not enough room here.";
        Long c = cooldown.get(p.getUniqueId());
        if (c != null && now < c) return "Slow down a second.";
        return null;
    }

    @SuppressWarnings("deprecation")
    static boolean onGround(Player p) {
        if (p.isOnGround()) return true;
        Location l = p.getLocation();
        return l.clone().subtract(0, 0.2, 0).getBlock().getType().isSolid();
    }

    void play(Player p, Kind k) {
        if (!p.hasPermission("faultline.emote")) { p.sendMessage(ChatColor.RED + "You can't use emotes."); return; }
        Run cur = runs.get(p.getUniqueId());
        if (cur != null) {
            if (cur.partner != null) { p.sendMessage(ChatColor.GRAY + "Finish this one first (/emote stop)."); return; }
            if (cur.kind == k) { stop(p, true); return; }
            if (!k.duo) {             // swap solo emotes on the spot: same mannequin, same camera
                cur.kind = k; cur.t = 0; cur.waiting = false; removeLabel(cur); return;
            }
            stop(p, false);
        }
        if (k.duo) {
            Run host = nearestWaiting(p, k);
            if (host != null) { join(host, p); return; }
        }
        String why = refuse(p);
        if (why != null) { p.sendMessage(ChatColor.RED + why); return; }
        Run r = start(p, k, p.getLocation(), p.getLocation().getYaw());
        if (r == null) return;
        if (k.duo) {
            r.waiting = true;
            label(r, ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + k.title.toUpperCase(Locale.ROOT) + "?\n" + ChatColor.GRAY + "right-click to join");
            invite(r);
            p.sendActionBar(ChatColor.LIGHT_PURPLE + "Waiting for someone to " + (k == Kind.DAP ? "dap you up" : "hug you") + "...");
        }
    }

    Run nearestWaiting(Player p, Kind k) {
        Run best = null; double bd = Double.MAX_VALUE;
        double range = cfg("duo-range", 6);
        for (Run r : runs.values()) {
            if (!r.waiting || r.kind != k || r.p.equals(p) || !r.base.getWorld().equals(p.getWorld())) continue;
            double d = r.base.distanceSquared(p.getLocation());
            if (d <= range * range && d < bd) { bd = d; best = r; }
        }
        return best;
    }

    /** Spawns the mannequin (and the camera) for a player. base = where the mannequin stands. */
    Run start(Player p, Kind k, Location base, float yaw) {
        Run r = new Run(p, k);
        r.home = p.getLocation().clone();
        r.mode = p.getGameMode();
        r.base = base.clone(); r.base.setPitch(0); r.base.setYaw(yaw); r.baseYaw = yaw;
        r.lastGood = r.base.clone();
        r.camera = cameraPref(p) && !Edition.bedrock(p) && plugin.getConfig().getBoolean("emotes.camera", true);
        Mannequin m;
        try {
            m = (Mannequin) p.getWorld().spawnEntity(r.base, EntityType.MANNEQUIN, false);
        } catch (RuntimeException | LinkageError ex) {
            plugin.getLogger().warning("[Emotes] couldn't spawn a mannequin: " + ex);
            p.sendMessage(ChatColor.RED + "Emotes aren't working right now.");
            return null;
        }
        dress(m, p);
        r.m = m;
        runs.put(r.id, r);
        byMannequin.put(m.getUniqueId(), r);
        if (bendy()) {
            try { spawnRig(r); }
            catch (RuntimeException | LinkageError ex) { plugin.getLogger().warning("[Emotes] couldn't build the emote body: " + ex + " " + where(ex)); removeRig(r); }
        }
        if (r.camera) {
            save();
            moving.add(r.id);
            try { p.setGameMode(GameMode.SPECTATOR); } finally { moving.remove(r.id); }
            r.camStart = yaw + 180 + 35;
            ItemDisplay cam = (ItemDisplay) p.getWorld().spawnEntity(shotSpot(r), EntityType.ITEM_DISPLAY, false);
            cam.addScoreboardTag(CAM_TAG);
            cam.setPersistent(false);
            q(() -> cam.setTeleportDuration(3));
            r.cam = cam;
            p.sendActionBar(ChatColor.GRAY + "Sneak to stop.");
        } else {
            r.hid = true;
            for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p)) o.hidePlayer(plugin, p);
        }
        return r;
    }

    /** The mannequin wears the player: skin, name, armour copies (never droppable). */
    void dress(Mannequin m, Player p) {
        m.addScoreboardTag(TAG);
        q(() -> m.setPersistent(false));
        q(() -> m.setGravity(false));
        q(() -> m.setSilent(true));
        q(() -> m.setImmovable(true));
        q(() -> m.setCollidable(false));
        q(() -> m.setCanPickupItems(false));
        q(() -> m.setRemoveWhenFarAway(false));
        try { m.setProfile(ResolvableProfile.resolvableProfile(p.getPlayerProfile())); } catch (RuntimeException ignored) { }
        q(() -> m.setDescription(null));
        q(() -> m.customName(Component.text(p.getName())));
        q(() -> m.setCustomNameVisible(true));
        q(() -> m.setMainHand(p.getMainHand()));
        EntityEquipment eq = m.getEquipment();
        EntityEquipment pe = p.getEquipment();
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack it = pe.getItem(s);
            eq.setItem(s, it == null ? null : it.clone());
        }
        for (EquipmentSlot s : EquipmentSlot.values()) { try { eq.setDropChance(s, 0f); } catch (RuntimeException ignored) { } }
    }

    /** Room for a body: nothing solid, no liquid. */
    static boolean open(Block b) { return !b.getType().isSolid() && !b.isLiquid(); }

    /** Runs a setter that's only nice to have (a test server or another version may not have it). */
    static void q(Runnable r) { try { r.run(); } catch (RuntimeException | LinkageError ignored) { } }

    // ================================================================ duo

    void invite(Run host) {
        String verb = host.kind == Kind.DAP ? "dap them up" : "hug them";
        Component msg = Component.text(host.p.getName() + " wants to " + (host.kind == Kind.DAP ? "dap someone up" : "hug someone") + " ", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text("[JOIN]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/emote join " + host.p.getName()))
                        .hoverEvent(HoverEvent.showText(Component.text("Click to " + verb))));
        for (Player o : host.p.getWorld().getPlayers()) {
            if (o.equals(host.p) || o.getLocation().distanceSquared(host.base) > 12 * 12) continue;
            if (Edition.bedrock(o) && o.getLocation().distanceSquared(host.base) <= cfg("duo-range", 6) * cfg("duo-range", 6) && !emoting(o)) {
                UUID hid = host.id;
                if (BedrockForms.ask(plugin, o, host.kind.title, host.p.getName() + " wants to " + (host.kind == Kind.DAP ? "dap you up" : "hug you") + ".", "Join", "No thanks", yes -> {
                    Run h = runs.get(hid);
                    if (yes && h != null && h.waiting) join(h, o);
                })) continue;
            }
            o.sendMessage(msg);
        }
    }

    /** Partner joins a waiting host: their body snaps in front of the host and both play in sync. */
    void join(Run host, Player who) {
        if (who.equals(host.p)) { who.sendMessage(ChatColor.GRAY + "You can't " + host.kind.title.toLowerCase(Locale.ROOT) + " yourself."); return; }
        if (!host.waiting || host.partner != null) { who.sendMessage(ChatColor.RED + "Too late."); return; }
        if (!who.hasPermission("faultline.emote")) return;
        double range = cfg("duo-range", 6);
        if (!who.getWorld().equals(host.base.getWorld()) || who.getLocation().distanceSquared(host.base) > range * range) {
            who.sendMessage(ChatColor.RED + "Get closer to " + host.p.getName() + "."); return;
        }
        Run mine = runs.get(who.getUniqueId());
        if (mine != null) stop(who, false);
        String why = refuse(who);
        if (why != null) { who.sendMessage(ChatColor.RED + why); return; }
        double gap = host.kind == Kind.DAP ? 1.6 : 1.7;
        Vector d = who.getLocation().toVector().subtract(host.base.toVector()).setY(0);
        if (d.lengthSquared() < 0.01) d = host.facing();
        d.normalize();
        Location spot = stand(host.base.clone().add(d.clone().multiply(gap)));
        if (spot == null) { // the host's front, then their sides
            for (Vector alt : new Vector[]{host.facing(), host.facing().rotateAroundY(Math.PI / 2), host.facing().rotateAroundY(-Math.PI / 2)}) {
                spot = stand(host.base.clone().add(alt.clone().multiply(gap)));
                if (spot != null) { d = alt.clone(); break; }
            }
        }
        if (spot == null) { who.sendMessage(ChatColor.RED + "No room here."); return; }
        float hostYaw = yawOf(d);
        float yourYaw = hostYaw + 180;
        Run r = start(who, host.kind, spot, yourYaw);
        if (r == null) return;
        host.baseYaw = hostYaw; host.base.setYaw(hostYaw);
        host.waiting = false; removeLabel(host);
        host.partner = r; r.partner = host;
        host.leader = true; r.leader = false;
        host.t = 0; r.t = 0;
        // two-shot cameras from either side of the pair
        Location mid = host.base.clone().add(r.base).multiply(0.5);
        host.camStart = hostYaw + 90; r.camStart = hostYaw - 90;
        host.camDist = r.camDist = 5.5;
        snapCam(host, mid); snapCam(r, mid);
        host.p.playSound(host.base, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.4f, 1.8f);
        who.playSound(r.base, Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.4f, 1.8f);
    }

    /** A spot a body can stand on near l (same height +-1): solid floor, two free blocks. */
    static Location stand(Location l) {
        for (int dy : new int[]{0, 1, -1}) {
            Location c = l.clone().add(0, dy, 0);
            Block feet = c.getBlock();
            if (open(feet) && open(feet.getRelative(0, 1, 0)) && feet.getRelative(0, -1, 0).getType().isSolid()) {
                Location s = c.clone(); s.setY(feet.getY() + (feet.getType().isAir() ? 0 : feet.getBoundingBox().getHeight()));
                return s;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClickMannequin(PlayerInteractEntityEvent e) {
        Run r = byMannequin.get(e.getRightClicked().getUniqueId());
        if (r == null) r = byHitbox.get(e.getRightClicked().getUniqueId());
        if (r == null) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (r.waiting && !r.p.equals(e.getPlayer())) join(r, e.getPlayer());
    }

    // ================================================================ stopping

    boolean stop(Player p, boolean say) {
        Run r = runs.get(p.getUniqueId());
        if (r == null) return false;
        end(r, say);
        return true;
    }

    void end(Run r, boolean say) {
        if (runs.get(r.id) != r) return;
        runs.remove(r.id);
        if (r.m != null) { byMannequin.remove(r.m.getUniqueId()); r.m.remove(); }
        removeRig(r);
        removeLabel(r);
        Player p = r.p;
        if (r.camera) {
            moving.add(r.id);
            try {
                if (p.isOnline()) {
                    try { if (p.getSpectatorTarget() != null) p.setSpectatorTarget(null); } catch (RuntimeException ignored) { }
                    Location back = r.home.getWorld() == null ? p.getWorld().getSpawnLocation() : r.home;
                    p.teleport(back, PlayerTeleportEvent.TeleportCause.PLUGIN);
                    if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(r.mode == GameMode.SPECTATOR ? GameMode.SURVIVAL : r.mode);
                    p.setFallDistance(0);
                    p.setVelocity(new Vector());
                }
            } finally { moving.remove(r.id); }
            save();
        }
        if (r.cam != null) r.cam.remove();
        if (r.hid) for (Player o : Bukkit.getOnlinePlayers()) if (!o.equals(p)) o.showPlayer(plugin, p);
        cooldown.put(r.id, now + (long) (cfg("cooldown-seconds", 2) * 20));
        Run partner = r.partner;
        r.partner = null;
        if (partner != null) { partner.partner = null; end(partner, false); }
        if (say && p.isOnline()) p.sendActionBar(ChatColor.GRAY + "Stopped emoting.");
    }

    void shutdown() { for (Run r : new ArrayList<>(runs.values())) end(r, false); for (Prop pr : props) pr.d().remove(); props.clear(); }

    // ================================================================ getting hit, moving, everything else that ends it

    /** Any damage to the mannequin ends the emote and lands on the real player (same source: knockback, kill credit). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onMannequinHurt(EntityDamageEvent e) {
        Run r = byMannequin.get(e.getEntity().getUniqueId());
        if (r == null) return;
        e.setCancelled(true);
        if (e.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION || e.getCause() == EntityDamageEvent.DamageCause.CRAMMING
                || e.getCause() == EntityDamageEvent.DamageCause.FALL || e.getCause() == EntityDamageEvent.DamageCause.KILL
                || e.getCause() == EntityDamageEvent.DamageCause.WORLD_BORDER) return;
        Entity by = e instanceof org.bukkit.event.entity.EntityDamageByEntityEvent x ? x.getDamager() : null;
        hitLands(r, e.getDamage(), e.getDamageSource(), by);
    }

    /** The emote ends and the hit lands on the real player (same source: knockback, kill credit); both count as a fight. */
    void hitLands(Run r, double dmg, org.bukkit.damage.DamageSource src, Entity by) {
        Player p = r.p;
        end(r, false);
        if (!p.isOnline() || p.isDead()) return;
        if (plugin.camera != null && by != null) {
            Entity d = by;
            if (d instanceof org.bukkit.entity.Projectile pr && pr.getShooter() instanceof Entity sh) d = sh;
            plugin.camera.noteFight(p.getUniqueId());
            if (d instanceof Player a) plugin.camera.noteFight(a.getUniqueId());
        }
        p.sendActionBar(ChatColor.RED + "Hit! Emote stopped.");
        if (dmg > 0) try { p.damage(dmg, src); } catch (RuntimeException ex) { p.damage(dmg); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerHurt(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && emoting(p)) { stop(p, false); p.sendActionBar(ChatColor.RED + "Hit! Emote stopped."); }
    }

    @EventHandler
    public void onMannequinDeath(EntityDeathEvent e) {
        if (!e.getEntity().getScoreboardTags().contains(TAG)) return;
        e.getDrops().clear(); e.setDroppedExp(0);
        Run r = byMannequin.get(e.getEntity().getUniqueId());
        if (r != null) end(r, false);
    }

    /** 1.2.74: solo emotes walk with you (the body follows wherever you go); duos stay on their spot. */
    boolean walks(Run r) { return !r.camera && !r.kind.duo && r.partner == null; }

    void follow(Run r) {
        Location l = r.p.getLocation();
        float yaw = l.getYaw();
        r.base = l.clone(); r.base.setPitch(0); r.base.setYaw(yaw); r.baseYaw = yaw;
        if (r.lastGood == null || !r.lastGood.getWorld().equals(l.getWorld()) || r.lastGood.distanceSquared(l) > 9) r.lastGood = r.base.clone();
    }

    /** Sneak stops an emote you're walking with. */
    @EventHandler(ignoreCancelled = true)
    public void onSneak(org.bukkit.event.player.PlayerToggleSneakEvent e) {
        Run r = runs.get(e.getPlayer().getUniqueId());
        if (r != null && e.isSneaking() && !r.camera) stop(e.getPlayer(), true);
    }

    /** First person (no camera): walking away is how you stop (duos). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Run r = runs.get(e.getPlayer().getUniqueId());
        if (r == null || r.camera || walks(r)) return;
        Location f = e.getFrom(), t = e.getTo();
        if (t != null && (Math.abs(f.getX() - t.getX()) > 0.02 || Math.abs(f.getZ() - t.getZ()) > 0.02 || t.getY() - f.getY() > 0.05)) stop(e.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUse(PlayerInteractEvent e) {
        Run r = runs.get(e.getPlayer().getUniqueId());
        if (r != null && !r.camera && e.getAction() != org.bukkit.event.block.Action.PHYSICAL) stop(e.getPlayer(), false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) { if (emoting(e.getPlayer())) stop(e.getPlayer(), false); }

    /** Spectator hotbar teleports are blocked; anything else (a /home, staff) ends the emote first, then goes ahead. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        Run r = runs.get(p.getUniqueId());
        if (r == null || moving.contains(p.getUniqueId())) return;
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE) { e.setCancelled(true); return; }
        if (!r.camera) { stop(p, false); return; }
        Location to = e.getTo();
        PlayerTeleportEvent.TeleportCause cause = e.getCause();
        e.setCancelled(true);
        stop(p, false);
        if (to != null) Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) p.teleport(to, cause); });
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpectateStart(com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent e) {
        Run r = runs.get(e.getPlayer().getUniqueId());
        if (r != null && !moving.contains(r.id) && e.getNewSpectatorTarget() != r.cam) e.setCancelled(true);
    }

    /** Sneak (= stop spectating) ends the emote. */
    @EventHandler
    public void onSpectateStop(com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent e) {
        Run r = runs.get(e.getPlayer().getUniqueId());
        if (r == null || moving.contains(r.id)) return;
        e.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> stop(e.getPlayer(), true));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMode(PlayerGameModeChangeEvent e) {
        Run r = runs.get(e.getPlayer().getUniqueId());
        if (r == null || moving.contains(r.id)) return;
        // staff changed it: end without moving them back or resetting the mode they chose
        r.camera = false;
        Bukkit.getScheduler().runTask(plugin, () -> { Run x = runs.get(e.getPlayer().getUniqueId()); if (x != null) end(x, false); });
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) { stop(e.getPlayer(), false); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        stop(e.getPlayer(), false); // back on their feet before the player file is saved
        cooldown.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        for (Run r : runs.values()) if (r.hid && !r.p.equals(p)) p.hidePlayer(plugin, r.p);
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection c = y.getConfigurationSection("runs." + p.getUniqueId());
        if (c == null) return;
        Location at = c.getLocation("at");
        GameMode mode;
        try { mode = GameMode.valueOf(c.getString("mode", "SURVIVAL")); } catch (IllegalArgumentException ex) { mode = GameMode.SURVIVAL; }
        GameMode back = mode;
        y.set("runs." + p.getUniqueId(), null);
        try { y.save(file); } catch (IOException ignored) { }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || emoting(p)) return;
            moving.add(p.getUniqueId());
            try {
                if (at != null && at.getWorld() != null) p.teleport(at, PlayerTeleportEvent.TeleportCause.PLUGIN);
                if (p.getGameMode() == GameMode.SPECTATOR) p.setGameMode(back == GameMode.SPECTATOR ? GameMode.SURVIVAL : back);
                p.setFallDistance(0);
            } finally { moving.remove(p.getUniqueId()); }
        });
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity x : e.getEntities()) if (ours(x) && !byMannequin.containsKey(x.getUniqueId())) {
            boolean live = false;
            for (Run r : runs.values()) {
                if (x.equals(r.cam) || x.equals(r.label) || x.equals(r.hit) || x.equals(r.tag)) live = true;
                if (r.rig != null) for (ItemDisplay d : r.rig.parts) if (x.equals(d)) live = true;
            }
            if (!live) x.remove();
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Run r : runs.values()) {
            if (!r.camera) continue;
            y.set("runs." + r.id + ".at", r.home);
            y.set("runs." + r.id + ".mode", r.mode.name());
        }
        try { y.save(file); } catch (IOException ex) { plugin.getLogger().warning("Couldn't save emotes.yml: " + ex.getMessage()); }
    }

    // ================================================================ the tick

    void tick() {
        now++;
        if (!props.isEmpty()) tickProps();
        if (runs.isEmpty()) return;
        double ms = cfg("max-seconds", 0);
        if (ms == 60) ms = 0;                                  // 1.2.70's default: emotes loop until you stop now
        int max = ms <= 0 ? Integer.MAX_VALUE : (int) (ms * 20);
        int wait = (int) (cfg("duo-wait-seconds", 20) * 20);
        for (Run r : new ArrayList<>(runs.values())) {
            if (runs.get(r.id) != r) continue;
            Player p = r.p;
            if (!p.isOnline() || p.isDead() || r.m == null || !r.m.isValid() || !p.getWorld().equals(r.base.getWorld())) { end(r, false); continue; }
            if (r.camera && p.getGameMode() != GameMode.SPECTATOR) { r.camera = false; end(r, false); continue; }
            if (r.waiting && r.t > wait) { end(r, false); p.sendActionBar(ChatColor.GRAY + "Nobody joined."); continue; }
            if (r.partner == null && !r.waiting && r.kind.duo) { end(r, false); continue; }
            if (r.kind.loops && r.t > max) { end(r, true); continue; }
            if (walks(r)) {
                if (p.isSwimming() || p.isGliding() || p.isInsideVehicle() || p.isFlying() || p.getLocation().getBlock().isLiquid()) { end(r, false); continue; }
                follow(r);
            }
            try {
                frame(r);
                apply(r);
                if (r.rig != null) {
                    r.rig.apply(rigPose(r), r.base, r.baseYaw);
                    if (r.tag != null) r.tag.teleport(r.base.clone().add(r.facing().multiply(r.fwd)).add(0, 2.2 + r.lift, 0));
                    if (r.t % 10 == 0) viewers(r);
                }
                if (r.swM >= 0 && ++r.swM >= 6) r.swM = -1;
                if (r.swO >= 0 && ++r.swO >= 6) r.swO = -1;
                if (r.camera) camera(r);
                if (r.label != null) r.label.teleport(r.at(2.45 + 0.06 * Math.sin(r.t * 0.15)));
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("[Emotes] " + r.kind.id + " broke at tick " + r.t + ": " + ex + " " + where(ex));
                end(r, false);
                continue;
            }
            r.t++;
        }
    }


    /** Puts the frame's wants on the mannequin. */
    void apply(Run r) {
        Mannequin m = r.m;
        Vector f = r.facing(), right = new Vector(-f.getZ(), 0, f.getX());
        Location l = r.base.clone().add(f.clone().multiply(r.fwd)).add(right.multiply(r.side)).add(0, r.lift, 0);
        // never into a wall: keep the last spot whose body fits
        Block feet = l.getBlock(), head = l.clone().add(0, 1.5 * r.scale, 0).getBlock();
        if (feet.getType().isOccluding() || head.getType().isOccluding()) l = r.lastGood.clone();
        else r.lastGood = l.clone();
        l.setYaw(r.baseYaw + r.yaw);
        l.setPitch(Math.max(-90, Math.min(90, r.pitch)));
        m.teleport(l);
        try { m.setBodyYaw(r.baseYaw + r.yaw); } catch (RuntimeException ignored) { }
        Pose want = r.pose == null ? Pose.STANDING : r.pose;
        if (want != r.curPose) { try { m.setPose(want, true); r.curPose = want; } catch (RuntimeException ex) { r.curPose = want; } }
        String hold = r.hold == null ? "" : r.hold;
        if (!hold.equals(r.curHold)) {
            try { m.clearActiveItem(); } catch (RuntimeException ignored) { }
            m.getEquipment().setItemInMainHand(hold.isEmpty() ? null : poseItem(hold));
            r.curHold = hold;
            if (!hold.isEmpty() && !hold.startsWith("crossbow")) {
                try { m.startUsingItem(EquipmentSlot.HAND); } catch (RuntimeException ignored) { }
            }
        } else if (!hold.isEmpty() && !hold.startsWith("crossbow") && !m.isHandRaised()) {
            try { m.startUsingItem(EquipmentSlot.HAND); } catch (RuntimeException ignored) { }
        }
        if (r.riptide != r.curRip || r.riptide) { try { m.setRiptiding(r.riptide); } catch (RuntimeException ignored) { } r.curRip = r.riptide; }
        if (Math.abs(r.scale - r.curScale) > 0.001) {
            AttributeInstance a = m.getAttribute(Attribute.SCALE);
            if (a != null) a.setBaseValue(r.scale);
            r.curScale = r.scale;
        }
    }

    /** Invisible items that only exist to put the arms in a pose (Bedrock shows the base item). */
    ItemStack poseItem(String what) {
        ItemStack it;
        ItemUseAnimation anim;
        switch (what) {
            case "crossbow" -> { // both arms straight out
                it = new ItemStack(Material.CROSSBOW);
                CrossbowMeta cm = (CrossbowMeta) it.getItemMeta();
                cm.addChargedProjectile(new ItemStack(Material.ARROW));
                cm.setItemModel(new NamespacedKey("faultline", "gui/blank"));
                it.setItemMeta(cm);
                return it;
            }
            case "goggles" -> { it = new ItemStack(Material.PAPER); anim = ItemUseAnimation.SPYGLASS; }
            case "handout" -> { it = new ItemStack(Material.PAPER); anim = ItemUseAnimation.BRUSH; }
            case "raise" -> { it = new ItemStack(Material.PAPER); anim = ItemUseAnimation.TRIDENT; }
            case "chest" -> { it = new ItemStack(Material.PAPER); anim = ItemUseAnimation.BLOCK; }
            case "mouth" -> { it = new ItemStack(Material.PAPER); anim = ItemUseAnimation.TOOT_HORN; }
            default -> { it = new ItemStack(Material.PAPER); anim = ItemUseAnimation.BOW; }
        }
        ItemMeta im = it.getItemMeta();
        im.setItemModel(new NamespacedKey("faultline", "gui/blank"));
        it.setItemMeta(im);
        it.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable().consumeSeconds(3600).animation(anim)
                .sound(Key.key("minecraft:intentionally_empty")).hasConsumeParticles(false).build());
        return it;
    }

    // ================================================================ the emotes (keyframes)

    void frame(Run r) {
        r.fwd = 0; r.side = 0; r.lift = 0; r.yaw = 0; r.pitch = 0; r.pose = Pose.STANDING; r.hold = null; r.riptide = false; r.scale = 1;
        if (r.waiting) { waitLoop(r); return; }
        switch (r.kind) {
            case GRIDDY -> griddy(r);
            case AURA -> aura(r);
            case STURDY -> sturdy(r);
            case BIRD -> bird(r);
            case DAP -> dap(r);
            case HUG -> hug(r);
        }
    }

    /** Duo hosts: hand (or arms) out, looking around, the prompt bobbing overhead. */
    void waitLoop(Run r) {
        int t = r.t;
        r.hold = r.kind == Kind.DAP ? "handout" : "crossbow";
        r.yaw = (float) (Math.sin(t * 0.05) * 25);
        r.pitch = (float) (Math.sin(t * 0.11) * 6);
        if (t % 40 == 0) sound(r, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.35f, r.kind == Kind.DAP ? 1.4f : 1.8f);
        if (r.kind == Kind.HUG && t % 10 == 0) particle(r, Particle.HEART, 0, 2.2, 0, 1, 0.2);
        if (t % 4 == 0) dust(r, 0, 2.1, 0, 2, 0.3, Color.fromRGB(230, 120, 255), 0.8f);
    }

    /** Griddy: 16-step loop (a step = 4 ticks): heel taps, arm pumps, a forward strut, the goggles on the last 4. */
    /** Griddy timing (both bodies): a tap every GRIDDY_STEP ticks (~100 BPM in eighths), 16 to a bar: 12 traveling heel
     *  taps, then 4 steps of goggles while it skips back to where it started (so the loop lines up). */
    static final int GRIDDY_STEP = 6, GRIDDY_BAR = GRIDDY_STEP * 16, GRIDDY_TAPS = GRIDDY_STEP * 12;

    static double griddyTravel(int t) {
        int b = t % GRIDDY_BAR;
        return b < GRIDDY_TAPS ? 0.85 * ((double) b / GRIDDY_TAPS) : 0.85 * (1 - sm((b - GRIDDY_TAPS) / (double) (GRIDDY_BAR - GRIDDY_TAPS)));
    }

    void griddy(Run r) {
        int t = r.t, step = t / GRIDDY_STEP, s = step % 16, sub = t % GRIDDY_STEP;
        boolean goggles = s >= 12;
        r.fwd = griddyTravel(t);
        if (goggles) {
            r.hold = "goggles";
            r.pitch = -6;
            r.lift = 0.06 * Math.abs(Math.sin(Math.PI * t / GRIDDY_STEP));
            if (s == 12 && sub == 0) { sound(r, Sound.ITEM_SPYGLASS_USE, 0.8f, 1.2f); floatText(r, ChatColor.YELLOW + "" + ChatColor.BOLD + "GRIDDY", 2.3); }
        } else {
            r.pitch = 20; // eyes on the feet
            r.yaw = (float) ((s % 2 == 0 ? 1 : -1) * 10);
            r.pose = sub < 2 ? Pose.SNEAKING : Pose.STANDING; // the heel tap
            r.lift = sub == 3 ? 0.07 : 0;
            if (sub == 0) swing(r, s % 2 == 1);
            if (sub == 0) groundPuff(r, 4);
        }
        if (sub == 0) {
            beat(r, s, "x...x...x...x...", "..x...x...x.x.x.", "xxxxxxxxxxxxxxxx", new int[]{6, -1, 6, -1, 9, -1, 6, -1, 11, -1, 9, -1, 6, 8, 9, -1});
            dust(r, 0, 0.1, 0, 6, 0.4, Color.fromRGB(80, 220, 255), 1.0f);
        }
    }

    /** Aura Farming: unbothered. Slow head turn to the horizon, a lazy hand flow, swirling aura, deep hum. */
    void aura(Run r) {
        int t = r.t;
        double look = Math.sin(t * 0.02);
        r.yaw = (float) (look * 55);
        r.pitch = (float) (-6 + Math.sin(t * 0.031) * 4);
        r.lift = 0.04 * Math.sin(t * 0.05);
        if (t % 50 == 10) swing(r, false);
        if (t % 50 == 35) swing(r, true);
        if (t % 120 > 90 && t % 120 < 110) r.hold = "chest"; // arm across the chest for a moment
        // the aura: two rising spirals + sparks + a slow ring at the feet
        for (int i = 0; i < 2; i++) {
            double a = t * 0.22 + i * Math.PI;
            double h = (t * 0.06 + i * 1.1) % 2.4;
            double rad = 0.75 - h * 0.12;
            dustT(r, Math.cos(a) * rad, h, Math.sin(a) * rad, Color.fromRGB(255, 255, 255), Color.fromRGB(150, 70, 255), 1.1f);
        }
        if (t % 3 == 0) particle(r, Particle.END_ROD, 0, 1.2, 0, 1, 0.5);
        if (t % 8 == 0) for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6 + t * 0.03;
            dust(r, Math.cos(a) * 1.2, 0.05, Math.sin(a) * 1.2, 1, 0, Color.fromRGB(120, 60, 220), 0.9f);
        }
        if (t % 5 == 0) wind(r);
        if (t % 80 == 0) sound(r, Sound.BLOCK_BEACON_AMBIENT, 0.8f, 0.6f);
        if (t % 160 == 40) sound(r, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.7f, 0.5f);
        if (t == 30) floatText(r, ChatColor.LIGHT_PURPLE + "" + ChatColor.ITALIC + "+aura", 2.4);
        if (t > 30 && t % 200 == 30) floatText(r, ChatColor.LIGHT_PURPLE + "" + ChatColor.ITALIC + "+" + (1000 * (t / 200 + 1)) + " aura", 2.4);
    }

    /** Get Sturdy: low bounce on every step, sliding side to side, a stomp every 8 steps that shakes the ground. */
    void sturdy(Run r) {
        int t = r.t, step = t / 5, s = step % 16, sub = t % 5;
        boolean stomp = s == 7 || s == 15;
        r.pitch = 12;
        r.side = 0.35 * Math.sin(step * Math.PI / 4 + sub * Math.PI / 20);
        r.yaw = (float) (Math.sin(step * Math.PI / 4) * 18);
        if (stomp) {
            double k = sub / 4.0;
            r.lift = 0.55 * Math.sin(k * Math.PI);
            r.pose = sub >= 3 ? Pose.SNEAKING : Pose.STANDING;
            if (sub == 4) {
                sound(r, Sound.ENTITY_GENERIC_EXPLODE, 0.25f, 1.6f);
                sound(r, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1f, 0.5f);
                groundPuff(r, 30);
                particle(r, Particle.POOF, 0, 0.1, 0, 10, 0.6);
                shake(r);
            }
        } else {
            r.pose = sub < 2 ? Pose.SNEAKING : Pose.STANDING;
            r.lift = sub == 3 ? 0.18 : sub == 4 ? 0.08 : 0;
            if (sub == 0) { swing(r, true); swing(r, false); }
            if (sub == 1) groundPuff(r, 3);
        }
        if (sub == 0) beat(r, s, "x..x..x.x..x..x.", "....x.......x...", "x.x.x.x.x.x.x.x.", new int[]{0, -1, 0, 3, -1, 0, 5, -1, 0, -1, 0, 3, -1, 7, 5, 3});
    }

    /** Birdbrain: pecking head bobs, flapping arms, little hops with a glide, feathers everywhere. */
    void bird(Run r) {
        int t = r.t, cyc = t % 48;
        r.pitch = (t / 3) % 2 == 0 ? 28 : -8;                 // the bob
        r.yaw = (float) (((t / 9) % 3 - 1) * 35);             // sharp bird glances
        if (t % 2 == 0) swing(r, (t / 2) % 2 == 0);           // flapping
        if (cyc >= 16 && cyc < 24) { r.pose = Pose.SNEAKING; r.pitch = 55; if (cyc % 4 == 0) sound(r, Sound.BLOCK_WOOD_HIT, 0.4f, 1.9f); } // peck peck
        if (cyc >= 34 && cyc < 44) {                           // hop + glide
            double k = (cyc - 34) / 10.0;
            r.lift = 0.7 * Math.sin(k * Math.PI);
            if (cyc >= 36 && cyc < 42) r.pose = Pose.FALL_FLYING;
            r.fwd = 0.25 * Math.sin(k * Math.PI);
            if (cyc == 34) sound(r, Sound.ENTITY_PARROT_FLY, 0.8f, 1.2f);
        }
        if (t % 6 == 0) feathers(r, 2);
        if (t % 22 == 0) sound(r, Sound.ENTITY_CHICKEN_AMBIENT, 0.7f, 1.0f + (float) Math.random() * 0.4f);
        if (t % 70 == 35) sound(r, Sound.ENTITY_PARROT_AMBIENT, 0.6f, 1.3f);
        if (t == 20) floatText(r, ChatColor.YELLOW + "" + ChatColor.BOLD + "BAWK", 2.3);
    }

    /** Dap Up (duo): step in, slap, grip, pull in for the half hug with two pats, step back, nod, fist bump. */
    void dap(Run r) {
        int t = r.t % 72;                                      // loops: dap, dap, dap...
        double inK = t < 8 ? ease(t / 8.0) : t < 34 ? 1 : t < 42 ? 1 - ease((t - 34) / 8.0) * 0.6 : 0.4;
        r.fwd = 0.25 * inK + (t >= 18 && t < 32 ? 0.25 * Math.sin((t - 18) / 14.0 * Math.PI) : 0);
        r.pitch = 5;
        if (t == 8) { swing(r, true); if (r.leader) { dapHit(r, 1.35, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.5f); } }
        if (t == 13) { swing(r, true); if (r.leader) dapHit(r, 1.25, Sound.BLOCK_WOOD_HIT, 1.3f); }
        if (t >= 18 && t < 32) {
            r.hold = "crossbow"; // pulled in
            r.yaw = r.leader ? -12 : 12;
            if (t == 22 || t == 27) { swing(r, false); sound(r, Sound.ENTITY_PLAYER_ATTACK_WEAK, 0.7f, 1.2f); }
        }
        if (t >= 38 && t < 46) { r.pose = t < 42 ? Pose.SNEAKING : Pose.STANDING; r.pitch = 25; }
        if (t >= 50 && t < 56) r.hold = "handout";
        if (t == 54) {
            swing(r, true);
            if (r.leader) {
                dapHit(r, 1.25, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.2f);
                Location mid = mid(r, 1.3);
                mid.getWorld().spawnParticle(Particle.FIREWORK, mid, 25, 0.1, 0.1, 0.1, 0.12);
                floatAt(mid.clone().add(0, 1.0, 0), ChatColor.GOLD + "" + ChatColor.BOLD + "+RESPECT");
            }
        }
        if (t > 58) r.pitch = -5;
    }

    /** Hug (duo): close in, arms around, a sway and back pats, hearts, then let go. */
    void hug(Run r) {
        int t = r.t < 74 ? r.t : 14 + (r.t - 14) % 60;         // loops the embrace until someone stops
        double close = t < 14 ? ease(t / 14.0) : t < 74 ? 1 : 1 - ease(Math.min(1, (t - 74) / 14.0));
        r.fwd = 0.55 * close;
        r.pitch = 10;
        if (t >= 4 && t < 80) r.hold = "crossbow";
        if (t >= 14 && t < 74) {
            double sway = Math.sin((t - 14) * 0.18);
            r.yaw = (float) (sway * 10);
            r.side = 0.06 * sway * (r.leader ? 1 : -1);
            if (!r.leader) r.pose = (t / 20) % 2 == 0 ? Pose.SNEAKING : Pose.STANDING; // one leans in on the other
            if (t % 12 == 6) { swing(r, false); sound(r, Sound.ENTITY_PLAYER_ATTACK_NODAMAGE, 0.5f, 1.4f); }
            if (r.leader && t % 6 == 0) {
                Location mid = mid(r, 2.0);
                mid.getWorld().spawnParticle(Particle.HEART, mid, 1, 0.35, 0.15, 0.35, 0);
            }
        }
        if (r.leader && t == 16) { sound(r, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.2f); floatAt(mid(r, 2.6), ChatColor.RED + "<3"); }
        if (r.leader && t == 72) sound(r, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f);
    }

    // ================================================================ effects

    static double ease(double k) { k = Math.max(0, Math.min(1, k)); return k * k * (3 - 2 * k); }

    void swing(Run r, boolean main) {
        if (main) r.swM = 0; else r.swO = 0;
        try { if (main) r.m.swingMainHand(); else r.m.swingOffHand(); } catch (RuntimeException ignored) { }
    }

    Location spot(Run r, double x, double y, double z) {
        Location l = r.m.getLocation();
        Vector f = dir(l.getYaw()), right = new Vector(-f.getZ(), 0, f.getX());
        return l.clone().add(f.multiply(z)).add(right.multiply(x)).add(0, y, 0);
    }

    void particle(Run r, Particle p, double x, double y, double z, int n, double spread) {
        Location l = spot(r, x, y, z);
        l.getWorld().spawnParticle(p, l, n, spread, spread, spread, 0.01);
    }

    void dust(Run r, double x, double y, double z, int n, double spread, Color c, float size) {
        Location l = spot(r, x, y, z);
        l.getWorld().spawnParticle(Particle.DUST, l, n, spread, spread, spread, 0, new Particle.DustOptions(c, size));
    }

    void dustT(Run r, double x, double y, double z, Color a, Color b, float size) {
        Location l = r.m.getLocation().add(x, y, z);
        l.getWorld().spawnParticle(Particle.DUST_COLOR_TRANSITION, l, 1, 0, 0, 0, 0, new Particle.DustTransition(a, b, size));
    }

    void wind(Run r) {
        Vector f = dir(r.baseYaw + 90);
        Location l = r.m.getLocation().add(f.clone().multiply(-2)).add(0, 0.5 + Math.random() * 1.6, 0);
        l.getWorld().spawnParticle(Particle.CLOUD, l, 0, f.getX(), 0.02, f.getZ(), 0.25);
    }

    void groundPuff(Run r, int n) {
        Location l = r.m.getLocation();
        Block under = l.clone().subtract(0, 0.2, 0).getBlock();
        if (!under.getType().isSolid()) return;
        l.getWorld().spawnParticle(Particle.BLOCK, l.clone().add(0, 0.1, 0), n, 0.35, 0.05, 0.35, 0, under.getBlockData());
    }

    void feathers(Run r, int n) {
        Location l = r.m.getLocation().add(0, 1.2, 0);
        l.getWorld().spawnParticle(Particle.ITEM, l, n, 0.3, 0.3, 0.3, 0.05, new ItemStack(Material.FEATHER));
    }

    /** A little camera shake for anyone watching through the emote camera nearby (Java), plus a thud. */
    void shake(Run r) {
        for (Run o : runs.values()) if (o.camera && o.cam != null && o.base.getWorld().equals(r.base.getWorld()) && o.base.distanceSquared(r.base) < 100) {
            Location c = o.cam.getLocation().add((Math.random() - 0.5) * 0.25, (Math.random() - 0.5) * 0.2, 0);
            o.cam.teleport(c);
        }
    }

    void sound(Run r, Sound s, float vol, float pitch) {
        r.m.getWorld().playSound(r.m.getLocation(), s, SoundCategory.PLAYERS, vol, pitch);
    }

    /** One 16-step bar of drums (kick, snare, hats) + a bass line (semitones from F#, -1 = rest). */
    void beat(Run r, int s, String kick, String snare, String hat, int[] bass) {
        if (kick.charAt(s) == 'x') sound(r, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.9f, 0.8f);
        if (snare.charAt(s) == 'x') sound(r, Sound.BLOCK_NOTE_BLOCK_SNARE, 0.7f, 1.0f);
        if (hat.charAt(s) == 'x') sound(r, Sound.BLOCK_NOTE_BLOCK_HAT, 0.35f, 1.6f);
        if (bass[s] >= 0) sound(r, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, (float) Math.pow(2, (bass[s] - 12) / 12.0));
        if (s % 4 == 0) particle(r, Particle.NOTE, 0, 2.3, 0, 1, 0.3);
    }

    Location mid(Run r, double y) {
        Run o = r.partner;
        Location a = r.m.getLocation();
        if (o == null || o.m == null) return a.add(0, y, 0);
        return a.add(o.m.getLocation()).multiply(0.5).add(0, y, 0);
    }

    void dapHit(Run r, double y, Sound s, float pitch) {
        Location mid = mid(r, y);
        mid.getWorld().spawnParticle(Particle.CRIT, mid, 12, 0.1, 0.1, 0.1, 0.25);
        mid.getWorld().playSound(mid, s, SoundCategory.PLAYERS, 1f, pitch);
    }

    void floatText(Run r, String text, double y) { floatAt(r.m.getLocation().add(0, y, 0), text); }

    @SuppressWarnings("deprecation")
    void floatAt(Location l, String text) {
        TextDisplay d = (TextDisplay) l.getWorld().spawnEntity(l, EntityType.TEXT_DISPLAY, false);
        d.addScoreboardTag(PROP_TAG);
        d.setPersistent(false);
        q(() -> d.setText(text));
        q(() -> d.setBillboard(Display.Billboard.CENTER));
        q(() -> d.setShadowed(true));
        q(() -> d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0)));
        q(() -> d.setTeleportDuration(2));
        props.add(new Prop(d, new Vector(0, 0.03, 0), 40, new int[]{0}));
    }

    @SuppressWarnings("deprecation")
    void label(Run r, String text) {
        TextDisplay d = (TextDisplay) r.base.getWorld().spawnEntity(r.at(2.45), EntityType.TEXT_DISPLAY, false);
        d.addScoreboardTag(PROP_TAG);
        d.setPersistent(false);
        q(() -> d.setText(text));
        q(() -> d.setBillboard(Display.Billboard.CENTER));
        q(() -> d.setAlignment(TextDisplay.TextAlignment.CENTER));
        q(() -> d.setTeleportDuration(3));
        r.label = d;
    }

    void removeLabel(Run r) { if (r.label != null) { r.label.remove(); r.label = null; } }

    void tickProps() {
        for (Iterator<Prop> it = props.iterator(); it.hasNext(); ) {
            Prop p = it.next();
            int age = ++p.age()[0];
            if (!p.d().isValid() || age > p.life()) { p.d().remove(); it.remove(); continue; }
            if (age % 2 == 0) p.d().teleport(p.d().getLocation().add(p.vel().clone().multiply(2)));
            if (age == p.life() - 8 && p.d() instanceof TextDisplay td) td.setTextOpacity((byte) 60);
        }
    }

    // ================================================================ the bendy body (1.2.72)

    @SuppressWarnings("deprecation")
    void spawnRig(Run r) {
        Player p = r.p;
        r.rig = new EmoteRig(p, r.base, EmoteRig.slim(p));
        org.bukkit.entity.Interaction hit = (org.bukkit.entity.Interaction) r.base.getWorld().spawnEntity(r.base, EntityType.INTERACTION, false);
        hit.addScoreboardTag(PROP_TAG);
        q(() -> hit.setPersistent(false));
        q(() -> hit.setVisibleByDefault(false));
        q(() -> hit.setInteractionWidth(0.7f));
        q(() -> hit.setInteractionHeight(1.9f));
        q(() -> hit.setResponsive(true));
        r.hit = hit;
        byHitbox.put(hit.getUniqueId(), r);
        TextDisplay tag = (TextDisplay) r.base.getWorld().spawnEntity(r.base.clone().add(0, 2.2, 0), EntityType.TEXT_DISPLAY, false);
        tag.addScoreboardTag(PROP_TAG);
        q(() -> tag.setPersistent(false));
        q(() -> tag.setVisibleByDefault(false));
        q(() -> tag.setText(p.getName()));
        q(() -> tag.setBillboard(Display.Billboard.CENTER));
        q(() -> tag.setTeleportDuration(2));
        r.tag = tag;
        r.rig.apply(rigPose(r), r.base, r.baseYaw);
        // invisible by default; also hidden by hand from everyone here now (belt and braces)
        for (Player v : r.base.getWorld().getPlayers()) {
            for (ItemDisplay d : r.rig.parts) v.hideEntity(plugin, d);
            v.hideEntity(plugin, hit); v.hideEntity(plugin, tag);
        }
        viewers(r);
    }

    void removeRig(Run r) {
        for (UUID v : r.rigSees) { Player o = Bukkit.getPlayer(v); if (o != null && r.m != null) o.showEntity(plugin, r.m); }
        r.rigSees.clear();
        if (r.rig != null) { r.rig.remove(); r.rig = null; }
        if (r.hit != null) { byHitbox.remove(r.hit.getUniqueId()); r.hit.remove(); r.hit = null; }
        if (r.tag != null) { r.tag.remove(); r.tag = null; }
    }

    boolean viewClassic(Player p) {
        Byte b = p.getPersistentDataContainer().get(viewPref, PersistentDataType.BYTE);
        return b != null && b == 1;
    }

    /** Sees the bendy body: Java, the emote pack loaded, a game version the shader was made for, and not opted out. */
    boolean rigViewer(Player v) {
        if (!bendy() || Edition.bedrock(v) || viewClassic(v)) return false;
        if (!packLoaded.contains(v.getUniqueId())) return false;
        return protocolOk(v);
    }

    boolean protocolOk(Player v) {
        int proto = EmoteRig.protocol(v);
        if (proto < 0) return true;
        List<Integer> ok = plugin.getConfig().getIntegerList("emotes.rig-protocols");
        return (ok.isEmpty() ? List.of(776) : ok).contains(proto);
    }

    /** Who sees the rig and who sees the mannequin: rechecked every 10 ticks (new arrivals, packs that just loaded). */
    void viewers(Run r) {
        if (r.rig == null) return;
        World w = r.base.getWorld();
        for (Player v : w.getPlayers()) {
            boolean near = v.getLocation().distanceSquared(r.base) < 160 * 160;
            boolean want = near && rigViewer(v);
            boolean has = r.rigSees.contains(v.getUniqueId());
            if (want == has) continue;
            if (want) {
                for (ItemDisplay d : r.rig.parts) v.showEntity(plugin, d);
                if (r.hit != null) v.showEntity(plugin, r.hit);
                if (r.tag != null) v.showEntity(plugin, r.tag);
                v.hideEntity(plugin, r.m);
                r.rigSees.add(v.getUniqueId());
            } else {
                for (ItemDisplay d : r.rig.parts) v.hideEntity(plugin, d);
                if (r.hit != null) v.hideEntity(plugin, r.hit);
                if (r.tag != null) v.hideEntity(plugin, r.tag);
                v.showEntity(plugin, r.m);
                r.rigSees.remove(v.getUniqueId());
            }
        }
        r.rigSees.removeIf(id -> { Player o = Bukkit.getPlayer(id); return o == null || !o.getWorld().equals(w); });
    }

    /** Rig viewers can't see the mannequin: hitting the rig's hitbox is a real attack on it (then the usual forwarding). */
    @EventHandler(priority = EventPriority.LOW)
    public void onHitRig(io.papermc.paper.event.player.PrePlayerAttackEntityEvent e) {
        Run r = byHitbox.get(e.getAttacked().getUniqueId());
        if (r == null) return;
        e.setCancelled(true);
        if (e.getPlayer().equals(r.p) || r.m == null) return;
        Player a = e.getPlayer();
        Mannequin m = r.m;
        // a melee hit like the game's: their attack damage (weapon included) scaled by how charged the swing was
        AttributeInstance dmg = a.getAttribute(Attribute.ATTACK_DAMAGE);
        double charge = 1;
        try { float c = a.getAttackCooldown(); charge = 0.2 + 0.8 * c * c; } catch (RuntimeException ignored) { }
        double amount = Math.max(0.5, (dmg == null ? 1 : dmg.getValue()) * charge);
        org.bukkit.damage.DamageSource src = org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK)
                .withCausingEntity(a).withDirectEntity(a).build();
        hitLands(r, amount, src, a);
    }

    // ---------------------------------------------------------------- the emote pack

    /** FaultlineEmotes.zip beside the main pack (only to games on a version the shader was ported to). */
    void sendPack(Player p) {
        if (!bendy() || Edition.bedrock(p) || !protocolOk(p)) return;
        String url = plugin.getConfig().getString("item-textures.emotes-url", DEFAULT_PACK_URL);
        if (url == null || url.isBlank()) return;
        String hex = PackHash.pick(url, plugin.getConfig().getString("item-textures.emotes-hash", ""), "emotes-hash", plugin.getLogger());
        if (!hex.isEmpty() && !hex.matches("[0-9a-fA-F]{40}")) { plugin.getLogger().warning("[Resource pack] emote pack not sent: item-textures.emotes-hash isn't a SHA-1"); return; }
        try { p.addResourcePack(PACK_ID, url, hex.isEmpty() ? new byte[0] : java.util.HexFormat.of().parseHex(hex), null, false); }
        catch (Exception ex) { plugin.getLogger().warning("[Resource pack] couldn't send the emote pack to " + p.getName() + ": " + ex); }
    }

    @EventHandler
    public void onPackStatus(org.bukkit.event.player.PlayerResourcePackStatusEvent e) {
        if (!PACK_ID.equals(e.getID())) return;
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> packLoaded.add(e.getPlayer().getUniqueId());
            case ACCEPTED, DOWNLOADED -> { }
            default -> {
                packLoaded.remove(e.getPlayer().getUniqueId());
                plugin.getLogger().info("[Resource pack] " + e.getPlayer().getName() + " (emote pack): " + e.getStatus() + " (they see the classic emotes)");
            }
        }
        for (Run r : runs.values()) viewers(r);
    }

    @EventHandler
    public void onQuitPack(PlayerQuitEvent e) { packLoaded.remove(e.getPlayer().getUniqueId()); }

    // ---------------------------------------------------------------- rig poses

    static double sm(double k) { k = Math.max(0, Math.min(1, k)); return k * k * (3 - 2 * k); }

    /** The rig's pose this tick: the frame's wants (offsets, yaw, crouch, arm poses, swings) + each emote's own bends. */
    EmoteRig.Pose rigPose(Run r) {
        EmoteRig.Pose q = new EmoteRig.Pose();
        q.fwd = r.fwd; q.right = r.side; q.up = r.lift; q.yaw = -r.yaw;
        q.lean(EmoteRig.Part.HEAD, r.pitch, 0, 0);
        // relaxed: arms a little out, elbows a little bent, breathing
        double br = Math.sin(r.t * 0.08);
        q.limb(EmoteRig.Part.R_ARM, 2 + br, 5, 0).limb(EmoteRig.Part.L_ARM, 2 - br, 5, 0);
        q.limb(EmoteRig.Part.R_FORE, 10, 0, 0).limb(EmoteRig.Part.L_FORE, 10, 0, 0);
        q.lean(EmoteRig.Part.CHEST, br * 1.2, 0, 0);
        if (r.pose == Pose.SNEAKING) crouch(q, 1);
        if (r.pose == Pose.FALL_FLYING) { q.pelvis[0] = -55; q.lean(EmoteRig.Part.HEAD, -40, 0, 0); q.limb(EmoteRig.Part.R_ARM, 0, 80, 0).limb(EmoteRig.Part.L_ARM, 0, 80, 0); }
        String hold = r.hold == null ? "" : r.hold;
        switch (hold) {
            case "crossbow" -> q.limb(EmoteRig.Part.R_ARM, 85, 0, 12).limb(EmoteRig.Part.L_ARM, 85, 0, 12).limb(EmoteRig.Part.R_FORE, 10, -10, 0).limb(EmoteRig.Part.L_FORE, 10, -10, 0);
            case "goggles" -> q.limb(EmoteRig.Part.R_ARM, 62, -25, 0).limb(EmoteRig.Part.R_FORE, 125, 0, 0);
            case "handout" -> q.limb(EmoteRig.Part.R_ARM, 55, 6, 0).limb(EmoteRig.Part.R_FORE, 25, -8, 0);
            case "chest" -> q.limb(EmoteRig.Part.R_ARM, 30, -10, 0).limb(EmoteRig.Part.R_FORE, 95, -35, 0);
            case "raise" -> q.limb(EmoteRig.Part.R_ARM, 165, 10, 0).limb(EmoteRig.Part.R_FORE, 15, 0, 0);
            case "mouth" -> q.limb(EmoteRig.Part.R_ARM, 60, -22, 0).limb(EmoteRig.Part.R_FORE, 120, 0, 0);
            default -> { }
        }
        swingRig(q, r.swM, true);
        swingRig(q, r.swO, false);
        switch (r.kind) {
            case GRIDDY -> { if (!r.waiting) return griddyRig(r, q); }
            case AURA -> auraRig(r, q);
            case STURDY -> sturdyRig(r, q);
            case BIRD -> birdRig(r, q);
            case DAP -> dapRig(r, q);
            case HUG -> hugRig(r, q);
        }
        return q;
    }

    /** Knees bend, hips drop and go back so the feet stay put, the back leans in. */
    static void crouch(EmoteRig.Pose q, double k) {
        q.up -= 0.13 * k; q.fwd -= 0.12 * k;
        q.add(EmoteRig.Part.WAIST, -18 * k, 0, 0).add(EmoteRig.Part.CHEST, -12 * k, 0, 0).add(EmoteRig.Part.HEAD, 22 * k, 0, 0);
        q.add(EmoteRig.Part.R_LEG, 48 * k, 0, 0).add(EmoteRig.Part.L_LEG, 48 * k, 0, 0);
        q.add(EmoteRig.Part.R_SHIN, -70 * k, 0, 0).add(EmoteRig.Part.L_SHIN, -70 * k, 0, 0);
        q.add(EmoteRig.Part.R_ARM, 18 * k, 0, 0).add(EmoteRig.Part.L_ARM, 18 * k, 0, 0);
        q.add(EmoteRig.Part.R_FORE, 20 * k, 0, 0).add(EmoteRig.Part.L_FORE, 20 * k, 0, 0);
    }

    /** A swing: shoulder whips forward with the elbow snapping open, the chest turns into it. */
    static void swingRig(EmoteRig.Pose q, int tick, boolean main) {
        if (tick < 0) return;
        double at = tick / 6.0, f = 1 - Math.pow(1 - at, 4);
        double up = Math.sin(f * Math.PI) * 75, elbow = Math.sin(at * Math.PI) * 45;
        double turn = Math.sin(Math.sqrt(at) * 2 * Math.PI) * 14 * (main ? -1 : 1);
        EmoteRig.Part arm = main ? EmoteRig.Part.R_ARM : EmoteRig.Part.L_ARM, fore = main ? EmoteRig.Part.R_FORE : EmoteRig.Part.L_FORE;
        q.add(arm, up, 0, 0).add(fore, elbow, 0, 0).add(EmoteRig.Part.CHEST, 0, turn, 0);
    }

    /**
     * Griddy, keyed to the real dance: a hop-skip on every tap (~100 BPM). The tapping leg kicks out nearly straight on its
     * HEEL while the support knee bends under a little bounce; loose, slightly bent arms swing opposite the legs and cross
     * low in front of the hips, the shoulders counter-twisting; the torso leans in and the head watches the feet. After 12
     * taps: the GOGGLES, elbows flared out wide at shoulder height and both hands ringed over the eyes, still bouncing,
     * while it skips back to the start of the loop.
     */
    EmoteRig.Pose griddyRig(Run r, EmoteRig.Pose base) {
        int t = r.t;
        double ph = Math.PI * t / GRIDDY_STEP;             // right tap on +, left tap on -
        double a = Math.sin(ph), aa = Math.abs(a);
        double tr = Math.max(0, a), tl = Math.max(0, -a);
        EmoteRig.Pose q = new EmoteRig.Pose();
        q.fwd = griddyTravel(t); q.right = 0; q.yaw = 0;
        q.up = -0.045 * aa + 0.035 * (1 - aa);              // lands on the tap, hops between taps
        // legs: the tapping leg straight out on the heel, the support knee bent under the body
        q.limb(EmoteRig.Part.R_LEG, 42 * tr + 18 * tl, 3, 0).limb(EmoteRig.Part.R_SHIN, -3 * tr - 38 * tl, 0, 0);
        q.limb(EmoteRig.Part.L_LEG, 42 * tl + 18 * tr, 3, 0).limb(EmoteRig.Part.L_SHIN, -3 * tl - 38 * tr, 0, 0);
        // arms swing opposite the legs: the forward one crosses low in front of the hips, the other swings back
        double rf = Math.max(0, -a), rb = Math.max(0, a);   // right arm forward on a LEFT tap
        double lf = Math.max(0, a), lb = Math.max(0, -a);
        q.limb(EmoteRig.Part.R_ARM, 38 * rf - 30 * rb, 8 - 26 * rf, 0).limb(EmoteRig.Part.R_FORE, 22 + 18 * rf, -14 * rf, 0);
        q.limb(EmoteRig.Part.L_ARM, 38 * lf - 30 * lb, 8 - 26 * lf, 0).limb(EmoteRig.Part.L_FORE, 22 + 18 * lf, -14 * lf, 0);
        q.lean(EmoteRig.Part.WAIST, 8, 6 * a, 0);
        q.lean(EmoteRig.Part.CHEST, 8, -12 * a, 3 * a);
        q.lean(EmoteRig.Part.HEAD, 6 + 5 * aa, 8 * a, 0);
        // the goggles, eased in and out
        int b = t % GRIDDY_BAR;
        double gk = b < GRIDDY_TAPS ? 0 : sm((b - GRIDDY_TAPS) / 4.0) * sm((GRIDDY_BAR - b) / 4.0);
        if (gk > 0) {
            EmoteRig.Pose g = new EmoteRig.Pose();
            g.fwd = q.fwd; g.yaw = q.yaw; g.up = q.up;
            System.arraycopy(q.j, 0, g.j, 0, q.j.length);
            double wob = Math.sin(ph);
            // elbows up and out, forearms folded in: each hand rings its eye (angles solved for the hand on the eye, tools: ik)
            g.limb(EmoteRig.Part.R_ARM, 120, 60 + 3 * wob, 50).limb(EmoteRig.Part.R_FORE, 50, -30, 20);
            g.limb(EmoteRig.Part.L_ARM, 120, 60 - 3 * wob, 50).limb(EmoteRig.Part.L_FORE, 50, -30, 20);
            g.lean(EmoteRig.Part.WAIST, 2, 0, 0);
            g.lean(EmoteRig.Part.CHEST, 0, 4 * wob, 0);
            g.lean(EmoteRig.Part.HEAD, -4 + 3 * aa, 6 * wob, 0);
            q = EmoteRig.Pose.lerp(q, g, (float) gk);
        }
        return q;
    }

    /** Aura Farming: the body stays square, the head turns away to the horizon, one hand drifts up and flows. */
    void auraRig(Run r, EmoteRig.Pose q) {
        q.yaw = -r.yaw * 0.35f;
        q.add(EmoteRig.Part.HEAD, 0, -r.yaw * 0.65f, 0);
        double flow = Math.sin(r.t * 0.045);
        q.limb(EmoteRig.Part.R_ARM, 28 + 14 * flow, 12, -10).limb(EmoteRig.Part.R_FORE, 60 + 25 * flow, -15, 0);
        q.limb(EmoteRig.Part.L_ARM, -6, 7, 0).limb(EmoteRig.Part.L_FORE, 22, 0, 0);
        q.limb(EmoteRig.Part.R_LEG, 0, 6, 0).limb(EmoteRig.Part.L_LEG, 4, 8, 0).limb(EmoteRig.Part.L_SHIN, -8, 0, 0);
        q.lean(EmoteRig.Part.CHEST, -4, 0, 2 * flow);
    }

    /** Get Sturdy: always low (knees bent), arms pumping bent, the knee lifts high before each stomp. */
    void sturdyRig(Run r, EmoteRig.Pose q) {
        if (r.pose != Pose.SNEAKING) crouch(q, 0.45);
        int step = r.t / 5, s = step % 16, sub = r.t % 5;
        double pump = Math.sin(r.t / 10.0 * 2 * Math.PI);
        q.limb(EmoteRig.Part.R_ARM, 30 + 30 * pump, 18, 0).limb(EmoteRig.Part.L_ARM, 30 - 30 * pump, 18, 0);
        q.limb(EmoteRig.Part.R_FORE, 95, -15, 0).limb(EmoteRig.Part.L_FORE, 95, -15, 0);
        if (s == 6 || s == 14) { double k = sm(sub / 4.0); q.limb(EmoteRig.Part.R_LEG, 30 + 55 * k, 6, 0).limb(EmoteRig.Part.R_SHIN, -40 - 50 * k, 0, 0); }
        if (s == 7 || s == 15) { double k = 1 - sm(sub / 3.0); q.limb(EmoteRig.Part.R_LEG, 85 * k, 6, 0).limb(EmoteRig.Part.R_SHIN, -90 * k, 0, 0);
            if (sub >= 3) { q.limb(EmoteRig.Part.R_ARM, -20, 35, 0).limb(EmoteRig.Part.L_ARM, -20, 35, 0); } }
        q.lean(EmoteRig.Part.CHEST, 8, 10 * pump, 0);
    }

    /** Birdbrain: chicken wings (hands tucked at the chest, elbows flapping out), head pecking. */
    void birdRig(Run r, EmoteRig.Pose q) {
        if (r.pose == Pose.FALL_FLYING) { q.limb(EmoteRig.Part.R_FORE, 0, 0, 0).limb(EmoteRig.Part.L_FORE, 0, 0, 0); return; }
        double flap = Math.abs(Math.sin(r.t * 0.8));
        q.limb(EmoteRig.Part.R_ARM, 8, 25 + 45 * flap, 0).limb(EmoteRig.Part.L_ARM, 8, 25 + 45 * flap, 0);
        q.limb(EmoteRig.Part.R_FORE, 125, -40, 0).limb(EmoteRig.Part.L_FORE, 125, -40, 0);
        q.limb(EmoteRig.Part.R_LEG, 0, 5, 0).limb(EmoteRig.Part.L_LEG, 0, 5, 0);
        q.add(EmoteRig.Part.HEAD, 0, 0, 0);
        q.lean(EmoteRig.Part.CHEST, 6 + 6 * flap, 0, 0);
    }

    /** Dap Up: the hand-out arm bent and ready, the bro hug wraps the right arm round their back. */
    void dapRig(Run r, EmoteRig.Pose q) {
        if ("crossbow".equals(r.hold)) {
            q.limb(EmoteRig.Part.R_ARM, 70, 20, 25).limb(EmoteRig.Part.R_FORE, 30, -75, 0);
            q.limb(EmoteRig.Part.L_ARM, 40, 10, 0).limb(EmoteRig.Part.L_FORE, 70, -30, 0);
            q.lean(EmoteRig.Part.CHEST, 10, r.leader ? 10 : -10, 0);
        }
    }

    /** Hug: both arms wrap round, forearms curling in, the head rests to the side. */
    void hugRig(Run r, EmoteRig.Pose q) {
        if ("crossbow".equals(r.hold)) {
            double sway = Math.sin(r.t * 0.18);
            q.limb(EmoteRig.Part.R_ARM, 78, 18, 15).limb(EmoteRig.Part.L_ARM, 78, 18, 15);
            q.limb(EmoteRig.Part.R_FORE, 15, -80, 0).limb(EmoteRig.Part.L_FORE, 15, -80, 0);
            q.lean(EmoteRig.Part.CHEST, 8, 0, 4 * sway);
            q.lean(EmoteRig.Part.HEAD, 6, r.leader ? 25 : -25, 8);
        }
    }

    // ================================================================ the cinematic camera

    /** Where the camera wants to be: an orbit per emote (angle, distance, height) round a look point. */
    double[] shot(Run r) {
        int t = r.t;
        double speed = cfg("camera-speed", 0.6), dist = r.camDist > 0 ? r.camDist : cfg("camera-distance", 4.2);
        double ang = r.camStart, h = 1.5, look = 1.0;
        if (r.waiting) { ang += t * 0.4; dist = 3.8; h = 1.7; return new double[]{ang, dist, h, look}; }
        switch (r.kind) {
            case GRIDDY -> { ang += t * speed; h = 1.2 + 0.3 * Math.sin(t * 0.02); look = 0.8; }
            case AURA -> { // low, slow push-in, then a slow orbit
                double k = ease(Math.min(1, t / 100.0));
                dist = 6.5 - 3.0 * k; h = 0.35; look = 1.55;
                ang += -20 + t * speed * 0.45;
            }
            case STURDY -> { ang += t * speed * 1.8; h = 0.7; look = 0.9; dist = 3.8; }
            case BIRD -> { ang += t * speed * 1.3; h = 2.6; look = 0.8; }
            case DAP, HUG -> { ang += t * 0.35; h = 1.6; look = 1.1; dist = r.camDist > 0 ? r.camDist : 5.5; if (t > 15 && t < 40) dist -= 1.2 * Math.sin((t - 15) / 25.0 * Math.PI); }
        }
        return new double[]{ang, dist, h, look};
    }

    Location center(Run r, double look) {
        if (r.partner != null && r.partner.base != null) return r.base.clone().add(r.partner.base).multiply(0.5).add(0, look, 0);
        return r.base.clone().add(0, look, 0);
    }

    Location shotSpot(Run r) {
        double[] s = shot(r);
        Location c = center(r, s[3]);
        Vector d = dir((float) s[0]).multiply(-1);
        double h = s[2] - s[3];
        Vector way = new Vector(d.getX() * s[1], h, d.getZ() * s[1]);
        double len = way.length();
        Vector u = way.clone().normalize();
        // pull in before any solid block (no seeing through walls), keep 0.4 clear of it
        double free = len;
        for (double k = 0.25; k <= len; k += 0.25) {
            Location probe = c.clone().add(u.clone().multiply(k));
            if (probe.getBlock().getType().isOccluding() || probe.getBlock().getType().isSolid()) { free = Math.max(0.9, k - 0.45); break; }
        }
        Location cam = c.clone().add(u.multiply(free));
        Vector to = c.toVector().subtract(cam.toVector());
        cam.setYaw(yawOf(to));
        cam.setPitch((float) Math.toDegrees(-Math.atan2(to.getY(), Math.hypot(to.getX(), to.getZ()))));
        return cam;
    }

    void snapCam(Run r, Location mid) {
        if (r.cam == null) return;
        q(() -> r.cam.setTeleportDuration(10));
        r.cam.teleport(shotSpot(r));
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (r.cam != null && r.cam.isValid()) r.cam.setTeleportDuration(3); }, 10L);
    }

    void camera(Run r) {
        if (r.cam == null || !r.cam.isValid()) { end(r, false); return; }
        if (r.t % 1 == 0) r.cam.teleport(shotSpot(r));
        Player p = r.p;
        if (!r.attached && r.t >= 2) {
            moving.add(r.id);
            try { p.setSpectatorTarget(r.cam); r.attached = true; } finally { moving.remove(r.id); }
        } else if (r.attached && p.getSpectatorTarget() != r.cam && r.t % 10 == 0) {
            moving.add(r.id);
            try { p.setSpectatorTarget(r.cam); } finally { moving.remove(r.id); }
        }
    }

    // ================================================================ maths

    static String where(Throwable ex) {
        StringBuilder b = new StringBuilder();
        for (StackTraceElement e : ex.getStackTrace()) if (e.getClassName().startsWith("net.faultlinesmp")) { b.append("at ").append(e.getMethodName()).append(':').append(e.getLineNumber()); break; }
        for (StackTraceElement e : ex.getStackTrace()) { b.append(" <- ").append(e.getClassName().replaceAll(".*\\.", "")).append('.').append(e.getMethodName()); break; }
        return b.toString();
    }

    static Vector dir(float yaw) {
        double a = Math.toRadians(yaw);
        return new Vector(-Math.sin(a), 0, Math.cos(a));
    }

    static float yawOf(Vector v) { return (float) Math.toDegrees(Math.atan2(-v.getX(), v.getZ())); }
}
