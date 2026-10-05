package net.faultlinesmp.bosses;

import net.faultlinesmp.bosses.FaultlineBosses.Pose;

import static net.faultlinesmp.bosses.FaultlineBosses.*;
import static net.faultlinesmp.bosses.VendettaAnims.keys;
import static net.faultlinesmp.bosses.VendettaAnims.sin;

/**
 * The Lost Explorer's animations (pure functions of each move's own clock, like VendettaAnims). He's huge and heavy:
 * every swing has a long, readable wind-up (his hits take 40-55% of your health, so you need time to see them coming)
 * and a fast, brutal strike. Render them with tools/anim/preview.sh (skin "explorer").
 *
 * Conventions: arms/legs negative X = forward/up; right arm out = negative Z, left arm out = positive Z; body Y positive
 * turns his front toward HIS LEFT. The axe is in his right hand; the left hand joins it on the haft for two-handed blows.
 */
final class ExplorerAnims {
    private ExplorerAnims() { }

    /** Standing guard: axe held low across his body, both hands on the haft, slow heavy breathing. */
    static Pose idle(int t) {
        float b = sin(t * 0.06), s = sin(t * 0.031);
        return new Pose().set(ARM_R, 8 + b * 2, 42, -6).set(ARM_L, -28 + b * 2, 44, 10)
                .set(BODY, 5 + b * 1.5f, -8 + s * 2, 0).set(HEAD, 6 - b, 6, 0)
                .set(LEG_R, 2, 0, -4).set(LEG_L, -4, 0, 5).drop(-0.02f - Math.abs(b) * 0.02f);
    }

    /** A heavy, deliberate stride (a = how fast he's moving, 0-1). */
    static Pose walk(int t, float a) {
        float s = sin(t * 0.32) * 26 * a, bob = Math.abs(sin(t * 0.32));
        Pose p = idle(t);
        p.set(LEG_R, s, 0, -3).set(LEG_L, -s, 0, 3)
                .add(BODY, 6 * a, s * 0.12f, 0).add(ARM_R, -s * 0.15f, 0, 0).add(ARM_L, -s * 0.15f, 0, 0);
        p.drop = -0.05f * bob * a;
        return p;
    }

    /** CLEAVE (32 ticks, the axe lands at 18): raises the axe high over his head, then splits the ground in front of him. */
    static Pose cleave(float t) {
        Pose g = idle((int) t);
        Pose lift = new Pose().set(ARM_R, -150, 10, -10).set(ARM_L, -140, -10, 12).set(BODY, -8, 10, 0).set(HEAD, -10, -6, 0)
                .set(LEG_R, 8, 0, -4).set(LEG_L, -22, 0, 6);
        Pose high = new Pose().set(ARM_R, -178, 0, -6).set(ARM_L, -170, 0, 8).set(BODY, -16, 0, 0).set(HEAD, -14, 0, 0)
                .set(LEG_R, 12, 0, -4).set(LEG_L, -30, 0, 6).drop(0.04f);
        Pose strike = new Pose().set(ARM_R, -32, -6, -4).set(ARM_L, -28, 8, 6).set(BODY, 34, 0, 0).set(HEAD, 18, 0, 0)
                .set(LEG_R, 22, 0, -4).set(LEG_L, -44, 0, 6).drop(-0.3f);
        Pose hold = strike.copy().add(BODY, 2, 0, 0).add(HEAD, 4, 0, 0);
        return keys(t, new int[]{0, 8, 14, 18, 26, 32}, new Pose[]{g, lift, high, strike, hold, g}, "EESEE");
    }

    /** SWEEP (36 ticks, the spin hits 18-26): coils to his right, then whirls the axe all the way around (the fight turns his yaw). */
    static Pose sweep(float t) {
        Pose g = idle((int) t);
        Pose coil = new Pose().set(ARM_R, -20, 88, -40).set(ARM_L, -40, 60, 20).set(BODY, 10, -55, 0).set(HEAD, 6, 40, 0)
                .set(LEG_R, -18, 0, -10).set(LEG_L, 16, 0, 10).drop(-0.18f);
        Pose spin = new Pose().set(ARM_R, -25, -75, -35).set(ARM_L, -45, -45, 30).set(BODY, 6, 40, 0).set(HEAD, 0, -20, 0)
                .set(LEG_R, -10, 0, -14).set(LEG_L, 10, 0, 14).drop(-0.12f);
        Pose follow = new Pose().set(ARM_R, -12, -95, -20).set(ARM_L, -30, -60, 20).set(BODY, 14, 50, 0).set(HEAD, 8, -30, 0)
                .set(LEG_R, 6, 0, -8).set(LEG_L, -6, 0, 8).drop(-0.1f);
        return keys(t, new int[]{0, 12, 18, 26, 36}, new Pose[]{g, coil, spin, follow, g}, "ESEE");
    }

    /** CHARGE wind-up (24 ticks): drops low like a bull, axe dragged back, head down at you. */
    static Pose chargeWindup(float t) {
        Pose g = idle((int) t);
        Pose low = new Pose().set(ARM_R, 34, 0, -28).set(ARM_L, -64, 10, 24).set(BODY, 34, 0, 0).set(HEAD, -24, 0, 0)
                .set(LEG_R, -46, 0, -6).set(LEG_L, 36, 0, 6).drop(-0.32f);
        Pose shake = low.copy().add(BODY, sin(t * 1.3) * 2, 0, 0).add(HEAD, 0, sin(t * 1.7) * 3, 0);
        return t < 10 ? keys(t, new int[]{0, 10}, new Pose[]{g, low}, "E") : shake;
    }

    /** CHARGING: a full sprint, shoulder down, axe out in front like a ram. */
    static Pose charge(int t) {
        float s = sin(t * 0.85) * 48;
        return new Pose().set(LEG_R, s - 10, 0, -3).set(LEG_L, -s - 10, 0, 3)
                .set(ARM_R, -66, -10, -14).set(ARM_L, -58, 30, 18)
                .set(BODY, 34, s * 0.06f, 0).set(HEAD, -20, 0, 0)
                .drop(-0.12f - Math.abs(sin(t * 0.85)) * 0.08f);
    }

    /** CRASH into a pillar (16 ticks): head snaps back, staggers, then drops to one knee dazed (see stunned). */
    static Pose crash(float t) {
        Pose hit = new Pose().set(BODY, -30, 0, 0).set(HEAD, -40, 0, 0).set(ARM_R, 50, 0, -50).set(ARM_L, 50, 0, 50)
                .set(LEG_R, -30, 0, -6).set(LEG_L, 20, 0, 6).drop(0.05f);
        return keys(t, new int[]{0, 4, 16}, new Pose[]{charge(0), hit, stunned(0)}, "SE");
    }

    /** STUNNED: down on one knee, axe dropped beside him, head lolling, swaying (the window to drop a pillar on him). */
    static Pose stunned(int t) {
        float w = sin(t * 0.13), w2 = sin(t * 0.09);
        return new Pose().drop(-0.62f).set(LEG_R, -84, 0, -4).set(LEG_L, 76, 0, 6)
                .set(BODY, 24 + w * 4, w2 * 6, w * 3).set(HEAD, 32 + w2 * 8, w * 14, w2 * 8)
                .set(ARM_R, 18, 0, -18 + w * 4).set(ARM_L, -24, 0, 22);
    }

    /** PINNED under a fallen pillar (30 ticks): crushed flat to the ground, then shoving it off. */
    static Pose pinned(float t) {
        Pose flat = new Pose().drop(-1.0f).set(BODY, 78, 0, 0).set(HEAD, -40, 0, 0).set(LEG_R, -10, 0, -8).set(LEG_L, -4, 0, 8)
                .set(ARM_R, -150, 0, -40).set(ARM_L, -150, 0, 40);
        Pose push = new Pose().drop(-0.7f).set(BODY, 50, 0, 0).set(HEAD, -30, 0, 0).set(LEG_R, -70, 0, -6).set(LEG_L, 60, 0, 6)
                .set(ARM_R, -30, 0, -30).set(ARM_L, -30, 0, 30);
        return keys(t, new int[]{0, 18, 30}, new Pose[]{flat, flat, push}, "EE");
    }

    /** ROAR (60 ticks): rises, throws his arms wide, head back: a new phase. */
    static Pose roar(float t) {
        Pose rise = stunned(0);
        Pose open = new Pose().set(BODY, -22, 0, 0).set(HEAD, -38, 0, 0).set(ARM_R, 24, 0, -78).set(ARM_L, 24, 0, 78)
                .set(LEG_R, 0, 0, -12).set(LEG_L, 0, 0, 12).drop(0.02f);
        Pose shake = open.copy().add(HEAD, sin(t * 1.4) * 4, sin(t * 0.9) * 6, 0).add(BODY, sin(t * 1.1) * 2, 0, 0);
        if (t < 16) return keys(t, new int[]{0, 16}, new Pose[]{rise, open}, "E");
        if (t < 48) return shake;
        return keys(t, new int[]{48, 60}, new Pose[]{open, idle((int) t)}, "E");
    }

    /** LEAP (20 ticks): sinks deep, then springs up with the axe raised two-handed. */
    static Pose leap(float t) {
        Pose crouch = new Pose().drop(-0.45f).set(BODY, 30, 0, 0).set(HEAD, -16, 0, 0).set(LEG_R, -50, 0, -6).set(LEG_L, 40, 0, 6)
                .set(ARM_R, 40, 0, -30).set(ARM_L, 40, 0, 30);
        Pose up = new Pose().set(BODY, -14, 0, 0).set(HEAD, -10, 0, 0).set(LEG_R, -40, 0, -4).set(LEG_L, 30, 0, 4)
                .set(ARM_R, -175, 0, -6).set(ARM_L, -168, 0, 8);
        return keys(t, new int[]{0, 12, 20}, new Pose[]{idle(0), crouch, up}, "ES");
    }

    /** Mid-air: axe over his head, knees tucked. */
    static Pose airborne(int t) {
        return new Pose().set(BODY, -8, 0, 0).set(HEAD, 6, 0, 0).set(LEG_R, -60, 0, -6).set(LEG_L, -20, 0, 6)
                .set(ARM_R, -178, 0, -6).set(ARM_L, -172, 0, 8);
    }

    /** SLAM landing (24 ticks): smashes the axe down as he lands in a deep crouch. */
    static Pose slam(float t) {
        Pose land = new Pose().drop(-0.5f).set(BODY, 40, 0, 0).set(HEAD, 22, 0, 0).set(LEG_R, -56, 0, -8).set(LEG_L, 44, 0, 8)
                .set(ARM_R, -20, 0, -6).set(ARM_L, -16, 0, 8);
        return keys(t, new int[]{0, 3, 16, 24}, new Pose[]{airborne(0), land, land, idle(0)}, "SEE");
    }

    /** THROW the greatsword (22 ticks, released at 12): winds back over his shoulder and hurls it. */
    static Pose throwBlade(float t) {
        Pose g = idle((int) t);
        Pose back = new Pose().set(ARM_R, -165, 50, 10).set(ARM_L, -60, -20, 30).set(BODY, -6, -45, 0).set(HEAD, 0, 35, 0)
                .set(LEG_R, 16, 0, -6).set(LEG_L, -24, 0, 6);
        Pose release = new Pose().set(ARM_R, -70, -40, -10).set(ARM_L, 10, 0, 30).set(BODY, 18, 40, 0).set(HEAD, 8, -30, 0)
                .set(LEG_R, -20, 0, -6).set(LEG_L, 16, 0, 6).drop(-0.12f);
        return keys(t, new int[]{0, 9, 12, 22}, new Pose[]{g, back, release, g}, "ESE");
    }

    /** Catching the blade as it comes back (10 ticks). */
    static Pose catchBlade(float t) {
        Pose reach = new Pose().set(ARM_R, -120, -10, -20).set(BODY, -6, 10, 0).set(HEAD, -10, 0, 0);
        return keys(t, new int[]{0, 4, 10}, new Pose[]{idle(0), reach, idle(0)}, "SE");
    }

    /** SHADOW STEP: sinks into the dark (14 ticks), then the slash when he reappears behind you (18 ticks, hits at 6). */
    static Pose vanish(float t) {
        Pose low = new Pose().drop(-0.4f).set(BODY, 34, 0, 0).set(HEAD, 20, 0, 0).set(LEG_R, -50, 0, -6).set(LEG_L, 40, 0, 6)
                .set(ARM_R, 30, 0, -20).set(ARM_L, 30, 0, 20);
        return keys(t, new int[]{0, 14}, new Pose[]{idle(0), low}, "E");
    }

    static Pose ambush(float t) {
        Pose wind = new Pose().set(ARM_R, -120, 70, -30).set(ARM_L, -60, 40, 20).set(BODY, 4, -50, 0).set(HEAD, 0, 30, 0)
                .set(LEG_R, 14, 0, -6).set(LEG_L, -20, 0, 6).drop(-0.1f);
        Pose cut = new Pose().set(ARM_R, -70, -70, -50).set(ARM_L, -40, -30, 20).set(BODY, 16, 55, 0).set(HEAD, 6, -30, 0)
                .set(LEG_R, -20, 0, -6).set(LEG_L, 18, 0, 6).drop(-0.14f);
        return keys(t, new int[]{0, 4, 7, 18}, new Pose[]{vanish(14), wind, cut, idle(0)}, "ESE");
    }

    /** VOID RAIN (40 ticks): raises the axe to the black sky and calls it down. */
    static Pose callDown(float t) {
        Pose up = new Pose().set(ARM_R, -182, 0, -4).set(ARM_L, -40, 0, 30).set(BODY, -14, 0, 0).set(HEAD, -36, 0, 0)
                .set(LEG_R, 0, 0, -8).set(LEG_L, 0, 0, 8);
        Pose shake = up.copy().add(ARM_R, sin(t * 2.1) * 3, 0, 0).add(HEAD, sin(t * 1.3) * 2, 0, 0);
        return t < 12 ? keys(t, new int[]{0, 12}, new Pose[]{idle(0), up}, "E") : t < 32 ? shake : keys(t, new int[]{32, 40}, new Pose[]{up, idle(0)}, "E");
    }

    /** COUNTER (12 ticks): a backhand slash at whoever dared to hit him. */
    static Pose counter(float t) {
        Pose wind = new Pose().set(ARM_R, -100, 80, -20).set(BODY, 4, -40, 0).set(HEAD, 0, 30, 0);
        Pose cut = new Pose().set(ARM_R, -80, -60, -50).set(BODY, 12, 45, 0).set(HEAD, 4, -20, 0).drop(-0.06f);
        return keys(t, new int[]{0, 3, 5, 12}, new Pose[]{idle(0), wind, cut, idle(0)}, "SSE");
    }

    /** "You cannot defeat me...": looms over the fallen, axe planted, head tilted down at them. */
    static Pose loom(int t) {
        float b = sin(t * 0.05);
        return new Pose().set(ARM_R, -36, 20, -4).set(ARM_L, -42, 36, 8).set(BODY, 12 + b, 0, 0).set(HEAD, 30, 0, 6)
                .set(LEG_R, 0, 0, -6).set(LEG_L, 0, 0, 6);
    }

    /** "Get out of my sight." (40 ticks): points them away, then kicks (contact at 30). */
    static Pose dismiss(float t) {
        Pose point = new Pose().set(ARM_L, -92, -14, 0).set(ARM_R, -30, 20, -4).set(BODY, 0, -14, 0).set(HEAD, 8, 10, 0);
        Pose chamber = new Pose().set(LEG_R, 64, 0, -4).set(LEG_L, -8, 0, 4).set(BODY, -6, 0, 0).set(ARM_L, -40, 0, 30).set(ARM_R, -30, 0, -20);
        Pose kick = new Pose().set(LEG_R, -100, 0, 0).set(LEG_L, 12, 0, 4).set(BODY, -18, 0, 0).set(HEAD, 10, 0, 0)
                .set(ARM_L, 30, 0, 36).set(ARM_R, 10, 0, -36).drop(0.04f);
        return keys(t, new int[]{0, 8, 22, 26, 30, 40}, new Pose[]{loom(0), point, point, chamber, kick, loom(0)}, "EEESE");
    }

    /** DEFEAT (90 ticks): the last pillar is off him, he rises halfway, falls to one knee, plants the axe and bows his head. */
    static Pose defeat(float t) {
        Pose kneel = new Pose().drop(-0.62f).set(LEG_R, -86, 0, -4).set(LEG_L, 78, 0, 6).set(BODY, 12, 0, 0).set(HEAD, 40, 0, 0)
                .set(ARM_R, -60, 10, -6).set(ARM_L, -56, 30, 8);
        Pose slump = kneel.copy().add(BODY, 10, 0, 0).add(HEAD, 10, 0, 0).add(ARM_L, 30, 0, 10);
        return keys(t, new int[]{0, 20, 40, 90}, new Pose[]{pinned(30), stunned(0), kneel, slump}, "EEE");
    }

    /** The intro (70 ticks): head rises to look at you, he lifts the axe off the ground and squares up. */
    static Pose awaken(float t) {
        Pose statue = new Pose().set(ARM_R, -32, -8, -4).set(ARM_L, -30, 34, 8).set(HEAD, 20, 0, 0);
        Pose look = statue.copy().set(HEAD, -6, 0, 0);
        Pose lift = new Pose().set(ARM_R, -120, 10, -10).set(ARM_L, -100, 30, 10).set(BODY, -6, 0, 0).set(HEAD, -4, 0, 0)
                .set(LEG_R, 6, 0, -4).set(LEG_L, -14, 0, 6);
        return keys(t, new int[]{0, 20, 40, 52, 70}, new Pose[]{statue, look, look, lift, idle(0)}, "EEEE");
    }

    // ======================================================================== the black tiger (body pitch, head pitch/yaw, jaw, tail)

    // =================================================================================================
    //  Cutscenes
    // =================================================================================================

    /** On top of his pillar, digging into it (24-tick loop): the pickaxe comes up over his shoulder and bites down. */
    static Pose dig(int t) {
        float f = (t % 24);
        Pose up = new Pose().set(ARM_R, -165, 20, -12).set(ARM_L, -40, 30, 16).set(BODY, -6, 14, 0).set(HEAD, 18, -8, 0)
                .set(LEG_R, 4, 0, -6).set(LEG_L, -14, 0, 8);
        Pose bite = new Pose().set(ARM_R, -40, -10, -6).set(ARM_L, -30, 20, 14).set(BODY, 34, 0, 0).set(HEAD, 36, 0, 0)
                .set(LEG_R, 12, 0, -6).set(LEG_L, -26, 0, 8).drop(-0.12f);
        return keys(f, new int[]{0, 12, 15, 24}, new Pose[]{bite, up, bite, bite}, "ESE");
    }

    /** "Who are you?" (40 ticks): stops digging, straightens up, lowers the pickaxe and looks at you. */
    static Pose straighten(float t) {
        Pose stand = new Pose().set(ARM_R, -20, 10, -8).set(ARM_L, 4, 0, 10).set(BODY, -2, 0, 0).set(HEAD, -4, 0, 0)
                .set(LEG_R, 0, 0, -6).set(LEG_L, 0, 0, 6);
        return keys(t, new int[]{0, 16, 40}, new Pose[]{dig(14), dig(14).add(HEAD, -40, 0, 0), stand}, "EE");
    }

    /** Standing on his pillar, watching (looks down at the fight). */
    static Pose watch(int t) {
        float b = sin(t * 0.05);
        return new Pose().set(ARM_R, -20, 10, -8).set(ARM_L, -60, 60, 30).set(BODY, 4 + b, 0, 0).set(HEAD, 22, 0, 0)
                .set(LEG_R, 0, 0, -6).set(LEG_L, 0, 0, 6);
    }

    /** The finger snap (30 ticks, the snap at 18): his left hand comes up beside his head, then a sharp flick. */
    static Pose snap(float t) {
        Pose ready = watch(0).set(ARM_L, -150, -20, 26).set(HEAD, 4, 20, 0);
        Pose snapped = ready.copy().set(ARM_L, -132, -40, 34).add(HEAD, 4, 0, 0);
        return keys(t, new int[]{0, 12, 18, 22, 30}, new Pose[]{watch(0), ready, snapped, snapped, watch(0)}, "ESEE");
    }

    /** "Useless." (60 ticks): looks down at the dead tiger, head shaking slowly, a dismissive flick of the hand. */
    static Pose useless(float t) {
        Pose look = watch(0).set(HEAD, 40, 0, 0).set(BODY, 12, 0, 0);
        Pose flick = look.copy().set(ARM_L, -40, 0, 50);
        Pose shake = look.copy().add(HEAD, 0, sin(t * 0.4) * 14, 0);
        return keys(t, new int[]{0, 14, 36, 44, 60}, new Pose[]{watch(0), look, shake, flick, look}, "EESE");
    }

    // =================================================================================================
    //  The Roaring Knight's moves: fast, sharp, the blade doing the talking
    // =================================================================================================

    /** SLASH (22 ticks, the cut at 12): a low coil to his left, then one huge rising slash that sends white lines across the floor. */
    static Pose slash(float t) {
        Pose g = idle((int) t);
        Pose coil = new Pose().set(ARM_R, -30, 80, -50).set(ARM_L, -50, 60, 20).set(BODY, 18, -50, 0).set(HEAD, 8, 40, 0)
                .set(LEG_R, -24, 0, -10).set(LEG_L, 18, 0, 10).drop(-0.22f);
        Pose cut = new Pose().set(ARM_R, -170, -60, -40).set(ARM_L, -60, -30, 30).set(BODY, -14, 50, 0).set(HEAD, -10, -30, 0)
                .set(LEG_R, 10, 0, -10).set(LEG_L, -16, 0, 10).drop(0.02f);
        return keys(t, new int[]{0, 8, 12, 18, 22}, new Pose[]{g, coil, cut, cut, g}, "ESEE");
    }

    /** DASH (one 12-tick dash): crouched, blade trailing behind, then a lunge with the blade thrust out. */
    static Pose dash(float t) {
        Pose set = new Pose().set(ARM_R, 70, 30, -30).set(ARM_L, -60, 30, 20).set(BODY, 30, -20, 0).set(HEAD, -24, 20, 0)
                .set(LEG_R, -50, 0, -6).set(LEG_L, 40, 0, 6).drop(-0.35f);
        Pose lunge = new Pose().set(ARM_R, -16, -10, -8).set(ARM_L, 30, 0, 40).set(BODY, 34, 10, 0).set(HEAD, -28, -10, 0)
                .set(LEG_R, -70, 0, -4).set(LEG_L, 60, 0, 4).drop(-0.3f);
        return keys(t, new int[]{0, 6, 8, 12}, new Pose[]{set, set, lunge, lunge}, "ESE");
    }

    /** CROSS (26 ticks, cuts at 10 and 18): one diagonal slash down to the right, then back up across it. */
    static Pose cross(float t) {
        Pose g = idle((int) t);
        Pose highL = new Pose().set(ARM_R, -170, 40, 30).set(ARM_L, -150, 20, 20).set(BODY, -10, -30, 0).set(HEAD, -10, 20, 0)
                .set(LEG_R, 8, 0, -8).set(LEG_L, -16, 0, 8);
        Pose lowR = new Pose().set(ARM_R, -30, -50, -60).set(ARM_L, -40, -20, 20).set(BODY, 26, 40, 0).set(HEAD, 10, -20, 0)
                .set(LEG_R, -16, 0, -8).set(LEG_L, 12, 0, 8).drop(-0.18f);
        Pose highR = new Pose().set(ARM_R, -175, -40, -50).set(ARM_L, -130, -20, 10).set(BODY, -12, 30, 0).set(HEAD, -12, -20, 0)
                .set(LEG_R, -10, 0, -8).set(LEG_L, 10, 0, 8);
        return keys(t, new int[]{0, 7, 10, 15, 18, 26}, new Pose[]{g, highL, lowR, lowR, highR, g}, "ESESE");
    }

    /** SUMMON (30 ticks): the blade raised straight up, the left hand spread toward you: swords appear around you. */
    static Pose summon(float t) {
        Pose up = new Pose().set(ARM_R, -180, 0, -8).set(ARM_L, -90, -20, 30).set(BODY, -6, -10, 0).set(HEAD, -6, 10, 0)
                .set(LEG_R, 4, 0, -10).set(LEG_L, -10, 0, 10);
        Pose close = up.copy().set(ARM_L, -86, 10, 10);
        return keys(t, new int[]{0, 8, 22, 26, 30}, new Pose[]{idle(0), up, up, close, idle(0)}, "EESE");
    }

    /** STARBURST (40 ticks, waves at 14, 22, 30): gathers in, then throws both arms wide as blades burst out all around him. */
    static Pose starburst(float t) {
        Pose in = new Pose().set(ARM_R, -40, 50, 30).set(ARM_L, -40, -50, -30).set(BODY, 24, 0, 0).set(HEAD, 24, 0, 0)
                .set(LEG_R, -20, 0, -8).set(LEG_L, 16, 0, 8).drop(-0.3f);
        Pose out = new Pose().set(ARM_R, -60, 0, -100).set(ARM_L, -60, 0, 100).set(BODY, -16, 0, 0).set(HEAD, -24, 0, 0)
                .set(LEG_R, 0, 0, -14).set(LEG_L, 0, 0, 14).drop(0.04f);
        Pose pulse = out.copy().add(BODY, 6, 0, 0);
        return keys(t, new int[]{0, 12, 14, 20, 22, 28, 30, 40}, new Pose[]{idle(0), in, out, pulse, out, pulse, out, idle(0)}, "ESESESE");
    }

    // =================================================================================================
    //  The black tiger: {bodyPitch, headPitch, headYaw, jaw, tailYaw, legFR, legFL, legBR, legBL, drop(px), roll, tailPitch}
    //  Pitch: positive = nose/foot down-and-back, negative = up/forward. Roll positive = onto its left side.
    // =================================================================================================
    static final int T_BODY = 0, T_HEAD = 1, T_HYAW = 2, T_JAW = 3, T_TAIL = 4, T_FR = 5, T_FL = 6, T_BR = 7, T_BL = 8, T_DROP = 9, T_ROLL = 10, T_TPITCH = 11;

    static float[] tiger() { return new float[12]; }

    static float[] tlerp(float[] a, float[] b, float f) {
        f = Math.max(0, Math.min(1, f));
        float[] o = new float[12];
        for (int i = 0; i < 12; i++) o[i] = a[i] + (b[i] - a[i]) * f;
        return o;
    }

    static float ease(float f) { f = Math.max(0, Math.min(1, f)); return f * f * (3 - 2 * f); }

    /** Standing, breathing, tail swaying, head turning slowly. */
    static float[] tigerIdle(int t) {
        float[] p = tiger();
        float b = sin(t * 0.08);
        p[T_HEAD] = 4 + b * 2; p[T_HYAW] = sin(t * 0.021) * 18; p[T_TAIL] = sin(t * 0.07) * 22; p[T_TPITCH] = -10 + sin(t * 0.05) * 6;
        p[T_DROP] = -Math.abs(b) * 0.4f; p[T_BODY] = b;
        return p;
    }

    /** Sitting upright beside its master (cutscene). */
    static float[] tigerSit(int t) {
        float[] p = tigerIdle(t);
        p[T_BODY] = -28; p[T_HEAD] = 24; p[T_FR] = 26; p[T_FL] = 26; p[T_BR] = -62; p[T_BL] = -62; p[T_DROP] = -4.5f;
        p[T_TAIL] = 40 + sin(t * 0.06) * 10; p[T_TPITCH] = 50;
        return p;
    }

    /** Prowling walk / run (a: 0..1.5): diagonal pairs swing together, shoulders roll, head low and level. */
    static float[] tigerWalk(int t, float a) {
        float[] p = tigerIdle(t);
        float ph = t * (0.34f + 0.12f * a), s = sin(ph) * 30 * Math.min(1.3f, a);
        p[T_FR] = s; p[T_BL] = s; p[T_FL] = -s; p[T_BR] = -s;
        p[T_HEAD] = 10 - sin(ph * 2) * 2; p[T_HYAW] = 0; p[T_ROLL] = sin(ph) * 3;
        p[T_DROP] = -Math.abs(sin(ph)) * 0.8f * a - 0.8f * a; p[T_TAIL] = sin(ph * 0.5f) * 14; p[T_TPITCH] = -18;
        return p;
    }

    /** Roar (24 ticks): rears up on its hind legs, head thrown back, jaw wide. */
    static float[] tigerRoar(float t) {
        float f = sin(Math.min(1f, t / 24f) * Math.PI);
        float[] p = tigerIdle((int) t);
        p[T_BODY] = -22 * f; p[T_HEAD] = -26 * f; p[T_JAW] = 40 * f; p[T_FR] = -30 * f; p[T_FL] = -24 * f;
        p[T_BR] = 20 * f; p[T_BL] = 20 * f; p[T_TAIL] = sin(t * 0.6) * 35; p[T_TPITCH] = -30 * f;
        return p;
    }

    /** Crouched to pounce: chest down, haunches up and wiggling, tail lashing. */
    static float[] tigerCrouch(int t) {
        float[] p = tiger();
        p[T_BODY] = 8; p[T_HEAD] = -6; p[T_JAW] = 8; p[T_FR] = -24; p[T_FL] = -24; p[T_BR] = 30; p[T_BL] = 30;
        p[T_DROP] = -4f; p[T_ROLL] = sin(t * 1.2) * 4; p[T_TAIL] = sin(t * 0.9) * 40; p[T_TPITCH] = -6;
        return p;
    }

    /** In the air: fully stretched out, front paws reaching, jaws open. */
    static float[] tigerLeap(int t) {
        float[] p = tiger();
        p[T_BODY] = -8; p[T_HEAD] = -4; p[T_JAW] = 34; p[T_FR] = -80; p[T_FL] = -74; p[T_BR] = 60; p[T_BL] = 56; p[T_TAIL] = -10; p[T_TPITCH] = -24;
        return p;
    }

    /** Landing (12 ticks): front paws slam down, the body absorbs it. */
    static float[] tigerLand(float t) {
        float[] land = tiger();
        land[T_BODY] = 10; land[T_HEAD] = 14; land[T_JAW] = 20; land[T_FR] = -10; land[T_FL] = -10; land[T_BR] = 34; land[T_BL] = 34; land[T_DROP] = -3.5f;
        return t < 4 ? tlerp(tigerLeap(0), land, ease(t / 4f)) : tlerp(land, tigerIdle((int) t), ease((t - 4) / 8f));
    }

    /** Swipe (18 ticks, contact at 10): rises onto its hind legs a little and rakes one front paw across. right = its right paw. */
    static float[] tigerSwipe(float t, boolean right) {
        float[] g = tigerIdle((int) t), up = tigerIdle((int) t), rake = tigerIdle((int) t);
        up[T_BODY] = -16; up[T_HEAD] = -6; up[T_JAW] = 22; up[right ? T_FR : T_FL] = -120; up[right ? T_FL : T_FR] = -20; up[T_ROLL] = right ? 8 : -8;
        up[T_HYAW] = right ? 16 : -16; up[T_BR] = 14; up[T_BL] = 14; up[T_DROP] = 1.5f;
        rake[T_BODY] = 10; rake[T_HEAD] = 10; rake[T_JAW] = 30; rake[right ? T_FR : T_FL] = -30; rake[right ? T_FL : T_FR] = -6;
        rake[T_ROLL] = right ? -10 : 10; rake[T_HYAW] = right ? -22 : 22; rake[T_DROP] = -2f;
        if (t < 8) return tlerp(g, up, ease(t / 8f));
        if (t < 11) return tlerp(up, rake, (t - 8) / 3f);
        return tlerp(rake, g, ease((t - 11) / 7f));
    }

    /** Hurt flinch (6 ticks). */
    static float[] tigerFlinch(float[] base, float t) {
        float[] p = base.clone();
        float f = sin(Math.min(1f, t / 6f) * Math.PI);
        p[T_HEAD] -= 16 * f; p[T_JAW] += 14 * f; p[T_BODY] -= 4 * f;
        return p;
    }

    /** Dying (80 ticks): staggers, the back legs give out, it slumps, rolls onto its side and goes still. */
    static float[] tigerDie(float t) {
        float[] stagger = tigerIdle(0);
        stagger[T_HEAD] = 24; stagger[T_JAW] = 18; stagger[T_ROLL] = 8; stagger[T_FR] = -10; stagger[T_FL] = 12;
        float[] sink = stagger.clone();
        sink[T_BR] = -60; sink[T_BL] = -60; sink[T_BODY] = -14; sink[T_DROP] = -5f; sink[T_ROLL] = 4; sink[T_HEAD] = 10; sink[T_JAW] = 30;
        float[] slump = sink.clone();
        slump[T_FR] = -70; slump[T_FL] = -70; slump[T_BODY] = 0; slump[T_DROP] = -9f; slump[T_HEAD] = 30; slump[T_JAW] = 10;
        float[] side = slump.clone();
        side[T_ROLL] = 84; side[T_DROP] = -8.5f; side[T_FR] = -40; side[T_FL] = -30; side[T_BR] = -30; side[T_BL] = -20;
        side[T_HEAD] = 6; side[T_HYAW] = -10; side[T_JAW] = 12; side[T_TAIL] = 30; side[T_TPITCH] = 40;
        if (t < 14) { float[] p = tlerp(tigerIdle(0), stagger, ease(t / 14f)); p[T_ROLL] += sin(t * 0.9) * 6; return p; }
        if (t < 30) return tlerp(stagger, sink, ease((t - 14) / 16f));
        if (t < 44) return tlerp(sink, slump, ease((t - 30) / 14f));
        if (t < 60) return tlerp(slump, side, ease((t - 44) / 16f));
        float[] p = side.clone();
        if (t < 70) p[T_HEAD] += sin((t - 60) * 0.6) * 3; // one last breath
        return p;
    }
}
