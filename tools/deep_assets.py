#!/usr/bin/env python3
"""
Art for the Dark Ocean (FaultlineDeep): its 20 creatures, their eggs / Index icons, the Dark Loot Bag, and the Bedrock pack.

Each creature is authored like the wild models (tools/wild_assets.py Part: boxes in blocks, +x = its FRONT, +y = up,
+z = its RIGHT side), CENTRED on its middle and about one block long; the plugin scales it by the kind's modelScale.
  Java     assets/faultline/items|models/item|textures/item/deep/<id>   16 units per block, the origin at (8, 8, 8),
           so the display scale is just modelScale (DeepMobs.pose turns it with Wild's Q0/FLIP convention).
           deep/<id>_icon = the egg (a 16x16 picture of the model), also its Index icon deep_<id>.
  Bedrock  FaultlineDeep.mcpack: geometry/attachable per creature (worn by a Bedrock-only armor stand at the body's middle,
           scaled by the SCALE attribute), the egg icons, and the Dark Ocean's two music tracks (sound_definitions).
           faultline_deep_mappings.json maps paper + item model faultline:deep/<id>[_icon] to them.

Usage: python3 tools/deep_assets.py <unpacked-pack-dir> <bedrock-out-dir> [--preview <dir>] [--music <dir with deep/*.ogg>]
"""
import json, math, os, sys, zipfile, shutil
import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wild_assets as W                                                          # noqa: E402
from wild_assets import Part, rounded, solid, glow, scales, membrane, stripes_h, teeth, bands   # noqa: E402

NS = "faultline"


# --------------------------------------------------------------------------------------------- painters + helpers
# v2 (Deep 1.3.0): far more detail, and every creature has named moving BONES (tail, fins, wings, jaw, lid, arms,
# tentacles, legs...) with looping animations. Bedrock plays them in the pack (attachable animations); Java gets one item
# display per bone, posed by DeepMobs from the same numbers (DeepParts.java, written by main()). `p.half` = bones whose
# animation only opens and shuts (jaws, lids). `p.pivots["body"]` = the pivot of whole-body motion ("body" anims).
def _c(c, d): return W._c(c, d)


def fish(top, belly, spot=None, dens=0.0, arcs=True):
    """A fish's flank: darker back fading to a pale belly, little scale arcs, optional spots."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        reg[:, :, 3] = 255
        for y in range(h):
            t = 0 if face == "up" else 1 if face == "down" else y / max(1, h - 1)
            base = W._mix(top, belly, t)
            for x in range(w):
                c = np.array(base, float) + rng.uniform(-5, 5)
                if arcs and face not in ("up", "down") and y % 3 == 2 and (x + (y // 3)) % 3 != 1: c = c * 0.86
                if spot and rng.random() < dens: c = np.array(spot, float)
                reg[y, x, :3] = np.clip(c, 0, 255)
    return f


def finp(c, ray):
    """A fin: translucent-looking colour with rays running out from the base."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        reg[:, :, 3] = 255
        for y in range(h):
            for x in range(w):
                col = W._mix(c, ray, 0.55) if x % 3 == 0 else c
                reg[y, x, :3] = np.clip(np.array(col, float) + rng.uniform(-6, 6) - y * 2, 0, 255)
    return f


def spotted(c, dot, dens=0.12):
    return W.grad(c, _c(c, -30), 5, dot, dens)


def eyes(p, x0, x1, y0, y1, z, white, iris, pupil=(14, 12, 18), slit=False, depth=0.03):
    """A pair of eyes on the flanks at +-z (each a thin box painted as an eye)."""
    for s in (-1, 1):
        a, b = sorted((s * z, s * (z + depth)))
        p.add(x0, y0, a, x1, y1, b, W.eye_paint(white, iris, pupil, slit))


def pair(p, x0, y0, z0, x1, y1, z1, paint, bone=None):
    """A box on the right (+z) side and its mirror on the left."""
    for s in (-1, 1):
        a, b = sorted((s * z0, s * z1))
        p.add(x0, y0, a, x1, y1, b, paint)


def side_bone(p, name, pivot, boxes, mirror):
    """Bones `<name>l` (left, -z) and `<name>r` (right, +z): boxes given for the RIGHT side."""
    if not mirror:
        p.bone(name, pivot)
        for (b, paint) in boxes: p.add(*b, paint)
        p.bone(None); return
    for side, s in (("l", -1), ("r", 1)):
        p.bone(name + side, (pivot[0], pivot[1], s * pivot[2]))
        for (b, paint) in boxes:
            x0, y0, z0, x1, y1, z1 = b
            a, c = sorted((s * z0, s * z1))
            p.add(x0, y0, a, x1, y1, c, paint)
        p.bone(None)


def staircase(p, x0, x1, y0, height, z0, z1, paint, steps=4, lean=0.0):
    """A fin rising in steps that shrink toward the top (and lean back)."""
    for i in range(steps):
        t0 = i / steps
        w = (x1 - x0) * (1 - t0 * 0.75)
        sx = x0 + (x1 - x0 - w) * 0.5 - lean * t0
        p.add(sx, y0 + height * t0, z0, sx + w, y0 + height * (t0 + 1 / steps), z1, paint)


TEETH = solid((246, 244, 232), 2)
MOUTH = solid((70, 14, 24), 3)


# --------------------------------------------------------------------------------------------- the Sunlit Sea
def gawker():
    """A round little fish that's mostly eye."""
    p = Part("gawker", 16, 128)
    sk = fish((226, 178, 40), (252, 236, 170), (200, 120, 30), 0.05)
    rounded(p, -0.28, -0.25, -0.24, 0.3, 0.28, 0.24, sk, 0.1)
    rounded(p, -0.02, 0.18, -0.18, 0.26, 0.33, 0.18, sk, 0.06)                   # the brow
    eyes(p, -0.02, 0.24, -0.15, 0.15, 0.24, (252, 252, 246), (50, 130, 220), depth=0.04)   # the huge eyes
    p.add(0.29, -0.13, -0.08, 0.37, -0.02, 0.08, solid((214, 90, 90), 3))        # pouty lips
    p.add(0.36, -0.09, -0.05, 0.375, -0.06, 0.05, MOUTH)
    staircase(p, -0.18, 0.12, 0.28, 0.16, -0.015, 0.015, finp((250, 150, 40), (255, 210, 120)), 3, 0.06)
    staircase(p, -0.2, 0.02, -0.4, 0.12, -0.015, 0.015, finp((250, 150, 40), (255, 210, 120)), 2)
    side_bone(p, "fin", (0.04, -0.06, 0.24), [((-0.06, -0.12, 0.24, 0.12, -0.02, 0.36), finp((250, 160, 50), (255, 220, 140)))], True)
    p.bone("tail", (-0.28, 0, 0))
    p.add(-0.4, -0.1, -0.06, -0.27, 0.1, 0.06, sk)
    p.add(-0.62, -0.28, -0.02, -0.38, 0.28, 0.02, finp((250, 150, 40), (255, 210, 120)))
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("tail", "rot", (0, 28, 0), 0.7).anim("finl", "rot", (0, 0, 25), 0.5).anim("finr", "rot", (0, 0, -25), 0.5)
    p.anim("body", "pos", (0, 0.6, 0), 1.8)
    return p


def bloatfin():
    """A fish like a bubble with fins: a clear air sac you can pop."""
    p = Part("bloatfin", 16, 128)
    sac = W.glow((150, 220, 244))
    rounded(p, -0.3, -0.3, -0.3, 0.3, 0.3, 0.3, sac, 0.12)
    rounded(p, -0.12, -0.12, -0.12, 0.12, 0.12, 0.12, solid((230, 120, 150), 4), 0.04)   # the bladder inside
    sk = fish((70, 140, 180), (200, 230, 240))
    p.add(0.27, -0.15, -0.15, 0.42, 0.13, 0.15, sk)                               # its little face
    eyes(p, 0.3, 0.38, -0.02, 0.08, 0.15, (250, 250, 250), (30, 30, 40))
    p.add(0.42, -0.07, -0.04, 0.45, -0.02, 0.04, MOUTH)
    for (y, z) in ((0.31, 0), (-0.31, 0), (0, 0.31), (0, -0.31), (0.22, 0.22), (-0.22, -0.22), (0.22, -0.22), (-0.22, 0.22),
                   (0.3, 0.12), (-0.3, -0.12)):
        p.add(-0.03, y - 0.035, z - 0.035, 0.03, y + 0.035, z + 0.035, solid((240, 250, 255), 2))   # spines
    side_bone(p, "fin", (0.08, -0.02, 0.3), [((0.0, -0.06, 0.3, 0.16, 0.04, 0.44), finp((80, 200, 230), (190, 240, 250)))], True)
    p.bone("tail", (-0.3, 0, 0))
    p.add(-0.56, -0.16, -0.02, -0.28, 0.16, 0.02, finp((80, 200, 230), (190, 240, 250)))
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("tail", "rot", (0, 22, 0), 0.6).anim("finl", "rot", (0, 0, 30), 0.4).anim("finr", "rot", (0, 0, -30), 0.4)
    p.anim("body", "pos", (0, 0.9, 0), 2.0)
    return p


def jellywing():
    """A glowing ray as clear as jelly, its spines a siren's comb."""
    p = Part("jellywing", 16, 256)
    jelly = W.glow((206, 140, 236))
    wing = W.grad((222, 170, 246), (170, 100, 214), 4, (255, 230, 255), 0.06)
    rounded(p, -0.32, -0.05, -0.19, 0.3, 0.08, 0.19, jelly, 0.05)
    p.add(-0.1, 0.07, -0.08, 0.2, 0.11, 0.08, W.glow((236, 190, 255)))           # the raised back
    for s in (-1, 1):
        a, b = sorted((s * 0.07, s * 0.14))
        p.add(0.3, -0.02, a, 0.46, 0.04, b, wing)                                 # the horns in front
    eyes(p, 0.16, 0.24, 0.04, 0.1, 0.19, (40, 20, 60), (250, 230, 255))
    for x in (-0.2, -0.1, 0.0, 0.1): p.add(x, 0.1, -0.015, x + 0.04, 0.2, 0.015, solid((255, 240, 250), 2))   # comb spines
    wing_boxes = [((-0.24, -0.03, 0.19, 0.24, 0.045, 0.38), wing), ((-0.17, -0.025, 0.38, 0.17, 0.04, 0.56), wing),
                  ((-0.1, -0.02, 0.56, 0.1, 0.035, 0.7), wing), ((-0.04, -0.015, 0.7, 0.04, 0.03, 0.8), wing)]
    side_bone(p, "w", (0.0, 0.0, 0.19), wing_boxes, True)
    p.bone("tail", (-0.32, 0, 0))
    p.add(-1.15, -0.012, -0.012, -0.3, 0.012, 0.012, solid((180, 110, 220)))
    p.add(-0.6, 0.012, -0.01, -0.52, 0.06, 0.01, solid((255, 220, 255), 2))       # the stinger barb
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("wl", "rot", (0, 0, 32), 1.6).anim("wr", "rot", (0, 0, -32), 1.6).anim("tail", "rot", (0, 16, 0), 1.6, 0.3)
    p.anim("body", "pos", (0, 0.8, 0), 1.6, 0.25)
    return p


def driftback():
    """A gentle giant with a whole reef growing on its back."""
    p = Part("driftback", 16, 256)
    sk = fish((86, 108, 128), (206, 210, 198), (120, 140, 160), 0.08)
    rounded(p, -0.42, -0.15, -0.21, 0.36, 0.14, 0.21, sk, 0.07)
    rounded(p, 0.34, -0.12, -0.15, 0.56, 0.1, 0.15, sk, 0.05)                    # the head
    p.add(0.5, -0.1, -0.12, 0.58, -0.06, 0.12, solid((60, 70, 80), 3))           # the mouth line
    eyes(p, 0.45, 0.5, -0.02, 0.03, 0.15, (30, 30, 30), (220, 200, 160))
    for i in range(5): p.add(0.2 - i * 0.12, -0.155, -0.12, 0.25 - i * 0.12, -0.14, 0.12, solid((180, 184, 172), 3))   # belly grooves
    reef = [((222, 90, 110), 0.13), ((250, 170, 60), 0.1), ((120, 200, 90), 0.16), ((180, 90, 220), 0.11), ((60, 190, 200), 0.12),
            ((240, 120, 160), 0.09)]
    for i, (c, h) in enumerate(reef):
        x, z = -0.34 + i * 0.12, (-0.08, 0.06, -0.02, 0.09, -0.1, 0.03)[i]
        p.add(x, 0.14, z - 0.035, x + 0.07, 0.14 + h, z + 0.035, solid(c, 4))
        p.add(x - 0.02, 0.14 + h * 0.6, z - 0.06, x + 0.09, 0.14 + h * 0.72, z + 0.06, solid(_c(c, 20), 4))   # coral branches
    p.add(-0.08, 0.14, -0.14, 0.12, 0.18, 0.14, solid((90, 110, 80), 6))          # moss
    p.bone("kelp", (0.0, 0.14, 0.0))
    for (x, z, h) in ((-0.22, 0.12, 0.34), (0.1, -0.13, 0.28), (-0.02, 0.05, 0.4)):
        p.add(x, 0.14, z - 0.015, x + 0.035, 0.14 + h, z + 0.015, solid((40, 140, 60), 4))
    p.bone(None)
    side_bone(p, "ff", (0.15, -0.08, 0.21), [((0.0, -0.12, 0.21, 0.26, -0.08, 0.5), finp((76, 96, 116), (120, 140, 160)))], True)
    side_bone(p, "rf", (-0.25, -0.1, 0.18), [((-0.36, -0.13, 0.18, -0.18, -0.1, 0.34), finp((76, 96, 116), (120, 140, 160)))], True)
    p.bone("tail", (-0.42, 0, 0))
    p.add(-0.56, -0.07, -0.08, -0.4, 0.06, 0.08, sk)
    p.add(-0.74, -0.03, -0.3, -0.54, 0.03, 0.3, finp((76, 96, 116), (120, 140, 160)))   # a whale's fluke
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("tail", "rot", (14, 0, 0), 3.0).anim("ffl", "rot", (0, 0, 18), 3.0).anim("ffr", "rot", (0, 0, -18), 3.0)
    p.anim("rfl", "rot", (0, 0, 10), 3.0, 0.5).anim("rfr", "rot", (0, 0, -10), 3.0, 0.5).anim("kelp", "rot", (0, 0, 12), 3.6)
    p.anim("body", "pos", (0, 1.0, 0), 4.0)
    return p


def clam():
    """Sits shut on the sea floor; the lid breathes open, a pearl glows inside."""
    p = Part("clam", 16, 128)
    shell = W.ribbed((204, 184, 160), (180, 160, 136), (226, 210, 186))
    rounded(p, -0.42, -0.32, -0.4, 0.42, -0.04, 0.4, shell, 0.12)
    p.add(-0.36, -0.05, -0.34, 0.36, -0.02, 0.34, solid((240, 150, 170), 4))     # the soft mantle
    p.add(-0.06, -0.04, -0.06, 0.06, 0.07, 0.06, W.glow((250, 246, 255)))         # the pearl
    for i in range(7): p.add(0.36, -0.06, -0.33 + i * 0.1, 0.43, -0.01, -0.29 + i * 0.1, solid((230, 120, 150), 3))   # frills
    p.bone("lid", (-0.42, -0.04, 0))
    rounded(p, -0.42, -0.04, -0.4, 0.42, 0.16, 0.4, shell, 0.12)
    rounded(p, -0.3, 0.15, -0.28, 0.3, 0.25, 0.28, W.ribbed((226, 210, 186), (200, 182, 158), (240, 228, 206)), 0.08)
    for (x, z) in ((-0.2, 0.2), (0.1, -0.22), (0.2, 0.15)): p.add(x, 0.16, z, x + 0.07, 0.21, z + 0.07, solid((120, 140, 110), 4))   # barnacles
    p.bone(None)
    p.half = {"lid"}
    p.anim("lid", "rot", (-16, 0, 0), 4.0)
    return p


def lumenbell():
    """A ghostly bell of a jellyfish, pulsing with light."""
    p = Part("lumenbell", 16, 128)
    bell = W.glow((110, 160, 255))
    rounded(p, -0.3, 0.0, -0.3, 0.3, 0.3, 0.3, bell, 0.11)
    rounded(p, -0.2, 0.28, -0.2, 0.2, 0.4, 0.2, bell, 0.07)
    p.add(-0.33, -0.04, -0.33, 0.33, 0.02, 0.33, solid((170, 210, 255), 3))      # the rim
    for i in range(8):                                                           # radial canals glowing on the bell
        a = i * math.pi / 4
        x, z = math.cos(a) * 0.3, math.sin(a) * 0.3
        p.add(x - 0.02, 0.04, z - 0.02, x + 0.02, 0.28, z + 0.02, W.glow((200, 230, 255)))
    p.bone("arms", (0, 0, 0))
    for (x, z) in ((0.06, 0.0), (-0.06, 0.0), (0.0, 0.06), (0.0, -0.06)):
        p.add(x - 0.03, -0.42, z - 0.03, x + 0.03, 0.0, z + 0.03, W.glow((180, 200, 255)))   # frilled oral arms
    p.bone(None)
    p.bone("t", (0, 0, 0))
    for i in range(10):
        a = i * math.pi / 5
        x, z = math.cos(a) * 0.27, math.sin(a) * 0.27
        p.add(x - 0.012, -0.78, z - 0.012, x + 0.012, -0.02, z + 0.012, solid((200, 230, 255), 2))   # trailing threads
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("t", "rot", (10, 0, 10), 2.2).anim("arms", "rot", (6, 0, -6), 2.2, 0.5).anim("body", "pos", (0, 1.4, 0), 2.2)
    return p


def gasbloom():
    """A slow, swollen floater full of poison gas."""
    p = Part("gasbloom", 16, 128)
    sk = spotted((132, 160, 66), (178, 210, 84), 0.1)
    rounded(p, -0.33, -0.3, -0.33, 0.33, 0.35, 0.33, sk, 0.14)
    for (x, y, z) in ((0.27, 0.2, 0.25), (-0.26, 0.25, -0.2), (0.1, -0.24, -0.31), (-0.2, -0.1, 0.33), (0.33, -0.05, -0.12), (-0.1, 0.36, 0.08)):
        p.add(x - 0.08, y - 0.08, z - 0.08, x + 0.08, y + 0.08, z + 0.08, W.glow((196, 236, 70)))   # gas pods
    for (x, y, z) in ((0, 0.36, 0), (0.2, 0.3, -0.2), (-0.25, 0.1, 0.25), (0.3, -0.2, 0.1), (-0.1, -0.31, -0.2)):
        p.add(x - 0.02, y - 0.02, z - 0.02, x + 0.02, y + 0.1, z + 0.02, solid((220, 230, 160), 2))   # spikes
    eyes(p, 0.28, 0.34, 0.05, 0.15, 0.2, (230, 230, 120), (40, 30, 10))
    p.add(0.33, -0.08, -0.08, 0.36, -0.02, 0.08, MOUTH)
    p.bone("t", (0, -0.3, 0))
    for (x, z) in ((0.1, 0.05), (-0.08, -0.1), (0.0, 0.12)):
        p.add(x - 0.025, -0.78, z - 0.025, x + 0.025, -0.3, z + 0.025, solid((100, 130, 50), 3))
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("t", "rot", (8, 0, 8), 3.0).anim("body", "pos", (0, 1.0, 0), 3.5).anim("body", "rot", (0, 6, 0), 7.0)
    return p


def shark(name, sk, fin, belly, dorsal=0.34, tail=True):
    """A shark: a long body, a gaping jaw that opens and shuts, rows of teeth, gills, a beating tail."""
    p = Part(name, 16, 256)
    rounded(p, -0.42, -0.15, -0.16, 0.3, 0.15, 0.16, sk, 0.06)
    rounded(p, 0.28, -0.1, -0.13, 0.52, 0.12, 0.13, sk, 0.05)                    # the head
    p.add(0.5, -0.04, -0.08, 0.6, 0.08, 0.08, sk)                                  # the snout
    p.add(-0.4, -0.16, -0.12, 0.5, -0.12, 0.12, solid(belly, 4))
    for i in range(4): pair(p, 0.16 + i * 0.03, -0.08, 0.16, 0.175 + i * 0.03, 0.06, 0.168, solid(_c(belly, -150), 2))   # gills
    eyes(p, 0.43, 0.48, 0.02, 0.07, 0.125, (16, 16, 16), (200, 40, 40), depth=0.02)
    p.add(0.33, -0.11, -0.1, 0.56, -0.1, 0.1, MOUTH)                             # inside the mouth
    for i in range(6): p.add(0.36 + i * 0.033, -0.1, -0.09, 0.38 + i * 0.033, -0.07, 0.09, TEETH)   # upper teeth
    staircase(p, -0.14, 0.12, 0.15, dorsal, -0.02, 0.02, fin, 4, 0.1)            # the dorsal fin
    staircase(p, -0.34, -0.22, 0.15, 0.08, -0.015, 0.015, fin, 2)
    side_bone(p, "fin", (0.12, -0.1, 0.16), [((0.02, -0.13, 0.16, 0.22, -0.09, 0.36), fin), ((-0.04, -0.14, 0.32, 0.08, -0.1, 0.42), fin)], True)
    p.bone("jaw", (0.32, -0.1, 0))
    p.add(0.32, -0.16, -0.1, 0.55, -0.1, 0.1, sk)
    for i in range(6): p.add(0.36 + i * 0.03, -0.1, -0.09, 0.38 + i * 0.03, -0.07, 0.09, TEETH)
    p.bone(None)
    if tail:
        p.bone("tail", (-0.42, 0, 0))
        p.add(-0.62, -0.08, -0.08, -0.4, 0.08, 0.08, sk)
        staircase(p, -0.84, -0.6, 0.0, 0.36, -0.02, 0.02, fin, 4, 0.12)          # the upper lobe
        p.add(-0.76, -0.24, -0.02, -0.6, 0.0, 0.02, fin)
        p.bone(None)
        p.anim("tail", "rot", (0, 22, 0), 0.9)
    p.half = {"jaw"}
    p.pivots["body"] = (0, 0, 0)
    p.anim("jaw", "rot", (16, 0, 0), 1.3).anim("finl", "rot", (0, 0, 10), 1.8).anim("finr", "rot", (0, 0, -10), 1.8)
    return p


def fangstalker():
    """A lean shark with a jaw full of hooked teeth."""
    return shark("fangstalker", fish((110, 118, 132), (220, 222, 226), (90, 96, 110), 0.04), finp((90, 96, 110), (140, 146, 160)), (214, 216, 220))


def burrowshark():
    """Lies buried in the sand with only its fin showing."""
    return shark("burrowshark", W.stripes_h((204, 172, 122), (150, 64, 52), 3), finp((160, 70, 56), (210, 120, 90)), (232, 216, 184), dorsal=0.42)


def blastfin():
    """A spiky little fish full of volatile gas."""
    p = Part("blastfin", 16, 128)
    sk = fish((240, 150, 40), (255, 220, 150), (200, 90, 20), 0.08)
    rounded(p, -0.28, -0.26, -0.26, 0.3, 0.26, 0.26, sk, 0.1)
    for (x, y, z) in ((0, 0.3, 0.15), (0, 0.3, -0.15), (0.15, 0.28, 0), (-0.15, 0.28, 0), (0, -0.3, 0.15), (0, -0.3, -0.15),
                      (-0.15, 0, 0.3), (0.1, 0, 0.3), (-0.15, 0, -0.3), (0.1, 0, -0.3), (0.12, 0.2, 0.22), (-0.12, -0.2, -0.22),
                      (0.12, -0.2, 0.22), (-0.12, 0.2, -0.22)):
        nx, ny, nz = [0.06 if abs(v) > 0.25 else 0.025 for v in (x, y, z)]
        p.add(x - nx, y - ny, z - nz, x + nx, y + ny, z + nz, solid((255, 232, 120), 2))   # spines
    p.add(-0.05, -0.08, -0.27, 0.05, 0.08, 0.27, W.glow((255, 80, 40)))           # the glowing belly band
    eyes(p, 0.16, 0.26, 0.04, 0.14, 0.26, (250, 250, 240), (30, 20, 10))
    p.add(0.3, -0.06, -0.05, 0.34, 0.0, 0.05, solid((200, 80, 60), 3))
    side_bone(p, "fin", (0.08, -0.02, 0.26), [((0.02, -0.06, 0.26, 0.14, 0.04, 0.36), finp((250, 120, 40), (255, 200, 120)))], True)
    p.bone("tail", (-0.28, 0, 0))
    p.add(-0.5, -0.15, -0.02, -0.26, 0.15, 0.02, finp((250, 110, 30), (255, 190, 110)))
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("tail", "rot", (0, 30, 0), 0.4).anim("finl", "rot", (0, 0, 30), 0.3).anim("finr", "rot", (0, 0, -30), 0.3)
    p.anim("body", "pos", (0, 0.6, 0), 1.0)
    return p


# --------------------------------------------------------------------------------------------- the caves
def cavecrawler():
    """A spindly crab that scuttles over cave walls."""
    p = Part("cavecrawler", 16, 128)
    sh = W.grad((196, 66, 44), (130, 32, 22), 5, (220, 110, 70), 0.08)
    rounded(p, -0.25, -0.06, -0.3, 0.25, 0.14, 0.3, sh, 0.07)
    rounded(p, -0.18, 0.12, -0.22, 0.18, 0.18, 0.22, sh, 0.05)
    for z in (-0.28, -0.14, 0.0, 0.14, 0.28): p.add(0.24, 0.0, z - 0.02, 0.29, 0.06, z + 0.02, solid((230, 200, 170), 2))   # rim spikes
    p.add(0.2, -0.04, -0.1, 0.27, 0.02, 0.1, MOUTH)
    p.bone("eyes", (0.18, 0.14, 0))
    for s in (-1, 1):
        p.add(0.17, 0.14, s * 0.1 - 0.02, 0.21, 0.28, s * 0.1 + 0.02, solid((180, 60, 40), 3))
        p.add(0.16, 0.27, s * 0.1 - 0.035, 0.23, 0.33, s * 0.1 + 0.035, W.glow((240, 230, 120)))
    p.bone(None)
    legs = []
    for i in range(3):
        x = -0.15 + i * 0.13
        legs += [((x - 0.02, -0.04, 0.3, x + 0.02, 0.02, 0.52), solid((170, 52, 32), 3)),
                 ((x - 0.02, -0.32, 0.49, x + 0.02, -0.02, 0.53), solid((150, 44, 28), 3))]
    side_bone(p, "leg", (0.0, -0.02, 0.3), legs, True)
    side_bone(p, "claw", (0.25, 0.0, 0.2), [((0.25, -0.03, 0.18, 0.42, 0.04, 0.24), sh), ((0.4, -0.08, 0.13, 0.58, 0.09, 0.3), sh),
                                           ((0.55, 0.0, 0.14, 0.66, 0.06, 0.2), solid((240, 220, 200), 3))], True)
    p.anim("legl", "rot", (0, 12, 8), 0.5).anim("legr", "rot", (0, -12, -8), 0.5, 0.5).anim("eyes", "rot", (0, 0, 8), 1.6)
    p.anim("clawl", "rot", (0, 10, 0), 1.2).anim("clawr", "rot", (0, -10, 0), 1.2, 0.5)
    return p


def glowgrub():
    """A soft grub that glows: its rings ripple as it crawls."""
    p = Part("glowgrub", 16, 128)
    ring = lambda i: W.glow((250, 230, 110)) if i % 2 else solid((224, 204, 128), 4)
    def seg(x, r, i): rounded(p, x, -0.14, -r, x + 0.15, -0.14 + r * 1.6, r, ring(i), r * 0.35)
    seg(-0.08, 0.15, 2)
    p.bone("head", (0.07, 0, 0))
    seg(0.07, 0.14, 3); seg(0.21, 0.12, 4)
    p.add(0.35, -0.12, -0.1, 0.44, 0.06, 0.1, solid((210, 190, 110), 4))
    eyes(p, 0.38, 0.42, -0.02, 0.03, 0.1, (30, 30, 20), (250, 250, 250), depth=0.02)
    for s in (-1, 1): p.add(0.42, 0.06, s * 0.04 - 0.012, 0.46, 0.2, s * 0.04 + 0.012, solid((200, 180, 100), 3))   # feelers
    p.bone(None)
    p.bone("tail", (-0.08, 0, 0))
    seg(-0.22, 0.13, 1); seg(-0.35, 0.1, 0)
    p.bone(None)
    for x in (-0.25, -0.05, 0.15): pair(p, x, -0.17, 0.08, x + 0.05, -0.12, 0.15, solid((200, 170, 90), 3))   # stubby legs
    p.anim("head", "rot", (0, 12, 0), 1.4).anim("tail", "rot", (0, -12, 0), 1.4)
    return p


def shellsnake():
    """Coils inside cracks in the rock: all teeth and armour plates."""
    p = Part("shellsnake", 16, 128)
    pl = W.grad((48, 136, 136), (22, 72, 82), 5, (90, 170, 160), 0.05)
    plate = solid((186, 206, 196), 3)
    def seg(x, r):
        rounded(p, x, -r, -r, x + 0.16, r, r, pl, r * 0.3)
        p.add(x + 0.02, r - 0.01, -r * 0.65, x + 0.14, r + 0.04, r * 0.65, plate)
    p.add(0.15, -0.12, -0.12, 0.5, 0.12, 0.12, pl)                                # the head
    p.add(0.18, 0.11, -0.09, 0.46, 0.15, 0.09, plate)
    eyes(p, 0.36, 0.42, 0.02, 0.07, 0.12, (250, 230, 60), (10, 10, 10), slit=True, depth=0.02)
    p.add(0.4, -0.1, -0.09, 0.56, -0.08, 0.09, MOUTH)
    for i in range(4): p.add(0.42 + i * 0.03, -0.08, -0.08, 0.44 + i * 0.03, -0.05, 0.08, TEETH)
    p.bone("jaw", (0.38, -0.1, 0))
    p.add(0.38, -0.15, -0.09, 0.56, -0.1, 0.09, pl)
    for i in range(4): p.add(0.42 + i * 0.03, -0.1, -0.08, 0.44 + i * 0.03, -0.07, 0.08, TEETH)
    p.bone(None)
    seg(0.0, 0.1)
    p.bone("mid", (0.0, 0, 0)); seg(-0.15, 0.095); seg(-0.3, 0.09); p.bone(None)
    p.bone("tail", (-0.3, 0, 0)); seg(-0.45, 0.08); seg(-0.6, 0.065); p.add(-0.8, -0.04, -0.02, -0.6, 0.04, 0.02, pl); p.bone(None)
    p.half = {"jaw"}
    p.anim("mid", "rot", (0, 14, 0), 1.0).anim("tail", "rot", (0, 22, 0), 1.0, 0.25).anim("jaw", "rot", (14, 0, 0), 2.0)
    return p


# --------------------------------------------------------------------------------------------- the Abyss
def lampsquid():
    """A squid with lanterns for eyes."""
    p = Part("lampsquid", 16, 256)
    sk = W.grad((48, 78, 120), (22, 40, 70), 5, (120, 255, 240), 0.03)
    rounded(p, -0.58, -0.13, -0.15, 0.05, 0.16, 0.15, sk, 0.06)                  # the mantle (it swims mantle-first)
    p.add(-0.66, -0.07, -0.08, -0.56, 0.08, 0.08, sk)
    p.add(0.04, -0.12, -0.13, 0.2, 0.12, 0.13, sk)                                 # the head
    for s in (-1, 1):
        a, b = sorted((s * 0.13, s * 0.17))
        p.add(0.06, -0.04, a, 0.17, 0.09, b, W.glow((120, 255, 240)))            # lantern eyes
    side_bone(p, "fin", (-0.45, 0.0, 0.15), [((-0.62, -0.01, 0.15, -0.34, 0.03, 0.34), W.membrane((60, 100, 150), (120, 255, 240)))], True)
    p.bone("arms", (0.2, 0, 0))
    for i in range(8):
        a = i * math.pi / 4
        y, z = math.sin(a) * 0.08, math.cos(a) * 0.08
        p.add(0.2, y - 0.022, z - 0.022, 0.58, y + 0.022, z + 0.022, solid((70, 100, 150), 3))
        p.add(0.56, y - 0.03, z - 0.03, 0.62, y + 0.03, z + 0.03, W.glow((120, 255, 240)))   # glowing tips
    for s in (-1, 1):
        p.add(0.2, -0.015, s * 0.03 - 0.015, 0.92, 0.015, s * 0.03 + 0.015, solid((60, 90, 140), 3))   # two long tentacles
        p.add(0.88, -0.04, s * 0.03 - 0.04, 1.0, 0.04, s * 0.03 + 0.04, solid((80, 120, 180), 3))
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("arms", "rot", (0, 10, 10), 1.4).anim("finl", "rot", (0, 0, 22), 1.0).anim("finr", "rot", (0, 0, -22), 1.0)
    p.anim("body", "pos", (0, 0.8, 0), 1.4, 0.5)
    return p


def volteel():
    """A long eel crackling with power."""
    p = Part("volteel", 16, 128)
    sk = W.bands((230, 210, 60), (54, 46, 20), 3)
    spark = W.glow((150, 230, 255))
    def seg(x0, x1, r):
        p.add(x0, -r, -r, x1, r, r, sk)
        p.add(x0, r, -0.01, x1, r + 0.03, 0.01, solid((40, 36, 16), 3))          # the ridge
        pair(p, x0 + 0.02, -0.01, r, x1 - 0.02, 0.02, r + 0.01, spark)
    p.add(0.36, -0.07, -0.07, 0.56, 0.06, 0.07, sk)                                # the head
    eyes(p, 0.48, 0.52, 0.01, 0.05, 0.07, (120, 220, 255), (255, 255, 255), depth=0.02)
    p.add(0.52, -0.06, -0.05, 0.58, -0.03, 0.05, MOUTH)
    seg(0.2, 0.37, 0.08)
    p.bone("s1", (0.2, 0, 0)); seg(0.03, 0.2, 0.075); seg(-0.14, 0.03, 0.07); p.bone(None)
    p.bone("s2", (-0.14, 0, 0)); seg(-0.31, -0.14, 0.064); seg(-0.48, -0.31, 0.058); p.bone(None)
    p.bone("s3", (-0.48, 0, 0)); seg(-0.65, -0.48, 0.05); p.add(-0.85, -0.08, -0.012, -0.64, 0.08, 0.012, finp((200, 180, 50), (255, 240, 120))); p.bone(None)
    p.anim("s1", "rot", (0, 10, 0), 0.9).anim("s2", "rot", (0, 16, 0), 0.9, 0.15).anim("s3", "rot", (0, 24, 0), 0.9, 0.3)
    return p


def needlejaw():
    """A black fish with a lure of light and teeth like needles."""
    p = Part("needlejaw", 16, 128)
    sk = W.grad((30, 22, 38), (14, 10, 20), 4, (70, 50, 110), 0.04)
    rounded(p, -0.3, -0.18, -0.2, 0.26, 0.24, 0.2, sk, 0.09)
    rounded(p, 0.0, 0.16, -0.17, 0.26, 0.28, 0.17, sk, 0.06)                      # the great brow
    p.add(0.24, -0.04, -0.16, 0.36, -0.02, 0.16, MOUTH)
    for z in (-0.13, -0.08, -0.03, 0.02, 0.07, 0.12): p.add(0.32, -0.12, z, 0.35, -0.02, z + 0.02, TEETH)   # upper needles
    eyes(p, 0.15, 0.21, 0.06, 0.12, 0.2, (180, 120, 255), (250, 250, 255), depth=0.02)
    p.bone("jaw", (0.12, -0.16, 0))
    p.add(0.12, -0.24, -0.18, 0.4, -0.14, 0.18, sk)                                # the underbite
    for z in (-0.15, -0.1, -0.05, 0.0, 0.05, 0.1, 0.14): p.add(0.36, -0.14, z, 0.39, 0.02, z + 0.02, TEETH)   # lower needles
    p.bone(None)
    p.bone("lure", (0.1, 0.26, 0))
    p.add(0.08, 0.26, -0.015, 0.11, 0.52, 0.015, sk)
    p.add(0.11, 0.5, -0.015, 0.3, 0.53, 0.015, sk)
    p.add(0.28, 0.42, -0.055, 0.39, 0.53, 0.055, W.glow((200, 160, 255)))          # the bulb
    p.bone(None)
    side_bone(p, "fin", (0.0, -0.06, 0.2), [((-0.08, -0.1, 0.2, 0.1, -0.02, 0.32), finp((40, 30, 60), (90, 70, 140)))], True)
    p.bone("tail", (-0.3, 0, 0))
    p.add(-0.42, -0.08, -0.05, -0.28, 0.08, 0.05, sk)
    p.add(-0.62, -0.18, -0.02, -0.4, 0.18, 0.02, finp((40, 30, 60), (90, 70, 140)))
    p.bone(None)
    p.half = {"jaw"}
    p.anim("jaw", "rot", (18, 0, 0), 2.4).anim("lure", "rot", (8, 0, 10), 1.8).anim("tail", "rot", (0, 20, 0), 0.8)
    p.anim("finl", "rot", (0, 0, 20), 0.6).anim("finr", "rot", (0, 0, -20), 0.6)
    return p


def devilfish():
    """Wears a mask of bone that turns most hits aside."""
    p = Part("devilfish", 16, 128)
    sk = fish((140, 32, 44), (210, 120, 110), (80, 10, 20), 0.06)
    bone_c = solid((232, 222, 192), 4)
    rounded(p, -0.4, -0.18, -0.16, 0.24, 0.2, 0.16, sk, 0.07)
    p.add(0.22, -0.2, -0.18, 0.37, 0.22, 0.18, bone_c)                            # the bone mask
    p.add(0.36, -0.14, -0.12, 0.4, 0.16, 0.12, bone_c)
    for s in (-1, 1):
        a, b = sorted((s * 0.06, s * 0.14))
        p.add(0.39, 0.04, a, 0.41, 0.12, b, solid((20, 0, 0), 2))                 # eye holes
        a2, b2 = sorted((s * 0.08, s * 0.12))
        p.add(0.405, 0.06, a2, 0.415, 0.1, b2, W.glow((255, 60, 40)))             # the eyes burning in them
        c0, c1 = sorted((s * 0.12, s * 0.17))
        p.add(0.16, 0.2, c0, 0.28, 0.32, c1, bone_c); p.add(0.08, 0.3, c0, 0.18, 0.44, c1, bone_c)   # horns sweeping back
    p.add(0.38, -0.15, -0.1, 0.42, -0.06, 0.1, solid((20, 0, 0), 2))              # the grille
    for i in range(5): p.add(-0.3 + i * 0.1, 0.19, -0.015, -0.26 + i * 0.1, 0.32 - abs(i - 2) * 0.03, 0.015, solid((90, 12, 24), 3))   # spines
    side_bone(p, "fin", (0.05, -0.08, 0.16), [((-0.04, -0.12, 0.16, 0.14, -0.04, 0.3), finp((90, 12, 24), (170, 60, 70)))], True)
    p.bone("tail", (-0.4, 0, 0))
    p.add(-0.52, -0.09, -0.06, -0.38, 0.09, 0.06, sk)
    p.add(-0.72, -0.2, -0.02, -0.5, 0.2, 0.02, finp((90, 12, 24), (170, 60, 70)))
    p.bone(None)
    p.anim("tail", "rot", (0, 18, 0), 1.0).anim("finl", "rot", (0, 0, 16), 0.8).anim("finr", "rot", (0, 0, -16), 0.8)
    return p


def riftwarper():
    """It blinks through the water: a sliver of purple with glowing rifts down its sides."""
    p = Part("riftwarper", 16, 128)
    sk = W.glow_lines((54, 18, 82), (210, 120, 255), 4, 0.02)
    mem = W.membrane((140, 60, 200), (230, 150, 255))
    rounded(p, -0.32, -0.12, -0.11, 0.3, 0.13, 0.11, sk, 0.05)
    p.add(0.28, -0.08, -0.08, 0.44, 0.08, 0.08, sk)
    eyes(p, 0.32, 0.38, 0.0, 0.06, 0.08, (255, 200, 255), (60, 0, 80), depth=0.02)
    p.add(-0.2, -0.04, -0.12, 0.2, 0.04, 0.12, W.glow((230, 150, 255)))          # the rift down its middle
    p.bone("fint", (0.0, 0.13, 0)); p.add(-0.2, 0.13, -0.012, 0.16, 0.4, 0.012, mem); p.bone(None)
    p.bone("finb", (0.0, -0.12, 0)); p.add(-0.2, -0.38, -0.012, 0.16, -0.12, 0.012, mem); p.bone(None)
    side_bone(p, "w", (0.0, 0.0, 0.11), [((-0.12, -0.015, 0.11, 0.12, 0.015, 0.46), mem)], True)
    p.bone("tail", (-0.32, 0, 0)); p.add(-0.6, -0.2, -0.012, -0.3, 0.2, 0.012, W.glow((190, 100, 240))); p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("fint", "rot", (0, 12, 0), 0.8).anim("finb", "rot", (0, -12, 0), 0.8).anim("tail", "rot", (0, 20, 0), 0.8, 0.25)
    p.anim("wl", "rot", (0, 0, 24), 1.0).anim("wr", "rot", (0, 0, -24), 1.0)
    return p


def hollowkin():
    """Shadows of children, drifting in the dark."""
    p = Part("hollowkin", 16, 128)
    sh = W.cloth((30, 30, 40))
    rounded(p, -0.12, -0.14, -0.16, 0.12, 0.26, 0.16, sh, 0.05)                  # the robe
    rounded(p, -0.15, 0.24, -0.15, 0.15, 0.54, 0.15, sh, 0.06)                   # the hood
    p.add(0.1, 0.28, -0.1, 0.16, 0.5, 0.1, solid((6, 6, 10), 2))                  # the dark inside the hood
    for s in (-1, 1):
        a, b = sorted((s * 0.03, s * 0.08))
        p.add(0.155, 0.38, a, 0.165, 0.43, b, W.glow((240, 240, 255)))            # two empty white eyes
    p.bone("hem", (0, -0.14, 0))
    for i, z in enumerate((-0.14, -0.07, 0.0, 0.07)):
        p.add(-0.14, -0.46 + 0.05 * (i % 2), z, 0.14, -0.13, z + 0.07, W.cloth((34, 34, 46)))   # a ragged hem
    p.bone(None)
    side_bone(p, "arm", (0.0, 0.22, 0.16), [((-0.05, -0.08, 0.16, 0.05, 0.22, 0.22), sh), ((-0.03, -0.14, 0.17, 0.04, -0.08, 0.21), solid((10, 10, 14), 2))], True)
    p.pivots["body"] = (0, 0, 0)
    p.anim("hem", "rot", (6, 0, 6), 2.6).anim("arml", "rot", (0, 0, 10), 2.6).anim("armr", "rot", (0, 0, -10), 2.6, 0.5)
    p.anim("body", "pos", (0, 1.0, 0), 2.6)
    return p


def sharkquid():
    """Half shark, half squid, all hunger."""
    p = shark("sharkquid", fish((50, 82, 152), (180, 200, 232), (30, 50, 100), 0.05), finp((30, 50, 100), (80, 110, 190)), (184, 204, 234), tail=False)
    p.add(-0.6, -0.1, -0.1, -0.4, 0.1, 0.1, fish((50, 82, 152), (180, 200, 232)))
    p.bone("arms", (-0.58, 0, 0))
    for i in range(6):
        a = i * math.pi / 3
        y, z = math.sin(a) * 0.06, math.cos(a) * 0.06
        p.add(-1.04, y - 0.02, z - 0.02, -0.58, y + 0.02, z + 0.02, solid((70, 110, 190), 3))
        p.add(-1.08, y - 0.03, z - 0.03, -1.02, y + 0.03, z + 0.03, solid((200, 220, 255), 2))   # suckered tips
    p.bone(None)
    p.anim("arms", "rot", (0, 14, 10), 1.1)
    return p


MOBS = [gawker, bloatfin, jellywing, driftback, clam, lumenbell, gasbloom, fangstalker, blastfin, burrowshark,
        cavecrawler, glowgrub, shellsnake, lampsquid, volteel, needlejaw, devilfish, riftwarper, hollowkin, sharkquid]


# =====================================================================================================================
def java_model(part, boxes):
    """16 units per block, the part's origin at (8, 8, 8); local axes as the wild models (Wild's Q0/FLIP turn them)."""
    s = 16.0 / part.size
    def U(p): return [round(p[0] * 16 + 8, 4), round(p[1] * 16 + 8, 4), round(p[2] * 16 + 8, 4)]
    els = []
    for (a, b), regs in boxes:
        e = {"from": U(a), "to": U(b), "faces": {}}
        for key, (x, y, w, h) in regs.items():
            e["faces"][W.FACES[key][0]] = {"uv": [round(x * s, 4), round(y * s, 4), round((x + w) * s, 4), round((y + h) * s, 4)], "texture": "#t"}
        els.append(e)
    for e in els:
        for k in ("from", "to"):
            for i in range(3):
                if not -16 <= e[k][i] <= 32: raise SystemExit(f"{part.name}: outside -16..32 ({e[k]})")
    return {"textures": {"t": f"{NS}:item/deep/{part.name}", "particle": f"{NS}:item/deep/{part.name}"}, "elements": els}


def write_parts(parts):
    """DeepParts.java for FaultlineDeep: each creature's bones (pivot, in blocks) and their animations, exactly as the
    Bedrock pack plays them (rotation in degrees / position in pixels, Bedrock axes), so Java moves the same."""
    f = lambda v: f"{float(v):.4f}f"
    lines = []
    for part in parts:
        bones = [b for b, owner in ((b, b) for b in part.pivots) if b != "body" and b in set(part.bone_of)]
        bl = ", ".join(f'new Bone("{b}", {f(part.pivots[b][0])}, {f(part.pivots[b][1])}, {f(part.pivots[b][2])})' for b in bones)
        lines.append(f'        BONES.put("{part.name}", List.of({bl}));')
        half = getattr(part, "half", set())
        al = ", ".join(f'new Anim("{bn}", {str(kind == "rot").lower()}, {f(a[0])}, {f(a[1])}, {f(a[2])}, {f(secs)}, {f(ph)}, {str(bn in half).lower()})'
                       for (bn, kind, a, secs, ph) in part.anims)
        lines.append(f'        ANIMS.put("{part.name}", List.of({al}));')
        bp = part.pivots.get("body", (0, 0, 0))
        lines.append(f'        BODY_PIVOT.put("{part.name}", new float[]{{{f(bp[0])}, {f(bp[1])}, {f(bp[2])}}});')
    src = """package net.faultlinesmp.deep;

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
%LINES%
    }
}
""".replace("%LINES%", "\n".join(lines))
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "plugins", "FaultlineDeep", "src", "main", "java",
                       "net", "faultlinesmp", "deep", "DeepParts.java")
    with open(out, "w") as fh: fh.write(src)


def bag_icon():
    g = [
        "................",
        "......1111......",
        ".....1%%%%1.....",
        "......1%%1......",
        ".....222222.....",
        "....2&&&&&&2....",
        "...2&&~~~~&&2...",
        "..2&&~~%%~~&&2..",
        "..2&~~%ll%~~&2..",
        "..2&~~%ll%~~&2..",
        "..2&&~~%%~~&&2..",
        "..2&&&~~~~&&&2..",
        "...2&&&&&&&&2...",
        "....22222222....",
        "................",
        "................",
    ]
    return W.draw(g)


def main():
    pack, bout = sys.argv[1], sys.argv[2]
    prev = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    music = sys.argv[sys.argv.index("--music") + 1] if "--music" in sys.argv else None
    A = os.path.join(pack, "assets", NS)
    rp = os.path.join(bout, "rp_deep")
    if os.path.isdir(rp): shutil.rmtree(rp)
    mappings = {"minecraft:paper": []}
    item_tex = {}

    def bed_icon(key, img):
        p = os.path.join(rp, "textures/items/faultline", key + ".png"); os.makedirs(os.path.dirname(p), exist_ok=True); img.save(p)
        item_tex[f"faultline.{key}"] = {"textures": f"textures/items/faultline/{key}"}
        return f"faultline.{key}"

    built = []
    icons = {}
    for i, fn in enumerate(MOBS):
        part = fn()
        img, boxes = W.build(part, 5000 + i)
        built.append((part, img, boxes))
        n = part.name
        p = os.path.join(A, "textures/item/deep", n + ".png"); os.makedirs(os.path.dirname(p), exist_ok=True); img.save(p)
        # Java: the body (boxes in no bone) as deep/<id>, and each bone as deep/<id>__<bone> with its pivot at the origin
        for bn in [None] + [b for b in part.pivots if b != "body"]:
            piv = (0, 0, 0) if bn is None else part.pivots[bn]
            sub = [(((a[0] - piv[0], a[1] - piv[1], a[2] - piv[2]), (b[0] - piv[0], b[1] - piv[1], b[2] - piv[2])), regs)
                   for ((a, b), regs), owner in zip(boxes, part.bone_of) if owner == bn]
            if not sub and bn is not None: continue
            mn = n if bn is None else f"{n}__{bn}"
            model = java_model(part, sub)
            model["textures"] = {"t": f"{NS}:item/deep/{n}", "particle": f"{NS}:item/deep/{n}"}
            W.jw(os.path.join(A, "models/item/deep", mn + ".json"), model)
            W.jw(os.path.join(A, "items/deep", mn + ".json"), {"model": {"type": "minecraft:model", "model": f"{NS}:item/deep/{mn}"}})
        icon = W.render_icon([(part, img, boxes, lambda q: q)])
        icons[n] = icon
        W.flat_item(A, n + "_icon", icon, folder="deep")
        # Bedrock: the worn model + the egg icon
        t = os.path.join(rp, "textures/faultline/deep", n + ".png"); os.makedirs(os.path.dirname(t), exist_ok=True); img.save(t)
        geo = W.bedrock_geo(part, boxes)
        geo["minecraft:geometry"][0]["description"]["identifier"] = f"geometry.faultline.deep_{n}"
        W.jw(os.path.join(rp, "models/entity/faultline", f"deep_{n}.geo.json"), geo)
        desc = {"identifier": f"faultline:deep_{n}", "materials": {"default": "armor", "enchanted": "armor_enchanted"},
                "textures": {"default": f"textures/faultline/deep/{n}", "enchanted": "textures/misc/enchanted_actor_glint"},
                "geometry": {"default": f"geometry.faultline.deep_{n}"}, "scripts": {"parent_setup": "v.helmet_layer_visible = 0.0;"},
                "render_controllers": ["controller.render.armor"]}
        if part.anims:
            an = W.bedrock_anim(part)
            an = {"format_version": an["format_version"], "animations": {k.replace("wild_", "deep_"): v for k, v in an["animations"].items()}}
            W.jw(os.path.join(rp, "animations", f"faultline.deep_{n}.animation.json"), an)
            short = {f"a{j}": k for j, k in enumerate(an["animations"])}
            desc["animations"] = short
            desc["scripts"]["animate"] = list(short)
        W.jw(os.path.join(rp, "attachables", f"faultline.deep_{n}.json"), {"format_version": "1.10.0", "minecraft:attachable": {"description": desc}})
        ic = bed_icon("deep_" + n, icon)
        mappings["minecraft:paper"].append({"type": "definition", "model": f"{NS}:deep/{n}", "bedrock_identifier": f"faultline:deep_{n}",
            "display_name": n, "bedrock_options": {"icon": ic, "allow_offhand": False},
            "components": {"minecraft:equippable": {"slot": "head"}, "minecraft:max_stack_size": 1}})
        mappings["minecraft:paper"].append({"type": "definition", "model": f"{NS}:deep/{n}_icon", "bedrock_identifier": f"faultline:deep_{n}_egg",
            "display_name": n + " egg", "bedrock_options": {"icon": ic, "allow_offhand": True}, "components": {"minecraft:max_stack_size": 64}})
    # the Dark Loot Bag (FaultlineItems DarkLoot, paper + faultline:dark_loot_bag)
    bag = bag_icon()
    W.flat_item(A, "dark_loot_bag", bag)
    mappings["minecraft:paper"].append({"type": "definition", "model": f"{NS}:dark_loot_bag", "bedrock_identifier": "faultline:dark_loot_bag",
        "display_name": "Dark Loot Bag", "bedrock_options": {"icon": bed_icon("dark_loot_bag", bag), "allow_offhand": True},
        "components": {"minecraft:max_stack_size": 64}})
    # Index icons
    index = {"deep_" + k: v for k, v in icons.items()}
    index["dark_loot_bag"] = bag
    for k, im in index.items(): im.save(os.path.join(A, "textures/index", k + ".png"))
    W.glyphs(A, index)
    # music for Bedrock (Java gets FaultlineDeepMusic.zip)
    if music:
        sd = {}
        for nm in ("sunken", "abyss"):
            src = os.path.join(music, nm + ".ogg")
            if not os.path.exists(src): continue
            dst = os.path.join(rp, "sounds/faultline/deep", nm + ".ogg"); os.makedirs(os.path.dirname(dst), exist_ok=True); shutil.copy(src, dst)
            sd[f"faultline:deep.{nm}"] = {"category": "music", "sounds": [{"name": f"sounds/faultline/deep/{nm}", "stream": True}]}
        W.jw(os.path.join(rp, "sounds/sound_definitions.json"), {"format_version": "1.14.0", "sound_definitions": sd})
    W.jw(os.path.join(rp, "textures/item_texture.json"), {"resource_pack_name": "faultline_deep", "texture_name": "atlas.items", "texture_data": item_tex})
    W.jw(os.path.join(rp, "manifest.json"), {"format_version": 2, "header": {
        "name": "Faultline SMP Dark Ocean", "description": "The Dark Ocean's creatures, items and music for Bedrock players (Geyser)",
        "uuid": "b3e1c7a4-2f5d-4c8e-9a61-5d0e7f2b9c34", "version": [1, 0, 0], "min_engine_version": [1, 21, 0]},
        "modules": [{"description": "Deep", "type": "resources", "uuid": "e7d2a9f1-4b6c-4e3a-8f15-9c2b0a6d3e78", "version": [1, 0, 0]}]})
    out = os.path.join(bout, "FaultlineDeep.mcpack")
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(rp):
            for f in sorted(files):
                full = os.path.join(root, f); z.write(full, os.path.relpath(full, rp))
    from mcpack_version import stamp; stamp(out)
    W.jw(os.path.join(bout, "faultline_deep_mappings.json"), {"format_version": 2, "items": mappings})
    write_parts([b[0] for b in built])
    shutil.rmtree(rp)
    print(f"{len(MOBS)} creatures, Bedrock pack {out}")
    if prev:
        os.makedirs(prev, exist_ok=True)
        tiles = []
        for part, img, boxes in built:
            tiles.append((part.name, W.render_icon([(part, img, boxes, lambda q: q)], size=180)))
        sheet = Image.new("RGBA", (5 * 190, 4 * 210), (18, 30, 50, 255))
        d = ImageDraw.Draw(sheet)
        for i, (n, t) in enumerate(tiles):
            sheet.alpha_composite(t, ((i % 5) * 190 + 5, (i // 5) * 210 + 5)); d.text(((i % 5) * 190 + 8, (i // 5) * 210 + 190), n, fill=(230, 230, 230))
        sheet.save(os.path.join(prev, "deep_creatures.png"))
        ic = list(index.items())
        s2 = Image.new("RGBA", (11 * 68, 2 * 68), (200, 180, 140, 255))
        for i, (k, im) in enumerate(ic): s2.alpha_composite(im.resize((64, 64), Image.NEAREST), ((i % 11) * 68 + 2, (i // 11) * 68 + 2))
        s2.save(os.path.join(prev, "deep_icons.png"))


if __name__ == "__main__":
    main()
