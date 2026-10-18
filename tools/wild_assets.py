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
        "bones": [{"name": "body", "pivot": bed_pt(part.pivots["body"]) if "body" in part.pivots else [0, 24, 0], "cubes": cubes.get(None, [])}]
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
            if bn in getattr(part, "half", ()): w = (w + 1) / 2   # a jaw / lid: opens and shuts, never swings through
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
# (Bosses 1.6.x, DeepBosses.java). Serpents: the head's origin is the boss's position (front +x); a body segment's
# origin is the BACK joint and it reaches forward to the next one (~1.9-2.3 blocks); the tail's origin is the last joint
# and it trails back (-x). v2 (1.6.3): far more detailed models and hand-made textures.

def _c(c, d):
    return tuple(int(max(0, min(255, v + d))) for v in c)


def _mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def grad(top, bottom, n=5, spots=None, dens=0.0):
    """Side faces fade top -> bottom; the top face is `top`, the bottom face `bottom`; optional spots."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        for y in range(h):
            t = 0 if face == "up" else 1 if face == "down" else y / max(1, h - 1)
            c = _mix(top, bottom, t)
            for x in range(w):
                reg[y, x, :3] = np.clip(np.array(c) + rng.uniform(-n, n), 0, 255)
        reg[:, :, 3] = 255
        if spots:
            for y in range(h):
                for x in range(w):
                    if rng.random() < dens: reg[y, x, :3] = spots
    return f


def aq_scales(base, light, green, dark):
    """The Aquatic Scourge's hide: teal scales in a diamond lattice, turquoise and green mottling inside each diamond,
    navy seams, a darker belly and a brighter back."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        for y in range(h):
            for x in range(w):
                shade = 1.0 if face == "up" else 0.62 if face == "down" else 1.0 - 0.35 * (y / max(1, h - 1))
                c = np.array(base) * shade
                u, v = (x + y) % 6, (x - y) % 6
                if u == 0 or v == 0: c = np.array(dark) * (0.8 + 0.2 * shade)
                elif (u in (2, 3) and v in (2, 3)):
                    c = np.array(green if rng.random() < 0.45 else light) * shade
                elif rng.random() < 0.18: c = np.array(light) * shade
                reg[y, x, :3] = np.clip(c + rng.uniform(-6, 6), 0, 255)
        reg[:, :, 3] = 255
    return f


def bone_curve(c=(222, 206, 172), tip=(60, 54, 80)):
    """Bone along the length, darkening toward the tip end (+x of the region), with growth rings."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        for y in range(h):
            for x in range(w):
                t = x / max(1, w - 1)
                col = np.array(_mix(c, tip, max(0, (t - 0.55) / 0.45)))
                if x % 4 == 3: col = col * 0.85
                if face not in ("up",) and y == h - 1: col = col * 0.8
                reg[y, x, :3] = np.clip(col + rng.uniform(-5, 5), 0, 255)
        reg[:, :, 3] = 255
    return f


def glow_lines(base, glow, every=5, dots=0.0):
    """A black hide with thin glowing lines running along it (and a few glowing dots)."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        _fill(reg, base, rng, 4)
        for y in range(h):
            for x in range(w):
                if face in ("north", "south", "east", "west") and y % every == every // 2 and (x // 3) % 3 != 2:
                    reg[y, x, :3] = glow
                elif face == "up" and x % every == every // 2:
                    reg[y, x, :3] = _c(glow, -40)
                elif rng.random() < dots:
                    reg[y, x, :3] = glow
    return f


def skin(c, blush=None):
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        for y in range(h):
            for x in range(w):
                col = np.array(_c(c, -14 * (y / max(1, h - 1)) if face not in ("up", "down") else (8 if face == "up" else -18)))
                reg[y, x, :3] = np.clip(col + rng.uniform(-3, 3), 0, 255)
        reg[:, :, 3] = 255
        if blush and face == "east" and h > 3 and w > 4:
            for x in (1, w - 2): reg[h // 2, x, :3] = blush
    return f


def hair(c, hi):
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        for x in range(w):
            strand = rng.random()
            for y in range(h):
                col = _c(c, (strand - 0.5) * 40 - 10 * (y / max(1, h - 1)))
                if (x + y // 3) % 5 == 0: col = hi
                reg[y, x, :3] = np.clip(np.array(col) + rng.uniform(-4, 4), 0, 255)
        reg[:, :, 3] = 255
    return f


def ribbed(c, band, rib):
    """A clam shell: concentric growth bands across, raised radial ribs along."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        for y in range(h):
            for x in range(w):
                col = np.array(c)
                if (x + (y // 2)) % 5 in (0, 1): col = np.array(rib)
                if y % 4 == 3: col = np.array(band) * 0.9
                if face == "down": col = col * 0.75
                reg[y, x, :3] = np.clip(col + rng.uniform(-6, 6), 0, 255)
        reg[:, :, 3] = 255
    return f


def mantle(c1, c2, dot):
    """A giant clam's mantle: wavy bands of two colours with bright dots (real giant clams are blue/violet/green)."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        for y in range(h):
            for x in range(w):
                t = (math.sin(x * 0.7 + math.sin(y * 0.9) * 1.5) + 1) / 2
                col = np.array(_mix(c1, c2, t))
                if rng.random() < 0.07: col = np.array(dot)
                reg[y, x, :3] = np.clip(col + rng.uniform(-5, 5), 0, 255)
        reg[:, :, 3] = 255
    return f


def suckers(c, ring, inner):
    """An octopus arm: the top shaded and spotted, the underside rows of pale suckers."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        _fill(reg, c, rng, 6)
        if face == "down":
            for y in range(h):
                for x in range(w):
                    if (x % 3, y % 3) in ((1, 0), (1, 2), (0, 1), (2, 1)): reg[y, x, :3] = ring
                    elif (x % 3, y % 3) == (1, 1): reg[y, x, :3] = inner
        else:
            for _ in range(max(1, w * h // 9)):
                reg[rng.randrange(h), rng.randrange(w), :3] = _c(c, -35)
    return f


def eye_paint(white, iris, pupil, slit=False):
    """A big eye on the east (+x) face... and the side faces: a white with a ringed iris and a pupil (round or a slit)."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        _fill(reg, white, rng, 3)
        cy, cx = (h - 1) / 2, (w - 1) / 2
        r = min(h, w) / 2
        for y in range(h):
            for x in range(w):
                d = math.hypot((y - cy) / max(1, r), (x - cx) / max(1, r))
                if d < 0.75: reg[y, x, :3] = iris if d > 0.35 else iris
                if (slit and abs(x - cx) < max(0.6, w * 0.08) and d < 0.75) or (not slit and d < 0.32): reg[y, x, :3] = pupil
                if d < 0.75 and 0.55 < d: reg[y, x, :3] = _c(iris, -40)
        if h > 3 and w > 3: reg[1, 1, :3] = (255, 255, 255)
    return f


def arc_boxes(p, pts, w0, w1, h0, h1, paint, ys=None):
    """Boxes along a polyline in the x-z plane (pts = [(x, z)...]), tapering from w0/h0 to w1/h1 (ys = y per point)."""
    n = len(pts) - 1
    for i in range(n):
        (xa, za), (xb, zb) = pts[i], pts[i + 1]
        t = i / max(1, n - 1)
        wd, ht = w0 + (w1 - w0) * t, h0 + (h1 - h0) * t
        ya = ys[i] if ys else 0.0
        yb = ys[i + 1] if ys else 0.0
        yc = (ya + yb) / 2
        p.add(min(xa, xb) - wd * 0.3, yc - ht / 2, min(za, zb) - wd / 2, max(xa, xb) + wd * 0.3, yc + ht / 2, max(za, zb) + wd / 2, paint)
    return p


# ---------------------------------------------------------------- THE BRINE SCOURGE (Calamity's Aquatic Scourge look)
AQ_TEAL, AQ_LIGHT, AQ_GREEN, AQ_NAVY, AQ_BLUE = (36, 132, 150), (70, 196, 186), (104, 200, 110), (22, 34, 84), (42, 84, 150)
AQ_BONE, AQ_BONE_D = (226, 210, 174), (150, 128, 96)
SC_HINGE = (0.55, 0.0, 1.0)   # the mandibles hinge here (z mirrored for the left one); DeepBosses.Scourge.pose matches


def _scourge_mandible(side):
    """One great crescent mandible (side -1 = left/-z), in the head's frame: out from the cheek, forward, then curling
    inward so the pair almost closes into a ring in front of the head, serrated along the inside."""
    s = side
    pts = [(0.55, 1.0), (1.3, 1.7), (2.25, 2.0), (3.2, 1.85), (3.95, 1.3), (4.35, 0.6), (4.4, 0.15)]
    pts = [(x, z * s) for x, z in pts]
    m = Part("tmp")
    arc_boxes(m, pts, 0.55, 0.16, 0.5, 0.2, bone_curve(AQ_BONE, (54, 46, 70)), ys=[0, 0.05, 0.1, 0.12, 0.1, 0.06, 0.04])
    for (x, z) in pts[2:6]:  # serrations on the inner edge
        m.add(x - 0.08, -0.08, z * 1 - s * 0.38 - 0.08, x + 0.08, 0.08, z - s * 0.38 + 0.08, solid(AQ_BONE_D, 4))
    m.add(0.4, -0.3, s * 0.85 - 0.3, 0.8, 0.3, s * 0.85 + 0.3, solid(AQ_NAVY, 4))  # the socket
    return m


def scourge():
    parts = _scourge_set("", aq_scales(AQ_TEAL, AQ_LIGHT, AQ_GREEN, AQ_NAVY), aq_scales(AQ_LIGHT, AQ_GREEN, AQ_GREEN, AQ_NAVY),
                         solid(AQ_NAVY, 5), solid(AQ_BLUE, 6), bone_curve(AQ_BONE, (60, 50, 74)), aq_scales(AQ_BLUE, AQ_TEAL, AQ_LIGHT, AQ_NAVY), 1.0)
    # phase 3, THE MOLT: the armour is gone. Raw pink-red flesh with glowing lime veins, the spikes snapped off to stubs.
    flesh = glow_lines((150, 36, 64), (170, 255, 60), 5, 0.03)
    molt = _scourge_set("_molt", flesh, glow_lines((170, 52, 80), (190, 255, 80), 4, 0.04), solid((70, 16, 34), 5), solid((110, 28, 52), 6),
                        bone_curve((214, 196, 160), (120, 60, 70)), glow_lines((120, 30, 56), (170, 255, 60), 4, 0.03), 0.35)
    return parts + molt


def _scourge_set(sfx, sc, crown_sc, navy, blue, bone, tail_sc, spike):
    """The Scourge's head / Bedrock head / body segment / tail with these paints; spike = how much of its spikes are left."""
    # ---- head (no mandibles: Java poses them separately)
    hd = Part("tmp")
    rounded(hd, -1.4, -0.7, -1.25, 1.35, 0.75, 1.25, sc, 0.35)            # the broad flat skull
    rounded(hd, 1.35, -0.55, -0.95, 2.15, 0.55, 0.95, sc, 0.25)           # the snout
    hd.add(-1.2, 0.75, -0.85, 1.2, 0.95, 0.85, crown_sc)   # the crown plate
    hd.add(-1.45, 0.72, -1.05, -1.25, 0.9, 1.05, navy)
    hd.add(2.15, -0.42, -0.72, 2.25, 0.4, 0.72, solid((14, 18, 30), 3))   # the maw
    for z in (-0.55, -0.3, -0.05, 0.2, 0.45):
        hd.add(2.12, 0.18, z, 2.27, 0.38, z + 0.1, solid(AQ_BONE, 3))       # teeth, top
        hd.add(2.12, -0.4, z + 0.05, 2.27, -0.22, z + 0.15, solid(AQ_BONE, 3))
    for s in (-1, 1):
        hd.add(1.55, 0.25, s * 0.9 - 0.12, 1.9, 0.45, s * 0.9 + 0.12, glow((200, 240, 90) if spike >= 1 else (255, 250, 120)))   # small glowing eyes
        hd.add(1.4, 0.45, s * 0.75 - 0.2, 2.0, 0.6, s * 0.75 + 0.2, navy)                         # brow ridge
        hd.add(-1.0, 0.1, s * 1.2, 0.9, 0.5, s * 1.32, navy)                                      # cheek seam
        hs = [(-0.6, s * 1.15), (-1.0, s * 1.65), (-1.6, s * 1.95), (-2.2, s * 2.05)][:max(2, int(round(4 * spike)))]
        arc_boxes(hd, hs, 0.34, 0.14, 0.3, 0.14, bone, ys=[0.35, 0.45, 0.55, 0.6][:len(hs)])     # the head's own spikes
    for x in (-0.9, -0.2, 0.5):
        hd.add(x - 0.1, 0.95, -0.12, x + 0.1, 1.25 - abs(x) * 0.15, 0.12, solid(AQ_BONE_D, 4))  # crown nubs
    h = Part("scourge_head" + sfx, 10, 256, java_only=True)
    h.take(hd)
    mand = _scourge_mandible(-1)
    ml = Part("scourge_mandible", 10, 128, java_only=True)
    ml.take(mand, -SC_HINGE[0], 0, SC_HINGE[2])
    mr = ml.mirror_z("scourge_mandible_r")
    hb = Part("scourge_head_b" + sfx, 10, 256, bedrock_only=True)
    hb.take(hd)
    hb.bone("mand_l", (SC_HINGE[0], 0, -SC_HINGE[2])); hb.take(mand, bone="mand_l")
    hb.bone("mand_r", (SC_HINGE[0], 0, SC_HINGE[2])); hb.take(_scourge_mandible(1), bone="mand_r")
    hb.bone(None)
    hb.anim("mand_l", "rot", (0, 14, 0), 1.6)
    hb.anim("mand_r", "rot", (0, -14, 0), 1.6)
    # ---- a body segment
    seg = Part("scourge_body" + sfx, 10, 256)
    rounded(seg, -0.25, -0.82, -1.05, 2.0, 0.85, 1.05, sc, 0.3)
    seg.add(1.72, -0.92, -1.14, 2.02, 0.94, 1.14, navy)                    # the overlapping plate lip
    seg.add(0.15, 0.84, -0.42, 1.55, 0.98, 0.42, crown_sc)   # the bright back stripe
    seg.add(0.0, -0.9, -0.7, 1.7, -0.78, 0.7, grad(_c(AQ_BLUE, 10), AQ_NAVY))                         # the belly
    for s in (-1, 1):
        seg.bone("spike_" + ("r" if s > 0 else "l"), (1.25, 0.3, s * 1.0))
        sp = [(1.3, s * 0.95), (1.0, s * 1.55), (0.55, s * 2.05), (0.0, s * 2.4), (-0.45, s * 2.55)][:max(2, int(round(5 * spike)))]
        arc_boxes(seg, sp, 0.38, 0.12 if spike >= 1 else 0.3, 0.34, 0.12 if spike >= 1 else 0.28, bone, ys=[0.3, 0.42, 0.55, 0.65, 0.7][:len(sp)])   # the great side spike (a stub, molted)
        seg.bone(None)
        seg.add(0.9, -0.4, s * 1.02, 1.3, -0.15, s * 1.25, solid(AQ_BONE_D, 4))   # a small lower spur
        for x in (0.2, 0.8, 1.4): seg.add(x, 0.05, s * 1.04, x + 0.22, 0.3, s * 1.08, glow((150, 230, 120)))   # glowing pores
    seg.anim("spike_l", "rot", (0, 0, 5), 2.2)
    seg.anim("spike_r", "rot", (0, 0, -5), 2.2)
    # ---- the tail
    tail = Part("scourge_tail" + sfx, 10, 256)
    rounded(tail, -1.7, -0.7, -0.85, 0.25, 0.72, 0.85, sc, 0.26)
    rounded(tail, -2.9, -0.48, -0.58, -1.6, 0.5, 0.58, tail_sc, 0.18)
    tail.add(-3.9, -0.3, -0.36, -2.8, 0.32, 0.36, blue)
    tail.add(-4.7, -0.16, -0.18, -3.85, 0.18, 0.18, navy)
    tail.add(-5.3, -0.07, -0.07, -4.65, 0.08, 0.07, solid((14, 20, 50), 3))  # the dark point
    for s in (-1, 1):
        arc_boxes(tail, [(-0.3, s * 0.82), (-0.6, s * 1.3), (-1.05, s * 1.62)], 0.3, 0.12, 0.26, 0.1, bone, ys=[0.25, 0.32, 0.38])
        arc_boxes(tail, [(-1.9, s * 0.56), (-2.15, s * 0.9), (-2.5, s * 1.08)], 0.2, 0.08, 0.18, 0.08, bone, ys=[0.2, 0.24, 0.28])
    tail.bone_all("tail", (0, 0, 0))
    tail.anim("tail", "rot", (0, 10, 0), 1.8)
    return [h, ml, mr, hb, seg, tail] if not sfx else [h, hb, seg, tail]


# ---------------------------------------------------------------- THE UMBRAL LEVIATHAN (after Below Zero's Shadow Leviathan)
# A dark slate-navy body; a great flat wedge of a head that rises to a sail-like crest; three pairs of red eyes down its
# cheek; two huge black hooked claws at the jaw and more curling under the throat; a glowing CYAN comb of rib-teeth along
# its underside; rows of long black leg-spikes raking down and back along its belly; a line of red dots down its back to
# a flattened, finned tail with a red tip.
UM_SKIN, UM_DARK, UM_BELLY, UM_RED, UM_CYAN, UM_CLAW = (34, 44, 64), (16, 20, 30), (54, 70, 96), (230, 46, 50), (90, 220, 255), (22, 22, 26)
UM_HINGE = (1.8, 0.0, 0.55)


def tube(m, pts, r0, r1, paint):
    """A continuous curved limb through 3D points: small cubes, closer than their own size, tapering from r0 to r1."""
    total = sum(math.dist(pts[i], pts[i + 1]) for i in range(len(pts) - 1)) or 1
    run = 0.0
    for i in range(len(pts) - 1):
        a, b = pts[i], pts[i + 1]
        seg = math.dist(a, b)
        n = max(1, int(seg / (min(r0, r1) * 1.1 + 0.02)) + 1)
        for j in range(n):
            t = j / n
            q = [a[k] + (b[k] - a[k]) * t for k in range(3)]
            r = r0 + (r1 - r0) * min(1, (run + seg * t) / total)
            m.add(q[0] - r, q[1] - r, q[2] - r, q[0] + r, q[1] + r, q[2] + r, paint)
        run += seg
    return m


def _um_claw(m, x, y, z, s, length, curl, w0, paint, tip):
    """A hooked black claw from (x, y, z) on side s: forward, down, then curling back up at the point."""
    pts = []
    n = 10
    for i in range(n + 1):
        t = i / n
        pts.append((x + math.sin(t * curl) * length * 0.7, y - (1 - math.cos(t * curl)) * length * 0.55, z + s * 0.1 * t))
    tube(m, pts, w0 * 0.5, w0 * 0.14, paint)
    ex, ey, ez = pts[-1]
    m.add(ex - 0.05, ey - 0.05, ez - 0.05, ex + 0.07, ey + 0.08, ez + 0.05, tip)


def umbral():
    sk = glow_lines(UM_SKIN, (60, 84, 120), 6, 0.0)
    skin_d, belly = solid(UM_DARK, 4), grad(UM_BELLY, UM_SKIN)
    claw, claw_hi = bone_curve(UM_CLAW, (60, 60, 70)), solid((70, 72, 80), 3)
    cyan, red = glow(UM_CYAN), glow(UM_RED)
    fin = membrane((40, 52, 76), (70, 90, 130))
    hd = Part("tmp")
    # ---- the head: a wide flat wedge, rising in steps from the snout to a tall crest at the back
    rounded(hd, -1.3, -0.75, -0.95, 1.5, 0.55, 0.95, sk, 0.3)                    # the skull
    for i in range(7):                                                            # the wedge plate, stepping up and back
        t = i / 6
        x0, x1 = -1.4 + t * 0.5, 3.4 - t * 2.4
        hw = 1.25 - t * 0.85
        y0 = 0.45 + t * 1.55
        hd.add(x0, y0, -hw, x1, y0 + 0.3, hw, sk if i % 2 else glow_lines(UM_SKIN, (70, 96, 136), 5, 0.0))
    hd.add(1.3, 0.25, -0.9, 3.5, 0.5, 0.9, sk)                                    # the flat brim jutting forward
    hd.add(3.3, 0.28, -0.55, 3.75, 0.45, 0.55, skin_d)                           # the snout tip
    hd.add(1.4, -0.55, -0.7, 2.8, 0.25, 0.7, sk)                                  # the jaw
    hd.add(2.75, -0.5, -0.55, 2.85, 0.2, 0.55, solid((10, 12, 18), 3))            # the dark maw
    for s in (-1, 1):
        for i, (x, y, r) in enumerate(((1.95, 0.05, 0.13), (1.4, 0.12, 0.16), (0.8, 0.18, 0.2))):   # three red eyes down the cheek
            z0, z1 = sorted((s * 0.92, s * 1.0))
            hd.add(x - r - 0.06, y - r - 0.06, z0 - s * 0.02, x + r + 0.06, y + r + 0.06, z1 - s * 0.02, skin_d)   # the socket
            hd.add(x - r, y - r, z0, x + r, y + r, z1, eye_paint((40, 10, 12), UM_RED, (10, 6, 8)))
            a0, a1 = sorted((s * 0.99, s * 1.02))
            hd.add(x - r * 0.4, y - r * 0.4, a0, x + r * 0.4, y + r * 0.4, a1, red)
        hd.add(-1.1, -0.5, s * 0.95 - 0.05, 0.6, 0.4, s * 0.95 + 0.05, glow_lines(UM_SKIN, (60, 84, 120), 3, 0.0))   # cheek folds
    # the glowing cyan comb under the throat
    for i in range(9):
        x = -1.2 + i * 0.28
        hd.add(x, -0.95, -0.55, x + 0.08, -0.72, 0.55, cyan)
    hd.add(-1.25, -0.78, -0.6, 1.4, -0.72, 0.6, solid((30, 60, 80), 3))
    h = Part("umbral_head", 10, 256)
    h.take(hd)
    # the two great jaw claws and the throat claws: one bone, they flex together
    h.bone("claws", (UM_HINGE[0], -0.3, 0))
    cm = Part("tmp")
    for s in (-1, 1):
        _um_claw(cm, 2.2, -0.2, s * 0.75, s, 2.4, 2.6, 0.32, claw, claw_hi)       # the big jaw claws
        _um_claw(cm, 1.3, -0.6, s * 0.55, s, 1.5, 2.4, 0.2, claw, claw_hi)        # throat claws
        _um_claw(cm, 0.5, -0.7, s * 0.6, s, 1.3, 2.2, 0.18, claw, claw_hi)
    h.take(cm, bone="claws")
    h.bone(None)
    h.anim("claws", "rot", (0, 0, 9), 2.2)
    # ---- a body segment
    seg = Part("umbral_body", 10, 256)
    rounded(seg, -0.25, -0.8, -0.92, 2.1, 0.85, 0.92, sk, 0.32)
    seg.add(1.8, -0.9, -1.0, 2.1, 0.92, 1.0, skin_d)                              # the plate seam
    seg.add(-0.2, -0.86, -0.7, 2.0, -0.78, 0.7, belly)
    for i in range(6):                                                            # the cyan rib-comb glowing under it
        x = -0.1 + i * 0.36
        seg.add(x, -1.05, -0.62, x + 0.1, -0.8, 0.62, cyan)
    for x in (0.2, 0.9, 1.6): seg.add(x, 0.84, -0.09, x + 0.2, 0.92, 0.09, red)  # red dots down the back
    for x in (0.55, 1.25): seg.add(x - 0.06, 0.85, -0.06, x + 0.06, 1.2, 0.06, skin_d)   # small back ridges
    for side, s in (("l", -1), ("r", 1)):
        seg.bone("legs_" + side, (1.0, -0.7, s * 0.8))
        lm = Part("tmp")
        for k2, x in enumerate((0.3, 1.2)):                                       # two long black leg-spikes a side, raking back
            tube(lm, [(x + 0.2, -0.6, s * 0.8), (x, -1.0, s * 1.05), (x - 0.35, -1.5, s * 1.2), (x - 0.8, -2.0, s * 1.25), (x - 1.25, -2.45, s * 1.2)], 0.12, 0.035, claw)
        seg.take(lm, bone="legs_" + side)
        seg.bone(None)
        for x in (0.4, 1.4): seg.add(x, -0.15, s * 0.9 - 0.04, x + 0.4, 0.15, s * 0.9 + 0.04, glow_lines(UM_SKIN, (70, 96, 136), 2, 0.0))   # flank folds
    seg.anim("legs_l", "rot", (14, 0, 6), 1.6).anim("legs_r", "rot", (-14, 0, -6), 1.6, 0.5)
    # ---- the tail: long and flattened, a fin fold under it, red dots, a red tip
    tail = Part("umbral_tail", 10, 256)
    rounded(tail, -1.9, -0.6, -0.8, 0.25, 0.65, 0.8, sk, 0.24)
    rounded(tail, -3.4, -0.36, -0.7, -1.8, 0.4, 0.7, sk, 0.14)
    for x in (-0.6, -1.5, -2.4): tail.add(x, 0.6 if x > -1.8 else 0.36, -0.08, x + 0.2, 0.68 if x > -1.8 else 0.44, 0.08, red)
    tail.bone("fin", (-2.6, 0, 0))
    tail.add(-5.0, -0.18, -1.0, -3.3, 0.14, 1.0, fin)                             # the flat tail blade
    tail.add(-5.6, -0.12, -0.55, -4.9, 0.1, 0.55, fin)
    tail.add(-5.9, -0.08, -0.12, -5.5, 0.08, 0.12, red)                           # the red tip
    tail.add(-4.6, -0.6, -0.04, -2.0, -0.3, 0.04, fin)                            # the fin fold beneath
    for x in (-3.8, -4.4): tail.add(x, 0.14, -0.07, x + 0.18, 0.2, 0.07, red)
    tail.bone(None)
    tail.anim("fin", "rot", (0, 12, 0), 1.6)
    return [h, seg, tail]


# ---------------------------------------------------------------- CORALIA, SIREN OF THE SHOALS (Cala Maria look)
CO_SKIN, CO_HAIR, CO_HAIR_HI, CO_TAIL, CO_TAIL_D, CO_TAIL_L = (176, 222, 196), (40, 64, 58), (90, 140, 120), (62, 160, 150), (26, 84, 92), (140, 220, 200)
CO_GOLD, CO_FIN, CO_LIP, CO_CORAL = (238, 196, 76), (130, 230, 214), (200, 60, 96), (255, 120, 110)


def toon(c, edge=None, shade=12):
    """Cuphead style: a flat colour, a touch darker toward the bottom (no outline: rounded() overlaps two boxes, and an
    outline on both would cross the middle of each face)."""
    e = edge or _c(c, -95)
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        for y in range(h):
            col = _c(c, -shade * (y / max(1, h - 1)) if face not in ("up", "down") else (6 if face == "up" else -shade * 1.5))
            reg[y, :, :3] = col
        reg[:, :, 3] = 255
        if face not in ("up", "down") and h > 6: reg[-1, :, :3] = _c(c, -40)   # a soft shadow line at the bottom only
    return f


def curl(c, line, spot):
    """A rolled tentacle curl seen end-on: a spiral on its front/back, stripes and pink suckers round it."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        reg[:, :, 3] = 255
        cy, cx = (h - 1) / 2, (w - 1) / 2
        for y in range(h):
            for x in range(w):
                col = c
                if face in ("east", "west"):
                    d = math.hypot(y - cy, x - cx) / max(1, min(h, w) / 2)
                    a = math.atan2(y - cy, x - cx) / (2 * math.pi)
                    if int(d * 3 + a * 1.0) % 2 == 0 and d < 1: col = _c(c, -30)
                    if d > 0.92: col = line
                else:
                    if y == 0 or y == h - 1: col = line
                    elif rng.random() < 0.09: col = spot
                reg[y, x, :3] = col
    return f


def button(c, line):
    """A bun with a button's X on its face (Cala Maria's hair buns)."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        reg[:, :, 3] = 255
        for y in range(h):
            for x in range(w):
                col = _c(c, -10 * (y / max(1, h - 1)))
                if face in ("east", "west", "north", "south") and h > 4 and w > 4:
                    m = min(h, w)
                    yy, xx = y - (h - m) / 2, x - (w - m) / 2
                    if 0 <= yy < m and 0 <= xx < m:
                        d = math.hypot(yy - (m - 1) / 2, xx - (m - 1) / 2) / (m / 2)
                        if 0.62 < d < 0.8: col = line
                        if d < 0.55 and (abs(yy - xx) < 0.8 or abs(yy + xx - (m - 1)) < 0.8): col = line
                if y == 0 or y == h - 1 or x == 0 or x == w - 1: col = _c(c, -80)
                reg[y, x, :3] = col
    return f


def shell_paint(c, ridge):
    """A scallop shell: ribs fanning up, a darker rim."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        reg[:, :, 3] = 255
        for y in range(h):
            for x in range(w):
                col = _c(c, 10 - 25 * (y / max(1, h - 1)))
                if face in ("east", "west", "north", "south") and x % 3 == 1: col = ridge
                if y == 0 or y == h - 1 or x == 0 or x == w - 1: col = _c(ridge, -50)
                reg[y, x, :3] = col
    return f


def fish_tail(c, mark, skin_c=None, scallop=0):
    """A mermaid tail: little U-shaped scale marks; with skin_c, the top rows are her skin in scallops over the green."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        reg[:, :, 3] = 255
        for y in range(h):
            for x in range(w):
                col = _c(c, -18 * (y / max(1, h - 1)) if face not in ("up", "down") else 0)
                if face not in ("up", "down") and (y % 4 == 3) and (x + (y // 4) * 2) % 4 in (1, 2): col = mark
                if skin_c and face not in ("up", "down"):
                    edge = scallop + (1.5 if (x % 4) in (1, 2) else 0)
                    if y < edge: col = skin_c
                    elif y < edge + 1: col = _c(skin_c, -90)
                if skin_c and face == "up": col = skin_c
                if face not in ("up", "down") and (x == 0 or x == w - 1): col = _c(c, -90)
                reg[y, x, :3] = col
    return f


def snake_paint(c, belly, line):
    """Snake hair: purple scales in bands, a pale belly stripe, dark outlines."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        reg[:, :, 3] = 255
        for y in range(h):
            for x in range(w):
                col = _c(c, -12 * (y / max(1, h - 1)))
                if face == "down": col = belly
                elif (x + y) % 5 == 0: col = _c(c, -28)
                if y == 0 or y == h - 1 or x == 0 or x == w - 1: col = line
                reg[y, x, :3] = col
    return f


def leaf_paint(c, vein):
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        reg[:, :, 3] = 255
        for y in range(h):
            for x in range(w):
                col = _c(c, -15 * (y / max(1, h - 1)))
                if abs(x - (w - 1) / 2) < 0.6 or (y + abs(x - w // 2)) % 4 == 0: col = vein
                if y == h - 1 or x == 0 or x == w - 1: col = _c(vein, -40)
                reg[y, x, :3] = col
    return f


# CORALIA = Cala Maria: lavender skin, purple octopus-curl hair with two button buns, mint eyes under green eyeshadow, pink
# lips, a blue scallop-shell top and a green tail; phase 3 she turns into her MEDUSA form (green skin, a nest of purple
# snakes, yellow eyes, a mouth full of fangs, a leafy top, claws). The *_medusa parts are swapped in at phase 3.
CM_SKIN, CM_HAIR, CM_CURL, CM_SPOT, CM_LINE = (242, 228, 250), (152, 104, 200), (204, 150, 232), (244, 158, 206), (64, 30, 82)
CM_IRIS, CM_SHADOW, CM_LIP, CM_BRA, CM_TAIL, CM_TAIL_M = (150, 226, 206), (64, 186, 146), (240, 156, 196), (150, 212, 246), (38, 170, 140), (22, 118, 96)
MD_SKIN, MD_EYE, MD_SNAKE, MD_BELLY, MD_LEAF = (196, 234, 204), (250, 226, 64), (150, 100, 202), (214, 176, 238), (70, 156, 84)


def _cm_face(head, sk, line, iris, shadow, brow, skin_c=None):
    """The shared face: a big round cartoon head with huge eyes."""
    rounded(head, -0.3, 0.0, -0.3, 0.32, 0.7, 0.3, sk, 0.12)
    head.add(-0.12, -0.05, -0.17, 0.26, 0.08, 0.17, sk)                       # the chin
    for s in (-1, 1):
        z0, z1 = sorted((s * 0.03, s * 0.23))
        head.add(0.31, 0.25, z0, 0.355, 0.535, z1, eye_paint((252, 252, 252), iris, (18, 16, 26), slit=iris == MD_EYE))   # big eyes
        head.add(0.31, 0.52, z0, 0.36, 0.565, z1, solid(shadow, 3))           # eyeshadow
        a0, a1 = sorted((s * 0.215, s * 0.275))
        head.add(0.32, 0.5, a0, 0.38, 0.535, a1, solid(line, 1))              # lashes flicking out
        b0, b1 = sorted((s * 0.2, s * 0.25))
        head.add(0.32, 0.55, b0, 0.38, 0.58, b1, solid(line, 1))
        i0, i1 = sorted((s * 0.04, s * 0.12))
        o0, o1 = sorted((s * 0.12, s * 0.21))
        head.add(0.33, 0.585, i0, 0.37, 0.615, i1, solid(brow, 2))           # angry brows (low inside, high outside)
        head.add(0.33, 0.615, o0, 0.37, 0.645, o1, solid(brow, 2))
    head.add(0.32, 0.22, -0.025, 0.37, 0.27, 0.025, solid(_c(skin_c or CM_SKIN, -25), 2))   # the little nose


def nacre(base):
    """Mother-of-pearl: pastel colours shifting across the face, fine sheen ridges."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        reg[:, :, 3] = 255
        for y in range(h):
            for x in range(w):
                t = math.sin(x * 0.7 + y * 0.45) * 0.5 + 0.5
                u = math.sin(x * 0.31 - y * 0.6 + 1.7) * 0.5 + 0.5
                col = (base[0] - 22 + 40 * t, base[1] - 26 + 36 * u, base[2] - 10 + 26 * (1 - t))
                if (y + x // 3) % 6 == 0: col = _c(tuple(int(v) for v in col), 22)
                reg[y, x, :3] = np.clip(col, 0, 255)
    return f


def queen_scales(c, hi, shade=-34):
    """Iridescent scales: offset rows of little arcs, a sheen on each, darker toward the bottom of the face."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        reg[:, :, 3] = 255
        for y in range(h):
            for x in range(w):
                col = _mix(c, _c(c, shade), y / max(1, h - 1)) if face not in ("up", "down") else c
                row = y // 3
                ox = (x + (row % 2) * 2) % 4
                if y % 3 == 2 and ox in (1, 2): col = _c(col, -38)
                elif y % 3 == 0 and ox in (1, 2): col = _mix(col, hi, 0.55)
                reg[y, x, :3] = col
    return f


def strands(top, bottom, sheen):
    """Long hair: a colour fading from top to bottom, strand lines, a sheen band."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        reg[:, :, 3] = 255
        for y in range(h):
            for x in range(w):
                t = y / max(1, h - 1)
                col = _mix(top, bottom, t) if face not in ("up", "down") else top
                if x % 3 == 0: col = _c(col, -26)
                elif x % 5 == 2 and int(t * 10) in (2, 3): col = _mix(col, sheen, 0.6)
                reg[y, x, :3] = col
    return f


def lace(c, vein, rim):
    """A fin like lace: a pale membrane, fine ribs fanning, a darker rim."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        reg[:, :, 3] = 255
        for y in range(h):
            for x in range(w):
                col = _mix(c, _c(c, 30), x / max(1, w - 1))
                if (x * 2 + y) % 5 == 0: col = vein
                if y == 0 or y == h - 1 or x == 0 or x == w - 1: col = rim
                reg[y, x, :3] = col
    return f


# CORALIA (1.7.0 remodel), Queen of the Mermaid Den: porcelain skin, long hair fading from aqua to violet to rose,
# a crown of living coral and pearls, a mother-of-pearl bodice strung with pearls, a gold collar with a glowing pearl, a
# tail fading from teal to sapphire to orchid, a lace fan fluke, gold armlets and a coral-and-gold trident.
# Phase 3 = her WRATH: cold sea-blue skin with glowing veins, hair turned to writhing eels, a black coral crown,
# burning magenta eyes, a dark tail with glowing stripes. The *_wrath parts are swapped in at phase 3.
QC_SKIN, QC_BLUSH, QC_LINE = (252, 226, 216), (244, 168, 176), (70, 40, 70)
QC_HAIR = ((70, 210, 214), (150, 110, 226), (246, 140, 206))
QC_TAIL = ((44, 196, 196), (60, 120, 220), (150, 90, 222), (226, 110, 200))
QC_GOLD, QC_PEARL, QC_CORAL, QC_IRIS = (246, 206, 96), (250, 246, 255), (246, 96, 122), (64, 222, 230)
QW_SKIN, QW_VEIN, QW_EYE, QW_HAIR = (122, 150, 204), (255, 80, 200), (255, 70, 150), ((40, 30, 70), (90, 40, 120))
QW_TAIL = ((30, 40, 80), (40, 30, 90), (60, 24, 90), (90, 20, 100))


def coralia():
    """Waist = origin. Joints (Java DeepBosses.Coralia uses the same numbers): neck (0, 1.25, 0); shoulders (0, 1.0, +-0.55);
    the hair hangs from the back of the head (-0.22, 0.72, 0 in the head's frame); the fluke from the tail's end
    (-1.85, -2.8, 0); the trident's grip is in the right hand (0, -1.3, 0 in the arm's frame), its shaft along +y."""
    gold = grad(QC_GOLD, _c(QC_GOLD, -50), 3, (255, 250, 220), 0.25)
    pearl = glow(QC_PEARL)
    out = []

    def tail_part(name, cols, wrath):
        t = Part(name, 14, 256)
        hi = (255, 120, 220) if wrath else (220, 255, 250)
        segs = ((-0.44, -0.95, -0.5, 0.44, 0.08, 0.5, 0.18), (-0.7, -1.85, -0.42, 0.24, -0.85, 0.42, 0.16),
                (-1.28, -2.55, -0.33, -0.4, -1.75, 0.33, 0.13), (-1.86, -3.0, -0.24, -1.15, -2.4, 0.24, 0.1))
        for i, (x0, y0, z0, x1, y1, z1, r) in enumerate(segs):
            rounded(t, x0, y0, z0, x1, y1, z1, queen_scales(cols[i], hi), r)
        t.add(-0.45, -0.06, -0.51, 0.45, 0.06, 0.51, solid(QW_VEIN, 2) if wrath else gold)     # the girdle
        for k in range(-3, 4):                                                                # pearls (shells) hanging from it
            z = k * 0.13
            t.add(0.44, -0.16 - (0.05 if k % 2 else 0), z - 0.04, 0.5, -0.06, z + 0.04, glow(QW_VEIN) if wrath else pearl)
        if wrath:                                                                             # glowing stripes down the tail
            for (x0, y0, x1, y1, z) in ((0.2, -1.7, 0.25, -0.9, 0.0), (-0.45, -2.45, -0.4, -1.8, 0.0), (-1.2, -2.9, -1.15, -2.45, 0.0)):
                t.add(x0, y0, -0.05, x1 + 0.02, y1, 0.05, glow(QW_VEIN))
        fin = lace(_c(cols[1], 40), _c(cols[1], -20), gold if False else _c(QC_GOLD, -30)) if not wrath else lace((70, 30, 100), QW_VEIN, (20, 10, 40))
        for s in (-1, 1):                                                                     # side frills
            z0, z1 = sorted((s * 0.42, s * 0.62))
            t.add(-0.4, -1.75, z0, 0.0, -1.05, z1, fin)
            z0, z1 = sorted((s * 0.33, s * 0.5))
            t.add(-1.0, -2.45, z0, -0.65, -1.95, z1, fin)
        return t

    def fluke_part(name, c, vein, rim, tips):
        """A fan fluke in its own frame: the tail goes on along -x (DeepBosses turns it 40 degrees to meet the tail's end),
        two broad lobes sweep out up-back and down-back as one crescent, ribbed, tipped with pearls."""
        f = Part(name, 16, 256)
        lc = lace(c, vein, rim)
        f.add(-0.45, -0.24, -0.08, 0.2, 0.24, 0.08, lc)                       # the stem
        for s_ in (-1, 1):
            for (x0, x1, y0, y1) in ((-0.85, -0.15, 0.05, 0.55), (-1.15, -0.45, 0.35, 0.9), (-1.45, -0.75, 0.7, 1.2),
                                     (-1.75, -1.1, 1.0, 1.45), (-2.0, -1.5, 1.25, 1.6)):
                ya, yb = sorted((s_ * y0, s_ * y1))
                f.add(x0, ya, -0.06, x1, yb, 0.06, lc)
            for (x0, x1, y) in ((-1.0, -0.2, 0.3), (-1.6, -0.7, 0.85), (-1.95, -1.3, 1.3)):   # ribs
                ya, yb = sorted((s_ * (y - 0.03), s_ * (y + 0.03)))
                f.add(x0, ya, -0.075, x1, yb, 0.075, solid(vein, 2))
            ya, yb = sorted((s_ * 1.52, s_ * 1.68))
            f.add(-2.1, ya, -0.085, -1.94, yb, 0.085, tips)                   # pearl tips
        f.add(-1.0, -0.3, -0.06, -0.4, 0.3, 0.06, lc)                         # the middle, cut back in a notch
        return f

    def torso_part(name, sk, wrath):
        t = Part(name, 22, 256)
        rounded(t, -0.24, 0.04, -0.32, 0.24, 0.55, 0.32, sk, 0.12)          # slim waist
        rounded(t, -0.28, 0.5, -0.4, 0.28, 1.0, 0.4, sk, 0.13)              # chest
        t.add(-0.28, 0.95, -0.54, 0.24, 1.1, 0.54, sk)                      # shoulders
        t.add(-0.1, 1.08, -0.12, 0.1, 1.3, 0.12, sk)                        # neck
        t.add(0.235, 0.26, -0.02, 0.255, 0.3, 0.02, solid(_c(QC_SKIN if not wrath else QW_SKIN, -70), 1))   # navel
        cup = nacre((236, 220, 246)) if not wrath else grad((40, 20, 60), (20, 10, 30), 3, QW_VEIN, 0.06)
        trim = gold if not wrath else glow(QW_VEIN)
        for s in (-1, 1):
            z0, z1 = sorted((s * 0.03, s * 0.32))
            t.add(0.26, 0.6, z0, 0.42, 0.86, z1, cup)                       # shell cups
            q0, q1 = sorted((s * 0.07, s * 0.28))
            t.add(0.29, 0.86, q0, 0.4, 0.9, q1, cup)
            t.add(0.41, 0.6, z0, 0.43, 0.63, z1, trim)                      # their gold rims
            for i in range(6):                                              # pearl strands draped from the shoulders
                zz = s * (0.48 - i * 0.075)
                yy = 1.02 - i * 0.055 - (0.02 if i % 2 else 0)
                t.add(0.24 + i * 0.005, yy - 0.03, zz - 0.03, 0.3 + i * 0.005, yy + 0.03, zz + 0.03, pearl if not wrath else glow(QW_VEIN))
        t.add(-0.29, 0.7, -0.41, 0.27, 0.76, 0.41, trim)                    # the band round her back
        t.add(-0.14, 1.04, -0.18, 0.16, 1.12, 0.18, trim)                   # the collar
        t.add(0.16, 0.98, -0.05, 0.22, 1.08, 0.05, glow((200, 255, 250)) if not wrath else glow(QW_VEIN))   # its glowing pearl
        for k in range(-2, 3):                                              # a belt of little scallops
            z = k * 0.13
            t.add(0.22, 0.04, z - 0.055, 0.27, 0.15, z + 0.055, shell_paint((250, 190, 210), (210, 120, 160)) if not wrath else solid((40, 20, 60), 3))
        if wrath:                                                           # glowing veins
            for (y0, y1, z) in ((0.1, 0.5, -0.2), (0.2, 0.55, 0.22), (0.55, 0.95, 0.35), (0.6, 0.9, -0.36)):
                t.add(0.24, y0, z - 0.012, 0.29, y1, z + 0.012, glow(QW_VEIN))
        return t

    def head_part(name, sk, wrath):
        h = Part(name, 32, 512)
        rounded(h, -0.28, 0.0, -0.28, 0.3, 0.68, 0.28, sk, 0.11)
        h.add(-0.1, -0.05, -0.15, 0.25, 0.08, 0.15, sk)                     # the chin
        iris = QW_EYE if wrath else QC_IRIS
        for s in (-1, 1):
            z0, z1 = sorted((s * 0.05, s * 0.2))
            h.add(0.29, 0.29, z0, 0.32, 0.45, z1, eye_paint((20, 10, 30) if wrath else (252, 252, 255), iris, (18, 16, 26) if not wrath else iris, slit=wrath))
            a0, a1 = sorted((s * 0.19, s * 0.25))
            h.add(0.3, 0.42, a0, 0.35, 0.46, a1, solid(QC_LINE, 1))         # lashes sweeping out
            b0, b1 = sorted((s * 0.06, s * 0.21))
            h.add(0.3, 0.5, b0, 0.34, 0.525, b1, solid((120, 70, 120) if not wrath else (20, 10, 30), 1))   # arched brows
            c0, c1 = sorted((s * 0.13, s * 0.22))
            if not wrath: h.add(0.29, 0.22, c0, 0.305, 0.28, c1, solid(QC_BLUSH, 2))   # blush
        h.add(0.3, 0.2, -0.022, 0.34, 0.27, 0.022, solid(_c(QC_SKIN if not wrath else QW_SKIN, -24), 2))   # nose
        if wrath:
            h.add(0.29, 0.08, -0.12, 0.32, 0.16, 0.12, solid((40, 10, 30), 2))    # a snarl
            for k in range(5):
                z = -0.09 + k * 0.045
                h.add(0.31, 0.12, z - 0.012, 0.34, 0.165, z + 0.012, solid((250, 245, 255), 1))   # fangs
            for (y0, y1, z) in ((0.25, 0.5, 0.25), (0.15, 0.35, -0.24)):
                h.add(0.2, y0, z - 0.012, 0.3, y1, z + 0.012, glow(QW_VEIN))
        else:
            h.add(0.29, 0.1, -0.065, 0.335, 0.16, 0.065, toon((236, 100, 130), (170, 50, 90), 4))   # coral lips
        hp = strands(QW_HAIR[0], QW_HAIR[1], QW_VEIN) if wrath else strands(QC_HAIR[0], QC_HAIR[1], (255, 255, 255))
        rounded(h, -0.32, 0.5, -0.32, 0.2, 0.84, 0.32, hp, 0.12)            # the hair on top
        h.add(0.12, 0.56, -0.3, 0.32, 0.74, 0.02, hp)                       # bangs swept to one side
        for s in (-1, 1):
            z0, z1 = sorted((s * 0.27, s * 0.35))
            h.add(-0.3, 0.05, z0, 0.22, 0.72, z1, hp)                       # framing locks
            e0, e1 = sorted((s * 0.3, s * 0.5))
            h.add(-0.2, 0.3, e0, 0.05, 0.6, e1, lace((255, 190, 220), (230, 120, 180), (200, 90, 150)) if not wrath else lace((60, 30, 90), QW_VEIN, (20, 10, 40)))   # fin ears
        # the crown: a gold band, branches of coral, pearls (wrath: black coral)
        band = gold if not wrath else solid((30, 20, 40), 3)
        h.add(-0.3, 0.8, -0.3, 0.26, 0.88, 0.3, band)
        cor = solid(QC_CORAL, 3) if not wrath else solid((24, 16, 30), 3)
        for (x, z, ht) in ((0.22, 0.0, 0.42), (0.18, -0.16, 0.3), (0.18, 0.16, 0.3), (0.05, -0.25, 0.24), (0.05, 0.25, 0.24),
                           (-0.12, -0.22, 0.2), (-0.12, 0.22, 0.2), (-0.25, 0.0, 0.22)):
            h.add(x - 0.03, 0.86, z - 0.03, x + 0.03, 0.86 + ht, z + 0.03, cor)
            h.add(x - 0.03, 0.86 + ht * 0.6, z - 0.08 if z <= 0 else z, x + 0.03, 0.86 + ht * 0.6 + 0.05, z if z <= 0 else z + 0.08, cor)   # a side branch
            h.add(x - 0.035, 0.86 + ht, z - 0.035, x + 0.035, 0.86 + ht + 0.06, z + 0.035, pearl if not wrath else glow(QW_VEIN))
        h.add(0.24, 0.82, -0.06, 0.32, 0.94, 0.06, glow((200, 255, 250)) if not wrath else glow(QW_EYE))   # the crown's great pearl
        return h

    def hair_part(name, wrath):
        """Flowing hair from the back of her head, down past her waist; the ends curl out (a bone, so Bedrock waves them)."""
        hr = Part(name, 16, 256)
        if not wrath:
            top, mid, end = QC_HAIR
            hr.add(-0.34, -1.0, -0.33, 0.0, 0.1, 0.33, strands(top, mid, (255, 255, 255)))
            for s in (-1, 1):
                z0, z1 = sorted((s * 0.2, s * 0.4))
                hr.add(-0.3, -1.3, z0, -0.02, -0.2, z1, strands(top, mid, (255, 255, 255)))
            for i in range(5):                                              # pearls woven in
                hr.add(-0.02, -0.15 - i * 0.2, -0.03 + (i % 2) * 0.06 - 0.03, 0.02, -0.09 - i * 0.2, 0.03 + (i % 2) * 0.06, pearl)
            hr.bone("ends", (-0.2, -1.0, 0))
            hr.add(-0.44, -1.9, -0.36, -0.06, -0.95, 0.36, strands(mid, end, (255, 230, 250)))
            for s in (-1, 1):                                               # curling tips
                z0, z1 = sorted((s * 0.2, s * 0.5))
                hr.add(-0.56, -2.15, z0, -0.2, -1.8, z1, strands(end, _c(end, -30), (255, 255, 255)))
            hr.bone(None)
            hr.anim("ends", "rot", (0, 0, 12), 2.2)
        else:
            sp = snake_paint((70, 40, 110), (150, 110, 200), (20, 10, 30))
            hr.add(-0.34, -0.8, -0.33, 0.0, 0.1, 0.33, sp)
            hr.bone("eels", (-0.2, -0.6, 0))
            for i, (z, ln) in enumerate(((-0.3, 1.6), (-0.1, 1.9), (0.1, 1.75), (0.3, 1.5))):   # eels for hair, glowing heads
                hr.add(-0.3 - i * 0.03, -0.6 - ln, z - 0.07, -0.16 - i * 0.03, -0.6, z + 0.07, sp)
                hr.add(-0.34, -0.72 - ln, z - 0.09, -0.12, -0.6 - ln, z + 0.09, sp)
                hr.add(-0.13, -0.68 - ln, z - 0.05, -0.1, -0.64 - ln, z + 0.05, glow(QW_VEIN))
            hr.bone(None)
            hr.anim("eels", "rot", (0, 10, 16), 1.4)
        return hr

    def arm_part(name, sk, wrath):
        a = Part(name, 16, 128)
        rounded(a, -0.1, -0.6, -0.1, 0.1, 0.05, 0.1, sk, 0.04)
        rounded(a, -0.085, -1.12, -0.085, 0.085, -0.55, 0.085, sk, 0.03)
        a.add(-0.09, -1.3, -0.1, 0.11, -1.1, 0.1, sk)                       # hand
        for z in (-0.07, -0.023, 0.023, 0.07):
            a.add(-0.01, -1.5, z - 0.02, 0.05, -1.28, z + 0.02, sk)
            if wrath: a.add(0.0, -1.62, z - 0.016, 0.045, -1.5, z + 0.016, solid((30, 10, 50), 2))   # claws
        a.add(0.02, -1.4, -0.14, 0.07, -1.2, -0.095, sk)                    # thumb
        a.add(-0.115, -0.2, -0.115, 0.115, -0.12, 0.115, gold if not wrath else glow(QW_VEIN))   # armlet
        for i in range(4):                                                  # pearls at the wrist
            a.add(-0.11 + i * 0.07, -1.12, -0.11, -0.06 + i * 0.07, -1.07, 0.11, pearl if not wrath else glow(QW_VEIN))
        a.add(-0.24, -1.05, -0.02, -0.08, -0.6, 0.02, lace((255, 190, 220), (230, 120, 180), _c(QC_GOLD, -30)) if not wrath else lace((60, 30, 90), QW_VEIN, (20, 10, 40)))   # a forearm fin
        if wrath:
            a.add(0.09, -0.55, -0.012, 0.11, -0.1, 0.012, glow(QW_VEIN))
        return a

    def trident_part(name, wrath):
        """The Den's trident: grip at the origin, shaft along +y, three barbed prongs at the top, a pearl at their root."""
        tr = Part(name, 16, 256)
        shaft = gold if not wrath else solid((30, 20, 40), 3)
        tr.add(-0.04, -0.9, -0.04, 0.04, 1.7, 0.04, shaft)
        tr.add(-0.07, -0.95, -0.07, 0.07, -0.8, 0.07, solid(QC_CORAL, 3) if not wrath else glow(QW_VEIN))   # the butt cap
        for y in (-0.3, 0.3, 0.9):                                          # coral bands
            tr.add(-0.055, y, -0.055, 0.055, y + 0.06, 0.055, solid(QC_CORAL, 3) if not wrath else glow(QW_VEIN))
        tr.add(-0.08, 1.7, -0.32, 0.08, 1.8, 0.32, shaft)                   # the crosspiece
        tr.add(-0.09, 1.66, -0.09, 0.09, 1.84, 0.09, glow((200, 255, 250)) if not wrath else glow(QW_EYE))   # the pearl
        prong = gold if not wrath else glow(QW_VEIN)
        for z, ht in ((-0.28, 0.55), (0.0, 0.75), (0.28, 0.55)):
            tr.add(-0.035, 1.8, z - 0.035, 0.035, 1.8 + ht, z + 0.035, prong)
            tr.add(-0.03, 1.8 + ht - 0.08, z - 0.03, 0.03, 1.8 + ht + 0.14, z + 0.03, prong)       # the point
            zb0, zb1 = sorted((z, z + (0.08 if z >= 0 else -0.08)))
            tr.add(-0.03, 1.8 + ht - 0.2, zb0, 0.03, 1.8 + ht - 0.12, zb1, prong)                   # a barb
        tr.add(-0.02, 1.2, 0.04, 0.02, 1.62, 0.1, solid((246, 140, 180), 3) if not wrath else solid((60, 20, 80), 3))   # a tassel
        return tr

    out += [tail_part("coralia_tail", QC_TAIL, False), fluke_part("coralia_fluke", (190, 150, 240), (150, 90, 210), _c(QC_GOLD, -30), pearl),
            torso_part("coralia_torso", toon(QC_SKIN, QC_LINE), False), head_part("coralia_head", toon(QC_SKIN, QC_LINE), False),
            hair_part("coralia_hair", False), arm_part("coralia_arm", toon(QC_SKIN, QC_LINE, 8), False), trident_part("coralia_trident", False)]
    out += [tail_part("coralia_tail_wrath", QW_TAIL, True), fluke_part("coralia_fluke_wrath", (60, 30, 100), QW_VEIN, (20, 10, 40), glow(QW_VEIN)),
            torso_part("coralia_torso_wrath", toon(QW_SKIN, QC_LINE), True), head_part("coralia_head_wrath", toon(QW_SKIN, QC_LINE), True),
            hair_part("coralia_hair_wrath", True), arm_part("coralia_arm_wrath", toon(QW_SKIN, QC_LINE, 8), True), trident_part("coralia_trident_wrath", True)]
    return out


# ---------------------------------------------------------------- OLD INKJAW (a giant octopus)
IJ_TOP, IJ_SIDE, IJ_BELLY, IJ_SPOT, IJ_EYE = (110, 30, 54), (168, 56, 74), (234, 170, 158), (70, 16, 34), (255, 196, 60)


def inkjaw():
    """The body's origin is the middle; the mantle bulges up and back; arms (8 x 3 parts) leave from under it: each
    tentacle part starts at its origin and reaches +x 1.25, suckers underneath."""
    sk = grad(IJ_SIDE, IJ_TOP, 6, IJ_SPOT, 0.05)
    body = Part("inkjaw_body", 10, 256)
    rounded(body, -0.95, -0.75, -0.95, 0.95, 0.6, 0.95, sk, 0.32)             # the head
    rounded(body, -1.9, -0.25, -0.8, 0.35, 1.45, 0.8, grad(IJ_TOP, IJ_SIDE, 6, IJ_SPOT, 0.07), 0.32)   # the mantle
    rounded(body, -2.55, 0.15, -0.6, -1.7, 1.3, 0.6, grad(IJ_TOP, IJ_SIDE, 6, IJ_SPOT, 0.07), 0.24)
    body.add(-2.75, 0.4, -0.38, -2.5, 1.05, 0.38, solid(IJ_TOP, 5))
    for s in (-1, 1):
        body.add(0.5, 0.05, s * 0.88, 1.02, 0.62, s * 1.04, eye_paint((246, 230, 190), IJ_EYE, (20, 10, 10), slit=True))   # huge slit eyes
        body.add(0.42, 0.6, s * 0.7, 1.05, 0.78, s * 1.04, solid(IJ_TOP, 4))      # the heavy lid ridge
        body.add(0.6, -0.1, s * 0.85, 0.95, 0.02, s * 1.02, solid(IJ_SPOT, 3))
        for x in (-1.5, -0.9, -0.3): body.add(x, 1.0 + (0.2 if x > -1 else 0), s * 0.72, x + 0.28, 1.25 + (0.2 if x > -1 else 0), s * 0.8, solid(IJ_BELLY, 4))   # warts
    body.add(-0.4, -0.3, -1.05, 0.15, 0.05, -0.85, solid(IJ_SIDE, 4))          # the siphon
    body.add(-0.35, -0.25, -1.15, 0.1, 0.0, -1.0, solid(IJ_SPOT, 3))
    body.add(0.3, -0.98, -0.22, 0.75, -0.68, 0.22, solid((36, 26, 30), 4))     # the beak
    body.add(0.65, -1.08, -0.13, 0.95, -0.88, 0.13, solid((230, 200, 120), 4))
    body.add(-0.95, -0.8, -0.95, 0.95, -0.68, 0.95, solid(IJ_BELLY, 4))
    for i in range(8):                                                          # the webbing between the arm roots
        a = i * math.pi / 4
        body.add(math.cos(a) * 0.7 - 0.25, -0.95, math.sin(a) * 0.7 - 0.25, math.cos(a) * 0.7 + 0.25, -0.75, math.sin(a) * 0.7 + 0.25, solid(IJ_BELLY, 4))
    body.bone_all("b")
    body.anim("b", "pos", (0, 0.6, 0), 3.0)
    t = Part("inkjaw_tentacle", 10, 128)
    arm = suckers(IJ_SIDE, IJ_BELLY, (200, 120, 120))
    rounded(t, -0.1, -0.24, -0.25, 0.7, 0.24, 0.25, arm, 0.08)
    rounded(t, 0.6, -0.2, -0.21, 1.4, 0.2, 0.21, arm, 0.07)
    for x in (0.1, 0.5, 0.9, 1.2): t.add(x, -0.28, -0.1, x + 0.16, -0.22, 0.1, solid(IJ_BELLY, 3))
    for x in (0.25, 0.75, 1.15): t.add(x, 0.18, -0.05, x + 0.12, 0.26, 0.05, solid(IJ_TOP, 3))   # stripes on the top
    return [body, t]


# ---------------------------------------------------------------- THE GRANDMAW CLAM (Calamity's Giant Clam look)
GM_SHELL, GM_BAND, GM_RIB, GM_LIP = (214, 192, 162), (160, 120, 96), (236, 220, 196), (120, 86, 72)
GM_M1, GM_M2, GM_DOT, GM_PEARL = (60, 80, 200), (130, 70, 200), (120, 240, 255), (244, 244, 255)


def grandmaw():
    """A ribbed bowl (origin = its middle, on the floor), a lid hinged at the back (its origin is the hinge, (-1, 0.75, 0)
    in the shell's frame; closed it lies flat over the bowl), and a glowing pearl inside."""
    sh = ribbed(GM_SHELL, GM_BAND, GM_RIB)
    flesh = mantle(GM_M1, GM_M2, GM_DOT)
    shell = Part("grandmaw_shell", 10, 256)
    shell.add(-0.95, 0.0, -0.85, 0.95, 0.22, 0.85, sh)
    shell.add(-1.1, 0.18, -1.1, 1.15, 0.5, 1.1, sh)
    shell.add(-1.18, 0.45, -1.22, 1.3, 0.72, 1.22, sh)
    for i in range(9):                                                          # the scalloped, ribbed rim (raised ribs)
        z = -1.1 + i * 0.275
        top = 0.86 if i % 2 == 0 else 0.76
        shell.add(1.22, 0.45, z - 0.12, 1.42, top, z + 0.12, solid(GM_LIP if i % 2 else GM_RIB, 4))
        shell.add(-0.9, 0.0, z - 0.06, 1.25, 0.08, z + 0.06, solid(GM_BAND, 3))
    for s in (-1, 1):
        for i in range(4):
            x = -0.9 + i * 0.6
            shell.add(x - 0.12, 0.45, s * 1.22, x + 0.12, 0.8 - (i % 2) * 0.08, s * 1.32, solid(GM_LIP, 4))
    shell.add(-0.95, 0.62, -1.02, 1.12, 0.74, 1.02, flesh)                     # the living mantle
    for z in (-0.8, -0.4, 0.0, 0.4, 0.8): shell.add(1.05, 0.66, z - 0.15, 1.22, 0.8, z + 0.15, mantle(GM_M2, GM_M1, GM_DOT))   # wavy lips
    shell.add(-0.2, 0.7, -0.4, 0.4, 0.78, 0.4, solid((200, 120, 160), 4))      # the pearl's bed
    lid = Part("grandmaw_lid", 10, 256)
    lid.add(0.0, 0.0, -1.22, 2.35, 0.2, 1.22, sh)
    lid.add(0.1, 0.18, -1.08, 2.15, 0.46, 1.08, sh)
    lid.add(0.3, 0.42, -0.85, 1.85, 0.68, 0.85, sh)
    lid.add(0.6, 0.64, -0.55, 1.4, 0.82, 0.55, sh)
    for i in range(9):
        z = -1.1 + i * 0.275
        lid.add(2.3, -0.08 if i % 2 == 0 else 0.0, z - 0.12, 2.48, 0.22, z + 0.12, solid(GM_LIP if i % 2 else GM_RIB, 4))
        lid.add(0.05, 0.18, z - 0.05, 2.3, 0.24 + 0.2 * math.cos((i - 4) / 4), z + 0.05, solid(GM_RIB, 3))   # radial ribs
    lid.add(0.05, -0.04, -1.02, 2.2, 0.02, 1.02, flesh)
    for (x, z, c) in ((0.8, -0.5, (150, 200, 190)), (1.3, 0.4, (220, 120, 120)), (1.0, 0.0, (140, 190, 170)), (0.5, 0.6, (240, 170, 90))):
        lid.add(x - 0.15, 0.8, z - 0.15, x + 0.15, 1.0, z + 0.15, solid(c, 4))   # barnacles and coral growing on it
    lid.add(1.55, 0.6, -0.9, 1.7, 1.15, -0.8, solid(CO_CORAL, 4))
    lid.add(1.62, 1.0, -1.0, 1.75, 1.1, -0.7, solid(CO_CORAL, 4))
    pearl = Part("grandmaw_pearl", 10, 64)
    rounded(pearl, -0.34, -0.34, -0.34, 0.34, 0.34, 0.34, glow(GM_PEARL), 0.13)
    pearl.add(-0.2, 0.15, -0.2, 0.0, 0.3, 0.0, glow((255, 255, 255)))
    return [shell, lid, pearl]


# ---------------------------------------------------------------- THE LAMENT (Sea of Solitude's great fish look)
LA_SKIN, LA_DARK, LA_EYE, LA_CRACK = (20, 20, 28), (6, 6, 10), (246, 246, 250), (120, 140, 190)


def cracks(c, crack):
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        _fill(reg, c, rng, 4)
        for _ in range(max(1, (w + h) // 6)):
            x, y = rng.randrange(w), rng.randrange(h)
            for _ in range(rng.randrange(3, 8)):
                if 0 <= x < w and 0 <= y < h: reg[y, x, :3] = crack
                x += rng.choice((-1, 0, 1)); y += rng.choice((0, 1))
    return f


def lament():
    sk, dk = cracks(LA_SKIN, LA_CRACK), solid(LA_DARK, 3)
    fin = cloth((30, 30, 42), trim=None, ragged=True)
    eye = eye_paint(LA_EYE, (200, 210, 230), (240, 240, 250))
    h = Part("lament_head", 10, 256)
    rounded(h, -1.45, -1.25, -1.2, 1.0, 1.35, 1.2, sk, 0.45)
    rounded(h, 1.0, -0.95, -1.0, 1.95, 1.05, 1.0, sk, 0.32)
    h.add(1.95, -0.8, -0.8, 2.05, 0.4, 0.8, solid((2, 2, 4), 2))              # the gaping mouth
    for z in [i * 0.16 - 0.72 for i in range(10)]:
        h.add(1.88, 0.18, z, 2.0, 0.42, z + 0.05, solid((190, 190, 180), 3))   # rows of needle teeth
        h.add(1.88, -0.8, z + 0.07, 2.0, -0.56, z + 0.12, solid((190, 190, 180), 3))
    for s in (-1, 1):
        h.add(0.8, 0.45, s * 1.05, 1.5, 1.05, s * 1.14, eye)                  # huge pale eyes
        h.add(0.75, 1.0, s * 0.95, 1.55, 1.15, s * 1.14, dk)                  # a heavy brow
        for i in range(4): h.add(1.0 + i * 0.05, -0.1 - i * 0.35, s * 1.06, 1.15 + i * 0.05, 0.45 - i * 0.35, s * 1.1, glow((160, 180, 230)))   # a glowing tear trail
        h.add(-0.9, -1.0, s * 1.1, -0.2, 0.6, s * 1.6, fin)                   # pectoral frills
    h.bone("whisk", (-1.0, 1.35, 0))
    h.add(-1.3, 1.35, -0.08, 0.7, 2.6, 0.08, fin)                              # dorsal
    h.bone(None)
    for i in range(5):
        x = -1.2 + i * 0.5
        h.add(x, -2.2 - (i % 2) * 0.6, -0.05, x + 0.1, -1.25, 0.05, solid((30, 30, 50), 2))   # shadow tendrils dripping down
    h.anim("whisk", "rot", (0, 0, 6), 3.2)
    seg = Part("lament_body", 10, 256)
    rounded(seg, -0.25, -1.15, -1.1, 2.35, 1.15, 1.1, sk, 0.4)
    seg.add(0.0, 1.1, -0.08, 2.0, 2.3, 0.08, fin)
    seg.add(0.2, -1.2, -0.7, 2.0, -1.05, 0.7, solid((34, 34, 46), 3))
    for s in (-1, 1):
        seg.bone("fin_" + ("r" if s > 0 else "l"), (1.2, -0.4, s * 1.1))
        seg.add(0.5, -0.65, s * 1.1, 1.9, -0.4, s * 2.5, fin)
        seg.bone(None)
        for x in (0.4, 1.1, 1.8): seg.add(x, 0.2, s * 1.09, x + 0.2, 0.32, s * 1.12, glow((140, 160, 220)))
    for i in range(4):
        x = 0.2 + i * 0.55
        seg.add(x, -2.0 - (i % 2) * 0.7, -0.05, x + 0.1, -1.15, 0.05, solid((30, 30, 50), 2))
    seg.anim("fin_l", "rot", (0, 0, 16), 2.6)
    seg.anim("fin_r", "rot", (0, 0, -16), 2.6)
    tail = Part("lament_tail", 10, 256)
    rounded(tail, -1.6, -0.85, -0.85, 0.2, 0.85, 0.85, sk, 0.28)
    tail.add(-2.3, -0.45, -0.45, -1.5, 0.45, 0.45, sk)
    tail.bone("fluke", (-2.2, 0, 0))
    tail.add(-4.2, -2.3, -0.09, -2.1, 2.3, 0.09, fin)                          # a huge ragged tail fan
    tail.add(-4.8, -1.4, -0.08, -4.1, 1.4, 0.08, fin)
    for i in range(5): tail.add(-4.2, -2.0 + i, -0.11, -2.2, -1.9 + i, 0.11, solid((60, 60, 90), 2))   # fin rays
    tail.bone(None)
    tail.anim("fluke", "rot", (0, 18, 0), 2.2)
    return [h, seg, tail]


# ---------------------------------------------------------------- THE ABYSSAL TITAN (Bosses 1.7.0): the Trench's final boss
TI_HIDE, TI_DARK, TI_PLATE, TI_BELLY = (30, 34, 52), (12, 13, 22), (52, 58, 84), (70, 64, 78)
TI_CYAN, TI_VIOLET, TI_BONE = (90, 255, 230), (190, 110, 255), (200, 194, 176)
TI_NECK, TI_SHOULDER, TI_ELBOW, TI_HINGE = (0.2, 2.45, 0), (0.15, 2.05, 1.45), 2.1, (0.15, -0.3, 0)
TI_LURES = ((0.9, 0.95, 0.0), (0.45, 0.95, -0.45), (0.45, 0.95, 0.45))


def _titan_body(p):
    hide = glow_lines(TI_HIDE, (60, 140, 160), 7, 0.004)
    plate = grad(TI_PLATE, TI_HIDE, 6, (90, 100, 140), 0.03)
    dark_plate = grad((40, 44, 66), TI_DARK, 5, (70, 80, 110), 0.02)
    bone = bone_curve(TI_BONE, (80, 76, 70))
    rounded(p, -1.0, -1.6, -1.35, 0.9, 2.5, 1.35, hide, 0.42)                 # the colossal torso, its base sunk in the floor
    # the chest: overlapping plates in two columns, a glowing seam between them, a rift showing the heart
    for i in range(6):
        y = -0.9 + i * 0.55
        for s in (-1, 1):
            a, b = sorted((s * 0.08, s * (1.02 - i * 0.05)))
            p.add(0.78 + (i % 2) * 0.03, y, a, 1.02 + (i % 2) * 0.03, y + 0.47, b, plate)
            a, b = sorted((s * (0.98 - i * 0.05), s * (1.12 - i * 0.05)))
            p.add(0.7, y + 0.05, a, 0.94, y + 0.4, b, dark_plate)                # the plate's rim
    p.add(0.9, -1.0, -0.07, 1.06, 2.15, 0.07, glow(TI_CYAN))                    # the seam
    p.add(0.95, 0.3, -0.32, 1.1, 1.3, 0.32, solid((20, 4, 12), 3))              # the rift
    p.add(1.0, 0.45, -0.22, 1.14, 1.15, 0.22, glow((255, 70, 110)))             # the heart beating inside it
    p.add(1.02, 0.6, -0.12, 1.17, 1.0, 0.12, glow((255, 190, 200)))
    p.add(0.85, -1.4, -0.85, 0.95, -0.95, 0.85, solid(TI_BELLY, 4))
    for s in (-1, 1):
        for i in range(5):                                                      # ribs showing through the hide
            y = -0.6 + i * 0.5
            a, b = sorted((s * 1.3, s * 1.44))
            p.add(-0.7, y, a, 0.7, y + 0.16, b, bone)
            a, b = sorted((s * 1.33, s * 1.36))
            p.add(-0.6, y + 0.17, a, 0.6, y + 0.21, b, glow((60, 150, 160)))     # glowing flesh between them
        # PAULDRONS: three stacked shells over each shoulder with spikes
        for k in range(3):
            a, b = sorted((s * (0.95 + k * 0.12), s * (1.8 + k * 0.08)))
            p.add(-0.65 + k * 0.08, 1.75 + k * 0.28, a, 0.85 - k * 0.05, 2.1 + k * 0.28, b, plate if k != 1 else dark_plate)
        for k in range(3):
            x = -0.4 + k * 0.4
            tube(p, [(x, 2.55, s * 1.45), (x - 0.1, 2.95, s * 1.6), (x - 0.25, 3.25, s * 1.75)], 0.11, 0.03, bone)
        # gill slits glowing on the flanks
        for i in range(4):
            a, b = sorted((s * 1.34, s * 1.37))
            p.add(0.1, 0.9 + i * 0.22, a, 0.55, 0.98 + i * 0.22, b, glow(TI_CYAN))
        # barnacle crust and old coral on the lower torso
        for (x, y, z) in ((0.4, -1.2, 1.2), (-0.3, -1.0, 1.25), (0.1, -1.45, 1.3), (-0.6, -1.3, 1.1), (0.6, -0.5, 1.28)):
            p.add(x - 0.1, y - 0.1, s * z - 0.1, x + 0.1, y + 0.12, s * z + 0.1, solid((150, 150, 140), 3))
            p.add(x - 0.04, y + 0.1, s * z - 0.04, x + 0.04, y + 0.14, s * z + 0.04, solid((40, 40, 40), 2))
        for (x, y) in ((-0.2, -1.55), (0.5, -1.5)):
            a, b = sorted((s * 1.2, s * 1.5))
            p.add(x - 0.15, y, a, x + 0.15, y + 0.5, b, solid((120, 60, 140), 4))     # dead coral growing on it
        # broken chains hanging from the shoulders: something tried to bind it here
        for k in range(5):
            y = 1.6 - k * 0.35
            p.add(0.2 - 0.06, y - 0.15, s * 1.5 - 0.06 + (k % 2) * 0.03, 0.2 + 0.06, y + 0.15, s * 1.5 + 0.06, solid((70, 72, 78), 3))
    for i in range(7):                                                          # spines down the back, curving up
        y = -0.8 + i * 0.5
        h = 0.5 + (3 - abs(i - 3)) * 0.12
        tube(p, [(-1.0, y, 0), (-1.3, y + h * 0.4, 0), (-1.45 - h * 0.3, y + h, 0)], 0.12, 0.03, bone)
        for s in (-1, 1):
            a, b = sorted((s * 0.25, s * 0.55))
            p.add(-1.15, y + 0.05, a, -0.95, y + 0.25, b, dark_plate)               # small back plates
    for (x, y, z) in ((0.5, 1.6, 0.9), (0.3, 0.6, -1.1), (-0.3, 1.2, 1.25), (0.6, -0.2, -0.95), (-0.6, 2.0, -0.9), (0.2, -1.0, 1.1),
                      (-0.8, 0.3, 0.7), (-0.8, 1.4, -0.6), (0.0, -0.4, -1.3), (-0.4, -1.2, 0.9)):
        p.add(x - 0.06, y - 0.06, z - 0.06, x + 0.06, y + 0.06, z + 0.06, glow(TI_CYAN))   # bioluminescent spots
    # roots / tendrils spreading into the Maw's floor round its base
    for k in range(8):
        a = k * math.pi / 4 + 0.3
        ox, oz = math.cos(a), math.sin(a)
        tube(p, [(ox * 0.9, -1.4, oz * 1.2), (ox * 1.5, -1.6, oz * 1.7), (ox * 2.1, -1.65, oz * 2.2), (ox * 2.6, -1.62, oz * 2.5)],
             0.2, 0.05, hide)
    rounded(p, -0.6, 2.2, -0.6, 0.6, 2.7, 0.6, hide, 0.2)                     # the neck
    for i in range(3):                                                          # its ridged front
        p.add(0.5, 2.25 + i * 0.15, -0.45, 0.66, 2.33 + i * 0.15, 0.45, plate)


def _titan_head(p, eyes_glow):
    hide = glow_lines(TI_HIDE, (60, 140, 160), 6, 0.0)
    plate = grad(TI_PLATE, TI_HIDE, 6, (90, 100, 140), 0.03)
    bone = bone_curve(TI_BONE, (60, 56, 50))
    rounded(p, -0.4, -0.3, -0.8, 1.2, 0.85, 0.8, hide, 0.3)                    # the skull
    p.add(0.9, 0.0, -0.7, 1.75, 0.55, 0.7, hide)                               # the snout
    p.add(1.7, 0.05, -0.55, 1.95, 0.45, 0.55, solid(TI_DARK, 4))
    p.add(0.4, 0.72, -0.85, 1.35, 0.86, 0.85, plate)                           # the heavy brow ridge
    p.add(1.1, 0.5, -0.72, 1.8, 0.62, 0.72, plate)                             # plates down the snout
    for i in range(4):
        p.add(0.2 - i * 0.18, 0.86, -0.5 + i * 0.05, 0.38 - i * 0.18, 0.98 + i * 0.03, 0.5 - i * 0.05, plate)   # crest plates going back
    for s in (-1, 1):
        a, b = sorted((s * 0.15, s * 0.3))
        p.add(1.75, 0.32, a, 1.96, 0.42, b, solid((10, 10, 16), 2))                # nostrils
    for i, z in enumerate((-0.6, -0.42, -0.24, -0.06, 0.12, 0.3, 0.48, 0.64)):   # the upper fangs, uneven
        ln = 0.42 if i in (0, 7) else 0.3 + (i % 3) * 0.06
        p.add(1.5 + abs(z) * -0.3, 0.02 - ln, z - 0.05, 1.62 + abs(z) * -0.3, 0.02, z + 0.05, teeth)
    p.add(0.95, -0.1, -0.68, 1.75, 0.02, 0.68, solid((60, 10, 24), 3))           # the gums
    for s in (-1, 1):
        # horns: a big pair sweeping back, a smaller pair below
        tube(p, [(-0.1, 0.7, s * 0.55), (-0.5, 1.1, s * 0.85), (-1.1, 1.3, s * 1.05), (-1.6, 1.15, s * 1.1), (-1.85, 0.9, s * 1.05)], 0.17, 0.03, bone)
        tube(p, [(0.0, 0.2, s * 0.75), (-0.4, 0.15, s * 1.05), (-0.8, 0.3, s * 1.25)], 0.1, 0.03, bone)
        # cheek frills: three fanned spines with webbing
        for k in range(3):
            y = -0.2 + k * 0.25
            tube(p, [(-0.2, y, s * 0.8), (-0.6, y + 0.05, s * (1.1 + k * 0.08)), (-0.9, y + 0.1, s * (1.25 + k * 0.1))], 0.06, 0.02, bone)
        a, b = sorted((s * 0.8, s * 1.15))
        p.add(-0.7, -0.2, a, -0.2, 0.45, b, glow_lines((40, 60, 80), (80, 170, 190), 3, 0.0))
        # three eyes a side, sunk in dark sockets ringed with glow
        a, b = sorted((s * 0.79, s * 0.83))
        a2, b2 = sorted((s * 0.77, s * 0.8))
        for i, (x, y) in enumerate(((1.15, 0.45), (0.85, 0.55), (0.55, 0.62))):
            p.add(x - 0.13, y - 0.1, a2, x + 0.13, y + 0.1, b2, solid(TI_DARK, 2))
            p.add(x - 0.1, y - 0.07, a, x + 0.1, y + 0.07, b, glow(TI_CYAN) if eyes_glow else solid(TI_DARK, 2))
        # glowing gill slits behind the jaw
        for i in range(3):
            a, b = sorted((s * 0.78, s * 0.82))
            p.add(-0.3 + i * 0.15, -0.25, a, -0.24 + i * 0.15, 0.15, b, glow(TI_CYAN))
    # barnacles on the skull
    for (x, y, z) in ((0.1, 0.84, 0.3), (-0.2, 0.7, -0.6), (0.6, 0.85, -0.4)):
        p.add(x - 0.07, y, z - 0.07, x + 0.07, y + 0.1, z + 0.07, solid((150, 150, 140), 3))


def titan():
    """The Abyssal Titan: a colossal torso rising out of the Maw's floor (its origin = the waist), a head on its neck joint
    TI_NECK, a lower jaw on the hinge TI_HINGE (in the head's frame), two arms from the shoulders TI_SHOULDER (each
    `titan_arm` reaches +x TI_ELBOW, then `titan_claw` with four talons), and three lures on its brow (`titan_lure`,
    glowing). The head's eyes are their own part (`titan_eyes`, drawn fully lit) so it stays a dark shape with burning
    eyes. Java gets the jointed parts; Bedrock (stands can't pitch) one `titan_b` with the jaw, arms and lures as bones."""
    hide = glow_lines(TI_HIDE, (60, 140, 160), 6, 0.0)
    body = Part("titan_body", 10, 512, java_only=True)
    _titan_body(body)
    head = Part("titan_head", 10, 256, java_only=True)
    _titan_head(head, False)
    eyes = Part("titan_eyes", 10, 64, java_only=True)
    for s in (-1, 1):
        a, b = sorted((s * 0.81, s * 0.86))
        for (x, y) in ((1.15, 0.45), (0.85, 0.55), (0.55, 0.62)): eyes.add(x - 0.1, y - 0.07, a, x + 0.1, y + 0.07, b, glow(TI_CYAN))
    plate = grad(TI_PLATE, TI_HIDE, 6, (90, 100, 140), 0.03)
    bone = bone_curve(TI_BONE, (60, 56, 50))
    jaw = Part("titan_jaw", 10, 256, java_only=True)
    jaw.add(0.0, -0.45, -0.7, 1.75, -0.05, 0.7, hide)
    jaw.add(0.2, -0.55, -0.55, 1.6, -0.42, 0.55, plate)                        # the chin plate
    jaw.add(0.1, -0.08, -0.6, 1.7, 0.0, 0.6, solid((60, 10, 24), 3))
    jaw.add(0.3, -0.02, -0.25, 1.3, 0.06, 0.25, solid((140, 40, 70), 3))        # the tongue
    for i, z in enumerate((-0.55, -0.37, -0.19, 0.0, 0.19, 0.37, 0.55)):
        ln = 0.42 if i in (1, 5) else 0.3                                        # two tusks among the lower teeth
        jaw.add(1.45 - abs(z) * 0.3, -0.05, z - 0.05, 1.57 - abs(z) * 0.3, -0.05 + ln, z + 0.05, teeth)
    for s in (-1, 1):                                                           # mandible spikes
        for k in range(3):
            x = 0.3 + k * 0.45
            tube(jaw, [(x, -0.5, s * 0.6), (x - 0.1, -0.75, s * 0.75), (x - 0.25, -0.9, s * 0.8)], 0.07, 0.02, bone)
    arm = Part("titan_arm", 10, 256, java_only=True)
    rounded(arm, -0.2, -0.38, -0.38, TI_ELBOW + 0.2, 0.38, 0.38, hide, 0.15)
    for x in (0.2, 0.75, 1.3, 1.8):                                             # armour bands, overlapping
        arm.add(x, 0.28, -0.36, x + 0.48, 0.46, 0.36, plate)
        arm.add(x + 0.05, -0.42, -0.3, x + 0.4, -0.3, 0.3, grad((40, 44, 66), TI_DARK, 4))
    for x in (0.5, 1.1, 1.7): arm.add(x, -0.05, -0.41, x + 0.08, 0.05, 0.41, glow(TI_CYAN))   # glowing joints in the hide
    tube(arm, [(TI_ELBOW - 0.1, 0.3, 0), (TI_ELBOW - 0.4, 0.75, 0), (TI_ELBOW - 0.8, 1.0, 0)], 0.13, 0.03, bone)   # the elbow spike
    for k in range(3): tube(arm, [(0.4 + k * 0.6, 0.45, 0), (0.25 + k * 0.6, 0.7, 0)], 0.07, 0.02, bone)            # little spines
    arm.add(TI_ELBOW - 0.3, -0.45, -0.45, TI_ELBOW + 0.1, 0.45, 0.45, solid((70, 72, 78), 3))   # an iron shackle at the wrist
    for k in range(3): arm.add(TI_ELBOW - 0.18, -0.55 - k * 0.3, -0.07, TI_ELBOW - 0.02, -0.3 - k * 0.3, 0.07, solid((60, 62, 68), 3))  # its broken chain
    claw = Part("titan_claw", 10, 256, java_only=True)
    rounded(claw, -0.1, -0.32, -0.34, 1.5, 0.32, 0.34, hide, 0.12)
    claw.add(0.3, 0.26, -0.3, 1.4, 0.4, 0.3, plate)                            # the back of the hand
    claw.add(0.4, -0.36, -0.22, 1.3, -0.28, 0.22, glow((90, 200, 210)))        # a glowing palm
    for (y, z) in ((0.12, -0.26), (0.12, 0.0), (0.12, 0.26), (-0.18, 0.0)):    # four talons, knuckle plates on each
        claw.add(1.3, y - 0.1, z - 0.11, 1.55, y + 0.13, z + 0.11, plate)
        tube(claw, [(1.4, y, z), (1.9, y + 0.1, z * 1.2), (2.3, y - 0.05, z * 1.3), (2.55, y - 0.35, z * 1.3), (2.62, y - 0.55, z * 1.3)],
             0.14, 0.02, bone_curve(TI_BONE, (40, 36, 34)))
    lure = Part("titan_lure", 10, 128, java_only=True)
    tube(lure, [(0, 0, 0), (0.3, 0.7, 0), (0.8, 1.1, 0), (1.3, 1.0, 0)], 0.07, 0.04, glow((40, 120, 130)))
    for k in range(4): lure.add(0.15 + k * 0.28, 0.4 + k * 0.17 - (0.1 if k == 3 else 0), -0.09, 0.22 + k * 0.28, 0.48 + k * 0.17, 0.09, glow(TI_CYAN))  # rings up the stalk
    lure.add(1.2, 0.75, -0.18, 1.55, 1.1, 0.18, glow(TI_VIOLET))
    lure.add(1.15, 0.82, -0.12, 1.6, 1.03, 0.12, glow((235, 200, 255)))        # its bright core
    for (y, z) in ((0.7, -0.1), (0.7, 0.1), (0.68, 0.0)):                      # little feelers dangling under the bulb
        lure.add(1.33, y - 0.25, z - 0.02, 1.37, y, z + 0.02, glow((150, 90, 220)))
    # Bedrock: one model, the jaw / arms / lures as animated bones
    b = Part("titan_b", 10, 1024, bedrock_only=True)
    _titan_body(b)
    hd = Part("tmp"); _titan_head(hd, True)
    b.take(hd, *TI_NECK)
    b.bone("jaw", (TI_NECK[0] + TI_HINGE[0], TI_NECK[1] + TI_HINGE[1], 0))
    b.take(jaw, TI_NECK[0] + TI_HINGE[0], TI_NECK[1] + TI_HINGE[1], 0, bone="jaw")
    b.bone(None)
    for side, s in (("arm_l", -1), ("arm_r", 1)):
        sh = (TI_SHOULDER[0], TI_SHOULDER[1], s * TI_SHOULDER[2])
        b.bone(side, sh)
        am = Part("tmp")
        # hanging forward and down: the arm along (+x, -y), the claw reaching forward
        for (a0, b0, paint) in arm.boxes: am.add(a0[0] * 0.7, -a0[0] * 0.7 + a0[1], a0[2], b0[0] * 0.7, -b0[0] * 0.7 + b0[1], b0[2], paint)
        for (a0, b0, paint) in claw.boxes: am.add(TI_ELBOW * 0.7 + a0[0], -TI_ELBOW * 0.7 + a0[1], a0[2], TI_ELBOW * 0.7 + b0[0], -TI_ELBOW * 0.7 + b0[1], b0[2], paint)
        b.take(am, *sh, bone=side)
        b.bone(None)
    for i, (lx, ly, lz) in enumerate(TI_LURES):
        nm = f"lure{i}"
        piv = (TI_NECK[0] + lx, TI_NECK[1] + ly, lz)
        b.bone(nm, piv)
        b.take(lure, *piv, bone=nm)
        b.bone(None)
    b.half = {"jaw"}
    b.anim("jaw", "rot", (0, 0, -18), 3.0).anim("arm_l", "rot", (8, 0, 10), 4.0).anim("arm_r", "rot", (-8, 0, 10), 4.0, 0.5)
    for i in range(3): b.anim(f"lure{i}", "rot", (6, 0, 10), 2.4, i / 3)
    return [body, head, eyes, jaw, arm, claw, lure, b]


def deep_bosses():
    return scourge() + umbral() + coralia() + inkjaw() + grandmaw() + lament() + titan()


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
    "j": (200, 112, 74), "J": (140, 74, 48), "u": (150, 214, 236), "z": (160, 96, 240), "q": (214, 176, 84),
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
    "dive_helmet_1": [
        "................",
        "....jjjjjjjj....",
        "...jJjjjjjjJj...",
        "..jjJqqqqqqJjj..",
        "..jJqquuuuqqJj..",
        ".jjJquuuuuuqJjj.",
        ".jjJquueuuuqJjj.",
        ".jjJquuuuuuqJjj.",
        ".jjJquuuuuuqJjj.",
        "..jJqquuuuqqJj..",
        "..jjJqqqqqqJjj..",
        "...jJjjjjjjJj...",
        "..qqqqqqqqqqqq..",
        "..qJJJJJJJJJJq..",
        "..qqqqqqqqqqqq..",
        "................",
    ],
    "dive_helmet_2": [
        "......xxxx......",
        "....xxxxxxxx....",
        "...xXxxxxxxXx...",
        "..xxXOOOOOOXxx..",
        "..xXOOuuuuOOXx..",
        ".xxXOuuuuuuOXxx.",
        ".OxXOuueuuuOXxO.",
        ".OxXOuuuuuuOXxO.",
        ".xxXOuuuuuuOXxx.",
        "..xXOOuuuuOOXx..",
        "..xxXOOOOOOXxx..",
        "...xXxxxxxxXx...",
        "..OOOOOOOOOOOO..",
        "..OXXXXXXXXXXO..",
        "..OOOOOOOOOOOO..",
        "................",
    ],
    "dive_helmet_3": [
        "......2222......",
        "....22222222....",
        "...2122222212...",
        "..2212%%%%2122..",
        "..212%%zzz%%12..",
        ".2212%zzzzz%122.",
        ".%212%zz*zz%12%.",
        ".%212%zzzzz%12%.",
        ".2212%zzzzz%122.",
        "..212%%zzz%%12..",
        "..2212%%%%2122..",
        "...2122222212...",
        "..%%%%%%%%%%%%..",
        "..%1111111111%..",
        "..%%%%%%%%%%%%..",
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
    out = {"knight_helm": (a1, helm), "mining_helmet": (a2, hat)}
    # the dive helmets (FaultlineItems DiveHelmets, 1.2.57): a round dome, a big front porthole, two side ports, a collar
    for key, dome, dome_d, ring, glass in (("dive_helmet_1", (200, 112, 74), (140, 74, 48), (214, 176, 84), (150, 214, 236)),
                                          ("dive_helmet_2", (176, 180, 192), (110, 114, 126), (214, 168, 60), (150, 214, 236)),
                                          ("dive_helmet_3", (40, 40, 52), (22, 22, 28), (160, 100, 255), (170, 110, 255))):
        def d(reg, face, wu, hu, c=dome): noise_fill(reg, c, rng, 7)
        def dd(reg, face, wu, hu, c=dome_d): noise_fill(reg, c, rng, 5)
        def rg(reg, face, wu, hu, c=ring): noise_fill(reg, c, rng, 6)
        def gl(reg, face, wu, hu, c=glass): noise_fill(reg, c, rng, 3)
        at = A2(128, 2)
        el = [head_box(at, (-4.8, -4.4, -4.8), (4.8, 4.9, 4.8), d),
              head_box(at, (-3.8, 4.9, -3.8), (3.8, 5.8, 3.8), d),
              head_box(at, (-0.8, 5.8, -0.8), (0.8, 6.8, 0.8), rg),                      # the valve on top
              head_box(at, (-3.0, -2.4, -5.3), (3.0, 2.6, -4.8), rg),                    # the front porthole's ring
              head_box(at, (-2.3, -1.7, -5.5), (2.3, 1.9, -5.3), gl),                    # its glass
              head_box(at, (-5.2, -1.2, -1.6), (-4.8, 1.6, 1.6), rg), head_box(at, (-5.4, -0.7, -1.1), (-5.2, 1.1, 1.1), gl),   # side ports
              head_box(at, (4.8, -1.2, -1.6), (5.2, 1.6, 1.6), rg), head_box(at, (5.2, -0.7, -1.1), (5.4, 1.1, 1.1), gl),
              head_box(at, (-5.4, -5.4, -5.4), (5.4, -4.4, 5.4), rg),                    # the collar
              head_box(at, (-1.0, -3.0, 4.8), (1.0, -0.6, 6.6), dd)]                     # the air hose's stub
        for (x, y) in ((-3.6, 3.6), (3.6, 3.6), (-3.6, -3.4), (3.6, -3.4)):
            el.append(head_box(at, (x - 0.4, y - 0.4, -5.0), (x + 0.4, y + 0.4, -4.8), rg))   # bolts
        out[key] = (at, el)
    return out


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
                 "grandmaw": "grandmaw_clam", "lament": "the_lament", "titan": "abyssal_titan"}


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
    out = {"brine_scourge": render_icon(size=size, pieces=serpent("scourge", 1.9, 3) + [at("scourge_mandible", 0.55, 0, -1.0), at("scourge_mandible_r", 0.55, 0, 1.0)]), "umbral_leviathan": render_icon(size=size, pieces=serpent("umbral", 2.05, 3)),
           "the_lament": render_icon(size=size, pieces=serpent("lament", 2.3, 2))}
    out["coralia"] = render_icon(size=size, pieces=[at("coralia_tail"), at("coralia_fluke", -1.75, -2.8, 0), at("coralia_torso"),
                                  at("coralia_head", 0, 1.25, 0), at("coralia_hair", -0.22, 1.97, 0), at("coralia_arm", 0, 1.0, -0.55),
                                  at("coralia_arm", 0, 1.0, 0.55), at("coralia_trident", 0, -0.3, 0.55)])
    ink = [at("inkjaw_body")]
    for j, (x, z) in enumerate(((-0.5, -0.6), (0.5, -0.6), (-0.5, 0.6), (0.5, 0.6), (0, 0))):
        for sgi in range(3):
            ink.append(at("inkjaw_tentacle", x + (sgi * 0.25 if j % 2 else -sgi * 0.25), -0.6 - sgi * 1.15, z, rot="down", s=1 - sgi * 0.22))
    out["old_inkjaw"] = render_icon(ink, size)
    out["grandmaw_clam"] = render_icon(size=size, pieces=[at("grandmaw_shell"), at("grandmaw_lid", -1.0, 0.75, 0, rot="up"), at("grandmaw_pearl", 0.1, 0.75, 0)])
    N, S = TI_NECK, TI_SHOULDER
    ti = [at("titan_body"), at("titan_head", *N), at("titan_eyes", *N), at("titan_jaw", N[0] + TI_HINGE[0], N[1] + TI_HINGE[1], 0)]
    for sgn in (-1, 1): ti += [at("titan_arm", S[0], S[1], sgn * S[2]), at("titan_claw", S[0] + TI_ELBOW, S[1], sgn * S[2])]
    for (lx, ly, lz) in TI_LURES: ti.append(at("titan_lure", N[0] + lx, N[1] + ly, lz))
    out["abyssal_titan"] = render_icon(size=size, pieces=ti)
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
            "display_name": {"knight_helm": "Knight's Helm", "mining_helmet": "Mining Helmet", "dive_helmet_1": "Copper Dive Helmet",
                             "dive_helmet_2": "Reinforced Dive Helmet", "dive_helmet_3": "Hadal Dive Helmet"}[key],
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
