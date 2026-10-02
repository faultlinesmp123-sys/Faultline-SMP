#!/usr/bin/env python3
"""
Builds Diamond Jacob's models and textures for the resource pack:
  jacob_armor_*  (phases 1-3: diamond knight)
  jacob_blaze_*  (phase 4: the same knight, charred and burning)
  jacob_bird_*   (his Great Hawk: body, head, tail, wings)

Usage:  python3 tools/jacob_models.py <unpacked-pack-dir> [--preview <dir>]
Needs Pillow + numpy. Re-run it after changing anything here, then re-zip the pack and update its hash.

Conventions (match FaultlineBosses' renderRig / the hawk rig):
  - every part is drawn with its joint at model (8, 8, 8); the front is +z (the "south" face)
  - humanoid: head/body/pauldrons extend UP from the joint, arms and legs hang DOWN from it
  - hawk: beak at +z, tail at -z, wing_r extends +x, wing_l extends -x
"""
import json, math, os, random, sys
import numpy as np
from PIL import Image

C = 8.0  # the joint, in model units

# ------------------------------------------------------------------ atlas + elements

class Atlas:
    def __init__(self, size, ppu):
        self.size, self.ppu = size, ppu
        self.img = np.zeros((size, size, 4), dtype=np.float64)
        self.x = self.y = self.row = 0

    def alloc(self, w, h):
        if self.x + w > self.size:
            self.x, self.y, self.row = 0, self.y + self.row, 0
        if self.y + h > self.size:
            raise RuntimeError("atlas full")
        at = (self.x, self.y)
        self.x += w; self.row = max(self.row, h)
        return at


FACE_DIMS = {"north": (0, 1), "south": (0, 1), "east": (2, 1), "west": (2, 1), "up": (0, 2), "down": (0, 2)}


def box(atlas, frm, to, paint, faces=None, **kw):
    """An element from/to (offsets from the joint). paint(reg, face, w_units, h_units, **kw) fills each face."""
    f = [C + v for v in frm]; t = [C + v for v in to]
    size = [t[i] - f[i] for i in range(3)]
    out = {"from": [round(v, 3) for v in f], "to": [round(v, 3) for v in t], "faces": {}}
    for face in faces or FACE_DIMS:
        a, b = FACE_DIMS[face]
        wu, hu = size[a], size[b]
        wp, hp = max(1, round(wu * atlas.ppu)), max(1, round(hu * atlas.ppu))
        x, y = atlas.alloc(wp, hp)
        reg = atlas.img[y:y + hp, x:x + wp]
        paint(reg, face, wu, hu, **kw)
        s = 16.0 / atlas.size
        out["faces"][face] = {"uv": [round(x * s, 4), round(y * s, 4), round((x + wp) * s, 4), round((y + hp) * s, 4)], "texture": "#t"}
    return out

# ------------------------------------------------------------------ painting helpers

def col(c, a=255):
    return np.array([c[0], c[1], c[2], a], dtype=np.float64)


def shade(c, k):
    return tuple(max(0, min(255, v * k)) for v in c[:3])


def noise_fill(reg, base, rng, amt=6):
    h, w = reg.shape[:2]
    n = np.array([[rng.uniform(-amt, amt) for _ in range(w)] for _ in range(h)])
    for i in range(3):
        reg[:, :, i] = np.clip(base[i] + n, 0, 255)
    reg[:, :, 3] = 255


def plate(reg, pal, rng, sparkle=0.04, bevel=True):
    """A polished armor plate: lit top-left edge, dark bottom-right edge, a soft top-to-bottom gradient."""
    h, w = reg.shape[:2]
    noise_fill(reg, pal["base"], rng, 5)
    for y in range(h):
        k = 1.08 - 0.16 * (y / max(1, h - 1))
        reg[y, :, :3] = np.clip(reg[y, :, :3] * k, 0, 255)
    if bevel and w > 2 and h > 2:
        reg[0, :, :3] = pal["light"]; reg[:, 0, :3] = pal["light"]
        reg[-1, :, :3] = pal["dark"]; reg[:, -1, :3] = pal["dark"]
        reg[0, -1, :3] = pal["base"]; reg[-1, 0, :3] = pal["base"]
    for _ in range(int(w * h * sparkle)):
        x, y = rng.randrange(1, max(2, w - 1)), rng.randrange(1, max(2, h - 1))
        reg[y, x, :3] = pal["hi"]


def trim(reg, pal, rows=None, cols=None):
    """Gold bands: rows/cols are index lists (negative allowed)."""
    for r in rows or []:
        reg[r, :, :3] = pal["gold"]
        reg[r, ::3, :3] = pal["gold_hi"]
    for c in cols or []:
        reg[:, c, :3] = pal["gold"]
        reg[::3, c, :3] = pal["gold_hi"]


def cracks(reg, pal, rng, n=3):
    """Glowing lava cracks for the blaze form."""
    h, w = reg.shape[:2]
    for _ in range(n):
        x, y = rng.randrange(w), rng.randrange(h)
        for _ in range(rng.randrange(3, 8)):
            if 0 <= x < w and 0 <= y < h:
                reg[y, x, :3] = pal["glow"]
                if 0 <= x + 1 < w: reg[y, x + 1, :3] = np.maximum(reg[y, x + 1, :3], pal["glow_dim"])
            x += rng.choice((-1, 0, 1)); y += rng.choice((0, 1, 1))

# ------------------------------------------------------------------ palettes

DIAMOND = dict(base=(74, 208, 208), light=(170, 250, 240), dark=(22, 110, 122), hi=(235, 255, 252),
               gold=(226, 178, 40), gold_hi=(255, 236, 130), gold_dark=(140, 92, 12),
               under=(48, 62, 84), under_hi=(80, 96, 120), slit=(8, 14, 24), eye=(120, 240, 255),
               cape=(30, 48, 128), cape_hi=(52, 80, 180), cape_dark=(16, 24, 70), lining=(130, 20, 30),
               leather=(98, 60, 32), leather_hi=(136, 88, 48),
               plume=(186, 24, 36), plume_hi=(236, 70, 70), plume_dark=(110, 10, 22))

BLAZE = dict(base=(48, 40, 42), light=(96, 78, 70), dark=(18, 14, 16), hi=(255, 140, 40),
             gold=(232, 96, 16), gold_hi=(255, 196, 60), gold_dark=(120, 30, 6),
             under=(30, 22, 22), under_hi=(60, 40, 34), slit=(40, 10, 0), eye=(255, 230, 90),
             cape=(70, 14, 10), cape_hi=(140, 34, 12), cape_dark=(30, 6, 6), lining=(40, 8, 8),
             leather=(46, 30, 24), leather_hi=(70, 44, 32),
             plume=(255, 120, 10), plume_hi=(255, 230, 90), plume_dark=(200, 40, 0),
             glow=(255, 190, 50), glow_dim=(200, 70, 10))

# ------------------------------------------------------------------ the knight

def knight(pal, blaze, seed):
    atlas = Atlas(256, 2)
    rng = random.Random(seed)
    parts = {}

    def armor(reg, face, wu, hu, sparkle=0.04, trims=(), lit=True):
        plate(reg, pal, rng, sparkle=0 if blaze else sparkle)
        if blaze: cracks(reg, pal, rng, max(1, int(reg.size / 4 / 90)))
        for t in trims:
            t(reg, face)

    def gold(reg, face, wu, hu):
        noise_fill(reg, pal["gold"], rng, 8)
        reg[0, :, :3] = pal["gold_hi"]; reg[-1, :, :3] = pal["gold_dark"]

    def under(reg, face, wu, hu):  # chainmail
        h, w = reg.shape[:2]
        noise_fill(reg, pal["under"], rng, 4)
        for y in range(h):
            for x in range(w):
                if (x + (y % 2)) % 2 == 0: reg[y, x, :3] = pal["under_hi"]

    def leather(reg, face, wu, hu):
        noise_fill(reg, pal["leather"], rng, 6)
        reg[0, :, :3] = pal["leather_hi"]
        if face == "south":
            h, w = reg.shape[:2]; m = w // 2
            reg[:, m - 2:m + 2, :3] = pal["gold"]; reg[1:-1, m - 1:m + 1, :3] = pal["leather"]

    # ---------------- head
    def helm_front(reg, face):
        if face == "south":
            reg[:, reg.shape[1] // 2 - 1:reg.shape[1] // 2 + 1, :3] = pal["light"]  # center ridge
        if face == "up":
            reg[:, reg.shape[1] // 2 - 1:reg.shape[1] // 2 + 1, :3] = pal["gold"]
        if face in ("east", "west"):  # rivets
            for y in (3, reg.shape[0] - 5):
                reg[y, 3, :3] = pal["gold_hi"]; reg[y, -4, :3] = pal["gold_hi"]

    def faceplate(reg, face, wu, hu):
        plate(reg, pal, rng, sparkle=0)
        if blaze: cracks(reg, pal, rng, 1)
        if face != "south": return
        h, w = reg.shape[:2]
        reg[2:4, 1:w - 1, :3] = pal["slit"]                    # eye slit
        reg[4:h - 2, w // 2 - 1:w // 2 + 1, :3] = pal["slit"]  # nose/mouth slit
        for x in (3, w - 4):                                   # the eyes glint in the dark
            reg[2:4, x, :3] = pal["eye"]
        for y in range(5, h - 2, 2):                           # breathing holes
            reg[y, 2, :3] = pal["slit"]; reg[y, w - 3, :3] = pal["slit"]

    def plume(reg, face, wu, hu):
        h, w = reg.shape[:2]
        noise_fill(reg, pal["plume"], rng, 14)
        for y in range(h):
            for x in range(w):
                r = rng.random()
                if r < 0.18: reg[y, x, :3] = pal["plume_hi"]
                elif r < 0.32: reg[y, x, :3] = pal["plume_dark"]
        if blaze:  # flickering flame tips
            for x in range(w):
                if rng.random() < 0.35: reg[0, x, 3] = 0

    head = [
        box(atlas, (-4.5, 0.6, -4.5), (4.5, 9.4, 4.5), armor, trims=[helm_front]),
        box(atlas, (-4.75, 0, -4.75), (4.75, 1.1, 4.75), gold),                      # gold rim
        box(atlas, (-3.6, 1.6, 4.5), (3.6, 7.2, 5.3), faceplate),                   # faceplate
        box(atlas, (-0.6, 9.4, -4.2), (0.6, 10.3, 4.2), gold),                       # crest ridge
        box(atlas, (-0.8, 10.3, -2.2), (0.8, 12.3, 2.0), plume),                     # plume, sweeping back
        box(atlas, (-0.8, 12.3, -3.8), (0.8, 13.8, 0.4), plume),
        box(atlas, (-0.7, 13.8, -5.4), (0.7, 14.8, -1.6), plume),
    ]
    if blaze:  # horns
        head += [box(atlas, (-5.4, 6, -0.6), (-4.5, 8, 0.6), gold), box(atlas, (-5.9, 8, -0.5), (-5.0, 10.5, 0.5), gold),
                 box(atlas, (4.5, 6, -0.6), (5.4, 8, 0.6), gold), box(atlas, (5.0, 8, -0.5), (5.9, 10.5, 0.5), gold)]
    parts["head"] = head

    # ---------------- body
    def chest_deco(reg, face):
        h, w = reg.shape[:2]
        if face in ("south", "north", "east", "west"): trim(reg, pal, rows=[0, 1])
        if face == "south":
            m, cy = w // 2, h // 2 - 1
            for d in range(4):  # a gold diamond emblem
                reg[cy - 3 + d, m - 1 - d:m + 1 + d, :3] = pal["gold"]
                reg[cy + 4 - d, m - 1 - d:m + 1 + d, :3] = pal["gold"]
            reg[cy - 1:cy + 3, m - 1:m + 1, :3] = pal["eye"] if not blaze else pal["glow"]
            reg[2:, m - 1:m + 1, :3] = np.maximum(reg[2:, m - 1:m + 1, :3], np.array(pal["light"]) * 0.0)

    def lames(reg, face):
        for y in range(0, reg.shape[0], 2):
            reg[y, :, :3] = pal["dark"]

    def tasset(reg, face, wu, hu):
        armor(reg, face, wu, hu, trims=[lambda r, f: trim(r, pal, rows=[-1])])

    def cape(reg, face, wu, hu):
        h, w = reg.shape[:2]
        noise_fill(reg, pal["cape"] if face != "south" else pal["lining"], rng, 6)
        if face == "north":  # the outside
            for x in range(0, w, 4): reg[:, x, :3] = pal["cape_dark"]   # folds
            for x in range(2, w, 4): reg[:, x, :3] = pal["cape_hi"]
            trim(reg, pal, cols=[0, -1], rows=[-1, -2])
            m, cy = w // 2, h // 3
            for d in range(3):
                reg[cy - 2 + d, m - 1 - d:m + 1 + d, :3] = pal["gold"]; reg[cy + 3 - d, m - 1 - d:m + 1 + d, :3] = pal["gold"]
        if blaze and face in ("north", "south"):  # burnt, tattered hem with embers
            for x in range(w):
                for y in range(h - rng.randrange(1, 6), h): reg[y, x, 3] = 0
                yy = h - 6 - rng.randrange(0, 3)
                if 0 <= yy < h: reg[yy, x, :3] = pal["glow"]

    parts["body"] = [
        box(atlas, (-4.2, 0, -2.2), (4.2, 12, 2.2), under),                             # chainmail under it all
        box(atlas, (-4.5, 4.4, -2.55), (4.5, 12.4, 2.55), armor, trims=[chest_deco]),   # breastplate
        box(atlas, (-4.3, 2.6, -2.4), (4.3, 4.4, 2.4), armor, trims=[lames]),           # fauld
        box(atlas, (-4.65, 1.4, -2.65), (4.65, 2.8, 2.65), leather),                    # belt + buckle
        box(atlas, (-4.1, -1.6, 2.1), (-0.3, 1.6, 2.9), tasset),                        # tassets
        box(atlas, (0.3, -1.6, 2.1), (4.1, 1.6, 2.9), tasset),
        box(atlas, (-4.3, -4.5, -3.35), (4.3, 12.2, -2.6), cape),                       # cape
        box(atlas, (-2.6, 12, -2.2), (2.6, 12.8, 2.2), gold),                           # gorget
    ]

    # ---------------- arms (one model, used for both)
    def pauldron(reg, face, wu, hu):
        armor(reg, face, wu, hu, trims=[lambda r, f: trim(r, pal, rows=[-1, -3]) if f not in ("up", "down") else None])

    def gauntlet(reg, face, wu, hu):
        p2 = dict(pal); p2["base"] = shade(pal["base"], 0.82)
        plate(reg, p2, rng, sparkle=0)
        if blaze: cracks(reg, pal, rng, 1)
        if face not in ("up", "down"): trim(reg, pal, rows=[0])
        if face == "south":
            for x in range(1, reg.shape[1] - 1, 2): reg[-2, x, :3] = pal["dark"]  # knuckles

    parts["arm"] = [
        box(atlas, (-1.8, -11.5, -1.8), (1.8, 0.5, 1.8), armor),                     # vambrace
        box(atlas, (-1.95, -6.9, -1.95), (1.95, -5.3, 1.95), gold),                 # elbow band
        box(atlas, (-2.15, -12.4, -2.15), (2.15, -8.6, 2.15), gauntlet),
        box(atlas, (-2.75, -3.2, -2.75), (2.75, 1.6, 2.75), pauldron),
        box(atlas, (-2.3, 1.6, -2.3), (2.3, 2.4, 2.3), armor),                       # pauldron dome
    ]

    # ---------------- legs (one model, used for both)
    def sabaton(reg, face, wu, hu):
        p2 = dict(pal); p2["base"] = shade(pal["base"], 0.75)
        plate(reg, p2, rng, sparkle=0)
        if blaze: cracks(reg, pal, rng, 1)

    parts["leg"] = [
        box(atlas, (-2, -12, -2), (2, 0, 2), under),
        box(atlas, (-2.2, -6, -2.2), (2.2, 0, 2.2), armor),                          # cuisse
        box(atlas, (-1.6, -7.4, 2.0), (1.6, -5.1, 2.7), gold),                       # knee cop
        box(atlas, (-2.15, -9.2, -2.15), (2.15, -6.6, 2.15), armor),                 # greave
        box(atlas, (-2.35, -12, -2.35), (2.35, -9.2, 2.35), sabaton),                # boot
        box(atlas, (-2.0, -12, 2.35), (2.0, -10.7, 3.4), sabaton),                   # toe
    ]
    return atlas, parts

# ------------------------------------------------------------------ the hawk

HAWK = dict(back=(92, 58, 34), back_hi=(132, 86, 48), back_dark=(54, 34, 20), cream=(226, 204, 158), streak=(120, 76, 40),
            rufous=(184, 82, 36), rufous_hi=(214, 120, 60), band=(40, 24, 16), beak=(240, 196, 60), beak_tip=(40, 36, 40),
            eye=(250, 170, 20), pupil=(16, 10, 8), leather=(98, 60, 32), leather_hi=(140, 92, 50),
            gold=(226, 178, 40), gold_hi=(255, 236, 130), white=(240, 236, 226))


def hawk(seed):
    atlas = Atlas(256, 2)
    rng = random.Random(seed)
    P = HAWK
    parts = {}

    def feathers(reg, face, wu, hu, base="back", rows=3, tips=False, tip_axis="y", under_light=True):
        h, w = reg.shape[:2]
        light_face = face == "down" and under_light
        noise_fill(reg, P["cream"] if light_face else P[base], rng, 6)
        if light_face:  # barred underside
            for y in range(1, h, 4): reg[y, ::2, :3] = P["streak"]
        else:  # overlapping feather scallops
            for y in range(h):
                for x in range(w):
                    if (y % rows == rows - 1) and ((x + (y // rows) * 2) % 4 != 0): reg[y, x, :3] = P["back_dark"]
                    elif (y % rows == 0) and rng.random() < 0.5: reg[y, x, :3] = P["back_hi"]
        if tips:  # ragged feather tips along the far edge
            if tip_axis == "x":
                for y in range(h):
                    for x in range(w - rng.randrange(0, 4) - (2 if y % 4 < 2 else 0), w): reg[y, x, 3] = 0
            else:
                for x in range(w):
                    for y in range(h - rng.randrange(0, 4) - (2 if x % 4 < 2 else 0), h): reg[y, x, 3] = 0

    def chest(reg, face, wu, hu):
        h, w = reg.shape[:2]
        noise_fill(reg, P["cream"], rng, 6)
        for y in range(2, h, 3):
            for x in range(rng.randrange(0, 3), w, 3): reg[y, x, :3] = P["streak"]

    def plain(c, amt=6):
        def p(reg, face, wu, hu):
            noise_fill(reg, P[c], rng, amt)
        return p

    def saddle(reg, face, wu, hu):
        noise_fill(reg, P["leather"], rng, 6)
        reg[0, :, :3] = P["leather_hi"]
        if face in ("east", "west", "north", "south"): reg[-1, :, :3] = P["gold"]

    def leg(reg, face, wu, hu):
        noise_fill(reg, P["beak"], rng, 8)
        for y in range(0, reg.shape[0], 2): reg[y, :, :3] = shade(P["beak"], 0.8)

    parts["body"] = [
        box(atlas, (-3.5, -2.2, -6), (3.5, 4, 5.5), feathers),                              # torso
        box(atlas, (-3.1, -3.2, 2.5), (3.1, 2.8, 7.6), chest),                              # chest
        box(atlas, (-2.1, 0.6, 5.5), (2.1, 4.6, 8.6), feathers),                            # neck
        box(atlas, (-2.6, -1.4, -8.4), (2.6, 3.0, -5.6), feathers),                         # rump
        box(atlas, (-3.8, 1.0, -5), (3.8, 4.3, 3.5), feathers, rows=2),                    # shoulders/back
        box(atlas, (-2.4, 4.3, -4.2), (2.4, 5.1, 1.0), saddle),                             # saddle
        box(atlas, (-1.0, 5.1, 0.2), (1.0, 6.3, 1.0), plain("gold")),                       # saddle horn
        box(atlas, (-2.6, -5.2, 0), (-1.2, -2.2, 1.6), leg), box(atlas, (1.2, -5.2, 0), (2.6, -2.2, 1.6), leg),
        box(atlas, (-2.9, -6, -0.6), (-0.9, -5.2, 3.0), plain("beak_tip")), box(atlas, (0.9, -6, -0.6), (2.9, -5.2, 3.0), plain("beak_tip")),
    ]

    def skull(reg, face, wu, hu):
        h, w = reg.shape[:2]
        noise_fill(reg, P["back"], rng, 6)
        if face in ("east", "west"):
            reg[h // 2:, :, :3] = P["cream"]                         # pale cheek/throat
            ex = 2 if face == "east" else w - 4                       # the eye sits toward the front (+z)
            ex = max(0, min(w - 3, ex))
            ey = max(0, h // 2 - 3)
            reg[ey:ey + 3, ex:ex + 3, :3] = P["eye"]; reg[ey + 1, ex + 1, :3] = P["pupil"]
            reg[ey - 1 if ey > 0 else 0, ex:ex + 3, :3] = P["back_dark"]   # fierce brow
        if face == "down": reg[:, :, :3] = P["cream"]
        if face == "south": reg[h // 2:, :, :3] = P["cream"]

    def beak(reg, face, wu, hu):
        h, w = reg.shape[:2]
        noise_fill(reg, P["beak_tip"], rng, 6)
        reg[:, : max(1, w // 3), :3] = P["beak"] if face in ("east", "west") else P["beak_tip"]

    parts["head"] = [
        box(atlas, (-3.0, -1.6, -1.8), (3.0, 4.2, 4.4), skull),
        box(atlas, (-3.25, 2.6, 1.8), (3.25, 3.7, 4.8), plain("back_dark")),                 # brow ridge
        box(atlas, (-1.8, 0.8, 4.3), (1.8, 3.0, 5.0), plain("beak")),                        # cere
        box(atlas, (-1.35, 0.3, 5.0), (1.35, 2.7, 7.6), beak),                               # upper beak
        box(atlas, (-0.9, -0.9, 6.6), (0.9, 0.7, 8.0), plain("beak_tip")),                   # the hook
        box(atlas, (-1.1, -1.0, 4.4), (1.1, 0.3, 6.4), plain("beak")),                       # lower beak
        box(atlas, (-1.4, 2.0, -2.8), (1.4, 3.2, -1.6), feathers, rows=2),                   # nape feathers
    ]

    def tail(reg, face, wu, hu):
        h, w = reg.shape[:2]
        if face in ("up", "down"):
            noise_fill(reg, P["rufous"] if face == "up" else P["cream"], rng, 8)
            for x in range(0, w, 3): reg[:, x, :3] = shade(reg[0, x, :3], 0.75)   # feather shafts
            reg[h - 6:h - 4, :, :3] = P["band"]                                     # dark sub-terminal band
            reg[h - 3:, :, :3] = P["white"]
            for x in range(w):                                                      # rounded feather ends
                if x % 3 == 0: reg[h - 1, x, 3] = 0
            if face == "up": reg[:] = reg[::-1].copy()                              # up faces run tip-at-top
        else:
            noise_fill(reg, P["rufous"], rng, 8)

    def tail_base(reg, face, wu, hu):
        feathers(reg, face, wu, hu, base="back", rows=2, under_light=False)

    # tail faces run along -z: the far (bottom of texture for up/down) edge is the tip, see box(): up = z1 at top
    parts["tail"] = [
        box(atlas, (-2.2, -0.7, -4.5), (2.2, 0.7, 0.5), tail_base),
        box(atlas, (-5.2, -0.35, -13), (5.2, 0.35, -4), tail_t := tail),
        box(atlas, (-2.6, 0.35, -14), (2.6, 0.75, -5), tail_t),
    ]

    def wing_part(tips, axis, base="back", rows=3, bars=False):
        def p(reg, face, wu, hu):
            feathers(reg, face, wu, hu, base=base, rows=rows, tips=tips and face in ("up", "down"), tip_axis=axis)
            if face == "up" and not bars:  # lighter coverts toward the leading edge
                h = reg.shape[0]
                for y in range(h):
                    k = 0.85 + 0.35 * (y / max(1, h - 1))
                    reg[y, :, :3] = np.clip(reg[y, :, :3] * k, 0, 255)
            if bars and face in ("up", "down"):  # dark bars across the flight feathers
                for x in range(1, reg.shape[1], 5): reg[:, x, :3] = np.where(reg[:, x, 3:4] > 0, np.array(P["band"], float), reg[:, x, :3])
            if tips and face == "up": reg[:] = reg[::-1].copy()   # the trailing edge (-z) is the TOP of an up face
        return p

    def primaries(reg, face, wu, hu, sign=1):
        h, w = reg.shape[:2]
        if face not in ("up", "down"):
            noise_fill(reg, P["back_dark"], rng, 6); return
        noise_fill(reg, P["back_dark"] if face == "up" else P["cream"], rng, 6)
        for y in range(0, h, 4):  # separate "finger" feathers
            reg[y, :, :3] = P["band"]
            if face == "down": reg[y + 1 if y + 1 < h else y, :, :3] = P["streak"]
        for y in range(h):
            cut = (y * 3 + rng.randrange(0, 3)) % 7
            for x in range(w - cut, w): reg[y, x, 3] = 0
            if y % 4 == 0:
                for x in range(w - cut - 3, w): reg[y, x, 3] = 0
        if sign < 0: reg[:] = reg[:, ::-1].copy()   # wing_l: the tip is at -x (the left of the face)

    def make_wing(sign):
        def X(a, b):
            return (a, b) if sign > 0 else (-b, -a)
        el = []
        x1, x2 = X(0, 11)
        el.append(box(atlas, (x1, -0.8, -3), (x2, 0.8, 5.2), wing_part(False, "y", rows=2)))   # arm: coverts
        x1, x2 = X(0, 12.5)
        el.append(box(atlas, (x1, -0.45, -8.5), (x2, 0.45, -3), wing_part(True, "y", base="back_dark", bars=True)))  # secondaries
        x1, x2 = X(10.5, 17.5)
        el.append(box(atlas, (x1, -0.6, -2.2), (x2, 0.6, 4.6), wing_part(False, "y", rows=2)))  # hand
        x1, x2 = X(15.5, 24)
        el.append(box(atlas, (x1, -0.35, -6.5), (x2, 0.35, 3.4), primaries, sign=sign))                    # primaries
        x1, x2 = X(0.5, 17)
        el.append(box(atlas, (x1, 0.8, 3.6), (x2, 1.3, 5.4), plain("back_dark")))              # leading edge
        return el

    parts["wing_r"] = make_wing(+1)
    parts["wing_l"] = make_wing(-1)
    return atlas, parts

# ------------------------------------------------------------------ writing

def write_model(pack, name, tex, elements):
    path = os.path.join(pack, "assets/faultline/models/item", name + ".json")
    m = {"textures": {"t": "faultline:item/" + tex, "particle": "faultline:item/" + tex}, "elements": elements}
    with open(path, "w") as f: json.dump(m, f, indent=1)
    items = os.path.join(pack, "assets/faultline/items", name + ".json")
    if not os.path.exists(items):
        with open(items, "w") as f: json.dump({"model": {"type": "minecraft:model", "model": "faultline:item/" + name}}, f, indent=1)


def write_tex(pack, tex, atlas):
    img = Image.fromarray(np.clip(atlas.img, 0, 255).astype(np.uint8), "RGBA")
    img.save(os.path.join(pack, "assets/faultline/textures/item", tex + ".png"))
    return img

# ------------------------------------------------------------------ preview renderer (z-buffered, orthographic)

CORNERS = {  # TL, TR, BL for each face (Minecraft's UV orientation)
    "south": lambda f, t: ((f[0], t[1], t[2]), (t[0], t[1], t[2]), (f[0], f[1], t[2])),
    "north": lambda f, t: ((t[0], t[1], f[2]), (f[0], t[1], f[2]), (t[0], f[1], f[2])),
    "east": lambda f, t: ((t[0], t[1], t[2]), (t[0], t[1], f[2]), (t[0], f[1], t[2])),
    "west": lambda f, t: ((f[0], t[1], f[2]), (f[0], t[1], t[2]), (f[0], f[1], f[2])),
    "up": lambda f, t: ((f[0], t[1], f[2]), (t[0], t[1], f[2]), (f[0], t[1], t[2])),
    "down": lambda f, t: ((f[0], f[1], t[2]), (t[0], f[1], t[2]), (f[0], f[1], f[2])),
}
SHADE = {"up": 1.0, "south": 0.82, "north": 0.82, "east": 0.62, "west": 0.62, "down": 0.5}


ANCHOR = 0.62


def render(placed, yaw, pitch, scale, size):
    """placed: list of (elements, texture ndarray, offset xyz). Returns an RGBA image."""
    W, H = size
    out = np.zeros((H, W, 4)); out[:, :, :3] = (34, 38, 46); out[:, :, 3] = 255
    zb = np.full((H, W), -1e9)
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))

    def proj(p):
        x, y, z = p
        x, z = x * cy - z * sy, x * sy + z * cy
        y, z = y * cp - z * sp, y * sp + z * cp
        return np.array([W / 2 + x * scale, H * ANCHOR - y * scale, z])

    for els, tex, off in placed:
        th, tw = tex.shape[:2]
        for e in els:
            f = [e["from"][i] - C + off[i] for i in range(3)]; t = [e["to"][i] - C + off[i] for i in range(3)]
            for face, fd in e["faces"].items():
                tl, tr, bl = [proj(p) for p in CORNERS[face](f, t)]
                u1, v1, u2, v2 = [v * tw / 16 for v in fd["uv"]]
                ex, ey = tr - tl, bl - tl
                det = ex[0] * ey[1] - ex[1] * ey[0]
                if abs(det) < 1e-6: continue
                xs = [tl[0], tr[0], bl[0], tr[0] + ey[0]]; ys = [tl[1], tr[1], bl[1], tr[1] + ey[1]]
                x0, x1 = max(0, int(min(xs))), min(W - 1, int(max(xs)) + 1)
                y0, y1 = max(0, int(min(ys))), min(H - 1, int(max(ys)) + 1)
                if x0 > x1 or y0 > y1: continue
                gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
                dx, dy = gx - tl[0], gy - tl[1]
                s = (dx * ey[1] - dy * ey[0]) / det
                tt = (ex[0] * dy - ex[1] * dx) / det
                m = (s >= 0) & (s <= 1) & (tt >= 0) & (tt <= 1)
                if not m.any(): continue
                depth = tl[2] + s * ex[2] + tt * ey[2]
                uu = np.clip((u1 + s * (u2 - u1)).astype(int), 0, tw - 1)
                vv = np.clip((v1 + tt * (v2 - v1)).astype(int), 0, th - 1)
                px = tex[vv, uu]
                m &= px[:, :, 3] > 127
                sub = zb[y0:y1 + 1, x0:x1 + 1]
                m &= depth > sub
                sub[m] = depth[m]
                o = out[y0:y1 + 1, x0:x1 + 1]
                o[m, :3] = px[m, :3] * SHADE[face]
    return Image.fromarray(out.astype(np.uint8), "RGBA")


JOINT = {"leg_r": (-2, 12, 0), "leg_l": (2, 12, 0), "body": (0, 12, 0), "arm_r": (-5.5, 24, 0), "arm_l": (5.5, 24, 0), "head": (0, 24, 0)}
BIRD_JOINT = {"body": (0, 0, 0), "head": (0, 2, 7), "tail": (0, 0.5, -7), "wing_r": (3, 2.5, 1), "wing_l": (-3, 2.5, 1)}


def preview(outdir, name, models, tex, joints, views, scale, size):
    t = np.asarray(tex).astype(np.float64)
    placed = [(models[k], t, joints[k]) for k in joints]
    imgs = [render(placed, yw, pt, scale, size) for yw, pt in views]
    sheet = Image.new("RGBA", (size[0] * len(imgs), size[1]))
    for i, im in enumerate(imgs): sheet.paste(im, (i * size[0], 0))
    sheet.save(os.path.join(outdir, name + ".png"))


def set_anchor(a):
    global ANCHOR
    ANCHOR = a

# ------------------------------------------------------------------ main

def main():
    pack = sys.argv[1]
    prev = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    for d in ("models/item", "textures/item", "items"): os.makedirs(os.path.join(pack, "assets/faultline", d), exist_ok=True)
    for form, pal, blaze, seed in (("armor", DIAMOND, False, 7), ("blaze", BLAZE, True, 11)):
        atlas, parts = knight(pal, blaze, seed)
        tex = "jacob_" + form
        img = write_tex(pack, tex, atlas)
        names = {"head": "head", "body": "body", "arm_r": "arm", "arm_l": "arm", "leg_r": "leg", "leg_l": "leg"}
        for part, src in names.items(): write_model(pack, f"jacob_{form}_{part}", tex, parts[src])
        if prev:
            set_anchor(0.97)
            preview(prev, "jacob_" + form, {k: parts[v] for k, v in names.items()}, img, JOINT,
                    [(20, 12), (160, 12), (-35, 25)], 9, (360, 520))
    atlas, parts = hawk(5)
    img = write_tex(pack, "jacob_bird", atlas)
    for part in ("body", "head", "tail", "wing_r", "wing_l"): write_model(pack, "jacob_bird_" + part, "jacob_bird", parts[part])
    if prev:
        set_anchor(0.55)
        preview(prev, "jacob_bird", parts, img, BIRD_JOINT, [(30, 25), (160, 20), (0, 75)], 6, (420, 420))
        preview(prev, "jacob_bird_close", {k: parts[k] for k in ("body", "head", "tail")}, img,
                {k: BIRD_JOINT[k] for k in ("body", "head", "tail")}, [(35, 10), (90, 5)], 16, (420, 360))


if __name__ == "__main__":
    main()
