package net.faultlinesmp.items;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * BOSS ENCHANT BOOKS (Items 1.2.17, config enchants:). Bosses drop books of enchantments vanilla doesn't have; put the
 * item on the left of an anvil and the book on the right. Two books of the same enchantment and level make the next level.
 *  - Lifesteal I-II (swords, axes, tridents, and bows since 1.2.27: on the arrow, a 90% draw counts as charged): heal 8% / 16% of the damage you deal (max 3 hp a hit).
 *  - Frostbite I-II (swords, axes, tridents, and bows since 1.2.27: on the arrow, a 90% draw counts as charged): 15% / 30% to chill: Slowness I/II for 3/4 s.
 *  - Venom I-II (swords, axes, tridents, and bows since 1.2.27: on the arrow, a 90% draw counts as charged): 15% / 30% to poison: Poison I/II for 3 s.
 *  - Thunderstrike I (swords, axes, tridents, and bows since 1.2.27: on the arrow, a 90% draw counts as charged): 8% to call a (harmless to blocks) lightning bolt for +4 damage, 8 s cooldown.
 *  - Vitality I-II (chestplates): Regeneration I / II while worn (1.2.46; it was +2 / +4 max health).
 *  - Vein Miner I (pickaxes): sneak + mine an ore = the connected vein of that ore (enchants.veinminer.max-blocks 32),
 *    each block broken as the player (claims, quests, Fortune/Silk Touch and durability all apply).
 *  - Featherstep I (boots): 35% less fall damage (stacks with Feather Falling) and you never trample farmland.
 * 1.2.52: Lifesteal on PLAYERS heals only on every 5th hit you land on players (`lifesteal.pvp-every-hits`; mobs: every hit).
 * New: Executioner I-II, Cleave I-II, Bleed I-II (weapons), Arrowguard I-II (chestplates), Thornhide I-II (leggings),
 * Swiftstride I-II and Tidecaller I (boots), Autosmelt I (pickaxes), Clearsight I (helmets). They drop through
 * enchants.new-boss-drops, rolled ON TOP of boss-drops (a server config keeps its old boss-drops lists).
 * Which boss drops which: enchants.boss-drops (entry id: ["enchant:level:chance", ...]); the Index's Museum asks for a
 * roll on every boss defeat (`fenchant bossdrop <player> <entry>`), so every boss is covered by one hook.
 * Stored on the item as PDC faultlineitems:fenchants ("lifesteal=2;venom=1") plus a lore line each.
 */
final class Enchants implements Listener, CommandExecutor, TabCompleter {

    enum Kind { MELEE, CHEST, BOOTS, PICKAXE, LEGS, HELMET }

    record Def(String id, String name, int max, Kind kind, ChatColor color, String desc) { }

    static final Map<String, Def> DEFS = new LinkedHashMap<>();
    static {
        add(new Def("lifesteal", "Lifesteal", 2, Kind.MELEE, ChatColor.RED, "Heals you for part of the damage you deal."));
        add(new Def("frostbite", "Frostbite", 2, Kind.MELEE, ChatColor.AQUA, "A chance to chill: Slowness."));
        add(new Def("venom", "Venom", 2, Kind.MELEE, ChatColor.DARK_GREEN, "A chance to poison."));
        add(new Def("thunderstrike", "Thunderstrike", 1, Kind.MELEE, ChatColor.YELLOW, "A chance to call lightning for extra damage."));
        add(new Def("vitality", "Vitality", 2, Kind.CHEST, ChatColor.LIGHT_PURPLE, "Regeneration while worn."));
        add(new Def("veinminer", "Vein Miner", 1, Kind.PICKAXE, ChatColor.GOLD, "Sneak while mining an ore to dig out the whole vein."));
        add(new Def("featherstep", "Featherstep", 1, Kind.BOOTS, ChatColor.WHITE, "Less fall damage; never trample farmland."));
        // 1.2.52
        add(new Def("executioner", "Executioner", 2, Kind.MELEE, ChatColor.DARK_RED, "More damage to anything below 30% health."));
        add(new Def("cleave", "Cleave", 2, Kind.MELEE, ChatColor.GOLD, "A charged swing also cuts whatever stands beside your target."));
        add(new Def("bleed", "Bleed", 2, Kind.MELEE, ChatColor.RED, "A chance to open a wound that bleeds for a few seconds."));
        add(new Def("arrowguard", "Arrowguard", 2, Kind.CHEST, ChatColor.GRAY, "Less damage from arrows and other projectiles."));
        add(new Def("thornhide", "Thornhide", 2, Kind.LEGS, ChatColor.DARK_GREEN, "Hits you take in melee cut the attacker back."));
        add(new Def("swiftstride", "Swiftstride", 2, Kind.BOOTS, ChatColor.AQUA, "You move faster."));
        add(new Def("tidecaller", "Tidecaller", 1, Kind.BOOTS, ChatColor.DARK_AQUA, "Swim like a dolphin."));
        add(new Def("autosmelt", "Autosmelt", 1, Kind.PICKAXE, ChatColor.GOLD, "Raw iron, gold and copper come out as ingots."));
        add(new Def("clearsight", "Clearsight", 1, Kind.HELMET, ChatColor.YELLOW, "Blindness and Darkness can't touch you (the Abyss's own dark still can)."));
    }
    private static void add(Def d) { DEFS.put(d.id, d); }

    static final Map<String, List<String>> DEFAULT_DROPS = new LinkedHashMap<>();
    static {
        DEFAULT_DROPS.put("the_demon_eye", List.of("thunderstrike:1:0.25", "featherstep:1:0.2"));
        DEFAULT_DROPS.put("queen_spider", List.of("venom:1:0.3"));
        DEFAULT_DROPS.put("the_bulwark", List.of("vitality:1:0.2"));
        DEFAULT_DROPS.put("the_great_hog", List.of("vitality:1:0.25"));
        DEFAULT_DROPS.put("captain_rotbeard", List.of("lifesteal:1:0.2"));
        DEFAULT_DROPS.put("mortimer_freeze", List.of("frostbite:1:0.3"));
        DEFAULT_DROPS.put("the_dune_devourer", List.of("venom:1:0.3"));
        DEFAULT_DROPS.put("the_frostmaw", List.of("frostbite:2:0.25"));
        DEFAULT_DROPS.put("don_lorenzo", List.of("lifesteal:1:0.3"));
        DEFAULT_DROPS.put("the_kraken", List.of("thunderstrike:1:0.3"));
        DEFAULT_DROPS.put("the_kraken_at_sea", List.of("thunderstrike:1:0.3"));
        DEFAULT_DROPS.put("diamond_jacob", List.of("vitality:2:0.35"));
        DEFAULT_DROPS.put("rocco_vendetta", List.of("lifesteal:2:0.35"));
        DEFAULT_DROPS.put("brine_scourge", List.of("lifesteal:2:0.35", "venom:2:0.35", "veinminer:1:0.25"));
        DEFAULT_DROPS.put("umbral_leviathan", List.of("vitality:2:0.35", "thunderstrike:1:0.35", "veinminer:1:0.25"));
        DEFAULT_DROPS.put("coralia", List.of("featherstep:1:0.2", "frostbite:1:0.2"));
        DEFAULT_DROPS.put("old_inkjaw", List.of("venom:1:0.2", "lifesteal:1:0.15"));
        DEFAULT_DROPS.put("grandmaw_clam", List.of("vitality:1:0.2"));
        DEFAULT_DROPS.put("the_lament", List.of("lifesteal:1:0.2", "frostbite:2:0.1"));
        DEFAULT_DROPS.put("the_skeleton_captain", List.of("thunderstrike:1:0.3"));
        DEFAULT_DROPS.put("grimtusk", List.of("lifesteal:1:0.3", "vitality:1:0.2"));
        DEFAULT_DROPS.put("leviathan", List.of("thunderstrike:1:0.2"));
        DEFAULT_DROPS.put("sandworm_king", List.of("venom:2:0.2"));
        DEFAULT_DROPS.put("lich", List.of("lifesteal:2:0.2"));
        DEFAULT_DROPS.put("frost_wyrm", List.of("frostbite:2:0.25"));
        DEFAULT_DROPS.put("stone_golem", List.of("vitality:1:0.2", "veinminer:1:0.3"));
        DEFAULT_DROPS.put("ghost_captain", List.of("featherstep:1:0.3"));
    }

    /** 1.2.52 books, rolled ON TOP of enchants.boss-drops (enchants.new-boss-drops.<entry> overrides these). */
    static final Map<String, List<String>> NEW_DROPS = new LinkedHashMap<>();
    static {
        NEW_DROPS.put("the_demon_eye", List.of("clearsight:1:0.2"));
        NEW_DROPS.put("queen_spider", List.of("bleed:1:0.25"));
        NEW_DROPS.put("the_bulwark", List.of("arrowguard:1:0.25"));
        NEW_DROPS.put("the_great_hog", List.of("cleave:1:0.2"));
        NEW_DROPS.put("captain_rotbeard", List.of("tidecaller:1:0.2"));
        NEW_DROPS.put("mortimer_freeze", List.of("swiftstride:1:0.2"));
        NEW_DROPS.put("the_dune_devourer", List.of("autosmelt:1:0.2"));
        NEW_DROPS.put("the_frostmaw", List.of("arrowguard:1:0.2"));
        NEW_DROPS.put("don_lorenzo", List.of("executioner:1:0.25"));
        NEW_DROPS.put("the_kraken", List.of("tidecaller:1:0.3"));
        NEW_DROPS.put("the_kraken_at_sea", List.of("tidecaller:1:0.3"));
        NEW_DROPS.put("diamond_jacob", List.of("executioner:2:0.2", "arrowguard:2:0.2"));
        NEW_DROPS.put("rocco_vendetta", List.of("cleave:2:0.2", "thornhide:1:0.25"));
        NEW_DROPS.put("brine_scourge", List.of("bleed:2:0.25", "tidecaller:1:0.3"));
        NEW_DROPS.put("umbral_leviathan", List.of("clearsight:1:0.3", "executioner:1:0.25"));
        NEW_DROPS.put("coralia", List.of("swiftstride:1:0.2"));
        NEW_DROPS.put("old_inkjaw", List.of("clearsight:1:0.2"));
        NEW_DROPS.put("grandmaw_clam", List.of("thornhide:1:0.25"));
        NEW_DROPS.put("the_lament", List.of("bleed:1:0.2"));
        NEW_DROPS.put("the_skeleton_captain", List.of("cleave:1:0.25"));
        NEW_DROPS.put("grimtusk", List.of("thornhide:2:0.2", "cleave:1:0.2"));
        NEW_DROPS.put("leviathan", List.of("tidecaller:1:0.25"));
        NEW_DROPS.put("sandworm_king", List.of("autosmelt:1:0.25"));
        NEW_DROPS.put("lich", List.of("executioner:1:0.2"));
        NEW_DROPS.put("frost_wyrm", List.of("swiftstride:2:0.2"));
        NEW_DROPS.put("stone_golem", List.of("autosmelt:1:0.3"));
        NEW_DROPS.put("ghost_captain", List.of("swiftstride:1:0.2"));
    }

    private final FaultlineItems plugin;
    final NamespacedKey itemKey, bookKey, vitalityKey, swiftKey;
    /** Hits each player has landed on players with a Lifesteal weapon since its last heal (1.2.52). */
    final Map<UUID, Integer> pvpHits = new HashMap<>();
    /** Bleeding things: seconds left and who cut them. */
    private final Map<UUID, int[]> bleeding = new HashMap<>();
    private final Map<UUID, UUID> bledBy = new HashMap<>();
    private final java.util.Set<UUID> cutting = new java.util.HashSet<>();
    private final Random random = new Random();
    private final Map<UUID, Long> thunderReady = new HashMap<>();

    Enchants(FaultlineItems plugin) {
        this.plugin = plugin;
        itemKey = new NamespacedKey(plugin, "fenchants");
        bookKey = new NamespacedKey(plugin, "fenchant_book");
        vitalityKey = new NamespacedKey(plugin, "vitality");
        swiftKey = new NamespacedKey(plugin, "swiftstride");
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private double cfg(String path, double def) { return plugin.getConfig().getDouble("enchants." + path, def); }

    // ---------------------------------------------------------------- items

    static String roman(int n) { return switch (n) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; default -> String.valueOf(n); }; }

    ItemStack book(String id, int level) {
        Def d = DEFS.get(id);
        if (d == null) return null;
        level = Math.max(1, Math.min(d.max, level));
        ItemStack it = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(d.color + "" + ChatColor.BOLD + d.name + " " + roman(level) + ChatColor.GRAY + " (Boss Enchant)");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + d.desc);
        lore.add(ChatColor.DARK_GRAY + "For: " + switch (d.kind) { case MELEE -> d.id.equals("cleave") ? "swords, axes, tridents" : "swords, axes, tridents, bows"; case CHEST -> "chestplates"; case BOOTS -> "boots"; case PICKAXE -> "pickaxes"; case LEGS -> "leggings"; case HELMET -> "helmets"; });
        lore.add(ChatColor.DARK_GRAY + "Item on the left of an anvil, this book on the right.");
        if (level < d.max) lore.add(ChatColor.DARK_GRAY + "Two of these make " + d.name + " " + roman(level + 1) + ".");
        m.setLore(lore);
        m.getPersistentDataContainer().set(bookKey, PersistentDataType.STRING, id + "=" + level);
        m.setMaxStackSize(1);
        it.setItemMeta(m);
        return it;
    }

    /** id -> level stored in a book (one entry) or an item (any number); empty if none. */
    Map<String, Integer> read(ItemStack it, boolean asBook) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (it == null || !it.hasItemMeta()) return out;
        String s = it.getItemMeta().getPersistentDataContainer().get(asBook ? bookKey : itemKey, PersistentDataType.STRING);
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(";")) {
            String[] kv = part.split("=");
            if (kv.length != 2 || !DEFS.containsKey(kv[0])) continue;
            try { out.put(kv[0], Math.max(1, Math.min(DEFS.get(kv[0]).max, Integer.parseInt(kv[1])))); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    int level(ItemStack it, String id) { return read(it, false).getOrDefault(id, 0); }

    static boolean fits(Material m, Kind k) {
        String n = m.name();
        return switch (k) {
            case MELEE -> n.endsWith("_SWORD") || n.endsWith("_AXE") || m == Material.TRIDENT || m == Material.BOW; // 1.2.27: bows too (on the arrow)
            case CHEST -> n.endsWith("_CHESTPLATE");
            case BOOTS -> n.endsWith("_BOOTS");
            case PICKAXE -> n.endsWith("_PICKAXE");
            case LEGS -> n.endsWith("_LEGGINGS");
            case HELMET -> n.endsWith("_HELMET");
        };
    }

    /** The anvil result for left + right, or null when it isn't one of ours. */
    ItemStack combine(ItemStack left, ItemStack right) {
        if (left == null || right == null || left.getType().isAir()) return null;
        Map<String, Integer> book = read(right, true);
        if (book.isEmpty()) return null;
        Map.Entry<String, Integer> b = book.entrySet().iterator().next();
        Def d = DEFS.get(b.getKey());
        Map<String, Integer> leftBook = read(left, true);
        if (!leftBook.isEmpty()) { // book + book of the same level = the next level
            Integer l = leftBook.get(d.id);
            if (l == null || !l.equals(b.getValue()) || l >= d.max) return null;
            return book(d.id, l + 1);
        }
        if (left.getAmount() != 1 || !fits(left.getType(), d.kind)) return null;
        Map<String, Integer> have = read(left, false);
        int now = have.getOrDefault(d.id, 0);
        int to = now == b.getValue() ? Math.min(d.max, now + 1) : Math.max(now, b.getValue());
        if (to <= now) return null; // nothing to gain
        have.put(d.id, to);
        ItemStack out = left.clone();
        write(out, have);
        return out;
    }

    void write(ItemStack it, Map<String, Integer> ench) {
        ItemMeta m = it.getItemMeta();
        StringBuilder s = new StringBuilder();
        for (var e : ench.entrySet()) { if (s.length() > 0) s.append(';'); s.append(e.getKey()).append('=').append(e.getValue()); }
        m.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, s.toString());
        List<String> lore = new ArrayList<>();
        for (var e : ench.entrySet()) { Def d = DEFS.get(e.getKey()); lore.add(d.color + "✦ " + d.name + (d.max > 1 || e.getValue() > 1 ? " " + roman(e.getValue()) : "")); }
        if (m.hasLore()) for (String l : m.getLore()) if (!ours(l)) lore.add(l);
        m.setLore(lore);
        m.setEnchantmentGlintOverride(true);
        it.setItemMeta(m);
    }

    private static boolean ours(String loreLine) {
        String s = ChatColor.stripColor(loreLine);
        if (!s.startsWith("✦ ")) return false;
        for (Def d : DEFS.values()) if (s.equals("✦ " + d.name) || s.startsWith("✦ " + d.name + " ")) return true;
        return false;
    }

    // ---------------------------------------------------------------- anvil

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnvil(PrepareAnvilEvent e) {
        ItemStack left = e.getInventory().getItem(0), right = e.getInventory().getItem(1);
        if (read(right, true).isEmpty()) return;
        ItemStack out = combine(left, right);
        e.setResult(out);
        if (out == null) return;
        int lvl = read(right, true).values().iterator().next();
        e.getView().setRepairCost((int) cfg("anvil-levels-per-level", 6) * lvl + (read(left, true).isEmpty() ? 0 : 2));
        e.getView().setRepairItemCountCost(1);
    }

    // ---------------------------------------------------------------- effects

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity victim) || victim instanceof ArmorStand) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK && e.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) return;
        if (cutting.contains(p.getUniqueId())) return; // a Cleave splash: no procs off it
        Map<String, Integer> ench = read(p.getInventory().getItemInMainHand(), false);
        if (ench.isEmpty()) return;
        procs(p, victim, e, ench, charged(p), true); // spam-clicking doesn't proc
    }

    /**
     * 1.2.27: the same on a BOW, carried by the arrow (RangerGear copies the bow's enchants onto it when it's shot;
     * `full` = drawn at least 90%, like a charged swing). Lifesteal heals off the arrow's damage. Cleave is melee only.
     */
    void applyRanged(Player p, LivingEntity victim, EntityDamageByEntityEvent e, String data, boolean full) {
        Map<String, Integer> ench = new LinkedHashMap<>();
        for (String part : data.split(";")) {
            String[] kv = part.split("=");
            if (kv.length != 2 || !DEFS.containsKey(kv[0]) || DEFS.get(kv[0]).kind != Kind.MELEE) continue;
            try { ench.put(kv[0], Integer.parseInt(kv[1])); } catch (NumberFormatException ignored) { }
        }
        procs(p, victim, e, ench, full, false);
    }

    /** Every weapon enchant on one hit (melee or an arrow). */
    void procs(Player p, LivingEntity victim, EntityDamageByEntityEvent e, Map<String, Integer> ench, boolean full, boolean melee) {
        Integer x = ench.get("executioner"); // first, so the procs below see the bigger hit
        AttributeInstance vmax = victim.getAttribute(Attribute.MAX_HEALTH);
        if (x != null && vmax != null && victim.getHealth() < vmax.getValue() * cfg("executioner.below", 0.3)) {
            e.setDamage(e.getDamage() * (1 + cfg("executioner.bonus-per-level", 0.12) * x));
            victim.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, victim.getLocation().add(0, 1, 0), 4, 0.2, 0.3, 0.2, 0.05);
        }
        Integer t = ench.get("thunderstrike");
        if (t != null && full && random.nextDouble() < cfg("thunderstrike.chance", 0.08)
                && thunderReady.getOrDefault(p.getUniqueId(), 0L) <= System.currentTimeMillis()) {
            thunderReady.put(p.getUniqueId(), System.currentTimeMillis() + (long) (cfg("thunderstrike.cooldown-seconds", 8) * 1000));
            victim.getWorld().strikeLightningEffect(victim.getLocation());
            e.setDamage(e.getDamage() + cfg("thunderstrike.damage", 4));
        }
        Integer f = ench.get("frostbite");
        if (f != null && full && random.nextDouble() < cfg("frostbite.chance-per-level", 0.15) * f) {
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40 + 20 * f, f - 1));
            victim.getWorld().spawnParticle(Particle.SNOWFLAKE, victim.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.02);
            victim.getWorld().playSound(victim.getLocation(), Sound.BLOCK_GLASS_BREAK, 0.5f, 1.8f);
        }
        Integer v = ench.get("venom");
        if (v != null && full && random.nextDouble() < cfg("venom.chance-per-level", 0.15) * v) {
            victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 60, v - 1));
            victim.getWorld().spawnParticle(Particle.ITEM_SLIME, victim.getLocation().add(0, 1, 0), 8, 0.3, 0.4, 0.3, 0.02);
        }
        Integer b = ench.get("bleed");
        if (b != null && full && random.nextDouble() < cfg("bleed.chance-per-level", 0.12) * b) {
            bleeding.put(victim.getUniqueId(), new int[]{(int) cfg("bleed.seconds", 3) + b});
            bledBy.put(victim.getUniqueId(), p.getUniqueId());
            victim.getWorld().spawnParticle(Particle.BLOCK, victim.getLocation().add(0, 1, 0), 14, 0.3, 0.4, 0.3, 0, Material.REDSTONE_BLOCK.createBlockData());
        }
        Integer c = ench.get("cleave");
        if (c != null && melee && full) cleave(p, victim, e.getDamage() * cfg("cleave.share-per-level", 0.25) * c);
        Integer l = ench.get("lifesteal");
        if (l != null && !p.isDead()) {
            // 1.2.52: against PLAYERS it only heals on every 5th hit you land on players (it was every hit); mobs: every hit
            if (victim instanceof Player) {
                int every = Math.max(1, (int) cfg("lifesteal.pvp-every-hits", 5));
                int n = pvpHits.merge(p.getUniqueId(), 1, Integer::sum);
                if (n < every) {
                    p.sendActionBar(net.kyori.adventure.text.Component.text("Lifesteal " + n + "/" + every, net.kyori.adventure.text.format.NamedTextColor.DARK_RED));
                    return;
                }
                pvpHits.put(p.getUniqueId(), 0);
            }
            double heal = Math.min(cfg("lifesteal.max-per-hit", 3), e.getFinalDamage() * cfg("lifesteal.per-level", 0.08) * l);
            AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
            if (heal > 0 && max != null) {
                p.setHealth(Math.min(max.getValue(), p.getHealth() + heal));
                if (victim instanceof Player) {
                    p.sendActionBar(net.kyori.adventure.text.Component.text("Lifesteal! +" + String.format(Locale.ROOT, "%.1f", heal / 2) + " hearts", net.kyori.adventure.text.format.NamedTextColor.RED));
                    p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 2, 0), 2, 0.3, 0.2, 0.3, 0);
                }
            }
        }
    }

    /** Cleave: the other things within reach of your target take a share of the hit (never you, your pets or armor stands). */
    void cleave(Player p, LivingEntity target, double dmg) {
        if (dmg <= 0) return;
        double r = cfg("cleave.radius", 2.5);
        cutting.add(p.getUniqueId());
        try {
            for (org.bukkit.entity.Entity n : target.getNearbyEntities(r, 1.5, r)) {
                if (!(n instanceof LivingEntity le) || n.equals(p) || n instanceof ArmorStand || le.isDead()) continue;
                if (n instanceof org.bukkit.entity.Tameable tm && p.equals(tm.getOwner())) continue;
                if (le.isInvisible()) continue; // boss hitboxes and model bodies (several would hand one boss the splash many times)
                le.damage(dmg, p);
            }
        } finally { cutting.remove(p.getUniqueId()); }
        target.getWorld().spawnParticle(Particle.SWEEP_ATTACK, target.getLocation().add(0, 1, 0), 2, 0.6, 0.1, 0.6, 0);
    }

    /** Arrowguard (projectiles) and Thornhide (melee back at the attacker). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getDamager() instanceof org.bukkit.entity.Projectile) {
            int a = level(p.getInventory().getChestplate(), "arrowguard");
            if (a > 0) e.setDamage(e.getDamage() * (1 - cfg("arrowguard.reduction-per-level", 0.1) * a));
            return;
        }
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK || !(e.getDamager() instanceof LivingEntity att) || att.equals(p)) return;
        int th = level(p.getInventory().getLeggings(), "thornhide");
        if (th <= 0 || cutting.contains(p.getUniqueId())) return;
        double back = e.getDamage() * cfg("thornhide.reflect-per-level", 0.1) * th;
        if (back <= 0) return;
        cutting.add(p.getUniqueId()); // (never ping-pong between two Thornhide wearers)
        try { att.damage(back, p); } finally { cutting.remove(p.getUniqueId()); }
        p.getWorld().spawnParticle(Particle.CRIT, att.getLocation().add(0, 1, 0), 6, 0.3, 0.4, 0.3, 0.1);
    }

    /** Clearsight: Blindness and Darkness never land on you (hidden ambient ones, the Abyss's dark and cutscenes, still do). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEffect(org.bukkit.event.entity.EntityPotionEffectEvent e) {
        if (!(((org.bukkit.event.entity.EntityEvent) e).getEntity() instanceof Player p) || e.getNewEffect() == null) return;
        PotionEffectType t = e.getNewEffect().getType();
        if (!t.equals(PotionEffectType.BLINDNESS) && !t.equals(PotionEffectType.DARKNESS)) return;
        if (e.getNewEffect().isAmbient() && !e.getNewEffect().hasParticles()) return;
        if (level(p.getInventory().getHelmet(), "clearsight") > 0) e.setCancelled(true);
    }

    /** Autosmelt: raw ores from your pickaxe drop as ingots. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrops(org.bukkit.event.block.BlockDropItemEvent e) {
        if (level(e.getPlayer().getInventory().getItemInMainHand(), "autosmelt") <= 0) return;
        for (org.bukkit.entity.Item it : e.getItems()) {
            ItemStack st = it.getItemStack();
            Material to = switch (st.getType()) { case RAW_IRON -> Material.IRON_INGOT; case RAW_GOLD -> Material.GOLD_INGOT; case RAW_COPPER -> Material.COPPER_INGOT; default -> null; };
            if (to == null || st.hasItemMeta() && st.getItemMeta().hasDisplayName()) continue;
            it.setItemStack(new ItemStack(to, st.getAmount()));
        }
    }

    private static boolean charged(Player p) {
        try { return p.getAttackCooldown() > 0.85f; } catch (RuntimeException e) { return true; }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p)) return;
        if (level(p.getInventory().getBoots(), "featherstep") > 0) e.setDamage(e.getDamage() * (1 - cfg("featherstep.fall-reduction", 0.35)));
    }

    @EventHandler(ignoreCancelled = true)
    public void onTrample(PlayerInteractEvent e) {
        if (e.getAction() == Action.PHYSICAL && e.getClickedBlock() != null && e.getClickedBlock().getType() == Material.FARMLAND
                && level(e.getPlayer().getInventory().getBoots(), "featherstep") > 0) e.setCancelled(true);
    }

    private final java.util.Set<UUID> veining = new java.util.HashSet<>();

    static boolean ore(Material m) { return m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS; }

    /** deepslate_iron_ore and iron_ore are one vein. */
    static String family(Material m) { return m.name().replace("DEEPSLATE_", ""); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMine(org.bukkit.event.block.BlockBreakEvent e) {
        Player p = e.getPlayer();
        org.bukkit.block.Block start = e.getBlock();
        if (!p.isSneaking() || veining.contains(p.getUniqueId()) || !ore(start.getType())) return;
        if (p.getGameMode() == org.bukkit.GameMode.CREATIVE || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (level(tool, "veinminer") <= 0) return;
        if (plugin.getConfig().getStringList("enchants.veinminer.exclude").contains(start.getType().name())) return;
        String fam = family(start.getType());
        int max = (int) cfg("veinminer.max-blocks", 32);
        java.util.List<org.bukkit.block.Block> vein = new ArrayList<>();
        java.util.Set<org.bukkit.block.Block> seen = new java.util.HashSet<>(List.of(start));
        java.util.ArrayDeque<org.bukkit.block.Block> queue = new java.util.ArrayDeque<>(List.of(start));
        while (!queue.isEmpty() && vein.size() < max) {
            org.bukkit.block.Block b = queue.poll();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                org.bukkit.block.Block n = b.getRelative(dx, dy, dz);
                if (!seen.add(n) || !ore(n.getType()) || !family(n.getType()).equals(fam)) continue;
                if (vein.size() < max) { vein.add(n); queue.add(n); }
            }
        }
        if (vein.isEmpty()) return;
        // the next tick, after this block is gone: each one as if the player broke it (events, drops, durability)
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            veining.add(p.getUniqueId());
            try {
                for (org.bukkit.block.Block b : vein) {
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    if (level(hand, "veinminer") <= 0) break; // swapped away or the pickaxe broke
                    if (!family(b.getType()).equals(fam)) continue;
                    p.breakBlock(b);
                }
            } finally { veining.remove(p.getUniqueId()); }
        });
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        thunderReady.remove(id); pvpHits.remove(id);
        vitality(e.getPlayer(), 0); swift(e.getPlayer(), 0);
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            vitality(p, level(p.getInventory().getChestplate(), "vitality"));
            ItemStack boots = p.getInventory().getBoots();
            swift(p, level(boots, "swiftstride"));
            if (level(boots, "tidecaller") > 0 && p.isInWater()) p.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 40, 0, true, false, true));
        }
        // Bleed: 1 damage a second while it lasts (from whoever cut them, so kills are credited)
        for (var it = bleeding.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            org.bukkit.entity.Entity ent = Bukkit.getEntity(en.getKey());
            if (!(ent instanceof LivingEntity le) || le.isDead() || --en.getValue()[0] < 0) { it.remove(); bledBy.remove(en.getKey()); continue; }
            UUID byId = bledBy.get(en.getKey());
            Player by = byId == null ? null : Bukkit.getPlayer(byId);
            if (by != null && by.getWorld().equals(le.getWorld())) {
                cutting.add(by.getUniqueId()); // (the bleed's own damage never procs anything)
                try { le.damage(cfg("bleed.damage", 1), by); } finally { cutting.remove(by.getUniqueId()); }
            } else le.damage(cfg("bleed.damage", 1));
            le.getWorld().spawnParticle(Particle.BLOCK, le.getLocation().add(0, 1, 0), 6, 0.2, 0.3, 0.2, 0, Material.REDSTONE_BLOCK.createBlockData());
        }
    }

    /** Swiftstride: +5% movement speed a level (a modifier refreshed every second, taken off when the boots come off). */
    void swift(Player p, int lvl) {
        AttributeInstance sp = p.getAttribute(Attribute.MOVEMENT_SPEED);
        if (sp == null) return;
        double want = lvl * cfg("swiftstride.speed-per-level", 0.05);
        AttributeModifier have = null;
        for (AttributeModifier m : sp.getModifiers()) if (swiftKey.equals(m.getKey())) have = m;
        if (have != null && (lvl <= 0 || Math.abs(have.getAmount() - want) > 1e-9)) { sp.removeModifier(have); have = null; }
        if (lvl > 0 && have == null) sp.addModifier(new AttributeModifier(swiftKey, want, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
    }

    /**
     * 1.2.46: Vitality on the chestplate = Regeneration (level I = Regeneration I, II = II), refreshed every second while
     * worn (ambient, no particles). It used to be +2 max health a level as a player modifier, which persists in player
     * data: that old modifier is always taken off here.
     */
    void vitality(Player p, int lvl) {
        AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) for (AttributeModifier m : new ArrayList<>(max.getModifiers())) if (vitalityKey.equals(m.getKey())) {
            max.removeModifier(m);
            if (p.getHealth() > max.getValue()) p.setHealth(max.getValue());
        }
        if (p.isDead()) return;
        if (lvl <= 0) { // took the chestplate off: ours goes (ambient, no particles, at most 10 s left), a real potion stays
            PotionEffect r = p.getPotionEffect(PotionEffectType.REGENERATION);
            if (r != null && r.isAmbient() && !r.hasParticles() && r.getDuration() <= 200 && r.getAmplifier() <= 1) p.removePotionEffect(PotionEffectType.REGENERATION);
            return;
        }
        int amp = Math.max(0, Math.min(lvl, (int) cfg("vitality.max-regeneration-level", 2)) - 1);
        PotionEffect have = p.getPotionEffect(PotionEffectType.REGENERATION);
        // never cut short a stronger one (a potion, a golden apple), and only top ours up when it's nearly out: re-adding
        // it every second would restart Regeneration's heal timer and heal far faster than the real effect
        if (have != null && (have.getAmplifier() > amp || have.getAmplifier() == amp && have.getDuration() > 30)) return;
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, amp, true, false, true));
    }

    // ---------------------------------------------------------------- boss drops

    /** One boss defeat: roll that boss's books for this player. */
    List<ItemStack> bossDrop(Player p, String entry) {
        List<ItemStack> got = new ArrayList<>();
        List<String> rolls = new ArrayList<>(plugin.getConfig().isList("enchants.boss-drops." + entry)
                ? plugin.getConfig().getStringList("enchants.boss-drops." + entry) : DEFAULT_DROPS.getOrDefault(entry, List.of()));
        rolls.addAll(plugin.getConfig().isList("enchants.new-boss-drops." + entry)
                ? plugin.getConfig().getStringList("enchants.new-boss-drops." + entry) : NEW_DROPS.getOrDefault(entry, List.of()));
        for (String r : rolls) {
            String[] s = r.split(":");
            if (s.length != 3) continue;
            try {
                if (random.nextDouble() >= Double.parseDouble(s[2])) continue;
                ItemStack b = book(s[0], Integer.parseInt(s[1]));
                if (b != null) got.add(b);
            } catch (NumberFormatException ignored) { }
        }
        for (ItemStack b : got) {
            p.getInventory().addItem(b).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
            p.sendMessage(ChatColor.LIGHT_PURPLE + "✦ The boss dropped a book: " + b.getItemMeta().getDisplayName());
        }
        if (!got.isEmpty()) p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
        return got;
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("items.fenchant")) { sender.sendMessage(ChatColor.RED + "You don't have permission to do that."); return true; }
        String sub = args.length == 0 ? "list" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "give" -> { // /fenchant give <id> [level] [amount] [player]
                if (args.length < 2 || !DEFS.containsKey(args[1].toLowerCase(Locale.ROOT))) { sender.sendMessage(ChatColor.RED + "/fenchant give <" + String.join("|", DEFS.keySet()) + "> [level] [amount] [player]"); return true; }
                int lvl = args.length > 2 ? parse(args[2], 1) : 1, amount = args.length > 3 ? Math.max(1, Math.min(64, parse(args[3], 1))) : 1;
                Player to = args.length > 4 ? Bukkit.getPlayerExact(args[4]) : sender instanceof Player sp ? sp : null;
                if (to == null) { sender.sendMessage(ChatColor.RED + "Player not found."); return true; }
                for (int i = 0; i < amount; i++) to.getInventory().addItem(book(args[1].toLowerCase(Locale.ROOT), lvl)).values().forEach(l -> to.getWorld().dropItemNaturally(to.getLocation(), l));
                sender.sendMessage(ChatColor.GREEN + "Gave " + amount + " " + DEFS.get(args[1].toLowerCase(Locale.ROOT)).name + " " + roman(lvl) + " book(s) to " + to.getName() + ".");
            }
            case "bossdrop" -> { // console: /fenchant bossdrop <player> <index entry id>
                if (args.length < 3) { sender.sendMessage(ChatColor.RED + "/fenchant bossdrop <player> <boss entry>"); return true; }
                Player to = Bukkit.getPlayerExact(args[1]);
                if (to != null && plugin.getConfig().getBoolean("enchants.boss-drops-enabled", true)) bossDrop(to, args[2].toLowerCase(Locale.ROOT));
                if (to != null && plugin.trinkets != null) plugin.trinkets.bossDrop(to, args[2].toLowerCase(Locale.ROOT)); // 1.2.28: the Gladiator's Crest
            }
            default -> {
                sender.sendMessage(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Boss enchants " + ChatColor.GRAY + "(/fenchant give <id> [level] [amount] [player])");
                for (Def d : DEFS.values()) sender.sendMessage(d.color + " " + d.id + ChatColor.GRAY + " (max " + roman(d.max) + "): " + d.desc);
            }
        }
        return true;
    }

    private static int parse(String s, int def) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; } }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("give", "list", "bossdrop");
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) return new ArrayList<>(DEFS.keySet());
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) return List.of("1", "2");
        return null;
    }
}
