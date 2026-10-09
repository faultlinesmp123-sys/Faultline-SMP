#!/usr/bin/env python3
"""
Art for the "wild" update: the Leviathan, the Sandworm King, the Lich, the Frost Wyrm, the Stone Golem, the Ghost Ship's
captain, Mimics, Locusts, Skeleton Knights, the aurora, and the new items (mining helmet, Lantern of
Souls, boss music discs, museum pedestal, boss trophies, boss drops and summoning items).

Every 3D model is authored ONCE as boxes in blocks (local frame: +x = its FRONT, +y = up, +z = its RIGHT side) and
written for both editions:
  Java     assets/faultline/items|models/item|textures/item/wild/<part>   an item model the plugins draw on an
           ItemDisplay. Java item models must fit 48 units (-16..32), so big parts are drawn small and the display
           scales them back up: WildParts.java (generated) holds each part's display scale and offset.
  Bedrock  rp/models/entity/faultline/wild_<part>.geo.json + attachables/faultline.wild_<part>.json: worn as the
           helmet of an armor stand only Bedrock players see (the way ships are drawn), full size, origin = the stand.
           Geyser maps paper with item model faultline:wild/<part> to it (faultline_wild_mappings.json).
Worn items (knight_helm, mining_helmet) are head-pixel models like the cosmetic hats (Java display.head scale 1.6,
Bedrock helmet attachable on the head bone).
Flat items get a 16x16 texture; it is also their Faultline Index icon. New Index icons get glyphs + font providers.

Usage: python3 tools/wild_assets.py <unpacked-pack-dir> <bedrock-out-dir> [--preview <dir>]
Then zip the pack, update the hash, and run tools/bedrock_pack.py (it skips what faultline_wild_mappings.json maps).
"""
import json, math, os, random, re, sys, zipfile
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from jacob_models import Atlas, box as head_box, noise_fill, plate          # noqa: E402
from cosmetics_assets import to_bedrock_head_geo                            # noqa: E402

NS = "faultline"
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")

# =====================================================================================================================
#  painters: fn(reg, face, rng) fills an (h, w, 4) float region
# =====================================================================================================================

def _fill(reg, c, rng, n=6):
    noise_fill(reg, c, rng, n)


def solid(c, n=6):
    return lambda reg, face, rng: _fill(reg, c, rng, n)


def glow(c):
    def f(reg, face, rng):
        _fill(reg, c, rng, 3)
        h, w = reg.shape[:2]
        if h > 2 and w > 2: reg[1:-1, 1:-1, :3] = np.clip(np.array(c) * 1.15 + 20, 0, 255)
    return f


def scales(c, dark):
    def f(reg, face, rng):
        _fill(reg, c, rng, 7)
        h, w = reg.shape[:2]
        for y in range(h):
            for x in range(w):
                if (y % 3 == 2) and ((x + (y // 3)) % 3 != 1): reg[y, x, :3] = dark
                elif rng.random() < 0.06: reg[y, x, :3] = np.clip(np.array(c) + 30, 0, 255)
    return f


def bands(c, dark, every=4):
    def f(reg, face, rng):
        _fill(reg, c, rng, 6)
        h, w = reg.shape[:2]
        if face in ("up", "down", "east", "west", "north", "south"):
            for x in range(w):
                if x % every == 0: reg[:, x, :3] = dark
    return f


def stripes_h(c, dark, every=3):
    def f(reg, face, rng):
        _fill(reg, c, rng, 6)
        for y in range(reg.shape[0]):
            if y % every == every - 1: reg[y, :, :3] = dark
    return f


def stone(c, moss=None):
    def f(reg, face, rng):
        _fill(reg, c, rng, 12)
        h, w = reg.shape[:2]
        for _ in range(max(1, w * h // 7)):  # cracks
            x, y = rng.randrange(w), rng.randrange(h)
            reg[y, x, :3] = np.clip(np.array(c) - 45, 0, 255)
        if moss:
            for y in range(h):
                for x in range(w):
                    top = face == "up" or y < max(1, h // 4)
                    if (top and rng.random() < 0.55) or rng.random() < 0.08:
                        reg[y, x, :3] = np.clip(np.array(moss) + rng.uniform(-15, 15), 0, 255)
    return f


def ice(c):
    def f(reg, face, rng):
        _fill(reg, c, rng, 6)
        h, w = reg.shape[:2]
        for _ in range(max(1, (w + h) // 3)):
            x, y = rng.randrange(w), rng.randrange(h)
            for k in range(3):
                if 0 <= y + k < h and 0 <= x + k < w: reg[y + k, x + k, :3] = (240, 250, 255)
    return f


def robe(c):
    def f(reg, face, rng):
        _fill(reg, c, rng, 5)
        h, w = reg.shape[:2]
        for x in range(w):
            if x % 3 == 0: reg[:, x, :3] = np.clip(np.array(c) - 18, 0, 255)
        if face not in ("up", "down"):
            for x in range(w):  # a ragged hem
                if rng.random() < 0.4: reg[h - 1, x, 3] = 0
    return f


def membrane(c, vein):
    def f(reg, face, rng):
        _fill(reg, c, rng, 5)
        h, w = reg.shape[:2]
        for y in range(h):
            for x in range(w):
                if (x + y) % 6 == 0 or (x - y) % 9 == 0: reg[y, x, :3] = vein
    return f


def wood(c, band):
    def f(reg, face, rng):
        _fill(reg, c, rng, 8)
        h, w = reg.shape[:2]
        for y in range(h):
            if y % 4 == 0: reg[y, :, :3] = np.clip(np.array(c) - 25, 0, 255)
        if band:
            for x in (0, w - 1):
                reg[:, x, :3] = band
    return f


def metal(c):
    pal = dict(base=c, light=tuple(min(255, v + 40) for v in c), dark=tuple(max(0, v - 45) for v in c), hi=tuple(min(255, v + 70) for v in c))
    return lambda reg, face, rng: plate(reg, pal, rng, sparkle=0.03)


def teeth(reg, face, rng):
    _fill(reg, (236, 228, 206), rng, 6)
    h, w = reg.shape[:2]
    if w > 2: reg[:, ::3, :3] = (180, 168, 140)


# =====================================================================================================================
#  models in blocks
# =====================================================================================================================

class Part:
    """A model part. ppb = texture pixels per block (per face, capped), size = its texture size."""

    def __init__(self, name, ppb=6, size=128, java_only=False, bedrock_only=False):
        self.name, self.ppb, self.size, self.java_only, self.bedrock_only = name, ppb, size, java_only, bedrock_only
        self.boxes = []

    def add(self, x0, y0, z0, x1, y1, z1, paint):
        self.boxes.append(((min(x0, x1), min(y0, y1), min(z0, z1)), (max(x0, x1), max(y0, y1), max(z0, z1)), paint))
        return self

    def mirror_z(self, name):
        p = Part(name, self.ppb, self.size, self.java_only, self.bedrock_only)
        for (a, b, paint) in self.boxes: p.boxes.append(((a[0], a[1], -b[2]), (b[0], b[1], -a[2]), paint))
        return p

    def bounds(self):
        lo = [min(b[0][i] for b in self.boxes) for i in range(3)]
        hi = [max(b[1][i] for b in self.boxes) for i in range(3)]
        return lo, hi


# local face -> (Java face, Bedrock face, the two axes its texture spans)
FACES = {"+x": ("east", "north", (2, 1)), "-x": ("west", "south", (2, 1)), "+z": ("south", "west", (0, 1)),
         "-z": ("north", "east", (0, 1)), "+y": ("up", "up", (0, 2)), "-y": ("down", "down", (0, 2))}


def build(part, seed):
    """Paint the atlas; returns (texture image, [(box, {face: (px, py, w, h)})])."""
    while True:  # grow the texture until every face fits
        try: return _build(part, seed)
        except RuntimeError: part.size *= 2


def _build(part, seed):
    rng = random.Random(seed)
    atlas = Atlas(part.size, 1)
    out = []
    for (a, b, paint) in part.boxes:
        size = [b[i] - a[i] for i in range(3)]
        regs = {}
        for key, (jf, bf, (u, v)) in FACES.items():
            w = int(min(32, max(1, round(size[u] * part.ppb))))
            h = int(min(32, max(1, round(size[v] * part.ppb))))
            x, y = atlas.alloc(w, h)
            paint(atlas.img[y:y + h, x:x + w], jf, rng)
            regs[key] = (x, y, w, h)
        out.append(((a, b), regs))
    img = Image.fromarray(np.clip(atlas.img, 0, 255).astype(np.uint8), "RGBA")
    return img, out


def java_model(part, boxes):
    """Java item model elements, scaled to fit -16..32. Returns (model json, display scale, offset in blocks)."""
    lo, hi = part.bounds()
    ext = max(hi[i] - lo[i] for i in range(3))
    u = min(16.0, 47.0 / ext)                         # model units per block
    cx, cz = (lo[0] + hi[0]) / 2, (lo[2] + hi[2]) / 2
    yb = -16.0 if (hi[1] - lo[1]) * u <= 47.5 else 8 - (hi[1] - lo[1]) * u / 2
    def U(p): return [round((p[0] - cx) * u + 8, 4), round((p[1] - lo[1]) * u + yb, 4), round((p[2] - cz) * u + 8, 4)]
    els = []
    s = 16.0 / part.size
    for (a, b), regs in boxes:
        e = {"from": U(a), "to": U(b), "faces": {}}
        for key, (x, y, w, h) in regs.items():
            jf = FACES[key][0]
            e["faces"][jf] = {"uv": [round(x * s, 4), round(y * s, 4), round((x + w) * s, 4), round((y + h) * s, 4)], "texture": "#t"}
        els.append(e)
    S = 16.0 / u                                       # display scale: back to real size
    # where the part's local origin (0,0,0) ends up, so the plugin can put it at the entity: offset = -(that)
    o = U((0, 0, 0))
    off = [-(o[0] - 8) / 16 * S, -(o[1] - 8) / 16 * S, -(o[2] - 8) / 16 * S]
    return {"textures": {"t": f"{NS}:item/wild/{part.name}", "particle": f"{NS}:item/wild/{part.name}"}, "elements": els}, S, off


def bedrock_geo(part, boxes):
    cubes = []
    for (a, b), regs in boxes:
        # local +x (front) -> Bedrock -z, local +z (right) -> Bedrock -x (the ship convention)
        bx0, bx1 = -b[2] * 16, -a[2] * 16
        bz0, bz1 = -b[0] * 16, -a[0] * 16
        cube = {"origin": [round(bx0, 3), round(a[1] * 16, 3), round(bz0, 3)],
                "size": [round(bx1 - bx0, 3), round((b[1] - a[1]) * 16, 3), round(bz1 - bz0, 3)], "uv": {}}
        for key, (x, y, w, h) in regs.items():
            cube["uv"][FACES[key][1]] = {"uv": [x, y], "uv_size": [w, h]}
        cubes.append(cube)
    lo, hi = part.bounds()
    vb = max(4, math.ceil(max(hi[0] - lo[0], hi[2] - lo[2], hi[1] - lo[1]) * 2 + 2))
    return {"format_version": "1.16.0", "minecraft:geometry": [{
        "description": {"identifier": f"geometry.faultline.wild_{part.name}", "texture_width": part.size, "texture_height": part.size,
                        "visible_bounds_width": vb, "visible_bounds_height": vb, "visible_bounds_offset": [0, (lo[1] + hi[1]) / 2, 0]},
        "bones": [{"name": "body", "pivot": [0, 24, 0], "cubes": cubes}]}]}


# ---------------------------------------------------------------------------------------------- the parts

TEAL, TEAL_D, BELLY, FIN = (24, 86, 104), (12, 50, 64), (196, 206, 176), (60, 180, 196)
YEL, MOUTH, HORN = (255, 222, 70), (96, 22, 34), (58, 56, 70)


def leviathan():
    """A sea serpent-dragon: long fanged jaws, swept horns, a crest fin, gill frills, barbels and glowing spots; finned
    body segments with a webbed dorsal crest; a crescent tail fluke."""
    sc = scales(TEAL, TEAL_D)
    fin = membrane(FIN, (30, 120, 140))
    spot = glow((120, 255, 230))
    h = Part("lev_head", 6, 128)
    h.add(-1.3, -0.95, -1.15, 1.0, 0.95, 1.15, sc)                 # skull
    h.add(1.0, -0.25, -0.8, 3.0, 0.65, 0.8, sc)                   # upper jaw
    h.add(3.0, -0.2, -0.6, 3.5, 0.45, 0.6, sc)
    h.add(0.6, -1.35, -0.72, 2.9, -0.8, 0.72, sc)                 # lower jaw, dropped open
    h.add(0.8, -1.42, -0.55, 2.8, -1.3, 0.55, solid(BELLY))
    h.add(1.0, -0.8, -0.62, 2.85, -0.25, 0.62, solid(MOUTH, 4))   # the gape
    for i in range(7):
        x = 1.15 + i * 0.27
        for z in (-0.72, 0.6):
            h.add(x, -0.6, z, x + 0.13, -0.25, z + 0.12, teeth)   # upper fangs
            h.add(x + 0.1, -0.8, z, x + 0.22, -0.5, z + 0.12, teeth)  # lower fangs
    h.add(3.1, -0.55, -0.12, 3.35, -0.2, 0.12, teeth)             # a front tusk
    h.add(-1.4, 0.95, -0.08, 1.2, 2.0, 0.08, fin)                 # crest fin
    for x in (-1.1, -0.3, 0.5):
        h.add(x - 0.06, 0.95, -0.1, x + 0.06, 2.15, 0.1, solid(HORN, 6))
    for s in (-1, 1):
        h.add(0.55, 0.2, s * 1.15, 1.0, 0.6, s * 1.24, glow(YEL))     # eyes
        h.add(0.4, 0.6, s * 0.6, 1.2, 0.85, s * 1.2, solid(TEAL_D))   # brow
        h.add(-2.2, 0.55, s * 0.62, -0.5, 1.0, s * 0.95, solid(HORN, 8))   # horns
        h.add(-3.1, 0.85, s * 0.66, -2.1, 1.3, s * 0.9, solid(HORN, 8))
        h.add(-3.7, 1.15, s * 0.7, -3.0, 1.45, s * 0.84, solid(HORN, 8))
        h.add(-1.2, -0.8, s * 1.15, -0.2, 0.6, s * 1.75, fin)              # gill frills
        h.add(-0.9, -0.5, s * 1.75, -0.4, 0.3, s * 2.05, fin)
        h.add(2.6, -0.4, s * 0.8, 2.75, -0.25, s * 0.95, solid(TEAL_D))    # barbels
        h.add(2.5, -1.4, s * 0.86, 2.65, -0.4, s * 0.98, solid((40, 110, 120), 4))
        h.add(2.45, -1.9, s * 0.88, 2.6, -1.4, s * 1.0, spot)
        for x in (-0.8, 0.0, 1.5, 2.2): h.add(x, 0.0, s * 1.15 if x < 1 else s * 0.8, x + 0.18, 0.15, s * (1.18 if x < 1 else 0.83), spot)
    seg = Part("lev_body", 6, 128)
    seg.add(-1.25, -1.05, -1.15, 1.25, 1.05, 1.15, sc)
    seg.add(-1.2, -1.13, -0.92, 1.2, -0.62, 0.92, stripes_h(BELLY, (160, 170, 140)))
    seg.add(-1.1, 1.05, -0.08, 1.0, 2.3, 0.08, fin)                 # dorsal crest
    for x in (-0.8, 0.0, 0.8): seg.add(x - 0.06, 1.05, -0.1, x + 0.06, 2.45 - abs(x) * 0.4, 0.1, solid(HORN, 6))
    for s in (-1, 1):
        seg.add(-0.6, -0.55, s * 1.15, 0.5, -0.1, s * 1.85, fin)      # side fins
        for x in (-0.7, 0.1, 0.8): seg.add(x, 0.1, s * 1.15, x + 0.2, 0.3, s * 1.18, spot)
    tail = Part("lev_tail", 6, 128)
    tail.add(-1.4, -0.85, -0.95, 0.75, 0.85, 0.95, sc)
    tail.add(-2.7, -0.6, -0.65, -1.4, 0.6, 0.65, sc)
    tail.add(-3.4, -0.35, -0.4, -2.7, 0.35, 0.4, sc)
    tail.add(-1.2, 0.85, -0.07, 0.4, 1.6, 0.07, fin)
    for (y0, y1, x0, x1) in ((0.3, 2.6, -4.2, -3.2), (2.0, 3.4, -4.9, -3.8), (-2.6, -0.3, -4.2, -3.2), (-3.4, -2.0, -4.9, -3.8)):
        tail.add(x0, y0, -0.09, x1, y1, 0.09, fin)                  # the crescent fluke (upright)
    tail.add(-3.6, -0.5, -0.12, -3.1, 0.5, 0.12, spot)
    return [h, seg, tail]


SAND, SAND_D, MAW, THROAT, BONE = (200, 164, 104), (118, 88, 50), (74, 24, 24), (26, 6, 6), (230, 216, 184)


def sandworm():
    """The Sandworm KING: an armoured, plated head with a four-petal maw (each petal fanged), two rings of teeth down a
    glowing throat and a crown of bone spikes; plated, ridged body rings; a tail ending in a barbed stinger."""
    pl = bands(SAND, SAND_D, 5)
    armor = stone((176, 140, 86))
    crown = solid(BONE, 6)
    h = Part("worm_head", 5, 128)
    h.add(-1.5, -1.5, -1.5, 0.9, 1.5, 1.5, pl)
    for x in (-1.2, -0.45, 0.3):                                     # overlapping armour rings
        h.add(x, -1.62, -1.62, x + 0.45, 1.62, 1.62, armor)
    h.add(0.9, -1.3, -1.3, 1.3, 1.3, 1.3, solid(MAW, 6))
    h.add(1.3, -0.85, -0.85, 1.36, 0.85, 0.85, solid(THROAT, 3))
    h.add(1.2, -0.4, -0.4, 1.38, 0.4, 0.4, glow((255, 120, 40)))   # the glow deep in its throat
    for ring, r, x in ((0, 1.05, 1.3), (1, 0.62, 1.32)):
        for kk in range(12 if ring == 0 else 8):
            a = kk * math.pi / (6 if ring == 0 else 4) + ring * 0.3
            y, z = math.sin(a) * r, math.cos(a) * r
            h.add(x, y - 0.1, z - 0.1, x + (0.5 if ring == 0 else 0.3), y + 0.1, z + 0.1, teeth)
    for (y0, y1, z0, z1, ty0, ty1, tz0, tz1) in ((1.0, 1.55, -0.75, 0.75, 1.3, 1.55, -0.2, 0.2), (-1.55, -1.0, -0.75, 0.75, -1.55, -1.3, -0.2, 0.2),
                                                 (-0.75, 0.75, 1.0, 1.55, -0.2, 0.2, 1.3, 1.55), (-0.75, 0.75, -1.55, -1.0, -0.2, 0.2, -1.55, -1.3)):
        h.add(1.0, y0, z0, 2.5, y1, z1, armor)                       # the four petals of the maw, flared open
        h.add(2.5, ty0, tz0, 3.1, ty1, tz1, teeth)                   # each tipped with a fang
        for xx in (1.4, 1.9):
            h.add(xx, (y0 + y1) / 2 - 0.08, (z0 + z1) / 2 - 0.08, xx + 0.12, (y0 + y1) / 2 + 0.08, (z0 + z1) / 2 + 0.08, teeth)
    for kk in range(8):                                               # the crown of bone spikes
        a = kk * math.pi / 4 + math.pi / 8
        y, z = math.sin(a), math.cos(a)
        h.add(-0.9, y * 1.6 - 0.14, z * 1.6 - 0.14, -0.5, y * 1.6 + 0.14, z * 1.6 + 0.14, crown)
        h.add(-1.4, y * 2.1 - 0.1, z * 2.1 - 0.1, -0.8, y * 2.1 + 0.1, z * 2.1 + 0.1, crown) if y > 0 else None
    h.add(-1.1, 1.62, -0.18, -0.2, 2.6, 0.18, crown)                 # the tallest spike
    s2 = Part("worm_body", 5, 128)
    s2.add(-1.35, -1.35, -1.35, 1.35, 1.35, 1.35, pl)
    s2.add(-1.4, -1.48, -1.48, -0.9, 1.48, 1.48, armor)
    s2.add(0.2, -1.42, -1.42, 0.7, 1.42, 1.42, armor)
    for x in (-0.6, 0.6):
        s2.add(x - 0.28, 1.35, -0.2, x + 0.28, 2.0, 0.2, crown)
        s2.add(x - 0.16, 2.0, -0.12, x + 0.16, 2.4, 0.12, crown)
    for sgn in (-1, 1): s2.add(-0.25, 0.2, sgn * 1.35, 0.25, 0.6, sgn * 1.85, crown)
    t = Part("worm_tail", 5, 128)
    t.add(-0.8, -1.05, -1.05, 1.2, 1.05, 1.05, pl)
    t.add(-0.2, -1.12, -1.12, 0.3, 1.12, 1.12, armor)
    t.add(-1.9, -0.75, -0.75, -0.8, 0.75, 0.75, pl)
    t.add(-2.9, -0.42, -0.42, -1.9, 0.42, 0.42, armor)
    t.add(-3.9, -0.2, -0.2, -2.9, 0.2, 0.2, crown)                   # the stinger
    for sgn in (-1, 1): t.add(-3.2, -0.08, sgn * 0.2, -2.9, 0.08, sgn * 0.5, crown)
    t.add(-1.4, 0.75, -0.14, -0.9, 1.3, 0.14, crown)
    return [h, s2, t]


ICE, ICE_D, FROST, CYAN, WMEM = (150, 200, 232), (90, 140, 186), (232, 244, 252), (110, 240, 255), (126, 176, 224)


def _stairs(p, x0, z0, x1, z1, y0, y1, w, paint, steps=7):
    """A diagonal bone as a run of small boxes from (x0, z0) to (x1, z1) (boxes can't be turned)."""
    for i in range(steps):
        t0, t1 = i / steps, (i + 1) / steps
        xa, xb = x0 + (x1 - x0) * t0, x0 + (x1 - x0) * t1
        za, zb = z0 + (z1 - z0) * t0, z0 + (z1 - z0) * t1
        p.add(min(xa, xb) - w, y0, min(za, zb) - w, max(xa, xb) + w, y1, max(za, zb) + w, paint)


def _lerp_pts(pts, z):
    """x at z along a polyline of (z, x) points (z falling)."""
    for (za, xa), (zb, xb) in zip(pts, pts[1:]):
        if za >= z >= zb:
            t = 0 if za == zb else (za - z) / (za - zb)
            return xa + (xb - xa) * t
    return pts[-1][1]


def frost_wyrm():
    """A real ice DRAGON: chest, four clawed legs, a spiked spine, a long neck (3 segments), a horned head, bat wings with
    finger bones and scalloped membranes, a spiked tail ending in an ice blade."""
    sc = scales(ICE, ICE_D)
    plate = stripes_h(FROST, (200, 216, 232))
    claw = solid((52, 66, 86), 4)
    bone = ice(FROST)
    b = Part("wyrm_body", 6, 128)
    b.add(-1.6, -0.85, -1.0, 1.0, 0.85, 1.0, sc)                 # torso
    b.add(0.4, -1.0, -1.15, 1.85, 0.92, 1.15, sc)                # deep chest
    b.add(-2.3, -0.75, -0.85, -1.4, 0.65, 0.85, sc)              # hips
    b.add(-2.1, -1.1, -0.7, 1.75, -0.84, 0.7, plate)             # belly plates
    for i, x in enumerate((1.4, 0.75, 0.1, -0.55, -1.2, -1.85)):  # spine spikes, biggest over the shoulders
        h = 0.8 - i * 0.09
        b.add(x - 0.2, 0.85, -0.12, x + 0.2, 0.85 + h, 0.12, bone)
        b.add(x - 0.32, 0.85 + h * 0.6, -0.06, x - 0.1, 0.85 + h + 0.25, 0.06, bone)
    for s in (-1, 1):
        b.add(-0.1, 0.45, s * 0.85, 0.95, 1.05, s * 1.25, sc)        # shoulder (the wing joint)
        b.add(0.9, -1.65, s * 0.75, 1.5, -0.55, s * 1.22, sc)        # front leg: upper arm
        b.add(1.3, -2.2, s * 0.8, 1.95, -1.55, s * 1.15, sc)         # forearm, reaching forward
        for dz in (-0.14, 0.0, 0.14):
            b.add(1.95, -2.3, s * 0.97 + dz - 0.045, 2.3, -2.08, s * 0.97 + dz + 0.045, claw)
        b.add(-2.15, -1.55, s * 0.7, -1.05, -0.25, s * 1.32, sc)      # hind leg: thigh
        b.add(-2.5, -2.3, s * 0.82, -1.9, -1.4, s * 1.22, sc)        # shin
        b.add(-2.6, -2.5, s * 0.76, -1.55, -2.28, s * 1.28, sc)      # foot
        for dz in (-0.16, 0.0, 0.16):
            b.add(-1.55, -2.5, s * 1.02 + dz - 0.05, -1.25, -2.32, s * 1.02 + dz + 0.05, claw)
    n = Part("wyrm_neck", 6, 64)
    n.add(-0.62, -0.48, -0.48, 0.62, 0.48, 0.48, sc)
    n.add(-0.58, -0.57, -0.34, 0.58, -0.44, 0.34, plate)
    n.add(-0.16, 0.48, -0.08, 0.16, 0.95, 0.08, bone)
    for s in (-1, 1): n.add(-0.15, -0.1, s * 0.48, 0.15, 0.12, s * 0.66, bone)
    hd = Part("wyrm_head", 7, 128)
    hd.add(-0.25, -0.5, -0.56, 1.2, 0.56, 0.56, sc)              # skull
    hd.add(1.2, -0.42, -0.42, 2.6, 0.28, 0.42, sc)               # snout
    hd.add(2.6, -0.36, -0.33, 3.0, 0.16, 0.33, sc)               # nose
    hd.add(0.45, -0.86, -0.38, 2.75, -0.52, 0.38, sc)            # lower jaw, hanging a little open
    hd.add(0.55, -0.92, -0.3, 2.65, -0.84, 0.3, plate)
    hd.add(1.25, -0.52, -0.34, 2.6, -0.44, 0.34, solid(MOUTH, 4)) # the dark of the mouth
    for i in range(6):
        x = 1.3 + i * 0.24
        for z in (-0.38, 0.3):
            hd.add(x, -0.62, z, x + 0.1, -0.42, z + 0.08, teeth)  # upper fangs
            hd.add(x + 0.12, -0.54, z, x + 0.2, -0.4, z + 0.08, teeth)
    hd.add(0.0, 0.56, -0.07, 1.05, 0.86, 0.07, bone)              # crest
    hd.add(1.3, 0.28, -0.05, 2.2, 0.42, 0.05, bone)               # ridge down the snout
    for s in (-1, 1):
        hd.add(2.78, -0.04, s * 0.1, 2.98, 0.08, s * 0.24, glow(CYAN))           # frosty nostrils
        hd.add(0.7, 0.38, s * 0.26, 1.55, 0.66, s * 0.62, bone)                  # brow ridge
        hd.add(1.12, 0.12, s * 0.53, 1.46, 0.36, s * 0.6, glow(CYAN))            # eye
        hd.add(-0.1, 0.42, s * 0.26, 0.55, 0.8, s * 0.52, bone)                  # horns, swept back
        hd.add(-0.95, 0.62, s * 0.3, -0.1, 0.94, s * 0.5, bone)
        hd.add(-1.65, 0.84, s * 0.34, -0.95, 1.08, s * 0.47, bone)
        hd.add(-2.1, 0.98, s * 0.36, -1.65, 1.16, s * 0.44, glow((200, 240, 255)))
        hd.add(-0.05, -0.5, s * 0.55, 0.7, -0.08, s * 0.68, bone)                # cheek spikes
        hd.add(-0.6, -0.42, s * 0.6, -0.05, -0.16, s * 0.82, bone)
        hd.add(-0.35, -0.45, s * 0.54, 0.35, 0.45, s * 0.6, membrane(WMEM, ICE_D)) # frill
    wl = Part("wyrm_wing_l", 5, 128)
    # the arm along the leading edge
    wl.add(-0.05, 0.0, -2.5, 0.38, 0.34, 0.0, bone)
    wl.add(0.22, 0.03, -5.15, 0.56, 0.3, -2.4, bone)
    wl.add(0.15, -0.02, -5.5, 0.7, 0.36, -5.05, solid((90, 120, 150), 4))      # wrist
    wl.add(0.7, 0.08, -5.35, 1.12, 0.24, -5.2, claw)                          # thumb claw
    tips = [(-1.25, -7.1), (-3.25, -5.95), (-3.45, -3.65)]                   # finger tips (x, z)
    for tx, tz in tips: _stairs(wl, 0.35, -5.2, tx, tz, 0.08, 0.2, 0.07, bone, 8)
    trail = [(0.0, -1.8), (-1.8, -2.3), (-3.65, -3.45), (-4.8, -2.55), (-5.95, -3.25), (-6.5, -2.15), (-7.1, -1.25)]
    lead = [(0.0, 0.25), (-5.2, 0.25), (-7.1, -1.25)]
    z = 0.0
    while z > -7.0:
        zc = z - 0.25
        xl, xt = _lerp_pts(lead, zc), _lerp_pts(trail, zc)
        if xl - xt > 0.15: wl.add(xt, 0.05, z - 0.5, xl, 0.12, z, membrane(WMEM, ICE_D))
        z -= 0.5
    wr = wl.mirror_z("wyrm_wing_r")
    seg = Part("wyrm_tail", 6, 64)
    seg.add(-0.62, -0.45, -0.48, 0.62, 0.45, 0.48, sc)
    seg.add(-0.58, -0.53, -0.32, 0.58, -0.42, 0.32, plate)
    seg.add(-0.18, 0.45, -0.1, 0.18, 0.98, 0.1, bone)
    for s in (-1, 1): seg.add(-0.18, -0.05, s * 0.48, 0.18, 0.13, s * 0.74, bone)
    tip = Part("wyrm_tail_tip", 6, 64)
    tip.add(-0.25, -0.33, -0.34, 0.6, 0.33, 0.34, sc)
    tip.add(-1.7, -0.06, -0.75, -0.2, 0.06, 0.75, bone)          # the ice blade (flat)
    tip.add(-2.1, -0.05, -0.36, -1.7, 0.05, 0.36, bone)
    tip.add(-1.5, -0.62, -0.05, -0.2, 0.62, 0.05, glow((170, 235, 255)))  # and its fin (upright)
    return [b, n, hd, wl, wr, seg, tip]


ROBE, ROBE_D, LBONE, GREEN, GOLD, DMETAL = (62, 30, 84), (40, 18, 56), (222, 216, 198), (90, 255, 130), (214, 168, 60), (52, 46, 62)
# the Lich (1.5.0): a desaturated grave palette instead of the old bright purple and gold
L_CLOTH, L_CLOTH_D, L_INNER = (46, 38, 58), (30, 25, 40), (18, 15, 24)
L_BONE, L_BONE_D, L_BRONZE, L_SOUL = (206, 198, 172), (150, 140, 116), (138, 108, 60), (110, 255, 196)


def cloth(c, trim=None, ragged=True):
    """Heavy old cloth: soft vertical folds (lit ridge, dark crease), darker toward the hem, an optional trim band at
    the bottom edge and a frayed hem."""
    def f(reg, face, rng):
        _fill(reg, c, rng, 4)
        h, w = reg.shape[:2]
        if face in ("up", "down"): return
        phase = rng.random() * 6
        for x in range(w):
            fold = math.sin(x * 1.3 + phase) * 0.5 + 0.5
            for y in range(h):
                dim = 1.0 - 0.28 * (y / max(1, h - 1))            # the hem is in shadow
                reg[y, x, :3] = np.clip(np.array(c) * dim * (0.82 + 0.3 * fold) + rng.uniform(-3, 3), 0, 255)
        if trim and h > 3:
            reg[h - 2, :, :3] = trim
            reg[h - 3, :, :3] = np.clip(np.array(trim) * 0.6, 0, 255)
        if ragged and h > 2:
            for x in range(w):
                cut = rng.choice((0, 0, 1, 1, 2))
                for y in range(cut): reg[h - 1 - y, x, 3] = 0
    return f


def bone(c=L_BONE):
    def f(reg, face, rng):
        _fill(reg, c, rng, 5)
        h, w = reg.shape[:2]
        for y in range(h):                                       # aged: darker toward the bottom, a few hairline cracks
            reg[y, :, :3] = np.clip(reg[y, :, :3] * (1.0 - 0.18 * y / max(1, h - 1)), 0, 255)
        for _ in range(max(1, w * h // 40)):
            x, y = rng.randrange(w), rng.randrange(h)
            for k in range(rng.randrange(1, 4)):
                if 0 <= y + k < h: reg[y + k, min(w - 1, x + (k // 2)), :3] = L_BONE_D
    return f


def skull_face(reg, face, rng):
    """The skull: a bone box whose front (east) face has the sockets, the nose hole and cheekbone shadows painted in."""
    bone()(reg, face, rng)
    if face != "east": return
    h, w = reg.shape[:2]
    dark = (20, 16, 22)
    def px(x, y, c):
        if 0 <= x < w and 0 <= y < h: reg[y, x, :3] = c
    ew, eh = max(2, w // 4), max(2, h // 4)
    ey = int(h * 0.38)
    for cx in (int(w * 0.28), int(w * 0.72)):                # deep round sockets, a little brow ridge over them
        for y in range(ey - eh // 2, ey + eh // 2 + 1):
            for x in range(cx - ew // 2, cx + ew // 2 + 1):
                if (x - cx) ** 2 / max(1, (ew / 2) ** 2) + (y - ey) ** 2 / max(1, (eh / 2) ** 2) <= 1.15: px(x, y, dark)
        for x in range(cx - ew // 2, cx + ew // 2 + 1): px(x, ey - eh // 2 - 1, (236, 230, 206))
    ny = int(h * 0.62)                                       # the nose: an upside-down heart
    px(w // 2, ny, dark); px(w // 2 - 1, ny, dark); px(w // 2, ny + 1, dark)
    for x in range(1, w - 1):                                # cheekbone shadow + the upper teeth line
        px(x, int(h * 0.74), L_BONE_D)
    for x in range(2, w - 2, 2): px(x, h - 2, dark)


def ribs(reg, face, rng):
    """Ribs over the dark inside of the chest."""
    _fill(reg, L_INNER, rng, 3)
    h, w = reg.shape[:2]
    if face != "east": return
    for y in range(1, h - 1, 3):
        for x in range(w):
            if x != w // 2: reg[y, x, :3] = L_BONE if abs(x - w / 2) < w * 0.45 else L_BONE_D
    reg[:, w // 2, :3] = L_BONE_D                             # the sternum


def lich():
    """The Lich (1.5.0): a jointed rig, so it can actually move. A tall, gaunt sorcerer-king in heavy grave-cloth: a deep
    cowl over a cracked skull with two soul-lights for eyes, a thin bent bronze circlet with uneven tines, a stiff high
    collar, an open chest showing the ribs and the soul inside, a long bell-sleeved robe that frays into ragged strips
    (it floats; there are no feet), a torn cape, bony hands with long fingers, and a gnarled crook-staff with a soul
    lantern hanging from the hook. Parts: body (origin = waist), head (origin = neck), arm (origin = shoulder, hangs
    down; used for both), staff (origin = the grip), cape (origin = between the shoulder blades, hangs down)."""
    cl, cd = cloth(L_CLOTH, L_BRONZE), cloth(L_CLOTH_D)
    inner, br, soul = solid(L_INNER, 2), metal(L_BRONZE), glow(L_SOUL)
    body = Part("lich_body", 16, 128)
    # the robe: a long skirt widening to the hem, then ragged strips hanging off it
    body.add(-0.3, -0.75, -0.36, 0.3, 0.0, 0.36, cd)
    body.add(-0.38, -1.4, -0.44, 0.36, -0.7, 0.44, cl)
    body.add(0.3, -1.38, -0.12, 0.39, -0.05, 0.12, inner)                # the robe opens at the front...
    for s in (-1, 1):
        body.add(0.3, -1.38, s * 0.12, 0.4, -0.05, s * 0.2, solid(L_BRONZE, 5))   # ...edged in bronze
    rng = random.Random(7)
    for i in range(14):                                                  # the strips: uneven, around the hem
        a = (i + 0.5) * 2 * math.pi / 14 + rng.uniform(-0.12, 0.12)
        x, z = math.cos(a) * 0.36, math.sin(a) * 0.42
        ln = rng.uniform(0.25, 0.62)
        wd = rng.uniform(0.07, 0.11)
        body.add(x - wd, -1.4 - ln, z - wd, x + wd, -1.36, z + wd, cloth(L_CLOTH_D if i % 2 else L_CLOTH))
    # the waist: a sash with a bronze clasp holding a soul stone, and a tail of it hanging down the front
    body.add(-0.32, -0.1, -0.38, 0.32, 0.08, 0.38, cloth((64, 22, 30), None, False))
    body.add(0.31, -0.12, -0.08, 0.37, 0.1, 0.08, br)
    body.add(0.36, -0.06, -0.035, 0.39, 0.04, 0.035, soul)
    body.add(0.3, -0.75, -0.3, 0.36, -0.1, -0.2, cloth((64, 22, 30)))
    # the chest: narrow, open in front over the ribs, the soul burning inside
    body.add(-0.26, 0.08, -0.34, 0.22, 0.86, 0.34, cd)
    body.add(0.22, 0.28, -0.16, 0.27, 0.74, 0.16, ribs)
    body.add(0.12, 0.42, -0.07, 0.24, 0.58, 0.07, soul)
    for s in (-1, 1):
        body.add(0.2, 0.08, s * 0.16, 0.3, 0.86, s * 0.34, cl)           # the robe's lapels
        body.add(0.28, 0.68, s * 0.1, 0.33, 0.76, s * 0.22, br)          # clasps, and a chain between them
    body.add(0.31, 0.7, -0.1, 0.32, 0.72, 0.1, br)
    # the mantle over the shoulders and the stiff high collar standing up behind the head
    body.add(-0.3, 0.78, -0.56, 0.26, 0.92, 0.56, cl)
    body.add(-0.32, 0.8, -0.4, -0.2, 1.42, 0.4, cd)
    for s in (-1, 1):
        body.add(-0.3, 0.86, s * 0.34, 0.02, 1.32, s * 0.44, cd)
        body.add(-0.3, 1.32, s * 0.3, 0.0, 1.36, s * 0.44, br)
    body.add(-0.33, 1.38, -0.4, -0.19, 1.43, 0.4, br)

    head = Part("lich_head", 32, 128)
    head.add(-0.05, 0.0, -0.05, 0.05, 0.12, 0.05, bone())                # neck bone
    head.add(-0.2, 0.1, -0.18, 0.2, 0.5, 0.18, skull_face)               # the skull
    head.add(0.02, 0.02, -0.13, 0.19, 0.12, 0.13, bone(L_BONE_D))        # its jaw
    for s in (-1, 1):
        head.add(0.2, 0.25, s * 0.05, 0.215, 0.31, s * 0.12, soul)       # soul-lights in the sockets
    # the cowl: deep, so the face sits in its shadow
    head.add(-0.32, 0.04, -0.27, -0.2, 0.64, 0.27, cd)
    for s in (-1, 1): head.add(-0.3, 0.04, s * 0.19, 0.16, 0.6, s * 0.28, cd)
    head.add(-0.3, 0.5, -0.27, 0.12, 0.64, 0.27, cd)
    head.add(0.1, 0.5, -0.24, 0.26, 0.58, 0.24, cloth(L_CLOTH_D, None, False))   # the brim overhanging the face
    head.add(-0.42, 0.52, -0.1, -0.24, 0.74, 0.1, cd)                    # the cowl's peak falls back
    # a thin bent circlet over the cowl with five uneven tines and one soul stone
    for (x0, z0, x1, z1) in ((-0.31, -0.29, 0.27, -0.25), (-0.31, 0.25, 0.27, 0.29), (-0.31, -0.29, -0.27, 0.29), (0.23, -0.29, 0.27, 0.29)):
        head.add(x0, 0.6, z0, x1, 0.65, z1, br)
    for (x, z, ht) in ((0.25, 0.0, 0.26), (0.2, -0.2, 0.17), (0.2, 0.2, 0.2), (0.0, -0.28, 0.12), (0.0, 0.28, 0.1)):
        head.add(x - 0.022, 0.64, z - 0.022, x + 0.022, 0.64 + ht, z + 0.022, br)
    head.add(0.24, 0.66, -0.035, 0.285, 0.73, 0.035, soul)

    arm = Part("lich_arm", 24, 128)
    arm.add(-0.11, -0.52, -0.11, 0.11, 0.06, 0.11, cloth(L_CLOTH, None, False))  # the upper sleeve
    arm.add(-0.19, -0.98, -0.17, 0.17, -0.5, 0.17, cl)                   # the bell sleeve, bronze-hemmed and frayed
    arm.add(-0.13, -0.98, -0.11, 0.11, -0.94, 0.11, inner)
    arm.add(-0.035, -1.06, -0.035, 0.035, -0.9, 0.035, bone())           # the wrist
    arm.add(-0.06, -1.17, -0.075, 0.07, -1.04, 0.075, bone())            # the hand
    for (z, ln) in ((-0.06, 0.22), (-0.02, 0.27), (0.025, 0.25), (0.065, 0.19)):   # long fingers, a little hooked
        arm.add(-0.02, -1.17 - ln * 0.6, z - 0.015, 0.02, -1.17, z + 0.015, bone())
        arm.add(0.0, -1.17 - ln, z - 0.013, 0.04, -1.17 - ln * 0.6, z + 0.013, bone(L_BONE_D))
    arm.add(0.05, -1.16, -0.02, 0.1, -1.05, 0.02, bone())                # the thumb

    staff = Part("lich_staff", 16, 128)
    knot = wood((40, 32, 30), None)
    staff.add(-0.045, -1.75, -0.045, 0.045, 1.3, 0.045, knot)            # the shaft
    for (y, d) in ((-1.2, 0.015), (-0.5, -0.012), (0.55, 0.018)):        # gnarls
        staff.add(-0.06 + d, y, -0.06, 0.06 + d, y + 0.14, 0.06, knot)
    for y in (0.22, 0.95): staff.add(-0.06, y, -0.06, 0.06, y + 0.06, 0.06, br)   # bronze rings
    staff.add(-0.05, 1.26, -0.045, 0.34, 1.36, 0.045, knot)              # the crook bends forward...
    staff.add(0.27, 1.06, -0.045, 0.36, 1.33, 0.045, knot)               # ...and hooks down
    staff.add(0.3, 0.88, -0.008, 0.32, 1.07, 0.008, br)                  # a chain, and the soul lantern on it
    staff.add(0.22, 0.66, -0.09, 0.4, 0.68, 0.09, br)
    staff.add(0.22, 0.86, -0.09, 0.4, 0.88, 0.09, br)
    for (dx, dz) in ((0.22, -0.09), (0.38, -0.09), (0.22, 0.07), (0.38, 0.07)):
        staff.add(dx, 0.68, dz, dx + 0.02, 0.86, dz + 0.02, br)
    staff.add(0.26, 0.7, -0.05, 0.36, 0.84, 0.05, soul)

    cape = Part("lich_cape", 12, 128)
    cape.add(-0.06, -0.8, -0.42, 0.0, 0.0, 0.42, cloth(L_CLOTH_D, None, False))
    cape.add(-0.06, -1.6, -0.5, 0.0, -0.8, 0.5, cloth(L_CLOTH_D, None, False))
    cape.add(0.0, -1.6, -0.46, 0.01, -0.8, 0.46, solid((60, 20, 28), 4))         # a faded red lining on the inside
    rng = random.Random(11)
    for i in range(7):                                                   # torn at the bottom
        z = -0.5 + (i + 0.5) * 1.0 / 7
        ln = rng.uniform(0.15, 0.55)
        cape.add(-0.06, -1.6 - ln, z - 0.06, 0.0, -1.6, z + 0.06, cloth(L_CLOTH_D))
    cape.add(-0.07, -0.06, -0.44, 0.01, 0.02, 0.44, br)
    return [body, head, arm, staff, cape]


STONE, STONE_D, MOSS, AMBER, AMETH = (112, 112, 106), (78, 78, 74), (72, 122, 44), (255, 176, 60), (152, 92, 214)


def golem():
    """The Stone Golem: a massive mossy torso with a cracked chest showing its amber core and amethyst growing from its
    shoulders; a separate head (it turns to look at you) with a heavy brow, amber eyes and a mossy beard; long arms with
    huge fists and crystal-studded forearms; thick legs."""
    st = stone(STONE, MOSS)
    dk = stone(STONE_D)
    b = Part("golem_body", 5, 128)
    b.add(-0.8, 0.0, -1.0, 0.8, 0.6, 1.0, dk)                        # pelvis
    b.add(-1.0, 0.6, -1.3, 1.0, 1.4, 1.3, st)                       # waist
    b.add(-1.2, 1.4, -1.75, 1.2, 2.65, 1.75, st)                    # chest
    b.add(1.2, 1.55, -0.55, 1.32, 2.35, 0.55, dk)                   # the crack...
    b.add(1.25, 1.75, -0.3, 1.36, 2.15, 0.3, glow(AMBER))           # ...and the core in it
    b.add(-1.2, 2.65, -1.75, 1.2, 2.82, 1.75, solid(MOSS, 14))
    for sgn in (-1, 1):
        b.add(-0.9, 2.3, sgn * 1.5, 0.9, 2.95, sgn * 2.05, dk)          # shoulder blocks
        b.add(-0.3, 2.95, sgn * 1.6, 0.3, 3.6, sgn * 1.95, glow(AMETH))  # amethyst
        b.add(-0.65, 2.95, sgn * 1.75, -0.35, 3.3, sgn * 1.98, glow(AMETH))
        b.add(-1.3, 0.8, sgn * 0.9, -1.2, 2.4, sgn * 1.5, solid(MOSS, 14))
    b.add(-1.25, 1.2, -0.5, -1.2, 2.6, 0.5, solid((60, 110, 40), 10))  # vines down the back
    hd = Part("golem_head", 5, 64)
    hd.add(-0.62, 0.0, -0.7, 0.75, 1.15, 0.7, st)
    hd.add(0.62, 0.72, -0.72, 0.95, 0.98, 0.72, dk)                 # brow
    for z0, z1 in ((-0.5, -0.18), (0.18, 0.5)):
        hd.add(0.74, 0.5, z0, 0.8, 0.7, z1, glow(AMBER))            # eyes
    hd.add(0.75, 0.25, -0.1, 0.95, 0.6, 0.1, dk)                    # nose
    hd.add(0.6, -0.35, -0.5, 0.82, 0.25, 0.5, solid((60, 110, 40), 10))  # mossy beard
    hd.add(-0.62, 1.15, -0.7, 0.75, 1.27, 0.7, solid(MOSS, 14))
    hd.add(-0.2, 1.15, 0.35, 0.15, 1.6, 0.6, glow(AMETH))          # a crystal on its head
    arm = Part("golem_arm", 5, 64)
    arm.add(-0.55, -1.6, -0.55, 0.55, 0.0, 0.55, st)               # upper arm
    arm.add(-0.6, -2.8, -0.6, 0.6, -1.6, 0.6, st)                  # forearm
    arm.add(-0.85, -3.75, -0.8, 0.85, -2.8, 0.8, dk)               # the fist
    arm.add(0.85, -3.6, -0.6, 0.98, -3.0, 0.6, stone((92, 92, 88)))   # knuckles
    arm.add(-0.6, -0.15, -0.6, 0.6, 0.12, 0.6, solid(MOSS, 14))
    arm.add(-0.15, -2.5, 0.6, 0.15, -1.9, 0.85, glow(AMETH))        # crystals on the forearm
    arm.add(-0.1, -2.3, -0.82, 0.18, -1.85, -0.6, glow(AMETH))
    leg = Part("golem_leg", 5, 64)
    leg.add(-0.62, -1.1, -0.62, 0.62, 0.0, 0.62, st)
    leg.add(-0.58, -2.0, -0.58, 0.58, -1.1, 0.58, dk)
    leg.add(-0.7, -2.3, -0.7, 1.0, -2.0, 0.7, dk)                  # foot
    leg.add(1.0, -2.3, -0.5, 1.1, -2.15, 0.5, stone((92, 92, 88)))
    return [b, hd, arm, leg]


WOOD, IRON, TONGUE, RED = (150, 100, 52), (70, 70, 76), (210, 80, 112), (255, 50, 40)


def mimic():
    base = Part("mimic_base", 16, 64)
    base.add(-0.45, 0.0, -0.45, 0.45, 0.62, 0.45, wood(WOOD, IRON))
    for i in range(6):
        z = -0.4 + i * 0.15
        base.add(0.33, 0.62, z, 0.43, 0.76, z + 0.08, teeth)
    for s in (-1, 1):
        for i in range(4):
            x = -0.3 + i * 0.18
            base.add(x, 0.62, s * 0.42 - 0.04, x + 0.08, 0.74, s * 0.42 + 0.04, teeth)
    base.add(0.0, 0.5, -0.12, 0.62, 0.6, 0.12, solid(TONGUE, 8))
    lid = Part("mimic_lid", 16, 64)
    lid.add(0.0, 0.0, -0.45, 0.9, 0.3, 0.45, wood(WOOD, IRON))
    lid.add(0.9, -0.06, -0.07, 0.96, 0.14, 0.07, metal(GOLD))
    for i in range(6):
        z = -0.37 + i * 0.15
        lid.add(0.78, -0.14, z, 0.86, 0.0, z + 0.08, teeth)
    for z0, z1 in ((-0.3, -0.12), (0.12, 0.3)):
        lid.add(0.55, 0.3, z0, 0.78, 0.35, z1, glow(RED))
    return [base, lid]


def locust():
    p = Part("locust", 32, 64)
    OL, OLD, WING = (112, 124, 42), (70, 80, 24), (170, 150, 96)
    p.add(-0.3, -0.1, -0.1, 0.25, 0.1, 0.1, stripes_h(OL, OLD, 2))
    p.add(0.25, -0.08, -0.09, 0.4, 0.12, 0.09, solid(OL))
    for s in (-1, 1):
        p.add(0.32, 0.02, s * 0.085 - 0.02, 0.39, 0.1, s * 0.085 + 0.02, solid((40, 14, 10), 2))
        p.add(-0.25, 0.0, s * 0.1 - 0.02, 0.05, 0.2, s * 0.1 + 0.02, solid(OLD))
        p.add(-0.38, -0.16, s * 0.1 - 0.02, -0.2, 0.0, s * 0.1 + 0.02, solid(OLD))
        p.add(0.1, -0.18, s * 0.09 - 0.02, 0.16, -0.08, s * 0.09 + 0.02, solid(OLD))
        p.add(0.38, 0.1, s * 0.04 - 0.01, 0.56, 0.12, s * 0.04 + 0.01, solid(OLD, 2))
    p.add(-0.36, 0.1, -0.17, 0.14, 0.12, 0.17, membrane(WING, (120, 100, 60)))
    return [p]


GHOST, GHOST_D, SOUL = (150, 230, 236), (80, 160, 176), (90, 250, 255)


def ghost_captain():
    p = Part("ghost_captain", 10, 128)
    p.add(-0.42, 0.0, -0.5, 0.42, 1.0, 0.5, robe(GHOST_D))
    p.add(-0.32, 1.0, -0.42, 0.32, 1.65, 0.42, robe(GHOST))
    p.add(0.32, 1.1, -0.38, 0.38, 1.6, 0.38, stripes_h((200, 250, 255), GHOST_D, 3))
    for s in (-1, 1):
        p.add(-0.12, 0.9, s * 0.42, 0.12, 1.62, s * 0.62, robe(GHOST))
    p.add(0.12, 1.0, 0.44, 0.9, 1.1, 0.52, metal((210, 230, 236)))          # cutlass
    p.add(-0.27, 1.65, -0.27, 0.27, 2.15, 0.27, solid(GHOST, 4))
    for z0, z1 in ((-0.16, -0.05), (0.05, 0.16)):
        p.add(0.26, 1.88, z0, 0.3, 1.98, z1, glow(SOUL))
    p.add(-0.48, 2.1, -0.48, 0.48, 2.2, 0.48, solid((30, 40, 50), 4))     # tricorn
    p.add(-0.32, 2.2, -0.32, 0.32, 2.42, 0.32, solid((30, 40, 50), 4))
    p.add(0.4, 2.12, -0.5, 0.52, 2.32, 0.5, solid((30, 40, 50), 4))
    p.add(0.2, 2.24, -0.06, 0.34, 2.36, 0.06, solid((230, 240, 240), 4))
    for k in range(8):
        a = k * math.pi / 4
        x, z = math.cos(a) * 0.38, math.sin(a) * 0.38
        p.add(x - 0.08, -0.4, z - 0.08, x + 0.08, 0.0, z + 0.08, robe(GHOST_D))
    return [p]


def aurora():
    p = Part("aurora", 4, 64, java_only=True)
    cols = [(60, 255, 150), (80, 240, 200), (120, 160, 255), (190, 110, 255)]
    for i, c in enumerate(cols):
        p.add(-12 + i * 0.2, i * 1.2, -0.05, 12 - i * 0.2, i * 1.2 + 1.3, 0.05, glow(c))
    return [p]


def black_sun():
    """The eclipse: a black disc with a burning corona, lying flat (seen from below, straight up)."""
    p = Part("black_sun", 4, 64, java_only=True)
    R, C = 8.0, 10.5
    core, ring, rim = solid((4, 4, 8), 2), glow((255, 236, 190)), glow((255, 150, 40))
    for z in range(-int(C), int(C)):
        zc = z + 0.5
        hw_c = math.sqrt(max(0.0, C * C - zc * zc))
        hw_r = math.sqrt(max(0.0, R * R - zc * zc))
        if hw_r > 0.3:
            p.add(-hw_r, 0.0, z, hw_r, 0.25, z + 1, core)               # the dark disc (nearest the viewer below)
        if hw_c > 0.3:
            inner = hw_r if hw_r > 0.3 else 0.0
            if inner > 0:
                p.add(-hw_c, 0.2, z, -inner + 0.4, 0.3, z + 1, ring)    # the corona on each side
                p.add(inner - 0.4, 0.2, z, hw_c, 0.3, z + 1, ring)
            else:
                p.add(-hw_c, 0.2, z, hw_c, 0.3, z + 1, ring)
    for (x0, z0, x1, z1) in ((-0.6, C - 0.5, 0.6, C + 3.5), (-0.6, -C - 3.5, 0.6, -C + 0.5),
                             (C - 0.5, -0.6, C + 3.5, 0.6), (-C - 3.5, -0.6, -C + 0.5, 0.6)):
        p.add(x0, 0.22, z0, x1, 0.28, z1, rim)                           # four flares
    return [p]


ALL_PARTS = leviathan() + sandworm() + frost_wyrm() + lich() + golem() + mimic() + locust() + ghost_captain() + aurora() + black_sun()

# =====================================================================================================================
#  16x16 pixel art (flat items + Index icons)
# =====================================================================================================================

PAL = {
    "#": (20, 14, 12), "k": (45, 34, 30), "e": (255, 255, 255), "b": (24, 24, 24),
    "T": TEAL, "t": TEAL_D, "f": FIN, "Y": YEL, "y": (255, 244, 170), "B": BELLY,
    "S": SAND, "s": SAND_D, "m": MAW, "w": (236, 228, 206),
    "I": ICE, "i": ICE_D, "F": FROST, "C": CYAN,
    "R": ROBE, "r": ROBE_D, "L": LBONE, "G": GREEN, "g": (40, 150, 70), "O": GOLD, "o": (150, 110, 30),
    "N": STONE, "n": STONE_D, "M": MOSS, "A": AMBER, "a": AMETH,
    "W": WOOD, "d": (96, 62, 30), "x": IRON, "X": (130, 130, 140), "p": TONGUE, "Q": RED,
    "V": (112, 124, 42), "v": (70, 80, 24), "U": (170, 150, 96),
    "H": GHOST, "h": GHOST_D, "Z": SOUL,
    "K": (180, 184, 196), "c": (110, 114, 126), "P": (200, 30, 36),
    "D": (60, 40, 30), "E": (230, 190, 40), "l": (255, 250, 200),
    "1": (24, 24, 28), "2": (52, 52, 60), "3": (90, 90, 100),
    "4": L_CLOTH, "5": L_CLOTH_D, "6": L_BRONZE, "7": L_SOUL, "8": L_BONE_D, "9": (64, 22, 30),
}

ICONS = {
    "leviathan": [
        "................",
        "..........fff...",
        "........TTTTTf..",
        ".......TTYTTTTT.",
        "..f...TTTTTTmww.",
        ".fTf..TTTBBmmw..",
        ".TTTf..TTBBBB...",
        "..TTTT..TTTT....",
        "...TTTTTTTTT....",
        "....TtTTTTt.....",
        ".....TTTTT......",
        "......BBB.......",
        "................",
        "...f.f.f.f.f....",
        "..ffffffffffff..",
        "................",
    ],
    "sandworm_king": [
        "......ww.ww.....",
        ".....wSSSSSSw...",
        "....SSmmmmmSS...",
        "...wSmm###mmSw..",
        "...SSm#####mSS..",
        "...wSm#####mSw..",
        "...SSmm###mmSS..",
        "....SSmmmmmSS...",
        "....sSSSSSSSs...",
        ".....SSSSSSS....",
        ".....sssssss....",
        "......SSSSS.....",
        "......sssss.....",
        ".......SSS......",
        ".......sss......",
        "........S.......",
    ],
    "lich": [
        "....6.6.6.......",
        "...6666666..555.",
        "...5555555..5.5.",
        "..55LLLLL55...5.",
        "..5L7LLL7L5..666",
        "..55L8L8L55..676",
        "...55LLL55...666",
        "..4455555544..5.",
        ".445.L8L8.544L5.",
        ".L45.L777.5448..",
        "..45.6996.54.5..",
        "...4559554...5..",
        "...45595554..5..",
        "..4555955554.5..",
        "..4.5.4.5.4..5..",
        "................",
    ],
    "frost_wyrm": [
        "................",
        "FF..........FF..",
        ".FiI......IiF...",
        "..IIi....iII....",
        "..IIIi..iIII....",
        "...IIIiiIII..F..",
        "....IIIIII..IIC.",
        ".....IIIIIIIIII.",
        "....IIIIII...wI.",
        "...IIFFII.......",
        "..II.FF.........",
        ".II.............",
        "II..............",
        "F...............",
        "................",
        "................",
    ],
    "stone_golem": [
        "................",
        "......NNNN......",
        ".....NNnNNN.....",
        ".....NANNAN.....",
        "...a.NNNNNN.....",
        ".MMMMMMMMMMMM...",
        ".NNNNNNNNNNNNa..",
        ".NN.NNNAANN.NN..",
        ".NN.NNNNNNN.NN..",
        ".NN.NNNNNNN.NN..",
        ".nn.NNNNNNN.nn..",
        ".nn..NNNNN..nn..",
        ".....NN.NN......",
        ".....NN.NN......",
        "....nnn.nnn.....",
        "................",
    ],
    "mimic": [
        "................",
        "....WWWWWWW.....",
        "...WdQWWWQdW....",
        "...WWWWOWWWW....",
        "...w.w.w.w.w....",
        "................",
        "...w.w.w.w.w....",
        "...WppppppWW....",
        "...WWWWpWWWW....",
        "...xWWWWWWWx....",
        "...WWWWWWWWW....",
        "...xWWWWWWWx....",
        "...WWWWWWWWW....",
        "................",
        "................",
        "................",
    ],
    "locust": [
        "................",
        "................",
        "................",
        "........v...v...",
        ".........v.v....",
        "...UUUUUUVVv....",
        "..UUUUUUVVVVQ...",
        "..VVVVVVVVVVV...",
        "...vvVvvVVV.....",
        "....v..v.v......",
        "...v..v...v.....",
        "..v...v.........",
        "................",
        "................",
        "................",
        "................",
    ],
    "skeleton_knight": [
        "......PPP.......",
        ".....PPP........",
        "....KKKKKK......",
        "....KKKKKK......",
        "....K1111K......",
        "....KKOKKK......",
        "....KKOKKK......",
        "...cKKKKKKc.....",
        "..cKKKLLKKKc..X.",
        "..K.KLLLLK.K.X..",
        "..L.KKKKKK.LX...",
        "....KKKKKK..L...",
        "....LL..LL......",
        "....LL..LL......",
        "....cc..cc......",
        "................",
    ],
    "ghost_ship": [
        "........H.......",
        "......HHHH......",
        ".....HhHHHH.....",
        "....HHHHHHH.Z...",
        "...HHhHHHHHH....",
        "...HHHHHHHHH....",
        ".......h........",
        ".hhhhhhhhhhhhh..",
        "..hHHHHHHHHHh...",
        "...hhZhhZhhh....",
        "....hhhhhhh.....",
        "................",
        ".h..h...h..h....",
        "..hh.hhh.hh.....",
        "................",
        "................",
    ],
    "ghost_captain": [
        ".....1111111....",
        "....111111111...",
        "......HHHH......",
        "......HZHZ......",
        "......HHHH......",
        ".....hHHHHh.....",
        "....HhHHHHhH....",
        "....H.HHHH.HK...",
        "....H.hHHh.HK...",
        "......HHHH..K...",
        ".....hHHHHh.....",
        "....hHHHHHHh....",
        "...hH.hH.hH.h...",
        "...h..h..h..h...",
        "................",
        "................",
    ],
    "revenant": [
        "................",
        ".....kkkkkk.....",
        ".....kGkkGk.....",
        ".....kkkkkk.....",
        ".....kk##kk.....",
        "....tTTTTTTt....",
        "...tTTTTTTTTt...",
        "...gTtTTTTtTg...",
        "...g.TTTTTT.g...",
        ".....TTTTTT.....",
        ".....xxxxxx.....",
        ".....xx..xx.....",
        ".....xx..xx.....",
        ".....##..##.....",
        "................",
        "................",
    ],
    "mining_helmet": [
        "................",
        "................",
        "......EEEE......",
        "....EEEEEEEE....",
        "...EEE2222EEE...",
        "...EE2llll2EE...",
        "..EEE2llll2EEE..",
        "..EEEE2222EEEE..",
        "..EEEEEEEEEEEE..",
        ".EEEEEEEEEEEEEE.",
        ".oooooooooooooo.",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    "knight_helm": [
        "......PP........",
        ".....PPPP.......",
        "....PP..........",
        "....KKKKKKK.....",
        "...KKKKKKKKK....",
        "...KKKKOKKKK....",
        "...K111O111K....",
        "...KKKKOKKKK....",
        "...KKKKOKKKK....",
        "...KK1KOK1KK....",
        "...KKKKKKKKK....",
        "...cKKKKKKKc....",
        "....ccccccc.....",
        "................",
        "................",
        "................",
    ],
    "lantern_of_souls": [
        "......xxxx......",
        "......x..x......",
        ".......xx.......",
        ".....xxxxxx.....",
        "....x222222x....",
        "....x2ZZZZ2x....",
        "....x2ZllZ2x....",
        "....xZlZZlZx....",
        "....x2ZllZ2x....",
        "....x2ZZZZ2x....",
        "....x222222x....",
        ".....xxxxxx.....",
        "................",
        "................",
        "................",
        "................",
    ],
    "museum_pedestal": [
        "................",
        "......OyO.......",
        ".....OOyOO......",
        "......OOO.......",
        "................",
        "....ooooooo.....",
        ".....33333......",
        "......222.......",
        "......232.......",
        "......222.......",
        "......232.......",
        "......222.......",
        ".....33333......",
        "....2222222.....",
        "...111111111....",
        "................",
    ],
    "ancient_gear_part": [
        "................",
        "......nNNn......",
        "...n..NNNN..n...",
        "..nNNNNNNNNNNn..",
        "...NNNnnnnNNN...",
        "...NNn....nNN...",
        ".nNNn..AA..nNNn.",
        ".NNNn.AAAA.nNNN.",
        ".NNNn.AAAA.nNNN.",
        ".nNNn..AA..nNNn.",
        "...NNn....nNN...",
        "...NNNnnnnNNN...",
        "..nNNNNNNNNNNn..",
        "...n..NNNN..n...",
        "......nNNn......",
        "................",
    ],
    "ancient_core": [
        "................",
        "......aaaa......",
        "....aaNNNNaa....",
        "...aNNnnnnNNa...",
        "..aNnnAAAAnnNa..",
        "..aNnAAyyAAnNa..",
        ".aNnAAyyyyAAnNa.",
        ".aNnAyyeeyyAnNa.",
        ".aNnAyyeeyyAnNa.",
        ".aNnAAyyyyAAnNa.",
        "..aNnAAyyAAnNa..",
        "..aNnnAAAAnnNa..",
        "...aNNnnnnNNa...",
        "....aaNNNNaa....",
        "......aaaa......",
        "................",
    ],
    "tidebreaker": [
        "...........f.f.f",
        "...........fTfTf",
        "............TTT.",
        "...........TTf..",
        "..........TT....",
        ".........TT.....",
        "........TT......",
        ".......TT.......",
        "......TT........",
        ".....TT.........",
        "....TT..........",
        "...BB...........",
        "..BB............",
        ".TT.............",
        "TT..............",
        "................",
    ],
    "sandworm_fang": [
        "...............w",
        "..............ww",
        ".............wwS",
        "............wwS.",
        "...........wwS..",
        "..........wwS...",
        ".........wwS....",
        "........wwS.....",
        ".......wwS......",
        "...s..wwS.......",
        "....sSwS........",
        ".....sS.........",
        "....sDDs........",
        "...DDs..........",
        "..DD............",
        "..D.............",
    ],
    "lich_staff": [
        "...........L.L..",
        "..........LGGL..",
        "..........GGGG..",
        "...........GG...",
        "..........LDL...",
        ".........DD.....",
        "........DD......",
        ".......DD.......",
        "......DD........",
        ".....DD.........",
        "....OD..........",
        "...DD...........",
        "..DD............",
        ".DD.............",
        "DD..............",
        "................",
    ],
    "glacial_fang": [
        "..............FF",
        ".............FCF",
        "............FCI.",
        "...........FCI..",
        "..........FCI...",
        ".........FCI....",
        "........FCI.....",
        ".......FCI......",
        "...ii.FCI.......",
        "....iiCI........",
        ".....iI.........",
        "....iDDi........",
        "...DDi..........",
        "..DD............",
        ".DD.............",
        "................",
    ],
    "abyssal_lure": [
        "................",
        "......xxxx......",
        ".....x....x.....",
        ".....x..........",
        ".....x....ff....",
        ".....x...fTTf...",
        ".....x..fTYTTf..",
        "......xfTTTTTf..",
        "......fTTBBTTTf.",
        "......fTBBBBTf..",
        ".......fTTTTf...",
        "........ffff....",
        "......f.........",
        ".....fff........",
        "................",
        "................",
    ],
    "sandworm_drum": [
        "................",
        "....SSSSSSSS....",
        "...SwwwwwwwwS...",
        "...SwwwwwwwwS...",
        "...dSSSSSSSSd...",
        "...dmsmsmsmsd...",
        "...dsmsmsmsmd...",
        "...dmsmsmsmsd...",
        "...dsmsmsmsmd...",
        "...dSSSSSSSSd...",
        "....dddddddd....",
        "................",
        "..L.........L...",
        "...L.......L....",
        "....L.....L.....",
        "................",
    ],
    "lich_phylactery": [
        "................",
        ".......OO.......",
        "......OOOO......",
        "......rOOr......",
        ".....rRRRRr.....",
        "....rRGGGGRr....",
        "....RGGllGGR....",
        "....RGlGGlGR....",
        "....RGGllGGR....",
        "....rRGGGGRr....",
        ".....rRRRRr.....",
        "......OOOO......",
        ".....OOOOOO.....",
        "................",
        "................",
        "................",
    ],
    "frozen_horn": [
        "................",
        "...........FFF..",
        "..........FIIF..",
        ".........FII.F..",
        "........FIIF....",
        ".......FIIIF....",
        "......FIIIF.....",
        ".....FIIIIF.....",
        "....FIIIIiF.....",
        "...FCIIIiF......",
        "..FCCIIiF.......",
        "..FCCCiF........",
        "..FCCiF.........",
        "...FFF..........",
        "................",
        "................",
    ],
}

TROPHY_BOSSES = ["demon_eye", "mortimer_freeze", "dune_devourer", "frostmaw", "don_lorenzo", "kraken", "diamond_jacob",
                 "rocco_vendetta", "grimtusk", "lost_explorer", "queen_spider", "leviathan", "sandworm_king", "lich", "frost_wyrm",
                 "stone_golem", "ghost_captain"]

DISCS = {  # id -> (title, label colour, sound event)
    "demon_eye": ("It Sees Everything", (200, 40, 50)), "dune": ("The Dune Devourer", (220, 180, 90)),
    "don": ("Don Lorenzo", (150, 120, 60)), "kraken": ("The Kraken", (40, 150, 170)),
    "jacob1": ("Diamond Jacob I", (110, 220, 230)), "jacob2": ("Diamond Jacob II", (255, 140, 40)),
    "rocco": ("Rocco Vendetta", (210, 170, 50)), "explorer": ("The Lost Explorer", (40, 40, 48)),
    "pirates": ("The Pirate Invasion", (150, 230, 240)),
}


def draw(grid):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(grid):
        for x, ch in enumerate(row[:16]):
            if ch in PAL: im.putpixel((x, y), PAL[ch] + (255,))
    return im


def disc_icon(label):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, y - 7.5)
            if d > 7.3: continue
            c = (28, 28, 32) if d > 3.2 else label if d > 1.0 else (14, 14, 16)
            if 3.2 < d < 7.3 and int(d * 2) % 3 == 0: c = (44, 44, 52)
            if d > 6.6: c = (18, 18, 20)
            if 4 < d < 6 and 2 < x < 7 and 2 < y < 7: c = (90, 90, 104)   # shine
            im.putpixel((x, y), c + (255,))
    return im


# =====================================================================================================================
#  worn items (head pixels, front -z, like the cosmetic hats)
# =====================================================================================================================

def worn_items():
    from jacob_models import Atlas as A2
    rng = random.Random(77)
    steel = dict(base=(176, 180, 192), light=(220, 224, 234), dark=(110, 114, 126), hi=(250, 250, 255))
    def st(reg, face, wu, hu): plate(reg, steel, rng, sparkle=0.03)
    def blk(reg, face, wu, hu): noise_fill(reg, (20, 20, 24), rng, 3)
    def red(reg, face, wu, hu): noise_fill(reg, (190, 30, 36), rng, 8)
    def gold(reg, face, wu, hu): noise_fill(reg, (214, 168, 60), rng, 6)
    a1 = A2(128, 2)
    helm = [head_box(a1, (-4.6, -4.4, -4.6), (4.6, 4.6, 4.6), st),
            head_box(a1, (-3.6, 0.4, -4.8), (3.6, 1.2, -4.6), blk),
            head_box(a1, (-0.5, -3.6, -4.8), (0.5, 0.4, -4.6), gold),
            head_box(a1, (-2.6, -2.6, -4.8), (-1.4, -1.8, -4.6), blk),
            head_box(a1, (1.4, -2.6, -4.8), (2.6, -1.8, -4.6), blk),
            head_box(a1, (-0.7, 4.6, -2.6), (0.7, 7.4, 3.4), red),
            head_box(a1, (-0.6, 5.4, 3.4), (0.6, 6.8, 6.8), red)]
    def yel(reg, face, wu, hu): noise_fill(reg, (232, 190, 40), rng, 7)
    def dark(reg, face, wu, hu): noise_fill(reg, (52, 52, 60), rng, 4)
    def lens(reg, face, wu, hu): noise_fill(reg, (255, 250, 200), rng, 2)
    a2 = A2(128, 2)
    hat = [head_box(a2, (-4.7, 1.2, -4.7), (4.7, 4.9, 4.7), yel),
           head_box(a2, (-5.3, 1.0, -5.7), (5.3, 1.6, 5.3), yel),
           head_box(a2, (-0.6, 4.9, -4.0), (0.6, 5.5, 4.0), yel),
           head_box(a2, (-1.5, 1.8, -5.8), (1.5, 4.2, -4.7), dark),
           head_box(a2, (-1.0, 2.2, -6.0), (1.0, 3.8, -5.8), lens)]
    return {"knight_helm": (a1, helm), "mining_helmet": (a2, hat)}


# =====================================================================================================================
#  iso preview
# =====================================================================================================================

def preview(parts_built, out):
    tiles = []
    for part, img, boxes in parts_built:
        lo, hi = part.bounds()
        ext = max(hi[i] - lo[i] for i in range(3))
        S = 150 / max(ext, 0.5)
        W = 260
        canvas = Image.new("RGBA", (W, W), (46, 52, 64, 255))
        px = canvas.load()
        tex = np.array(img)
        zbuf = np.full((W, W), -1e9)
        def proj(x, y, z):
            # view from front-right-top: screen x = right, screen y = down
            sx = (x * 0.7 + z * 0.7) * S + W / 2
            sy = (-y + (x * 0.35 - z * 0.35)) * S * 1.0 + W / 2 + (lo[1] + hi[1]) / 2 * S
            depth = x * 0.5 - z * 0.5 + y * 0.3
            return sx, sy, depth
        for (a, b), regs in boxes:
            for key, (rx, ry, rw, rh) in regs.items():
                if key in ("-x", "+z", "-y"): continue   # back faces (camera sits front / left / above)
                avg = tex[ry:ry + rh, rx:rx + rw].reshape(-1, 4)
                avg = avg[avg[:, 3] > 0]
                if len(avg) == 0: continue
                k = {"+x": 0.95, "-z": 0.75, "+y": 1.15}.get(key, 1)
                n = 6
                for i in range(n):
                    for j in range(n):
                        u, v = (i + 0.5) / n, (j + 0.5) / n
                        if key == "+x": p = (b[0], a[1] + (b[1] - a[1]) * v, a[2] + (b[2] - a[2]) * u)
                        elif key == "-z": p = (a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * v, a[2])
                        else: p = (a[0] + (b[0] - a[0]) * u, b[1], a[2] + (b[2] - a[2]) * v)
                        sx, sy, d = proj(*p)
                        c = tex[ry + min(rh - 1, int(v * rh)), rx + min(rw - 1, int(u * rw))]
                        if c[3] == 0: continue
                        col = tuple(int(min(255, c[q] * k)) for q in range(3))
                        r = max(1, int(S * max(b[0] - a[0], b[1] - a[1], b[2] - a[2]) / n / 1.2) + 1)
                        for yy in range(int(sy) - r, int(sy) + r):
                            for xx in range(int(sx) - r, int(sx) + r):
                                if 0 <= xx < W and 0 <= yy < W and d >= zbuf[yy, xx]:
                                    zbuf[yy, xx] = d; px[xx, yy] = col + (255,)
        tiles.append((part.name, canvas))
    cols = 6
    rows = (len(tiles) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * 264, rows * 264), (30, 30, 36, 255))
    for i, (n, t) in enumerate(tiles):
        sheet.alpha_composite(t, ((i % cols) * 264 + 2, (i // cols) * 264 + 2))
    sheet.save(out)


# =====================================================================================================================
#  main
# =====================================================================================================================

def jw(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f: json.dump(obj, f, indent=1)


def flat_item(A, key, img, parent="minecraft:item/generated", folder=""):
    sub = (folder + "/") if folder else ""
    p = os.path.join(A, "textures/item", sub + key + ".png"); os.makedirs(os.path.dirname(p), exist_ok=True); img.save(p)
    jw(os.path.join(A, "models/item", sub + key + ".json"), {"parent": parent, "textures": {"layer0": f"{NS}:item/{sub}{key}"}})
    jw(os.path.join(A, "items", sub + key + ".json"), {"model": {"type": "minecraft:model", "model": f"{NS}:item/{sub}{key}"}})


HANDHELD = {"tidebreaker", "sandworm_fang", "lich_staff", "glacial_fang"}
FLAT = ["lantern_of_souls", "museum_pedestal", "ancient_gear_part", "ancient_core", "tidebreaker", "sandworm_fang",
        "lich_staff", "glacial_fang", "abyssal_lure", "sandworm_drum", "lich_phylactery", "frozen_horn"]


def main():
    pack, bout = sys.argv[1], sys.argv[2]
    prev = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    A = os.path.join(pack, "assets", NS)
    rp = os.path.join(bout, "rp_wild")
    if os.path.isdir(rp):
        import shutil; shutil.rmtree(rp)
    mappings = {"minecraft:paper": []}
    item_tex = {}

    def bed_icon(key, img):
        p = os.path.join(rp, "textures/items/faultline", key + ".png"); os.makedirs(os.path.dirname(p), exist_ok=True); img.save(p)
        item_tex[f"faultline.{key}"] = {"textures": f"textures/items/faultline/{key}"}
        return f"faultline.{key}"

    icons = {k: draw(v) for k, v in ICONS.items()}
    # ---- 3D parts
    built, meta = [], {}
    icon_for = {"lev": "leviathan", "worm": "sandworm_king", "wyrm": "frost_wyrm", "lich": "lich", "golem": "stone_golem",
                "mimic": "mimic", "locust": "locust", "ghost": "ghost_captain", "aurora": "ghost_ship"}
    for i, part in enumerate(ALL_PARTS):
        img, boxes = build(part, 1000 + i)
        built.append((part, img, boxes))
        model, S, off = java_model(part, boxes)
        meta[part.name] = (S, off)
        p = os.path.join(A, "textures/item/wild", part.name + ".png"); os.makedirs(os.path.dirname(p), exist_ok=True); img.save(p)
        jw(os.path.join(A, "models/item/wild", part.name + ".json"), model)
        jw(os.path.join(A, "items/wild", part.name + ".json"), {"model": {"type": "minecraft:model", "model": f"{NS}:item/wild/{part.name}"}})
        if part.java_only: continue
        t = os.path.join(rp, "textures/faultline/wild", part.name + ".png"); os.makedirs(os.path.dirname(t), exist_ok=True); img.save(t)
        jw(os.path.join(rp, "models/entity/faultline", f"wild_{part.name}.geo.json"), bedrock_geo(part, boxes))
        jw(os.path.join(rp, "attachables", f"faultline.wild_{part.name}.json"), {"format_version": "1.10.0", "minecraft:attachable": {"description": {
            "identifier": f"faultline:wild_{part.name}", "materials": {"default": "armor", "enchanted": "armor_enchanted"},
            "textures": {"default": f"textures/faultline/wild/{part.name}", "enchanted": "textures/misc/enchanted_actor_glint"},
            "geometry": {"default": f"geometry.faultline.wild_{part.name}"}, "scripts": {"parent_setup": "v.helmet_layer_visible = 0.0;"},
            "render_controllers": ["controller.render.armor"]}}})
        ik = icon_for[part.name.split("_")[0]]
        mappings["minecraft:paper"].append({"type": "definition", "model": f"{NS}:wild/{part.name}", "bedrock_identifier": f"faultline:wild_{part.name}",
            "display_name": part.name, "bedrock_options": {"icon": bed_icon(ik, icons[ik]), "allow_offhand": False},
            "components": {"minecraft:equippable": {"slot": "head"}, "minecraft:max_stack_size": 1}})
    # ---- worn items: Java item model (3D on the head, flat in the inventory) + Bedrock helmet attachable
    for key, (atlas, el) in worn_items().items():
        tex = Image.fromarray(np.clip(atlas.img, 0, 255).astype(np.uint8), "RGBA")
        tex.save(os.path.join(A, "textures/item/wild", key + "_worn.png"))
        jw(os.path.join(A, "models/item/wild", key + "_worn.json"), {"textures": {"t": f"{NS}:item/wild/{key}_worn", "particle": f"{NS}:item/wild/{key}_worn"},
                                                                     "elements": el, "display": {"head": {"scale": [1.6, 1.6, 1.6]}}})
        flat = icons[key]
        fp = os.path.join(A, "textures/item", key + ".png"); flat.save(fp)
        jw(os.path.join(A, "models/item", key + ".json"), {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/{key}"}})
        jw(os.path.join(A, "items", key + ".json"), {"model": {"type": "minecraft:select", "property": "minecraft:display_context",
            "cases": [{"when": "head", "model": {"type": "minecraft:model", "model": f"{NS}:item/wild/{key}_worn"}}],
            "fallback": {"type": "minecraft:model", "model": f"{NS}:item/{key}"}}})
        tex.save(os.path.join(rp, "textures/faultline/wild", key + ".png"))
        jw(os.path.join(rp, "models/entity/faultline", f"wild_{key}.geo.json"), to_bedrock_head_geo(f"geometry.faultline.wild_{key}", el, atlas.size, atlas.size))
        jw(os.path.join(rp, "attachables", f"faultline.wild_{key}.json"), {"format_version": "1.10.0", "minecraft:attachable": {"description": {
            "identifier": f"faultline:{key}", "materials": {"default": "armor", "enchanted": "armor_enchanted"},
            "textures": {"default": f"textures/faultline/wild/{key}", "enchanted": "textures/misc/enchanted_actor_glint"},
            "geometry": {"default": f"geometry.faultline.wild_{key}"}, "scripts": {"parent_setup": "v.helmet_layer_visible = 0.0;"},
            "render_controllers": ["controller.render.armor"]}}})
        mappings["minecraft:paper"].append({"type": "definition", "model": f"{NS}:{key}", "bedrock_identifier": f"faultline:{key}",
            "display_name": {"knight_helm": "Knight's Helm", "mining_helmet": "Mining Helmet"}[key],
            "bedrock_options": {"icon": bed_icon(key, flat), "allow_offhand": False},
            "components": {"minecraft:equippable": {"slot": "head"}, "minecraft:max_stack_size": 1}})
    # ---- flat items (bedrock_pack.py maps these from the plugins' source; their icon is the same 16x16 art)
    for key in FLAT:
        flat_item(A, key, icons[key], "minecraft:item/handheld" if key in HANDHELD else "minecraft:item/generated")
    # ---- music discs + trophies: model names the source can't spell out, so they're mapped here
    for d, (title, colr) in DISCS.items():
        img = disc_icon(colr)
        icons["disc_" + d] = img
        flat_item(A, "disc_" + d, img, folder="disc")
        mappings["minecraft:paper"].append({"type": "definition", "model": f"{NS}:disc/disc_{d}", "bedrock_identifier": f"faultline:disc_{d}",
            "display_name": "Music Disc", "bedrock_options": {"icon": bed_icon("disc_" + d, img), "allow_offhand": True},
            "components": {"minecraft:max_stack_size": 1}})
    have_index = set(os.path.splitext(f)[0] for f in os.listdir(os.path.join(A, "textures/index")))
    for t in TROPHY_BOSSES:
        src = icons.get(t) or (Image.open(os.path.join(A, "textures/index", t + ".png")).convert("RGBA") if t in have_index else None)
        if src is None: print("  no icon for trophy", t); continue
        img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        base = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        for x in range(3, 13): base.putpixel((x, 15), (150, 110, 30, 255)); base.putpixel((x, 14), (214, 168, 60, 255))
        for x in range(5, 11): base.putpixel((x, 13), (214, 168, 60, 255))
        small = src.resize((12, 12), Image.NEAREST)
        img.alpha_composite(base); img.alpha_composite(small, (2, 1))
        flat_item(A, "trophy_" + t, img, folder="trophy")
        icons["trophy_" + t] = img
        mappings["minecraft:paper"].append({"type": "definition", "model": f"{NS}:trophy/trophy_{t}", "bedrock_identifier": f"faultline:trophy_{t}",
            "display_name": "Boss Trophy", "bedrock_options": {"icon": bed_icon("trophy_" + t, img), "allow_offhand": True},
            "components": {"minecraft:max_stack_size": 1}})
    # ---- Index icons + glyphs + font providers
    index_icons = {k: v for k, v in icons.items() if not k.startswith("trophy_")}
    for k, im in index_icons.items(): im.save(os.path.join(A, "textures/index", k + ".png"))
    glyphs(A, index_icons)
    # ---- Bedrock pack
    jw(os.path.join(rp, "textures/item_texture.json"), {"resource_pack_name": "faultline_wild", "texture_name": "atlas.items", "texture_data": item_tex})
    jw(os.path.join(rp, "manifest.json"), {"format_version": 2, "header": {
        "name": "Faultline SMP Wild", "description": "Bosses, creatures and worn items for Bedrock players (Geyser)",
        "uuid": "8d4e2a71-5c3b-4f19-a6e0-2b7d9c1f4e83", "version": [1, 0, 0], "min_engine_version": [1, 21, 0]},
        "modules": [{"description": "Wild", "type": "resources", "uuid": "3f9a6c25-1e8d-4b70-9c42-7a5e0d3b6f18", "version": [1, 0, 0]}]})
    with zipfile.ZipFile(os.path.join(bout, "FaultlineWild.mcpack"), "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(rp):
            for fn in sorted(files):
                full = os.path.join(root, fn); z.write(full, os.path.relpath(full, rp))
    from mcpack_version import stamp; stamp(os.path.join(bout, "FaultlineWild.mcpack"))
    jw(os.path.join(bout, "faultline_wild_mappings.json"), {"format_version": 2, "items": mappings})
    # ---- WildParts.java (display scale + offset per part) for the plugins that draw them
    lines = []
    for n, (S, off) in meta.items():
        lines.append(f'        P.put("{n}", new double[]{{{S:.5f}, {off[0]:.5f}, {off[1]:.5f}, {off[2]:.5f}}});')
    for pkg, plug in (("net.faultlinesmp.bosses", "FaultlineBosses"), ("net.faultlinesmp.items", "FaultlineItems"), ("net.faultlinesmp.ships", "FaultlineShips")):
        path = os.path.join(ROOT, "plugins", plug, "src/main/java", pkg.replace(".", "/"), "WildParts.java")
        with open(path, "w") as f:
            f.write(f"package {pkg};\n\n" + WILD_JAVA.replace("%LINES%", "\n".join(lines)))
    print(f"{len(ALL_PARTS)} parts, {len(FLAT)} flat items, {len(DISCS)} discs, {len(TROPHY_BOSSES)} trophies")
    if prev:
        os.makedirs(prev, exist_ok=True)
        preview(built, os.path.join(prev, "wild_models.png"))
        ic = list(index_icons.items()) + [(k, v) for k, v in icons.items() if k.startswith("trophy_")]
        sheet = Image.new("RGBA", (12 * 68, ((len(ic) + 11) // 12) * 68), (200, 180, 140, 255))
        for i, (k, im) in enumerate(ic): sheet.alpha_composite(im.resize((64, 64), Image.NEAREST), ((i % 12) * 68 + 2, (i // 12) * 68 + 2))
        sheet.save(os.path.join(prev, "wild_icons.png"))


WILD_JAVA = '''import java.util.HashMap;
import java.util.Map;

/**
 * GENERATED by tools/wild_assets.py: don't edit. For each "wild" model part (item model faultline:wild/<part>):
 * {display scale, offset x, y, z}. Java item models are drawn small to fit; an ItemDisplay showing one at scale k
 * needs scale = k * s and translation = Q0 * (k * offset) (offset in the part's own frame: +x front, +z right) so the
 * part's origin sits on the entity at its real size. Bedrock draws the same part full size (an armor stand's helmet).
 */
final class WildParts {
    static final Map<String, double[]> P = new HashMap<>();
    static {
%LINES%
    }

    static double[] of(String part) { return P.getOrDefault(part, new double[]{1, 0, 0, 0}); }
}
'''


def glyphs(A, imgs):
    glyphs_path = os.path.join(ROOT, "plugins", "FaultlineIndex", "src", "main", "resources", "glyphs.yml")
    gtext = open(glyphs_path).read()
    have = set(re.findall(r"^([a-z0-9_]+):", gtext, re.M))
    smalls = [int(v) for v in re.findall(r"small: (\d+)", gtext)]
    larges = [int(v) for v in re.findall(r"large: (\d+)", gtext)]
    nsmall, nlarge = max(smalls) + 1, max(larges) + 1
    font_path = os.path.join(A, "font/index.json")
    font = json.load(open(font_path)) if os.path.exists(font_path) else {"providers": []}
    provided = {(p.get("file"), p.get("height")) for p in font["providers"]}
    added = []
    for k in imgs:
        f = "faultline:index/" + k + ".png"
        if k not in have:
            gtext += f"{k}:\n  large: {nlarge}\n  small: {nsmall}\n"
            small, large = nsmall, nlarge
            nsmall += 1; nlarge += 1
            added.append(k)
        else:
            m = re.search(rf"^{k}:\n  large: (\d+)\n  small: (\d+)", gtext, re.M)
            large, small = int(m.group(1)), int(m.group(2))
        if (f, 8) not in provided: font["providers"].append({"type": "bitmap", "file": f, "ascent": 7, "height": 8, "chars": [chr(small)]})
        if (f, 16) not in provided: font["providers"].append({"type": "bitmap", "file": f, "ascent": 7, "height": 16, "chars": [chr(large)]})
    with open(glyphs_path, "w") as fh: fh.write(gtext)
    with open(font_path, "w") as fh: json.dump(font, fh, indent=1)
    print("new glyphs:", ", ".join(added) or "none")


if __name__ == "__main__":
    main()
