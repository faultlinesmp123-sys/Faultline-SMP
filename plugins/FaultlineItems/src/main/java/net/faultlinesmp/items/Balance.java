package net.faultlinesmp.items;

/**
 * THE ACCESSORY BALANCE PASS (Items 1.2.31). Server configs keep the values they were written with, so a value still
 * equal to its old default (from before this pass) is read as the new default; a value the owner changed on purpose
 * is kept. Old -> new:
 *   golden-ring.exp-multiplier 2.0 -> 1.5            discount-card.discount-percent 17.5 -> 10, drop-chance 0.10 -> 0.03
 *   life-jelly.heal-percent (was fixed 2%) -> 1%     shield-of-the-ocean.move-speed-boost 0.10 -> 0.05
 *   black-belt.speed-penalty 0.025 -> 0               bloody-wolf-scarf.damage-reduction 0.05 -> 0.03
 *   spelunker-amulet.mining-speed-bonus 0.02 -> 0.10  moon-stone.phantom-drop-chance 0.05 -> 0.02
 *   ranger-gear.windrunner-speed 1.2 -> 1.08          ranger-gear.oath-speed 0.2 -> 0.1
 *   trinkets.fang-heal 6 -> 4 (hostile mobs + players only, 3 s cooldown)
 *   harpy-ring.speed-bonus 0.075 -> 0.05, elytra-durability-save-chance 0.5 -> 0.3 (1.2.32)
 *   Ancient Core: +3 armor / +25% knockback resistance / +2 hearts -> +2 armor / +15% (ancient-core.*)
 */
final class Balance {
    private Balance() { }

    static double num(FaultlineItems p, String path, double oldDefault, double newDefault) {
        double v = p.getConfig().getDouble(path, newDefault);
        return Math.abs(v - oldDefault) < 1e-9 ? newDefault : v;
    }

    /** 0.075 -> "7.5", 0.1 -> "10". */
    static String pct(double fraction) {
        double v = Math.round(fraction * 1000) / 10.0;
        return v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v);
    }

    static String num(double v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(Math.round(v * 100) / 100.0);
    }
}
