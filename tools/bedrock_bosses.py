#!/usr/bin/env python3
"""Bedrock (Geyser) models for Diamond Jacob and the Kraken, built from the SAME Java models (jacob_models.py, kraken_models.py).

Geyser can't draw display entities, so Bedrock players see each boss as ONE armor stand (FaultlineBosses' stand-in) wearing
paper whose item model this pack maps to a helmet attachable drawing the whole boss. A stand can only turn left/right, so
the motion is in the pack: every boss state is its own item with its own looping animation, and the plugin swaps the item
(`bedrockState` in FaultlineBosses): Jacob jacob_b_<armor|blaze>_<idle|walk|swing|slam|ride>, the Kraken
kraken_b_<1|2|3|dead>_<idle|swim|attack> (the number = his skin: veins from phase 2, grey when dead).

  python3 tools/bedrock_bosses.py <out dir, e.g. bedrock> [--preview tools/previews]

writes <out>/FaultlineBossModels.mcpack + <out>/faultline_bosses_mappings.json (Geyser: packs/ and custom_mappings/).

Frames: the Java models are built in "entity" pixels (front +z, right -x, up +y; the joints of renderRig); Bedrock geometry
is the same with z mirrored (front -z), faces named by where they end up. Bones carry the joints, so the pack's animations
swing arms, legs, cape and every arm segment about the right points.
"""
import json, math, os, sys, zipfile, shutil
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import jacob_models as J
import kraken_models as K

C = 8.0
NAME_BY_NORMAL = {(1, 0, 0): "east", (-1, 0, 0): "west", (0, 1, 0): "up", (0, -1, 0): "down", (0, 0, 1): "south", (0, 0, -1): "north"}
NORMAL = {"east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0), "south": (0, 0, 1), "north": (0, 0, -1)}


def jw(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f: json.dump(obj, f, indent=1)


class Sheet:
    """Several Java textures stacked into one Bedrock texture (an attachable has one)."""
    def __init__(self):
        self.parts, self.y = [], 0
    def add(self, key, img):
        img = img.convert("RGBA")
        self.parts.append((key, img, self.y)); off = self.y; self.y += img.size[1]
        return off
    def image(self, swap=None):
        w = max(i.size[0] for _, i, _ in self.parts)
        out = Image.new("RGBA", (w, self.y), (0, 0, 0, 0))
        for key, img, y in self.parts:
            out.paste((swap or {}).get(key, img).convert("RGBA"), (0, y))
        return out


def tex_img(atlas):
    return Image.fromarray(np.clip(atlas.img, 0, 255).astype(np.uint8), "RGBA")


def transform_elements(els, tex_size, tex_off, M=None, s=(1, 1, 1), t=(0, 0, 0)):
    """Java elements (model units, joint at C) -> cubes in Java-entity px: p' = M . ((p - C) * s) + t.
    M is an axis permutation with signs (keeps boxes axis-aligned). Returns [(lo, hi, faces{java_name: uv}, rot)]."""
    M = np.eye(3) if M is None else np.array(M, dtype=float)
    out = []
    k = tex_size / 16.0
    for e in els:
        a = (np.array(e["from"]) - C) * s; b = (np.array(e["to"]) - C) * s
        corners = [M @ np.array([x, y, z]) for x in (a[0], b[0]) for y in (a[1], b[1]) for z in (a[2], b[2])]
        lo = np.min(corners, axis=0) + t; hi = np.max(corners, axis=0) + t
        faces = {}
        for fn, fd in e["faces"].items():
            n = M @ np.array(NORMAL[fn])
            nn = NAME_BY_NORMAL[tuple(int(round(v)) for v in n)]
            u1, v1, u2, v2 = fd["uv"]
            faces[nn] = (u1 * k, v1 * k + tex_off, (u2 - u1) * k, (v2 - v1) * k)
        rot = None
        if e.get("rotation"):
            r = e["rotation"]
            ax = M @ np.array({"x": (1, 0, 0), "y": (0, 1, 0), "z": (0, 0, 1)}[r["axis"]])
            i = int(np.argmax(np.abs(ax))); sign = 1 if ax[i] > 0 else -1
            o = M @ ((np.array(r["origin"]) - C) * s) + t
            rot = ("xyz"[i], r["angle"] * sign, o)
        out.append((lo, hi, faces, rot))
    return out


def bed_cube(lo, hi, faces, rot, inflate=0.0):
    """Java-entity px -> a Bedrock cube (z mirrored)."""
    origin = [lo[0], lo[1], -hi[2]]
    size = [hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]]
    cube = {"origin": [round(v, 4) for v in origin], "size": [round(v, 4) for v in size], "uv": {}}
    for fn, (x, y, w, h) in faces.items():
        n = NORMAL[fn]; bn = NAME_BY_NORMAL[(n[0], n[1], -n[2])]
        cube["uv"][bn] = {"uv": [round(x, 4), round(y, 4)], "uv_size": [round(w, 4), round(h, 4)]}
    if rot:
        axis, ang, o = rot
        # mirroring z flips the sense of turns about x and y; Bedrock's x and y turns already run the other way round
        rr = {"x": [ang, 0, 0], "y": [0, ang, 0], "z": [0, 0, ang]}[axis]
        cube["rotation"] = rr
        cube["pivot"] = [round(o[0], 4), round(o[1], 4), round(-o[2], 4)]
    if inflate: cube["inflate"] = inflate
    return cube


def bp(p):
    """A Java-entity point -> Bedrock."""
    return [round(p[0], 4), round(p[1], 4), round(-p[2], 4)]


# ======================================================================================================== Diamond Jacob

JOINT = {"leg_r": (-2, 12, 0), "leg_l": (2, 12, 0), "body": (0, 12, 0), "arm_r": (-5.5, 24, 0), "arm_l": (5.5, 24, 0),
         "head": (0, 24, 0), "cape_top": (0, 23.6, -3.1), "cape_low": (0, 11.3, -3.3)}
PARENT = {"body": "root", "head": "body", "arm_r": "body", "arm_l": "body", "leg_r": "root", "leg_l": "root",
          "cape_top": "body", "cape_low": "cape_top", "weapon": "arm_r"}
HAND = (-5.5, 13.4, 1.0)


def jacob_geo():
    sheet = Sheet()
    forms = {}
    for form, pal, blaze, seed in (("armor", J.DIAMOND, False, 7), ("blaze", J.BLAZE, True, 11)):
        atlas, parts = J.knight(pal, blaze, seed)
        forms[form] = (tex_img(atlas), parts)
    matlas, mel = J.mace(13)
    a_img, parts = forms["armor"]
    off_body = sheet.add("body", a_img)
    off_mace = sheet.add("mace", tex_img(matlas))
    N = a_img.size[0]
    names = {"head": "head", "body": "body", "arm_r": "arm", "arm_l": "arm", "leg_r": "leg", "leg_l": "leg",
             "cape_top": "cape_top", "cape_low": "cape_low"}
    bones = [{"name": "root", "pivot": [0, 0, 0]}]
    for bone, src in names.items():
        cubes = [bed_cube(*c) for c in transform_elements(parts[src], N, off_body, t=JOINT[bone])]
        bones.append({"name": bone, "parent": PARENT[bone], "pivot": bp(JOINT[bone]), "cubes": cubes})
    # the war mace in his right fist: handle along +y in its model, turned to point forward (+z) like a held item
    Rx90 = [[1, 0, 0], [0, 0, -1], [0, 1, 0]]
    grip = np.array([8.0, 2.0, 8.0])
    sc = 0.8
    cubes = []
    for (lo, hi, faces, rot) in transform_elements(mel, matlas.size, off_mace, M=Rx90, s=(sc, sc, sc)):
        g = np.array(Rx90) @ ((grip - C) * sc)
        cubes.append(bed_cube(lo - g + HAND, hi - g + HAND, faces, None if rot is None else (rot[0], rot[1], rot[2] - g + HAND)))
    bones.append({"name": "weapon", "parent": "arm_r", "pivot": bp(HAND), "cubes": cubes})
    w, h = sheet.image().size
    geo = {"format_version": "1.16.0", "minecraft:geometry": [{
        "description": {"identifier": "geometry.faultline.jacob_b", "texture_width": w, "texture_height": h,
                        "visible_bounds_width": 4, "visible_bounds_height": 4, "visible_bounds_offset": [0, 1.5, 0]},
        "bones": bones}]}
    textures = {"armor": sheet.image(), "blaze": sheet.image({"body": forms["blaze"][0]})}
    return geo, textures, forms


def keys(frames):
    """[(t, value)] -> Bedrock keyframes (linear between them)."""
    return {f"{round(t, 3)}": [round(v, 3) for v in val] for t, val in frames}


def wave(length, n, fn):
    return keys([(length * i / n, fn(i / n)) for i in range(n + 1)])


def jacob_anims():
    S = lambda f, a, ph=0.0: (lambda u: a * math.sin(2 * math.pi * (f * u + ph)))
    A = {}
    # IDLE (3.2 s): breathing, the weight shifting, the cape stirring, the mace resting
    L = 3.2
    A["idle"] = {"loop": True, "animation_length": L, "bones": {
        "body": {"rotation": wave(L, 16, lambda u: [3 + 1.6 * math.sin(2 * math.pi * u), 2 * math.sin(2 * math.pi * u + 1), 1.2 * math.sin(2 * math.pi * u)]),
                 "position": wave(L, 16, lambda u: [0, -0.25 + 0.25 * math.sin(2 * math.pi * u), 0])},
        "head": {"rotation": wave(L, 16, lambda u: [-4 - 2 * math.sin(2 * math.pi * u), 6 * math.sin(2 * math.pi * u * 0.5), 0])},
        "arm_r": {"rotation": wave(L, 16, lambda u: [-28 + 3 * math.sin(2 * math.pi * u), 0, 8])},
        "arm_l": {"rotation": wave(L, 16, lambda u: [-8 - 3 * math.sin(2 * math.pi * u), 0, -10])},
        "leg_r": {"rotation": keys([(0, [-6, 0, 4]), (L, [-6, 0, 4])])},
        "leg_l": {"rotation": keys([(0, [8, 0, -4]), (L, [8, 0, -4])])},
        "cape_top": {"rotation": wave(L, 16, lambda u: [8 + 3 * math.sin(2 * math.pi * u + 0.6), 0, 2 * math.sin(2 * math.pi * u)])},
        "cape_low": {"rotation": wave(L, 16, lambda u: [4 + 4 * math.sin(2 * math.pi * u + 1.4), 0, 0])}}}
    # WALK (1.0 s a stride): heavy armoured steps, the body dipping on each footfall, the cape streaming behind
    L = 1.0
    s = lambda u: math.sin(2 * math.pi * u)
    A["walk"] = {"loop": True, "animation_length": L, "bones": {
        "leg_r": {"rotation": wave(L, 16, lambda u: [-30 * s(u), 0, 0])},
        "leg_l": {"rotation": wave(L, 16, lambda u: [30 * s(u), 0, 0])},
        "arm_r": {"rotation": wave(L, 16, lambda u: [-30 + 12 * s(u - 0.08), 0, 8])},
        "arm_l": {"rotation": wave(L, 16, lambda u: [-6 - 26 * s(u - 0.08), 0, -8])},
        "body": {"rotation": wave(L, 16, lambda u: [9, 7 * s(u), 3 * s(u)]),
                 "position": wave(L, 16, lambda u: [0, -1.4 * abs(s(u)), 0])},
        "head": {"rotation": wave(L, 16, lambda u: [-8, -6 * s(u), -2 * s(u)])},
        "cape_top": {"rotation": wave(L, 16, lambda u: [22 + 6 * s(2 * u), 0, 3 * s(u)])},
        "cape_low": {"rotation": wave(L, 16, lambda u: [14 + 10 * s(2 * u + 0.15), 0, 0])}}}
    # SWING (1.2 s): a one-handed overhead chop, wound up over the shoulder, cut down across the body
    L = 1.2
    A["swing"] = {"loop": True, "animation_length": L, "bones": {
        "arm_r": {"rotation": keys([(0, [-30, 0, 8]), (0.45, [-165, 0, 20]), (0.55, [-160, 0, 18]), (0.68, [-35, 0, -12]), (0.85, [-30, 0, -6]), (L, [-30, 0, 8])])},
        "arm_l": {"rotation": keys([(0, [-8, 0, -10]), (0.45, [-40, 0, -26]), (0.68, [18, 0, -18]), (L, [-8, 0, -10])])},
        "body": {"rotation": keys([(0, [6, 0, 0]), (0.45, [-8, 22, 0]), (0.68, [26, -24, 0]), (0.85, [20, -18, 0]), (L, [6, 0, 0])]),
                 "position": keys([(0, [0, 0, 0]), (0.68, [0, -2.5, 0]), (L, [0, 0, 0])])},
        "head": {"rotation": keys([(0, [-4, 0, 0]), (0.45, [-6, -16, 0]), (0.68, [8, 18, 0]), (L, [-4, 0, 0])])},
        "leg_r": {"rotation": keys([(0, [-6, 0, 0]), (0.45, [14, 0, 0]), (0.68, [-30, 0, 0]), (L, [-6, 0, 0])])},
        "leg_l": {"rotation": keys([(0, [8, 0, 0]), (0.45, [-14, 0, 0]), (0.68, [22, 0, 0]), (L, [8, 0, 0])])},
        "cape_top": {"rotation": keys([(0, [8, 0, 0]), (0.45, [4, 0, 6]), (0.68, [26, 0, -8]), (L, [8, 0, 0])])},
        "cape_low": {"rotation": keys([(0, [4, 0, 0]), (0.55, [2, 0, 0]), (0.8, [20, 0, 0]), (L, [4, 0, 0])])}}}
    # SLAM (1.6 s): both hands up, a beat at the top, down into the ground with the whole body
    L = 1.6
    A["slam"] = {"loop": True, "animation_length": L, "bones": {
        "arm_r": {"rotation": keys([(0, [-30, 0, 8]), (0.55, [-176, 0, 6]), (0.75, [-178, 0, 6]), (0.86, [-36, 0, 4]), (1.15, [-38, 0, 4]), (L, [-30, 0, 8])])},
        "arm_l": {"rotation": keys([(0, [-8, 0, -10]), (0.55, [-166, 0, -10]), (0.75, [-170, 0, -8]), (0.86, [-32, 0, -6]), (1.15, [-34, 0, -6]), (L, [-8, 0, -10])])},
        "body": {"rotation": keys([(0, [6, 0, 0]), (0.55, [-15, 0, 0]), (0.75, [-18, 0, 0]), (0.86, [36, 0, 0]), (1.15, [34, 0, 0]), (L, [6, 0, 0])]),
                 "position": keys([(0, [0, 0, 0]), (0.55, [0, 0.8, 0]), (0.86, [0, -5, 0]), (1.15, [0, -4.5, 0]), (L, [0, 0, 0])])},
        "head": {"rotation": keys([(0, [-4, 0, 0]), (0.55, [-14, 0, 0]), (0.86, [14, 0, 0]), (L, [-4, 0, 0])])},
        "leg_r": {"rotation": keys([(0, [-6, 0, 0]), (0.55, [10, 0, 0]), (0.86, [-32, 0, 0]), (1.15, [-30, 0, 0]), (L, [-6, 0, 0])])},
        "leg_l": {"rotation": keys([(0, [8, 0, 0]), (0.55, [-14, 0, 0]), (0.86, [26, 0, 0]), (1.15, [24, 0, 0]), (L, [8, 0, 0])])},
        "cape_top": {"rotation": keys([(0, [8, 0, 0]), (0.55, [0, 0, 0]), (0.86, [34, 0, 0]), (1.2, [20, 0, 0]), (L, [8, 0, 0])])},
        "cape_low": {"rotation": keys([(0, [4, 0, 0]), (0.6, [-4, 0, 0]), (0.95, [30, 0, 0]), (L, [4, 0, 0])])}}}
    # RIDE (2.4 s, phase 1 on the hawk): knees gripping, bow arm out, swaying with the wingbeats
    L = 2.4
    A["ride"] = {"loop": True, "animation_length": L, "bones": {
        "leg_r": {"rotation": keys([(0, [-72, 0, 16]), (L, [-72, 0, 16])])},
        "leg_l": {"rotation": keys([(0, [-72, 0, -16]), (L, [-72, 0, -16])])},
        "root": {"position": keys([(0, [0, -8, 0]), (L, [0, -8, 0])])},
        "body": {"rotation": wave(L, 12, lambda u: [6 + 2 * s(u), 0, 2 * s(u)])},
        "arm_r": {"rotation": wave(L, 12, lambda u: [-34 + 4 * s(u), 0, 12])},
        "arm_l": {"rotation": wave(L, 12, lambda u: [-86 - 3 * s(u), -30, 0])},
        "head": {"rotation": wave(L, 12, lambda u: [4, 10 * s(u * 0.5), 0])},
        "cape_top": {"rotation": wave(L, 12, lambda u: [40 + 8 * s(2 * u), 0, 0])},
        "cape_low": {"rotation": wave(L, 12, lambda u: [24 + 10 * s(2 * u + 0.2), 0, 0])}}}
    return A


# ======================================================================================================== the Kraken

ARMS, SEGS = 8, 9
CENTER = (0, 24, 0)


def arm_root(i):
    a = math.radians(i * 45 + 22.5)
    return (8.8 * math.cos(a), CENTER[1] - 9.2, 8.8 * math.sin(a)), a


def kraken_geo():
    sheet = Sheet()
    skins = {}
    for k, P in K.SKIN.items():
        atlas, els = K.mantle(P, 31)
        skins[k] = (tex_img(atlas), els)
    tatlas, seg, tip = K.tentacle(K.SKIN[1], 37)
    batlas, pupil_el, beak_el = K.bits(41)
    m_img, m_els = skins[1]
    off_m = sheet.add("skin", m_img)
    off_t = sheet.add("tentacle", tex_img(tatlas))
    off_b = sheet.add("bits", tex_img(batlas))
    Nm, Nt, Nb = m_img.size[0], tatlas.size, batlas.size
    bones = [{"name": "root", "pivot": [0, 0, 0]},
             {"name": "mantle", "parent": "root", "pivot": bp(CENTER),
              "cubes": [bed_cube(*c) for c in transform_elements(m_els, Nm, off_m, t=CENTER)]}]
    # eyes' pupils and the beak (two halves, the lower one flipped), like the Java render at rest
    cubes = []
    for x in (-7.5, 7.5):
        cubes += [bed_cube(*c) for c in transform_elements(pupil_el, Nb, off_b, s=(0.55, 0.55, 0.55), t=(x, CENTER[1], 16.6))]
    bones.append({"name": "eyes", "parent": "mantle", "pivot": bp(CENTER), "cubes": cubes})
    beak = (0, CENTER[1] - 12.5, 6)
    bones.append({"name": "beak_t", "parent": "mantle", "pivot": bp(beak),
                  "cubes": [bed_cube(*c) for c in transform_elements(beak_el, Nb, off_b, s=(0.55, 0.55, 0.55), t=beak)]})
    flipX = [[1, 0, 0], [0, -1, 0], [0, 0, -1]]
    lo_beak = (beak[0], beak[1] - 1.6, beak[2])
    bones.append({"name": "beak_b", "parent": "mantle", "pivot": bp(lo_beak),
                  "cubes": [bed_cube(*c) for c in transform_elements(beak_el, Nb, off_b, M=flipX, s=(0.5, 0.5, 0.5), t=lo_beak)]})
    # 8 arms, 9 segments each: a chain of bones laid out straight forward (+z) from its root, turned outward by
    # its arm bone and tipped down by the first segment; the pack's animations bend each segment
    for i in range(ARMS):
        P, a = arm_root(i)
        yaw = math.degrees(math.atan2(math.cos(a), math.sin(a)))   # turns +z (Java front) to point out along (cos a, sin a)
        bones.append({"name": f"arm{i}", "parent": "mantle", "pivot": bp(P), "rotation": [0, round(yaw, 3), 0]})
        z = 0.0
        for j in range(SEGS):
            L = 4 * (3.2 - 0.18 * j)
            fxy = (2.3 - 0.2 * j) / (1.0 if j == SEGS - 1 else 1.5)
            els = tip if j == SEGS - 1 else seg
            t = (P[0], P[1], P[2] + z + L / 2)
            cubes = [bed_cube(*c) for c in transform_elements(els, Nt, off_t, s=(fxy, fxy, L / 16.0), t=t)]
            bones.append({"name": f"a{i}s{j}", "parent": f"arm{i}" if j == 0 else f"a{i}s{j - 1}",
                          "pivot": bp((P[0], P[1], P[2] + z)), "cubes": cubes,
                          **({"rotation": [60, 0, 0]} if j == 0 else {})})
            z += L
    w, h = sheet.image().size
    geo = {"format_version": "1.16.0", "minecraft:geometry": [{
        "description": {"identifier": "geometry.faultline.kraken_b", "texture_width": w, "texture_height": h,
                        "visible_bounds_width": 16, "visible_bounds_height": 14, "visible_bounds_offset": [0, 0, 0]},
        "bones": bones}]}
    textures = {k: sheet.image({"skin": skins[k][0]}) for k in skins}
    return geo, textures


def kraken_anims():
    A = {}
    T = 2 * math.pi
    def arm_bones(L, n, base0, curl, amp, sway, speed, flare=None):
        b = {}
        for i in range(ARMS):
            ph = (i * 0.37) % 1.0
            rate = 0.85 + ((i * 37) % 9) / 22.0
            for j in range(SEGS):
                kk = j / (SEGS - 1)
                def fn(u, i=i, j=j, kk=kk, ph=ph, rate=rate):
                    w = math.sin(T * (speed * u + ph) - j * 0.7)
                    x = (base0 + (flare(u) if flare else 0) if j == 0 else 0) + curl * kk * kk + amp * kk * w
                    y = sway * kk * math.sin(T * (speed * u + ph) * 0.5 + j * 0.4 + 1.3)
                    return [x, y, 0]
                b[f"a{i}s{j}"] = {"rotation": wave(L, n, fn)}
        return b
    # IDLE (4 s): the arms drift out and down, each on its own beat, the tips coiling and uncoiling; the beak chews
    L = 4.0
    bones = arm_bones(L, 16, 0, 14, 9, 7, 1)
    bones["mantle"] = {"scale": wave(L, 16, lambda u: [1 - 0.02 * math.sin(T * u), 1 + 0.03 * math.sin(T * u), 1 - 0.02 * math.sin(T * u)]),
                       "rotation": wave(L, 16, lambda u: [2 * math.sin(T * u + 0.5), 0, 1.5 * math.sin(T * u)])}
    bones["beak_t"] = {"rotation": wave(L, 8, lambda u: [-6 * max(0, math.sin(T * 2 * u)), 0, 0])}
    bones["beak_b"] = {"rotation": wave(L, 8, lambda u: [6 * max(0, math.sin(T * 2 * u)), 0, 0])}
    A["idle"] = {"loop": True, "animation_length": L, "bones": bones}
    # SWIM (1.6 s, one jet): a quick squeeze with the arms snapping together behind him, then a slow open like an umbrella
    L = 1.6
    def jet(u):  # 0..1: 0-0.2 squeeze, then the long release
        return math.sin(u / 0.2 * math.pi / 2) if u < 0.2 else (1 - (u - 0.2) / 0.8) ** 1.6
    bones = arm_bones(L, 16, 0, 6, 6, 4, 1, flare=lambda u: 40 * jet(u) - 25 * (1 - jet(u)))
    bones["mantle"] = {"scale": wave(L, 16, lambda u: [1 - 0.1 * jet(u), 1 + 0.16 * jet(u), 1 - 0.1 * jet(u)]),
                       "rotation": wave(L, 16, lambda u: [8 * jet(u), 0, 0])}
    A["swim"] = {"loop": True, "animation_length": L, "bones": bones}
    # ATTACK (1.2 s): arms up and thrashing, the beak wide, the head jolting
    L = 1.2
    bones = arm_bones(L, 16, -45, 4, 26, 16, 2)
    bones["mantle"] = {"rotation": wave(L, 16, lambda u: [-10 + 4 * math.sin(T * 2 * u), 0, 3 * math.sin(T * u)]),
                       "scale": wave(L, 16, lambda u: [1.04, 1.06 + 0.03 * math.sin(T * 2 * u), 1.04])}
    bones["beak_t"] = {"rotation": wave(L, 8, lambda u: [-22 - 8 * math.sin(T * 2 * u), 0, 0])}
    bones["beak_b"] = {"rotation": wave(L, 8, lambda u: [22 + 8 * math.sin(T * 2 * u), 0, 0])}
    A["attack"] = {"loop": True, "animation_length": L, "bones": bones}
    return A


# ======================================================================================================== pack

def icon_from(img, box):
    return img.crop(box).resize((32, 32), Image.NEAREST)


def main():
    out = sys.argv[1]
    prev = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    rp = os.path.join(out, "rp_bosses")
    if os.path.isdir(rp): shutil.rmtree(rp)
    mappings = {"minecraft:paper": []}
    item_tex = {}

    def attach(item, geo_id, tex_rel, anim_id):
        desc = {"identifier": f"faultline:{item}", "materials": {"default": "armor", "enchanted": "armor_enchanted"},
                "textures": {"default": tex_rel, "enchanted": "textures/misc/enchanted_actor_glint"},
                "geometry": {"default": geo_id}, "animations": {"a": anim_id},
                "scripts": {"parent_setup": "v.helmet_layer_visible = 0.0;", "animate": ["a"]},
                "render_controllers": ["controller.render.armor"]}
        jw(os.path.join(rp, "attachables", f"faultline.{item}.json"), {"format_version": "1.10.0", "minecraft:attachable": {"description": desc}})

    def mapping(item, icon):
        mappings["minecraft:paper"].append({"type": "definition", "model": f"faultline:{item}", "bedrock_identifier": f"faultline:{item}",
            "display_name": item, "bedrock_options": {"icon": icon, "allow_offhand": False},
            "components": {"minecraft:equippable": {"slot": "head"}, "minecraft:max_stack_size": 1}})

    # ---- Jacob
    geo, texes, forms = jacob_geo()
    jw(os.path.join(rp, "models/entity/faultline/jacob_b.geo.json"), geo)
    anims = jacob_anims()
    jw(os.path.join(rp, "animations/faultline.jacob_b.animation.json"),
       {"format_version": "1.8.0", "animations": {f"animation.faultline.jacob_b.{k}": v for k, v in anims.items()}})
    for form, img in texes.items():
        p = os.path.join(rp, "textures/faultline/bosses", f"jacob_{form}.png"); os.makedirs(os.path.dirname(p), exist_ok=True); img.save(p)
        ic = os.path.join(rp, "textures/items/faultline", f"jacob_b_{form}.png"); os.makedirs(os.path.dirname(ic), exist_ok=True)
        icon_from(forms[form][0], (0, 0, 64, 64)).save(ic)
        item_tex[f"faultline.jacob_b_{form}"] = {"textures": f"textures/items/faultline/jacob_b_{form}"}
        for state in anims:
            item = f"jacob_b_{form}_{state}"
            attach(item, "geometry.faultline.jacob_b", f"textures/faultline/bosses/jacob_{form}", f"animation.faultline.jacob_b.{state}")
            mapping(item, f"faultline.jacob_b_{form}")
    # ---- the Kraken
    geo, texes = kraken_geo()
    jw(os.path.join(rp, "models/entity/faultline/kraken_b.geo.json"), geo)
    anims = kraken_anims()
    jw(os.path.join(rp, "animations/faultline.kraken_b.animation.json"),
       {"format_version": "1.8.0", "animations": {f"animation.faultline.kraken_b.{k}": v for k, v in anims.items()}})
    for k, img in texes.items():
        p = os.path.join(rp, "textures/faultline/bosses", f"kraken_{k}.png"); img.save(p)
        ic = os.path.join(rp, "textures/items/faultline", f"kraken_b_{k}.png"); icon_from(img, (0, 0, 128, 128)).save(ic)
        item_tex[f"faultline.kraken_b_{k}"] = {"textures": f"textures/items/faultline/kraken_b_{k}"}
        for state in anims:
            item = f"kraken_b_{k}_{state}"
            attach(item, "geometry.faultline.kraken_b", f"textures/faultline/bosses/kraken_{k}", f"animation.faultline.kraken_b.{state}")
            mapping(item, f"faultline.kraken_b_{k}")
    # ---- the pack
    jw(os.path.join(rp, "textures/item_texture.json"), {"resource_pack_name": "faultline_bosses", "texture_name": "atlas.items", "texture_data": item_tex})
    jw(os.path.join(rp, "manifest.json"), {"format_version": 2, "header": {
        "name": "Faultline SMP Boss Models", "description": "Diamond Jacob and the Kraken for Bedrock players (Geyser)",
        "uuid": "c41f7b2e-6d58-4a93-b1e7-5f0c8d2a9e46", "version": [1, 0, 0], "min_engine_version": [1, 21, 0]},
        "modules": [{"description": "Boss models", "type": "resources", "uuid": "7a2e9d14-3b6c-4f85-a0d1-e8c47b5f2a93", "version": [1, 0, 0]}]})
    mc = os.path.join(out, "FaultlineBossModels.mcpack")
    with zipfile.ZipFile(mc, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(rp):
            for fn in sorted(files):
                full = os.path.join(root, fn); z.write(full, os.path.relpath(full, rp))
    shutil.rmtree(rp)
    from mcpack_version import stamp; stamp(mc)
    jw(os.path.join(out, "faultline_bosses_mappings.json"), {"format_version": 2, "items": mappings})
    print("wrote", mc, len(mappings["minecraft:paper"]), "items")
    if prev: preview(mc, prev)


# ======================================================================================================== preview

def preview(mc, outdir):
    """Draws the Bedrock geometry as Bedrock would (bones, pivots, rotations; Bedrock's x/y turns run backwards), so the
    assembly can be checked without a Bedrock client."""
    z = zipfile.ZipFile(mc)
    os.makedirs(outdir, exist_ok=True)
    for name, tex, anim, shots, scale, size in (
            ("jacob_b", "textures/faultline/bosses/jacob_armor", "faultline.jacob_b", ["idle", "walk", "swing", "slam"], 9, (300, 420)),
            ("kraken_b", "textures/faultline/bosses/kraken_1", "faultline.kraken_b", ["idle", "swim", "attack"], 3.2, (420, 420))):
        geo = json.loads(z.read(f"models/entity/faultline/{name}.geo.json"))["minecraft:geometry"][0]
        img = np.asarray(Image.open(z.open(tex + ".png")).convert("RGBA")).astype(float)
        an = json.loads(z.read(f"animations/{anim}.animation.json"))["animations"]
        frames = []
        for state in shots:
            a = an[f"animation.{anim}.{state}"]
            for t in (0.0, a["animation_length"] * 0.5):
                frames.append((state, t, draw_bedrock(geo, img, a, t, scale, size)))
        sheet = Image.new("RGBA", (size[0] * len(frames), size[1]))
        for i, (_, _, im) in enumerate(frames): sheet.paste(im, (i * size[0], 0))
        sheet.save(os.path.join(outdir, f"bedrock_{name}.png"))


def sample(track, t, L):
    ks = sorted((float(k), v) for k, v in track.items())
    if t <= ks[0][0]: return ks[0][1]
    for (t0, v0), (t1, v1) in zip(ks, ks[1:]):
        if t <= t1:
            f = (t - t0) / max(1e-9, t1 - t0)
            return [a + (b - a) * f for a, b in zip(v0, v1)]
    return ks[-1][1]


def rotm(deg):
    """A Bedrock bone/cube rotation [x, y, z] in degrees (x and y turn the other way round) -> matrix (applied z, y, x)."""
    x, y, zz = [math.radians(v) for v in deg]
    x, y = -x, -y
    Rx = np.array([[1, 0, 0], [0, math.cos(x), -math.sin(x)], [0, math.sin(x), math.cos(x)]])
    Ry = np.array([[math.cos(y), 0, math.sin(y)], [0, 1, 0], [-math.sin(y), 0, math.cos(y)]])
    Rz = np.array([[math.cos(zz), -math.sin(zz), 0], [math.sin(zz), math.cos(zz), 0], [0, 0, 1]])
    return Rx @ Ry @ Rz


def draw_bedrock(geo, img, anim, t, scale, size):
    bones = {b["name"]: b for b in geo["bones"]}
    L = anim["animation_length"]
    cache = {}

    def world(name):
        if name in cache: return cache[name]
        b = bones[name]
        piv = np.array(b.get("pivot", [0, 0, 0]), float)
        rot = list(b.get("rotation", [0, 0, 0]))
        pos = np.zeros(3); sc = np.ones(3)
        tr = anim["bones"].get(name, {})
        if "rotation" in tr: rot = [r + a for r, a in zip(rot, sample(tr["rotation"], t, L))]
        if "position" in tr: pos = np.array(sample(tr["position"], t, L), float)
        if "scale" in tr: sc = np.array(sample(tr["scale"], t, L), float)
        R = rotm(rot) @ np.diag(sc)
        local = lambda p, R=R, piv=piv, pos=pos: R @ (np.asarray(p) - piv) + piv + pos
        par = b.get("parent")
        f = (lambda p, local=local, pw=world(par): pw(local(p))) if par else local
        cache[name] = f
        return f

    W, H = size
    out = np.zeros((H, W, 4)); out[:, :, :3] = (34, 38, 46); out[:, :, 3] = 255
    zb = np.full((H, W), -1e9)
    vy = math.radians(150); vp = math.radians(12)
    def proj(p):
        x, y, zz = p
        x, zz = x * math.cos(vy) - zz * math.sin(vy), x * math.sin(vy) + zz * math.cos(vy)
        y, zz = y * math.cos(vp) - zz * math.sin(vp), y * math.sin(vp) + zz * math.cos(vp)
        return np.array([W / 2 + x * scale, H * 0.82 - y * scale, zz])
    th, tw = img.shape[:2]
    FACE = {"north": ((0, 1, 0), "-z"), "south": ((0, 1, 0), "+z"), "east": ((2, 1, 0), "+x"), "west": ((2, 1, 0), "-x"), "up": ((0, 2, 0), "+y"), "down": ((0, 2, 0), "-y")}
    for name, b in bones.items():
        f = world(name)
        for c in b.get("cubes", []):
            o = np.array(c["origin"], float); s = np.array(c["size"], float)
            cr = (lambda p: p) if "rotation" not in c else (lambda p, R=rotm(c["rotation"]), pv=np.array(c["pivot"], float): R @ (np.asarray(p) - pv) + pv)
            for face, uvd in c["uv"].items():
                lo, hi = o, o + s
                if face == "north": q = [(hi[0], hi[1], lo[2]), (lo[0], hi[1], lo[2]), (hi[0], lo[1], lo[2])]
                elif face == "south": q = [(lo[0], hi[1], hi[2]), (hi[0], hi[1], hi[2]), (lo[0], lo[1], hi[2])]
                elif face == "east": q = [(hi[0], hi[1], hi[2]), (hi[0], hi[1], lo[2]), (hi[0], lo[1], hi[2])]
                elif face == "west": q = [(lo[0], hi[1], lo[2]), (lo[0], hi[1], hi[2]), (lo[0], lo[1], lo[2])]
                elif face == "up": q = [(lo[0], hi[1], lo[2]), (hi[0], hi[1], lo[2]), (lo[0], hi[1], hi[2])]
                else: q = [(lo[0], lo[1], hi[2]), (hi[0], lo[1], hi[2]), (lo[0], lo[1], lo[2])]
                tl, tr_, bl = [proj(f(cr(p))) for p in q]
                u1, v1 = uvd["uv"]; uw, vh = uvd["uv_size"]
                ex, ey = tr_ - tl, bl - tl
                det = ex[0] * ey[1] - ex[1] * ey[0]
                if abs(det) < 1e-6: continue
                xs = [tl[0], tr_[0], bl[0], tr_[0] + ey[0]]; ys = [tl[1], tr_[1], bl[1], tr_[1] + ey[1]]
                x0, x1 = max(0, int(min(xs))), min(W - 1, int(max(xs)) + 1)
                y0, y1 = max(0, int(min(ys))), min(H - 1, int(max(ys)) + 1)
                if x0 > x1 or y0 > y1: continue
                gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
                dx, dy = gx - tl[0], gy - tl[1]
                ss = (dx * ey[1] - dy * ey[0]) / det
                tt = (ex[0] * dy - ex[1] * dx) / det
                m = (ss >= 0) & (ss <= 1) & (tt >= 0) & (tt <= 1)
                if not m.any(): continue
                depth = tl[2] + ss * ex[2] + tt * ey[2]
                uu = np.clip((u1 + ss * uw).astype(int), 0, tw - 1)
                vv = np.clip((v1 + tt * vh).astype(int), 0, th - 1)
                px = img[vv, uu]
                m &= px[:, :, 3] > 127
                sub = zb[y0:y1 + 1, x0:x1 + 1]
                m &= depth > sub
                sub[m] = depth[m]
                shade = {"up": 1.0, "down": 0.5, "north": 0.8, "south": 0.8, "east": 0.62, "west": 0.62}[face]
                out[y0:y1 + 1, x0:x1 + 1][m, :3] = px[m, :3] * shade
    return Image.fromarray(out.astype(np.uint8), "RGBA")


if __name__ == "__main__":
    main()
