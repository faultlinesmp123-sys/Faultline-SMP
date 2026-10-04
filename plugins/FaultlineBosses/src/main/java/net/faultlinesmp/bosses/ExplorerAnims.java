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

    /** {bodyPitch, headPitch, headYaw, jawOpen, tailSway}: sitting, watching, tail curling. */
    static float[] tigerSit(int t) {
        return new float[]{0, sin(t * 0.04) * 3, sin(t * 0.017) * 20, 0, sin(t * 0.07) * 18};
    }

    /** Roar (20 ticks): rears the head back and opens the jaw wide. */
    static float[] tigerRoar(int t) {
        float f = sin(Math.min(1f, t / 20f) * Math.PI);
        return new float[]{-6 * f, -22 * f, 0, 38 * f, sin(t * 0.5) * 30};
    }

    /** Crouched to pounce: chest down, head low, tail lashing. */
    static float[] tigerCrouch(int t) {
        return new float[]{14, 12, 0, 10, sin(t * 0.9) * 35};
    }

    /** In the air: stretched out, jaws open. */
    static float[] tigerLeap(int t) {
        return new float[]{-10, -6, 0, 32, -20};
    }
}
