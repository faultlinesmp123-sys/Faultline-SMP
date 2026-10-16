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


# --------------------------------------------------------------------------------------------- the Sulphur Sea (1.4.0)
# Acid creatures, after the Calamity mod's Sulphurous Sea / Acid Rain. Olive and bile skins, glowing lime and sulphur.
OLIVE, OLIVE_D, BILE, LIME, SULF, RUST, BONEW = (86, 98, 44), (44, 52, 24), (130, 140, 60), (170, 255, 80), (240, 212, 60), (150, 84, 40), (226, 220, 196)
ACIDGLOW = W.glow(LIME)
SULFGLOW = W.glow(SULF)


def acideel():
    """A slick olive eel with a glowing lime stripe; its bite burns."""
    p = Part("acideel", 16, 128)
    sk = W.grad(OLIVE, OLIVE_D, 4, LIME, 0.04)
    def seg(x0, x1, r):
        p.add(x0, -r, -r, x1, r, r, sk)
        p.add(x0, r, -0.012, x1, r + 0.035, 0.012, finp(OLIVE_D, LIME))                  # the dorsal frill
        pair(p, x0 + 0.02, 0.0, r, x1 - 0.02, 0.025, r + 0.01, ACIDGLOW)                   # the lateral line
    p.add(0.36, -0.075, -0.075, 0.58, 0.065, 0.075, sk)                                    # the head
    p.add(0.4, 0.06, -0.05, 0.55, 0.08, 0.05, solid(OLIVE_D, 3))
    eyes(p, 0.47, 0.52, 0.01, 0.055, 0.075, (230, 255, 120), (20, 20, 10), slit=True, depth=0.02)
    p.add(0.5, -0.07, -0.06, 0.6, -0.04, 0.06, MOUTH)
    for i in range(3): p.add(0.52 + i * 0.025, -0.05, -0.05, 0.535 + i * 0.025, -0.02, 0.05, TEETH)
    p.bone("jaw", (0.48, -0.06, 0))
    p.add(0.48, -0.1, -0.06, 0.6, -0.06, 0.06, sk)
    p.bone(None)
    seg(0.2, 0.37, 0.085)
    p.bone("s1", (0.2, 0, 0)); seg(0.03, 0.2, 0.08); seg(-0.14, 0.03, 0.075); p.bone(None)
    p.bone("s2", (-0.14, 0, 0)); seg(-0.31, -0.14, 0.068); seg(-0.48, -0.31, 0.06); p.bone(None)
    p.bone("s3", (-0.48, 0, 0)); seg(-0.65, -0.48, 0.05); p.add(-0.86, -0.09, -0.012, -0.64, 0.09, 0.012, finp(OLIVE_D, LIME)); p.bone(None)
    p.half = {"jaw"}
    p.anim("s1", "rot", (0, 12, 0), 0.8).anim("s2", "rot", (0, 18, 0), 0.8, 0.15).anim("s3", "rot", (0, 26, 0), 0.8, 0.3).anim("jaw", "rot", (12, 0, 0), 1.6)
    return p


def trasher():
    """A crocodile of the acid shallows: armoured back, a long toothy snout, a heavy tail."""
    p = Part("trasher", 16, 256)
    sk = W.grad((70, 84, 46), (36, 44, 22), 5, (110, 130, 60), 0.06)
    scute = solid((54, 64, 34), 4)
    rounded(p, -0.3, -0.12, -0.18, 0.2, 0.1, 0.18, sk, 0.06)                              # the body
    for x in (-0.25, -0.15, -0.05, 0.05, 0.15):
        for z in (-0.1, 0.0, 0.1): p.add(x, 0.09, z - 0.03, x + 0.07, 0.13, z + 0.03, scute)   # back scutes
    p.add(-0.32, -0.13, -0.15, 0.22, -0.11, 0.15, solid((170, 170, 110), 4))               # the pale belly
    p.bone("head", (0.2, 0, 0))
    p.add(0.2, -0.07, -0.11, 0.36, 0.08, 0.11, sk)
    p.add(0.36, -0.04, -0.07, 0.62, 0.04, 0.07, sk)                                        # the snout
    eyes(p, 0.28, 0.33, 0.06, 0.11, 0.1, (240, 200, 40), (10, 10, 10), slit=True, depth=0.02)
    for s in (-1, 1): p.add(0.27, 0.08, s * 0.08 - 0.03, 0.34, 0.11, s * 0.08 + 0.03, scute)   # brow knobs
    for i in range(8): p.add(0.38 + i * 0.03, -0.05, -0.075, 0.395 + i * 0.03, -0.02, 0.075, TEETH)
    p.bone("jaw", (0.32, -0.04, 0))
    p.add(0.32, -0.09, -0.07, 0.6, -0.04, 0.07, sk)
    for i in range(7): p.add(0.38 + i * 0.03, -0.04, -0.07, 0.395 + i * 0.03, -0.01, 0.07, TEETH)
    p.bone("head")
    p.bone(None)
    for x, nm in ((0.12, "front"), (-0.22, "back")):
        side_bone(p, nm, (x, -0.06, 0.18), [((x - 0.04, -0.2, 0.18, x + 0.04, -0.04, 0.24), sk), ((x - 0.02, -0.23, 0.2, x + 0.1, -0.19, 0.28), solid(OLIVE_D, 3))], True)
    p.bone("tail", (-0.3, 0, 0))
    p.add(-0.55, -0.09, -0.1, -0.3, 0.07, 0.1, sk)
    p.add(-0.85, -0.06, -0.06, -0.55, 0.05, 0.06, sk)
    for x in (-0.8, -0.7, -0.6, -0.5, -0.4): p.add(x, 0.05, -0.012, x + 0.05, 0.11, 0.012, scute)   # tail crest
    p.bone(None)
    p.half = {"jaw"}
    p.anim("tail", "rot", (0, 20, 0), 1.2).anim("jaw", "rot", (22, 0, 0), 2.2).anim("head", "rot", (0, 6, 0), 2.4)
    p.anim("frontl", "rot", (0, 0, 18), 0.8).anim("frontr", "rot", (0, 0, -18), 0.8, 0.5).anim("backl", "rot", (0, 0, 18), 0.8, 0.5).anim("backr", "rot", (0, 0, -18), 0.8)
    return p


def radiator():
    """A little fish that glows sickly green from the inside: stay too close and it burns."""
    p = Part("radiator", 16, 128)
    sk = fish((90, 120, 50), (180, 220, 120), LIME, 0.06)
    rounded(p, -0.24, -0.16, -0.12, 0.26, 0.18, 0.12, sk, 0.06)
    p.add(-0.18, -0.08, -0.125, 0.18, 0.1, 0.125, ACIDGLOW)                                 # the glowing core shows through
    eyes(p, 0.15, 0.22, 0.04, 0.11, 0.12, (250, 255, 200), (40, 60, 10), depth=0.02)
    p.add(0.25, -0.05, -0.04, 0.3, 0.02, 0.04, MOUTH)
    p.bone("lure", (0.2, 0.18, 0))
    p.add(0.19, 0.18, -0.01, 0.21, 0.36, 0.01, solid(OLIVE_D, 3))
    p.add(0.24, 0.33, -0.035, 0.31, 0.4, 0.035, ACIDGLOW)
    p.add(0.2, 0.35, -0.01, 0.26, 0.37, 0.01, solid(OLIVE_D, 3))
    p.bone(None)
    side_bone(p, "fin", (0.05, -0.04, 0.12), [((-0.02, -0.08, 0.12, 0.12, 0.0, 0.24), finp(OLIVE, LIME))], True)
    p.bone("tail", (-0.24, 0, 0))
    p.add(-0.5, -0.18, -0.015, -0.22, 0.2, 0.015, finp(OLIVE, LIME))
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("tail", "rot", (0, 26, 0), 0.6).anim("finl", "rot", (0, 0, 24), 0.5).anim("finr", "rot", (0, 0, -24), 0.5)
    p.anim("lure", "rot", (0, 0, 10), 1.6).anim("body", "pos", (0, 0.5, 0), 2.0)
    return p


def flakcrab():
    """A crab in a thick rusted shell bristling with flak tubes; it shrugs off hits from the front."""
    p = Part("flakcrab", 16, 256)
    sh = W.grad((150, 94, 46), (90, 50, 26), 5, (200, 140, 60), 0.08)
    rounded(p, -0.3, -0.08, -0.34, 0.3, 0.18, 0.34, sh, 0.09)
    rounded(p, -0.22, 0.16, -0.26, 0.24, 0.26, 0.26, sh, 0.07)
    for (x, z) in ((-0.12, -0.14), (-0.12, 0.14), (0.04, 0.0), (-0.2, 0.0)):              # flak tubes on its back
        p.add(x - 0.04, 0.24, z - 0.04, x + 0.04, 0.38, z + 0.04, solid((70, 74, 66), 3))
        p.add(x - 0.03, 0.37, z - 0.03, x + 0.03, 0.39, z + 0.03, ACIDGLOW)
    p.add(0.28, 0.0, -0.22, 0.34, 0.14, 0.22, solid((110, 64, 30), 4))                    # the front plate
    p.add(0.24, -0.04, -0.1, 0.31, 0.02, 0.1, MOUTH)
    p.bone("eyes", (0.22, 0.2, 0))
    for s in (-1, 1):
        p.add(0.2, 0.2, s * 0.12 - 0.02, 0.24, 0.34, s * 0.12 + 0.02, solid((120, 70, 34), 3))
        p.add(0.19, 0.33, s * 0.12 - 0.04, 0.26, 0.4, s * 0.12 + 0.04, W.glow((250, 220, 90)))
    p.bone(None)
    legs = []
    for i in range(3):
        x = -0.18 + i * 0.14
        legs += [((x - 0.025, -0.02, 0.34, x + 0.025, 0.04, 0.56), solid((130, 76, 36), 3)),
                 ((x - 0.025, -0.34, 0.53, x + 0.025, -0.0, 0.58), solid((110, 62, 30), 3))]
    side_bone(p, "leg", (0.0, 0.0, 0.34), legs, True)
    side_bone(p, "claw", (0.3, 0.04, 0.24), [((0.3, -0.02, 0.22, 0.46, 0.06, 0.3), sh), ((0.44, -0.1, 0.16, 0.68, 0.14, 0.38), sh),
                                            ((0.64, 0.0, 0.18, 0.78, 0.08, 0.26), solid((60, 40, 20), 3)), ((0.62, -0.1, 0.27, 0.72, -0.04, 0.36), solid((60, 40, 20), 3))], True)
    p.anim("legl", "rot", (0, 12, 8), 0.45).anim("legr", "rot", (0, -12, -8), 0.45, 0.5).anim("eyes", "rot", (0, 0, 8), 1.4)
    p.anim("clawl", "rot", (0, 12, 0), 1.1).anim("clawr", "rot", (0, -12, 0), 1.1, 0.5)
    return p


def nucleartoad():
    """A bloated toad with glowing warts and a throat sac that pulses green. Don't pop it."""
    p = Part("nucleartoad", 16, 128)
    sk = W.grad((96, 120, 40), (54, 70, 24), 5, LIME, 0.1)
    rounded(p, -0.24, -0.14, -0.2, 0.2, 0.12, 0.2, sk, 0.08)
    rounded(p, 0.1, -0.06, -0.17, 0.32, 0.14, 0.17, sk, 0.06)                              # the head
    for s in (-1, 1):
        p.add(0.18, 0.12, s * 0.11 - 0.06, 0.3, 0.22, s * 0.11 + 0.06, sk)                # bulging eyes
        a, b = sorted((s * 0.17, s * 0.172))
        p.add(0.2, 0.14, s * 0.11 - 0.05, 0.29, 0.2, s * 0.11 + 0.05, W.eye_paint((250, 220, 60), (240, 180, 20), (10, 10, 10), True))
    for (x, z) in ((-0.15, 0.1), (-0.05, -0.12), (0.05, 0.08), (-0.18, -0.05), (0.0, 0.0)):
        p.add(x - 0.03, 0.11, z - 0.03, x + 0.03, 0.15, z + 0.03, ACIDGLOW)                 # glowing warts
    p.add(0.31, -0.02, -0.12, 0.33, 0.02, 0.12, MOUTH)
    p.bone("sac", (0.24, -0.1, 0))
    rounded(p, 0.16, -0.22, -0.11, 0.32, -0.06, 0.11, ACIDGLOW, 0.04)
    p.bone(None)
    for s, nm in ((1, "legr"), (-1, "legl")):
        a, b = sorted((s * 0.18, s * 0.3))
        p.bone(nm, (-0.12, -0.08, s * 0.2))
        p.add(-0.24, -0.16, a, 0.0, -0.04, b, sk)
        p.add(-0.02, -0.18, a, 0.1, -0.14, b, solid(OLIVE_D, 3))
        p.bone(None)
    pair(p, 0.12, -0.18, 0.12, 0.2, -0.1, 0.18, sk)
    p.pivots["body"] = (0, -0.14, 0)
    p.half = {"sac"}
    p.anim("sac", "pos", (0, -0.6, 0), 1.2).anim("legl", "rot", (0, 0, 8), 2.4).anim("legr", "rot", (0, 0, -8), 2.4).anim("body", "pos", (0, 0.4, 0), 2.4)
    return p


def gammaslime():
    """A cube of glowing green jelly with a dark, irradiated core."""
    p = Part("gammaslime", 16, 128)
    outer = W.glow((140, 230, 70))
    p.add(-0.3, -0.3, -0.3, 0.3, 0.3, 0.3, outer)
    p.add(-0.14, -0.14, -0.14, 0.14, 0.14, 0.14, solid((40, 70, 20), 4))                   # the core
    p.add(-0.06, -0.06, -0.06, 0.06, 0.06, 0.06, W.glow((250, 255, 150)))
    eyes(p, 0.29, 0.31, 0.06, 0.14, 0.12, (20, 40, 10), (230, 255, 160), depth=0.02)
    p.add(0.3, -0.1, -0.08, 0.31, -0.06, 0.08, solid((20, 40, 10), 2))
    p.bone("drip", (0, -0.3, 0))
    for (x, z) in ((0.2, 0.1), (-0.15, -0.2), (0.05, -0.18)): p.add(x - 0.03, -0.42, z - 0.03, x + 0.03, -0.3, z + 0.03, outer)
    p.bone(None)
    p.pivots["body"] = (0, -0.3, 0)
    p.anim("body", "pos", (0, 0.9, 0), 0.9).anim("drip", "pos", (0, -0.6, 0), 0.9, 0.25)
    return p


def sulphurskater():
    """A long-legged water strider that skates the acid surface; it bites fast when crowded."""
    p = Part("sulphurskater", 16, 128)
    sk = W.grad((60, 66, 40), (30, 34, 20), 4, SULF, 0.05)
    rounded(p, -0.28, -0.04, -0.07, 0.2, 0.06, 0.07, sk, 0.03)
    p.add(0.18, -0.03, -0.05, 0.3, 0.05, 0.05, sk)
    eyes(p, 0.25, 0.29, 0.0, 0.04, 0.05, SULF, (10, 10, 10), depth=0.02)
    for s in (-1, 1): p.add(0.29, 0.03, s * 0.025 - 0.008, 0.42, 0.045, s * 0.025 + 0.008, solid(OLIVE_D, 2))   # antennae
    p.add(-0.2, 0.06, -0.02, 0.1, 0.075, 0.02, SULFGLOW)                                   # a sulphur stripe
    legs = [((0.06, -0.01, 0.07, 0.09, 0.02, 0.32), solid(OLIVE_D, 2)), ((0.2, -0.04, 0.29, 0.44, -0.01, 0.32), solid(OLIVE_D, 2)),
            ((-0.05, -0.01, 0.07, -0.02, 0.02, 0.4), solid(OLIVE_D, 2)), ((-0.05, -0.12, 0.38, -0.02, 0.0, 0.41), solid(OLIVE_D, 2)),
            ((-0.15, -0.01, 0.07, -0.12, 0.02, 0.42), solid(OLIVE_D, 2)), ((-0.5, -0.12, 0.4, -0.14, -0.09, 0.43), solid(OLIVE_D, 2))]
    side_bone(p, "leg", (0.0, 0.0, 0.07), legs, True)
    p.pivots["body"] = (0, 0, 0)
    p.anim("legl", "rot", (0, 14, 4), 0.35).anim("legr", "rot", (0, -14, -4), 0.35, 0.5).anim("body", "pos", (0, 0.3, 0), 0.7)
    return p


def acidjelly():
    """A green bell of a jellyfish with lime veins, trailing stinging threads."""
    p = Part("acidjelly", 16, 128)
    bell = W.glow((120, 200, 80))
    rounded(p, -0.26, 0.0, -0.26, 0.26, 0.24, 0.26, bell, 0.09)
    rounded(p, -0.18, 0.22, -0.18, 0.18, 0.32, 0.18, bell, 0.06)
    p.add(-0.1, 0.05, -0.1, 0.1, 0.2, 0.1, W.glow((230, 255, 120)))                        # the glowing heart
    p.bone("skirt", (0, 0, 0))
    for i in range(8):
        a = i * math.pi / 4
        p.add(math.cos(a) * 0.22 - 0.04, -0.06, math.sin(a) * 0.22 - 0.04, math.cos(a) * 0.22 + 0.04, 0.02, math.sin(a) * 0.22 + 0.04, bell)
    p.bone(None)
    p.bone("threads", (0, -0.02, 0))
    for i in range(10):
        a = i * math.pi / 5
        r = 0.12 + (i % 2) * 0.07
        x, z = math.cos(a) * r, math.sin(a) * r
        p.add(x - 0.012, -0.85 + (i % 3) * 0.12, z - 0.012, x + 0.012, -0.02, z + 0.012, solid((150, 230, 90), 2))
    for i in range(4):
        a = i * math.pi / 2 + 0.4
        p.add(math.cos(a) * 0.05 - 0.03, -0.5, math.sin(a) * 0.05 - 0.03, math.cos(a) * 0.05 + 0.03, -0.02, math.sin(a) * 0.05 + 0.03, W.membrane((140, 210, 90), LIME))
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("skirt", "pos", (0, 0.8, 0), 1.4).anim("threads", "rot", (8, 0, 8), 1.8).anim("body", "pos", (0, 1.2, 0), 1.4, 0.5)
    return p


def orthocera():
    """A cephalopod in a long, straight, banded cone shell; it jets backward and sprays acid ink."""
    p = Part("orthocera", 16, 256)
    shell = W.bands((216, 196, 150), (130, 100, 70), 3)
    for i in range(7):                                                                       # the cone, narrowing behind
        r = 0.16 - i * 0.02
        x0 = -0.15 - i * 0.12
        p.add(x0 - 0.12, -r, -r, x0, r, r, shell)
    p.add(-0.99, -0.02, -0.02, -0.96, 0.02, 0.02, solid((120, 90, 60), 3))
    p.add(-0.16, -0.17, -0.17, 0.06, 0.17, 0.17, solid((180, 150, 110), 4))                # the lip
    p.add(0.04, -0.12, -0.12, 0.18, 0.12, 0.12, W.grad((110, 120, 60), (60, 70, 30), 4, LIME, 0.05))   # the head
    for s in (-1, 1):
        a, b = sorted((s * 0.12, s * 0.135))
        p.add(0.08, 0.02, a, 0.15, 0.09, b, W.eye_paint((230, 230, 160), (60, 80, 20), (10, 10, 10)))
    p.bone("arms", (0.18, 0, 0))
    for i in range(10):
        a = i * math.pi / 5
        y, z = math.sin(a) * 0.07, math.cos(a) * 0.07
        L = 0.42 if i % 2 else 0.3
        p.add(0.18, y - 0.018, z - 0.018, 0.18 + L, y + 0.018, z + 0.018, solid((120, 130, 60), 3))
        p.add(0.16 + L, y - 0.025, z - 0.025, 0.2 + L, y + 0.025, z + 0.025, ACIDGLOW)
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("arms", "rot", (0, 12, 12), 1.3).anim("body", "pos", (0, 0.6, 0), 2.0)
    return p


def trilobite():
    """An armoured bug of the sea floor; it curls into a ball when struck."""
    p = Part("trilobite", 16, 128)
    sh = W.grad((120, 110, 80), (70, 62, 44), 4, (170, 160, 110), 0.06)
    p.add(0.1, -0.05, -0.18, 0.3, 0.06, 0.18, sh)                                          # the head shield
    p.add(0.24, -0.05, -0.2, 0.3, 0.03, -0.14, solid((90, 80, 56), 3)); p.add(0.24, -0.05, 0.14, 0.3, 0.03, 0.2, solid((90, 80, 56), 3))
    eyes(p, 0.16, 0.22, 0.03, 0.08, 0.1, (60, 50, 30), (180, 220, 90), depth=0.03)
    p.bone("body1", (0.1, 0, 0))
    for i in range(3):
        x = 0.1 - (i + 1) * 0.07
        p.add(x, -0.04, -0.17 + i * 0.01, x + 0.065, 0.05, 0.17 - i * 0.01, sh)
        p.add(x + 0.01, 0.05, -0.04, x + 0.055, 0.075, 0.04, solid((90, 82, 58), 3))    # the axial ridge
    p.bone(None)
    p.bone("body2", (-0.11, 0, 0))
    for i in range(3):
        x = -0.11 - (i + 1) * 0.07
        p.add(x, -0.035, -0.14 + i * 0.02, x + 0.065, 0.045, 0.14 - i * 0.02, sh)
        p.add(x + 0.01, 0.045, -0.035, x + 0.055, 0.07, 0.035, solid((90, 82, 58), 3))
    p.add(-0.38, -0.03, -0.06, -0.32, 0.03, 0.06, sh)
    p.bone(None)
    side_bone(p, "legs", (0.0, -0.04, 0.12), [((x, -0.08, 0.1, x + 0.02, -0.04, 0.2), solid((150, 130, 90), 2)) for x in (-0.25, -0.15, -0.05, 0.05, 0.15)], True)
    p.anim("legsl", "rot", (0, 0, 14), 0.4).anim("legsr", "rot", (0, 0, -14), 0.4, 0.5).anim("body1", "rot", (0, 0, 4), 1.6).anim("body2", "rot", (0, 0, 6), 1.6, 0.2)
    return p


def skyfin():
    """A flying fish with wing-fins; it leaps from the sea and dives on anything above the water."""
    p = Part("skyfin", 16, 128)
    sk = fish((70, 96, 60), (200, 220, 170), LIME, 0.03)
    rounded(p, -0.3, -0.08, -0.08, 0.25, 0.08, 0.08, sk, 0.035)
    p.add(0.24, -0.05, -0.05, 0.38, 0.05, 0.05, sk)
    p.add(0.36, -0.02, -0.02, 0.48, 0.0, 0.02, solid((40, 50, 30), 3))                    # the spear of a beak
    eyes(p, 0.28, 0.33, 0.0, 0.05, 0.05, (250, 230, 120), (20, 10, 10), depth=0.02)
    for side, s in (("l", -1), ("r", 1)):
        p.bone("wing" + side, (0.08, 0.02, s * 0.08))
        a, b = sorted((s * 0.08, s * 0.55))
        p.add(-0.12, 0.01, a, 0.18, 0.03, b, W.membrane((100, 140, 80), LIME))
        for x in (-0.06, 0.04, 0.14):
            a2, b2 = sorted((s * 0.08, s * (0.5 - abs(x) * 0.6)))
            p.add(x, 0.03, a2, x + 0.015, 0.045, b2, solid(OLIVE_D, 2))
        p.bone(None)
    p.bone("tail", (-0.3, 0, 0))
    p.add(-0.56, 0.0, -0.015, -0.28, 0.2, 0.015, finp(OLIVE, LIME)); p.add(-0.6, -0.16, -0.015, -0.3, 0.0, 0.015, finp(OLIVE, LIME))
    p.bone(None)
    p.anim("wingl", "rot", (0, 0, 30), 0.35).anim("wingr", "rot", (0, 0, -30), 0.35).anim("tail", "rot", (0, 18, 0), 0.5)
    return p


def leech():
    """A ringed, glistening leech with a round mouth of hooks; it latches on and drinks."""
    p = Part("leech", 16, 128)
    ring = lambda i: solid((60, 40, 50), 4) if i % 2 else W.grad((90, 70, 70), (50, 34, 40), 3, LIME, 0.05)
    def seg(x, r, i): rounded(p, x, -r * 0.7, -r, x + 0.1, r * 0.7, r, ring(i), r * 0.3)
    p.bone("front", (0.12, 0, 0))
    seg(0.12, 0.08, 1); seg(0.22, 0.07, 2)
    p.add(0.32, -0.05, -0.06, 0.36, 0.05, 0.06, solid((120, 40, 50), 3))                 # the sucker
    for i in range(6):
        a = i * math.pi / 3
        p.add(0.355, math.sin(a) * 0.035 - 0.008, math.cos(a) * 0.035 - 0.008, 0.38, math.sin(a) * 0.035 + 0.008, math.cos(a) * 0.035 + 0.008, TEETH)
    p.bone(None)
    seg(0.02, 0.09, 0)
    p.bone("back", (0.02, 0, 0))
    seg(-0.08, 0.085, 1); seg(-0.18, 0.075, 2); seg(-0.28, 0.06, 3)
    p.add(-0.38, -0.04, -0.06, -0.28, 0.04, 0.06, solid((120, 40, 50), 3))
    p.bone(None)
    for x in (-0.2, 0.0, 0.2): pair(p, x, 0.04, 0.05, x + 0.04, 0.065, 0.075, ACIDGLOW)
    p.anim("front", "rot", (0, 16, 8), 0.9).anim("back", "rot", (0, -16, -8), 0.9)
    return p


def boglurker():
    """It looks like a dead acidwood stump, until the stump opens its eyes."""
    p = Part("boglurker", 16, 256)
    bark = W.grad((50, 40, 32), (28, 22, 18), 5, (90, 110, 40), 0.06)
    moss = W.grad((90, 110, 40), (60, 76, 26), 4, LIME, 0.04)
    rounded(p, -0.22, -0.4, -0.22, 0.22, 0.32, 0.22, bark, 0.08)                         # the trunk body
    p.add(-0.24, 0.24, -0.24, 0.24, 0.32, 0.24, moss)
    for (x, z) in ((0.18, 0.18), (-0.18, 0.18), (0.18, -0.18), (-0.18, -0.18)): p.add(x - 0.06, -0.44, z - 0.06, x + 0.06, -0.3, z + 0.06, bark)   # roots
    p.add(0.21, 0.02, -0.16, 0.24, 0.22, 0.16, solid((20, 16, 12), 3))                   # the hollow
    for s in (-1, 1): p.add(0.215, 0.12, s * 0.08 - 0.035, 0.245, 0.19, s * 0.08 + 0.035, W.glow((250, 210, 60)))   # glowing eyes in it
    p.bone("jaw", (0.2, 0.0, 0))
    p.add(0.2, -0.12, -0.15, 0.26, 0.02, 0.15, bark)
    for z in (-0.12, -0.06, 0.0, 0.06, 0.12): p.add(0.24, 0.02, z - 0.015, 0.27, 0.06, z + 0.015, TEETH)
    p.bone(None)
    for side, s in (("l", -1), ("r", 1)):
        p.bone("arm" + side, (0.0, 0.2, s * 0.22))
        a, b = sorted((s * 0.22, s * 0.3))
        p.add(-0.04, -0.16, a, 0.04, 0.22, b, bark)                                        # branch arms
        a2, b2 = sorted((s * 0.26, s * 0.42))
        p.add(-0.03, 0.18, a2, 0.03, 0.24, b2, bark); p.add(0.0, -0.22, a, 0.12, -0.16, b, bark)
        p.add(0.0, 0.22, a2, 0.04, 0.3, b2, moss)
        p.bone(None)
    p.half = {"jaw"}
    p.anim("jaw", "rot", (18, 0, 0), 2.6).anim("arml", "rot", (0, 0, 10), 2.0).anim("armr", "rot", (0, 0, -10), 2.0, 0.5)
    return p


def corrosionshark():
    """A huge shark with mottled bile skin, a glowing gill line, and teeth that eat through armour."""
    p = shark("corrosionshark", fish((70, 86, 50), (190, 196, 150), LIME, 0.05), finp((54, 66, 34), LIME), (196, 200, 160), dorsal=0.4)
    for x in (0.1, 0.13, 0.16, 0.19): pair(p, x, -0.06, 0.168, x + 0.012, 0.06, 0.172, ACIDGLOW)                # glowing gills
    for x in (-0.3, -0.1, 0.1): p.add(x, 0.15, -0.03, x + 0.06, 0.17, 0.03, solid((40, 50, 24), 3))            # scars
    return p


def urchin():
    """A spiny sulphur-yellow ball on the sea floor; touch it and you'll know."""
    p = Part("urchin", 16, 128)
    rounded(p, -0.2, -0.2, -0.2, 0.2, 0.2, 0.2, W.grad((120, 60, 90), (70, 30, 60), 4, (200, 120, 160), 0.1), 0.08)
    p.bone("spines", (0, 0, 0))
    for i in range(22):                                                                    # spines: stacked cubes, close enough to read as one spike
        u = (i + 0.5) / 22 * 2 - 1
        a = i * 2.4
        r = math.sqrt(1 - u * u)
        dx, dy, dz = math.cos(a) * r, u, math.sin(a) * r
        for k in range(7):
            t = 0.17 + k * 0.05
            w = 0.03 - k * 0.0035
            cx, cy, cz = dx * t, dy * t, dz * t
            p.add(cx - w, cy - w, cz - w, cx + w, cy + w, cz + w, SULFGLOW if k >= 5 else solid((210, 180, 70), 2))
    p.bone(None)
    p.add(-0.06, 0.18, -0.06, 0.06, 0.22, 0.06, solid((40, 20, 30), 3))
    p.anim("spines", "rot", (6, 8, 6), 2.6)
    return p


def crabling():
    """A crab no bigger than your fist, the colour of rust. They never come alone."""
    p = Part("crabling", 16, 128)
    sh = W.grad((190, 100, 50), (120, 60, 30), 4, (240, 160, 80), 0.08)
    rounded(p, -0.22, -0.08, -0.28, 0.22, 0.12, 0.28, sh, 0.08)
    p.bone("eyes", (0.18, 0.1, 0))
    for s in (-1, 1):
        p.add(0.17, 0.1, s * 0.1 - 0.02, 0.21, 0.22, s * 0.1 + 0.02, solid((150, 70, 34), 3))
        p.add(0.16, 0.21, s * 0.1 - 0.035, 0.23, 0.27, s * 0.1 + 0.035, solid((20, 20, 20), 2))
    p.bone(None)
    legs = []
    for i in range(3):
        x = -0.14 + i * 0.12
        legs += [((x - 0.02, -0.03, 0.28, x + 0.02, 0.02, 0.44), solid((170, 84, 40), 3)), ((x - 0.02, -0.24, 0.42, x + 0.02, -0.0, 0.46), solid((150, 70, 34), 3))]
    side_bone(p, "leg", (0.0, -0.02, 0.28), legs, True)
    side_bone(p, "claw", (0.22, 0.0, 0.2), [((0.22, -0.03, 0.18, 0.38, 0.05, 0.26), sh), ((0.36, -0.08, 0.14, 0.54, 0.1, 0.32), sh)], True)
    p.anim("legl", "rot", (0, 14, 10), 0.3).anim("legr", "rot", (0, -14, -10), 0.3, 0.5).anim("clawl", "rot", (0, 14, 0), 0.8).anim("clawr", "rot", (0, -14, 0), 0.8, 0.5)
    return p


def bloatgut():
    """A pufferfish swollen with acid gas, sulphur spines standing up all over it."""
    p = Part("bloatgut", 16, 128)
    sk = fish((140, 150, 60), (230, 230, 150), (100, 110, 40), 0.1)
    rounded(p, -0.28, -0.28, -0.28, 0.28, 0.28, 0.28, sk, 0.11)
    p.add(-0.2, -0.29, -0.2, 0.2, -0.27, 0.2, W.glow((200, 250, 100)))                   # the glowing belly
    p.bone("spines", (0, 0, 0))
    for (x, y, z) in ((0, 0.3, 0.16), (0, 0.3, -0.16), (0.16, 0.29, 0), (-0.16, 0.29, 0), (0, -0.31, 0.16), (0, -0.31, -0.16),
                      (-0.16, 0, 0.31), (0.12, 0, 0.31), (-0.16, 0, -0.31), (0.12, 0, -0.31), (0.14, 0.2, 0.24), (-0.14, -0.2, -0.24),
                      (0.14, -0.2, 0.24), (-0.14, 0.2, -0.24), (-0.3, 0.12, 0.1), (-0.3, -0.12, -0.1)):
        nx, ny, nz = [0.07 if abs(v) > 0.25 else 0.022 for v in (x, y, z)]
        p.add(x - nx, y - ny, z - nz, x + nx, y + ny, z + nz, SULFGLOW)
    p.bone(None)
    eyes(p, 0.16, 0.27, 0.06, 0.16, 0.28, (250, 250, 210), (30, 50, 10), depth=0.02)
    p.add(0.28, -0.08, -0.06, 0.33, 0.0, 0.06, solid((120, 60, 40), 3))
    side_bone(p, "fin", (0.1, 0.0, 0.28), [((0.04, -0.06, 0.28, 0.16, 0.04, 0.38), finp((140, 150, 60), SULF))], True)
    p.bone("tail", (-0.28, 0, 0)); p.add(-0.48, -0.14, -0.015, -0.26, 0.14, 0.015, finp((140, 150, 60), SULF)); p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("tail", "rot", (0, 28, 0), 0.5).anim("finl", "rot", (0, 0, 30), 0.35).anim("finr", "rot", (0, 0, -30), 0.35)
    p.anim("spines", "rot", (5, 5, 5), 1.2).anim("body", "pos", (0, 0.5, 0), 1.6)
    return p


def sulphurmoth():
    """A fat, furry moth with glowing sulphur wings, drawn to fumaroles."""
    p = Part("sulphurmoth", 16, 128)
    fur = W.grad((200, 180, 90), (150, 120, 50), 4, (250, 240, 160), 0.1)
    rounded(p, -0.22, -0.07, -0.07, 0.12, 0.07, 0.07, fur, 0.03)
    p.add(0.1, -0.06, -0.06, 0.2, 0.06, 0.06, fur)
    eyes(p, 0.14, 0.2, -0.01, 0.05, 0.06, (40, 30, 20), (250, 250, 220), depth=0.02)
    for s in (-1, 1): p.add(0.18, 0.05, s * 0.03 - 0.01, 0.3, 0.07, s * 0.09 + 0.01, solid((140, 110, 50), 2))   # feathery antennae
    for side, s in (("l", -1), ("r", 1)):
        p.bone("wing" + side, (0.0, 0.05, s * 0.07))
        a, b = sorted((s * 0.07, s * 0.46))
        p.add(-0.1, 0.05, a, 0.18, 0.07, b, W.membrane((230, 200, 70), (255, 250, 170)))
        a2, b2 = sorted((s * 0.07, s * 0.34))
        p.add(-0.28, 0.045, a2, -0.06, 0.065, b2, W.membrane((210, 170, 60), (255, 240, 150)))
        c0, c1 = sorted((s * 0.2, s * 0.28))
        p.add(0.0, 0.07, c0, 0.08, 0.075, c1, W.glow((255, 255, 200)))                   # eye spots
        p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("wingl", "rot", (0, 0, 40), 0.25).anim("wingr", "rot", (0, 0, -40), 0.25).anim("body", "pos", (0, 1.0, 0), 0.6)
    return p


def hazardturtle():
    """A turtle whose shell has grown spikes of glowing sulphur crystal."""
    p = Part("hazardturtle", 16, 256)
    shell = W.grad((70, 80, 50), (40, 46, 28), 5, (110, 120, 70), 0.06)
    skin = W.grad((120, 130, 80), (80, 90, 50), 4, (150, 160, 100), 0.05)
    rounded(p, -0.32, -0.06, -0.26, 0.28, 0.2, 0.26, shell, 0.09)
    p.add(-0.3, -0.1, -0.22, 0.26, -0.05, 0.22, solid((180, 170, 110), 4))               # the plastron
    for (x, z, h) in ((0.0, 0.0, 0.2), (-0.15, 0.1, 0.14), (-0.15, -0.1, 0.14), (0.14, 0.1, 0.12), (0.14, -0.1, 0.12), (-0.25, 0.0, 0.1)):
        p.add(x - 0.035, 0.18, z - 0.035, x + 0.035, 0.18 + h, z + 0.035, SULFGLOW)         # crystal spikes
        p.add(x - 0.02, 0.18 + h, z - 0.02, x + 0.02, 0.22 + h, z + 0.02, W.glow((255, 250, 180)))
    p.bone("head", (0.28, 0.04, 0))
    p.add(0.26, -0.02, -0.07, 0.46, 0.1, 0.07, skin)
    eyes(p, 0.38, 0.43, 0.04, 0.08, 0.07, (20, 20, 10), (220, 200, 60), depth=0.02)
    p.add(0.44, -0.0, -0.05, 0.47, 0.03, 0.05, solid((60, 60, 40), 3))
    p.bone(None)
    for x, nm in ((0.16, "front"), (-0.2, "back")):
        side_bone(p, nm, (x, -0.04, 0.24), [((x - 0.06, -0.08, 0.22, x + 0.1, -0.02, 0.4), skin)], True)
    p.add(-0.42, -0.02, -0.03, -0.32, 0.04, 0.03, skin)
    p.anim("head", "rot", (0, 10, 4), 2.2).anim("frontl", "rot", (0, 18, 0), 1.2).anim("frontr", "rot", (0, -18, 0), 1.2, 0.5)
    p.anim("backl", "rot", (0, 14, 0), 1.2, 0.5).anim("backr", "rot", (0, -14, 0), 1.2)
    return p


def sludgesnail():
    """A slow snail with a rusted, spiral shell, leaving a trail of acid slime."""
    p = Part("sludgesnail", 16, 128)
    skin = W.grad((130, 150, 70), (80, 96, 40), 4, LIME, 0.06)
    shell = W.bands((150, 90, 44), (90, 50, 26), 2)
    p.add(-0.3, -0.18, -0.12, 0.34, -0.06, 0.12, skin)                                    # the foot
    p.add(-0.32, -0.19, -0.13, 0.36, -0.17, 0.13, W.glow((160, 240, 90)))               # glistening slime underneath
    for (r, y, x) in ((0.2, 0.06, -0.08), (0.14, 0.2, -0.04), (0.09, 0.3, 0.0), (0.05, 0.37, 0.02)):   # the spiral, stacked
        rounded(p, x - r, y - r * 0.9, -r * 0.9, x + r, y + r * 0.9, r * 0.9, shell, r * 0.3)
    p.bone("head", (0.28, -0.06, 0))
    p.add(0.26, -0.1, -0.07, 0.4, 0.04, 0.07, skin)
    for s in (-1, 1):
        p.add(0.33, 0.03, s * 0.04 - 0.012, 0.36, 0.2, s * 0.04 + 0.012, skin)           # eye stalks
        p.add(0.32, 0.19, s * 0.04 - 0.025, 0.37, 0.24, s * 0.04 + 0.025, W.glow((250, 230, 120)))
    p.bone(None)
    p.anim("head", "rot", (0, 8, 6), 2.8)
    return p


SULPHUR_MOBS = [acideel, trasher, radiator, flakcrab, nucleartoad, gammaslime, sulphurskater, acidjelly, orthocera, trilobite,
                skyfin, leech, boglurker, corrosionshark, urchin, crabling, bloatgut, sulphurmoth, hazardturtle, sludgesnail]


MOBS = [gawker, bloatfin, jellywing, driftback, clam, lumenbell, gasbloom, fangstalker, blastfin, burrowshark,
        cavecrawler, glowgrub, shellsnake, lampsquid, volteel, needlejaw, devilfish, riftwarper, hollowkin, sharkquid] + SULPHUR_MOBS


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
        rows = (len(tiles) + 4) // 5
        sheet = Image.new("RGBA", (5 * 190, rows * 210), (18, 30, 50, 255))
        d = ImageDraw.Draw(sheet)
        for i, (n, t) in enumerate(tiles):
            sheet.alpha_composite(t, ((i % 5) * 190 + 5, (i // 5) * 210 + 5)); d.text(((i % 5) * 190 + 8, (i // 5) * 210 + 190), n, fill=(230, 230, 230))
        sheet.save(os.path.join(prev, "deep_creatures.png"))
        ic = list(index.items())
        s2 = Image.new("RGBA", (11 * 68, ((len(ic) + 10) // 11) * 68), (200, 180, 140, 255))
        for i, (k, im) in enumerate(ic): s2.alpha_composite(im.resize((64, 64), Image.NEAREST), ((i % 11) * 68 + 2, (i // 11) * 68 + 2))
        s2.save(os.path.join(prev, "deep_icons.png"))


if __name__ == "__main__":
    main()
