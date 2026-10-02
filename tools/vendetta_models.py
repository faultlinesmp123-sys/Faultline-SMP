#!/usr/bin/env python3
"""
Art for Rocco Vendetta and his little brother Werner (FaultlineBosses), written into an unpacked FaultlineSMP pack:

  rocco_* / werner_*   6-piece rigs (leg_r, leg_l, body, arm_r, arm_l, head) painted from a generated 64x64 skin,
                       same geometry as Don Lorenzo's rig (slim arms), so renderRig() draws them.
  vendetta_fist        the Vendetta Fist (gold knuckle-duster) held item
  vendetta_contract    the summon item (a sealed contract)
  textures/index/      Index icons: rocco_vendetta, werner, vendetta_enforcer, vendetta_fist, vendetta_contract

Usage: python3 tools/vendetta_models.py <unpacked-pack-dir> [--preview <dir>]
"""
import json, os, random, sys
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from jacob_models import Atlas, box, plate, noise_fill, render, set_anchor, write_model, write_tex, JOINT  # shared helpers

NS = "faultline"
GOLD = dict(base=(232, 184, 46), light=(255, 236, 140), dark=(150, 98, 14), hi=(255, 250, 210))

# ------------------------------------------------------------------ skins

class Skin:
    def __init__(self, seed):
        self.img = np.zeros((64, 64, 4), dtype=np.float64)
        self.rng = random.Random(seed)

    def px(self, x, y, c):
        self.img[y, x, :3] = c[:3]; self.img[y, x, 3] = 255

    def fill(self, x, y, w, h, c, n=5):
        for j in range(y, y + h):
            for i in range(x, x + w):
                v = self.rng.uniform(-n, n)
                self.px(i, j, tuple(max(0, min(255, k + v)) for k in c))

    def clear(self, x, y, w, h):
        self.img[y:y + h, x:x + w] = 0

    def image(self):
        return Image.fromarray(np.clip(self.img, 0, 255).astype(np.uint8), "RGBA")


# skin layout (slim arms). Each part: (x, y) of its net and its (w, h, d).
HEAD, HAT = (0, 0), (32, 0)
BODY, JACKET = (16, 16), (16, 32)
ARM_R, SLEEVE_R = (40, 16), (40, 32)
ARM_L, SLEEVE_L = (32, 48), (48, 48)
LEG_R, PANTS_R = (0, 16), (0, 32)
LEG_L, PANTS_L = (16, 48), (0, 48)


def faces(origin, w, h, d):
    """The 6 faces of a cuboid net: name -> (x, y, fw, fh)."""
    x, y = origin
    return {"top": (x + d, y, w, d), "bottom": (x + d + w, y, w, d),
            "right": (x, y + d, d, h), "front": (x + d, y + d, w, h),
            "left": (x + d + w, y + d, d, h), "back": (x + d + w + d, y + d, w, h)}


def paint_part(s, origin, w, h, d, colors, n=5):
    """colors: face -> color (missing = skipped)."""
    for f, (x, y, fw, fh) in faces(origin, w, h, d).items():
        if f in colors: s.fill(x, y, fw, fh, colors[f], n)


def brother(seed, p):
    """One brother's skin. p = palette/style dict."""
    s = Skin(seed)
    SK, SKD, HAIR, HAIRD = p["skin"], p["skin_dk"], p["hair"], p["hair_dk"]
    COAT, COATD, COATH, FUR, FURH = p["coat"], p["coat_dk"], p["coat_hi"], p["fur"], p["fur_hi"]
    SHIRT, PANT, PANTD, SHOE = p["shirt"], p["pants"], p["pants_dk"], p["shoes"]
    G, GL, GD = GOLD["base"], GOLD["light"], GOLD["dark"]
    # ---- head
    hf = faces(HEAD, 8, 8, 8)
    paint_part(s, HEAD, 8, 8, 8, {"top": HAIR, "bottom": SKD, "right": SK, "front": SK, "left": SK, "back": HAIR})
    for f in ("right", "left"):
        x, y, _, _ = hf[f]
        s.fill(x, y, 8, 3, HAIR); s.fill(x + (5 if f == "right" else 0), y + 3, 3, 2, HAIR)  # hair over the temples, toward the back
        s.px(x + (3 if f == "right" else 4), y + 4, SKD)                                     # ear
    x, y, _, _ = hf["back"]; s.fill(x, y + 6, 8, 2, SKD)  # nape
    fx, fy, _, _ = hf["front"]
    s.fill(fx, fy, 8, 2, HAIR); s.px(fx, fy + 2, HAIR); s.px(fx + 7, fy + 2, HAIR)
    if p["slick"]: s.fill(fx + 1, fy + 2, 6, 1, SK)
    for i, c in enumerate([(1, 3), (2, 3), (5, 3), (6, 3)]):
        s.px(fx + c[0], fy + c[1], p["brow"])
    s.px(fx + 2, fy + 2, p["brow"]) if p["angry"] else None
    s.px(fx + 5, fy + 2, p["brow"]) if p["angry"] else None
    s.px(fx + 1, fy + 4, (235, 235, 235)); s.px(fx + 2, fy + 4, p["eye"])
    s.px(fx + 5, fy + 4, p["eye"]); s.px(fx + 6, fy + 4, (235, 235, 235))
    s.px(fx + 3, fy + 5, SKD); s.px(fx + 4, fy + 5, SKD)                 # nose shadow
    s.px(fx + 2, fy + 6, SKD); s.fill(fx + 3, fy + 6, 2, 1, p["mouth"], 3); s.px(fx + 5, fy + 6, SKD)
    if p["stubble"]: s.fill(fx + 1, fy + 7, 6, 1, p["stubble"], 4); s.px(fx + 1, fy + 6, p["stubble"]); s.px(fx + 6, fy + 6, p["stubble"])
    for (sx, sy) in p["scar"]: s.px(fx + sx, fy + sy, p["scar_col"])
    # hat layer: hair volume
    hh = faces(HAT, 8, 8, 8)
    hx, hy, _, _ = hh["front"]
    for i in range(8):
        if p["spiky"]: s.px(hx + i, hy, HAIR if i % 2 else HAIRD)
        else: s.px(hx + i, hy, HAIR)
    s.px(hx, hy + 1, HAIRD); s.px(hx + 7, hy + 1, HAIRD)
    if p["spiky"]: s.px(hx + 2, hy + 1, HAIR); s.px(hx + 5, hy + 1, HAIR)
    tx, ty, _, _ = hh["top"]; s.fill(tx, ty, 8, 8, HAIR, 10)
    for f in ("right", "left", "back"):
        x, y, fw, _ = hh[f]
        s.fill(x, y, fw, 2, HAIR, 10)
        if f == "back": s.fill(x, y + 2, fw, 3, HAIRD, 8)
    # ---- body: open coat over the shirt, belt, gold chains
    bf = faces(BODY, 8, 12, 4)
    paint_part(s, BODY, 8, 12, 4, {"top": COATD, "bottom": PANTD, "right": COAT, "front": SHIRT, "left": COAT, "back": COAT})
    fx, fy, _, _ = bf["front"]
    s.fill(fx, fy, 2, 12, COAT); s.fill(fx + 6, fy, 2, 12, COAT)
    for j in range(12): s.px(fx + 1, fy + j, COATH); s.px(fx + 6, fy + j, COATH)  # lapel edges
    s.fill(fx + 2, fy + 8, 4, 1, (20, 18, 22), 2); s.px(fx + 3, fy + 8, G); s.px(fx + 4, fy + 8, GD)  # belt + buckle
    for j, x in enumerate([2, 3, 3, 4, 4, 5]): s.px(fx + x, fy + 1 + j, G if j % 2 else GL)   # chain, one way
    for j, x in enumerate([5, 4, 4, 3]): s.px(fx + x, fy + 2 + j, GD if j % 2 else G)         # and crossing back
    s.px(fx + 3, fy + 7, GL); s.px(fx + 4, fy + 7, G)                                          # medallion
    bx, by, _, _ = bf["back"]
    for j in range(12): s.px(bx + 3, by + j, COATD)                                            # back seam
    # jacket layer: fur collar over the shoulders, coat skirt edges
    jf = faces(JACKET, 8, 12, 4)
    for f in ("front", "back", "right", "left"):
        x, y, fw, fh = jf[f]
        for i in range(fw):
            depth = 2 + (self_rand := s.rng.randint(0, 1))
            s.fill(x + i, y, 1, depth, FUR if i % 2 else FURH, 6)
    tx, ty, tw, td = jf["top"]; s.fill(tx, ty, tw, td, FUR, 8)
    x, y, _, _ = jf["front"]
    for j in range(2, 12): s.px(x, y + j, COAT); s.px(x + 7, y + j, COAT)                       # coat edges stand proud
    x, y, _, _ = jf["back"]; s.fill(x, y + 10, 8, 2, FUR, 6)                                   # fur hem at the back
    # ---- arms: sleeves, fur cuffs, hands (+ a gold ring)
    for net, sleeve, chain_arm in ((ARM_R, SLEEVE_R, False), (ARM_L, SLEEVE_L, True)):
        af = faces(net, 3, 12, 4)
        paint_part(s, net, 3, 12, 4, {"top": COAT, "bottom": SK, "right": COAT, "front": COAT, "left": COAT, "back": COATD})
        for f in ("right", "front", "left", "back"):
            x, y, fw, _ = af[f]
            s.fill(x, y + 10, fw, 2, SK, 4)                     # hands
            if f == "front": s.px(x + 1, y + 11, SKD)
        x, y, _, _ = af["front"]; s.px(x, y + 10, G)            # a gold ring
        sf = faces(sleeve, 3, 12, 4)
        for f in ("right", "front", "left", "back"):
            x, y, fw, _ = sf[f]
            s.fill(x, y + 8, fw, 2, FUR, 8)                     # fur cuff
            s.px(x, y + 7, FURH)
            if chain_arm and p["arm_chain"]:                    # chain wrapped around the forearm
                for i in range(fw): s.px(x + i, y + 5 + (i % 2), G if i % 2 else GL)
            if f in ("right", "left"): s.fill(x, y, fw, 1, FUR, 6)  # fur at the shoulder
    # ---- legs: trousers, shoes, the long coat's skirt on the outer layer
    for net, pants, outer in ((LEG_R, PANTS_R, "right"), (LEG_L, PANTS_L, "left")):
        lf = faces(net, 4, 12, 4)
        paint_part(s, net, 4, 12, 4, {"top": PANT, "bottom": SHOE, "right": PANT, "front": PANT, "left": PANT, "back": PANTD})
        for f in ("right", "front", "left", "back"):
            x, y, fw, _ = lf[f]
            s.fill(x, y + 10, fw, 2, SHOE, 4)
            if f == "front": s.px(x + (1 if outer == "right" else 2), y + 3, PANTD)  # crease
        pf = faces(pants, 4, 12, 4)
        for f in (outer, "back"):                                # the coat hangs down behind and to the outside
            x, y, fw, _ = pf[f]
            s.fill(x, y, fw, 6, COAT, 6)
            for i in range(fw): s.fill(x + i, y + 6, 1, 1 + s.rng.randint(0, 1), FUR if i % 2 else FURH, 6)
        x, y, fw, _ = pf["front"]
        col = 0 if outer == "right" else 3
        s.fill(x + col, y, 1, 6, COAT, 6); s.px(x + col, y + 6, FUR)
    return s.image()


WERNER = dict(skin=(196, 152, 124), skin_dk=(160, 118, 96), hair=(236, 236, 240), hair_dk=(184, 184, 196),
              coat=(112, 40, 146), coat_dk=(72, 22, 96), coat_hi=(156, 74, 188), fur=(24, 20, 26), fur_hi=(46, 40, 52),
              shirt=(86, 30, 110), pants=(226, 226, 232), pants_dk=(186, 186, 198), shoes=(96, 30, 120),
              brow=(70, 60, 64), eye=(60, 40, 70), mouth=(120, 70, 66), stubble=None,
              scar=[(5, 1), (6, 2), (6, 3), (1, 5)], scar_col=(120, 70, 70), slick=False, spiky=True, angry=True, arm_chain=True)

ROCCO = dict(skin=(188, 142, 112), skin_dk=(150, 108, 86), hair=(26, 22, 32), hair_dk=(70, 50, 92),
             coat=(34, 28, 40), coat_dk=(20, 16, 24), coat_hi=(120, 48, 156), fur=(14, 12, 16), fur_hi=(36, 30, 40),
             shirt=(112, 40, 146), pants=(56, 30, 72), pants_dk=(38, 20, 50), shoes=(18, 16, 20),
             brow=(20, 16, 24), eye=(150, 30, 40), mouth=(110, 64, 60), stubble=(140, 104, 84),
             scar=[(2, 2), (2, 3), (2, 5), (3, 6)], scar_col=(116, 60, 60), slick=True, spiky=False, angry=True, arm_chain=False)

# ------------------------------------------------------------------ rig models (Don's geometry, a new skin)

def uv(face_rect):
    x, y, w, h = face_rect
    return [x / 4, y / 4, (x + w) / 4, (y + h) / 4]


def cuboid(frm, to, net, w, h, d):
    f = faces(net, w, h, d)
    # Java face names, as seen with the model facing +z (south): its right side is -x (west)
    m = {"up": f["top"], "down": f["bottom"], "west": f["right"], "south": f["front"], "east": f["left"], "north": f["back"]}
    return {"from": frm, "to": to, "faces": {k: {"texture": "#skin", "uv": uv(v)} for k, v in m.items()}}


def grow(frm, to, by):
    return [v - by for v in frm], [v + by for v in to]


RIG = {  # piece -> (inner from/to, net, outer net, w, h, d, outer growth)
    "leg_r": ([6, -4, 6], [10, 8, 10], LEG_R, PANTS_R, 4, 12, 4, 0.25),
    "leg_l": ([6, -4, 6], [10, 8, 10], LEG_L, PANTS_L, 4, 12, 4, 0.25),
    "body": ([4, 8, 6], [12, 20, 10], BODY, JACKET, 8, 12, 4, 0.25),
    "arm_r": ([6.5, -4, 6], [9.5, 8, 10], ARM_R, SLEEVE_R, 3, 12, 4, 0.25),
    "arm_l": ([6.5, -4, 6], [9.5, 8, 10], ARM_L, SLEEVE_L, 3, 12, 4, 0.25),
    "head": ([4, 8, 4], [12, 16, 12], HEAD, HAT, 8, 8, 8, 0.5),
}


def write_rig(pack, name, skin):
    A = os.path.join(pack, "assets", NS)
    os.makedirs(os.path.join(A, "textures/item"), exist_ok=True)
    skin.save(os.path.join(A, "textures/item", name + "_skin.png"))
    for piece, (frm, to, net, onet, w, h, d, g) in RIG.items():
        o1, o2 = grow(frm, to, g)
        model = {"textures": {"skin": f"{NS}:item/{name}_skin", "particle": f"{NS}:item/{name}_skin"},
                 "elements": [cuboid(frm, to, net, w, h, d), cuboid(o1, o2, onet, w, h, d)]}
        for sub, obj in (("models/item", model), ("items", {"model": {"type": "minecraft:model", "model": f"{NS}:item/{name}_{piece}"}})):
            p = os.path.join(A, sub, f"{name}_{piece}.json"); os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "w") as fh: json.dump(obj, fh, indent=1)

# ------------------------------------------------------------------ the Vendetta Fist

FIST_DISPLAY = {
    "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 2.5, 2.0], "scale": [0.7, 0.7, 0.7]},
    "thirdperson_lefthand": {"rotation": [0, 0, 0], "translation": [0, 2.5, 2.0], "scale": [0.7, 0.7, 0.7]},
    "firstperson_righthand": {"rotation": [0, -10, 0], "translation": [1.5, 3.5, 0], "scale": [0.6, 0.6, 0.6]},
    "firstperson_lefthand": {"rotation": [0, 10, 0], "translation": [1.5, 3.5, 0], "scale": [0.6, 0.6, 0.6]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "gui": {"rotation": [25, -35, 0], "translation": [0, 0, 0], "scale": [0.85, 0.85, 0.85]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.9, 0.9, 0.9]},
}


def fist(seed):
    """A heavy gold knuckle-duster: four finger rings, a palm grip, a purple gem on the bar."""
    atlas = Atlas(128, 4); rng = random.Random(seed)
    def gold(reg, face, wu, hu): plate(reg, GOLD, rng, sparkle=0.1)
    def gem(reg, face, wu, hu): plate(reg, dict(base=(140, 50, 190), light=(200, 120, 240), dark=(70, 20, 100), hi=(240, 200, 255)), rng, sparkle=0.15)
    el = []
    for i in range(4):                     # rings across the knuckles (x), the finger holes run front to back (z)
        x = -4 + i * 2
        el.append(box(atlas, (x, 2.0, -1.0), (x + 2, 2.8, 1.0), gold))    # ring tops (the striking edge)
        el.append(box(atlas, (x, 0.0, -1.0), (x + 0.5, 2.0, 1.0), gold))  # ring sides
    el.append(box(atlas, (3.5, 0.0, -1.0), (4.0, 2.0, 1.0), gold))
    el.append(box(atlas, (-4, -0.6, -1.0), (4, 0.0, 1.0), gold))            # ring bottoms
    for i in range(4):                     # spiked studs on the striking edge
        el.append(box(atlas, (-3.4 + i * 2, 2.8, -0.5), (-2.6 + i * 2, 3.5, 0.5), gold))
    el.append(box(atlas, (-0.9, 2.9, 0.9), (0.9, 3.7, 1.4), gem))           # the gem, facing out
    el.append(box(atlas, (-3.5, -3.6, -0.7), (3.5, -2.4, 0.7), gold))        # the palm grip
    el.append(box(atlas, (-3.0, -2.4, -0.4), (-2.2, -0.6, 0.4), gold))       # struts
    el.append(box(atlas, (2.2, -2.4, -0.4), (3.0, -0.6, 0.4), gold))
    return atlas, el

# ------------------------------------------------------------------ flat items + Index icons

def contract_tex():
    img = np.zeros((16, 16, 4))
    def px(x, y, c): img[y, x, :3] = c; img[y, x, 3] = 255
    P, PD, INK, RED, REDL = (236, 224, 196), (196, 176, 140), (60, 40, 50), (150, 24, 36), (210, 60, 70)
    for y in range(1, 15):
        for x in range(3, 13): px(x, y, P)
    for y in range(1, 15): px(3, y, PD); px(12, y, PD)
    for x in range(3, 13): px(x, 14, PD)
    for y in (3, 5, 7, 9):
        for x in range(5, 11 if y != 9 else 8): px(x, y, INK)
    for x, y in [(9, 10), (10, 10), (11, 10), (9, 11), (11, 11), (9, 12), (10, 12), (11, 12), (10, 11)]: px(x, y, RED)  # wax seal
    px(10, 11, REDL); px(8, 13, RED); px(12, 13, RED)
    return Image.fromarray(img.astype(np.uint8), "RGBA")


def face_icon(skin):
    """16x16 Index icon: the head's front (with its hair layer), doubled."""
    s = np.asarray(skin).astype(float)
    face = s[8:16, 8:16].copy()
    hat = s[8:16, 40:48]
    a = hat[:, :, 3:4] / 255
    face[:, :, :3] = face[:, :, :3] * (1 - a) + hat[:, :, :3] * a
    face[:, :, 3] = np.maximum(face[:, :, 3], hat[:, :, 3])
    return Image.fromarray(face.astype(np.uint8), "RGBA").resize((16, 16), Image.NEAREST)


def enforcer_icon():
    img = np.zeros((16, 16, 4))
    def px(x, y, c): img[y, x, :3] = c; img[y, x, 3] = 255
    HAT, BAND, SK, SKD = (24, 20, 28), (112, 40, 146), (150, 140, 130), (110, 100, 96)
    for x in range(1, 15): px(x, 5, HAT)
    for y in range(1, 5):
        for x in range(4, 12): px(x, y, HAT)
    for x in range(4, 12): px(x, 4, BAND)
    for y in range(6, 15):
        for x in range(4, 12): px(x, y, SK)
    for x in (5, 6, 9, 10): px(x, 8, (20, 20, 20))
    px(7, 10, SKD); px(8, 10, SKD)
    for x in range(6, 10): px(x, 12, (70, 50, 50))
    for x in range(3, 13): px(x, 15, (34, 28, 40))
    return Image.fromarray(img.astype(np.uint8), "RGBA")


def fist_icon():
    img = np.zeros((16, 16, 4))
    def px(x, y, c): img[y, x, :3] = c; img[y, x, 3] = 255
    G, GL, GD, GEM = GOLD["base"], GOLD["light"], GOLD["dark"], (170, 70, 220)
    for i in range(4):
        x = 1 + i * 3 + (1 if i else 0)
        for dx in range(3):
            px(x + dx, 4, GL); px(x + dx, 8, GD)
        px(x, 5, G); px(x, 6, G); px(x, 7, G); px(x + 2, 5, G); px(x + 2, 6, G); px(x + 2, 7, G)
        px(x + 1, 3, GL)
    for x in range(2, 14): px(x, 9, G)
    px(7, 4, GEM); px(8, 4, GEM)
    for y in range(10, 13): px(3, y, GD); px(12, y, GD)
    for x in range(3, 13): px(x, 13, G)
    return Image.fromarray(img.astype(np.uint8), "RGBA")

# ------------------------------------------------------------------ main

def main():
    pack = sys.argv[1]
    prev = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    A = os.path.join(pack, "assets", NS)
    werner, rocco = brother(31, WERNER), brother(32, ROCCO)
    write_rig(pack, "werner", werner)
    write_rig(pack, "rocco", rocco)
    fa, fel = fist(33)
    write_tex(pack, "vendetta_fist", fa)
    write_model(pack, "vendetta_fist", "vendetta_fist", fel, FIST_DISPLAY)
    ct = contract_tex()
    ct.save(os.path.join(A, "textures/item/vendetta_contract.png"))
    for sub, obj in (("models/item/vendetta_contract.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/vendetta_contract"}}),
                     ("items/vendetta_contract.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/vendetta_contract"}})):
        with open(os.path.join(A, sub), "w") as fh: json.dump(obj, fh, indent=1)
    idx = os.path.join(A, "textures/index"); os.makedirs(idx, exist_ok=True)
    icons = {"rocco_vendetta": face_icon(rocco), "werner": face_icon(werner), "vendetta_enforcer": enforcer_icon(),
             "vendetta_fist": fist_icon(), "vendetta_contract": ct}
    for k, im in icons.items(): im.save(os.path.join(idx, k + ".png"))
    if prev:
        os.makedirs(prev, exist_ok=True)
        sheet = Image.new("RGBA", (64 * 8 * 2 + 16, 512), (34, 38, 46, 255))
        sheet.paste(rocco.resize((512, 512), Image.NEAREST), (0, 0), rocco.resize((512, 512), Image.NEAREST))
        sheet.paste(werner.resize((512, 512), Image.NEAREST), (528, 0), werner.resize((512, 512), Image.NEAREST))
        sheet.save(os.path.join(prev, "vendetta_skins.png"))
        row = Image.new("RGBA", (len(icons) * 136, 136), (34, 38, 46, 255))
        for i, im in enumerate(icons.values()): big = im.resize((128, 128), Image.NEAREST); row.paste(big, (i * 136 + 4, 4), big)
        row.save(os.path.join(prev, "vendetta_icons.png"))
        # both brothers, assembled, wearing the chains (and Rocco his book) like in the fight
        import cosmetics_assets as ca
        ch_a, ch_el = ca.chains(21); bk_a, bk_el = ca.book(22)
        ch_img = np.asarray(ca.save_tex(ch_a)).astype(float); bk_img = np.asarray(ca.save_tex(bk_a)).astype(float)
        set_anchor(0.85)
        figs = []
        for skin, extra in ((rocco, [(ch_el, ch_img), (bk_el, bk_img)]), (werner, [(ch_el, ch_img)])):
            simg = np.asarray(skin).astype(float)
            placed = []
            for piece, (frm, to, net, onet, w, h, d, g) in RIG.items():
                o1, o2 = grow(frm, to, g)
                placed.append(([cuboid(frm, to, net, w, h, d), cuboid(o1, o2, onet, w, h, d)], simg, JOINT[piece]))
            for el, im in extra: placed.append((el, im, (0, 18, 0)))
            for yaw in (25, 205): figs.append(render(placed, yaw, 10, 9, (300, 360)))
        out = Image.new("RGBA", (300 * len(figs), 360))
        for i, f in enumerate(figs): out.paste(f, (i * 300, 0))
        out.save(os.path.join(prev, "vendetta_brothers.png"))
        set_anchor(0.5)
        img = np.asarray(Image.fromarray(np.clip(fa.img, 0, 255).astype(np.uint8), "RGBA")).astype(float)
        views = [render([(fel, img, (0, 0, 0))], yaw, 20, 26, (300, 300)) for yaw in (20, 160)]
        out = Image.new("RGBA", (600, 300))
        for i, v in enumerate(views): out.paste(v, (i * 300, 0))
        out.save(os.path.join(prev, "vendetta_fist.png"))


if __name__ == "__main__":
    main()
