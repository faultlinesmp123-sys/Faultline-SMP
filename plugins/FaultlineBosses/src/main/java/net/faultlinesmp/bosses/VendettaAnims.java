package net.faultlinesmp.bosses;

import net.faultlinesmp.bosses.FaultlineBosses.Pose;

import static net.faultlinesmp.bosses.FaultlineBosses.*;

/**
 * Rocco's and Werner's animations: every move is a pure function of its own clock (ticks), so the fight code only says
 * WHEN things happen and these say how they look. tools/vendetta_anim_preview renders them to PNG strips.
 *
 * Conventions (renderRig): degrees x/y/z per part. Arms/legs: negative X swings forward/up. Y twists: positive turns the
 * front toward HIS LEFT (+X), so a right-hand punch drives the torso to positive Y. Arms out to the side: right arm
 * negative Z, left arm positive Z. drop = how far the whole body sinks, in blocks.
 */
final class VendettaAnims {
    private VendettaAnims() { }

    static float sin(double a) { return (float) Math.sin(a); }

    /** Keyframes with a per-segment easing: S = snap (strike), E = ease (wind-ups, recovery). */
    static Pose keys(float t, int[] f, Pose[] p, String ease) {
        if (t <= f[0]) return p[0].copy();
        for (int i = 0; i < f.length - 1; i++) {
            if (t < f[i + 1]) {
                float x = (t - f[i]) / (float) (f[i + 1] - f[i]);
                char c = i < ease.length() ? ease.charAt(i) : 'E';
                return Pose.lerp(p[i], p[i + 1], c == 'S' ? snap(x) : c == 'L' ? x : ease(x));
            }
        }
        return p[p.length - 1].copy();
    }

    // ======================================================================== ROCCO: a heavyweight brawler

    /** His guard: fists up by the jaw, lead (left) hand forward, chin down, bouncing on the balls of his feet. */
    static Pose roccoStance(int t) {
        float b = sin(t * 0.21), s = sin(t * 0.105);
        return new Pose()
                .set(ARM_R, -52 + b * 3, 38, 6).set(ARM_L, -66 - b * 3, -30, -4)
                .set(BODY, 10, -16 + s * 4, 0).set(HEAD, 8, 16 - s * 3, 0)
                .set(LEG_R, 16, 0, -4).set(LEG_L, -18, 0, 5)
                .drop(-0.05f - Math.abs(b) * 0.035f);
    }

    /** Walking/running with his guard up (the legs come from the stride, see Body.render). */
    static Pose roccoGuardWalk(int t) {
        Pose p = roccoStance(t);
        p.r[BODY][0] += 6;
        return p;
    }

    /** Kick (24 ticks, contact at 9): chamber the knee, snap the shin out, recoil, back to guard. */
    static Pose roccoKick(float t) {
        Pose g = roccoStance((int) t);
        Pose chamber = new Pose().set(LEG_R, -78, 0, -4).set(LEG_L, 10, 0, 4).set(BODY, -8, -18, 0).set(HEAD, 10, 14, 0)
                .set(ARM_R, -40, 10, -34).set(ARM_L, -88, -18, 18).drop(0.02f);
        Pose extend = new Pose().set(LEG_R, -104, 0, 2).set(LEG_L, 18, 0, 4).set(BODY, -22, -8, 0).set(HEAD, 22, 6, 0)
                .set(ARM_R, 24, 0, -58).set(ARM_L, -60, -10, 40).drop(0.06f);
        Pose recoil = new Pose().set(LEG_R, -70, 0, -4).set(LEG_L, 12, 0, 4).set(BODY, -10, -14, 0).set(HEAD, 12, 12, 0)
                .set(ARM_R, -30, 10, -30).set(ARM_L, -82, -16, 16);
        return keys(t, new int[]{0, 6, 9, 13, 17, 24}, new Pose[]{g, chamber, extend, extend, recoil, roccoStance((int) t)}, "ESEEE");
    }

    /** Punch (18 ticks, contact at 7): load the right hand (shoulder back, weight back), drive through, hold, reset. */
    static Pose roccoPunch(float t, boolean left) {
        Pose g = roccoStance((int) t);
        Pose load = new Pose().set(ARM_R, 22, -8, -14).set(ARM_L, -86, -20, 10).set(BODY, 12, -34, 0).set(HEAD, 8, 30, 0)
                .set(LEG_R, 22, 0, -4).set(LEG_L, -20, 0, 6).drop(-0.12f);
        Pose hit = new Pose().set(ARM_R, -96, 10, 0).set(ARM_L, -62, -30, 18).set(BODY, 16, 32, 0).set(HEAD, 6, -26, 0)
                .set(LEG_R, -8, 0, -4).set(LEG_L, -26, 0, 6).drop(-0.08f);
        Pose p = keys(t, new int[]{0, 5, 7, 11, 18}, new Pose[]{g, load, hit, hit, roccoStance((int) t)}, "ESEE");
        return left ? mirror(p) : p;
    }

    /** Uppercut (18 ticks, contact at 5): dip low, rip the right hand straight up, rise onto his toes. */
    static Pose roccoUppercut(float t) {
        Pose dip = new Pose().set(ARM_R, 10, 0, -20).set(ARM_L, -80, -18, 12).set(BODY, 26, -20, 0).set(HEAD, -10, 16, 0)
                .set(LEG_R, -30, 0, -6).set(LEG_L, -18, 0, 6).drop(-0.3f);
        Pose rip = new Pose().set(ARM_R, -168, 12, -6).set(ARM_L, -40, -10, 30).set(BODY, -16, 24, 0).set(HEAD, -24, -16, 0)
                .set(LEG_R, 8, 0, -4).set(LEG_L, -10, 0, 4).drop(0.1f);
        return keys(t, new int[]{0, 3, 5, 10, 18}, new Pose[]{dip, dip, rip, rip, roccoStance((int) t)}, "ESEE");
    }

    /** Payback: a high cross-guard, peeking over his forearms, swaying, daring you. */
    static Pose roccoGuard(int t) {
        float s = sin(t * 0.3);
        return new Pose().set(ARM_R, -112, 38, -6).set(ARM_L, -104, -40, 6).set(BODY, 14, s * 8, 0).set(HEAD, 14, -s * 6, 0)
                .set(LEG_R, 20, 0, -6).set(LEG_L, -14, 0, 8).drop(-0.14f + Math.abs(s) * 0.03f);
    }

    /** Assemble (26 ticks, the family arrives at 10): two fingers to his mouth, a whistle, then a point at you. */
    static Pose roccoWhistle(float t) {
        Pose whistle = new Pose().set(ARM_R, -150, 40, -4).set(ARM_L, 6, 0, 14).set(BODY, -8, -6, 0).set(HEAD, -22, 4, 0)
                .set(LEG_R, 4, 0, -4).set(LEG_L, -6, 0, 4);
        Pose point = new Pose().set(ARM_R, -94, -6, 0).set(ARM_L, 12, 0, 16).set(BODY, 6, 18, 0).set(HEAD, 4, -12, 0)
                .set(LEG_R, -14, 0, -4).set(LEG_L, 12, 0, 4);
        return keys(t, new int[]{0, 5, 12, 16, 22, 26}, new Pose[]{roccoStance((int) t), whistle, whistle, point, point, roccoStance((int) t)}, "EEEEE");
    }

    /** MY HAIR COUPONS charge: arms raised, shaking harder and harder as he sinks into a crouch. f = 0..1 charged. */
    static Pose roccoCharge(int t, float f) {
        float q = sin(t * 1.7) * (2 + 10 * f);
        return new Pose().set(ARM_R, -158 + q, 0, -26 - 8 * f).set(ARM_L, -158 - q, 0, 26 + 8 * f)
                .set(BODY, -16 + 26 * f, q * 0.3f, 0).set(HEAD, -30 + 34 * f, 0, q * 0.4f)
                .set(LEG_R, -22 * f, 0, -12 - 6 * f).set(LEG_L, -22 * f, 0, 12 + 6 * f).drop(-0.32f * f);
    }

    /** The coupon blast: both fists hammered into the ground, then he straightens (24 ticks). */
    static Pose roccoGroundSmash(float t) {
        Pose smash = new Pose().set(ARM_R, -36, 0, -6).set(ARM_L, -36, 0, 6).set(BODY, 52, 0, 0).set(HEAD, 18, 0, 0)
                .set(LEG_R, -50, 0, -10).set(LEG_L, 30, 0, 10).drop(-0.62f);
        return keys(t, new int[]{0, 2, 12, 24}, new Pose[]{smash, smash, smash, roccoStance((int) t)}, "SEE");
    }

    /** Extermination take-off (14 ticks): sinks deep, then launches straight up, fully stretched. */
    static Pose roccoLeapStart(float t) {
        Pose deep = new Pose().set(ARM_R, 40, 0, -24).set(ARM_L, 40, 0, 24).set(BODY, 36, 0, 0).set(HEAD, -20, 0, 0)
                .set(LEG_R, -64, 0, -8).set(LEG_L, -64, 0, 8).drop(-0.62f);
        Pose launch = new Pose().set(ARM_R, -172, 0, -10).set(ARM_L, -172, 0, 10).set(BODY, -6, 0, 0).set(HEAD, -16, 0, 0)
                .set(LEG_R, 16, 0, -2).set(LEG_L, 22, 0, 2).drop(0.1f);
        return keys(t, new int[]{0, 11, 14}, new Pose[]{roccoStance((int) t), deep, launch}, "ES");
    }

    /** In the air: tucked, turning, eyes on the ground; a = 0..1 how close he is to coming down (he unfolds into a dive). */
    static Pose roccoAirborne(int t, float a) {
        Pose tuck = new Pose().set(ARM_R, -40, 0, -50).set(ARM_L, -40, 0, 50).set(BODY, 24 + sin(t * 0.2) * 6, 0, 0).set(HEAD, 30, 0, 0)
                .set(LEG_R, -80, 0, -10).set(LEG_L, -70, 0, 10);
        Pose dive = new Pose().set(ARM_R, -176, 0, -6).set(ARM_L, -176, 0, 6).set(BODY, 40, 0, 0).set(HEAD, 40, 0, 0)
                .set(LEG_R, 10, 0, -6).set(LEG_L, 20, 0, 6);
        return Pose.lerp(tuck, dive, ease(a));
    }

    /** The Extermination landing: a crater-deep crouch, fists in the dirt, slowly rising (40 ticks). */
    static Pose roccoSlamLand(float t) {
        Pose land = new Pose().set(ARM_R, -20, 0, -40).set(ARM_L, -20, 0, 40).set(BODY, 58, 0, 0).set(HEAD, 26, 0, 0)
                .set(LEG_R, -76, 0, -14).set(LEG_L, -40, 0, 14).drop(-0.8f);
        Pose rise = new Pose().set(ARM_R, 20, 0, -30).set(ARM_L, 20, 0, 30).set(BODY, 20, 0, 0).set(HEAD, -10, 0, 0)
                .set(LEG_R, -20, 0, -6).set(LEG_L, -10, 0, 6).drop(-0.25f);
        return keys(t, new int[]{0, 16, 30, 40}, new Pose[]{land, land, rise, roccoStance((int) t)}, "EEE");
    }

    /** I SWEAR VENGEANCE (26 ticks): beats his chest twice, then a roar with his arms thrown wide. */
    static Pose roccoSwear(float t) {
        Pose beatR = new Pose().set(ARM_R, -70, 60, -4).set(ARM_L, -30, -20, 20).set(BODY, 6, -10, 0).set(HEAD, 10, 0, 0).set(LEG_R, 8, 0, -4).set(LEG_L, -8, 0, 4);
        Pose beatL = mirror(beatR);
        Pose roar = new Pose().set(ARM_R, -20, 0, -110).set(ARM_L, -20, 0, 110).set(BODY, -22, 0, 0).set(HEAD, -40 + sin(t * 1.3) * 4, 0, 0)
                .set(LEG_R, 0, 0, -16).set(LEG_L, 0, 0, 16).drop(-0.06f);
        return keys(t, new int[]{0, 4, 8, 12, 18, 23, 26}, new Pose[]{roccoStance((int) t), beatR, beatL, roar, roar, roar, roccoStance((int) t)}, "SSEEEE");
    }

    /** Hit hard enough to change phase (60 ticks): staggers back, drops to a knee, then rises roaring. */
    static Pose roccoPhaseChange(float t) {
        Pose stagger = new Pose().set(BODY, -24, 10, 0).set(HEAD, -30, 0, 0).set(ARM_R, 40, 0, -40).set(ARM_L, 30, 0, 36).set(LEG_R, -24, 0, -4).set(LEG_L, 20, 0, 4).drop(0.04f);
        Pose knee = kneelPose(0);
        Pose roar = new Pose().set(ARM_R, -30, 0, -100).set(ARM_L, -30, 0, 100).set(BODY, -20, 0, 0).set(HEAD, -42 + sin(t * 1.4) * 5, 0, 0)
                .set(LEG_R, 0, 0, -18).set(LEG_L, 0, 0, 18);
        return keys(t, new int[]{0, 5, 18, 30, 38, 52, 60}, new Pose[]{roccoStance(0), stagger, knee, knee, roar, roar, roccoStance((int) t)}, "SEEEEE");
    }

    /** Beaten (100 ticks to kneel and catch his breath, then he falls forward). */
    static Pose roccoDefeat(float t) {
        Pose knee = kneelPose((int) t);
        Pose sit = new Pose().set(BODY, -10, 0, 0).set(HEAD, 20, 0, 0).set(ARM_R, 30, 0, -30).set(ARM_L, 30, 0, 30)
                .set(LEG_R, -86, 0, -6).set(LEG_L, -80, 0, 6).drop(-0.62f);
        Pose fallen = new Pose().set(BODY, -88, 0, 0).set(HEAD, -6, 24, 0).set(ARM_R, -170, 0, -24).set(ARM_L, -150, 0, 34)
                .set(LEG_R, -90, 0, -6).set(LEG_L, -84, 0, 8).drop(-0.78f);
        return keys(t, new int[]{0, 20, 100, 108, 120}, new Pose[]{roccoStance(0), knee, knee, sit, fallen}, "EEEE");
    }

    /** Arrival (70 ticks): rises out of the smoke cracking his neck, then raises his guard. */
    static Pose roccoArrive(float t) {
        Pose low = new Pose().set(BODY, 40, 0, 0).set(HEAD, 30, 0, 0).set(ARM_R, 10, 0, -10).set(ARM_L, 10, 0, 10).set(LEG_R, -60, 0, -6).set(LEG_L, -30, 0, 6).drop(-0.55f);
        Pose up = new Pose().set(ARM_R, 6, 0, -8).set(ARM_L, 6, 0, 8).set(HEAD, -4, 0, 0);
        Pose crackL = new Pose().set(ARM_R, 6, 0, -8).set(ARM_L, 6, 0, 8).set(HEAD, 0, 0, 26);
        Pose crackR = new Pose().set(ARM_R, 6, 0, -8).set(ARM_L, 6, 0, 8).set(HEAD, 0, 0, -26);
        return keys(t, new int[]{0, 16, 30, 36, 42, 48, 60, 70}, new Pose[]{low, low, up, crackL, crackL, crackR, up, roccoStance((int) t)}, "EESEEEE");
    }

    // ======================================================================== WERNER: quick, cocky, a street fighter

    /** Loose and low, weight forward, right fist cocked, left hand hanging, rolling his shoulders. */
    static Pose wernerStance(int t) {
        float b = sin(t * 0.26), s = sin(t * 0.13);
        return new Pose()
                .set(ARM_R, -50 + b * 4, 22, -10).set(ARM_L, -14 - b * 6, -8, 16)
                .set(BODY, 16, 18 + s * 6, s * 3).set(HEAD, -6, -18 - s * 4, 0)
                .set(LEG_R, 20, 0, -6).set(LEG_L, -24, 0, 6)
                .drop(-0.1f - Math.abs(b) * 0.03f);
    }

    /** Aim for the Solar Plexus (16 ticks, contact at 6): a dipping body blow, low and straight. */
    static Pose wernerPlexus(float t) {
        Pose load = new Pose().set(ARM_R, 30, -6, -14).set(ARM_L, -40, -20, 20).set(BODY, 26, -30, 0).set(HEAD, -14, 26, 0)
                .set(LEG_R, 24, 0, -6).set(LEG_L, -30, 0, 6).drop(-0.22f);
        Pose hit = new Pose().set(ARM_R, -76, 8, 0).set(ARM_L, -10, -10, 26).set(BODY, 34, 30, 0).set(HEAD, -22, -24, 0)
                .set(LEG_R, -10, 0, -6).set(LEG_L, -36, 0, 6).drop(-0.3f);
        return keys(t, new int[]{0, 4, 6, 10, 16}, new Pose[]{wernerStance((int) t), load, hit, hit, wernerStance((int) t)}, "ESEE");
    }

    /** Right in the Dome (18 ticks, contact at 8): grabs you by the collar with both hands and slams his forehead in. */
    static Pose wernerDome(float t) {
        Pose grab = new Pose().set(ARM_R, -96, 14, -4).set(ARM_L, -96, -14, 4).set(BODY, -6, 0, 0).set(HEAD, -20, 0, 0)
                .set(LEG_R, 14, 0, -4).set(LEG_L, -16, 0, 4);
        Pose rear = new Pose().set(ARM_R, -84, 14, -4).set(ARM_L, -84, -14, 4).set(BODY, -24, 0, 0).set(HEAD, -34, 0, 0)
                .set(LEG_R, 20, 0, -4).set(LEG_L, -10, 0, 4).drop(0.05f);
        Pose bash = new Pose().set(ARM_R, -70, 14, -4).set(ARM_L, -70, -14, 4).set(BODY, 34, 0, 0).set(HEAD, 34, 0, 0)
                .set(LEG_R, -18, 0, -4).set(LEG_L, -24, 0, 4).drop(-0.16f);
        return keys(t, new int[]{0, 4, 7, 8, 12, 18}, new Pose[]{wernerStance((int) t), grab, rear, bash, bash, wernerStance((int) t)}, "EESEE");
    }

    /** Gut Crush (26 ticks, contacts at 5 and 12): two hooks to the body, then he flexes (the buff). */
    static Pose wernerGut(float t) {
        Pose loadR = new Pose().set(ARM_R, -10, 30, -40).set(ARM_L, -40, -10, 20).set(BODY, 22, -30, 0).set(HEAD, -10, 24, 0).set(LEG_R, 18, 0, -6).set(LEG_L, -22, 0, 6).drop(-0.2f);
        Pose hookR = new Pose().set(ARM_R, -70, 70, -20).set(ARM_L, -50, -10, 20).set(BODY, 26, 34, 0).set(HEAD, -14, -24, 0).set(LEG_R, -6, 0, -6).set(LEG_L, -26, 0, 6).drop(-0.24f);
        Pose hookL = mirror(hookR), loadL = mirror(loadR);
        Pose flex = new Pose().set(ARM_R, -10, 0, -70).set(ARM_L, -10, 0, 70).set(BODY, -14, 0, 0).set(HEAD, -24, 0, 0)
                .set(LEG_R, 0, 0, -14).set(LEG_L, 0, 0, 14).drop(-0.04f);
        Pose flex2 = flex.copy().add(ARM_R, -60, 0, 20).add(ARM_L, -60, 0, -20); // biceps up
        return keys(t, new int[]{0, 3, 5, 8, 10, 12, 16, 20, 23, 26},
                new Pose[]{wernerStance((int) t), loadR, hookR, hookR, loadL, hookL, hookL, flex2, flex2, wernerStance((int) t)}, "ESEESEEEE");
    }

    /** Vengeance Awaits You All! (30 ticks, slam at 16): a hop with both fists over his head, then a hammer blow. */
    static Pose wernerAwaits(float t) {
        Pose crouch = new Pose().set(ARM_R, 30, 0, -20).set(ARM_L, 30, 0, 20).set(BODY, 30, 0, 0).set(HEAD, -16, 0, 0)
                .set(LEG_R, -46, 0, -6).set(LEG_L, -46, 0, 6).drop(-0.45f);
        Pose up = new Pose().set(ARM_R, -178, -20, -6).set(ARM_L, -178, 20, 6).set(BODY, -16, 0, 0).set(HEAD, -12, 0, 0)
                .set(LEG_R, -30, 0, -4).set(LEG_L, 10, 0, 4).drop(0.55f);
        Pose smash = new Pose().set(ARM_R, -30, -20, -4).set(ARM_L, -30, 20, 4).set(BODY, 50, 0, 0).set(HEAD, 20, 0, 0)
                .set(LEG_R, -46, 0, -10).set(LEG_L, 26, 0, 10).drop(-0.6f);
        return keys(t, new int[]{0, 6, 12, 15, 16, 22, 30}, new Pose[]{wernerStance((int) t), crouch, up, up, smash, smash, wernerStance((int) t)}, "EEESEE");
    }

    /** Counter - Seize ya Chance: a low, open guard, beckoning you in with his lead hand. */
    static Pose wernerSeize(int t) {
        float s = sin(t * 0.35), beck = Math.max(0, sin(t * 0.5));
        return new Pose().set(ARM_R, -88, 30, -4).set(ARM_L, -70 - beck * 30, -24, 10).set(BODY, 18, s * 10, 0).set(HEAD, 10, -s * 8, 0)
                .set(LEG_R, 22, 0, -8).set(LEG_L, -22, 0, 8).drop(-0.22f);
    }

    /** The counter itself: a spinning backfist (14 ticks, contact at 5). */
    static Pose wernerCounter(float t) {
        Pose wind = new Pose().set(ARM_R, -60, -40, -60).set(ARM_L, -20, 0, 30).set(BODY, 6, -60, 0).set(HEAD, 0, 40, 0).set(LEG_R, 10, 0, -6).set(LEG_L, -14, 0, 6).drop(-0.12f);
        Pose hit = new Pose().set(ARM_R, -90, 0, -90).set(ARM_L, -30, 0, 20).set(BODY, 4, 50, 0).set(HEAD, 0, -30, 0).set(LEG_R, -14, 0, -6).set(LEG_L, 10, 0, 6).drop(-0.08f);
        return keys(t, new int[]{0, 3, 5, 9, 14}, new Pose[]{wind, wind, hit, hit, wernerStance((int) t)}, "ESEE");
    }
}
