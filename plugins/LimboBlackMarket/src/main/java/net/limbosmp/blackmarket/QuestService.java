package net.limbosmp.blackmarket;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.UUID;

import net.limbosmp.blackmarket.QuestDataManager.PlayerQuestData;
import net.limbosmp.blackmarket.QuestRegistry.Objective;
import net.limbosmp.blackmarket.QuestRegistry.Quest;
import net.limbosmp.blackmarket.QuestRegistry.Step;

/**
 * All quest logic. Rules:
 *  - One active quest at a time.
 *  - Nothing counts until you ACCEPT a quest (from the Quest Book menu).
 *  - Steps happen in order; only the current step's objective counts.
 *  - Abandoning wipes progress but doesn't start a cooldown.
 *  - Completing starts that quest's cooldown (or locks it forever if one-time).
 */
public class QuestService {

    public enum AcceptCheck { OK, HAS_ACTIVE, ON_COOLDOWN, ALREADY_COMPLETED }

    private final LimboBlackMarket plugin;
    private final QuestRegistry registry;
    private final QuestDataManager data;

    public QuestService(LimboBlackMarket plugin, QuestRegistry registry, QuestDataManager data) {
        this.plugin = plugin;
        this.registry = registry;
        this.data = data;
    }

    public QuestRegistry getRegistry() {
        return registry;
    }

    /**
     * Gets a player's quest data, clearing an active quest that no longer
     * exists (renamed/removed in an update) or whose saved step is past the
     * quest's end (steps shortened). Without this the player would be stuck:
     * "finish or abandon your current quest" but nothing to abandon.
     */
    private PlayerQuestData dataFor(UUID playerId) {
        PlayerQuestData d = data.get(playerId);
        if (d.activeQuest != null) {
            Quest quest = registry.get(d.activeQuest);
            if (quest == null || d.step >= quest.steps().size()) {
                d.clearActive();
                data.markDirty();
            }
        }
        return d;
    }

    public PlayerQuestData getData(UUID playerId) {
        return dataFor(playerId);
    }

    public Quest getActive(UUID playerId) {
        return registry.get(dataFor(playerId).activeQuest);
    }

    public AcceptCheck check(UUID playerId, Quest quest) {
        PlayerQuestData d = dataFor(playerId);
        if (d.activeQuest != null) return AcceptCheck.HAS_ACTIVE;
        if (quest.oneTime() && d.completedOnce.contains(quest.id())) return AcceptCheck.ALREADY_COMPLETED;
        if (cooldownRemaining(playerId, quest) > 0) return AcceptCheck.ON_COOLDOWN;
        return AcceptCheck.OK;
    }

    public long cooldownRemaining(UUID playerId, Quest quest) {
        Long last = dataFor(playerId).lastCompleted.get(quest.id());
        if (last == null) return 0;
        return Math.max(0, last + registry.cooldownMillis(quest) - System.currentTimeMillis());
    }

    public boolean accept(Player player, Quest quest) {
        if (check(player.getUniqueId(), quest) != AcceptCheck.OK) return false;

        PlayerQuestData d = dataFor(player.getUniqueId());
        d.clearActive();
        d.activeQuest = quest.id();
        data.saveNow();

        player.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "Quest accepted: "
                + quest.difficulty().color + quest.name());
        player.sendMessage(ChatColor.GRAY + "Step 1/" + quest.steps().size() + ": "
                + ChatColor.YELLOW + quest.steps().get(0).description());
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
        return true;
    }

    public void abandon(Player player) {
        PlayerQuestData d = dataFor(player.getUniqueId());
        Quest quest = registry.get(d.activeQuest);
        if (quest == null) return;

        d.clearActive();
        data.saveNow();
        player.sendMessage(ChatColor.RED + "You abandoned " + quest.name() + ". Its progress was lost.");
    }

    // ---------- progress hooks (called from QuestProgressListener) ----------

    public void onMobKill(Player killer, org.bukkit.entity.LivingEntity victim) {
        Step step = currentStep(killer);
        if (step == null || step.objective() != Objective.KILL_MOB || !step.mobs().contains(victim.getType())) return;
        boolean isCustomMob = victim.getScoreboardTags().stream().anyMatch(QuestRegistry.CUSTOM_MOB_TAGS::contains);
        if (step.requiredTag() == null) {
            if (isCustomMob) return; // custom mobs have their own steps
        } else if (!victim.getScoreboardTags().contains(step.requiredTag())) {
            return;
        }
        addProgress(killer);
    }

    public void onPlayerKill(Player killer, Player victim) {
        Step step = currentStep(killer);
        if (step == null || step.objective() != Objective.KILL_PLAYER) return;

        if (tierOf(victim.getUniqueId()) < step.minTier()) return;

        if (plugin.getAntiFarmManager().isFarmedKill(killer.getUniqueId(), victim.getUniqueId())) {
            killer.sendMessage(ChatColor.GRAY + "That kill didn't count for your quest — you killed "
                    + victim.getName() + " too recently.");
            return;
        }

        PlayerQuestData d = dataFor(killer.getUniqueId());
        if (step.uniqueVictims()) {
            if (!d.victims.add(victim.getUniqueId().toString())) {
                killer.sendMessage(ChatColor.GRAY + victim.getName()
                        + " already counted for this step — it needs different players.");
                return;
            }
        }
        addProgress(killer);
    }

    public void onMine(Player player, Material block) {
        Step step = currentStep(player);
        if (step == null || step.objective() != Objective.MINE || step.block() != block) return;
        addProgress(player);
    }

    public void onRaidWin(Player player) {
        Step step = currentStep(player);
        if (step == null || step.objective() != Objective.WIN_RAID) return;
        addProgress(player);
    }

    // ---------- internals ----------

    private Step currentStep(Player player) {
        PlayerQuestData d = dataFor(player.getUniqueId());
        Quest quest = registry.get(d.activeQuest);
        if (quest == null || d.step >= quest.steps().size()) return null;
        return quest.steps().get(d.step);
    }

    private void addProgress(Player player) {
        PlayerQuestData d = dataFor(player.getUniqueId());
        Quest quest = registry.get(d.activeQuest);
        Step step = quest.steps().get(d.step);

        d.progress++;
        data.markDirty();

        if (d.progress < step.count()) {
            actionBar(player, quest.difficulty().color + quest.name() + ChatColor.GRAY + " | "
                    + ChatColor.WHITE + step.description() + ChatColor.GRAY + " (" + d.progress + "/" + step.count() + ")");
            return;
        }

        // Step finished.
        d.step++;
        d.progress = 0;
        d.victims.clear();

        if (d.step >= quest.steps().size()) {
            complete(player, d, quest);
            return;
        }

        data.saveNow();
        Step next = quest.steps().get(d.step);
        player.sendMessage(ChatColor.GREEN + "Step complete! " + ChatColor.GRAY + "Step "
                + (d.step + 1) + "/" + quest.steps().size() + ": " + ChatColor.YELLOW + next.description());
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
    }

    private void complete(Player player, PlayerQuestData d, Quest quest) {
        d.clearActive();
        d.lastCompleted.put(quest.id(), System.currentTimeMillis());
        if (quest.oneTime()) d.completedOnce.add(quest.id());
        data.saveNow(); // save BEFORE giving the reward, so a crash can't let it be claimed twice

        quest.reward().accept(player);

        player.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "QUEST COMPLETE: " + quest.difficulty().color
                + quest.name() + ChatColor.GRAY + " (reward: " + quest.rewardText() + ")");
        player.showTitle(Title.title(
                legacy(ChatColor.GOLD + "" + ChatColor.BOLD + "QUEST COMPLETE"),
                legacy(quest.difficulty().color + quest.name()),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(700))));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);

        if (quest.difficulty() == QuestRegistry.Difficulty.EXTREME) {
            Bukkit.broadcastMessage(ChatColor.DARK_PURPLE + "" + ChatColor.BOLD + player.getName()
                    + " completed the " + quest.name() + "!");
        }
    }

    private int tierOf(UUID playerId) {
        String tier = plugin.getTierManager().getTier(playerId);
        if (tier == null) return 0;
        try {
            return Integer.parseInt(tier.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void actionBar(Player player, String text) {
        player.sendActionBar(legacy(text));
    }

    private static net.kyori.adventure.text.Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text);
    }

    public static String formatDuration(long millis) {
        long totalMinutes = millis / 60_000L;
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0) return hours + "h " + minutes + "m";
        if (minutes > 0) return minutes + "m";
        return "less than a minute";
    }

    /** For the menu: "Repeatable every 24h" / "One-time quest" / etc. */
    public String repeatText(Quest quest) {
        if (quest.oneTime()) return "One-time quest";
        long cd = registry.cooldownMillis(quest);
        if (cd <= 0) return "Repeatable anytime";
        double hours = cd / 3_600_000.0;
        return "Repeatable every " + (hours == Math.floor(hours) ? String.valueOf((long) hours) : String.valueOf(hours)) + "h";
    }
}
