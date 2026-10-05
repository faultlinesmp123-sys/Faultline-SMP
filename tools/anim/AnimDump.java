package net.faultlinesmp.bosses;

import net.faultlinesmp.bosses.FaultlineBosses.Pose;
import java.util.*;
import java.util.function.IntFunction;

/**
 * Prints Rocco's, Werner's and Swarm's animations as JSON (every tick of every move), for tools/vendetta_anim_preview.py.
 * Built and run by tools/anim/preview.sh against the compiled FaultlineBosses classes.
 */
public final class AnimDump {
    static final LinkedHashMap<String, Object[]> ANIMS = new LinkedHashMap<>();
    static void add(String skin, String name, int len, IntFunction<Pose> f) { ANIMS.put(name, new Object[]{skin, len, f}); }
    static final LinkedHashMap<String, Object[]> TIGER = new LinkedHashMap<>();
    static void tiger(String name, int len, IntFunction<float[]> f) { TIGER.put(name, new Object[]{len, f}); }

    public static void main(String[] a) {
        add("rocco", "rocco_stance", 30, VendettaAnims::roccoStance);
        add("rocco", "rocco_walk", 30, t -> VendettaAnims.roccoGuardWalk(t));
        add("rocco", "rocco_kick", 24, t -> VendettaAnims.roccoKick(t));
        add("rocco", "rocco_punch", 18, t -> VendettaAnims.roccoPunch(t, false));
        add("rocco", "rocco_hook", 18, t -> VendettaAnims.roccoPunch(t, true));
        add("rocco", "rocco_uppercut", 18, t -> VendettaAnims.roccoUppercut(t));
        add("rocco", "rocco_guard", 30, VendettaAnims::roccoGuard);
        add("rocco", "rocco_whistle", 26, t -> VendettaAnims.roccoWhistle(t));
        add("rocco", "rocco_charge", 50, t -> VendettaAnims.roccoCharge(t, t / 50f));
        add("rocco", "rocco_smash", 24, t -> VendettaAnims.roccoGroundSmash(t));
        add("rocco", "rocco_leap", 14, t -> VendettaAnims.roccoLeapStart(t));
        add("rocco", "rocco_air", 40, t -> VendettaAnims.roccoAirborne(t, t / 40f));
        add("rocco", "rocco_land", 40, t -> VendettaAnims.roccoSlamLand(t));
        add("rocco", "rocco_swear", 26, t -> VendettaAnims.roccoSwear(t));
        add("rocco", "rocco_phase", 60, t -> VendettaAnims.roccoPhaseChange(t));
        add("rocco", "rocco_defeat", 120, t -> VendettaAnims.roccoDefeat(t));
        add("rocco", "rocco_arrive", 70, t -> VendettaAnims.roccoArrive(t));
        add("werner", "werner_stance", 30, VendettaAnims::wernerStance);
        add("werner", "werner_plexus", 16, t -> VendettaAnims.wernerPlexus(t));
        add("werner", "werner_dome", 18, t -> VendettaAnims.wernerDome(t));
        add("werner", "werner_gut", 26, t -> VendettaAnims.wernerGut(t));
        add("werner", "werner_awaits", 30, t -> VendettaAnims.wernerAwaits(t));
        add("werner", "werner_seize", 30, VendettaAnims::wernerSeize);
        add("werner", "werner_counter", 14, t -> VendettaAnims.wernerCounter(t));
        add("swarm", "swarm_idle", 120, t -> Below.idlePose(t, true));
        add("swarm", "swarm_talk", 60, Below::talkPose);
        add("swarm", "swarm_take", 40, Below::takePose);
        add("swarm", "swarm_dig", 18, Below::swingPose);
        add("swarm", "swarm_step", 14, t -> Below.stepPose(t, 1f));
        add("swarm", "swarm_draw", 110, Below::drawPose);
        add("swarm", "swarm_bedrock", 24, Below::slamPose);
        add("swarm", "swarm_place", 20, Below::placePose);
        add("swarm", "swarm_bow", 30, Below::bowPose);
        add("explorer", "explorer_idle", 80, ExplorerAnims::idle);
        add("explorer", "explorer_walk", 40, t -> ExplorerAnims.walk(t, 1f));
        add("explorer", "explorer_cleave", 32, t -> ExplorerAnims.cleave(t));
        add("explorer", "explorer_sweep", 36, t -> ExplorerAnims.sweep(t));
        add("explorer", "explorer_charge_windup", 24, t -> ExplorerAnims.chargeWindup(t));
        add("explorer", "explorer_charge", 20, ExplorerAnims::charge);
        add("explorer", "explorer_crash", 16, t -> ExplorerAnims.crash(t));
        add("explorer", "explorer_stunned", 60, ExplorerAnims::stunned);
        add("explorer", "explorer_pinned", 30, t -> ExplorerAnims.pinned(t));
        add("explorer", "explorer_roar", 60, t -> ExplorerAnims.roar(t));
        add("explorer", "explorer_leap", 20, t -> ExplorerAnims.leap(t));
        add("explorer", "explorer_slam", 24, t -> ExplorerAnims.slam(t));
        add("explorer", "explorer_blade_throw", 22, t -> ExplorerAnims.throwBlade(t));
        add("explorer", "explorer_vanish", 14, t -> ExplorerAnims.vanish(t));
        add("explorer", "explorer_ambush", 18, t -> ExplorerAnims.ambush(t));
        add("explorer", "explorer_calldown", 40, t -> ExplorerAnims.callDown(t));
        add("explorer", "explorer_counter", 12, t -> ExplorerAnims.counter(t));
        add("explorer", "explorer_dismiss", 40, t -> ExplorerAnims.dismiss(t));
        add("explorer", "explorer_defeat", 90, t -> ExplorerAnims.defeat(t));
        add("explorer", "explorer_awaken", 70, t -> ExplorerAnims.awaken(t));
        add("explorer", "explorer_dig_pick", 24, ExplorerAnims::dig);
        add("explorer", "explorer_straighten", 40, t -> ExplorerAnims.straighten(t));
        add("explorer", "explorer_snap", 30, t -> ExplorerAnims.snap(t));
        add("explorer", "explorer_useless", 60, t -> ExplorerAnims.useless(t));
        add("explorer", "explorer_slash_blade", 22, t -> ExplorerAnims.slash(t));
        add("explorer", "explorer_dash_blade", 12, t -> ExplorerAnims.dash(t));
        add("explorer", "explorer_cross_blade", 26, t -> ExplorerAnims.cross(t));
        add("explorer", "explorer_summon_blade", 30, t -> ExplorerAnims.summon(t));
        add("explorer", "explorer_starburst", 40, t -> ExplorerAnims.starburst(t));
        tiger("tiger_idle", 60, t -> ExplorerAnims.tigerIdle(t));
        tiger("tiger_sit", 30, t -> ExplorerAnims.tigerSit(t));
        tiger("tiger_walk", 24, t -> ExplorerAnims.tigerWalk(t, 1f));
        tiger("tiger_run", 18, t -> ExplorerAnims.tigerWalk(t, 1.3f));
        tiger("tiger_roar", 24, t -> ExplorerAnims.tigerRoar(t));
        tiger("tiger_crouch", 14, ExplorerAnims::tigerCrouch);
        tiger("tiger_leap", 4, ExplorerAnims::tigerLeap);
        tiger("tiger_land", 12, t -> ExplorerAnims.tigerLand(t));
        tiger("tiger_swipe", 18, t -> ExplorerAnims.tigerSwipe(t, true));
        tiger("tiger_die", 80, t -> ExplorerAnims.tigerDie(t));
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : ANIMS.entrySet()) {
            Object[] v = e.getValue();
            @SuppressWarnings("unchecked") IntFunction<Pose> f = (IntFunction<Pose>) v[2];
            if (!first) sb.append(","); first = false;
            sb.append("\"").append(e.getKey()).append("\":{\"skin\":\"").append(v[0]).append("\",\"frames\":[");
            for (int t = 0; t < (int) v[1]; t++) {
                Pose p = f.apply(t);
                if (t > 0) sb.append(",");
                sb.append("{\"drop\":").append(p.drop).append(",\"r\":[");
                for (int i = 0; i < 6; i++) sb.append(i > 0 ? "," : "").append("[").append(p.r[i][0]).append(",").append(p.r[i][1]).append(",").append(p.r[i][2]).append("]");
                sb.append("]}");
            }
            sb.append("]}");
        }
        for (var e : TIGER.entrySet()) { // the tiger: 12 numbers a frame (see ExplorerAnims)
            @SuppressWarnings("unchecked") IntFunction<float[]> f = (IntFunction<float[]>) e.getValue()[1];
            sb.append(",\"").append(e.getKey()).append("\":{\"skin\":\"tiger\",\"frames\":[");
            for (int t = 0; t < (int) e.getValue()[0]; t++) {
                float[] p = f.apply(t);
                if (t > 0) sb.append(",");
                sb.append("{\"t\":[");
                for (int i = 0; i < p.length; i++) sb.append(i > 0 ? "," : "").append(p[i]);
                sb.append("]}");
            }
            sb.append("]}");
        }
        System.out.println(sb.append("}"));
    }
}
