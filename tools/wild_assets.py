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
        # Bedrock only: boxes can sit in named bones (a pivot in the part's own frame), and the attachable can loop
        # animations on them (Bedrock's armor stands can only be turned left/right by the server, so anything that
        # should move on its own there - jaws, wings, fins, capes - moves in the pack itself).
        self.bone_of = []          # bone name per box (None = the root)
        self.pivots = {}           # bone -> (x, y, z) local
        self.anims = []            # (bone, "rot"|"pos", (ax, ay, az) amplitude in Bedrock axes, seconds, phase 0..1)
        self.cur_bone = None

    def bone(self, name, pivot=(0, 0, 0)):
        """Boxes added from now on go in this Bedrock bone (None = back to the root)."""
        if name is not None: self.pivots[name] = pivot
        self.cur_bone = name
        return self

    def anim(self, bone, kind, amp, secs, phase=0.0):
        self.anims.append((bone, kind, amp, secs, phase))
        return self

    def add(self, x0, y0, z0, x1, y1, z1, paint):
        self.boxes.append(((min(x0, x1), min(y0, y1), min(z0, z1)), (max(x0, x1), max(y0, y1), max(z0, z1)), paint))
        self.bone_of.append(self.cur_bone)
        return self

    def take(self, other, dx=0.0, dy=0.0, dz=0.0, bone=None):
        """Copies another part's boxes in (shifted), into a bone of this one."""
        keep = self.cur_bone
        self.cur_bone = bone
        for (a, b, paint) in other.boxes: self.add(a[0] + dx, a[1] + dy, a[2] + dz, b[0] + dx, b[1] + dy, b[2] + dz, paint)
        self.cur_bone = keep
        return self

    def mirror_z(self, name):
        p = Part(name, self.ppb, self.size, self.java_only, self.bedrock_only)
        for (a, b, paint), bn in zip(self.boxes, self.bone_of):
            p.boxes.append(((a[0], a[1], -b[2]), (b[0], b[1], -a[2]), paint)); p.bone_of.append(bn)
        p.pivots = {k: (v[0], v[1], -v[2]) for k, v in self.pivots.items()}
        # rotations mirrored across the body's centre plane: the z (and y) turn flips, so both sides move together
        p.anims = [(bn, kind, (amp[0], -amp[1], -amp[2]) if kind == "rot" else (-amp[0], amp[1], amp[2]), secs, ph)
                   for (bn, kind, amp, secs, ph) in self.anims]
        return p

    def bone_all(self, name, pivot=(0, 0, 0)):
        """Puts every box so far into one Bedrock bone (to animate the whole part on Bedrock)."""
        self.pivots[name] = pivot
        self.bone_of = [name if b is None else b for b in self.bone_of]
        return self

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
    cubes = {}
    for ((a, b), regs), bn in zip(boxes, part.bone_of):
        # local +x (front) -> Bedrock -z, local +z (right) -> Bedrock -x (the ship convention)
        bx0, bx1 = -b[2] * 16, -a[2] * 16
        bz0, bz1 = -b[0] * 16, -a[0] * 16
        cube = {"origin": [round(bx0, 3), round(a[1] * 16, 3), round(bz0, 3)],
                "size": [round(bx1 - bx0, 3), round((b[1] - a[1]) * 16, 3), round(bz1 - bz0, 3)], "uv": {}}
        for key, (x, y, w, h) in regs.items():
            cube["uv"][FACES[key][1]] = {"uv": [x, y], "uv_size": [w, h]}
        cubes.setdefault(bn, []).append(cube)
    lo, hi = part.bounds()
    vb = max(4, math.ceil(max(hi[0] - lo[0], hi[2] - lo[2], hi[1] - lo[1]) * 2 + 2))
    return {"format_version": "1.16.0", "minecraft:geometry": [{
        "description": {"identifier": f"geometry.faultline.wild_{part.name}", "texture_width": part.size, "texture_height": part.size,
                        "visible_bounds_width": vb, "visible_bounds_height": vb, "visible_bounds_offset": [0, (lo[1] + hi[1]) / 2, 0]},
        "bones": [{"name": "body", "pivot": [0, 24, 0], "cubes": cubes.get(None, [])}]
                  + [{"name": bn, "parent": "body", "pivot": bed_pt(part.pivots[bn]), "cubes": cs} for bn, cs in cubes.items() if bn is not None]}]}


def bed_pt(p):
    """A point in a part's frame (+x front, +y up, +z right), in Bedrock geometry units (front = -z)."""
    return [round(-p[2] * 16, 3), round(p[1] * 16, 3), round(-p[0] * 16, 3)]


def bedrock_anim(part):
    """Looping sine animations for the part's bones (rotation in degrees / position in pixels, Bedrock axes)."""
    anims = {}
    for i, (bn, kind, amp, secs, ph) in enumerate(part.anims):
        n = 8
        keys = {}
        for j in range(n + 1):
            t = j / n
            w = math.sin(2 * math.pi * (t + ph))
            keys[f"{round(t * secs, 4)}"] = [round(a * w, 3) for a in amp]
        key = f"animation.faultline.wild_{part.name}.{bn}_{kind}_{i}"
        anims[key] = {"loop": True, "animation_length": secs,
                      "bones": {bn: {"rotation" if kind == "rot" else "position": keys}}}
    return {"format_version": "1.8.0", "animations": anims}


# ---------------------------------------------------------------------------------------------- the parts

TEAL, TEAL_D, BELLY, FIN = (24, 86, 104), (12, 50, 64), (196, 206, 176), (60, 180, 196)
YEL, MOUTH, HORN = (255, 222, 70), (96, 22, 34), (58, 56, 70)


def rounded(p, x0, y0, z0, x1, y1, z1, paint, r):
    """A box with its four long edges bevelled (two crossed boxes): reads round from the front instead of square."""
    p.add(x0, y0 + r, z0, x1, y1 - r, z1, paint)
    p.add(x0, y0, z0 + r, x1, y1, z1 - r, paint)
    return p


LEV_HINGE = (0.5, -0.55, 0.0)


def leviathan():
    """A sea serpent-dragon: long fanged jaws (the lower one its own part, so it bites and roars), swept horns, a crest
    fin, gill frills, barbels and glowing spots; rounded finned body segments with a webbed dorsal crest; a crescent
    tail fluke. Bedrock gets one head with the jaw as a bone, and its fins, crest and fluke move in the pack itself."""
    sc = scales(TEAL, TEAL_D)
    fin = membrane(FIN, (30, 120, 140))
    spot = glow((120, 255, 230))
    h = Part("lev_head", 6, 128)
    rounded(h, -1.3, -0.95, -1.15, 1.0, 0.95, 1.15, sc, 0.3)       # skull
    rounded(h, 1.0, -0.5, -0.8, 3.0, 0.65, 0.8, sc, 0.18)          # upper jaw (snout)
    h.add(3.0, -0.45, -0.6, 3.5, 0.45, 0.6, sc)
    h.add(1.05, -0.55, -0.66, 2.95, -0.46, 0.66, solid(MOUTH, 4))  # the roof of the mouth
    h.add(0.5, -0.9, -0.7, 1.0, -0.5, 0.7, solid(MOUTH, 4))        # the throat, seen when it opens
    for i in range(7):
        x = 1.15 + i * 0.27
        for z in (-0.72, 0.6):
            h.add(x, -0.82, z, x + 0.13, -0.5, z + 0.12, teeth)   # upper fangs, hanging down
    h.add(3.1, -0.85, -0.12, 3.35, -0.45, 0.12, teeth)             # a front tusk
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
        h.add(2.6, -0.4, s * 0.8, 2.75, -0.25, s * 0.95, solid(TEAL_D))    # barbel roots
        for x in (-0.8, 0.0, 1.5, 2.2): h.add(x, 0.0, s * 1.15 if x < 1 else s * 0.8, x + 0.18, 0.15, s * (1.18 if x < 1 else 0.83), spot)
    # the lower jaw, built in the head's frame and then moved so its hinge is its origin
    jaw_h = Part("tmp")
    rounded(jaw_h, 0.55, -1.05, -0.74, 2.9, -0.55, 0.74, sc, 0.14)
    jaw_h.add(0.75, -1.12, -0.56, 2.8, -1.0, 0.56, solid(BELLY))   # pale underside
    jaw_h.add(0.9, -0.62, -0.6, 2.85, -0.55, 0.6, solid((150, 40, 56), 4))  # tongue
    for i in range(7):
        x = 1.25 + i * 0.27
        for z in (-0.72, 0.6):
            jaw_h.add(x, -0.6, z, x + 0.12, -0.32, z + 0.12, teeth)  # lower fangs, pointing up
    for s in (-1, 1):
        jaw_h.add(2.5, -1.55, s * 0.86, 2.65, -0.95, s * 0.98, solid((40, 110, 120), 4))   # barbels, hanging
        jaw_h.add(2.45, -2.05, s * 0.88, 2.6, -1.55, s * 1.0, spot)
    jaw = Part("lev_jaw", 6, 64, java_only=True)
    jaw.take(jaw_h, -LEV_HINGE[0], -LEV_HINGE[1], -LEV_HINGE[2])
    hb = Part("lev_head_b", 6, 128, bedrock_only=True)
    hb.take(h)
    hb.bone("jaw", (LEV_HINGE[0], LEV_HINGE[1] - 0.06, 0))
    hb.take(jaw_h, 0, -0.06, 0, bone="jaw")
    hb.anim("jaw", "pos", (0, -1.4, 0), 2.6)                      # it works its jaw as it swims
    h.java_only = True
    seg = Part("lev_body", 6, 128)
    rounded(seg, -1.25, -1.05, -1.15, 1.25, 1.05, 1.15, sc, 0.32)
    seg.add(-1.2, -1.13, -0.8, 1.2, -0.7, 0.8, stripes_h(BELLY, (160, 170, 140)))
    seg.bone("crest", (0, 1.05, 0))
    seg.add(-1.1, 1.05, -0.08, 1.0, 2.3, 0.08, fin)                 # dorsal crest
    for x in (-0.8, 0.0, 0.8): seg.add(x - 0.06, 1.05, -0.1, x + 0.06, 2.45 - abs(x) * 0.4, 0.1, solid(HORN, 6))
    seg.bone(None)
    for s in (-1, 1):
        seg.bone("fin_r" if s > 0 else "fin_l", (0, -0.3, s * 1.1))
        seg.add(-0.6, -0.55, s * 1.1, 0.5, -0.1, s * 1.95, fin)       # side fins
        seg.add(-0.45, -0.5, s * 1.95, 0.25, -0.15, s * 2.2, fin)
        seg.bone(None)
        for x in (-0.7, 0.1, 0.8): seg.add(x, 0.1, s * 1.13, x + 0.2, 0.3, s * 1.16, spot)
        for x in (-0.9, -0.3, 0.3, 0.9): seg.add(x, -0.75, s * 0.95, x + 0.14, -0.6, s * 1.0, spot)   # a line of glow down the flank
    seg.anim("crest", "rot", (0, 0, 7), 2.2)
    seg.anim("fin_l", "rot", (0, 0, 18), 1.7)
    seg.anim("fin_r", "rot", (0, 0, -18), 1.7)
    tail = Part("lev_tail", 6, 128)
    rounded(tail, -1.4, -0.85, -0.95, 0.75, 0.85, 0.95, sc, 0.26)
    rounded(tail, -2.7, -0.6, -0.65, -1.4, 0.6, 0.65, sc, 0.18)
    tail.add(-3.4, -0.35, -0.4, -2.7, 0.35, 0.4, sc)
    tail.add(-1.2, 0.85, -0.07, 0.4, 1.6, 0.07, fin)
    tail.bone("fluke", (-3.1, 0, 0))
    for (y0, y1, x0, x1) in ((0.3, 2.6, -4.2, -3.2), (2.0, 3.4, -4.9, -3.8), (-2.6, -0.3, -4.2, -3.2), (-3.4, -2.0, -4.9, -3.8)):
        tail.add(x0, y0, -0.09, x1, y1, 0.09, fin)                  # the crescent fluke (upright)
    tail.add(-3.6, -0.5, -0.12, -3.1, 0.5, 0.12, spot)
    tail.bone(None)
    tail.anim("fluke", "rot", (0, 16, 0), 1.6)
    return [h, jaw, hb, seg, tail]


SAND, SAND_D, MAW, THROAT, BONE = (200, 164, 104), (118, 88, 50), (74, 24, 24), (26, 6, 6), (230, 216, 184)


WORM_HINGE = 1.0   # the petals hinge on the rim of the maw, this far out from the head's axis, at x = 1.0


def _petal(sc_armor):
    """One petal of the maw, in its own frame: hinge at the origin, pointing forward (+x), its outside facing +y."""
    p = Part("worm_petal", 5, 64, java_only=True)
    p.add(0.0, 0.0, -0.75, 1.5, 0.55, 0.75, sc_armor)
    p.add(0.0, -0.06, -0.62, 1.45, 0.0, 0.62, solid(MAW, 6))      # its inner face, red
    p.add(1.5, 0.3, -0.2, 2.1, 0.55, 0.2, teeth)                   # tipped with a fang
    for xx in (0.35, 0.8, 1.2):
        for zz in (-0.42, 0.0, 0.42):
            p.add(xx, -0.22, zz - 0.07, xx + 0.12, -0.06, zz + 0.07, teeth)   # rows of teeth facing the throat
    return p


def _turn_petal(p, k):
    """The petal's boxes turned to the k-th side of the maw (0 top, 1 right, 2 bottom, 3 left), hinge on the rim."""
    out = []
    for (a, b, paint) in p.boxes:
        cs = [(x, y, z) for x in (a[0], b[0]) for y in (a[1], b[1]) for z in (a[2], b[2])]
        tr = []
        for (x, y, z) in cs:
            y += WORM_HINGE
            if k == 1: y, z = -z, y
            elif k == 2: y, z = -y, -z
            elif k == 3: y, z = z, -y
            tr.append((x + 1.0, y, z))
        lo = [min(c[i] for c in tr) for i in range(3)]; hi = [max(c[i] for c in tr) for i in range(3)]
        out.append((lo, hi, paint))
    return out


def sandworm():
    """The Sandworm KING: an armoured, plated head with a four-petal maw (each petal its own part, so it opens and
    snaps shut; fanged, red inside), two rings of teeth down a glowing throat and a crown of bone spikes; rounded,
    ridged body rings with bristles underneath; a tail ending in a barbed stinger. Bedrock gets one head with the
    petals as bones, breathing open and shut in the pack."""
    pl = bands(SAND, SAND_D, 5)
    armor = stone((176, 140, 86))
    crown = solid(BONE, 6)
    h = Part("worm_head", 5, 128, java_only=True)
    rounded(h, -1.5, -1.5, -1.5, 0.9, 1.5, 1.5, pl, 0.42)
    for x in (-1.2, -0.45, 0.3):                                     # overlapping armour rings
        rounded(h, x, -1.62, -1.62, x + 0.45, 1.62, 1.62, armor, 0.45)
    h.add(0.9, -1.3, -1.3, 1.3, 1.3, 1.3, solid(MAW, 6))
    h.add(1.3, -0.85, -0.85, 1.36, 0.85, 0.85, solid(THROAT, 3))
    h.add(1.2, -0.4, -0.4, 1.38, 0.4, 0.4, glow((255, 120, 40)))   # the glow deep in its throat
    for ring, r, x in ((0, 0.95, 1.3), (1, 0.6, 1.32)):
        for kk in range(12 if ring == 0 else 8):
            a = kk * math.pi / (6 if ring == 0 else 4) + ring * 0.3
            y, z = math.sin(a) * r, math.cos(a) * r
            h.add(x, y - 0.1, z - 0.1, x + (0.45 if ring == 0 else 0.3), y + 0.1, z + 0.1, teeth)
    for kk in range(8):                                               # the crown of bone spikes
        a = kk * math.pi / 4 + math.pi / 8
        y, z = math.sin(a), math.cos(a)
        h.add(-0.9, y * 1.6 - 0.14, z * 1.6 - 0.14, -0.5, y * 1.6 + 0.14, z * 1.6 + 0.14, crown)
        h.add(-1.4, y * 2.1 - 0.1, z * 2.1 - 0.1, -0.8, y * 2.1 + 0.1, z * 2.1 + 0.1, crown) if y > 0 else None
    h.add(-1.1, 1.62, -0.18, -0.2, 2.6, 0.18, crown)                 # the tallest spike
    petal = _petal(armor)
    hb = Part("worm_head_b", 5, 128, bedrock_only=True)
    hb.take(h)
    for k, (bn, pv, amp) in enumerate((("p_up", (1.0, WORM_HINGE, 0), (0, 1.3, 0)), ("p_r", (1.0, 0, WORM_HINGE), (-1.3, 0, 0)),
                                       ("p_down", (1.0, -WORM_HINGE, 0), (0, -1.3, 0)), ("p_l", (1.0, 0, -WORM_HINGE), (1.3, 0, 0)))):
        hb.bone(bn, pv)
        for (lo, hi, paint) in _turn_petal(petal, k): hb.add(*lo, *hi, paint)
        hb.anim(bn, "pos", amp, 2.0)
    hb.bone(None)
    s2 = Part("worm_body", 5, 128)
    rounded(s2, -1.35, -1.35, -1.35, 1.35, 1.35, 1.35, pl, 0.4)
    rounded(s2, -1.4, -1.48, -1.48, -0.9, 1.48, 1.48, armor, 0.44)
    rounded(s2, 0.2, -1.42, -1.42, 0.7, 1.42, 1.42, armor, 0.42)
    for x in (-0.6, 0.6):
        s2.add(x - 0.28, 1.35, -0.2, x + 0.28, 2.0, 0.2, crown)
        s2.add(x - 0.16, 2.0, -0.12, x + 0.16, 2.4, 0.12, crown)
    for sgn in (-1, 1): s2.add(-0.25, 0.2, sgn * 1.35, 0.25, 0.6, sgn * 1.85, crown)
    s2.bone("bristles", (0, -1.35, 0))
    for x in (-0.9, -0.3, 0.3, 0.9):                                # bristles underneath it pulls itself along with
        for z in (-0.7, 0.0, 0.7):
            s2.add(x - 0.05, -1.75, z - 0.05, x + 0.05, -1.35, z + 0.05, solid((90, 64, 36), 4))
    s2.bone(None)
    s2.anim("bristles", "rot", (0, 0, 14), 0.9)
    t = Part("worm_tail", 5, 128)
    rounded(t, -0.8, -1.05, -1.05, 1.2, 1.05, 1.05, pl, 0.3)
    rounded(t, -0.2, -1.12, -1.12, 0.3, 1.12, 1.12, armor, 0.32)
    rounded(t, -1.9, -0.75, -0.75, -0.8, 0.75, 0.75, pl, 0.2)
    t.add(-2.9, -0.42, -0.42, -1.9, 0.42, 0.42, armor)
    t.bone("sting", (-2.9, 0, 0))
    t.add(-3.9, -0.2, -0.2, -2.9, 0.2, 0.2, crown)                   # the stinger
    for sgn in (-1, 1): t.add(-3.2, -0.08, sgn * 0.2, -2.9, 0.08, sgn * 0.5, crown)
    t.bone(None)
    t.anim("sting", "rot", (12, 0, 0), 1.3)
    t.add(-1.4, 0.75, -0.14, -0.9, 1.3, 0.14, crown)
    return [h, petal, hb, s2, t]


ICE, ICE_D, FROST, CYAN, WMEM = (150, 200, 232), (90, 140, 186), (232, 244, 252), (110, 240, 255), (126, 176, 224)
THROAT_W = (20, 30, 52)
WYRM_HINGE = (0.4, -0.5, 0.0)   # where the Frost Wyrm's lower jaw hinges, in the head's frame


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
    rounded(b, -1.6, -0.85, -1.0, 1.0, 0.85, 1.0, sc, 0.26)      # torso
    rounded(b, 0.4, -1.0, -1.15, 1.85, 0.92, 1.15, sc, 0.3)      # deep chest
    rounded(b, -2.3, -0.75, -0.85, -1.4, 0.65, 0.85, sc, 0.22)   # hips
    for x in (-1.3, -0.4, 0.5, 1.3):                              # a glowing frost seam down each flank
        for s in (-1, 1): b.add(x, -0.1, s * 1.0 - (0.04 if s > 0 else 0), x + 0.3, 0.05, s * 1.0 + (0.04 if s < 0 else 0), glow(CYAN))
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
    rounded(n, -0.62, -0.48, -0.48, 0.62, 0.48, 0.48, sc, 0.14)
    n.add(-0.58, -0.57, -0.34, 0.58, -0.44, 0.34, plate)
    n.add(-0.16, 0.48, -0.08, 0.16, 0.95, 0.08, bone)
    for s in (-1, 1): n.add(-0.15, -0.1, s * 0.48, 0.15, 0.12, s * 0.66, bone)
    hd = Part("wyrm_head", 7, 128, java_only=True)
    rounded(hd, -0.25, -0.5, -0.56, 1.2, 0.56, 0.56, sc, 0.16)  # skull
    rounded(hd, 1.2, -0.42, -0.42, 2.6, 0.28, 0.42, sc, 0.1)    # snout
    hd.add(2.6, -0.36, -0.33, 3.0, 0.16, 0.33, sc)               # nose
    hd.add(1.25, -0.5, -0.34, 2.6, -0.42, 0.34, solid(MOUTH, 4)) # the roof of the mouth
    hd.add(0.4, -0.62, -0.36, 1.25, -0.4, 0.36, solid(THROAT_W, 3))   # the throat, with frost glowing deep in it
    hd.add(0.42, -0.58, -0.14, 0.5, -0.44, 0.14, glow(CYAN))
    for i in range(6):
        x = 1.3 + i * 0.24
        for z in (-0.38, 0.3):
            hd.add(x, -0.66, z, x + 0.1, -0.42, z + 0.08, teeth)  # upper fangs
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
    # the lower jaw, built in the head's frame, then moved so its hinge is its origin
    jw_h = Part("tmp")
    rounded(jw_h, 0.45, -0.86, -0.38, 2.75, -0.52, 0.38, sc, 0.1)
    jw_h.add(0.55, -0.92, -0.3, 2.65, -0.84, 0.3, plate)
    jw_h.add(0.7, -0.54, -0.3, 2.6, -0.5, 0.3, solid((120, 36, 60), 4))   # tongue
    for i in range(6):
        x = 1.42 + i * 0.24
        for z in (-0.38, 0.3): jw_h.add(x, -0.54, z, x + 0.08, -0.38, z + 0.08, teeth)   # lower fangs
    for s in (-1, 1): jw_h.add(1.0, -0.9, s * 0.38, 1.6, -0.62, s * 0.46, bone)            # jaw spikes
    jaw = Part("wyrm_jaw", 7, 64, java_only=True)
    jaw.take(jw_h, -WYRM_HINGE[0], -WYRM_HINGE[1], -WYRM_HINGE[2])
    hb = Part("wyrm_head_b", 7, 128, bedrock_only=True)
    hb.take(hd)
    hb.bone("jaw", WYRM_HINGE)
    hb.take(jw_h, bone="jaw")
    hb.anim("jaw", "pos", (0, -1.1, 0), 2.4)
    wl = Part("wyrm_wing_l", 5, 128)
    wl.bone("wing", (0, 0, 0))
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
    wl.bone(None)
    wl.anim("wing", "rot", (0, 0, 20), 1.5)      # a slow beat on Bedrock (its stand can't roll the wing)
    wr = wl.mirror_z("wyrm_wing_r")
    seg = Part("wyrm_tail", 6, 64)
    seg.add(-0.62, -0.45, -0.48, 0.62, 0.45, 0.48, sc)
    seg.add(-0.58, -0.53, -0.32, 0.58, -0.42, 0.32, plate)
    seg.add(-0.18, 0.45, -0.1, 0.18, 0.98, 0.1, bone)
    for s in (-1, 1): seg.add(-0.18, -0.05, s * 0.48, 0.18, 0.13, s * 0.74, bone)
    tip = Part("wyrm_tail_tip", 6, 64)
    tip.add(-0.25, -0.33, -0.34, 0.6, 0.33, 0.34, sc)
    tip.bone("blade", (-0.2, 0, 0))
    tip.add(-1.7, -0.06, -0.75, -0.2, 0.06, 0.75, bone)          # the ice blade (flat)
    tip.add(-2.1, -0.05, -0.36, -1.7, 0.05, 0.36, bone)
    tip.add(-1.5, -0.62, -0.05, -0.2, 0.62, 0.05, glow((170, 235, 255)))  # and its fin (upright)
    tip.bone(None)
    tip.anim("blade", "rot", (0, 14, 0), 1.2)
    return [b, n, hd, jaw, hb, wl, wr, seg, tip]


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
    # Bedrock: its stands can only turn left/right, so the pack keeps the rig alive: the head nods, the sleeves
    # sway, the cape billows and the whole body bobs as it floats
    head.bone_all("nod").anim("nod", "rot", (4, 0, 2), 3.0)
    arm.bone_all("sway").anim("sway", "rot", (6, 0, 3), 2.6)
    cape.bone_all("billow").anim("billow", "rot", (8, 0, 0), 2.2)
    body.bone_all("hover").anim("hover", "pos", (0, 1.0, 0), 3.0)
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



# ---------------------------------------------------------------------------------------------- the Dark Ocean's bosses
# (Bosses 1.6.0, DeepBosses.java). Serpents: the head's origin is the boss's position (front +x); a body segment's
# origin is the BACK joint and it reaches forward to the next one (gap blocks); the tail's origin is the last joint and
# it trails back (-x).

def _seg(p, x0, x1, r, paint, belly=None, bev=0.25):
    rounded(p, x0, -r, -r, x1, r, r, paint, min(bev, r * 0.4))
    if belly: p.add(x0 + 0.1, -r - 0.06, -r * 0.7, x1 - 0.1, -r * 0.5, r * 0.7, belly)


SC_PLATE, SC_DARK, SC_ACID, SC_BONE = (128, 150, 104), (66, 82, 58), (178, 226, 62), (214, 206, 168)


def scourge():
    """The Brine Scourge: a huge plated sea worm (after Calamity's Aquatic Scourge): a blunt armoured head with four
    hooked mandibles around a glowing acid maw, spined back plates, acid sacs down the flanks, a barbed tail."""
    pl, dk, acid = scales(SC_PLATE, SC_DARK), solid(SC_DARK, 8), glow(SC_ACID)
    sp = solid(SC_BONE, 6)
    h = Part("scourge_head", 6, 128)
    rounded(h, -1.2, -1.1, -1.15, 1.3, 1.15, 1.15, pl, 0.35)
    rounded(h, 1.3, -0.85, -0.9, 2.2, 0.95, 0.9, pl, 0.25)          # the brow shield
    h.add(2.2, -0.5, -0.55, 2.35, 0.5, 0.55, solid((40, 60, 20), 4))   # the maw
    h.add(2.25, -0.35, -0.4, 2.4, 0.35, 0.4, acid)
    for s in (-1, 1):
        for u in (-1, 1):                                            # four mandibles around the maw
            h.add(2.0, u * 0.45 - 0.15, s * 0.45 - 0.15, 3.0, u * 0.45 + 0.15, s * 0.45 + 0.15, sp)
            h.add(2.9, u * 0.3 - 0.12, s * 0.3 - 0.12, 3.3, u * 0.3 + 0.12, s * 0.3 + 0.12, sp)
        h.add(1.4, 0.35, s * 0.9, 1.75, 0.6, s * 0.98, acid)         # eyes
        h.add(1.0, 0.25, s * 0.92, 1.25, 0.45, s * 0.98, acid)
        h.add(-0.6, 0.8, s * 0.5, 0.6, 1.0, s * 0.8, dk)             # plate ridges
    for x in (-0.8, 0.0, 0.8): h.add(x - 0.15, 1.1, -0.12, x + 0.15, 1.75 - abs(x) * 0.25, 0.12, sp)   # head spines
    seg = Part("scourge_body", 6, 128)
    _seg(seg, -0.2, 1.95, 1.0, pl, stripes_h((170, 172, 128), (120, 122, 90)))
    seg.add(0.15, 0.95, -0.85, 1.6, 1.15, 0.85, dk)                   # back plate
    for x in (0.35, 1.25): seg.add(x - 0.12, 1.15, -0.1, x + 0.12, 1.85, 0.1, sp)
    for s in (-1, 1):
        seg.add(0.6, -0.35, s * 1.0, 1.2, 0.25, s * 1.15, acid)       # acid sacs
        seg.add(0.2, 0.5, s * 0.95, 1.5, 0.7, s * 1.05, dk)
    seg.bone_all("seg")
    seg.anim("seg", "rot", (0, 5, 0), 2.4)
    tail = Part("scourge_tail", 6, 128)
    _seg(tail, -1.6, 0.2, 0.85, pl)
    _seg(tail, -2.8, -1.4, 0.55, pl)
    tail.add(-3.6, -0.3, -0.3, -2.7, 0.3, 0.3, dk)
    tail.bone("barb", (-3.4, 0, 0))
    tail.add(-4.6, -0.12, -0.12, -3.5, 0.12, 0.12, sp)                # the barb
    for s in (-1, 1): tail.add(-4.2, -0.1, s * 0.12, -3.7, 0.1, s * 0.55, sp)
    tail.add(-3.9, -0.15, -0.15, -3.6, 0.15, 0.15, acid)
    tail.bone(None)
    tail.anim("barb", "rot", (0, 15, 0), 1.5)
    return [h, seg, tail]


UM_SKIN, UM_DARK, UM_EYE, UM_FIN = (30, 26, 44), (14, 12, 22), (160, 100, 255), (52, 40, 86)


def umbral():
    """The Umbral Leviathan (after Below Zero's Shadow Leviathan): a black serpent with four long mandibles that open
    like a flower, a crown of rear horns, glowing violet eyes and stripes, wide fin-wings down the body."""
    sk, dk, eye = scales(UM_SKIN, UM_DARK), solid(UM_DARK, 6), glow(UM_EYE)
    fin = membrane(UM_FIN, (96, 64, 170))
    h = Part("umbral_head", 6, 128)
    rounded(h, -1.3, -0.95, -1.05, 1.4, 0.95, 1.05, sk, 0.32)
    rounded(h, 1.4, -0.6, -0.65, 2.3, 0.6, 0.65, sk, 0.2)
    h.add(2.3, -0.35, -0.35, 2.4, 0.35, 0.35, solid((60, 20, 90), 3))
    h.bone("mand", (2.0, 0, 0))
    for s in (-1, 1):
        for u in (-1, 1):                                            # the four mandibles
            h.add(1.6, u * 0.5 - 0.18, s * 0.55 - 0.18, 3.4, u * 0.5 + 0.18, s * 0.55 + 0.18, sk)
            h.add(3.4, u * 0.35 - 0.12, s * 0.38 - 0.12, 4.0, u * 0.35 + 0.12, s * 0.38 + 0.12, dk)
            h.add(2.2, u * 0.5 - 0.2, s * 0.55 - 0.05, 3.2, u * 0.5 - 0.15, s * 0.55 + 0.05, eye)
    h.bone(None)
    h.anim("mand", "pos", (0, 0, -1.2), 2.0)
    for s in (-1, 1):
        h.add(0.9, 0.3, s * 1.0, 1.35, 0.55, s * 1.08, eye)           # eyes
        h.add(0.3, 0.2, s * 1.02, 0.65, 0.4, s * 1.08, eye)
        h.add(-2.6, 0.6, s * 0.55, -0.9, 0.85, s * 0.8, dk)            # rear horns
        h.add(-3.5, 0.8, s * 0.6, -2.4, 1.0, s * 0.75, dk)
        h.add(-2.0, -0.2, s * 0.85, -1.0, 0.0, s * 1.4, dk)
    h.add(-1.4, 0.95, -0.07, 1.0, 1.7, 0.07, fin)
    seg = Part("umbral_body", 6, 128)
    _seg(seg, -0.2, 2.05, 0.95, sk)
    seg.add(0.0, 0.94, -0.1, 1.85, 1.0, 0.1, eye)                     # the glowing spine stripe
    for s in (-1, 1):
        seg.bone("fin_" + ("r" if s > 0 else "l"), (0.9, 0, s * 0.95))
        seg.add(0.2, -0.08, s * 0.95, 1.7, 0.08, s * 2.6, fin)        # fin-wings
        seg.add(0.5, -0.06, s * 2.6, 1.4, 0.06, s * 3.1, fin)
        seg.bone(None)
        for x in (0.3, 1.0, 1.7): seg.add(x, -0.2, s * 0.93, x + 0.15, 0.4, s * 0.97, eye)
    seg.anim("fin_l", "rot", (0, 0, 14), 2.0)
    seg.anim("fin_r", "rot", (0, 0, -14), 2.0)
    tail = Part("umbral_tail", 6, 128)
    _seg(tail, -1.8, 0.2, 0.75, sk)
    _seg(tail, -3.0, -1.6, 0.45, sk)
    tail.bone("fluke", (-2.9, 0, 0))
    for s in (-1, 1):
        tail.add(-4.4, -0.07, s * 0.2, -2.9, 0.07, s * 1.8, fin)      # flat fluke
    tail.add(-3.2, -0.1, -0.1, -2.8, 0.1, 0.1, eye)
    tail.bone(None)
    tail.anim("fluke", "rot", (12, 0, 0), 1.7)
    return [h, seg, tail]


CO_SKIN, CO_SKIN_D, CO_HAIR, CO_TAIL, CO_TAIL_D, CO_GOLD, CO_FIN = (196, 214, 186), (150, 170, 140), (226, 82, 104), (52, 150, 132), (30, 92, 84), (236, 196, 70), (120, 220, 200)


def coralia():
    """Coralia, Siren of the Shoals (after Cuphead's Cala Maria): a giant mermaid. Waist = the origin; the tail hangs
    down and back to a wide fin; the torso up to the neck (y 1.25), where the head sits; arms hang from the shoulders
    (0, 1.0, +-0.55)."""
    skin, tl = solid(CO_SKIN, 5), scales(CO_TAIL, CO_TAIL_D)
    gold, hair = solid(CO_GOLD, 6), solid(CO_HAIR, 8)
    fin = membrane(CO_FIN, (60, 160, 150))
    tail = Part("coralia_tail", 6, 128)
    rounded(tail, -0.42, -0.9, -0.48, 0.42, 0.1, 0.48, tl, 0.14)
    rounded(tail, -0.75, -1.8, -0.4, 0.2, -0.85, 0.4, tl, 0.12)
    rounded(tail, -1.4, -2.5, -0.32, -0.45, -1.7, 0.32, tl, 0.1)
    tail.add(-2.0, -2.9, -0.24, -1.2, -2.3, 0.24, tl)
    tail.bone("fin", (-1.9, -2.7, 0))
    tail.add(-3.1, -3.6, -0.08, -1.8, -2.4, 0.08, fin)                 # the fin (upright)
    tail.add(-2.9, -2.2, -0.08, -2.0, -1.6, 0.08, fin)
    tail.bone(None)
    tail.add(-0.46, -0.15, -0.52, 0.46, 0.1, 0.52, gold)               # the belt of shells
    for s in (-1, 1): tail.add(-0.3, -1.4, s * 0.38, 0.1, -1.0, s * 0.62, fin)   # side fins
    tail.anim("fin", "rot", (0, 0, 14), 1.8)
    torso = Part("coralia_torso", 6, 64)
    rounded(torso, -0.3, 0.05, -0.45, 0.32, 1.1, 0.45, skin, 0.12)
    torso.add(-0.32, 1.0, -0.62, 0.3, 1.18, 0.62, solid(CO_SKIN_D, 4))   # shoulders
    for s in (-1, 1): torso.add(0.25, 0.6, s * 0.08, 0.42, 0.88, s * 0.38, gold)   # shell top
    torso.add(-0.12, 1.1, -0.15, 0.12, 1.3, 0.15, skin)                 # neck
    head = Part("coralia_head", 6, 128)
    rounded(head, -0.32, 0.05, -0.3, 0.34, 0.75, 0.3, skin, 0.1)
    for s in (-1, 1):
        head.add(0.34, 0.4, s * 0.08, 0.37, 0.52, s * 0.2, solid((40, 30, 60), 3))   # eyes
        head.add(0.36, 0.43, s * 0.11, 0.38, 0.49, s * 0.16, glow((255, 120, 200)))
        head.add(-0.35, 0.1, s * 0.3, 0.1, 0.8, s * 0.45, hair)
    head.add(0.34, 0.2, -0.08, 0.37, 0.26, 0.08, solid((180, 40, 70), 3))      # lips
    head.add(-0.5, 0.55, -0.42, 0.25, 1.05, 0.42, hair)                 # hair
    head.add(-0.95, -0.6, -0.4, -0.3, 0.9, 0.4, hair)                   # it falls down her back
    head.add(-1.25, -1.0, -0.3, -0.85, 0.2, 0.3, hair)
    for i, z in enumerate((-0.32, -0.12, 0.08, 0.28)):                  # a coral crown
        head.add(-0.1, 1.0, z, 0.1, 1.35 + (i % 2) * 0.2, z + 0.08, solid((255, 120, 110), 4))
    head.add(-0.1, 1.0, -0.4, 0.1, 1.1, 0.4, gold)
    arm = Part("coralia_arm", 6, 64)
    rounded(arm, -0.13, -0.6, -0.13, 0.13, 0.05, 0.13, skin, 0.05)
    rounded(arm, -0.11, -1.15, -0.11, 0.11, -0.55, 0.11, skin, 0.04)
    arm.add(-0.13, -1.38, -0.13, 0.13, -1.12, 0.13, solid(CO_SKIN_D, 4))
    arm.add(-0.14, -0.9, -0.14, 0.14, -0.8, 0.14, gold)                 # a bracelet
    return [tail, torso, head, arm]


IJ_SKIN, IJ_DARK, IJ_BELLY, IJ_EYE = (150, 52, 70), (88, 26, 44), (222, 160, 150), (255, 200, 60)


def inkjaw():
    """Old Inkjaw: a giant octopus. The body's origin is the middle; the mantle bulges up and back; arms (8 x 3 parts)
    leave from under it: each tentacle part starts at its origin and reaches +x 1.25, suckers underneath."""
    sk = scales(IJ_SKIN, IJ_DARK)
    body = Part("inkjaw_body", 6, 128)
    rounded(body, -0.9, -0.7, -0.9, 0.9, 0.6, 0.9, sk, 0.3)
    rounded(body, -1.8, -0.2, -0.75, 0.3, 1.5, 0.75, sk, 0.3)           # the mantle
    rounded(body, -2.4, 0.2, -0.55, -1.6, 1.3, 0.55, sk, 0.22)
    for s in (-1, 1):
        body.add(0.55, 0.15, s * 0.85, 0.95, 0.6, s * 1.0, solid((240, 230, 200), 3))   # eyes
        body.add(0.75, 0.25, s * 0.95, 0.92, 0.5, s * 1.02, glow(IJ_EYE))
        body.add(0.5, 0.6, s * 0.7, 1.0, 0.75, s * 1.0, solid(IJ_DARK))
        for x in (-1.3, -0.6): body.add(x, 0.9, s * 0.72, x + 0.25, 1.15, s * 0.78, solid(IJ_BELLY))   # warts
    body.add(0.3, -0.95, -0.2, 0.75, -0.65, 0.2, solid((40, 30, 30), 4))   # the beak
    body.add(0.6, -1.05, -0.12, 0.9, -0.85, 0.12, solid((230, 200, 120), 4))
    body.add(-0.85, -0.75, -0.85, 0.85, -0.65, 0.85, solid(IJ_BELLY))
    body.bone_all("b")
    body.anim("b", "pos", (0, 0.6, 0), 3.0)
    t = Part("inkjaw_tentacle", 6, 64)
    rounded(t, -0.1, -0.24, -0.24, 1.35, 0.24, 0.24, sk, 0.08)
    for x in (0.15, 0.55, 0.95): t.add(x, -0.3, -0.1, x + 0.2, -0.22, 0.1, solid(IJ_BELLY, 3))   # suckers
    return [body, t]


GM_SHELL, GM_SHELL_D, GM_FLESH, GM_PEARL = (198, 176, 150), (120, 96, 80), (214, 120, 140), (240, 240, 255)


def grandmaw():
    """The Grandmaw Clam (after Calamity's Giant Clam): a ribbed bowl (origin = its middle, on the floor), a lid hinged
    at the back (its origin is the hinge, (-1, 0.75, 0) in the shell's frame; closed it lies flat over the bowl), and a
    glowing pearl inside."""
    def ribbed(c, d):
        def f(reg, face, rng):
            _fill(reg, c, rng, 6)
            h, w = reg.shape[:2]
            for x in range(w):
                if x % 4 in (0, 1): reg[:, x, :3] = np.clip(np.array(c) - 22, 0, 255)
            if h > 2: reg[-1, :, :3] = d
        return f
    sh = ribbed(GM_SHELL, GM_SHELL_D)
    flesh = solid(GM_FLESH, 5)
    shell = Part("grandmaw_shell", 6, 128)
    shell.add(-1.0, 0.0, -0.9, 1.0, 0.25, 0.9, sh)
    shell.add(-1.1, 0.2, -1.1, 1.2, 0.6, 1.1, sh)
    shell.add(-1.15, 0.55, -1.2, 1.3, 0.75, 1.2, sh)
    for z in (-0.9, -0.45, 0.0, 0.45, 0.9): shell.add(1.25, 0.6, z - 0.15, 1.38, 0.8, z + 0.15, solid(GM_SHELL_D, 4))   # scalloped lip
    shell.add(-0.95, 0.6, -1.0, 1.1, 0.72, 1.0, flesh)                  # the soft inside
    for x in (-0.4, 0.2, 0.7): shell.add(x, 0.7, -0.9, x + 0.25, 0.78, 0.9, solid((170, 80, 100), 4))
    lid = Part("grandmaw_lid", 6, 128)
    lid.add(0.0, 0.0, -1.2, 2.3, 0.2, 1.2, sh)
    lid.add(0.1, 0.18, -1.05, 2.1, 0.45, 1.05, sh)
    lid.add(0.3, 0.42, -0.8, 1.8, 0.65, 0.8, sh)
    for z in (-0.9, -0.45, 0.0, 0.45, 0.9): lid.add(2.25, -0.05, z - 0.15, 2.38, 0.15, z + 0.15, solid(GM_SHELL_D, 4))
    lid.add(0.05, -0.04, -1.0, 2.15, 0.02, 1.0, flesh)
    for z in (-0.6, 0.0, 0.6): lid.add(0.6, 0.64, z - 0.1, 1.6, 0.75, z + 0.1, solid((150, 200, 190), 4))   # barnacles
    pearl = Part("grandmaw_pearl", 6, 32)
    rounded(pearl, -0.32, -0.32, -0.32, 0.32, 0.32, 0.32, glow(GM_PEARL), 0.12)
    return [shell, lid, pearl]


LA_SKIN, LA_DARK, LA_EYE = (22, 22, 30), (8, 8, 12), (240, 240, 240)


def lament():
    """The Lament (after Sea of Solitude's great fish): a huge black fish with pale empty eyes, a gaping mouth and
    ragged fins, dripping shadow."""
    sk, dk = solid(LA_SKIN, 6), solid(LA_DARK, 4)
    fin = cloth((34, 34, 46), trim=None, ragged=True)
    eye = glow(LA_EYE)
    h = Part("lament_head", 6, 128)
    rounded(h, -1.4, -1.2, -1.15, 1.0, 1.3, 1.15, sk, 0.4)
    rounded(h, 1.0, -0.9, -0.95, 1.9, 1.0, 0.95, sk, 0.3)
    h.add(1.9, -0.75, -0.75, 2.0, 0.35, 0.75, solid((2, 2, 4), 2))    # the gaping mouth
    for z in (-0.6, -0.3, 0.0, 0.3, 0.6):
        h.add(1.85, 0.2, z - 0.05, 1.95, 0.4, z + 0.05, solid((180, 180, 170), 3))   # little teeth
        h.add(1.85, -0.75, z - 0.05, 1.95, -0.55, z + 0.05, solid((180, 180, 170), 3))
    for s in (-1, 1):
        h.add(0.9, 0.5, s * 1.0, 1.45, 1.0, s * 1.08, eye)              # big pale eyes
        h.add(1.1, 0.65, s * 1.04, 1.3, 0.85, s * 1.1, dk)
        h.add(1.2, 0.0, s * 1.0, 1.35, 0.45, s * 1.03, solid((180, 180, 200), 3))   # a tear streak
    h.bone("whisk", (-1.0, 1.3, 0))
    h.add(-1.2, 1.3, -0.08, 0.6, 2.4, 0.08, fin)                       # dorsal
    h.bone(None)
    h.anim("whisk", "rot", (0, 0, 6), 3.2)
    seg = Part("lament_body", 6, 128)
    _seg(seg, -0.2, 2.35, 1.1, sk)
    seg.add(0.0, 1.05, -0.08, 2.0, 2.2, 0.08, fin)
    for s in (-1, 1):
        seg.bone("fin_" + ("r" if s > 0 else "l"), (1.2, -0.4, s * 1.1))
        seg.add(0.6, -0.6, s * 1.1, 1.8, -0.4, s * 2.3, fin)
        seg.bone(None)
    seg.anim("fin_l", "rot", (0, 0, 16), 2.6)
    seg.anim("fin_r", "rot", (0, 0, -16), 2.6)
    tail = Part("lament_tail", 6, 128)
    _seg(tail, -1.6, 0.2, 0.8, sk)
    tail.add(-2.3, -0.4, -0.4, -1.5, 0.4, 0.4, sk)
    tail.bone("fluke", (-2.2, 0, 0))
    tail.add(-4.0, -2.0, -0.09, -2.1, 2.0, 0.09, fin)                  # a ragged, upright tail fin
    tail.bone(None)
    tail.anim("fluke", "rot", (0, 18, 0), 2.2)
    return [h, seg, tail]


def deep_bosses():
    return scourge() + umbral() + coralia() + inkjaw() + grandmaw() + lament()


ALL_PARTS = leviathan() + sandworm() + frost_wyrm() + lich() + golem() + mimic() + locust() + ghost_captain() + aurora() + black_sun() + deep_bosses()

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
    "@": (226, 82, 104), "%": (160, 100, 255), "&": (52, 40, 86), "*": (240, 240, 255), "+": (198, 176, 150),
    "=": (120, 96, 80), "~": (40, 60, 120), "^": (90, 120, 200), "!": (178, 226, 62), ";": (96, 120, 40),
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

DEEP_SUMMONS = {
    "brine_bait": [
        "................",
        ".......xx.......",
        "......x..x......",
        "......x..x......",
        ".......xx.......",
        "........x.......",
        "......;;x;;.....",
        ".....;!!!!!;....",
        "....;!!l!!!!;...",
        "....;!!!!!l!;...",
        "....;!l!!!!!;...",
        ".....;!!!!!;..x.",
        "......;;;;;..x..",
        "........x...x...",
        ".........xxx....",
        "................",
    ],
    "umbral_sonar": [
        "................",
        ".....&&&&&&.....",
        "....&......&....",
        "...&..&&&&..&...",
        "..&..&....&..&..",
        "..&.&..%%..&.&..",
        "..&.&.%ll%.&.&..",
        "..&.&.%ll%.&.&..",
        "..&.&..%%..&.&..",
        "..&..&....&..&..",
        "...&..&&&&..&...",
        "....&......&....",
        ".....&&&&&&.....",
        "......1111......",
        ".....122221.....",
        "................",
    ],
    "sirens_comb": [
        "................",
        "................",
        "...@@@@@@@@@@...",
        "..@@*@@@@@@*@@..",
        "..@@@@@@@@@@@@..",
        "..@@@@@@@@@@@@..",
        "..*.*.*.*.*.*...",
        "..*.*.*.*.*.*...",
        "..*.*.*.*.*.*...",
        "..*.*.*.*.*.*...",
        "..*...*...*.....",
        "................",
        "................",
        "................",
        "................",
        "................",
    ],
    "inkjaw_bait": [
        "................",
        "......9999......",
        ".....999999.....",
        "....99999999....",
        "....9e9999e9....",
        "....9b9999b9....",
        "....99999999....",
        ".....999999.....",
        "....9.9.9.9.9...",
        "...9..9.9..9.9..",
        "...9.9..9.9..9..",
        "..9..9.9...9....",
        "......Y.........",
        ".....YlY........",
        "......Y.........",
        "................",
    ],
    "glistening_pearl": [
        "................",
        "................",
        "..+++++++++++...",
        ".+=+=+=+=+=+=+..",
        ".+++++++++++++..",
        "..=+++++++++=...",
        "................",
        "......****......",
        ".....**l***.....",
        ".....*l*****....",
        ".....*******....",
        "..+++.****.+++..",
        ".+=+=+=+=+=+=+..",
        ".+++++++++++++..",
        "..===========...",
        "................",
    ],
    "mournful_shell": [
        "................",
        ".........^^.....",
        "........^~~^....",
        ".......^~^^~^...",
        "......^~^~~^~^..",
        ".....^~^~^^~^~..",
        "....^~^~^l^~^~..",
        "...^~^~^^^~^~^..",
        "..^~^~~~~~~^~...",
        "..^~^^^^^^^~^...",
        "..^~~~~~~~~^....",
        "...^^^^^^^^.....",
        "....~~~~~~......",
        "................",
        "................",
        "................",
    ],
}
ICONS.update(DEEP_SUMMONS)

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

def render_icon(pieces, size=16, outline=(16, 14, 20)):
    """A small picture of a 3D model for its icon: pieces = [(part, img, boxes, fn)], fn maps a local point to the
    picture's frame (+x = right on screen, +y = up, +z = away from the camera). Oblique view from the left, a little
    above; faces in their average texture colour, drawn far to near, then shrunk with a dark outline."""
    from PIL import ImageDraw
    quads = []
    for part, img, boxes, fn in pieces:
        tex = np.array(img)
        for (a, b), regs in boxes:
            p0, p1 = fn(a), fn(b)
            lo = [min(p0[i], p1[i]) for i in range(3)]; hi = [max(p0[i], p1[i]) for i in range(3)]
            cols = {}
            for key, (rx, ry, rw, rh) in regs.items():
                avg = tex[ry:ry + rh, rx:rx + rw].reshape(-1, 4)
                avg = avg[avg[:, 3] > 0]
                cols[key] = tuple(int(v) for v in avg[:, :3].mean(0)) if len(avg) else None
            side = cols.get("-z") or cols.get("+z") or (128, 128, 128)
            top = cols.get("+y") or side
            front = cols.get("+x") or side
            quads.append((lo, hi, side, top, front))
    if not quads: return Image.new("RGBA", (size, size))
    K = 0.38
    def P(x, y, z): return (x + K * z, -y - K * z)
    pts = [P(x, y, z) for lo, hi, *_ in quads for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])]
    minx, maxx = min(p[0] for p in pts), max(p[0] for p in pts)
    miny, maxy = min(p[1] for p in pts), max(p[1] for p in pts)
    big = size * 8
    pad = 1.2 * 8
    sc = (big - 2 * pad) / max(maxx - minx, maxy - miny, 1e-3)
    ox = (big - (maxx - minx) * sc) / 2 - minx * sc
    oy = (big - (maxy - miny) * sc) / 2 - miny * sc
    im = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    def S(x, y, z): q = P(x, y, z); return (q[0] * sc + ox, q[1] * sc + oy)
    def sh(c, k): return tuple(int(max(0, min(255, v * k))) for v in c) + (255,)
    for lo, hi, side, top, front in sorted(quads, key=lambda q: (-(q[0][2] + q[1][2]), q[0][1])):
        x0, y0, z0 = lo; x1, y1, z1 = hi
        d.polygon([S(x0, y1, z0), S(x1, y1, z0), S(x1, y1, z1), S(x0, y1, z1)], fill=sh(top, 1.15))
        d.polygon([S(x1, y0, z0), S(x1, y1, z0), S(x1, y1, z1), S(x1, y0, z1)], fill=sh(front, 0.85))
        d.polygon([S(x0, y0, z0), S(x1, y0, z0), S(x1, y1, z0), S(x0, y1, z0)], fill=sh(side, 1.0))
    small = im.resize((size, size), Image.BOX)
    a = np.array(small).astype(float)
    rgb, al = a[..., :3], a[..., 3]
    mask = al > 90
    out = np.zeros((size, size, 4), np.uint8)
    out[mask, :3] = np.clip(rgb[mask] / np.maximum(al[mask, None] / 255, 1e-3), 0, 255)
    out[mask, 3] = 255
    ring = np.zeros_like(mask)
    for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        ring |= np.roll(np.roll(mask, dy, 0), dx, 1)
    ring &= ~mask
    out[ring, :3] = outline; out[ring, 3] = 255
    return Image.fromarray(out, "RGBA")


DEEP_ICON_FOR = {"scourge": "brine_scourge", "umbral": "umbral_leviathan", "coralia": "coralia", "inkjaw": "old_inkjaw",
                 "grandmaw": "grandmaw_clam", "lament": "the_lament"}


def deep_boss_icons(by, size=16):
    """Index icons for the Dark Ocean's bosses, drawn from their own models."""
    def at(name, dx=0, dy=0, dz=0, rot=None, s=1.0):
        part, img, boxes = by[name]
        def fn(p):
            x, y, z = p[0] * s, p[1] * s, p[2] * s
            if rot == "down": x, y = y, -x
            elif rot == "up": x, y = -y, x
            return (x + dx, y + dy, z + dz)
        return (part, img, boxes, fn)
    def serpent(pre, gap, n):
        ps = [at(pre + "_head")]
        for i in range(n): ps.append(at(pre + "_body", -gap * (i + 1), math.sin(i * 1.3) * 0.5, 0, s=1 - 0.05 * i))
        ps.append(at(pre + "_tail", -gap * (n + 1), math.sin(n * 1.3) * 0.5, 0, s=1 - 0.05 * n))
        return ps
    out = {"brine_scourge": render_icon(size=size, pieces=serpent("scourge", 1.95, 3)), "umbral_leviathan": render_icon(size=size, pieces=serpent("umbral", 2.05, 3)),
           "the_lament": render_icon(size=size, pieces=serpent("lament", 2.3, 2))}
    out["coralia"] = render_icon(size=size, pieces=[at("coralia_tail"), at("coralia_torso"), at("coralia_head", 0, 1.25, 0),
                                  at("coralia_arm", 0, 1.0, -0.55), at("coralia_arm", 0, 1.0, 0.55)])
    ink = [at("inkjaw_body")]
    for j, (x, z) in enumerate(((-0.5, -0.6), (0.5, -0.6), (-0.5, 0.6), (0.5, 0.6), (0, 0))):
        for sgi in range(3):
            ink.append(at("inkjaw_tentacle", x + (sgi * 0.25 if j % 2 else -sgi * 0.25), -0.6 - sgi * 1.15, z, rot="down", s=1 - sgi * 0.22))
    out["old_inkjaw"] = render_icon(ink, size)
    out["grandmaw_clam"] = render_icon(size=size, pieces=[at("grandmaw_shell"), at("grandmaw_lid", -1.0, 0.75, 0, rot="up"), at("grandmaw_pearl", 0.1, 0.75, 0)])
    return out


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
        "lich_staff", "glacial_fang", "abyssal_lure", "sandworm_drum", "lich_phylactery", "frozen_horn"] + list(DEEP_SUMMONS)


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
    prebuilt = [build(part, 1000 + i) for i, part in enumerate(ALL_PARTS)]
    icons.update(deep_boss_icons({part.name: (part, img, boxes) for part, (img, boxes) in zip(ALL_PARTS, prebuilt)}))
    icon_for.update(DEEP_ICON_FOR)
    for i, part in enumerate(ALL_PARTS):
        img, boxes = prebuilt[i]
        built.append((part, img, boxes))
        model, S, off = java_model(part, boxes)
        meta[part.name] = (S, off)
        if not part.bedrock_only:
            p = os.path.join(A, "textures/item/wild", part.name + ".png"); os.makedirs(os.path.dirname(p), exist_ok=True); img.save(p)
            jw(os.path.join(A, "models/item/wild", part.name + ".json"), model)
            jw(os.path.join(A, "items/wild", part.name + ".json"), {"model": {"type": "minecraft:model", "model": f"{NS}:item/wild/{part.name}"}})
        if part.java_only: continue
        t = os.path.join(rp, "textures/faultline/wild", part.name + ".png"); os.makedirs(os.path.dirname(t), exist_ok=True); img.save(t)
        jw(os.path.join(rp, "models/entity/faultline", f"wild_{part.name}.geo.json"), bedrock_geo(part, boxes))
        desc = {"identifier": f"faultline:wild_{part.name}", "materials": {"default": "armor", "enchanted": "armor_enchanted"},
                "textures": {"default": f"textures/faultline/wild/{part.name}", "enchanted": "textures/misc/enchanted_actor_glint"},
                "geometry": {"default": f"geometry.faultline.wild_{part.name}"}, "scripts": {"parent_setup": "v.helmet_layer_visible = 0.0;"},
                "render_controllers": ["controller.render.armor"]}
        if part.anims:
            an = bedrock_anim(part)
            jw(os.path.join(rp, "animations", f"faultline.wild_{part.name}.animation.json"), an)
            short = {f"a{i}": k for i, k in enumerate(an["animations"])}
            desc["animations"] = short
            desc["scripts"]["animate"] = list(short)
        jw(os.path.join(rp, "attachables", f"faultline.wild_{part.name}.json"), {"format_version": "1.10.0", "minecraft:attachable": {"description": desc}})
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
    jo = ", ".join(f'"{p.name}"' for p in ALL_PARTS if p.java_only)
    bo = ", ".join(f'"{p.name}"' for p in ALL_PARTS if p.bedrock_only)
    lines.append(f"        JAVA_ONLY.addAll(java.util.List.of({jo}));")
    lines.append(f"        BEDROCK_ONLY.addAll(java.util.List.of({bo}));")
    for pkg, plug in (("net.faultlinesmp.bosses", "FaultlineBosses"), ("net.faultlinesmp.items", "FaultlineItems"), ("net.faultlinesmp.ships", "FaultlineShips")):
        path = os.path.join(ROOT, "plugins", plug, "src/main/java", pkg.replace(".", "/"), "WildParts.java")
        with open(path, "w") as f:
            f.write(f"package {pkg};\n\n" + WILD_JAVA.replace("%LINES%", "\n".join(lines)))
    print(f"{len(ALL_PARTS)} parts, {len(FLAT)} flat items, {len(DISCS)} discs, {len(TROPHY_BOSSES)} trophies")
    if prev:
        os.makedirs(prev, exist_ok=True)
        preview(built, os.path.join(prev, "wild_models.png"))
        big = deep_boss_icons({p.name: (p, im, bx) for p, im, bx in built}, 300)
        sheet = Image.new("RGBA", (3 * 310, 2 * 330), (24, 34, 52, 255))
        from PIL import ImageDraw
        dr = ImageDraw.Draw(sheet)
        for i, (k, im) in enumerate(big.items()):
            sheet.alpha_composite(im, ((i % 3) * 310 + 5, (i // 3) * 330 + 5)); dr.text(((i % 3) * 310 + 10, (i // 3) * 330 + 310), k, fill=(230, 230, 230))
        sheet.save(os.path.join(prev, "deep_bosses.png"))
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
    /** Parts only Java draws (an ItemDisplay, no Bedrock stand) and only Bedrock draws (a stand, no display). */
    static final java.util.Set<String> JAVA_ONLY = new java.util.HashSet<>(), BEDROCK_ONLY = new java.util.HashSet<>();
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
