#!/usr/bin/env python3
"""Builds FaultlineEmotes.zip: the bendy player body for emotes (Items 1.2.72, Emotes/EmoteRig.java).

How it works: every body part is an item_display holding the player's own PLAYER_HEAD (so the client has their skin) with an
item model whose base model stretches the head cube into that part's shape (faultline:emote/<part>). The core shader
(assets/minecraft/shaders/core/entity.vsh/.fsh, 26.2 = pack format 88) recognises a 64x64 skin drawn 1024*k blocks under
the camera, moves it back up by that much and remaps the cube's UVs to body part k of the skin (inner + outer layer).
The part ids, the UV remapping and the outer-layer blend come from Stable Player Display by bradleyq (CC0 1.0, public
domain: https://github.com/bradleyq/stable_player_display), ported here from 1.21.8's shaders to 26.2's.

Part ids (the plugin offsets each part's translation by -1024 * id on Y):
  0 head, 1 right arm, 2 left arm, 3 chest, 4 right leg, 5 left leg,
  6 right forearm, 7 left forearm, 8 waist, 9 right lower leg, 10 left lower leg

python3 tools/emote_pack.py <repo> [--check]   (--check compiles every shader variant with glslangValidator)
"""
import json, os, sys, zipfile, subprocess, tempfile, hashlib, re

PACK_FORMAT = 88  # 26.2

VSH = r"""#version 330

#if defined(PER_FACE_LIGHTING) || !defined(NO_CARDINAL_LIGHTING)
#moj_import <minecraft:light.glsl>
#endif
#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>
#moj_import <minecraft:sample_lightmap.glsl>

// Faultline emotes: the player-display part (Stable Player Display by bradleyq, CC0) only in variants that sample a texture
#if !defined(EMISSIVE) && !defined(APPLY_TEXTURE_MATRIX)
#define MAYBE_PLAYERDISP 1
#endif

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

#ifdef MAYBE_PLAYERDISP
uniform sampler2D Sampler0;
#endif

#ifndef NO_OVERLAY
uniform sampler2D Sampler1;
#endif

#ifndef EMISSIVE
uniform sampler2D Sampler2;
#endif

out float sphericalVertexDistance;
out float cylindricalVertexDistance;

#ifdef PER_FACE_LIGHTING
out vec4 vertexPerFaceColorBack;
out vec4 vertexPerFaceColorFront;
#else
out vec4 vertexColor;
#endif

#ifndef EMISSIVE
out vec4 lightMapColor;
#endif

#ifndef NO_OVERLAY
out vec4 overlayColor;
#endif

out vec2 texCoord0;

#ifdef MAYBE_PLAYERDISP
out vec2 texCoord1;
out float part;

#define SPACING 1024.0
#define MAXRANGE (0.5 * SPACING)
#define SKINRES 64
#define FACERES 8

#define SLIMCHECK0 vec2(54.0 / float(SKINRES), 20.0 / float(SKINRES))
#define SLIMCHECK1 vec2(55.0 / float(SKINRES), 20.0 / float(SKINRES))

const vec4[] subuvs = vec4[](
    vec4(4.0,  0.0,  8.0,  4.0 ), // 4x4x12
    vec4(8.0,  0.0,  12.0, 4.0 ),
    vec4(0.0,  4.0,  4.0,  16.0),
    vec4(4.0,  4.0,  8.0,  16.0),
    vec4(8.0,  4.0,  12.0, 16.0),
    vec4(12.0, 4.0,  16.0, 16.0),
    vec4(4.0,  0.0,  7.0,  4.0 ), // 4x3x12
    vec4(7.0,  0.0,  10.0, 4.0 ),
    vec4(0.0,  4.0,  4.0,  16.0),
    vec4(4.0,  4.0,  7.0,  16.0),
    vec4(7.0,  4.0,  11.0, 16.0),
    vec4(11.0, 4.0,  14.0, 16.0),
    vec4(4.0,  0.0,  12.0, 4.0 ), // 4x8x12
    vec4(12.0,  0.0, 20.0, 4.0 ),
    vec4(0.0,  4.0,  4.0,  16.0),
    vec4(4.0,  4.0,  12.0, 16.0),
    vec4(12.0, 4.0,  16.0, 16.0),
    vec4(16.0, 4.0,  24.0, 16.0)
);

const vec2[] origins = vec2[](
    vec2(40.0, 16.0), // right arm
    vec2(40.0, 32.0),
    vec2(32.0, 48.0), // left arm
    vec2(48.0, 48.0),
    vec2(16.0, 16.0), // torso
    vec2(16.0, 32.0),
    vec2(0.0,  16.0), // right leg
    vec2(0.0,  32.0),
    vec2(16.0, 48.0), // left leg
    vec2(0.0,  48.0)
);

const int[] faceremap = int[](0, 0, 1, 1, 2, 3, 4, 5);
#endif

void main() {
    vec3 pos = Position;
    texCoord0 = UV0;

#ifdef MAYBE_PLAYERDISP
    part = 0.0;
    texCoord1 = vec2(0.0);
    ivec2 dim = textureSize(Sampler0, 0);
    // a world view (not the GUI), a 64x64 texture (a skin), real fog (not the first-person hand), far under the camera
    if (abs(ProjMat[2][3]) > 10e-6 && dim.x == SKINRES && dim.y == SKINRES && FogRenderDistanceEnd > FogRenderDistanceStart && Position.y < -MAXRANGE) {
        int partId = -int((Position.y - MAXRANGE) / SPACING);
        if (partId > 0 && partId <= 10) {
            part = float(partId);
            partId -= 1;
            vec4 samp1 = texture(Sampler0, SLIMCHECK0);
            vec4 samp2 = texture(Sampler0, SLIMCHECK1);
            bool slim = samp1.a == 0.0 || (((samp1.r + samp1.g + samp1.b) == 0.0) && ((samp2.r + samp2.g + samp2.b) == 0.0) && samp1.a == 1.0 && samp2.a == 1.0);
            int partIdMod = partId % 5;
            int outerLayer = (gl_VertexID / 24) % 2;
            int vertexId = gl_VertexID % 4;
            int faceId = (gl_VertexID % 24) / 4;
            ivec2 faceIdTmp = ivec2(round(UV0 * SKINRES));

            vec2 UVout = origins[2 * partIdMod + outerLayer];
            vec2 UVout2 = origins[2 * partIdMod];

            if ((faceId != 1 && vertexId >= 2) || (faceId == 1 && vertexId <= 1)) {
                faceIdTmp.y -= FACERES;
            }
            if (vertexId == 0 || vertexId == 3) {
                faceIdTmp.x -= FACERES;
            }

            faceIdTmp /= FACERES;
            faceId = (faceIdTmp.x % 4) + 4 * faceIdTmp.y;
            faceId = faceremap[clamp(faceId, 0, 7)];
            int subuvIndex = faceId;

            if (slim && (partIdMod == 0 || partIdMod == 1)) {
                subuvIndex += 6;
            } else if (partIdMod == 2) {
                subuvIndex += 12;
            }

            vec4 subuv = subuvs[subuvIndex];
            vec2 offset = vec2(0.0);

            // split model: upper and lower halves of each limb / the torso
            if (faceId >= 2) {
                subuv.w -= 6.0;
                if (partId >= 5) {
                    subuv.yw += 6.0;
                }
            }

            if (faceId == 1) {
                if (vertexId == 0) offset += subuv.zw;
                else if (vertexId == 1) offset += subuv.xw;
                else if (vertexId == 2) offset += subuv.xy;
                else offset += subuv.zy;
            } else {
                if (vertexId == 0) offset += subuv.zy;
                else if (vertexId == 1) offset += subuv.xy;
                else if (vertexId == 2) offset += subuv.xw;
                else offset += subuv.zw;
            }

            UVout += offset;
            UVout2 += offset;
            texCoord0 = UVout / float(SKINRES);
            texCoord1 = UVout2 / float(SKINRES);
            pos.y += SPACING * float(partId + 1);
        }
    }
#endif

    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);

#ifdef PER_FACE_LIGHTING
    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, Normal);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light, Color);
    vertexPerFaceColorFront = minecraft_mix_light_separate(light, Color);
#elif defined(NO_CARDINAL_LIGHTING)
    vertexColor = Color;
#else
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
#endif

#ifndef EMISSIVE
    lightMapColor = sample_lightmap(Sampler2, UV2);
#endif

#ifndef NO_OVERLAY
    overlayColor = texelFetch(Sampler1, UV1, 0);
#endif

#ifdef APPLY_TEXTURE_MATRIX
    texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
#endif
}
"""

FSH = r"""#version 330

#moj_import <minecraft:fog.glsl>
#moj_import <minecraft:dynamictransforms.glsl>

#if !defined(EMISSIVE) && !defined(APPLY_TEXTURE_MATRIX)
#define MAYBE_PLAYERDISP 1
#endif

uniform sampler2D Sampler0;

#ifdef DISSOLVE
uniform sampler2D DissolveMaskSampler;
#endif

in float sphericalVertexDistance;
in float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
in vec4 vertexPerFaceColorBack;
in vec4 vertexPerFaceColorFront;
#else
in vec4 vertexColor;
#endif

#ifndef EMISSIVE
in vec4 lightMapColor;
#endif

#ifndef NO_OVERLAY
in vec4 overlayColor;
#endif

in vec2 texCoord0;

#ifdef MAYBE_PLAYERDISP
in vec2 texCoord1;
in float part;

#define FADERANGE 12.0
#define FADEBIAS 8.0
#define MINALPHA 0.25

const mat4 bayer4 = mat4( 0.0 / 16.0,  8.0 / 16.0,  2.0 / 16.0, 10.0 / 16.0,
                         12.0 / 16.0,  4.0 / 16.0, 14.0 / 16.0,  6.0 / 16.0,
                          3.0 / 16.0, 11.0 / 16.0,  1.0 / 16.0,  9.0 / 16.0,
                         15.0 / 16.0,  7.0 / 16.0, 13.0 / 16.0,  5.0 / 16.0);
#endif

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0);
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

#ifdef PER_FACE_LIGHTING
    vec4 faceVertexColor = gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack;
#else
    vec4 faceVertexColor = vertexColor;
#endif

#ifdef DISSOLVE
    if (faceVertexColor.a < texture(DissolveMaskSampler, texCoord0).a) {
        discard;
    }
    faceVertexColor.a = 1.0;
#endif

    color *= faceVertexColor * ColorModulator;
#ifndef NO_OVERLAY
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
#endif
#ifndef EMISSIVE
    color *= lightMapColor;
#endif

    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);

#ifdef MAYBE_PLAYERDISP
    // the outer skin layer over a body part: see-through pixels show the inner layer instead of nothing
    if (part > 1.0 - 10e-6 && fragColor.a < 1.0) {
        fragColor.a = max(fragColor.a, MINALPHA);
        vec3 underCol = texture(Sampler0, texCoord1).rgb;
        vec3 trueMix = mix(underCol, fragColor.rgb, fragColor.a);
        float fade = mix(fragColor.a, 1.0, clamp((cylindricalVertexDistance - FADEBIAS) / (FADERANGE - FADEBIAS), 0.0, 1.0));
        fragColor = vec4((trueMix - (1.0 - fade) * underCol) / fade, fade);
        if (fragColor.a < bayer4[int(gl_FragCoord.x) % 4][int(gl_FragCoord.y) % 4] + (0.5 / 16.0)) {
            discard;
        } else {
            fragColor.a = 1.0;
        }
    }
#endif
}
"""

# part -> (scale x, y, z), translation y (px). Every part's cube: centred on the display in x/z, hanging DOWN from it
# (y -0.5*sy .. 0 blocks), except the head, which sits ON it. The plugin poses them (EmoteRig).
ARM, ARM_SLIM, LEG, TORSO = (0.46875, 0.75, 0.46875), (0.3515625, 0.75, 0.46875), (0.46875, 0.75, 0.46875), (0.9375, 0.75, 0.46875)
PARTS = {
    "head": ((0.9375, 0.9375, 0.9375), 7.5, None),
    "right_arm": (ARM, 0, ARM_SLIM), "left_arm": (ARM, 0, ARM_SLIM),
    "right_forearm": (ARM, 0, ARM_SLIM), "left_forearm": (ARM, 0, ARM_SLIM),
    "torso": (TORSO, 0, None), "waist": (TORSO, 0, None),
    "right_leg": (LEG, 0, None), "left_leg": (LEG, 0, None),
    "lower_right_leg": (LEG, 0, None), "lower_left_leg": (LEG, 0, None),
}


def base_model(scale, ty):
    return {"display": {"thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, ty, 0], "scale": list(scale)}}}


def item_def(part, slim):
    fallback = {"type": "minecraft:special", "base": f"faultline:emote/{part}", "model": {"type": "minecraft:player_head"}}
    if not slim:
        return {"model": fallback}
    return {"model": {"type": "minecraft:select", "property": "minecraft:custom_model_data",
                      "cases": [{"when": "slim", "model": {"type": "minecraft:special", "base": f"faultline:emote/slim/{part}",
                                                            "model": {"type": "minecraft:player_head"}}}],
                      "fallback": fallback}}


def build(repo):
    out = os.path.join(repo, "FaultlineEmotes.zip")
    files = {
        "pack.mcmeta": json.dumps({"pack": {"pack_format": PACK_FORMAT, "min_format": PACK_FORMAT, "max_format": PACK_FORMAT,
                                            "description": "Faultline SMP: emote bodies (shader from Stable Player Display, CC0)"}}, indent=2),
        "assets/minecraft/shaders/core/entity.vsh": VSH,
        "assets/minecraft/shaders/core/entity.fsh": FSH,
        "CREDITS.txt": "The player-display shader technique, part ids and UV tables are from Stable Player Display by bradleyq\n"
                       "(https://github.com/bradleyq/stable_player_display), CC0 1.0 Universal. Ported to Minecraft 26.2 for Faultline SMP.\n",
    }
    for part, (scale, ty, slim) in PARTS.items():
        files[f"assets/faultline/models/emote/{part}.json"] = json.dumps(base_model(scale, ty))
        if slim:
            files[f"assets/faultline/models/emote/slim/{part}.json"] = json.dumps(base_model(slim, ty))
        files[f"assets/faultline/items/emote/{part}.json"] = json.dumps(item_def(part, slim is not None), indent=1)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for name in sorted(files):
            zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(zi, files[name])
    sha = hashlib.sha1(open(out, "rb").read()).hexdigest()
    print(out, os.path.getsize(out), "bytes, sha1", sha)
    return files


INCLUDES = ["fog", "dynamictransforms", "projection", "light", "sample_lightmap", "globals"]


def check(files, include_dir):
    """Compiles the shaders the way the game would for every define combination (includes inlined)."""
    inc = {n: open(os.path.join(include_dir, n + ".glsl")).read() for n in INCLUDES if os.path.exists(os.path.join(include_dir, n + ".glsl"))}

    def resolve(src):
        seen = set()
        def rep(m):
            n = m.group(1)
            if n in seen: return ""
            seen.add(n)
            body = inc[n]
            return "\n".join(l for l in body.splitlines() if not l.startswith("#version"))
        return re.sub(r"#moj_import <minecraft:(\w+)\.glsl>", rep, src)

    combos = []
    names = ["ALPHA_CUTOUT 0.1", "EMISSIVE", "NO_OVERLAY", "NO_CARDINAL_LIGHTING", "PER_FACE_LIGHTING", "APPLY_TEXTURE_MATRIX", "DISSOLVE"]
    for mask in range(1 << len(names)):
        combos.append([names[i] for i in range(len(names)) if mask >> i & 1])
    bad = 0
    with tempfile.TemporaryDirectory() as td:
        for stage, key in (("vert", "assets/minecraft/shaders/core/entity.vsh"), ("frag", "assets/minecraft/shaders/core/entity.fsh")):
            for c in combos:
                src = resolve(files[key])
                lines = src.split("\n", 1)
                src = lines[0] + "\n" + "".join(f"#define {d}\n" for d in c) + lines[1]
                p = os.path.join(td, "s." + stage)
                open(p, "w").write(src)
                r = subprocess.run(["glslangValidator", "-S", stage, p], capture_output=True, text=True)
                if r.returncode != 0:
                    bad += 1
                    if bad < 4: print("FAIL", stage, c, r.stdout[-800:])
    # the vertex outputs must match the fragment inputs for every combo
    print("shader variants compiled:", len(combos) * 2 - bad, "/", len(combos) * 2)
    return bad == 0


if __name__ == "__main__":
    repo = sys.argv[1]
    f = build(repo)
    if "--check" in sys.argv:
        inc = sys.argv[sys.argv.index("--check") + 1] if len(sys.argv) > sys.argv.index("--check") + 1 else None
        if not inc: print("--check <dir with the 26.2 include/*.glsl>"); sys.exit(2)
        sys.exit(0 if check(f, inc) else 1)
