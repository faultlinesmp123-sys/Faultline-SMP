package net.faultlinesmp.deep;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * THE DEN'S TRUST (Deep 1.8.0): every player has a reputation with the Mermaid Den (den_rep.yml).
 *
 *  earn: killing aggressive creatures near the Den (+1, +2 inside it; `den.rep.kills-per-day` 60), gifts handed to a mermaid
 *        (right-click her holding them: prismarine crystals / sea pickles 1 per 4, a nautilus shell 3, a Heart of the Sea
 *        25; `den.rep.gifts-per-day` 40)
 *  lose: hurting one of its people (-10), killing a mermaid (-50) or a guard (-20), bringing an Omen IV (-200, everyone
 *        else in that war -100)
 *  tiers: HATED (-40 or less: the guards attack on sight), STRANGER, FRIEND (40: the mermaids trade with you), ALLY (120:
 *        the guards trade too and fight beside you inside the Den), HONORED (250: a house of your own in the Den, where you
 *        come back when you die in the Dark Ocean; /den home, the conduit trade)
 * /den shows where you stand.
 */
final class DenRep {
    final FaultlineDeep pl;
    final File file;
    final Map<UUID, Integer> rep = new HashMap<>();
    final Map<UUID, Integer> house = new HashMap<>();
    final Map<UUID, int[]> today = new HashMap<>(); // {day, kills, gifts}
    final Map<UUID, Long> hurtCd = new HashMap<>(), homeCd = new HashMap<>(), lastHit = new HashMap<>();
    boolean dirty;

    enum Tier { HATED, STRANGER, FRIEND, ALLY, HONORED }

    DenRep(FaultlineDeep pl) {
        this.pl = pl;
        file = new File(pl.getDataFolder(), "den_rep.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(pl, () -> { if (dirty) save(); }, 1200L, 1200L);
    }

    double cfg(String path, double def) { return pl.getConfig().getDouble("den.rep." + path, def); }

    int of(UUID id) { return rep.getOrDefault(id, 0); }

    Tier tier(UUID id) {
        int r = of(id);
        if (r <= cfg("hated", -40)) return Tier.HATED;
        if (r >= cfg("honored", 250)) return Tier.HONORED;
        if (r >= cfg("ally", 120)) return Tier.ALLY;
        if (r >= cfg("friend", 40)) return Tier.FRIEND;
        return Tier.STRANGER;
    }

    static String name(Tier t) {
        return switch (t) {
            case HATED -> ChatColor.DARK_RED + "Hated"; case STRANGER -> ChatColor.GRAY + "Stranger"; case FRIEND -> ChatColor.AQUA + "Friend";
            case ALLY -> ChatColor.LIGHT_PURPLE + "Ally"; case HONORED -> ChatColor.GOLD + "Honored";
        };
    }

    /** Changes a player's standing (told when they cross a tier). */
    void add(Player p, int n, String why) {
        if (n == 0) return;
        Tier before = tier(p.getUniqueId());
        int now = Math.max(-1000, Math.min(1000, of(p.getUniqueId()) + n));
        rep.put(p.getUniqueId(), now);
        dirty = true;
        Tier after = tier(p.getUniqueId());
        if (why != null) p.sendActionBar(net.kyori.adventure.text.Component.text((n > 0 ? "+" : "") + n + " Den reputation " + why + " (" + now + ")",
                n > 0 ? net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE : net.kyori.adventure.text.format.NamedTextColor.RED));
        if (after != before) {
            p.sendMessage(ChatColor.LIGHT_PURPLE + "The Mermaid Den now sees you as: " + name(after) + ChatColor.GRAY + " (" + perks(after) + ")");
            p.playSound(p, after.ordinal() > before.ordinal() ? Sound.ENTITY_PLAYER_LEVELUP : Sound.ENTITY_ELDER_GUARDIAN_CURSE, SoundCategory.PLAYERS, 0.8f, 1.2f);
            if (after == Tier.HONORED) claimHouse(p);
            if (after.ordinal() >= Tier.FRIEND.ordinal()) pl.console("index stat " + p.getName() + " den_" + after.name().toLowerCase() + " 1");
        }
    }

    static String perks(Tier t) {
        return switch (t) {
            case HATED -> "its guards attack you on sight";
            case STRANGER -> "they leave you alone";
            case FRIEND -> "the mermaids trade with you";
            case ALLY -> "the guards trade too, and fight beside you in the Den";
            case HONORED -> "a house of your own in the Den: /den home";
        };
    }

    int[] day(UUID id) {
        int d = (int) LocalDate.now().toEpochDay();
        int[] t = today.get(id);
        if (t == null || t[0] != d) { t = new int[]{d, 0, 0}; today.put(id, t); }
        return t;
    }

    // =========================================================================================== earning it
    /** An aggressive creature a player killed: near the Den it counts as defending it. */
    void killed(DeepMobs.Beast m, Player killer) {
        if (killer == null || m.k.temper() != DeepMobs.Temper.AGGRESSIVE || pl.den == null || pl.den.resident(m)) return;
        Location l = m.body.getLocation();
        if (!Den.inside(l, cfg("defend-radius", 120))) return;
        int[] t = day(killer.getUniqueId());
        int n = Den.inside(l, 0) ? 2 : 1;
        if (t[1] >= cfg("kills-per-day", 60)) return;
        t[1] += n;
        add(killer, n, "(you defended the Den)");
    }

    /** Right-clicking a mermaid or a guard: a gift if you hold one (mermaids only), else their wares. */
    void click(Player p, DeepMobs.Beast m) {
        boolean guard = m.k.id().equals("merguard");
        Tier t = tier(p.getUniqueId());
        if (m.angry()) { p.sendActionBar(net.kyori.adventure.text.Component.text("Not now: they're fighting.", net.kyori.adventure.text.format.NamedTextColor.RED)); return; }
        if (t == Tier.HATED) { p.sendMessage(ChatColor.DARK_RED + "They turn their backs on you."); return; }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!guard && gift(p, m, hand)) return;
        Tier need = guard ? Tier.ALLY : Tier.FRIEND;
        if (t.ordinal() < need.ordinal()) {
            p.sendMessage(ChatColor.LIGHT_PURPLE + (guard ? "The guard eyes you and says nothing." : "She smiles, but she doesn't trust you yet.")
                    + ChatColor.GRAY + " (Den reputation " + of(p.getUniqueId()) + "/" + (int) cfg(guard ? "ally" : "friend", guard ? 120 : 40)
                    + ": defend the Den from creatures, or bring the mermaids gifts. /den)");
            return;
        }
        Merchant mer = Bukkit.createMerchant(guard ? "Den Guard" : "Mermaid");
        mer.setRecipes(guard ? guardTrades(t) : mermaidTrades(t));
        p.openMerchant(mer, true);
        m.body.getWorld().playSound(m.body.getLocation(), Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM, SoundCategory.NEUTRAL, 1f, guard ? 0.6f : 1.2f);
    }

    boolean gift(Player p, DeepMobs.Beast m, ItemStack hand) {
        if (hand == null) return false;
        if (pl.outer != null && pl.outer.isFossil(hand)) { // 1.9.0: an Ancient Fossil = 15 reputation + a sea treasure back
            int[] t = day(p.getUniqueId());
            int room = (int) cfg("gifts-per-day", 40) - t[2];
            if (room <= 0) { p.sendMessage(ChatColor.LIGHT_PURPLE + "She has had enough gifts for today." + ChatColor.GRAY + " (Come back tomorrow.)"); return true; }
            hand.setAmount(hand.getAmount() - 1);
            int gain = Math.min(room, (int) cfg("fossil", 15));
            t[2] += gain;
            ItemStack back = pl.outer.fossilReward();
            p.getInventory().addItem(back).values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
            Location l = m.body.getLocation().add(0, 1, 0);
            l.getWorld().spawnParticle(Particle.HEART, l, 8, 0.4, 0.4, 0.4, 0);
            l.getWorld().playSound(l, Sound.ENTITY_ALLAY_ITEM_GIVEN, SoundCategory.NEUTRAL, 1f, 0.9f);
            p.sendMessage(ChatColor.LIGHT_PURPLE + "She turns the fossil over and over, then presses something into your hand.");
            add(p, gain, "(an Ancient Fossil for the Den)");
            return true;
        }
        int per; // reputation per item (crystals and pickles: 1 per 4)
        switch (hand.getType()) {
            case PRISMARINE_CRYSTALS, SEA_PICKLE -> per = -4;
            case NAUTILUS_SHELL -> per = 3;
            case HEART_OF_THE_SEA -> per = 25;
            default -> { return false; }
        }
        int[] t = day(p.getUniqueId());
        int room = (int) cfg("gifts-per-day", 40) - t[2];
        if (room <= 0) { p.sendMessage(ChatColor.LIGHT_PURPLE + "She has had enough gifts for today." + ChatColor.GRAY + " (Come back tomorrow.)"); return true; }
        int take, gain;
        if (per < 0) { take = Math.min(hand.getAmount() / 4, room) * 4; gain = take / 4; }
        else { take = Math.min(hand.getAmount(), (int) Math.ceil(room / (double) per)); gain = Math.min(room, take * per); }
        if (take <= 0) { p.sendMessage(ChatColor.GRAY + "(Four at a time.)"); return true; }
        hand.setAmount(hand.getAmount() - take);
        t[2] += gain;
        Location l = m.body.getLocation().add(0, 1, 0);
        l.getWorld().spawnParticle(Particle.HEART, l, 5, 0.4, 0.4, 0.4, 0);
        l.getWorld().playSound(l, Sound.ENTITY_ALLAY_ITEM_GIVEN, SoundCategory.NEUTRAL, 1f, 1.2f);
        add(p, gain, "(a gift for the Den)");
        return true;
    }

    // =========================================================================================== losing it
    void hurt(Player by) {
        long now = System.currentTimeMillis();
        if (now - hurtCd.getOrDefault(by.getUniqueId(), 0L) < 5000) return;
        hurtCd.put(by.getUniqueId(), now);
        add(by, -(int) cfg("hurt", 10), "(you hurt one of the Den's people)");
    }

    void killedResident(Player by, boolean mermaid) { add(by, -(int) cfg(mermaid ? "kill-mermaid" : "kill-guard", mermaid ? 50 : 20), mermaid ? "(you killed a mermaid)" : "(you killed a guard)"); }

    void war(Player by, java.util.Collection<UUID> others) {
        add(by, -(int) cfg("omen", 200), "(you brought war to the Den)");
        for (UUID id : others) { Player o = Bukkit.getPlayer(id); if (o != null && o != by) add(o, -(int) cfg("war", 100), "(you made war on the Den)"); }
    }

    // =========================================================================================== the Den reacts (every second, Den.tick)
    void tick(List<Player> here) {
        if (pl.den == null) return;
        List<DeepMobs.Beast> guards = new ArrayList<>();
        for (int i = Den.MERMAIDS; i < pl.den.slot.length; i++) if (pl.den.slot[i] != null) guards.add(pl.den.slot[i]);
        if (guards.isEmpty()) return;
        for (Player p : here) {
            if (!DeepMobs.fair(p) || !Den.inside(p.getLocation(), 0)) continue;
            Tier t = tier(p.getUniqueId());
            if (t == Tier.HATED) { // on sight
                for (DeepMobs.Beast g : guards) if (!g.angry() && g.body.getLocation().distanceSquared(p.getLocation()) < cfg("hated-sight", 14) * cfg("hated-sight", 14)) pl.mobs.anger(g, p, 300);
            } else if (t.ordinal() >= Tier.ALLY.ordinal()) defend(p, guards);
        }
    }

    /** An Ally in the Den: the guards near them go for whatever hunts them (aggressive creatures within 16). */
    void defend(Player p, List<DeepMobs.Beast> guards) {
        DeepMobs.Beast foe = null; double best = cfg("defend-reach", 16) * cfg("defend-reach", 16);
        for (DeepMobs.Beast b : pl.mobs.live.values()) {
            if (b.k.temper() != DeepMobs.Temper.AGGRESSIVE || pl.den.resident(b) || !b.body.getWorld().equals(p.getWorld())) continue;
            double d = b.body.getLocation().distanceSquared(p.getLocation());
            if (d < best) { best = d; foe = b; }
        }
        if (foe == null) return;
        for (DeepMobs.Beast g : guards) {
            if (g.angry() || g.body.getLocation().distanceSquared(p.getLocation()) > 36 * 36) continue;
            Location fl = foe.body.getLocation();
            double d = g.body.getLocation().distance(fl);
            if (d > 3.2) { if (g.body instanceof Mob mob) try { mob.getPathfinder().moveTo(fl, 1.3); } catch (RuntimeException | LinkageError ignored) { } }
            else {
                foe.body.damage(g.k.damage(), g.body);
                fl.getWorld().spawnParticle(Particle.SWEEP_ATTACK, fl.clone().add(0, 0.8, 0), 1);
                fl.getWorld().playSound(fl, Sound.ITEM_TRIDENT_HIT, SoundCategory.NEUTRAL, 1f, 1f);
            }
        }
    }

    // =========================================================================================== the house (Honored)
    /** The houses no mermaid lives in. */
    static boolean mermaidHome(int i) {
        for (int m = 0; m < Den.MERMAIDS; m++) if (m * MermaidDen.HOUSES.size() / Den.MERMAIDS == i) return true;
        return false;
    }

    void claimHouse(Player p) {
        if (house.containsKey(p.getUniqueId())) return;
        for (int i = 0; i < MermaidDen.HOUSES.size(); i++) {
            if (mermaidHome(i) || house.containsValue(i)) continue;
            house.put(p.getUniqueId(), i);
            dirty = true;
            MermaidDen.House h = MermaidDen.HOUSES.get(i);
            p.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "The Den gives you a house of your own " + ChatColor.GRAY + "(" + h.x() + ", " + h.z()
                    + "). You come back here when you die in the Dark Ocean. " + ChatColor.WHITE + "/den home");
            return;
        }
        p.sendMessage(ChatColor.GRAY + "(Every house in the Den is taken. You're still Honored.)");
    }

    /** Where an Honored player's house is (open water inside it), or null. */
    Location houseSpot(Player p) {
        Integer i = house.get(p.getUniqueId());
        if (i == null || pl.world == null || i >= MermaidDen.HOUSES.size()) return null;
        MermaidDen.House h = MermaidDen.HOUSES.get(i);
        pl.world.getChunkAt(h.x() >> 4, h.z() >> 4);
        Location l = Den.waterAt(pl.world, h.x(), h.z());
        return l != null ? l : new Location(pl.world, h.x() + 0.5, MermaidDen.FLOOR + 2, h.z() + 0.5);
    }

    void noteHit(Player p) { lastHit.put(p.getUniqueId(), System.currentTimeMillis()); }

    // =========================================================================================== /den
    boolean command(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        if (args.length > 0 && args[0].equalsIgnoreCase("home")) {
            Location h = houseSpot(p);
            if (h == null) { p.sendMessage(ChatColor.GRAY + "You don't have a house in the Den. (Honored players get one: " + (int) cfg("honored", 250) + " reputation.)"); return true; }
            if (pl.world == null || !p.getWorld().equals(pl.world)) { p.sendMessage(ChatColor.RED + "Only from inside the Dark Ocean."); return true; }
            long now = System.currentTimeMillis();
            if (now - lastHit.getOrDefault(p.getUniqueId(), 0L) < 10_000) { p.sendMessage(ChatColor.RED + "Not while you're fighting."); return true; }
            long cd = homeCd.getOrDefault(p.getUniqueId(), 0L);
            if (now < cd) { p.sendMessage(ChatColor.GRAY + "Again in " + ((cd - now) / 1000 + 1) + " s."); return true; }
            homeCd.put(p.getUniqueId(), now + (long) (cfg("home-cooldown-seconds", 300) * 1000));
            p.teleport(h);
            p.playSound(p, Sound.ENTITY_DOLPHIN_PLAY, SoundCategory.PLAYERS, 1f, 1f);
            return true;
        }
        Tier t = tier(p.getUniqueId());
        p.sendMessage(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "The Mermaid Den " + ChatColor.GRAY + "(" + MermaidDen.MX + ", " + MermaidDen.MZ + " in the Dark Ocean)");
        p.sendMessage(ChatColor.GRAY + "Your reputation: " + ChatColor.WHITE + of(p.getUniqueId()) + ChatColor.GRAY + "  standing: " + name(t) + ChatColor.GRAY + " (" + perks(t) + ")");
        Tier next = t == Tier.HONORED ? null : Tier.values()[Math.max(Tier.FRIEND.ordinal(), t.ordinal() + 1)];
        if (next != null) p.sendMessage(ChatColor.GRAY + "Next: " + name(next) + ChatColor.GRAY + " at " + (int) cfg(next.name().toLowerCase(), switch (next) { case ALLY -> 120; case HONORED -> 250; default -> 40; })
                + " (" + perks(next) + ")");
        int[] d = day(p.getUniqueId());
        p.sendMessage(ChatColor.GRAY + "Earn it: kill creatures near the Den (" + d[1] + "/" + (int) cfg("kills-per-day", 60) + " today), right-click a mermaid holding prismarine crystals,"
                + " sea pickles, nautilus shells or a Heart of the Sea (" + d[2] + "/" + (int) cfg("gifts-per-day", 40) + " today). Hurting its people costs you.");
        Location h = houseSpot(p);
        if (h != null) p.sendMessage(ChatColor.GOLD + "Your house: " + h.getBlockX() + ", " + h.getBlockZ() + ChatColor.GRAY + " (/den home)");
        return true;
    }

    // =========================================================================================== trades
    static ItemStack it(Material m, int n) { return new ItemStack(m, n); }

    static ItemStack book(Enchantment e, int lvl) {
        ItemStack b = new ItemStack(Material.ENCHANTED_BOOK);
        EnchantmentStorageMeta m = (EnchantmentStorageMeta) b.getItemMeta();
        m.addStoredEnchant(e, lvl, true);
        b.setItemMeta(m);
        return b;
    }

    static ItemStack potion(PotionType type, int n) {
        ItemStack p = new ItemStack(Material.POTION, n);
        PotionMeta m = (PotionMeta) p.getItemMeta();
        m.setBasePotionType(type);
        p.setItemMeta(m);
        return p;
    }

    static MerchantRecipe trade(ItemStack out, ItemStack a, ItemStack b) {
        MerchantRecipe r = new MerchantRecipe(out, 0, 9999, false, 0, 0f);
        r.addIngredient(a);
        if (b != null) r.addIngredient(b);
        return r;
    }

    List<MerchantRecipe> mermaidTrades(Tier t) {
        List<MerchantRecipe> out = new ArrayList<>();
        out.add(trade(it(Material.NAUTILUS_SHELL, 1), it(Material.PRISMARINE_SHARD, 12), null));
        out.add(trade(it(Material.SEA_LANTERN, 4), it(Material.PRISMARINE_CRYSTALS, 8), null));
        out.add(trade(it(Material.SEA_PICKLE, 16), it(Material.NAUTILUS_SHELL, 2), null));
        out.add(trade(potion(PotionType.LONG_WATER_BREATHING, 3), it(Material.EMERALD, 6), null));
        out.add(trade(it(Material.GOLDEN_APPLE, 2), it(Material.PRISMARINE_CRYSTALS, 16), null));
        out.add(trade(book(Enchantment.DEPTH_STRIDER, 3), it(Material.DIAMOND, 4), null));
        out.add(trade(book(Enchantment.RESPIRATION, 3), it(Material.DIAMOND, 4), null));
        out.add(trade(book(Enchantment.AQUA_AFFINITY, 1), it(Material.DIAMOND, 3), null));
        out.add(trade(it(Material.HEART_OF_THE_SEA, 1), it(Material.PRISMARINE_CRYSTALS, 24), it(Material.NAUTILUS_SHELL, 4)));
        return out;
    }

    List<MerchantRecipe> guardTrades(Tier t) {
        List<MerchantRecipe> out = new ArrayList<>();
        out.add(trade(it(Material.TRIDENT, 1), it(Material.PRISMARINE_CRYSTALS, 32), it(Material.DIAMOND, 8)));
        out.add(trade(it(Material.TURTLE_HELMET, 1), it(Material.NAUTILUS_SHELL, 6), it(Material.DIAMOND, 4)));
        out.add(trade(book(Enchantment.RIPTIDE, 3), it(Material.DIAMOND, 10), null));
        out.add(trade(book(Enchantment.LOYALTY, 3), it(Material.DIAMOND, 10), null));
        out.add(trade(book(Enchantment.IMPALING, 5), it(Material.DIAMOND, 16), null));
        out.add(trade(book(Enchantment.MENDING, 1), it(Material.DIAMOND, 16), null));
        if (t == Tier.HONORED) out.add(trade(it(Material.CONDUIT, 1), it(Material.HEART_OF_THE_SEA, 1), it(Material.NAUTILUS_SHELL, 16)));
        return out;
    }

    // =========================================================================================== saving
    void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        var r = y.getConfigurationSection("rep");
        if (r != null) for (String k : r.getKeys(false)) try { rep.put(UUID.fromString(k), r.getInt(k)); } catch (IllegalArgumentException ignored) { }
        var h = y.getConfigurationSection("house");
        if (h != null) for (String k : h.getKeys(false)) try { house.put(UUID.fromString(k), h.getInt(k)); } catch (IllegalArgumentException ignored) { }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : rep.entrySet()) y.set("rep." + e.getKey(), e.getValue());
        for (var e : house.entrySet()) y.set("house." + e.getKey(), e.getValue());
        try { y.save(file); dirty = false; } catch (IOException e) { pl.getLogger().warning("[Den] couldn't save den_rep.yml: " + e); }
    }
}
