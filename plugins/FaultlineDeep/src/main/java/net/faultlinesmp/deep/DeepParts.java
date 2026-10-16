package net.faultlinesmp.deep;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WRITTEN BY tools/deep_assets.py (don't edit by hand): every Dark Ocean creature's moving parts. Each bone is its own Java
 * model (faultline:deep/<id>__<bone>, its pivot at the model's origin) and these are its looping animations, the same
 * numbers the Bedrock pack plays: rotation in degrees / position in pixels, in Bedrock's axes (x = -right, y = up,
 * z = -front). "body" moves the whole creature. half = only opens and shuts (jaws, lids).
 */
final class DeepParts {
    record Bone(String name, float x, float y, float z) { }
    record Anim(String bone, boolean rot, float x, float y, float z, float secs, float phase, boolean half) { }

    static final Map<String, List<Bone>> BONES = new HashMap<>();
    static final Map<String, List<Anim>> ANIMS = new HashMap<>();
    static final Map<String, float[]> BODY_PIVOT = new HashMap<>();

    static {
        BONES.put("gawker", List.of(new Bone("finl", 0.0400f, -0.0600f, -0.2400f), new Bone("finr", 0.0400f, -0.0600f, 0.2400f), new Bone("tail", -0.2800f, 0.0000f, 0.0000f)));
        ANIMS.put("gawker", List.of(new Anim("tail", true, 0.0000f, 28.0000f, 0.0000f, 0.7000f, 0.0000f, false), new Anim("finl", true, 0.0000f, 0.0000f, 25.0000f, 0.5000f, 0.0000f, false), new Anim("finr", true, 0.0000f, 0.0000f, -25.0000f, 0.5000f, 0.0000f, false), new Anim("body", false, 0.0000f, 0.6000f, 0.0000f, 1.8000f, 0.0000f, false)));
        BODY_PIVOT.put("gawker", new float[]{0.0000f, 0.0000f, 0.0000f});
        BONES.put("bloatfin", List.of(new Bone("finl", 0.0800f, -0.0200f, -0.3000f), new Bone("finr", 0.0800f, -0.0200f, 0.3000f), new Bone("tail", -0.3000f, 0.0000f, 0.0000f)));
        ANIMS.put("bloatfin", List.of(new Anim("tail", true, 0.0000f, 22.0000f, 0.0000f, 0.6000f, 0.0000f, false), new Anim("finl", true, 0.0000f, 0.0000f, 30.0000f, 0.4000f, 0.0000f, false), new Anim("finr", true, 0.0000f, 0.0000f, -30.0000f, 0.4000f, 0.0000f, false), new Anim("body", false, 0.0000f, 0.9000f, 0.0000f, 2.0000f, 0.0000f, false)));
        BODY_PIVOT.put("bloatfin", new float[]{0.0000f, 0.0000f, 0.0000f});
        BONES.put("jellywing", List.of(new Bone("wl", 0.0000f, 0.0000f, -0.1900f), new Bone("wr", 0.0000f, 0.0000f, 0.1900f), new Bone("tail", -0.3200f, 0.0000f, 0.0000f)));
        ANIMS.put("jellywing", List.of(new Anim("wl", true, 0.0000f, 0.0000f, 32.0000f, 1.6000f, 0.0000f, false), new Anim("wr", true, 0.0000f, 0.0000f, -32.0000f, 1.6000f, 0.0000f, false), new Anim("tail", true, 0.0000f, 16.0000f, 0.0000f, 1.6000f, 0.3000f, false), new Anim("body", false, 0.0000f, 0.8000f, 0.0000f, 1.6000f, 0.2500f, false)));
        BODY_PIVOT.put("jellywing", new float[]{0.0000f, 0.0000f, 0.0000f});
        BONES.put("driftback", List.of(new Bone("kelp", 0.0000f, 0.1400f, 0.0000f), new Bone("ffl", 0.1500f, -0.0800f, -0.2100f), new Bone("ffr", 0.1500f, -0.0800f, 0.2100f), new Bone("rfl", -0.2500f, -0.1000f, -0.1800f), new Bone("rfr", -0.2500f, -0.1000f, 0.1800f), new Bone("tail", -0.4200f, 0.0000f, 0.0000f)));
        ANIMS.put("driftback", List.of(new Anim("tail", true, 14.0000f, 0.0000f, 0.0000f, 3.0000f, 0.0000f, false), new Anim("ffl", true, 0.0000f, 0.0000f, 18.0000f, 3.0000f, 0.0000f, false), new Anim("ffr", true, 0.0000f, 0.0000f, -18.0000f, 3.0000f, 0.0000f, false), new Anim("rfl", true, 0.0000f, 0.0000f, 10.0000f, 3.0000f, 0.5000f, false), new Anim("rfr", true, 0.0000f, 0.0000f, -10.0000f, 3.0000f, 0.5000f, false), new Anim("kelp", true, 0.0000f, 0.0000f, 12.0000f, 3.6000f, 0.0000f, false), new Anim("body", false, 0.0000f, 1.0000f, 0.0000f, 4.0000f, 0.0000f, false)));
        BODY_PIVOT.put("driftback", new float[]{0.0000f, 0.0000f, 0.0000f});
        BONES.put("clam", List.of(new Bone("lid", -0.4200f, -0.0400f, 0.0000f)));
        ANIMS.put("clam", List.of(new Anim("lid", true, -16.0000f, 0.0000f, 0.0000f, 4.0000f, 0.0000f, true)));
        BODY_PIVOT.put("clam", new float[]{0.0000f, 0.0000f, 0.0000f});
        BONES.put("lumenbell", List.of(new Bone("arms", 0.0000f, 0.0000f, 0.0000f), new Bone("t", 0.0000f, 0.0000f, 0.0000f)));
        ANIMS.put("lumenbell", List.of(new Anim("t", true, 10.0000f, 0.0000f, 10.0000f, 2.2000f, 0.0000f, false), new Anim("arms", true, 6.0000f, 0.0000f, -6.0000f, 2.2000f, 0.5000f, false), new Anim("body", false, 0.0000f, 1.4000f, 0.0000f, 2.2000f, 0.0000f, false)));
        BODY_PIVOT.put("lumenbell", new float[]{0.0000f, 0.0000f, 0.0000f});
        BONES.put("gasbloom", List.of(new Bone("t", 0.0000f, -0.3000f, 0.0000f)));
        ANIMS.put("gasbloom", List.of(new Anim("t", true, 8.0000f, 0.0000f, 8.0000f, 3.0000f, 0.0000f, false), new Anim("body", false, 0.0000f, 1.0000f, 0.0000f, 3.5000f, 0.0000f, false), new Anim("body", true, 0.0000f, 6.0000f, 0.0000f, 7.0000f, 0.0000f, false)));
        BODY_PIVOT.put("gasbloom", new float[]{0.0000f, 0.0000f, 0.0000f});
        BONES.put("fangstalker", List.of(new Bone("finl", 0.1200f, -0.1000f, -0.1600f), new Bone("finr", 0.1200f, -0.1000f, 0.1600f), new Bone("jaw", 0.3200f, -0.1000f, 0.0000f), new Bone("tail", -0.4200f, 0.0000f, 0.0000f)));
        ANIMS.put("fangstalker", List.of(new Anim("tail", true, 0.0000f, 22.0000f, 0.0000f, 0.9000f, 0.0000f, false), new Anim("jaw", true, 16.0000f, 0.0000f, 0.0000f, 1.3000f, 0.0000f, true), new Anim("finl", true, 0.0000f, 0.0000f, 10.0000f, 1.8000f, 0.0000f, false), new Anim("finr", true, 0.0000f, 0.0000f, -10.0000f, 1.8000f, 0.0000f, false)));
        BODY_PIVOT.put("fangstalker", new float[]{0.0000f, 0.0000f, 0.0000f});
        BONES.put("blastfin", List.of(new Bone("finl", 0.0800f, -0.0200f, -0.2600f), new Bone("finr", 0.0800f, -0.0200f, 0.2600f), new Bone("tail", -0.2800f, 0.0000f, 0.0000f)));
        ANIMS.put("blastfin", List.of(new Anim("tail", true, 0.0000f, 30.0000f, 0.0000f, 0.4000f, 0.0000f, false), new Anim("finl", true, 0.0000f, 0.0000f, 30.0000f, 0.3000f, 0.0000f, false), new Anim("finr", true, 0.0000f, 0.0000f, -30.0000f, 0.3000f, 0.0000f, false), new Anim("body", false, 0.0000f, 0.6000f, 0.0000f, 1.0000f, 0.0000f, false)));
        BODY_PIVOT.put("blastfin", new float[]{0.0000f, 0.0000f, 0.0000f});
        BONES.put("burrowshark", List.of(new Bone("finl", 0.1200f, -0.1000f, -0.1600f), new Bone("finr", 0.1200f, -0.1000f, 0.1600f), new Bone("jaw", 0.3200f, -0.1000f, 0.0000f), new Bone("tail", -0.4200f, 0.0000f, 0.0000f)));
        ANIMS.put("burrowshark", List.of(new Anim("tail", true, 0.0000f, 22.0000f, 0.0000f, 0.9000f, 0.0000f, false), new Anim("jaw", true, 16.0000f, 0.0000f, 0.0000f, 1.3000f, 0.0000f, true), new Anim("finl", true, 0.0000f, 0.0000f, 10.0000f, 1.8000f, 0.0000f, false), new Anim("finr", true, 0.0000f, 0.0000f, -10.0000f, 1.8000f, 0.0000f, false)));
        BODY_PIVOT.put("burrowshark", new float[]{0.0000f, 0.0000f, 0.0000f});
        BONES.put("cavecrawler", List.of(new Bone("eyes", 0.1800f, 0.1400f, 0.0000f), new Bone("legl", 0.0000f, -0.0200f, -0.3000f), new Bone("legr", 0.0000f, -0.0200f, 0.3000f), new Bone("clawl", 0.2500f, 0.0000f, -0.2000f), new Bone("clawr", 0.2500f, 0.0000f, 0.2000f)));
        ANIMS.put("cavecrawler", List.of(new Anim("legl", true, 0.0000f, 12.0000f, 8.0000f, 0.5000f, 0.0000f, false), new Anim("legr", true, 0.0000f, -12.0000f, -8.0000f, 0.5000f, 0.5000f, false), new Anim("eyes", true, 0.0000f, 0.0000f, 8.0000f, 1.6000f, 0.0000f, false), new Anim("clawl", true, 0.0000f, 10.0000f, 0.0000f, 1.2000f, 0.0000f, false), new Anim("clawr", true, 0.0000f, -10.0000f, 0.0000f, 1.2000f, 0.5000f, false)));
        BODY_PIVOT.put("cavecrawler", new float[]{0.0000f, 0.0000f, 0.0000f});
        BONES.put("glowgrub", List.of(new Bone("head", 0.0700f, 0.0000f, 0.0000f), new Bone("tail", -0.0800f, 0.0000f, 0.0000f)));
        ANIMS.put("glowgrub", List.of(new Anim("head", true, 0.0000f, 12.0000f, 0.0000f, 1.4000f, 0.0000f, false), new Anim("tail", true, 0.0000f, -12.0000f, 0.0000f, 1.4000f, 0.0000f, false)));
        BODY_PIVOT.put("glowgrub", new float[]{0.0000f, 0.0000f, 0.0000f});
        BONES.put("shellsnake", List.of(new Bone("jaw", 0.3800f, -0.1000f, 0.0000f), new Bone("mid", 0.0000f, 0.0000f, 0.0000f), new Bone("tail", -0.3000f, 0.0000f, 0.0000f)));
        ANIMS.put("shellsnake", List.of(new Anim("mid", true, 0.0000f, 14.0000f, 0.0000f, 1.0000f, 0.0000f, false), new Anim("tail", true, 0.0000f, 22.0000f, 0.0000f, 1.0000f, 0.2500f, false), new Anim("jaw", true, 14.0000f, 0.0000f, 0.0000f, 2.0000f, 0.0000f, true)));
        BODY_PIVOT.put("shellsnake", new float[]{0.0000f, 0.0000f, 0.0000f});
        BONES.put("lampsquid", List.of(new Bone("finl", -0.4500f, 0.0000f, -0.1500f), new Bone("finr", -0.4500f, 0.0000f, 0.1500f), new Bone("arms", 0.2000f, 0.0000f, 0.0000f)));
        ANIMS.put("lampsquid", List.of(new Anim("arms", true, 0.0000f, 10.0000f, 10.0000f, 1.4000f, 0.0000f, false), new Anim("finl", true, 0.0000f, 0.0000f, 22.0000f, 1.0000f, 0.0000f, false), new Anim("finr", true, 0.0000f, 0.0000f, -22.0000f, 1.0000f, 0.0000f, false), new Anim("body", false, 0.0000f, 0.8000f, 0.0000f, 1.4000f, 0.5000f, false)));
        BODY_PIVOT.put("lampsquid", new float[]{0.0000f, 0.0000f, 0.0000f});
        BONES.put("volteel", List.of(new Bone("s1", 0.2000f, 0.0000f, 0.0000f), new Bone("s2", -0.1400f, 0.0000f, 0.0000f), new Bone("s3", -0.4800f, 0.0000f, 0.0000f)));
        ANIMS.put("volteel", List.of(new Anim("s1", true, 0.0000f, 10.0000f, 0.0000f, 0.9000f, 0.0000f, false), new Anim("s2", true, 0.0000f, 16.0000f, 0.0000f, 0.9000f, 0.1500f, false), new Anim("s3", true, 0.0000f, 24.0000f, 0.0000f, 0.9000f, 0.3000f, false)));
        BODY_PIVOT.put("volteel", new float[]{0.0000f, 0.0000f, 0.0000f});
        BONES.put("needlejaw", List.of(new Bone("jaw", 0.1200f, -0.1600f, 0.0000f), new Bone("lure", 0.1000f, 0.2600f, 0.0000f), new Bone("finl", 0.0000f, -0.0600f, -0.2000f), new Bone("finr", 0.0000f, -0.0600f, 0.2000f), new Bone("tail", -0.3000f, 0.0000f, 0.0000f)));
        ANIMS.put("needlejaw", List.of(new Anim("jaw", true, 18.0000f, 0.0000f, 0.0000f, 2.4000f, 0.0000f, true), new Anim("lure", true, 8.0000f, 0.0000f, 10.0000f, 1.8000f, 0.0000f, false), new Anim("tail", true, 0.0000f, 20.0000f, 0.0000f, 0.8000f, 0.0000f, false), new Anim("finl", true, 0.0000f, 0.0000f, 20.0000f, 0.6000f, 0.0000f, false), new Anim("finr", true, 0.0000f, 0.0000f, -20.0000f, 0.6000f, 0.0000f, false)));
        BODY_PIVOT.put("needlejaw", new float[]{0.0000f, 0.0000f, 0.0000f});
        BONES.put("devilfish", List.of(new Bone("finl", 0.0500f, -0.0800f, -0.1600f), new Bone("finr", 0.0500f, -0.0800f, 0.1600f), new Bone("tail", -0.4000f, 0.0000f, 0.0000f)));
        ANIMS.put("devilfish", List.of(new Anim("tail", true, 0.0000f, 18.0000f, 0.0000f, 1.0000f, 0.0000f, false), new Anim("finl", true, 0.0000f, 0.0000f, 16.0000f, 0.8000f, 0.0000f, false), new Anim("finr", true, 0.0000f, 0.0000f, -16.0000f, 0.8000f, 0.0000f, false)));
        BODY_PIVOT.put("devilfish", new float[]{0.0000f, 0.0000f, 0.0000f});
        BONES.put("riftwarper", List.of(new Bone("fint", 0.0000f, 0.1300f, 0.0000f), new Bone("finb", 0.0000f, -0.1200f, 0.0000f), new Bone("wl", 0.0000f, 0.0000f, -0.1100f), new Bone("wr", 0.0000f, 0.0000f, 0.1100f), new Bone("tail", -0.3200f, 0.0000f, 0.0000f)));
        ANIMS.put("riftwarper", List.of(new Anim("fint", true, 0.0000f, 12.0000f, 0.0000f, 0.8000f, 0.0000f, false), new Anim("finb", true, 0.0000f, -12.0000f, 0.0000f, 0.8000f, 0.0000f, false), new Anim("tail", true, 0.0000f, 20.0000f, 0.0000f, 0.8000f, 0.2500f, false), new Anim("wl", true, 0.0000f, 0.0000f, 24.0000f, 1.0000f, 0.0000f, false), new Anim("wr", true, 0.0000f, 0.0000f, -24.0000f, 1.0000f, 0.0000f, false)));
        BODY_PIVOT.put("riftwarper", new float[]{0.0000f, 0.0000f, 0.0000f});
        BONES.put("hollowkin", List.of(new Bone("hem", 0.0000f, -0.1400f, 0.0000f), new Bone("arml", 0.0000f, 0.2200f, -0.1600f), new Bone("armr", 0.0000f, 0.2200f, 0.1600f)));
        ANIMS.put("hollowkin", List.of(new Anim("hem", true, 6.0000f, 0.0000f, 6.0000f, 2.6000f, 0.0000f, false), new Anim("arml", true, 0.0000f, 0.0000f, 10.0000f, 2.6000f, 0.0000f, false), new Anim("armr", true, 0.0000f, 0.0000f, -10.0000f, 2.6000f, 0.5000f, false), new Anim("body", false, 0.0000f, 1.0000f, 0.0000f, 2.6000f, 0.0000f, false)));
        BODY_PIVOT.put("hollowkin", new float[]{0.0000f, 0.0000f, 0.0000f});
        BONES.put("sharkquid", List.of(new Bone("finl", 0.1200f, -0.1000f, -0.1600f), new Bone("finr", 0.1200f, -0.1000f, 0.1600f), new Bone("jaw", 0.3200f, -0.1000f, 0.0000f), new Bone("arms", -0.5800f, 0.0000f, 0.0000f)));
        ANIMS.put("sharkquid", List.of(new Anim("jaw", true, 16.0000f, 0.0000f, 0.0000f, 1.3000f, 0.0000f, true), new Anim("finl", true, 0.0000f, 0.0000f, 10.0000f, 1.8000f, 0.0000f, false), new Anim("finr", true, 0.0000f, 0.0000f, -10.0000f, 1.8000f, 0.0000f, false), new Anim("arms", true, 0.0000f, 14.0000f, 10.0000f, 1.1000f, 0.0000f, false)));
        BODY_PIVOT.put("sharkquid", new float[]{0.0000f, 0.0000f, 0.0000f});
    }
}
