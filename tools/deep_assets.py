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



# --------------------------------------------------------------------------------------------- the acid mobs, detailed (Deep 1.5.0)
# Every acid creature gets twice the texture resolution (32 pixels a block) and a pass of new detail on top of its v2
# model: crusts of sulphur, rust and barnacles, gills, drips of acid, claws, spines, glowing veins. `into(p, bone)` puts
# boxes in an EXISTING bone (Part.bone() would reset its pivot).
def crust(c=SULF, crack=(110, 90, 20)):
    """A sulphur crust: lumpy yellow with dark cracks between the crystals."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        reg[:, :, 3] = 255
        for y in range(h):
            for x in range(w):
                col = np.array(c, float) + rng.uniform(-18, 18)
                if (x * 7 + y * 3) % 11 == 0 or (x + y * 5) % 13 == 0: col = np.array(crack, float)
                reg[y, x, :3] = np.clip(col, 0, 255)
    return f


def rusty(base=RUST):
    """Rusted metal: orange-brown with darker streaks running down and green verdigris flecks."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        reg[:, :, 3] = 255
        streak = {rng.randrange(max(1, w)) for _ in range(3)}
        for y in range(h):
            for x in range(w):
                col = np.array(base, float) + rng.uniform(-14, 14)
                if x in streak: col = col * 0.7
                if rng.random() < 0.04: col = np.array((90, 140, 110), float)
                reg[y, x, :3] = np.clip(col, 0, 255)
    return f


def slick(c, shine=(240, 255, 200)):
    """Wet, glistening skin: a few bright highlights."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        reg[:, :, 3] = 255
        for y in range(h):
            for x in range(w):
                col = np.array(c, float) + rng.uniform(-8, 8)
                if face == "up" and rng.random() < 0.05: col = np.array(shine, float)
                reg[y, x, :3] = np.clip(col, 0, 255)
    return f


BARN = W.grad((200, 196, 170), (130, 124, 100), 3, (90, 86, 70), 0.2)
DRIP = W.glow((190, 255, 110))
DARK = solid((24, 26, 16), 2)
CLAW = solid((40, 36, 24), 2)


def into(p, bone):
    p.cur_bone = bone
    return p


def drips(p, x, y, z, n, seed, spread=0.05, length=0.08):
    """Little hanging drops of acid under a point."""
    r = np.random.default_rng(seed)
    for i in range(n):
        dx, dz = r.uniform(-spread, spread, 2)
        L = length * r.uniform(0.5, 1.0)
        p.add(x + dx - 0.008, y - L, z + dz - 0.008, x + dx + 0.008, y, z + dz + 0.008, DRIP)


def barnacles(p, pts, s=0.03):
    for (x, y, z) in pts:
        p.add(x - s, y, z - s, x + s, y + s * 0.9, z + s, BARN)
        p.add(x - s * 0.4, y + s * 0.9, z - s * 0.4, x + s * 0.4, y + s * 1.05, z + s * 0.4, DARK)


def _acideel(p):
    into(p, None)
    for x in (0.34, 0.31, 0.28): pair(p, x, -0.04, 0.0855, x + 0.012, 0.04, 0.09, solid((30, 36, 14), 2))      # gill slits
    for i in range(4): p.add(0.4 + i * 0.04, -0.06, -0.045, 0.415 + i * 0.04, -0.035, 0.045, TEETH)               # upper fangs
    into(p, "jaw")
    for s in (-1, 1): p.add(0.5, -0.16, s * 0.04 - 0.008, 0.52, -0.1, s * 0.04 + 0.008, solid(OLIVE_D, 2))        # barbels
    drips(p, 0.56, -0.1, 0, 3, 11, 0.04)
    for b, xs in (("s1", (0.1, -0.06)), ("s2", (-0.22, -0.4)), ("s3", (-0.56,))):
        into(p, b)
        for x in xs: pair(p, x, 0.03, 0.07, x + 0.04, 0.06, 0.085, DRIP)                                             # glowing spots
    into(p, None)


def _trasher(p):
    into(p, None)
    barnacles(p, [(-0.22, 0.12, 0.08), (-0.02, 0.13, -0.09), (0.1, 0.12, 0.12)], 0.025)
    p.add(-0.2, 0.13, -0.03, 0.12, 0.15, 0.03, crust())                                                               # a sulphur crust down the spine
    into(p, "head")
    for s in (-1, 1): p.add(0.58, 0.03, s * 0.03 - 0.01, 0.61, 0.05, s * 0.03 + 0.01, DARK)                          # nostrils
    for s in (-1, 1): p.add(0.25, 0.06, s * 0.105, 0.36, 0.09, s * 0.115, solid((48, 58, 30), 3))                    # eye ridges
    drips(p, 0.5, -0.05, 0.06, 2, 3); drips(p, 0.45, -0.05, -0.06, 2, 4)
    for b in ("frontl", "frontr", "backl", "backr"):
        if b not in p.pivots: continue
        into(p, b)
        x, _, z = p.pivots[b]
        s = 1 if z > 0 else -1
        for k in range(3): p.add(x + 0.06 + k * 0.012, -0.235, s * (0.215 + k * 0.025), x + 0.11 + k * 0.012, -0.215, s * (0.225 + k * 0.025), CLAW)   # claws
    into(p, "tail")
    for x in (-0.78, -0.62, -0.46): pair(p, x, -0.01, 0.095, x + 0.05, 0.04, 0.11, solid((54, 64, 34), 3))         # side osteoderms
    into(p, None)


def _radiator(p):
    into(p, None)
    for i, x in enumerate((-0.16, -0.08, 0.0, 0.08)): p.add(x, 0.17, -0.012, x + 0.05, 0.25 - i * 0.012, 0.012, finp(OLIVE_D, LIME))   # a spiky dorsal
    for (x, y) in ((-0.1, 0.05), (0.0, -0.06), (0.08, 0.06), (-0.05, -0.02)): pair(p, x, y, 0.121, x + 0.08, y + 0.012, 0.128, DRIP)  # veins
    p.add(-0.1, -0.17, -0.06, 0.12, -0.16, 0.06, W.glow((250, 255, 170)))                                            # the hot belly
    into(p, "lure"); drips(p, 0.275, 0.33, 0, 2, 7, 0.02, 0.05); into(p, None)


def _flakcrab(p):
    into(p, None)
    for (x, z) in ((-0.25, 0.25), (0.1, -0.28), (-0.05, 0.3), (0.2, 0.2)): p.add(x - 0.015, 0.17, z - 0.015, x + 0.015, 0.19, z + 0.015, solid((70, 50, 30), 2))  # rivets
    barnacles(p, [(-0.18, 0.255, 0.18), (0.12, 0.25, -0.16), (-0.24, 0.24, -0.05)], 0.028)
    p.add(-0.3, 0.12, -0.345, 0.3, 0.16, 0.345, rusty())                                                              # a rust band round the rim
    for (x, z) in ((-0.12, -0.14), (-0.12, 0.14), (0.04, 0.0), (-0.2, 0.0)): p.add(x - 0.045, 0.3, z - 0.045, x + 0.045, 0.32, z + 0.045, solid((50, 54, 48), 2))  # tube rings
    for s in (-1, 1): p.add(0.3, 0.06, s * 0.05 - 0.008, 0.48, 0.075, s * 0.05 + 0.008, solid((140, 80, 40), 2))      # antennae
    p.add(0.3, -0.05, -0.06, 0.33, 0.0, 0.06, solid((150, 70, 50), 2))                                                # mouthparts
    drips(p, 0.32, -0.05, 0, 2, 9, 0.04)


def _nucleartoad(p):
    into(p, None)
    for k in range(3):                                                                                                # the hazard mark on its back
        a = k * 2 * math.pi / 3 + math.pi / 2
        x, z = math.cos(a) * 0.06 - 0.04, math.sin(a) * 0.06
        p.add(x - 0.025, 0.125, z - 0.025, x + 0.025, 0.14, z + 0.025, W.glow((255, 240, 80)))
    p.add(-0.055, 0.125, -0.015, -0.025, 0.14, 0.015, DARK)
    for s in (-1, 1): p.add(0.31, 0.06, s * 0.04 - 0.01, 0.33, 0.08, s * 0.04 + 0.01, DARK)                          # nostrils
    p.add(-0.2, -0.15, -0.16, 0.16, -0.13, 0.16, slick((170, 190, 100)))                                               # the pale wet belly
    for (x, z) in ((-0.2, 0.15), (0.1, -0.15), (-0.1, -0.17), (0.12, 0.14)): p.add(x - 0.02, 0.08, z - 0.02, x + 0.02, 0.13, z + 0.02, ACIDGLOW)  # more warts
    for b, s in (("legl", -1), ("legr", 1)):
        into(p, b)
        for k in range(3): p.add(0.08 + k * 0.0, -0.185, s * (0.19 + k * 0.04), 0.14, -0.175, s * (0.21 + k * 0.04), solid((60, 80, 30), 2))   # webbed toes
    into(p, None)


def _gammaslime(p):
    into(p, None)
    for (x, y, z, ax) in ((0.1, 0.12, -0.15, 0), (-0.16, -0.08, 0.12, 1), (0.14, -0.15, 0.1, 2)):                    # bones floating inside
        d = [0.012, 0.012, 0.012]; d[ax] = 0.07
        p.add(x - d[0], y - d[1], z - d[2], x + d[0], y + d[1], z + d[2], solid(BONEW, 2))
    for (x, y, z) in ((-0.12, 0.18, -0.1), (0.18, 0.0, 0.18), (-0.2, -0.18, -0.2), (0.0, 0.22, 0.15)):
        p.add(x - 0.025, y - 0.025, z - 0.025, x + 0.025, y + 0.025, z + 0.025, W.glow((240, 255, 200)))              # bubbles
    p.add(-0.31, 0.27, -0.31, 0.31, 0.31, 0.31, W.glow((170, 250, 100)))                                              # a brighter crown
    into(p, "drip"); drips(p, 0.0, -0.3, 0.0, 4, 13, 0.25, 0.14); into(p, None)


def _sulphurskater(p):
    into(p, None)
    p.add(-0.26, 0.055, -0.065, 0.12, 0.07, 0.065, W.membrane((90, 96, 50), SULF))                                     # folded wing cases
    for s in (-1, 1): p.add(0.29, -0.02, s * 0.02 - 0.012, 0.33, 0.0, s * 0.02 + 0.012, CLAW)                         # mandibles
    into(p, "legl")
    for (x, z) in ((0.075, -0.32), (-0.035, -0.4), (-0.135, -0.42)): p.add(x - 0.02, -0.0, z - 0.02, x + 0.02, 0.035, z + 0.02, solid(OLIVE_D, 2))   # knees
    for (x, z) in ((0.43, -0.31), (-0.03, -0.4), (-0.48, -0.42)): p.add(x - 0.03, -0.13, z - 0.03, x + 0.03, -0.115, z + 0.03, W.glow((220, 255, 140)))   # glowing pads
    into(p, "legr")
    for (x, z) in ((0.075, 0.32), (-0.035, 0.4), (-0.135, 0.42)): p.add(x - 0.02, -0.0, z - 0.02, x + 0.02, 0.035, z + 0.02, solid(OLIVE_D, 2))
    for (x, z) in ((0.43, 0.31), (-0.03, 0.4), (-0.48, 0.42)): p.add(x - 0.03, -0.13, z - 0.03, x + 0.03, -0.115, z + 0.03, W.glow((220, 255, 140)))
    into(p, None)


def _acidjelly(p):
    into(p, None)
    for i in range(12):                                                                                               # rim lights
        a = i * math.pi / 6
        p.add(math.cos(a) * 0.255 - 0.02, 0.0, math.sin(a) * 0.255 - 0.02, math.cos(a) * 0.255 + 0.02, 0.04, math.sin(a) * 0.255 + 0.02, W.glow((240, 255, 160)))
    for i in range(4):                                                                                                # the four glowing rings inside
        a = i * math.pi / 2 + math.pi / 4
        x, z = math.cos(a) * 0.11, math.sin(a) * 0.11
        p.add(x - 0.04, 0.12, z - 0.04, x + 0.04, 0.16, z + 0.04, W.glow((255, 220, 90)))
    into(p, "threads")
    for i in range(4):                                                                                                # frilly oral arms
        a = i * math.pi / 2
        for k in range(4):
            x, z = math.cos(a) * (0.06 + k * 0.01), math.sin(a) * (0.06 + k * 0.01)
            p.add(x - 0.035, -0.12 - k * 0.1, z - 0.035, x + 0.035, -0.06 - k * 0.1, z + 0.035, W.membrane((160, 230, 100), (230, 255, 160)))
    into(p, None)


def _orthocera(p):
    into(p, None)
    p.add(-0.95, 0.0, -0.02, -0.15, 0.03, 0.02, solid((150, 120, 80), 2))                                              # the siphuncle line along the top
    barnacles(p, [(-0.4, 0.13, 0.05), (-0.62, 0.1, -0.04), (-0.3, 0.14, -0.08)], 0.022)
    p.add(0.02, 0.1, -0.13, 0.17, 0.15, 0.13, W.grad((90, 100, 50), (50, 60, 26), 3, LIME, 0.06))                     # the hood
    p.add(0.06, -0.15, -0.035, 0.16, -0.1, 0.035, solid((80, 90, 40), 3))                                              # the siphon
    p.add(0.155, -0.14, -0.025, 0.17, -0.11, 0.025, DARK)
    drips(p, -0.96, 0.0, 0.0, 2, 21, 0.01)


def _trilobite(p):
    into(p, None)
    for s in (-1, 1): p.add(-0.12, -0.03, s * 0.18 - 0.012, 0.12, 0.0, s * 0.18 + 0.012, solid((90, 80, 56), 2))       # genal spines sweeping back
    for s in (-1, 1): p.add(0.15, 0.08, s * 0.1 - 0.03, 0.22, 0.11, s * 0.1 + 0.03, W.grad((40, 34, 20), (20, 16, 10), 2, (180, 220, 90), 0.4))   # compound eye domes
    into(p, "body1")
    for i in range(3):
        x = 0.1 - (i + 1) * 0.07
        pair(p, x + 0.01, -0.035, 0.17 - i * 0.01, x + 0.045, -0.015, 0.215 - i * 0.01, solid((96, 86, 60), 2))      # pleural spines
    into(p, "body2")
    for i in range(3):
        x = -0.11 - (i + 1) * 0.07
        pair(p, x + 0.01, -0.03, 0.14 - i * 0.02, x + 0.045, -0.01, 0.18 - i * 0.02, solid((96, 86, 60), 2))
    pair(p, -0.46, -0.02, 0.03, -0.37, 0.0, 0.05, solid((96, 86, 60), 2))                                               # tail spines
    into(p, None)


def _skyfin(p):
    into(p, None)
    p.add(-0.12, 0.08, -0.012, 0.1, 0.16, 0.012, finp(OLIVE_D, LIME))                                                 # dorsal fin
    pair(p, -0.16, -0.12, 0.03, -0.04, -0.07, 0.06, finp(OLIVE, LIME))                                                  # pelvic fins
    for x in (0.2, 0.22): pair(p, x, -0.04, 0.081, x + 0.01, 0.04, 0.085, solid((30, 40, 20), 2))                     # gills
    for side, s in (("wingl", -1), ("wingr", 1)):
        into(p, side)
        a, b = sorted((s * 0.46, s * 0.55))
        p.add(0.06, 0.012, a, 0.18, 0.032, b, W.glow((200, 255, 120)))                                                 # glowing wing tips
    into(p, None)


def _leech(p):
    for b, x, sgn in (("front", 0.37, 1), ("back", -0.38, -1)):
        into(p, b)
        for i in range(8):
            a = i * math.pi / 4
            y, z = math.sin(a) * 0.05, math.cos(a) * 0.05
            p.add(x - 0.012, y - 0.008, z - 0.008, x + 0.012, y + 0.008, z + 0.008, TEETH)                             # rings of hooks
    into(p, None)
    p.add(-0.25, 0.062, -0.03, 0.25, 0.07, 0.03, slick((80, 60, 64)))                                                  # a glistening back
    drips(p, 0.05, -0.06, 0.0, 3, 31, 0.15, 0.06)


def _boglurker(p):
    into(p, None)
    for (x, z, L) in ((0.2, 0.15, 0.25), (-0.15, 0.22, 0.3), (0.1, -0.22, 0.2), (-0.22, -0.1, 0.28), (0.22, -0.05, 0.18)):   # hanging moss
        p.add(x - 0.015, 0.3 - L, z - 0.015, x + 0.015, 0.3, z + 0.015, W.grad((120, 140, 90), (80, 96, 60), 2, (170, 190, 130), 0.2))
    for (x, y, z) in ((-0.23, 0.0, 0.05), (0.0, -0.1, -0.23), (-0.1, 0.15, 0.23)):                                      # glowing shelf mushrooms
        p.add(x - 0.05, y, z - 0.05, x + 0.05, y + 0.025, z + 0.05, W.glow((190, 255, 90)))
    for (x, y) in ((-0.1, -0.2), (0.05, 0.1), (-0.05, -0.05)): p.add(x, y, 0.222, x + 0.05, y + 0.08, 0.232, DARK)      # bark knots
    into(p, "jaw"); drips(p, 0.25, -0.12, 0.0, 3, 41, 0.1); into(p, None)


def _corrosionshark(p):
    into(p, None)
    for (x, y, z) in ((-0.15, 0.06, 0.155), (0.05, -0.04, -0.16), (-0.35, 0.02, -0.12)):                               # corroded patches glowing through
        a, b = sorted((z, z + (0.012 if z > 0 else -0.012)))
        p.add(x, y, a, x + 0.09, y + 0.06, b, DRIP)
    p.add(-0.2, 0.17, -0.012, -0.17, 0.3, 0.012, rusty((110, 70, 40)))                                                  # a rusty harpoon stuck in its back
    p.add(-0.22, 0.29, -0.03, -0.15, 0.32, 0.03, rusty((90, 60, 34)))
    p.add(-0.2, 0.3, -0.004, -0.17, 0.4, 0.004, solid((180, 170, 140), 2))                                               # its frayed rope
    barnacles(p, [(-0.45, 0.08, 0.04), (0.2, 0.14, -0.05)], 0.025)


def _urchin(p):
    into(p, None)
    for i in range(10):                                                                                               # tube feet
        a = i * math.pi / 5
        x, z = math.cos(a) * 0.17, math.sin(a) * 0.17
        p.add(x - 0.012, -0.26, z - 0.012, x + 0.012, -0.17, z + 0.012, W.glow((230, 140, 190)))
    p.add(-0.05, -0.215, -0.05, 0.05, -0.2, 0.05, W.glow((250, 230, 90)))                                              # the glowing mouth underneath
    for i in range(5):                                                                                                # tiny stinging tips
        a = i * 2 * math.pi / 5
        p.add(math.cos(a) * 0.12 - 0.015, 0.16, math.sin(a) * 0.12 - 0.015, math.cos(a) * 0.12 + 0.015, 0.21, math.sin(a) * 0.12 + 0.015, W.glow((255, 120, 180)))


def _crabling(p):
    into(p, None)
    barnacles(p, [(-0.1, 0.12, 0.1)], 0.025)
    for z in (-0.24, -0.12, 0.0, 0.12, 0.24): p.add(0.18, 0.1, z - 0.015, 0.22, 0.14, z + 0.015, solid((160, 80, 40), 2))   # rim spikes
    for b, s in (("clawl", -1), ("clawr", 1)):
        into(p, b)
        a, c = sorted((s * 0.2, s * 0.26))
        for k in range(3): p.add(0.4 + k * 0.04, 0.1, a, 0.42 + k * 0.04, 0.13, c, solid((240, 230, 200), 2))         # claw teeth
    into(p, None)


def _bloatgut(p):
    into(p, None)
    p.add(0.27, -0.09, -0.08, 0.31, -0.07, 0.08, solid((170, 80, 60), 2)); p.add(0.27, 0.0, -0.08, 0.31, 0.02, 0.08, solid((170, 80, 60), 2))   # lips
    for (y, z) in ((0.05, 0.2), (-0.05, -0.2), (0.15, -0.1), (-0.15, 0.12)):
        a, b = sorted((0.28, 0.29)); p.add(0.0 + z * 0.3, y, z - 0.03, 0.06 + z * 0.3, y + 0.03, z + 0.03, solid((60, 70, 20), 2))
    pair(p, -0.24, -0.03, 0.27, 0.18, 0.0, 0.285, W.glow((220, 255, 120)))                                              # glowing side stripe
    drips(p, 0.3, -0.09, 0.0, 2, 51, 0.03)


def _sulphurmoth(p):
    into(p, None)
    p.add(0.06, -0.08, -0.08, 0.13, 0.08, 0.08, W.grad((240, 230, 180), (200, 180, 120), 3, (255, 255, 230), 0.2))     # fluffy collar
    for x in (-0.05, 0.03, 0.1): pair(p, x, -0.18, 0.04, x + 0.012, -0.06, 0.06, solid((120, 90, 40), 2))               # legs
    p.add(0.2, -0.06, -0.005, 0.27, -0.05, 0.005, solid((120, 90, 40), 2))                                              # the curled proboscis
    for side, s in (("wingl", -1), ("wingr", 1)):
        into(p, side)
        for k in range(3):
            a, b = sorted((s * 0.08, s * (0.44 - k * 0.04)))
            p.add(-0.06 + k * 0.08, 0.071, a, -0.05 + k * 0.08, 0.074, b, solid((170, 130, 40), 2))                   # wing veins
    into(p, None)


def _hazardturtle(p):
    into(p, None)
    for (x, z) in ((-0.2, -0.15), (-0.2, 0.15), (0.05, -0.17), (0.05, 0.17), (0.2, 0.0)):
        p.add(x - 0.06, 0.195, z - 0.05, x + 0.06, 0.205, z + 0.05, solid((50, 56, 32), 2))                             # scute borders
    for (x, z) in ((0.1, 0.18), (-0.1, -0.19), (0.22, -0.12)): p.add(x - 0.02, 0.18, z - 0.02, x + 0.02, 0.25, z + 0.02, SULFGLOW)   # small crystals
    into(p, "head")
    p.add(0.45, -0.02, -0.03, 0.49, 0.03, 0.03, solid((60, 56, 30), 2))                                                 # the hooked beak
    for b in ("frontl", "frontr", "backl", "backr"):
        if b not in p.pivots: continue
        into(p, b)
        x, _, z = p.pivots[b]
        s = 1 if z > 0 else -1
        for k in range(3): p.add(x + 0.06 + k * 0.012, -0.085, s * (0.32 + k * 0.03), x + 0.12, -0.07, s * (0.33 + k * 0.03), CLAW)
    into(p, None)
    for x in (-0.44, -0.4): p.add(x, 0.04, -0.012, x + 0.025, 0.07, 0.012, SULFGLOW)                                     # tail spikes


def _sludgesnail(p):
    into(p, None)
    for (x, y, z) in ((-0.2, -0.02, 0.17), (0.0, 0.12, -0.12), (-0.12, 0.18, 0.1)):
        p.add(x - 0.025, y, z - 0.02, x + 0.025, y + 0.04, z + 0.02, DRIP)                                               # rust holes glowing with slime
    drips(p, -0.24, -0.06, 0.12, 2, 61, 0.03); drips(p, 0.05, -0.06, -0.12, 2, 62, 0.03)
    for (x, y, z) in ((-0.15, 0.24, -0.06), (0.04, 0.33, 0.05)): p.add(x - 0.02, y, z - 0.02, x + 0.02, y + 0.04, z + 0.02, W.glow((230, 255, 190)))   # slime bubbles
    into(p, "head")
    for s in (-1, 1): p.add(0.39, -0.1, s * 0.04 - 0.01, 0.46, -0.08, s * 0.04 + 0.01, solid((110, 130, 60), 2))       # lower tentacles
    into(p, None)


def _detailed(fn, extra):
    def g():
        p = fn()
        p.ppb = 32
        extra(p)
        p.cur_bone = None
        return p
    g.__name__ = fn.__name__
    g.__doc__ = fn.__doc__
    return g


acideel, trasher, radiator, flakcrab, nucleartoad = (_detailed(acideel, _acideel), _detailed(trasher, _trasher), _detailed(radiator, _radiator),
                                                     _detailed(flakcrab, _flakcrab), _detailed(nucleartoad, _nucleartoad))
gammaslime, sulphurskater, acidjelly, orthocera, trilobite = (_detailed(gammaslime, _gammaslime), _detailed(sulphurskater, _sulphurskater),
                                                              _detailed(acidjelly, _acidjelly), _detailed(orthocera, _orthocera), _detailed(trilobite, _trilobite))
skyfin, leech, boglurker, corrosionshark, urchin = (_detailed(skyfin, _skyfin), _detailed(leech, _leech), _detailed(boglurker, _boglurker),
                                                    _detailed(corrosionshark, _corrosionshark), _detailed(urchin, _urchin))
crabling, bloatgut, sulphurmoth, hazardturtle, sludgesnail = (_detailed(crabling, _crabling), _detailed(bloatgut, _bloatgut),
                                                              _detailed(sulphurmoth, _sulphurmoth), _detailed(hazardturtle, _hazardturtle), _detailed(sludgesnail, _sludgesnail))


# --------------------------------------------------------------------------------------------- the acid mobs, detail pass 2 (Deep 1.6.0)
# Shape first: tapered snouts and tails, real teeth rows, joints on legs, segmented shells, eye ridges, finer spines and
# glowing veins, so the silhouettes stop reading as boxes.
VEIN = W.glow((200, 255, 120))


def _ring(p, x, r, paint, n=8, w=0.012, y=0.0):
    """A ring of little bumps round the x axis at radius r (ribs, sucker rims, collars)."""
    for i in range(n):
        a = i * 2 * math.pi / n
        cy, cz = y + math.sin(a) * r, math.cos(a) * r
        p.add(x - w, cy - w, cz - w, x + w, cy + w, cz + w, paint)


def _m_acideel(p):
    into(p, None)
    p.add(0.56, -0.05, -0.04, 0.63, 0.03, 0.04, W.grad(OLIVE, OLIVE_D, 3, LIME, 0.05))                                 # a tapered snout tip
    for s in (-1, 1): p.add(0.45, 0.055, s * 0.06 - 0.018, 0.53, 0.075, s * 0.06 + 0.018, solid(OLIVE_D, 2))            # brow ridges
    for s in (-1, 1): p.add(0.6, -0.0, s * 0.02 - 0.006, 0.62, 0.012, s * 0.02 + 0.006, DARK)                           # nostrils
    into(p, "jaw")
    for i in range(4): p.add(0.52 + i * 0.022, -0.065, -0.05, 0.53 + i * 0.022, -0.035, 0.05, TEETH)                   # lower teeth
    for b, x0, x1 in (("s1", -0.14, 0.2), ("s2", -0.48, -0.14), ("s3", -0.65, -0.48)):
        into(p, b)
        for k in range(int((x1 - x0) / 0.06)):
            x = x0 + 0.02 + k * 0.06
            p.add(x, 0.06, -0.006, x + 0.02, 0.13 - k * 0.004, 0.006, VEIN)                                            # glowing frill spines
    into(p, "s3")
    for k in range(4): p.add(-0.84 + k * 0.05, -0.08 + k * 0.01, -0.014, -0.83 + k * 0.05, 0.08 - k * 0.01, 0.014, solid(OLIVE_D, 2))   # tail fin rays
    into(p, None)


def _m_trasher(p):
    into(p, None)
    for x in (-0.25, -0.12, 0.01, 0.14): p.add(x, -0.125, -0.13, x + 0.08, -0.115, 0.13, solid((150, 150, 96), 2))      # belly scale rows
    p.add(0.16, -0.1, -0.15, 0.22, 0.06, 0.15, W.grad((70, 84, 46), (36, 44, 22), 3, (110, 130, 60), 0.05))              # a thick neck
    into(p, "head")
    p.add(0.58, -0.035, -0.06, 0.66, 0.035, 0.06, W.grad((70, 84, 46), (36, 44, 22), 3, (110, 130, 60), 0.05))          # a broad snout tip
    for i in range(3): pair(p, 0.38 + i * 0.07, 0.04, 0.07, 0.42 + i * 0.07, 0.05, 0.075, solid((54, 64, 34), 2))       # snout bumps
    for s in (-1, 1): p.add(0.6, -0.04, s * 0.06, 0.64, 0.02, s * 0.065, TEETH)                                          # the tooth that shows when shut
    into(p, "jaw")
    p.add(0.58, -0.085, -0.06, 0.66, -0.045, 0.06, W.grad((70, 84, 46), (36, 44, 22), 3, (110, 130, 60), 0.05))
    into(p, "tail")
    p.add(-0.95, -0.04, -0.04, -0.83, 0.03, 0.04, W.grad((70, 84, 46), (36, 44, 22), 3, (110, 130, 60), 0.05))          # the tapering tail tip
    for x in (-0.92, -0.86): p.add(x, 0.03, -0.01, x + 0.04, 0.07, 0.01, solid((54, 64, 34), 3))
    into(p, None)


def _m_radiator(p):
    into(p, None)
    for (y, z) in ((0.12, 0.1), (-0.1, 0.1), (0.12, -0.1), (-0.1, -0.1)):
        a, b = sorted((z, z + (0.025 if z > 0 else -0.025)))
        p.add(-0.2, y, a, 0.2, y + 0.02, b, solid((70, 96, 40), 2))                                                     # dark scale bands over the glow
    for i in range(3): p.add(-0.1 + i * 0.08, -0.04 + (i % 2) * 0.06, -0.126, -0.06 + i * 0.08, 0.0 + (i % 2) * 0.06, 0.126, W.glow((250, 255, 200)))   # hot spots
    p.add(0.26, -0.06, -0.035, 0.3, -0.04, 0.035, TEETH)
    into(p, "tail")
    for k in range(4): p.add(-0.48 + k * 0.06, -0.17 + k * 0.02, -0.018, -0.47 + k * 0.06, 0.19 - k * 0.02, 0.018, VEIN)   # glowing rays
    into(p, None)


def _m_flakcrab(p):
    for b, s in (("legl", -1), ("legr", 1)):
        into(p, b)
        for i in range(3):
            x = -0.18 + i * 0.14
            a, c = sorted((s * 0.52, s * 0.6))
            p.add(x - 0.035, -0.04, a, x + 0.035, 0.06, c, solid((90, 50, 24), 2))                                     # knee joints
            a2, c2 = sorted((s * 0.54, s * 0.57))
            p.add(x - 0.015, -0.4, a2, x + 0.015, -0.33, c2, solid((60, 34, 18), 2))                                   # pointed feet
    for b, s in (("clawl", -1), ("clawr", 1)):
        into(p, b)
        for k in range(4):
            a, c = sorted((s * 0.25, s * 0.29))
            p.add(0.66 + k * 0.025, 0.06, a, 0.675 + k * 0.025, 0.09, c, solid((230, 210, 170), 2))                    # serrations
        a, c = sorted((s * 0.18, s * 0.38))
        p.add(0.44, 0.12, a, 0.62, 0.15, c, rusty())                                                                   # a rusted plate on the claw
    into(p, None)
    for z in (-0.18, -0.06, 0.06, 0.18): p.add(0.335, 0.03, z - 0.03, 0.345, 0.1, z + 0.03, solid((230, 190, 40), 2))   # hazard stripes on the front plate
    for (x, z) in ((-0.12, -0.14), (-0.12, 0.14), (0.04, 0.0), (-0.2, 0.0)):
        p.add(x - 0.05, 0.32, z - 0.05, x + 0.05, 0.34, z + 0.05, solid((40, 44, 38), 2))                             # nozzle collars


def _m_nucleartoad(p):
    into(p, None)
    p.add(-0.2, 0.12, -0.03, 0.14, 0.145, 0.03, solid((70, 90, 30), 2))                                                # a ridge down its back
    for s in (-1, 1): p.add(0.1, 0.06, s * 0.17, 0.3, 0.09, s * 0.175, ACIDGLOW)                                        # glowing parotoid glands
    for (x, z) in ((-0.2, -0.12), (0.0, 0.16), (-0.08, 0.05), (0.08, -0.06), (-0.16, 0.0)):
        p.add(x - 0.035, 0.12, z - 0.035, x + 0.035, 0.125, z + 0.035, solid((50, 66, 20), 2))                        # dark mottles
    drips(p, 0.33, -0.02, 0.0, 3, 71, 0.08, 0.06)                                                                       # drool
    into(p, "sac")
    for i in range(4): p.add(0.18 + i * 0.035, -0.225, -0.1, 0.19 + i * 0.035, -0.07, 0.1, solid((110, 200, 60), 2))   # veins on the sac
    into(p, None)


def _m_gammaslime(p):
    into(p, None)
    p.add(-0.08, -0.03, -0.06, 0.06, 0.08, 0.06, solid(BONEW, 3))                                                       # a skull floating in the core
    for s in (-1, 1): p.add(0.055, 0.02, s * 0.03 - 0.015, 0.065, 0.05, s * 0.03 + 0.015, DARK)
    p.add(0.055, -0.03, -0.03, 0.065, -0.01, 0.03, DARK)
    p.add(-0.2, -0.22, 0.1, -0.12, -0.14, 0.18, rusty())                                                                # a rusty gear swallowed long ago
    for i in range(4):                                                                                                  # swirls of brighter jelly
        a = i * math.pi / 2
        x, z = math.cos(a) * 0.22, math.sin(a) * 0.22
        p.add(x - 0.04, -0.05 + i * 0.05, z - 0.04, x + 0.04, 0.0 + i * 0.05, z + 0.04, W.glow((200, 255, 130)))
    into(p, None)


def _m_sulphurskater(p):
    into(p, None)
    for i in range(4): p.add(-0.28 + i * 0.07, -0.035, -0.075, -0.24 + i * 0.07, 0.065, 0.075, solid((50, 56, 32), 2))  # abdomen segments
    p.add(0.18, 0.04, -0.055, 0.28, 0.06, 0.055, W.grad((90, 96, 50), (50, 56, 30), 2, SULF, 0.1))                      # head shield
    p.add(0.0, 0.06, -0.05, 0.16, 0.08, 0.05, SULFGLOW)                                                                 # a second stripe
    for b, s in (("legl", -1), ("legr", 1)):
        into(p, b)
        for (x, z) in ((0.3, 0.3), (-0.02, 0.27), (-0.3, 0.41)):
            a, c = sorted((s * z, s * (z + 0.03)))
            p.add(x - 0.04, -0.03, a, x + 0.04, -0.015, c, solid((140, 130, 70), 2))                                   # hairy pads
    into(p, None)


def _m_acidjelly(p):
    into(p, None)
    rounded(p, -0.1, 0.3, -0.1, 0.1, 0.36, 0.1, W.glow((150, 230, 100)), 0.03)                                         # the top of the bell
    for i in range(8):                                                                                                  # radial canals
        a = i * math.pi / 4
        p.add(math.cos(a) * 0.12 - 0.012, 0.23, math.sin(a) * 0.12 - 0.012, math.cos(a) * 0.24 + 0.012, 0.245, math.sin(a) * 0.24 + 0.012, VEIN)
    into(p, "threads")
    for i in range(10):                                                                                                 # glowing knots down the threads
        a = i * math.pi / 5
        r = 0.12 + (i % 2) * 0.07
        x, z = math.cos(a) * r, math.sin(a) * r
        for y in (-0.3, -0.55): p.add(x - 0.02, y, z - 0.02, x + 0.02, y + 0.03, z + 0.02, W.glow((240, 255, 160)))
    into(p, None)


def _m_orthocera(p):
    into(p, None)
    for i in range(7):                                                                                                  # raised growth rings on the cone
        r = 0.16 - i * 0.02 + 0.012
        x0 = -0.15 - i * 0.12
        p.add(x0 - 0.015, -r, -r, x0, r, r, solid((240, 226, 190), 2))
    for s in (-1, 1): p.add(0.1, 0.09, s * 0.12 - 0.02, 0.14, 0.13, s * 0.12 + 0.02, solid((70, 80, 30), 2))           # eye ridges
    into(p, "arms")
    for i in range(10):                                                                                                 # suckers
        a = i * math.pi / 5
        y, z = math.sin(a) * 0.07, math.cos(a) * 0.07
        for k in range(3): p.add(0.26 + k * 0.07, y - 0.022, z - 0.022, 0.28 + k * 0.07, y + 0.022, z + 0.022, solid((200, 210, 140), 2))
    into(p, None)


def _m_trilobite(p):
    into(p, None)
    p.add(0.16, 0.06, -0.06, 0.28, 0.1, 0.06, W.grad((130, 120, 86), (80, 72, 50), 3, (170, 160, 110), 0.1))           # the glabella bump
    for s in (-1, 1): W.tube(p, [(0.3, 0.0, s * 0.05), (0.42, 0.03, s * 0.12), (0.5, 0.0, s * 0.2)], 0.008, 0.005, solid((150, 130, 90), 2))   # antennae
    into(p, "body2")
    for k in range(3): p.add(-0.46 + k * 0.03, -0.025, -0.05 + k * 0.012, -0.44 + k * 0.03, 0.04, 0.05 - k * 0.012, solid((96, 86, 60), 2))   # tail segments
    into(p, None)


def _m_skyfin(p):
    into(p, None)
    for x in (-0.2, -0.05, 0.1): pair(p, x, 0.02, 0.081, x + 0.05, 0.05, 0.085, W.glow((210, 255, 140)))                # glowing flank spots
    p.add(0.36, -0.03, -0.02, 0.44, -0.015, 0.02, solid((40, 50, 30), 2))                                               # an underbite
    for side, s in (("wingl", -1), ("wingr", 1)):
        into(p, side)
        for k in range(4):
            a, b = sorted((s * (0.12 + k * 0.1), s * (0.14 + k * 0.1)))
            p.add(-0.1 + k * 0.02, 0.03, a, 0.16 - k * 0.02, 0.04, b, solid((60, 80, 40), 2))                         # cross ribs
    into(p, "tail")
    for k in range(3): p.add(-0.54 + k * 0.08, 0.02, -0.02, -0.53 + k * 0.08, 0.18 - k * 0.04, 0.02, solid(OLIVE_D, 2))
    into(p, None)


def _m_leech(p):
    for b, xs in (("front", (0.14, 0.24)), ("back", (-0.06, -0.16, -0.26))):
        into(p, b)
        for x in xs: _ring(p, x, 0.07, solid((40, 26, 34), 2), 10, 0.01)                                                # ring seams
    into(p, None)
    _ring(p, 0.04, 0.08, solid((40, 26, 34), 2), 10, 0.01)
    for x in (-0.3, -0.1, 0.1, 0.3): p.add(x, 0.066, -0.015, x + 0.03, 0.074, 0.015, VEIN)                              # a glowing dot down the back


def _m_boglurker(p):
    into(p, None)
    for (x, z, h) in ((0.05, 0.1, 0.18), (-0.1, -0.08, 0.14), (0.12, -0.12, 0.1)):
        p.add(x - 0.02, 0.32, z - 0.02, x + 0.02, 0.32 + h, z + 0.02, W.grad((50, 40, 32), (28, 22, 18), 2))           # broken twigs on top
        p.add(x - 0.06, 0.28 + h, z - 0.008, x + 0.02, 0.3 + h, z + 0.008, W.grad((50, 40, 32), (28, 22, 18), 2))
    for s in (-1, 1): p.add(0.24, 0.1, s * 0.08 - 0.05, 0.25, 0.21, s * 0.08 + 0.05, solid((70, 50, 20), 2))           # bark rims round the eyes
    for b, s in (("arml", -1), ("armr", 1)):
        into(p, b)
        a, c = sorted((s * 0.22, s * 0.3))
        for k in range(3): p.add(0.1 + k * 0.0, -0.26 - k * 0.015, a + k * 0.02 * s, 0.16, -0.22, a + 0.02 + k * 0.02 * s, CLAW)   # root claws
    into(p, None)


def _m_corrosionshark(p):
    into(p, "jaw")
    for i in range(5): p.add(0.36 + i * 0.033, -0.105, -0.085, 0.37 + i * 0.033, -0.08, 0.085, solid((230, 220, 150), 2))   # a second row of yellowed teeth
    into(p, None)
    for i in range(5): p.add(0.36 + i * 0.033, -0.095, -0.085, 0.37 + i * 0.033, -0.075, 0.085, solid((230, 220, 150), 2))
    p.add(0.5, -0.06, -0.06, 0.56, -0.04, 0.06, rusty((120, 100, 80)))                                                  # bits of armour stuck in its teeth
    for s in (-1, 1): p.add(0.4, 0.07, s * 0.1, 0.48, 0.09, s * 0.12, solid((40, 50, 24), 2))                           # heavy brows
    into(p, "tail")
    for x in (-0.6, -0.5): p.add(x, 0.07, -0.01, x + 0.04, 0.1, 0.01, DRIP)                                             # glowing tail sores
    into(p, None)


def _m_urchin(p):
    into(p, None)
    for i in range(5):                                                                                                  # the five plates of its test
        a = i * 2 * math.pi / 5
        for k in range(3):
            y = 0.18 - k * 0.12
            r = math.sqrt(max(0.0, 0.04 - y * y)) + 0.01
            x, z = math.cos(a) * r, math.sin(a) * r
            p.add(x - 0.015, y - 0.015, z - 0.015, x + 0.015, y + 0.015, z + 0.015, solid((210, 150, 190), 2))
    into(p, None)


def _m_crabling(p):
    into(p, None)
    for z in (-0.18, 0.0, 0.18): p.add(-0.18, 0.12, z - 0.012, 0.18, 0.135, z + 0.012, solid((150, 74, 36), 2))         # shell ridges
    p.add(0.2, -0.05, -0.06, 0.23, 0.0, 0.06, solid((140, 60, 40), 2))                                                  # mouthparts
    for s in (-1, 1): p.add(0.2, 0.06, s * 0.03 - 0.006, 0.34, 0.07, s * 0.03 + 0.006, solid((150, 70, 34), 2))         # antennae
    for b, s in (("legl", -1), ("legr", 1)):
        into(p, b)
        for i in range(3):
            x = -0.14 + i * 0.12
            a, c = sorted((s * 0.41, s * 0.47))
            p.add(x - 0.03, -0.03, a, x + 0.03, 0.03, c, solid((120, 56, 28), 2))                                      # knees
    into(p, None)


def _m_bloatgut(p):
    into(p, "spines")
    for (x, y, z) in ((0.2, 0.2, 0.0), (-0.2, 0.2, 0.0), (0.2, -0.2, 0.0), (-0.2, -0.2, 0.0), (0.0, 0.2, 0.2), (0.0, -0.2, -0.2)):
        d = [0.02, 0.02, 0.02]
        for i, v in enumerate((x, y, z)):
            if abs(v) > 0.1: d[i] = 0.06
        p.add(x - d[0], y - d[1], z - d[2], x + d[0], y + d[1], z + d[2], SULFGLOW)                                    # more spines
    into(p, None)
    for (y, z) in ((0.1, 0.18), (-0.12, -0.15), (0.05, -0.2)):
        a, b = sorted((z, z + (0.1 if z > 0 else -0.1)))
        p.add(-0.25, y, a, -0.1, y + 0.06, b, W.glow((220, 255, 110)))                                                 # gas pockets glowing under the skin
    _ring(p, 0.32, 0.05, solid((170, 80, 60), 2), 8, 0.015, -0.04)                                                       # puckered lips


def _m_sulphurmoth(p):
    into(p, None)
    for i in range(4): p.add(-0.22 + i * 0.06, -0.075, -0.075, -0.19 + i * 0.06, 0.075, 0.075, solid((170, 140, 60), 2))   # abdomen bands
    for (x, s) in ((0.0, 1), (-0.1, -1), (0.08, -1), (-0.15, 1)): p.add(x, 0.065, s * 0.06 - 0.02, x + 0.04, 0.09, s * 0.06 + 0.02, solid((250, 240, 190), 2))   # fur tufts
    for side, s in (("wingl", -1), ("wingr", 1)):
        into(p, side)
        a, b = sorted((s * 0.36, s * 0.46))
        p.add(-0.1, 0.071, a, 0.18, 0.075, b, solid((150, 110, 40), 2))                                                # a dark wing border
        c0, c1 = sorted((s * 0.12, s * 0.2))
        p.add(-0.24, 0.066, c0, -0.16, 0.07, c1, W.glow((255, 250, 200)))                                              # hindwing spots
    into(p, None)


def _m_hazardturtle(p):
    into(p, None)
    for (x, z) in ((-0.3, -0.2), (-0.3, 0.2), (-0.1, -0.26), (-0.1, 0.26), (0.1, -0.26), (0.1, 0.26), (0.26, -0.18), (0.26, 0.18)):
        p.add(x - 0.05, 0.0, z - 0.03, x + 0.05, 0.08, z + 0.03, solid((50, 56, 32), 2))                               # marginal scutes round the rim
    for (x, z, h) in ((0.0, 0.0, 0.2), (-0.15, 0.1, 0.14)):
        for k, (dx, dz) in enumerate(((0.04, 0.03), (-0.04, -0.02), (0.02, -0.04))):
            p.add(x + dx - 0.018, 0.18, z + dz - 0.018, x + dx + 0.018, 0.18 + h * 0.6, z + dz + 0.018, SULFGLOW)       # clusters at the base of the big crystals
    into(p, "head")
    for (x, z) in ((0.3, 0.0), (0.36, 0.04), (0.36, -0.04)): p.add(x - 0.02, 0.1, z - 0.02, x + 0.02, 0.11, z + 0.02, solid((90, 100, 60), 2))   # head scales
    for s in (-1, 1): p.add(0.37, 0.075, s * 0.07, 0.44, 0.09, s * 0.075, solid((70, 80, 40), 2))                        # eye ridges
    into(p, None)


def _m_sludgesnail(p):
    into(p, None)
    for (r, y, x) in ((0.2, 0.06, -0.08), (0.14, 0.2, -0.04), (0.09, 0.3, 0.0)):                                       # the spiral groove
        p.add(x - r - 0.005, y - 0.012, r * 0.9, x + r + 0.005, y + 0.012, r * 0.9 + 0.006, solid((60, 34, 18), 2))
        p.add(x - r - 0.005, y - 0.012, -r * 0.9 - 0.006, x + r + 0.005, y + 0.012, -r * 0.9, solid((60, 34, 18), 2))
    for (x, y) in ((-0.2, 0.22), (0.0, 0.3), (-0.12, 0.38)): p.add(x - 0.02, y, -0.02, x + 0.02, y + 0.06, 0.02, rusty())   # rusted spikes
    p.add(-0.36, -0.19, -0.04, -0.3, -0.17, 0.04, W.glow((160, 240, 90)))                                               # the trail behind
    into(p, "head")
    p.add(0.24, -0.09, -0.08, 0.28, 0.05, 0.08, slick((110, 130, 60)))                                                  # the mantle collar
    into(p, None)


for _n in ("acideel", "trasher", "radiator", "flakcrab", "nucleartoad", "gammaslime", "sulphurskater", "acidjelly", "orthocera", "trilobite",
           "skyfin", "leech", "boglurker", "corrosionshark", "urchin", "crabling", "bloatgut", "sulphurmoth", "hazardturtle", "sludgesnail"):
    globals()[_n] = _detailed(globals()[_n], globals()["_m_" + _n])


# --------------------------------------------------------------------------------------------- the Acid Rain (Deep 1.5.0)
# Five creatures that only come with the Acid Rain, and Grubnash the Acid Goblin (mini boss). Humanoids are about one
# block tall (feet at y -0.5), front +x, walking with swinging limbs.
def face_eyes(p, x, y0, y1, zc, w, white, iris, pupil=(14, 12, 18), slit=False):
    """A pair of eyes on the FRONT face (+x) at z = +-zc, each w wide (thin boxes just in front of the face)."""
    for s in (-1, 1):
        p.add(x, y0, s * zc - w / 2, x + 0.008, y1, s * zc + w / 2, W.eye_paint(white, iris, pupil, slit))


def _limbs(p, sk, pants, arm_w=0.05, leg_w=0.055, shoulder=0.13, hip=0.055, arm_paint=None, boots=None, hands=None):
    """Two legs and two arms as bones (legl/legr, arml/armr), swinging like a walk."""
    for side, s in (("l", -1), ("r", 1)):
        p.bone("leg" + side, (0, -0.1, s * hip))
        a, b = sorted((s * (hip - leg_w), s * (hip + leg_w)))
        p.add(-leg_w, -0.42, a, leg_w, -0.1, b, pants)
        p.add(-leg_w - 0.005, -0.5, a - 0.005, leg_w + 0.03, -0.42, b + 0.005, boots or pants)
        p.bone(None)
        p.bone("arm" + side, (0, 0.22, s * shoulder))
        a, b = sorted((s * shoulder, s * (shoulder + arm_w * 2)))
        p.add(-arm_w, -0.08, a, arm_w, 0.24, b, arm_paint or sk)
        p.add(-arm_w + 0.005, -0.12, a + 0.005 * s, arm_w - 0.005, -0.08, b - 0.005 * s, hands or sk)
        p.bone(None)


def _walk(p, legs=24, arms=20, secs=1.0, head=None):
    p.anim("legl", "rot", (legs, 0, 0), secs).anim("legr", "rot", (legs, 0, 0), secs, 0.5)
    p.anim("arml", "rot", (arms, 0, 0), secs, 0.5).anim("armr", "rot", (arms, 0, 0), secs)
    if head: p.anim("head", "rot", head, secs * 2)
    return p


def shambler():
    """A drowned sailor soaked through with acid: rotting olive skin, a torn striped shirt, kelp, ribs glowing through."""
    p = Part("shambler", 32, 256)
    sk = slick((96, 120, 70), (220, 255, 160))
    shirt = W.stripes_h((200, 196, 170), (60, 80, 120), 2) if hasattr(W, "stripes_h") else solid((180, 180, 160), 3)
    p.add(-0.07, -0.1, -0.12, 0.07, 0.24, 0.12, shirt)                                       # the torso in a torn shirt
    p.add(0.07, 0.0, -0.06, 0.075, 0.14, 0.06, solid((40, 60, 30), 2))                      # the torn hole...
    for y in (0.02, 0.06, 0.1): p.add(0.071, y, -0.045, 0.078, y + 0.015, 0.045, DRIP)       # ...ribs glowing through
    p.add(-0.075, -0.12, -0.125, 0.075, -0.08, 0.125, solid((60, 44, 30), 3))                # a rope belt
    for (y, z, L) in ((0.2, 0.08, 0.2), (0.1, -0.11, 0.16), (-0.05, 0.12, 0.14)):            # kelp hanging off it
        p.add(-0.075, y - L, z - 0.012, -0.065, y, z + 0.012, W.grad((60, 90, 40), (30, 50, 20), 2, LIME, 0.1))
    p.bone("head", (0, 0.24, 0))
    p.add(-0.08, 0.24, -0.08, 0.08, 0.42, 0.08, sk)
    face_eyes(p, 0.08, 0.32, 0.36, 0.035, 0.04, (230, 255, 120), (40, 60, 10))
    p.add(0.08, 0.26, -0.05, 0.085, 0.3, 0.05, MOUTH)
    p.add(-0.085, 0.38, -0.085, 0.085, 0.44, 0.085, W.grad((40, 40, 30), (20, 20, 14), 2, (70, 70, 50), 0.2))   # lank hair
    drips(p, 0.08, 0.26, 0.0, 3, 71, 0.04, 0.06)
    p.bone(None)
    _limbs(p, sk, solid((50, 60, 70), 3), arm_paint=shirt, boots=solid((40, 30, 24), 3))
    into(p, "armr"); drips(p, 0.0, -0.12, 0.17, 2, 72, 0.02, 0.06); into(p, None)
    p.pivots["body"] = (0, -0.5, 0)
    return _walk(p, 26, 30, 1.4, (0, 10, 6)).anim("body", "rot", (0, 0, 5), 2.8)


def rustbones():
    """A skeleton in corroded armour, risen from the wrecks in the acid rain."""
    p = Part("rustbones", 32, 256)
    bone = W.grad(BONEW, (170, 164, 140), 3, (120, 140, 80), 0.05)
    rust = rusty()
    p.add(-0.03, -0.1, -0.03, 0.03, 0.24, 0.03, bone)                                        # the spine
    for y in (0.0, 0.06, 0.12, 0.18): p.add(-0.05, y, -0.1, 0.06, y + 0.025, 0.1, bone)       # ribs
    p.add(-0.07, 0.08, -0.13, 0.08, 0.25, 0.13, rust)                                        # a rusted breastplate, holed
    p.add(0.079, 0.12, -0.04, 0.083, 0.18, 0.02, DARK)
    p.add(-0.06, -0.12, -0.1, 0.06, -0.06, 0.1, bone)                                        # the pelvis
    for s in (-1, 1): p.add(-0.06, 0.2, s * 0.13 - 0.05, 0.06, 0.27, s * 0.13 + 0.05, rust) # pauldrons
    p.bone("head", (0, 0.25, 0))
    p.add(-0.075, 0.25, -0.075, 0.075, 0.41, 0.075, bone)
    for z in (-0.035, 0.035): p.add(0.073, 0.32, z - 0.02, 0.078, 0.35, z + 0.02, W.glow((190, 255, 90)))   # glowing sockets
    p.add(0.073, 0.26, -0.04, 0.078, 0.29, 0.04, DARK)
    p.add(-0.085, 0.36, -0.085, 0.085, 0.44, 0.085, rust)                                    # a dented, rusted helm
    p.add(0.06, 0.36, -0.09, 0.1, 0.38, 0.09, rust)
    drips(p, 0.0, 0.36, 0.08, 2, 81, 0.05, 0.05)
    p.bone(None)
    _limbs(p, bone, bone, arm_w=0.025, leg_w=0.03, arm_paint=bone, boots=rust, hands=bone)
    p.pivots["body"] = (0, -0.5, 0)
    return _walk(p, 22, 16, 1.2, (0, 12, 0))


def acidmite():
    """A glowing tick the size of a cat."""
    p = Part("acidmite", 32, 128)
    shell = W.grad((70, 90, 40), (36, 46, 20), 3, LIME, 0.1)
    rounded(p, -0.3, -0.12, -0.2, 0.12, 0.14, 0.2, shell, 0.08)                              # the swollen abdomen
    p.add(-0.26, 0.13, -0.12, 0.06, 0.16, 0.12, W.glow((190, 255, 90)))                      # glowing through its back
    for x in (-0.2, -0.1, 0.0): p.add(x, 0.155, -0.15, x + 0.02, 0.165, 0.15, shell)        # segment lines
    p.add(0.1, -0.06, -0.09, 0.26, 0.06, 0.09, solid((60, 70, 30), 3))                       # the head
    eyes(p, 0.2, 0.24, 0.0, 0.04, 0.09, (250, 255, 160), (30, 50, 10), depth=0.01)
    for s in (-1, 1): p.add(0.24, -0.06, s * 0.03 - 0.012, 0.32, -0.03, s * 0.03 + 0.012, CLAW)   # mandibles
    legs = []
    for i, x in enumerate((-0.18, -0.08, 0.02, 0.1)):
        legs += [((x - 0.015, -0.02, 0.18, x + 0.015, 0.02, 0.32), solid((40, 50, 20), 2)), ((x - 0.015, -0.18, 0.3, x + 0.015, 0.0, 0.34), solid((40, 50, 20), 2))]
    side_bone(p, "leg", (0.0, 0.0, 0.18), legs, True)
    p.pivots["body"] = (0, -0.12, 0)
    p.anim("legl", "rot", (0, 16, 10), 0.4).anim("legr", "rot", (0, -16, -10), 0.4, 0.5).anim("body", "pos", (0, 0.3, 0), 0.8)
    return p


def stormwraith():
    """A shape of green storm cloud with two burning eyes and arms of mist."""
    p = Part("stormwraith", 32, 256)
    cloud = W.grad((120, 140, 110), (70, 86, 64), 4, (180, 200, 160), 0.1)
    dark = W.grad((70, 86, 64), (40, 50, 36), 3, LIME, 0.04)
    for (x, y, z, r) in ((0, 0.15, 0, 0.2), (-0.08, 0.28, 0.1, 0.13), (-0.06, 0.26, -0.12, 0.14), (0.05, 0.05, 0.14, 0.11), (0.04, 0.04, -0.13, 0.11), (-0.12, 0.08, 0, 0.14)):
        rounded(p, x - r, y - r * 0.8, z - r, x + r, y + r * 0.8, z + r, cloud, r * 0.4)     # the billowing body
    p.add(-0.1, -0.05, -0.13, 0.1, 0.05, 0.13, dark)
    for z in (-0.07, 0.07): p.add(0.18, 0.18, z - 0.035, 0.205, 0.23, z + 0.035, W.glow((220, 255, 120)))   # burning eyes
    p.add(0.19, 0.08, -0.05, 0.2, 0.11, 0.05, W.glow((180, 255, 90)))                       # a crackling mouth
    for (x, z) in ((0.05, 0.05), (-0.05, -0.08)):                                            # little bolts in it
        p.add(x, -0.02, z, x + 0.015, 0.1, z + 0.015, W.glow((240, 255, 170))); p.add(x + 0.01, -0.08, z + 0.01, x + 0.025, -0.02, z + 0.025, W.glow((240, 255, 170)))
    for side, s in (("l", -1), ("r", 1)):                                                    # arms of mist
        p.bone("arm" + side, (0.0, 0.18, s * 0.16))
        for k in range(4):
            a, b = sorted((s * (0.16 + k * 0.07), s * (0.23 + k * 0.07)))
            r = 0.06 - k * 0.01
            p.add(0.02 + k * 0.03 - r, 0.18 - k * 0.05 - r, a, 0.02 + k * 0.03 + r, 0.18 - k * 0.05 + r, b, cloud)
        p.bone(None)
    p.bone("tail", (-0.05, -0.08, 0))                                                        # a wispy tail trailing below
    for k in range(5):
        r = 0.09 - k * 0.015
        p.add(-0.08 - k * 0.07 - r, -0.12 - k * 0.07 - r, -r, -0.08 - k * 0.07 + r, -0.12 - k * 0.07 + r, r, cloud if k % 2 else dark)
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("arml", "rot", (0, 0, 20), 1.6).anim("armr", "rot", (0, 0, -20), 1.6, 0.5).anim("tail", "rot", (0, 18, 10), 1.4)
    p.anim("body", "pos", (0, 1.4, 0), 2.0)
    return p


def mauler():
    """A hulking, blind shark-thing: no eyes, a jaw like a bear trap, bile-green hide with old wounds."""
    p = Part("mauler", 32, 512)
    sk = W.grad((90, 104, 60), (46, 54, 28), 5, (140, 160, 80), 0.06)
    belly = solid((170, 174, 120), 4)
    rounded(p, -0.3, -0.16, -0.16, 0.28, 0.16, 0.16, sk, 0.07)                              # the body
    p.add(-0.28, -0.17, -0.12, 0.26, -0.15, 0.12, belly)
    p.add(0.24, -0.1, -0.13, 0.48, 0.12, 0.13, sk)                                           # the blunt eyeless head
    for s in (-1, 1): p.add(0.3, 0.02, s * 0.13 - 0.004, 0.42, 0.06, s * 0.13 + 0.004, solid((60, 70, 40), 2))   # where eyes should be: scar tissue
    for x in (0.2, 0.17, 0.14, 0.11): pair(p, x, -0.08, 0.16, x + 0.012, 0.06, 0.165, ACIDGLOW)                    # gills
    for i in range(9): p.add(0.27 + i * 0.024, -0.1, -0.12, 0.285 + i * 0.024, -0.05, 0.12, TEETH)                # upper teeth
    p.bone("jaw", (0.26, -0.1, 0))
    p.add(0.26, -0.18, -0.12, 0.49, -0.1, 0.12, sk)
    p.add(0.27, -0.17, -0.1, 0.47, -0.16, 0.1, MOUTH)
    for i in range(9): p.add(0.27 + i * 0.024, -0.1, -0.11, 0.285 + i * 0.024, -0.06, 0.11, TEETH)
    drips(p, 0.45, -0.18, 0.0, 3, 91, 0.06, 0.08)
    p.bone(None)
    staircase(p, -0.1, 0.14, 0.15, 0.24, -0.015, 0.015, finp((60, 70, 36), LIME), steps=4, lean=0.12)             # the dorsal fin
    for (x, y, z) in ((-0.15, 0.1, 0.16), (0.05, -0.02, -0.165), (-0.25, 0.0, 0.15)):                           # old wounds
        a, b = sorted((z, z + (0.008 if z > 0 else -0.008))); p.add(x, y, a, x + 0.1, y + 0.02, b, solid((120, 40, 40), 2))
    barnacles(p, [(-0.1, 0.16, 0.06), (0.1, 0.15, -0.08)], 0.025)
    side_bone(p, "fin", (0.08, -0.1, 0.16), [((0.0, -0.13, 0.16, 0.16, -0.09, 0.36), finp((60, 70, 36), LIME))], True)
    p.bone("tail", (-0.3, 0, 0))
    p.add(-0.56, -0.1, -0.1, -0.28, 0.1, 0.1, sk)
    p.add(-0.8, 0.0, -0.015, -0.54, 0.34, 0.015, finp((60, 70, 36), LIME)); p.add(-0.76, -0.26, -0.015, -0.54, 0.0, 0.015, finp((60, 70, 36), LIME))
    p.bone(None)
    p.half = {"jaw"}
    p.anim("tail", "rot", (0, 20, 0), 1.0).anim("jaw", "rot", (26, 0, 0), 1.6).anim("finl", "rot", (0, 0, 12), 1.6).anim("finr", "rot", (0, 0, -12), 1.6)
    return p


def goblin():
    """GRUBNASH THE ACID GOBLIN: green and warty, huge ears, a long crooked nose, a gap-toothed grin, scrap armour,
    a backpack of bubbling flasks, one flask always in hand."""
    p = Part("goblin", 32, 512)
    sk = W.grad((110, 150, 60), (70, 104, 36), 4, (150, 190, 90), 0.08)
    scrap = rusty((130, 100, 70))
    leather = W.grad((96, 66, 40), (60, 40, 24), 3, (130, 90, 50), 0.05)
    # the body: short and stocky, a pot belly under a leather jerkin
    p.add(-0.08, -0.12, -0.13, 0.09, 0.14, 0.13, leather)
    rounded(p, -0.02, -0.1, -0.11, 0.12, 0.08, 0.11, sk, 0.04)                               # the pot belly
    p.add(-0.09, -0.14, -0.135, 0.1, -0.1, 0.135, solid((50, 34, 20), 3))                    # belt
    p.add(0.1, -0.135, -0.03, 0.105, -0.105, 0.03, W.glow((240, 210, 80)))                   # its buckle
    for (y, z) in ((-0.13, 0.08), (-0.13, -0.09)): p.add(0.07, y - 0.06, z - 0.025, 0.12, y, z + 0.025, W.glow((170, 255, 80)))   # flasks on the belt
    p.add(-0.09, 0.08, -0.15, 0.1, 0.17, 0.15, scrap)                                        # a scrap-metal collar
    for s in (-1, 1): p.add(-0.07, 0.12, s * 0.16 - 0.05, 0.08, 0.2, s * 0.16 + 0.05, scrap)   # mismatched pauldrons
    p.add(0.04, 0.2, 0.16, 0.06, 0.25, 0.2, solid((90, 90, 90), 2))                          # a spike on one
    # the backpack of flasks
    p.add(-0.2, -0.06, -0.11, -0.08, 0.16, 0.11, leather)
    for i, (y, z) in enumerate(((0.12, -0.06), (0.12, 0.05), (0.02, -0.07), (0.02, 0.04), (-0.04, 0.0))):
        p.add(-0.22, y, z - 0.03, -0.17, y + 0.08, z + 0.03, W.glow((170, 255, 80) if i % 2 == 0 else (240, 220, 70)))
        p.add(-0.215, y + 0.08, z - 0.01, -0.185, y + 0.1, z + 0.01, solid((120, 90, 50), 2))   # corks
    p.add(-0.21, 0.16, -0.01, -0.19, 0.3, 0.01, solid((80, 80, 80), 2))                      # a pipe sticking out, smoking
    p.bone("head", (0, 0.17, 0))
    p.add(-0.08, 0.17, -0.09, 0.09, 0.36, 0.09, sk)                                          # the big head
    for (x, y, z) in ((0.05, 0.33, 0.06), (-0.03, 0.35, -0.05), (0.09, 0.22, 0.05)): p.add(x - 0.015, y, z - 0.015, x + 0.015, y + 0.02, z + 0.015, solid((80, 110, 40), 2))   # warts
    face_eyes(p, 0.09, 0.27, 0.31, 0.04, 0.045, (250, 230, 90), (200, 30, 20), slit=True)
    for s in (-1, 1): p.add(0.07, 0.31, s * 0.05 - 0.03, 0.1, 0.325, s * 0.05 + 0.03, solid((50, 70, 24), 2))   # a heavy brow
    p.add(0.09, 0.23, -0.02, 0.2, 0.27, 0.02, sk)                                            # the long crooked nose...
    p.add(0.19, 0.2, -0.018, 0.22, 0.25, 0.018, sk)                                          # ...hooking down
    p.add(0.09, 0.185, -0.06, 0.095, 0.215, 0.06, MOUTH)                                     # the grin
    for z in (-0.045, -0.015, 0.03): p.add(0.094, 0.2, z - 0.01, 0.1, 0.215, z + 0.01, TEETH)
    p.add(-0.09, 0.34, -0.09, 0.09, 0.39, 0.09, scrap)                                       # a pot for a helmet
    p.add(-0.02, 0.38, -0.02, 0.02, 0.43, 0.02, scrap)
    for side, s in (("l", -1), ("r", 1)):                                                    # the huge ears
        a, b = sorted((s * 0.09, s * 0.26))
        p.add(-0.02, 0.26, a, 0.04, 0.32, b, sk)
        a2, b2 = sorted((s * 0.2, s * 0.3))
        p.add(-0.03, 0.3, a2, 0.03, 0.35, b2, sk)
        p.add(0.0, 0.27, s * 0.14 - 0.02, 0.041, 0.31, s * 0.14 + 0.02, solid((150, 80, 70), 2))
    p.add(0.06, 0.3, 0.17, 0.08, 0.32, 0.19, W.glow((240, 210, 80)))                         # a gold earring
    p.bone(None)
    _limbs(p, sk, leather, arm_w=0.045, leg_w=0.05, shoulder=0.13, hip=0.06, boots=solid((40, 30, 20), 3),
           hands=W.grad((90, 130, 50), (60, 90, 30), 2, (120, 160, 70), 0.1))
    into(p, "armr")                                                                          # the flask in his hand
    p.add(0.02, -0.2, 0.16, 0.09, -0.11, 0.22, W.glow((170, 255, 80)))
    p.add(0.04, -0.11, 0.18, 0.07, -0.08, 0.2, solid((120, 90, 50), 2))
    drips(p, 0.05, -0.2, 0.19, 2, 101, 0.02, 0.06)
    into(p, "arml")                                                                          # spiked bracer
    p.add(-0.05, 0.0, -0.23, 0.05, 0.08, -0.13, scrap)
    p.add(-0.01, 0.02, -0.25, 0.01, 0.06, -0.23, solid((90, 90, 90), 2))
    into(p, None)
    p.pivots["body"] = (0, -0.5, 0)
    return _walk(p, 28, 26, 0.9, (0, 14, 6)).anim("body", "pos", (0, 0.5, 0), 0.45)


RAIN_MOBS = [shambler, rustbones, acidmite, stormwraith, mauler, goblin]


def bubble_paint(c, alpha=95):
    """Clear film: mostly see-through, a brighter rim at the edges and a white highlight up top."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        for y in range(h):
            for x in range(w):
                edge = min(x, y, w - 1 - x, h - 1 - y) == 0
                col, a = np.array(c, float), alpha + (60 if edge else 0)
                if face == "up" and abs(x - w * 0.35) < w * 0.15 and abs(y - h * 0.35) < h * 0.15: col, a = np.array((255, 255, 255), float), 200
                reg[y, x, :3] = np.clip(col + rng.uniform(-6, 6), 0, 255)
                reg[y, x, 3] = min(255, a)
    return f


def bubble(name, c):
    """The Brine Scourge's giant bubbles (FaultlineBosses: faultline:wild/air_bubble, wild/pink_bubble): a rounded ball of film."""
    p = Part(name, 16, 64)
    paint = bubble_paint(c)
    for i in range(7):                                                                       # stacked slabs, widest in the middle
        y0 = -0.5 + i / 7
        y1 = y0 + 1 / 7
        r = math.sqrt(max(0.0, 0.25 - ((y0 + y1) / 2) ** 2)) + 0.03
        p.add(-r, y0, -r, r, y1, r, paint)
    return p

# --------------------------------------------------------------------------------------------- 20 more (Deep 1.6.0)
# Ten for the Sunlit Sea and ten for the Abyss, authored at 32 pixels a block from the start (like the detailed acid mobs).
SILVER = (196, 206, 218)
PHOTO = W.glow((120, 220, 255))


def sailfin():
    """A marlin with a sail for a back and a spear for a nose. Hit it and it runs you through."""
    p = Part("sailfin", 32, 256)
    sk = fish((30, 60, 130), SILVER, (90, 160, 230), 0.05)
    rounded(p, -0.42, -0.13, -0.11, 0.26, 0.13, 0.11, sk, 0.05)
    rounded(p, 0.24, -0.09, -0.085, 0.44, 0.1, 0.085, sk, 0.04)                                     # the head
    for i in range(5): pair(p, -0.3 + i * 0.13, -0.02, 0.11, -0.25 + i * 0.13, 0.12, 0.115, solid((110, 190, 250), 2))   # blue bars down the flank
    p.add(0.43, -0.02, -0.025, 0.95, 0.02, 0.025, solid((40, 50, 80), 2))                          # the spear
    p.add(0.43, -0.035, -0.035, 0.6, 0.03, 0.035, solid((50, 70, 110), 2))
    eyes(p, 0.33, 0.39, 0.0, 0.06, 0.085, (240, 240, 250), (20, 30, 60), depth=0.02)
    p.add(0.36, -0.07, -0.05, 0.44, -0.05, 0.05, MOUTH)
    p.add(-0.42, -0.135, -0.09, 0.4, -0.11, 0.09, solid((220, 228, 236), 3))                       # the silver belly
    p.bone("sail", (0.0, 0.13, 0))
    for i in range(8):                                                                               # the great sail, rays and all
        x = 0.18 - i * 0.07
        h = 0.42 - abs(i - 2.5) * 0.05
        p.add(x - 0.07, 0.13, -0.012, x, 0.13 + h, 0.012, W.membrane((40, 70, 160), (120, 170, 255)))
        p.add(x - 0.012, 0.13, -0.016, x, 0.15 + h, 0.016, solid((20, 30, 70), 2))
        if i % 2 == 0: p.add(x - 0.05, 0.1 + h * 0.6, -0.014, x - 0.02, 0.13 + h * 0.6, 0.014, PHOTO)   # sail spots
    p.bone(None)
    side_bone(p, "fin", (0.15, -0.08, 0.11), [((0.05, -0.1, 0.11, 0.22, -0.06, 0.34), finp((30, 50, 120), (110, 160, 240)))], True)
    p.bone("tail", (-0.42, 0, 0))
    p.add(-0.6, -0.06, -0.05, -0.4, 0.06, 0.05, sk)
    for i in range(4):                                                                               # the crescent fluke
        t = i / 3
        p.add(-0.66 - t * 0.12, 0.02 + t * 0.12, -0.015, -0.58 - t * 0.12, 0.08 + t * 0.2, 0.015, finp((30, 50, 120), (110, 160, 240)))
        p.add(-0.66 - t * 0.12, -0.08 - t * 0.2, -0.015, -0.58 - t * 0.12, -0.02 - t * 0.12, 0.015, finp((30, 50, 120), (110, 160, 240)))
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("tail", "rot", (0, 18, 0), 0.8).anim("sail", "rot", (0, 0, 6), 1.6).anim("finl", "rot", (0, 0, 14), 1.0).anim("finr", "rot", (0, 0, -14), 1.0)
    return p


def mantaglider():
    """A giant ray gliding near the surface, dark above, white below, two horns curled in front."""
    p = Part("mantaglider", 32, 512)
    top = W.grad((26, 34, 48), (14, 18, 28), 5, (200, 210, 230), 0.03)
    belly = W.grad((236, 238, 242), (206, 210, 218), 3, (60, 60, 70), 0.05)
    rounded(p, -0.36, -0.06, -0.24, 0.3, 0.09, 0.24, top, 0.05)
    p.add(-0.34, -0.075, -0.22, 0.28, -0.05, 0.22, belly)
    p.add(0.06, 0.09, -0.12, 0.26, 0.11, 0.12, solid((230, 232, 238), 3))                           # white shoulder marks
    for s in (-1, 1):
        a, b = sorted((s * 0.16, s * 0.26))
        p.bone("horn" + ("l" if s < 0 else "r"), (0.28, 0.0, s * 0.2))
        p.add(0.28, -0.08, a, 0.48, 0.04, b, top)                                                   # cephalic fins
        p.add(0.44, -0.1, a, 0.5, -0.02, b, solid((40, 46, 60), 3))
        p.bone(None)
    eyes(p, 0.24, 0.29, -0.01, 0.04, 0.245, (20, 20, 28), (120, 140, 170), depth=0.02)
    p.add(0.3, -0.06, -0.14, 0.33, -0.01, 0.14, solid((10, 10, 14), 2))                             # the wide mouth
    for i in range(5): p.add(-0.1 + i * 0.05, -0.08, -0.17, -0.07 + i * 0.05, -0.075, -0.13, solid((60, 60, 70), 2))   # gill slits
    for i in range(5): p.add(-0.1 + i * 0.05, -0.08, 0.13, -0.07 + i * 0.05, -0.075, 0.17, solid((60, 60, 70), 2))
    for side, s in (("l", -1), ("r", 1)):
        p.bone("w" + side, (0.0, 0.0, s * 0.24))
        for k, (x0, x1, z0, z1) in enumerate(((-0.3, 0.26, 0.24, 0.42), (-0.24, 0.18, 0.42, 0.62), (-0.18, 0.08, 0.62, 0.8), (-0.12, -0.02, 0.8, 0.95))):
            a, b = sorted((s * z0, s * z1))
            p.add(x0, -0.03 + k * 0.01, a, x1, 0.06 - k * 0.012, b, top)
            p.add(x0 + 0.02, -0.04 + k * 0.01, a, x1 - 0.02, -0.025 + k * 0.01, b, belly)
        for (x, z) in ((0.0, 0.5), (-0.08, 0.7), (0.05, 0.36)):
            a, b = sorted((s * z, s * (z + 0.05)))
            p.add(x, 0.06, a, x + 0.06, 0.065, b, solid((200, 210, 230), 2))                         # pale freckles
        p.bone(None)
    p.bone("tail", (-0.36, 0.0, 0))
    p.add(-1.05, -0.015, -0.015, -0.34, 0.015, 0.015, solid((20, 24, 34), 2))
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("wl", "rot", (0, 0, 26), 2.4).anim("wr", "rot", (0, 0, -26), 2.4).anim("tail", "rot", (0, 10, 4), 2.4, 0.3)
    p.anim("hornl", "rot", (0, 6, 0), 1.6).anim("hornr", "rot", (0, -6, 0), 1.6).anim("body", "pos", (0, 1.2, 0), 2.4, 0.25)
    return p


def leafydragon():
    """A leafy seadragon: a slender seahorse cousin dressed in leaves of kelp. It looks like a drifting weed."""
    p = Part("leafydragon", 32, 256)
    sk = W.bands((200, 150, 60), (150, 100, 40), 3)
    leaf = W.membrane((120, 160, 60), (190, 220, 100))
    p.add(-0.22, -0.04, -0.035, 0.12, 0.05, 0.035, sk)                                            # the body
    p.add(0.1, 0.0, -0.03, 0.2, 0.1, 0.03, sk)                                                    # the neck
    p.add(0.16, 0.06, -0.035, 0.26, 0.13, 0.035, sk)                                              # the head
    p.add(0.26, 0.08, -0.012, 0.42, 0.11, 0.012, sk)                                              # the long snout
    eyes(p, 0.19, 0.23, 0.09, 0.12, 0.035, (250, 220, 120), (30, 20, 10), depth=0.01)
    for (x, y, z, h, lw) in ((-0.15, 0.05, 0, 0.12, 0.06), (0.0, 0.05, 0, 0.1, 0.05), (0.14, 0.1, 0, 0.09, 0.04), (0.22, 0.13, 0, 0.07, 0.04)):
        p.add(x - lw / 2, y, -0.008, x + lw / 2, y + h, 0.008, leaf)                              # leaves on its back
        p.add(x - lw * 0.7, y + h * 0.6, -0.008, x + lw * 0.7, y + h, 0.008, leaf)
    p.bone("leaves", (0.0, -0.04, 0))
    for (x, z, L) in ((-0.15, 0.06, 0.16), (-0.05, -0.06, 0.14), (0.06, 0.06, 0.12), (-0.1, -0.05, 0.1), (0.15, 0.04, 0.08)):
        a, b = sorted((z, z + (0.12 if z > 0 else -0.12)))
        p.add(x - 0.03, -0.04 - L, a, x + 0.03, -0.03, b, leaf)                                    # leaves hanging below
    p.bone(None)
    p.bone("tail", (-0.22, 0.0, 0))
    pts = [(-0.22, 0.0, 0.0), (-0.36, -0.04, 0.0), (-0.46, -0.14, 0.0), (-0.44, -0.24, 0.0), (-0.36, -0.26, 0.0), (-0.33, -0.2, 0.0)]
    W.tube(p, pts, 0.03, 0.012, sk)                                                                # the curled tail
    p.add(-0.42, -0.22, -0.06, -0.36, -0.16, 0.06, leaf)
    p.bone(None)
    p.bone("fin", (-0.05, 0.05, 0)); p.add(-0.08, 0.05, -0.006, 0.0, 0.1, 0.006, W.glow((230, 240, 200))); p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("leaves", "rot", (6, 0, 8), 2.6).anim("tail", "rot", (8, 0, 0), 2.2).anim("fin", "rot", (0, 24, 0), 0.8).anim("body", "pos", (0, 0.8, 0), 3.0)
    return p


def pearlhorse():
    """A pearly seahorse with a curled tail and a fluttering fin; it carries a pearl against its chest."""
    p = Part("pearlhorse", 32, 256)
    sk = W.bands((240, 220, 236), (200, 170, 210), 2)
    p.add(-0.08, -0.12, -0.07, 0.08, 0.2, 0.07, sk)                                               # the chest
    p.add(-0.05, -0.12, -0.075, 0.07, 0.18, -0.07, W.bands((255, 240, 250), (220, 190, 230), 2))
    p.add(-0.04, 0.18, -0.06, 0.08, 0.3, 0.06, sk)                                                # the neck
    p.add(0.0, 0.26, -0.065, 0.16, 0.38, 0.065, sk)                                               # the head
    p.add(0.16, 0.28, -0.025, 0.3, 0.33, 0.025, sk)                                               # the snout
    eyes(p, 0.06, 0.11, 0.31, 0.36, 0.065, (60, 40, 80), (250, 230, 255), depth=0.01)
    for i in range(4): p.add(-0.06 + i * 0.03, 0.38, -0.01, -0.04 + i * 0.03, 0.44 - i * 0.012, 0.01, solid((230, 190, 230), 2))   # the coronet
    for y in (0.15, 0.08, 0.0, -0.08): p.add(-0.1, y, -0.012, -0.08, y + 0.03, 0.012, solid((210, 170, 220), 2))   # back ridges
    p.add(0.07, 0.0, -0.035, 0.12, 0.07, 0.035, W.glow((255, 250, 245)))                           # the pearl
    p.bone("tail", (0.0, -0.12, 0))
    W.tube(p, [(0.0, -0.12, 0.0), (-0.02, -0.26, 0.0), (0.04, -0.38, 0.0), (0.12, -0.38, 0.0), (0.14, -0.3, 0.0), (0.08, -0.27, 0.0)], 0.05, 0.015, sk)
    p.bone(None)
    p.bone("fin", (-0.08, 0.08, 0)); p.add(-0.18, 0.02, -0.006, -0.08, 0.16, 0.006, W.membrane((250, 220, 250), (255, 255, 255))); p.bone(None)
    side_bone(p, "pec", (0.02, 0.28, 0.065), [((-0.02, 0.26, 0.065, 0.04, 0.32, 0.12), W.membrane((250, 220, 250), (255, 255, 255)))], True)
    p.pivots["body"] = (0, 0, 0)
    p.anim("fin", "rot", (0, 30, 0), 0.8).anim("pecl", "rot", (0, 0, 24), 0.8).anim("pecr", "rot", (0, 0, -24), 0.8)
    p.anim("tail", "rot", (10, 0, 0), 2.4).anim("body", "pos", (0, 1.0, 0), 2.4)
    return p


def hammerjaw():
    """A hammerhead: eyes on the ends of a wide flat head, scars, a dorsal like a blade. It rams."""
    p = shark("hammerjaw", fish((110, 112, 96), (226, 224, 210), (80, 80, 66), 0.04), finp((90, 90, 76), (150, 150, 130)), (230, 228, 214), dorsal=0.44)
    p.ppb = 32
    hd = fish((110, 112, 96), (226, 224, 210))
    p.add(0.5, -0.02, -0.34, 0.62, 0.07, 0.34, hd)                                                 # the hammer
    p.add(0.48, 0.0, -0.3, 0.52, 0.06, 0.3, hd)
    for s in (-1, 1):
        a, b = sorted((s * 0.34, s * 0.36))
        p.add(0.53, 0.0, a, 0.6, 0.06, b, W.eye_paint((240, 220, 60), (240, 200, 40), (10, 10, 10), True))
        c0, c1 = sorted((s * 0.08, s * 0.3))
        p.add(0.615, 0.01, c0, 0.625, 0.03, c1, solid((60, 60, 50), 2))                            # sensory pores line
    for (x, z) in ((-0.2, 0.161), (0.0, -0.162), (0.1, 0.161)):
        a, b = sorted((z, z + (0.006 if z > 0 else -0.006)))
        p.add(x, -0.02, a, x + 0.12, 0.0, b, solid((200, 196, 180), 2))                            # old scars
    return p


def razorcuda():
    """A barracuda: long, silver, barred, an underbite full of fangs. They hunt in threes."""
    p = Part("razorcuda", 32, 256)
    sk = fish((70, 90, 100), (230, 236, 240), (40, 50, 60), 0.03)
    rounded(p, -0.4, -0.07, -0.06, 0.26, 0.07, 0.06, sk, 0.025)
    for i in range(7): pair(p, -0.36 + i * 0.09, 0.0, 0.06, -0.33 + i * 0.09, 0.07, 0.064, solid((30, 40, 50), 2))   # dark bars
    p.add(0.24, -0.05, -0.05, 0.46, 0.05, 0.05, sk)                                               # the long head
    eyes(p, 0.3, 0.35, 0.0, 0.045, 0.05, (250, 240, 200), (10, 10, 10), depth=0.015)
    for i in range(5): p.add(0.3 + i * 0.03, -0.035, -0.04, 0.312 + i * 0.03, 0.0, 0.04, TEETH)
    p.bone("jaw", (0.26, -0.04, 0))
    p.add(0.26, -0.075, -0.045, 0.5, -0.035, 0.045, sk)
    for i in range(6): p.add(0.3 + i * 0.033, -0.035, -0.04, 0.312 + i * 0.033, 0.02, 0.04, TEETH)
    p.bone(None)
    staircase(p, -0.12, 0.0, 0.07, 0.1, -0.01, 0.01, finp((60, 70, 80), (150, 160, 170)), 3)
    staircase(p, -0.3, -0.2, 0.07, 0.07, -0.01, 0.01, finp((60, 70, 80), (150, 160, 170)), 2)
    side_bone(p, "fin", (0.12, -0.05, 0.06), [((0.06, -0.07, 0.06, 0.18, -0.04, 0.16), finp((60, 70, 80), (150, 160, 170)))], True)
    p.bone("tail", (-0.4, 0, 0))
    p.add(-0.5, -0.04, -0.035, -0.38, 0.04, 0.035, sk)
    staircase(p, -0.66, -0.5, 0.0, 0.16, -0.012, 0.012, finp((50, 60, 70), (150, 160, 170)), 3, 0.06)
    p.add(-0.62, -0.16, -0.012, -0.5, 0.0, 0.012, finp((50, 60, 70), (150, 160, 170)))
    p.bone(None)
    p.half = {"jaw"}
    p.pivots["body"] = (0, 0, 0)
    p.anim("tail", "rot", (0, 22, 0), 0.8).anim("jaw", "rot", (14, 0, 0), 1.6).anim("finl", "rot", (0, 0, 16), 0.8).anim("finr", "rot", (0, 0, -16), 0.8)
    return p


def stingray():
    """A sand-brown stingray that lies half-buried; its tail ends in a venomous barb."""
    p = Part("stingray", 32, 256)
    top = spotted((150, 120, 80), (90, 66, 40), 0.15)
    belly = solid((236, 226, 210), 4)
    for (x0, x1, z) in ((-0.3, 0.3, 0.2), (-0.26, 0.26, 0.3), (-0.2, 0.18, 0.38), (-0.12, 0.1, 0.44)):
        p.add(x0, -0.04, -z, x1, 0.04, z, top)                                                    # the round disc, in steps
        p.add(x0 + 0.02, -0.05, -z + 0.02, x1 - 0.02, -0.04, z - 0.02, belly)
    p.add(-0.18, 0.04, -0.12, 0.16, 0.08, 0.12, top)                                              # the raised middle
    for s in (-1, 1):
        a, b = sorted((s * 0.05, s * 0.1))
        p.add(0.06, 0.07, a, 0.12, 0.11, b, W.eye_paint((200, 180, 120), (60, 40, 20), (10, 10, 10)))   # eyes on top
        a2, b2 = sorted((s * 0.05, s * 0.09))
        p.add(-0.02, 0.07, a2, 0.03, 0.085, b2, solid((50, 36, 20), 2))                            # spiracles
    p.bone("tail", (-0.3, 0.0, 0))
    p.add(-0.85, -0.012, -0.012, -0.28, 0.012, 0.012, solid((110, 86, 56), 2))
    p.add(-0.62, 0.012, -0.008, -0.5, 0.04, 0.008, solid((240, 230, 200), 2))                     # the barb
    p.add(-0.6, 0.035, -0.004, -0.5, 0.05, 0.004, W.glow((200, 80, 200)))                         # venom on it
    p.bone(None)
    for side, s in (("l", -1), ("r", 1)):
        p.bone("w" + side, (0.0, 0.0, s * 0.3))
        a, b = sorted((s * 0.3, s * 0.5))
        p.add(-0.14, -0.03, a, 0.12, 0.03, b, top)
        p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("wl", "rot", (0, 0, 16), 1.4).anim("wr", "rot", (0, 0, -16), 1.4).anim("tail", "rot", (0, 14, 6), 1.6)
    return p


def mirrorfin():
    """A tall, flat fish as bright as a mirror. Hurt it and the flash blinds you."""
    p = Part("mirrorfin", 32, 256)
    chrome = W.grad((240, 246, 252), (170, 184, 200), 6, (255, 255, 255), 0.08)
    for (x0, x1, y) in ((-0.2, 0.2, 0.3), (-0.26, 0.24, 0.22), (-0.3, 0.26, 0.12)):
        p.add(x0, -y, -0.05, x1, y, 0.05, chrome)                                                 # the tall disc body
    p.add(-0.24, -0.02, -0.055, 0.24, 0.02, 0.055, W.glow((180, 220, 255)))                       # the bright lateral stripe
    eyes(p, 0.15, 0.22, 0.06, 0.13, 0.05, (250, 250, 255), (20, 40, 80), depth=0.015)
    p.add(0.25, -0.04, -0.02, 0.3, 0.0, 0.02, MOUTH)
    p.bone("fint", (0.0, 0.3, 0)); staircase(p, -0.22, 0.12, 0.28, 0.16, -0.008, 0.008, finp((200, 220, 240), (255, 255, 255)), 4, 0.08); p.bone(None)
    p.bone("finb", (0.0, -0.3, 0)); p.add(-0.2, -0.42, -0.008, 0.06, -0.28, 0.008, finp((200, 220, 240), (255, 255, 255))); p.bone(None)
    side_bone(p, "fin", (0.08, -0.04, 0.05), [((0.02, -0.08, 0.05, 0.14, 0.0, 0.14), finp((200, 220, 240), (255, 255, 255)))], True)
    p.bone("tail", (-0.3, 0, 0))
    p.add(-0.36, -0.06, -0.03, -0.28, 0.06, 0.03, chrome)
    p.add(-0.52, -0.2, -0.01, -0.34, 0.2, 0.01, finp((200, 220, 240), (255, 255, 255)))
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("tail", "rot", (0, 24, 0), 0.8).anim("fint", "rot", (0, 10, 0), 1.0).anim("finb", "rot", (0, -10, 0), 1.0).anim("finl", "rot", (0, 0, 22), 0.8).anim("finr", "rot", (0, 0, -22), 0.8)
    return p


def kelpstrangler():
    """A tall stalk of kelp with eyes. Its fronds wrap round you and pull you down."""
    p = Part("kelpstrangler", 32, 256)
    st = W.bands((70, 110, 40), (50, 80, 30), 3)
    leaf = W.membrane((90, 140, 50), (150, 200, 80))
    p.add(-0.06, -0.5, -0.06, 0.06, 0.4, 0.06, st)                                                # the stalk
    p.add(-0.12, -0.5, -0.12, 0.12, -0.42, 0.12, solid((60, 50, 30), 3))                          # the holdfast
    for (x, z) in ((0.1, 0.0), (-0.1, 0.0), (0.0, 0.1), (0.0, -0.1)): p.add(x - 0.03, -0.52, z - 0.03, x + 0.03, -0.46, z + 0.03, solid((50, 40, 24), 2))
    p.add(-0.1, 0.32, -0.1, 0.1, 0.5, 0.1, st)                                                    # the bulb of a head
    p.add(0.1, 0.36, -0.08, 0.11, 0.46, 0.08, solid((20, 30, 10), 2))
    face_eyes(p, 0.1, 0.4, 0.46, 0.045, 0.05, (250, 200, 60), (200, 120, 10), slit=True)
    p.add(0.1, 0.33, -0.05, 0.112, 0.36, 0.05, MOUTH)
    for side, s, y in (("al", -1, 0.25), ("ar", 1, 0.1), ("bl", -1, -0.05), ("br", 1, -0.2)):
        p.bone(side, (0.0, y, s * 0.06))
        pts = [(0.0, y, s * 0.06), (0.08, y + 0.05, s * 0.2), (0.2, y + 0.02, s * 0.32), (0.32, y - 0.06, s * 0.38)]
        W.tube(p, pts, 0.03, 0.014, st)
        for (q, L) in ((pts[1], 0.1), (pts[2], 0.08)):
            p.add(q[0] - 0.05, q[1] - 0.008, q[2] - 0.03, q[0] + 0.05, q[1] + 0.008, q[2] + 0.03, leaf)
        p.add(pts[3][0] - 0.03, pts[3][1] - 0.03, pts[3][2] - 0.03, pts[3][0] + 0.03, pts[3][1] + 0.03, pts[3][2] + 0.03, W.glow((200, 255, 120)))   # a sticky tip
        p.bone(None)
    p.pivots["body"] = (0, -0.5, 0)
    p.anim("al", "rot", (10, 14, 10), 2.0).anim("ar", "rot", (10, -14, -10), 2.0, 0.3).anim("bl", "rot", (10, 14, 10), 2.0, 0.6).anim("br", "rot", (10, -14, -10), 2.0, 0.9)
    p.anim("body", "rot", (4, 0, 6), 3.0)
    return p


def siren():
    """A sea siren: a woman to the waist, a fish below, long sea-green hair, a voice that pulls you in."""
    p = Part("siren", 32, 256)
    skn = W.skin((150, 196, 200), (120, 170, 190))
    hr = W.hair((40, 130, 110), (90, 200, 170))
    tl = W.scales((40, 110, 130), (20, 60, 80))
    p.add(-0.07, 0.0, -0.11, 0.07, 0.24, 0.11, skn)                                               # the torso
    p.add(0.065, 0.13, -0.09, 0.075, 0.2, 0.09, W.grad((200, 230, 240), (150, 190, 210), 3, (255, 255, 255), 0.2))   # a shell top
    p.add(-0.08, 0.24, -0.08, 0.08, 0.42, 0.08, skn)                                              # the head
    face_eyes(p, 0.08, 0.32, 0.36, 0.035, 0.035, (240, 250, 255), (240, 200, 60), slit=True)
    p.add(0.08, 0.28, -0.02, 0.085, 0.295, 0.02, solid((40, 90, 110), 2))                         # the mouth
    p.bone("hair", (-0.02, 0.42, 0))
    p.add(-0.1, 0.36, -0.1, 0.05, 0.44, 0.1, hr)
    p.add(-0.12, 0.02, -0.09, -0.06, 0.42, 0.09, hr)                                              # long hair down her back
    for s in (-1, 1): p.add(-0.08, 0.12, s * 0.085 - 0.02, 0.02, 0.4, s * 0.085 + 0.02, hr)
    for s in (-1, 1): p.add(-0.02, 0.38, s * 0.08, 0.02, 0.44, s * 0.11, W.membrane((120, 210, 200), (200, 255, 240)))   # fin ears
    p.bone(None)
    for side, s in (("l", -1), ("r", 1)):
        p.bone("arm" + side, (0.0, 0.22, s * 0.11))
        a, b = sorted((s * 0.11, s * 0.16))
        p.add(-0.025, -0.02, a, 0.025, 0.22, b, skn)
        p.add(-0.02, -0.06, a, 0.04, -0.02, b, skn)
        p.add(0.0, -0.08, a, 0.05, -0.06, b, solid((40, 90, 100), 2))                            # webbed claws
        p.bone(None)
    p.bone("tail", (0.0, 0.0, 0))
    pts = [(0.0, 0.0, 0.0), (-0.02, -0.15, 0.0), (-0.08, -0.3, 0.0), (-0.18, -0.4, 0.0), (-0.3, -0.42, 0.0)]
    W.tube(p, pts, 0.1, 0.04, tl)
    p.add(-0.46, -0.48, -0.1, -0.3, -0.36, 0.1, finp((60, 150, 160), (150, 230, 220)))           # the tail fin
    for (x, y) in ((-0.02, -0.08), (-0.06, -0.22)): p.add(x - 0.03, y, -0.1, x + 0.03, y + 0.03, 0.1, W.membrane((60, 150, 160), (150, 230, 220)))   # side frills
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("tail", "rot", (12, 0, 0), 1.6).anim("hair", "rot", (6, 0, 4), 2.2).anim("arml", "rot", (16, 0, 10), 2.0).anim("armr", "rot", (16, 0, -10), 2.0, 0.5)
    p.anim("body", "pos", (0, 0.8, 0), 2.0)
    return p


# ---- the Abyss
def gulper():
    """A gulper eel: a whip of a body behind a mouth that can swallow something its own size. Its tail tip glows."""
    p = Part("gulper", 32, 256)
    sk = W.grad((26, 22, 30), (12, 10, 16), 4, (70, 60, 90), 0.04)
    p.add(0.1, -0.05, -0.06, 0.26, 0.08, 0.06, sk)                                                # the head
    eyes(p, 0.2, 0.23, 0.04, 0.07, 0.06, (40, 40, 60), (120, 160, 255), depth=0.01)
    p.add(0.24, 0.04, -0.2, 0.52, 0.06, 0.2, sk)                                                  # the upper jaw: a wide hood
    p.add(0.26, 0.02, -0.18, 0.5, 0.04, 0.18, solid((60, 20, 30), 2))
    for z in (-0.16, -0.08, 0.0, 0.08, 0.16): p.add(0.48, 0.0, z - 0.008, 0.5, 0.04, z + 0.008, TEETH)
    p.bone("jaw", (0.22, -0.04, 0))
    for (x0, x1, z) in ((0.22, 0.52, 0.2), (0.26, 0.48, 0.24)):
        p.add(x0, -0.42, -z, x1, -0.4, z, W.membrane((40, 30, 44), (90, 60, 100)))               # the huge pouch of a lower jaw
    for s in (-1, 1):
        a, b = sorted((s * 0.2, s * 0.22))
        p.add(0.22, -0.42, a, 0.52, -0.04, b, W.membrane((40, 30, 44), (90, 60, 100)))
    p.add(0.5, -0.42, -0.2, 0.52, -0.04, 0.2, W.membrane((40, 30, 44), (90, 60, 100)))
    p.add(0.24, -0.4, -0.18, 0.5, -0.38, 0.18, solid((60, 20, 30), 2))
    p.bone(None)
    p.add(-0.06, -0.04, -0.045, 0.12, 0.05, 0.045, sk)
    p.bone("s1", (-0.06, 0, 0)); p.add(-0.3, -0.035, -0.035, -0.04, 0.04, 0.035, sk); p.bone(None)
    p.bone("s2", (-0.3, 0, 0)); p.add(-0.56, -0.025, -0.025, -0.28, 0.03, 0.025, sk); p.bone(None)
    p.bone("s3", (-0.56, 0, 0))
    p.add(-0.9, -0.012, -0.012, -0.54, 0.015, 0.012, sk)
    p.add(-0.98, -0.03, -0.03, -0.9, 0.03, 0.03, W.glow((255, 90, 140)))                          # the glowing tail tip
    p.bone(None)
    p.half = {"jaw"}
    p.anim("jaw", "rot", (26, 0, 0), 2.6).anim("s1", "rot", (0, 10, 0), 1.2).anim("s2", "rot", (0, 16, 0), 1.2, 0.15).anim("s3", "rot", (0, 26, 0), 1.2, 0.3)
    return p


def isopod():
    """A giant isopod: a woodlouse the size of a dog, plated in grey armour. Strike it and it rolls up."""
    p = Part("isopod", 32, 256)
    sh = W.grad((190, 186, 176), (130, 124, 116), 4, (220, 216, 206), 0.06)
    p.add(0.22, -0.06, -0.15, 0.34, 0.06, 0.15, sh)                                               # the head
    for s in (-1, 1):
        a, b = sorted((s * 0.09, s * 0.155))
        p.add(0.26, -0.01, a, 0.32, 0.05, b, W.grad((40, 40, 46), (20, 20, 24), 2, (90, 90, 110), 0.5))   # compound eyes
    p.add(0.34, -0.05, -0.05, 0.36, -0.01, 0.05, solid((80, 70, 60), 2))
    p.bone("ant", (0.34, 0.0, 0))
    for s in (-1, 1): W.tube(p, [(0.34, 0.0, s * 0.06), (0.46, 0.04, s * 0.14), (0.58, 0.02, s * 0.24)], 0.012, 0.006, solid((150, 140, 130), 2))
    p.bone(None)
    for i in range(7):                                                                             # the plates
        x = 0.22 - (i + 1) * 0.065
        w = 0.19 - abs(i - 2) * 0.012
        p.add(x, -0.06, -w, x + 0.06, 0.08 + (0.02 if i in (2, 3) else 0), w, sh)
        p.add(x + 0.05, 0.03, -w, x + 0.065, 0.09, w, solid((110, 104, 96), 2))                    # the seam
    p.add(-0.34, -0.05, -0.12, -0.24, 0.05, 0.12, sh)                                             # the tail fan
    for s in (-1, 1): p.add(-0.42, -0.03, s * 0.08 - 0.02, -0.32, 0.0, s * 0.08 + 0.02, sh)
    legs = [((x - 0.012, -0.12, 0.15, x + 0.012, -0.04, 0.24), solid((170, 160, 150), 2)) for x in (0.18, 0.1, 0.02, -0.06, -0.14, -0.22)]
    side_bone(p, "legs", (0.0, -0.06, 0.15), legs, True)
    p.anim("legsl", "rot", (0, 0, 14), 0.5).anim("legsr", "rot", (0, 0, -14), 0.5, 0.5).anim("ant", "rot", (0, 12, 6), 1.4)
    return p


def ghostshark():
    """A ghost shark (a chimaera): pale, half see-through, huge glassy eyes, a rat's tail. You only see it up close."""
    p = Part("ghostshark", 32, 256)
    sk = W.grad((200, 210, 230), (140, 150, 180), 5, (240, 245, 255), 0.06)
    rounded(p, -0.3, -0.11, -0.1, 0.22, 0.12, 0.1, sk, 0.04)
    rounded(p, 0.2, -0.08, -0.08, 0.4, 0.1, 0.08, sk, 0.03)                                       # the blunt head
    eyes(p, 0.26, 0.36, 0.0, 0.08, 0.08, (60, 230, 200), (20, 120, 110), depth=0.02)            # huge glassy eyes
    p.add(0.38, -0.08, -0.03, 0.41, -0.04, 0.03, TEETH)                                           # rabbit teeth plates
    for i in range(6): pair(p, 0.38 - i * 0.04, 0.02 + (i % 2) * 0.02, 0.081, 0.39 - i * 0.04, 0.04 + (i % 2) * 0.02, 0.083, PHOTO)   # sensory lines
    p.add(-0.05, 0.12, -0.01, 0.0, 0.32, 0.01, solid((240, 240, 250), 2))                         # the venom spine
    staircase(p, -0.14, 0.04, 0.12, 0.14, -0.01, 0.01, finp((170, 180, 210), (230, 235, 250)), 3)
    side_bone(p, "fin", (0.1, -0.06, 0.1), [((-0.02, -0.08, 0.1, 0.22, -0.04, 0.42), finp((170, 180, 210), (230, 235, 250)))], True)   # big wing-like pectorals
    p.bone("tail", (-0.3, 0, 0))
    p.add(-0.95, -0.012, -0.012, -0.28, 0.012, 0.012, sk)
    p.add(-0.7, -0.04, -0.006, -0.36, 0.04, 0.006, finp((170, 180, 210), (230, 235, 250)))
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("finl", "rot", (0, 0, 22), 1.6).anim("finr", "rot", (0, 0, -22), 1.6).anim("tail", "rot", (0, 14, 0), 1.6, 0.3)
    return p


def vampsquid():
    """The vampire squid: a red-black cloak of webbing between its arms, blue eyes, spines lining the cloak."""
    p = Part("vampsquid", 32, 256)
    sk = W.grad((120, 20, 30), (60, 8, 16), 4, (200, 60, 70), 0.04)
    rounded(p, -0.3, -0.12, -0.12, 0.06, 0.12, 0.12, sk, 0.05)                                    # the mantle
    side_bone(p, "ear", (-0.22, 0.0, 0.12), [((-0.3, -0.02, 0.12, -0.16, 0.02, 0.24), W.membrane((140, 30, 40), (200, 70, 80)))], True)   # ear fins
    for s in (-1, 1):
        a, b = sorted((s * 0.12, s * 0.135))
        p.add(-0.02, 0.0, a, 0.06, 0.08, b, W.eye_paint((80, 160, 255), (40, 120, 255), (10, 10, 20)))   # big blue eyes
    for (x, y, z) in ((-0.25, 0.06, 0.1), (-0.25, 0.06, -0.1), (-0.15, -0.1, 0.0)): p.add(x - 0.015, y, z - 0.015, x + 0.015, y + 0.02, z + 0.015, PHOTO)   # photophores
    p.bone("cloak", (0.06, 0, 0))
    for i in range(8):
        a = i * math.pi / 4
        y, z = math.sin(a) * 0.1, math.cos(a) * 0.1
        p.add(0.06, y - 0.02, z - 0.02, 0.42, y + 0.02, z + 0.02, sk)                              # arms
        for k in range(3): p.add(0.12 + k * 0.1, y * 1.15 - 0.012, z * 1.15 - 0.012, 0.15 + k * 0.1, y * 1.15 + 0.012, z * 1.15 + 0.012, solid((240, 220, 220), 2))   # cirri spines
        a2 = a + math.pi / 8
        y2, z2 = math.sin(a2) * 0.13, math.cos(a2) * 0.13
        p.add(0.06, y2 - 0.035, z2 - 0.035, 0.36, y2 + 0.035, z2 + 0.035, W.membrane((40, 6, 14), (110, 20, 30)))   # the webbing between
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("cloak", "rot", (0, 8, 8), 1.8).anim("earl", "rot", (0, 0, 26), 0.9).anim("earr", "rot", (0, 0, -26), 0.9).anim("body", "pos", (0, 0.8, 0), 1.8, 0.5)
    return p


def dumbo():
    """A dumbo octopus: a soft glowing bell with ear fins it flaps to swim. Its light keeps the dark away."""
    p = Part("dumbo", 32, 256)
    sk = W.glow((240, 170, 140))
    rounded(p, -0.15, -0.05, -0.15, 0.15, 0.22, 0.15, sk, 0.06)                                   # the bell
    rounded(p, -0.1, 0.2, -0.1, 0.1, 0.28, 0.1, sk, 0.04)
    for s in (-1, 1):
        a, b = sorted((s * 0.15, s * 0.16))
        p.add(0.02, 0.06, a, 0.1, 0.14, b, W.eye_paint((30, 20, 20), (60, 30, 30), (10, 10, 10)))
    for (x, z) in ((0.1, 0.1), (-0.1, -0.1), (0.1, -0.1), (-0.1, 0.1)): p.add(x - 0.02, 0.24, z - 0.02, x + 0.02, 0.26, z + 0.02, W.glow((255, 230, 200)))
    side_bone(p, "ear", (0.0, 0.2, 0.12), [((-0.08, 0.17, 0.12, 0.06, 0.21, 0.3), W.glow((250, 190, 160)))], True)
    p.bone("skirt", (0.0, -0.05, 0))
    for i in range(8):
        a = i * math.pi / 4
        x, z = math.cos(a) * 0.13, math.sin(a) * 0.13
        p.add(x - 0.035, -0.22, z - 0.035, x + 0.035, -0.04, z + 0.035, W.glow((230, 150, 120)))  # webbed arms
        p.add(x * 1.3 - 0.02, -0.26, z * 1.3 - 0.02, x * 1.3 + 0.02, -0.2, z * 1.3 + 0.02, W.glow((255, 210, 180)))
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("earl", "rot", (0, 0, 30), 1.0).anim("earr", "rot", (0, 0, -30), 1.0).anim("skirt", "pos", (0, 0.6, 0), 1.0).anim("body", "pos", (0, 1.0, 0), 2.0)
    return p


def swallower():
    """A black swallower: a tiny fish with a stomach that stretches round prey bigger than itself (you can see it glowing inside)."""
    p = Part("swallower", 32, 256)
    sk = W.grad((30, 26, 34), (14, 12, 18), 4, (60, 50, 80), 0.03)
    p.add(0.04, -0.05, -0.05, 0.3, 0.06, 0.05, sk)                                                # the slim front
    eyes(p, 0.22, 0.25, 0.01, 0.04, 0.05, (60, 60, 80), (200, 200, 255), depth=0.01)
    for i in range(4): p.add(0.23 + i * 0.02, -0.035, -0.04, 0.24 + i * 0.02, 0.0, 0.04, TEETH)
    p.bone("jaw", (0.2, -0.04, 0))
    p.add(0.2, -0.07, -0.045, 0.34, -0.04, 0.045, sk)
    for i in range(5): p.add(0.23 + i * 0.022, -0.04, -0.04, 0.24 + i * 0.022, -0.01, 0.04, TEETH)
    p.bone(None)
    p.bone("gut", (-0.04, -0.04, 0))
    rounded(p, -0.22, -0.3, -0.13, 0.08, -0.03, 0.13, W.membrane((60, 40, 60), (110, 70, 100)), 0.05)   # the stretched stomach
    p.add(-0.18, -0.24, -0.06, 0.04, -0.1, 0.06, W.glow((120, 220, 255)))                         # the swallowed fish glowing through
    p.bone(None)
    p.bone("tail", (-0.1, 0, 0))
    p.add(-0.3, -0.03, -0.03, -0.08, 0.04, 0.03, sk)
    p.add(-0.44, -0.1, -0.01, -0.28, 0.1, 0.01, finp((40, 30, 50), (90, 70, 110)))
    p.bone(None)
    p.half = {"jaw"}
    p.anim("jaw", "rot", (20, 0, 0), 2.0).anim("tail", "rot", (0, 24, 0), 0.8).anim("gut", "pos", (0, -0.4, 0), 1.6)
    return p


def chainlight():
    """A siphonophore: a colony grown into one long chain of glowing bells, trailing threads that sting."""
    p = Part("chainlight", 32, 256)
    bell = W.glow((140, 200, 255))
    thread = solid((180, 220, 255), 2)
    def unit(x, r):
        rounded(p, x - 0.07, -r, -r, x + 0.07, r, r, bell, r * 0.35)
        p.add(x - 0.02, -r - 0.18, -0.008, x + 0.02 - 0.03, -r, 0.008, thread)
        p.add(x - 0.03, -r - 0.21, -0.02, x + 0.01, -r - 0.17, 0.02, W.glow((230, 240, 255)))
    p.add(0.28, -0.05, -0.05, 0.38, 0.06, 0.05, W.glow((255, 220, 120)))                          # the float at the front
    unit(0.2, 0.07); unit(0.06, 0.065)
    p.bone("s1", (0.0, 0, 0)); unit(-0.08, 0.06); unit(-0.22, 0.06); p.bone(None)
    p.bone("s2", (-0.28, 0, 0)); unit(-0.36, 0.055); unit(-0.5, 0.05); p.bone(None)
    p.bone("s3", (-0.56, 0, 0)); unit(-0.64, 0.045); unit(-0.78, 0.04); p.add(-0.98, -0.006, -0.006, -0.82, 0.006, 0.006, thread); p.bone(None)
    p.add(-0.22, -0.006, -0.006, 0.28, 0.006, 0.006, thread)                                      # the stem through them
    p.pivots["body"] = (0, 0, 0)
    p.anim("s1", "rot", (0, 8, 4), 2.4).anim("s2", "rot", (0, 12, 6), 2.4, 0.2).anim("s3", "rot", (0, 18, 8), 2.4, 0.4).anim("body", "pos", (0, 0.6, 0), 3.0)
    return p


def viperfang():
    """A viperfish: fangs too long to fit in its mouth, rows of lights down its belly, a lure on its first spine."""
    p = Part("viperfang", 32, 256)
    sk = W.grad((40, 50, 70), (16, 20, 30), 4, (90, 140, 190), 0.05)
    rounded(p, -0.36, -0.07, -0.055, 0.2, 0.08, 0.055, sk, 0.025)
    p.add(0.18, -0.06, -0.06, 0.34, 0.08, 0.06, sk)
    eyes(p, 0.25, 0.3, 0.02, 0.07, 0.06, (220, 230, 255), (40, 120, 200), depth=0.015)
    for i in range(10): pair(p, 0.16 - i * 0.05, -0.065, 0.05, 0.18 - i * 0.05, -0.045, 0.058, PHOTO)   # belly lights
    for (x, L) in ((0.3, 0.16), (0.26, 0.12)):
        for s in (-1, 1): p.add(x, -0.05 - L, s * 0.03 - 0.008, x + 0.016, -0.02, s * 0.03 + 0.008, TEETH)   # upper fangs, hanging past the jaw
    p.bone("jaw", (0.18, -0.06, 0))
    p.add(0.18, -0.1, -0.055, 0.36, -0.06, 0.055, sk)
    for s in (-1, 1): p.add(0.34, -0.06, s * 0.035 - 0.008, 0.356, 0.12, s * 0.035 + 0.008, TEETH)  # the lower fangs reaching up over its eyes
    p.bone(None)
    p.bone("lure", (0.12, 0.08, 0))
    W.tube(p, [(0.12, 0.08, 0.0), (0.2, 0.24, 0.0), (0.34, 0.3, 0.0), (0.42, 0.24, 0.0)], 0.01, 0.006, solid((60, 70, 90), 2))
    p.add(0.4, 0.2, -0.025, 0.45, 0.25, 0.025, W.glow((180, 240, 255)))
    p.bone(None)
    p.bone("tail", (-0.36, 0, 0))
    p.add(-0.48, -0.04, -0.03, -0.34, 0.05, 0.03, sk)
    p.add(-0.62, -0.12, -0.008, -0.46, 0.13, 0.008, finp((30, 40, 60), (80, 120, 170)))
    p.bone(None)
    p.half = {"jaw"}
    p.pivots["body"] = (0, 0, 0)
    p.anim("jaw", "rot", (22, 0, 0), 2.4).anim("lure", "rot", (8, 0, 8), 1.8).anim("tail", "rot", (0, 22, 0), 0.8)
    return p


def hagfish():
    """A hagfish: a pink-grey eel with barbels round a sucking mouth. Grab it and it smothers you in slime."""
    p = Part("hagfish", 32, 256)
    sk = slick((170, 120, 120), (255, 220, 220))
    p.add(0.16, -0.05, -0.05, 0.32, 0.05, 0.05, sk)
    for (y, z) in ((0.02, 0.03), (0.02, -0.03), (-0.03, 0.04), (-0.03, -0.04)):
        p.add(0.32, y - 0.008, z - 0.008, 0.38, y + 0.008, z + 0.008, solid((200, 150, 150), 2))   # barbels
    p.add(0.32, -0.02, -0.02, 0.34, 0.01, 0.02, solid((80, 20, 30), 2))
    for i in range(12): pair(p, 0.14 - i * 0.07, -0.02, 0.048, 0.155 - i * 0.07, 0.0, 0.052, W.glow((230, 250, 255)))   # slime pores
    p.add(0.0, -0.045, -0.045, 0.16, 0.045, 0.045, sk)
    p.bone("s1", (0.0, 0, 0)); p.add(-0.26, -0.04, -0.04, 0.02, 0.04, 0.04, sk); p.bone(None)
    p.bone("s2", (-0.26, 0, 0)); p.add(-0.52, -0.035, -0.035, -0.24, 0.035, 0.035, sk); p.bone(None)
    p.bone("s3", (-0.52, 0, 0)); p.add(-0.74, -0.03, -0.03, -0.5, 0.03, 0.03, sk); p.add(-0.84, -0.06, -0.008, -0.7, 0.06, 0.008, finp((160, 110, 110), (220, 170, 170))); p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("s1", "rot", (0, 12, 0), 1.0).anim("s2", "rot", (0, 18, 0), 1.0, 0.15).anim("s3", "rot", (0, 24, 0), 1.0, 0.3)
    return p


def watcher():
    """The Abyss Watcher: a great eye drifting in the dark on a crown of tendrils. When it looks at you, you feel it."""
    p = Part("watcher", 32, 512)
    flesh = W.grad((50, 30, 50), (24, 12, 26), 5, (110, 50, 90), 0.05)
    rounded(p, -0.26, -0.26, -0.26, 0.22, 0.26, 0.26, flesh, 0.1)                                 # the eyeball's flesh
    p.add(0.2, -0.2, -0.2, 0.25, 0.2, 0.2, solid((230, 220, 210), 4))                             # the white
    p.add(0.245, -0.14, -0.14, 0.255, 0.14, 0.14, W.glow((220, 40, 60)))                          # the glowing iris
    p.add(0.252, -0.05, -0.02, 0.262, 0.05, 0.02, solid((8, 4, 10), 2))                           # the slit pupil
    for (y, z, L) in ((0.15, 0.1, 0.06), (-0.12, -0.15, 0.05), (0.05, -0.18, 0.04), (-0.17, 0.12, 0.05)):
        p.add(0.248, y, z, 0.252, y + 0.008, z + L * (1 if z < 0 else -1), solid((180, 30, 40), 2))   # veins
    p.bone("lid", (0.18, 0.2, 0))
    p.add(0.12, 0.18, -0.24, 0.27, 0.27, 0.24, flesh)                                             # the heavy lid
    p.bone(None)
    for i, nm in enumerate(("t1", "t2", "t3", "t4", "t5", "t6")):
        a = i * math.pi / 3 + 0.3
        y, z = math.sin(a) * 0.18, math.cos(a) * 0.18
        p.bone(nm, (-0.24, y, z))
        pts = [(-0.24, y, z), (-0.42, y * 1.4, z * 1.4), (-0.6, y * 1.2 - 0.06, z * 1.2), (-0.78, y * 0.8 - 0.14, z * 0.8)]
        W.tube(p, pts, 0.045, 0.015, flesh)
        q = pts[-1]
        p.add(q[0] - 0.025, q[1] - 0.025, q[2] - 0.025, q[0] + 0.025, q[1] + 0.025, q[2] + 0.025, W.glow((220, 60, 120)))
        p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.half = {"lid"}
    for i, nm in enumerate(("t1", "t2", "t3", "t4", "t5", "t6")): p.anim(nm, "rot", (10, 12, 10), 2.4, i / 6)
    p.anim("lid", "rot", (-16, 0, 0), 4.0).anim("body", "pos", (0, 1.2, 0), 3.0)
    return p


# ---- the Mermaid Den (1.7.0): its people
def mermaid():
    """A mermaid of the Den: coral-pink tail with a gold sheen, a pearl necklace, a scallop top, long rose-gold hair with a
    sea-star clip. Neutral: she sings when she's calm and fights when she isn't."""
    p = Part("mermaid", 32, 256)
    skn = W.skin((236, 200, 186), (226, 160, 160))
    hr = W.hair((214, 120, 110), (255, 196, 160))
    tl = W.scales((236, 110, 140), (150, 50, 100))
    gold = W.grad((255, 220, 120), (210, 160, 60), 3, (255, 255, 230), 0.2)
    pearl = W.grad((250, 248, 255), (210, 210, 235), 3, (255, 255, 255), 0.3)
    p.add(-0.065, 0.0, -0.1, 0.065, 0.24, 0.1, skn)                                               # the torso
    for s in (-1, 1): p.add(0.06, 0.13, s * 0.05 - 0.04, 0.078, 0.2, s * 0.05 + 0.04, W.grad((255, 170, 200), (220, 110, 170), 3, (255, 240, 250), 0.25))   # scallop top
    for z in (-0.06, -0.03, 0.0, 0.03, 0.06): p.add(0.06, 0.2, z - 0.011, 0.08, 0.222, z + 0.011, pearl)   # pearl necklace
    p.add(-0.075, 0.24, -0.075, 0.075, 0.41, 0.075, skn)                                         # the head
    face_eyes(p, 0.075, 0.31, 0.35, 0.033, 0.033, (250, 252, 255), (60, 170, 200))
    p.add(0.075, 0.275, -0.018, 0.08, 0.288, 0.018, solid((220, 90, 120), 2))                     # lips
    p.bone("hair", (-0.02, 0.41, 0))
    p.add(-0.09, 0.35, -0.09, 0.06, 0.43, 0.09, hr)
    p.add(-0.11, -0.04, -0.085, -0.06, 0.42, 0.085, hr)                                          # hair to the waist
    for s in (-1, 1): p.add(-0.07, 0.08, s * 0.08 - 0.02, 0.03, 0.4, s * 0.08 + 0.02, hr)
    p.add(-0.02, 0.41, 0.05, 0.03, 0.45, 0.1, solid((255, 150, 60), 3))                            # a sea-star clip
    for s in (-1, 1): p.add(-0.02, 0.36, s * 0.075, 0.02, 0.42, s * 0.105, W.membrane((255, 170, 190), (255, 230, 240)))   # fin ears
    p.bone(None)
    for side, s in (("l", -1), ("r", 1)):
        p.bone("arm" + side, (0.0, 0.22, s * 0.1))
        a, b = sorted((s * 0.1, s * 0.145))
        p.add(-0.022, -0.02, a, 0.022, 0.22, b, skn)
        p.add(-0.022, 0.0, a, 0.024, 0.03, b, gold)                                               # a gold bangle
        p.add(-0.018, -0.06, a, 0.035, -0.02, b, skn)
        p.bone(None)
    p.bone("tail", (0.0, 0.0, 0))
    p.add(-0.07, -0.04, -0.1, 0.07, 0.02, 0.1, gold)                                              # a gold girdle
    pts = [(0.0, 0.0, 0.0), (-0.01, -0.16, 0.0), (-0.06, -0.32, 0.0), (-0.16, -0.43, 0.0), (-0.29, -0.47, 0.0)]
    W.tube(p, pts, 0.1, 0.035, tl)
    p.add(-0.5, -0.56, -0.13, -0.3, -0.4, 0.13, finp((255, 140, 180), (255, 225, 235)))          # the fluke
    for (x, y) in ((-0.02, -0.1), (-0.07, -0.25)): p.add(x - 0.03, y, -0.11, x + 0.03, y + 0.03, 0.11, W.membrane((255, 150, 190), (255, 235, 245)))
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("tail", "rot", (14, 0, 0), 1.4).anim("hair", "rot", (7, 0, 5), 2.4).anim("arml", "rot", (20, 0, 12), 1.8).anim("armr", "rot", (20, 0, -12), 1.8, 0.5)
    p.anim("body", "pos", (0, 0.9, 0), 2.2)
    return p


def merguard():
    """A guard of the Den: a merman in prismarine armour, a fin-crested helm, shell pauldrons, a deep blue tail. He
    carries a real trident (the drowned underneath holds it)."""
    p = Part("merguard", 32, 256)
    skn = W.skin((170, 200, 196), (140, 180, 180))
    arm = W.grad((90, 170, 160), (40, 100, 110), 4, (170, 240, 220), 0.12)
    dark = W.grad((40, 70, 90), (20, 40, 60), 3, (90, 140, 160), 0.08)
    tl = W.scales((40, 90, 160), (18, 40, 90))
    gold = W.grad((255, 215, 110), (200, 150, 50), 3, (255, 255, 220), 0.2)
    p.add(-0.08, 0.0, -0.12, 0.08, 0.26, 0.12, arm)                                               # breastplate
    p.add(0.078, 0.06, -0.03, 0.086, 0.2, 0.03, gold)                                              # the Den's crest
    p.add(-0.08, 0.26, -0.08, 0.08, 0.44, 0.08, skn)                                              # head
    face_eyes(p, 0.08, 0.33, 0.37, 0.035, 0.03, (240, 250, 255), (230, 190, 60), slit=True)
    p.add(-0.09, 0.38, -0.09, 0.09, 0.48, 0.09, arm)                                              # helm
    p.add(0.07, 0.36, -0.09, 0.095, 0.39, 0.09, dark)                                              # brow guard
    p.bone("crest", (-0.02, 0.48, 0))
    p.add(-0.14, 0.48, -0.012, 0.06, 0.6, 0.012, W.membrane((60, 150, 200), (160, 230, 255)))     # the fin crest
    p.bone(None)
    for side, s in (("l", -1), ("r", 1)):
        a, b = sorted((s * 0.11, s * 0.19))
        p.add(-0.07, 0.2, a, 0.07, 0.28, b, W.grad((230, 200, 220), (180, 140, 170), 3, (255, 240, 250), 0.2))   # shell pauldrons
        p.bone("arm" + side, (0.0, 0.22, s * 0.13))
        a, b = sorted((s * 0.12, s * 0.17))
        p.add(-0.026, -0.04, a, 0.026, 0.2, b, skn)
        p.add(-0.03, -0.04, a, 0.03, 0.06, b, dark)                                               # bracers
        p.bone(None)
    p.bone("tail", (0.0, 0.0, 0))
    p.add(-0.08, -0.06, -0.12, 0.08, 0.02, 0.12, dark)                                             # a scale skirt
    pts = [(0.0, 0.0, 0.0), (-0.02, -0.17, 0.0), (-0.08, -0.34, 0.0), (-0.19, -0.45, 0.0), (-0.33, -0.48, 0.0)]
    W.tube(p, pts, 0.115, 0.04, tl)
    p.add(-0.52, -0.58, -0.14, -0.33, -0.4, 0.14, finp((50, 120, 200), (160, 220, 255)))          # the fluke
    p.add(-0.04, -0.2, -0.13, 0.02, -0.17, 0.13, W.membrane((50, 120, 200), (160, 220, 255)))
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("tail", "rot", (12, 0, 0), 1.3).anim("crest", "rot", (0, 0, 8), 1.8).anim("arml", "rot", (10, 0, 6), 2.2).anim("armr", "rot", (10, 0, -6), 2.2, 0.5)
    p.anim("body", "pos", (0, 0.6, 0), 2.0)
    return p


DEN_MOBS = [mermaid, merguard]


# ---- the Trench (1.8.0): its own creatures, and the Great Whale of the Leviathan Migration
def hadalsnail():
    """A hadal snailfish: a pale pink tadpole of a fish, soft as jelly, at home at the very bottom."""
    p = Part("hadalsnail", 32, 256)
    sk = W.grad((246, 210, 220), (214, 168, 186), 4, (255, 236, 240), 0.05)
    rounded(p, 0.0, -0.1, -0.09, 0.26, 0.1, 0.09, sk, 0.06)                                       # the big soft head
    eyes(p, 0.16, 0.22, 0.02, 0.06, 0.09, (40, 30, 40), (90, 60, 80), depth=0.01)                 # tiny dark eyes
    p.add(0.25, -0.05, -0.04, 0.27, -0.02, 0.04, solid((200, 120, 140), 2))                       # the mouth
    for i in range(4): pair(p, 0.04 + i * 0.05, -0.06, 0.09, 0.06 + i * 0.05, 0.06, 0.092, solid((255, 225, 232), 2))   # see-through bands
    side_bone(p, "fin", (0.06, -0.06, 0.08), [((-0.02, -0.1, 0.08, 0.1, -0.06, 0.2), finp((240, 200, 214), (255, 232, 240)))], True)
    p.bone("t1", (0.0, 0, 0)); rounded(p, -0.26, -0.07, -0.06, 0.02, 0.07, 0.06, sk, 0.03); p.bone(None)
    p.bone("t2", (-0.26, 0, 0))
    p.add(-0.5, -0.04, -0.03, -0.24, 0.04, 0.03, sk)
    p.add(-0.62, -0.07, -0.006, -0.42, 0.07, 0.006, finp((236, 196, 210), (255, 230, 238)))      # the soft tail fin
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("t1", "rot", (0, 10, 0), 1.4).anim("t2", "rot", (0, 18, 0), 1.4, 0.2).anim("finl", "rot", (0, 0, 20), 1.0).anim("finr", "rot", (0, 0, -20), 1.0, 0.5)
    return p


def anglerking():
    """The Anglerking: a great black anglerfish with a cavern of a mouth and a glowing lure. In the Trench its lure is the only light."""
    p = Part("anglerking", 32, 512)
    sk = W.grad((30, 26, 34), (12, 10, 16), 5, (70, 60, 80), 0.05)
    rounded(p, -0.26, -0.22, -0.2, 0.2, 0.2, 0.2, sk, 0.09)                                        # the round body
    p.add(-0.1, 0.18, -0.01, 0.12, 0.26, 0.01, finp((40, 34, 44), (80, 70, 90)))                  # the dorsal fin
    eyes(p, 0.08, 0.15, 0.06, 0.12, 0.2, (20, 20, 26), (90, 200, 220), depth=0.012)               # little pale eyes
    p.add(0.18, 0.02, -0.2, 0.32, 0.14, 0.2, sk)                                                   # the upper jaw
    for z in (-0.17, -0.1, -0.03, 0.04, 0.11, 0.18): p.add(0.3, -0.04, z - 0.01, 0.32, 0.03, z + 0.01, TEETH)
    p.add(0.19, 0.0, -0.18, 0.31, 0.03, 0.18, MOUTH)
    p.bone("jaw", (0.12, -0.08, 0))
    p.add(0.12, -0.24, -0.21, 0.36, -0.08, 0.21, sk)                                              # the jutting lower jaw
    for z in (-0.18, -0.11, -0.04, 0.03, 0.1, 0.17): p.add(0.33, -0.1, z - 0.01, 0.35, 0.0, z + 0.01, TEETH)
    p.add(0.14, -0.1, -0.19, 0.33, -0.08, 0.19, MOUTH)
    p.bone(None)
    p.bone("lure", (0.08, 0.2, 0))
    W.tube(p, [(0.08, 0.2, 0), (0.18, 0.4, 0), (0.34, 0.46, 0), (0.46, 0.36, 0)], 0.02, 0.012, sk)  # the rod
    p.add(0.42, 0.28, -0.05, 0.52, 0.38, 0.05, W.glow((180, 255, 230)))                           # the glowing bulb
    p.bone(None)
    side_bone(p, "fin", (-0.04, -0.08, 0.18), [((-0.1, -0.14, 0.18, 0.04, -0.04, 0.3), finp((40, 34, 44), (90, 80, 100)))], True)
    p.bone("tail", (-0.26, 0, 0))
    p.add(-0.42, -0.06, -0.05, -0.24, 0.06, 0.05, sk)
    p.add(-0.6, -0.14, -0.01, -0.4, 0.14, 0.01, finp((36, 30, 40), (80, 70, 90)))
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.half = {"jaw"}
    p.anim("jaw", "rot", (0, 0, -22), 3.2).anim("lure", "rot", (0, 6, 10), 2.2).anim("tail", "rot", (0, 14, 0), 1.6)
    p.anim("finl", "rot", (0, 0, 20), 1.2).anim("finr", "rot", (0, 0, -20), 1.2, 0.5).anim("body", "pos", (0, 0.8, 0), 3.0)
    return p


def bonecrawler():
    """A bone crawler: a crab that wears a skull for a shell and walks on legs of bone. It pins you where you stand."""
    p = Part("bonecrawler", 32, 256)
    bn = W.bone((222, 214, 190)) if hasattr(W, "bone") else solid((222, 214, 190), 4)
    dark = solid((40, 34, 30), 2)
    rounded(p, -0.2, -0.04, -0.17, 0.16, 0.16, 0.17, bn, 0.06)                                    # the skull shell
    for s in (-1, 1): p.add(0.13, 0.04, s * 0.09 - 0.04, 0.17, 0.1, s * 0.09 + 0.04, dark)         # empty eye sockets
    p.add(0.13, -0.02, -0.02, 0.17, 0.03, 0.02, dark)                                              # the nose hole
    for z in (-0.08, -0.03, 0.02, 0.07): p.add(0.15, -0.05, z - 0.015, 0.18, -0.02, z + 0.015, TEETH)
    for s in (-1, 1):
        a, b = sorted((s * 0.05, s * 0.07))
        p.add(0.15, 0.06, a, 0.175, 0.08, b, W.glow((120, 200, 255)))                             # pale glints deep in the sockets
    legs = [((x - 0.015, -0.14, 0.14, x + 0.015, -0.02, 0.32), bn) for x in (0.08, 0.0, -0.08, -0.16)]
    side_bone(p, "legs", (0.0, -0.04, 0.14), legs, True)
    side_bone(p, "claw", (0.14, -0.02, 0.12), [((0.14, -0.06, 0.12, 0.34, 0.02, 0.18), bn), ((0.3, -0.08, 0.14, 0.4, 0.06, 0.22), bn),
                                               ((0.38, -0.02, 0.15, 0.44, 0.04, 0.2), bn)], True)
    p.anim("legsl", "rot", (0, 0, 14), 0.5).anim("legsr", "rot", (0, 0, -14), 0.5, 0.5)
    p.anim("clawl", "rot", (0, 14, 0), 1.6).anim("clawr", "rot", (0, -14, 0), 1.6, 0.4)
    return p


def palestalker():
    """A pale stalker: a long, eyeless, milk-white eel lined with needle teeth. You never see it until it's already coming."""
    p = Part("palestalker", 32, 256)
    sk = W.grad((236, 236, 228), (190, 188, 180), 4, (255, 255, 250), 0.04)
    rounded(p, 0.14, -0.06, -0.06, 0.42, 0.07, 0.06, sk, 0.03)                                     # the blind head
    for i in range(5): pair(p, 0.18 + i * 0.045, 0.0, 0.06, 0.2 + i * 0.045, 0.04, 0.062, solid((200, 120, 130), 2))   # gill slits
    for z in (-0.04, 0.0, 0.04): p.add(0.4, -0.04, z - 0.008, 0.43, 0.0, z + 0.008, TEETH)
    p.bone("jaw", (0.16, -0.05, 0))
    p.add(0.16, -0.1, -0.05, 0.42, -0.05, 0.05, sk)
    for z in (-0.035, 0.0, 0.035): p.add(0.39, -0.06, z - 0.008, 0.42, -0.02, z + 0.008, TEETH)
    p.bone(None)
    p.add(-0.06, -0.055, -0.05, 0.16, 0.06, 0.05, sk)
    p.bone("s1", (-0.06, 0, 0)); p.add(-0.36, -0.05, -0.045, -0.04, 0.055, 0.045, sk); p.add(-0.34, 0.05, -0.006, -0.06, 0.09, 0.006, finp((220, 220, 212), (250, 250, 244))); p.bone(None)
    p.bone("s2", (-0.36, 0, 0)); p.add(-0.64, -0.04, -0.035, -0.34, 0.045, 0.035, sk); p.add(-0.62, 0.04, -0.006, -0.36, 0.08, 0.006, finp((220, 220, 212), (250, 250, 244))); p.bone(None)
    p.bone("s3", (-0.64, 0, 0)); p.add(-0.95, -0.025, -0.022, -0.62, 0.03, 0.022, sk); p.add(-1.0, -0.06, -0.006, -0.86, 0.06, 0.006, finp((220, 220, 212), (250, 250, 244))); p.bone(None)
    p.half = {"jaw"}
    p.anim("jaw", "rot", (0, 0, -20), 2.0).anim("s1", "rot", (0, 10, 0), 1.3).anim("s2", "rot", (0, 16, 0), 1.3, 0.15).anim("s3", "rot", (0, 24, 0), 1.3, 0.3)
    return p


def crushjelly():
    """A crush jelly: a heavy violet bell ringed with light. Its pulse is the weight of the whole ocean, pushing you down."""
    p = Part("crushjelly", 32, 512)
    bell = W.grad((120, 50, 160), (60, 20, 90), 5, (190, 120, 230), 0.05)
    p.bone("bell", (0, 0, 0))
    rounded(p, -0.22, 0.0, -0.22, 0.22, 0.24, 0.22, bell, 0.09)
    rounded(p, -0.14, 0.22, -0.14, 0.14, 0.32, 0.14, bell, 0.05)
    for i in range(12):                                                                            # the ring of light round the rim
        a = i * math.pi / 6
        x, z = math.cos(a) * 0.22, math.sin(a) * 0.22
        p.add(x - 0.025, 0.0, z - 0.025, x + 0.025, 0.04, z + 0.025, W.glow((210, 140, 255)))
    p.add(-0.1, -0.02, -0.1, 0.1, 0.12, 0.1, W.glow((255, 120, 200)))                             # its glowing heart
    p.bone(None)
    for i, nm in enumerate(("t1", "t2", "t3", "t4")):
        a = i * math.pi / 2 + math.pi / 4
        x, z = math.cos(a) * 0.12, math.sin(a) * 0.12
        p.bone(nm, (x, 0.0, z))
        W.tube(p, [(x, 0.0, z), (x * 1.3, -0.3, z * 1.3), (x * 0.9, -0.62, z * 0.9), (x * 1.2, -0.9, z * 1.2)], 0.035, 0.01, solid((90, 30, 120), 3))
        p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("bell", "pos", (0, 0.8, 0), 2.2)
    for i, nm in enumerate(("t1", "t2", "t3", "t4")): p.anim(nm, "rot", (12, 0, 12), 2.2, i / 4)
    p.anim("body", "pos", (0, 1.4, 0), 4.0)
    return p


def tubeworm():
    """A tubeworm colony: white tubes on a hot vent, each with a blood-red plume. Get close and they sting; hurt them and the vent scalds."""
    p = Part("tubeworm", 32, 256)
    tube = W.grad((236, 230, 214), (190, 180, 160), 4, (250, 246, 236), 0.05)
    plume = W.grad((230, 40, 50), (150, 10, 24), 4, (255, 120, 120), 0.08)
    p.add(-0.24, -0.5, -0.24, 0.24, -0.42, 0.24, solid((40, 30, 30), 3))                          # the vent's crust
    spots = [(0.0, 0.0, 0.5, "p1"), (0.12, 0.08, 0.38, "p2"), (-0.1, 0.1, 0.42, "p3"), (0.08, -0.12, 0.3, "p4"), (-0.12, -0.08, 0.34, "p5")]
    for (x, z, h, nm) in spots:
        p.add(x - 0.035, -0.42, z - 0.035, x + 0.035, -0.42 + h, z + 0.035, tube)
        p.bone(nm, (x, -0.42 + h, z))
        p.add(x - 0.06, -0.42 + h, z - 0.06, x + 0.06, -0.42 + h + 0.12, z + 0.06, plume)
        p.add(x - 0.04, -0.42 + h + 0.12, z - 0.04, x + 0.04, -0.42 + h + 0.18, z + 0.04, plume)
        p.bone(None)
    for i, (_, _, _, nm) in enumerate(spots): p.anim(nm, "rot", (8, 0, 8), 2.0 + i * 0.3, i / 5)
    return p


def greatwhale():
    """A great whale of the Leviathan Migration: a blue-grey giant with a pale throat and a reef of barnacles. Gentle, unless something angers it."""
    p = Part("greatwhale", 32, 512)
    sk = W.grad((70, 90, 110), (40, 52, 66), 6, (120, 140, 160), 0.04)
    belly = solid((210, 214, 220), 4)
    rounded(p, -0.4, -0.16, -0.17, 0.3, 0.16, 0.17, sk, 0.08)                                      # the great body
    rounded(p, 0.28, -0.13, -0.14, 0.46, 0.12, 0.14, sk, 0.06)                                     # the head
    p.add(0.0, -0.17, -0.12, 0.44, -0.12, 0.12, belly)                                             # the pale grooved throat
    for z in (-0.08, -0.04, 0.0, 0.04, 0.08): p.add(0.02, -0.175, z - 0.006, 0.42, -0.165, z + 0.006, solid((170, 176, 186), 2))
    eyes(p, 0.34, 0.39, -0.04, -0.01, 0.14, (30, 30, 36), (60, 70, 90), depth=0.01)
    for (x, y, z) in ((0.38, 0.12, 0.05), (0.32, 0.13, -0.06), (0.1, 0.16, 0.08), (-0.1, 0.16, -0.05), (-0.25, 0.15, 0.02)):
        p.add(x - 0.02, y, z - 0.02, x + 0.02, y + 0.02, z + 0.02, solid((200, 196, 180), 3))    # barnacles
    p.add(0.36, 0.12, -0.015, 0.4, 0.14, 0.015, solid((20, 24, 30), 2))                           # the blowhole
    side_bone(p, "fin", (0.16, -0.1, 0.16), [((0.0, -0.13, 0.16, 0.2, -0.09, 0.42), sk)], True)     # long flippers
    p.bone("tail", (-0.4, 0, 0))                                                                   # (one bone: Java poses bones separately)
    p.add(-0.72, -0.1, -0.11, -0.38, 0.1, 0.11, sk)
    p.add(-0.92, -0.025, -0.34, -0.7, 0.025, 0.34, sk)                                            # the fluke
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("tail", "rot", (0, 0, 12), 3.0)
    p.anim("finl", "rot", (10, 0, 14), 3.0).anim("finr", "rot", (-10, 0, -14), 3.0).anim("body", "pos", (0, 1.0, 0), 6.0)
    return p



def submersible():
    """The Submersible (FaultlineDeep 1.8.0): a little yellow research sub. A round hull you sit inside, a big glass dome at
    the front, a headlight, fins and a propeller (its own bone) at the back. +x = forward; the origin is where the pilot sits."""
    p = Part("submersible", 32, 512)
    hull = W.grad((236, 196, 50), (190, 148, 30), 5, (250, 220, 90), 0.03)
    dark = solid((50, 54, 62), 4)
    glass = solid((150, 214, 236), 3)
    rounded(p, -0.75, -0.55, -0.55, 0.45, 0.55, 0.55, hull, 0.24)                                  # the hull
    rounded(p, 0.25, -0.42, -0.45, 0.85, 0.48, 0.45, glass, 0.2)                                    # the glass dome
    for z in (-0.46, 0.46): p.add(0.25, -0.44, z - 0.03, 0.88, 0.5, z + 0.03, dark)               # the dome's frame
    p.add(0.84, -0.25, -0.1, 0.92, -0.1, 0.1, W.glow((255, 250, 210)))                            # the headlight
    p.add(-0.4, 0.5, -0.12, 0.1, 0.72, 0.12, hull)                                                 # the hatch on top
    p.add(-0.2, 0.72, -0.08, 0.0, 0.78, 0.08, dark)
    for s in (-1, 1):
        a, b = sorted((s * 0.55, s * 0.95))
        p.add(-0.5, -0.12, a, -0.1, -0.04, b, hull)                                                # side fins
        a2, b2 = sorted((s * 0.56, s * 0.62))
        p.add(-0.2, -0.3, a2, 0.15, 0.2, b2, dark)                                                 # side portholes' rims
    p.add(-1.05, -0.06, -0.06, -0.75, 0.06, 0.06, dark)                                           # the prop shaft
    p.add(-1.0, 0.0, -0.02, -0.7, 0.55, 0.02, hull)                                               # the tail fin
    p.add(-1.0, -0.4, -0.02, -0.7, 0.0, 0.02, hull)
    p.add(-0.9, -0.3, -0.3, -0.75, -0.22, 0.3, dark)                                              # skids
    p.bone("prop", (-1.08, 0, 0))
    for (y, z) in ((0.3, 0), (-0.3, 0), (0, 0.3), (0, -0.3)): p.add(-1.12, min(0, y) - 0.04 + (0.04 if y == 0 else 0), min(0, z) - 0.04 + (0.04 if z == 0 else 0),
                                                                 -1.06, max(0, y) + 0.04 - (0.04 if y == 0 else 0), max(0, z) + 0.04 - (0.04 if z == 0 else 0), solid((210, 210, 220), 3))
    p.bone(None)
    p.anim("prop", "rot", (0, 0, 0), 0.8)  # (Java spins it itself while it moves; Bedrock below)
    p.anims = [("prop", "rot", (0, 0, 180), 0.4, 0.0)]
    return p

TRENCH_MOBS = [hadalsnail, anglerking, bonecrawler, palestalker, crushjelly, tubeworm, greatwhale, submersible]


# --------------------------------------------------------------------------------------------- the outer ring (Deep 1.9.0)
# The Bone Fields, the Glass Reef, the Frozen Shelf and the undersea volcanoes. 32 pixels a block.
def glass_paint(c, alpha=150):
    """Crystal: a see-through colour with bright facet edges and a glint."""
    def f(reg, face, rng):
        h, w = reg.shape[:2]
        for y in range(h):
            for x in range(w):
                edge = min(x, y, w - 1 - x, h - 1 - y) == 0
                diag = abs((x - y) % 7) == 0
                col = np.array(c, float) * (1.25 if edge else 1.0) + (30 if diag else 0)
                a = alpha + (70 if edge else 0) + (30 if diag else 0)
                if face == "up" and x < w * 0.3 and y < h * 0.3: col, a = np.array((255, 255, 255), float), 220
                reg[y, x, :3] = np.clip(col + rng.uniform(-8, 8), 0, 255)
                reg[y, x, 3] = min(255, a)
    return f


BONEW = (226, 220, 200)


def marroweel():
    """A marrow eel: a pale eel that nests in the leviathans' bones, its body ribbed like a spine. It bites, and lets go of nothing."""
    p = Part("marroweel", 32, 256)
    sk = W.grad((208, 200, 180), (150, 140, 124), 4, (240, 234, 218), 0.05)
    rib = solid((236, 230, 212), 3)
    rounded(p, 0.12, -0.06, -0.055, 0.4, 0.07, 0.055, sk, 0.03)                                    # the bony head
    eyes(p, 0.3, 0.35, 0.02, 0.05, 0.056, (30, 20, 20), (220, 60, 50), depth=0.01)                 # red little eyes
    for z in (-0.035, 0.0, 0.035): p.add(0.38, -0.04, z - 0.007, 0.41, 0.0, z + 0.007, TEETH)
    p.bone("jaw", (0.14, -0.05, 0))
    p.add(0.14, -0.1, -0.05, 0.4, -0.05, 0.05, sk)
    for z in (-0.03, 0.03): p.add(0.37, -0.06, z - 0.007, 0.4, -0.02, z + 0.007, TEETH)
    p.bone(None)
    p.add(-0.08, -0.055, -0.05, 0.14, 0.06, 0.05, sk)
    for i, (nm, x0, x1, hh) in enumerate((("s1", -0.36, -0.06, 0.05), ("s2", -0.66, -0.34, 0.042), ("s3", -0.98, -0.64, 0.03))):
        p.bone(nm, (x1, 0, 0))
        p.add(x0, -hh, -hh * 0.9, x1, hh, hh * 0.9, sk)
        for k in range(4): p.add(x0 + (x1 - x0) * k / 4, hh - 0.005, -0.006, x0 + (x1 - x0) * k / 4 + 0.025, hh + 0.04, 0.006, rib)   # spine knobs
        p.bone(None)
    p.half = {"jaw"}
    p.anim("jaw", "rot", (0, 0, -22), 1.8).anim("s1", "rot", (0, 10, 0), 1.2).anim("s2", "rot", (0, 16, 0), 1.2, 0.15).anim("s3", "rot", (0, 24, 0), 1.2, 0.3)
    return p


def scavcrab():
    """A scavenger crab: a grey crab with a shell grown over with bone shards, picking the skeletons clean."""
    p = Part("scavcrab", 32, 256)
    shell = W.grad((110, 104, 98), (70, 64, 60), 4, (150, 144, 136), 0.05)
    bn = solid(BONEW, 3)
    rounded(p, -0.18, -0.04, -0.2, 0.16, 0.12, 0.2, shell, 0.06)
    for (x, z) in ((-0.1, -0.12), (0.0, 0.1), (0.08, -0.05), (-0.05, 0.02), (0.1, 0.14)): p.add(x - 0.02, 0.12, z - 0.02, x + 0.02, 0.2, z + 0.02, bn)   # bone shards
    for s in (-1, 1):
        a, b = sorted((s * 0.05, s * 0.09))
        p.add(0.15, 0.08, a, 0.2, 0.12, b, solid((20, 18, 18), 2))                                # eyes on stalks
    legs = [((x - 0.012, -0.14, 0.16, x + 0.012, -0.02, 0.34), shell) for x in (0.08, 0.0, -0.08, -0.15)]
    side_bone(p, "legs", (0.0, -0.04, 0.16), legs, True)
    side_bone(p, "claw", (0.14, 0.0, 0.14), [((0.14, -0.05, 0.14, 0.3, 0.03, 0.22), shell), ((0.28, -0.08, 0.15, 0.42, 0.08, 0.26), bn)], True)
    p.anim("legsl", "rot", (0, 0, 14), 0.45).anim("legsr", "rot", (0, 0, -14), 0.45, 0.5)
    p.anim("clawl", "rot", (0, 16, 0), 1.4).anim("clawr", "rot", (0, -16, 0), 1.4, 0.4)
    return p


def ossuaryray():
    """An ossuary ray: a great ray whose body has rotted away to a lattice of bones, still gliding over the Bone Fields."""
    p = Part("ossuaryray", 32, 512)
    bn = W.bone(BONEW) if hasattr(W, "bone") else solid(BONEW, 4)
    film = bubble_paint((120, 130, 150), 70)
    rounded(p, -0.2, -0.05, -0.12, 0.22, 0.06, 0.12, bn, 0.04)                                     # the spine and skull
    for s in (-1, 1): p.add(0.14, 0.03, s * 0.07 - 0.015, 0.18, 0.06, s * 0.07 + 0.015, solid((90, 220, 255), 1))   # cold glints for eyes
    for side in ("l", "r"):
        sg = 1 if side == "r" else -1
        p.bone("wing" + side, (0.0, 0.0, sg * 0.12))
        for k in range(5):                                                                         # finger bones fanning out
            x = 0.16 - k * 0.09
            a, b = sorted((sg * 0.12, sg * (0.6 - k * 0.07)))
            p.add(x - 0.015, -0.01, a, x + 0.015, 0.02, b, bn)
        a, b = sorted((sg * 0.12, sg * 0.56))
        p.add(-0.24, 0.0, a, 0.18, 0.005, b, film)                                                # the ghost of its skin
        p.bone(None)
    p.bone("tail", (-0.2, 0, 0))
    p.add(-0.8, -0.012, -0.012, -0.18, 0.012, 0.012, bn)
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("wingl", "rot", (24, 0, 0), 3.2).anim("wingr", "rot", (-24, 0, 0), 3.2).anim("tail", "rot", (0, 10, 0), 2.0).anim("body", "pos", (0, 1.0, 0), 5.0)
    return p


def glassjelly():
    """A glass jelly: a bell of living crystal that rings when it pulses. Kill it and it shatters."""
    p = Part("glassjelly", 32, 256)
    bell = glass_paint((140, 210, 255), 120)
    p.bone("bell", (0, 0, 0))
    rounded(p, -0.2, 0.0, -0.2, 0.2, 0.22, 0.2, bell, 0.08)
    p.add(-0.09, 0.02, -0.09, 0.09, 0.16, 0.09, W.glow((200, 240, 255)))                          # the bright core
    for i in range(8):
        a = i * math.pi / 4
        x, z = math.cos(a) * 0.2, math.sin(a) * 0.2
        p.add(x - 0.02, -0.01, z - 0.02, x + 0.02, 0.03, z + 0.02, W.glow((255, 160, 240) if i % 2 else (160, 255, 220)))
    p.bone(None)
    for i, nm in enumerate(("t1", "t2", "t3", "t4")):
        a = i * math.pi / 2
        x, z = math.cos(a) * 0.1, math.sin(a) * 0.1
        p.bone(nm, (x, 0.0, z))
        W.tube(p, [(x, 0.0, z), (x * 1.3, -0.25, z * 1.3), (x, -0.5, z)], 0.02, 0.008, glass_paint((180, 230, 255), 140))
        p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("bell", "pos", (0, 0.6, 0), 1.8)
    for i, nm in enumerate(("t1", "t2", "t3", "t4")): p.anim(nm, "rot", (10, 0, 10), 1.8, i / 4)
    p.anim("body", "pos", (0, 1.0, 0), 3.6)
    return p


def prismfish():
    """A prism fish: a little fish of cut crystal. Schools of them throw rainbows across the reef."""
    p = Part("prismfish", 32, 128)
    body = glass_paint((255, 200, 240), 150)
    rounded(p, -0.14, -0.08, -0.05, 0.14, 0.08, 0.05, body, 0.03)
    for i, c in enumerate(((255, 80, 80), (255, 200, 60), (80, 220, 120), (80, 160, 255))):        # rainbow bands down its flanks
        x = -0.09 + i * 0.05
        pair(p, x, -0.06, 0.05, x + 0.03, 0.06, 0.052, glass_paint(c, 190))
    eyes(p, 0.08, 0.11, 0.0, 0.03, 0.052, (250, 250, 255), (40, 40, 60), depth=0.006)
    p.add(-0.06, 0.07, -0.004, 0.06, 0.12, 0.004, glass_paint((255, 255, 255), 160))               # the dorsal fin
    p.bone("tail", (-0.14, 0, 0))
    p.add(-0.26, -0.07, -0.004, -0.13, 0.07, 0.004, glass_paint((200, 160, 255), 170))
    p.bone(None)
    p.anim("tail", "rot", (0, 24, 0), 0.6)
    return p


def crystalcrab():
    """A crystal crab: an amethyst geode that walks. Its shell turns most blows; hit the soft joints."""
    p = Part("crystalcrab", 32, 256)
    shell = W.grad((150, 100, 210), (90, 50, 150), 4, (210, 170, 255), 0.06)
    xtal = glass_paint((200, 150, 255), 170)
    rounded(p, -0.2, -0.04, -0.22, 0.18, 0.12, 0.22, shell, 0.06)
    for (x, z, h) in ((-0.08, -0.1, 0.16), (0.04, 0.08, 0.2), (0.1, -0.04, 0.12), (-0.12, 0.12, 0.14), (0.0, -0.02, 0.22)):
        p.add(x - 0.03, 0.12, z - 0.03, x + 0.03, 0.12 + h, z + 0.03, xtal)                       # crystals growing from its back
    for s in (-1, 1):
        a, b = sorted((s * 0.06, s * 0.1))
        p.add(0.17, 0.08, a, 0.22, 0.13, b, W.glow((255, 230, 120)))
    legs = [((x - 0.012, -0.14, 0.18, x + 0.012, -0.02, 0.36), shell) for x in (0.08, 0.0, -0.08, -0.16)]
    side_bone(p, "legs", (0.0, -0.04, 0.18), legs, True)
    side_bone(p, "claw", (0.16, 0.0, 0.16), [((0.16, -0.05, 0.16, 0.32, 0.04, 0.24), shell), ((0.3, -0.08, 0.17, 0.46, 0.09, 0.28), xtal)], True)
    p.anim("legsl", "rot", (0, 0, 14), 0.5).anim("legsr", "rot", (0, 0, -14), 0.5, 0.5)
    p.anim("clawl", "rot", (0, 14, 0), 1.6).anim("clawr", "rot", (0, -14, 0), 1.6, 0.4)
    return p


def shardback():
    """A shardback: a long fish armoured in glass blades. It flares them and fires the spare ones at you."""
    p = Part("shardback", 32, 256)
    sk = fish((60, 70, 110), (190, 200, 230), (120, 180, 255), 0.06)
    blade = glass_paint((150, 230, 255), 170)
    rounded(p, -0.3, -0.09, -0.08, 0.24, 0.09, 0.08, sk, 0.04)
    rounded(p, 0.22, -0.07, -0.065, 0.38, 0.07, 0.065, sk, 0.03)
    eyes(p, 0.3, 0.35, 0.01, 0.05, 0.066, (240, 250, 255), (20, 40, 80), depth=0.008)
    p.bone("blades", (0.0, 0.09, 0))
    for i in range(6): x = 0.16 - i * 0.08; p.add(x - 0.02, 0.08, -0.006, x + 0.02, 0.2 - i * 0.012, 0.006, blade)   # the crest of blades
    p.bone(None)
    for i in range(4):
        x = 0.1 - i * 0.1
        pair(p, x - 0.015, -0.04, 0.08, x + 0.015, 0.03, 0.14, blade)                               # side blades
    p.bone("tail", (-0.3, 0, 0))
    p.add(-0.46, -0.05, -0.04, -0.28, 0.05, 0.04, sk)
    p.add(-0.6, -0.12, -0.005, -0.44, 0.12, 0.005, blade)
    p.bone(None)
    p.anim("tail", "rot", (0, 18, 0), 0.9).anim("blades", "rot", (0, 0, 8), 1.4)
    return p


def narwhal():
    """A narwhal: a mottled grey whale with a single spiralled tusk. Gentle, until you hurt one of its pod."""
    p = Part("narwhal", 32, 512)
    sk = W.grad((150, 156, 164), (96, 100, 108), 5, (60, 64, 72), 0.12)
    rounded(p, -0.36, -0.13, -0.13, 0.26, 0.13, 0.13, sk, 0.07)
    rounded(p, 0.24, -0.1, -0.1, 0.4, 0.1, 0.1, sk, 0.05)
    p.add(-0.2, -0.14, -0.1, 0.36, -0.11, 0.1, solid((210, 214, 218), 4))                         # the pale belly
    eyes(p, 0.32, 0.36, 0.0, 0.03, 0.1, (20, 20, 26), (50, 50, 60), depth=0.008)
    W.tube(p, [(0.4, 0.02, 0.02), (0.7, 0.04, 0.03), (1.0, 0.06, 0.03)], 0.03, 0.008, W.grad((240, 232, 210), (200, 190, 170), 3))   # the tusk
    for i in range(6): p.add(0.44 + i * 0.09, 0.02, 0.0, 0.46 + i * 0.09, 0.07, 0.05, solid((180, 170, 150), 2))   # its spiral grooves
    side_bone(p, "fin", (0.12, -0.08, 0.12), [((0.02, -0.11, 0.12, 0.16, -0.07, 0.28), sk)], True)
    p.bone("tail", (-0.36, 0, 0))
    p.add(-0.62, -0.07, -0.08, -0.34, 0.07, 0.08, sk)
    p.add(-0.78, -0.02, -0.24, -0.6, 0.02, 0.24, sk)
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("tail", "rot", (0, 0, 14), 1.8).anim("finl", "rot", (10, 0, 14), 1.8).anim("finr", "rot", (-10, 0, -14), 1.8).anim("body", "pos", (0, 0.8, 0), 4.0)
    return p


def iceseal():
    """An ice seal: a fat silver seal with dark spots and big black eyes. It plays round the breathing holes."""
    p = Part("iceseal", 32, 256)
    sk = W.grad((200, 204, 212), (150, 154, 164), 4, (90, 94, 104), 0.14)
    rounded(p, -0.3, -0.12, -0.12, 0.18, 0.11, 0.12, sk, 0.08)
    rounded(p, 0.16, -0.08, -0.08, 0.32, 0.08, 0.08, sk, 0.05)
    eyes(p, 0.26, 0.31, 0.0, 0.05, 0.081, (12, 12, 16), (30, 30, 36), depth=0.008)
    p.add(0.31, -0.03, -0.02, 0.33, 0.0, 0.02, solid((40, 36, 36), 2))                            # the nose
    for s in (-1, 1): p.add(0.3, -0.03, s * 0.03 - 0.02, 0.32, -0.02, s * 0.03 + 0.02, solid((230, 230, 236), 1))   # whiskers
    side_bone(p, "fin", (0.06, -0.08, 0.1), [((-0.02, -0.11, 0.1, 0.12, -0.08, 0.2), sk)], True)
    p.bone("tail", (-0.3, 0, 0))
    p.add(-0.42, -0.04, -0.06, -0.28, 0.04, 0.06, sk)
    for s in (-1, 1):
        a, b = sorted((s * 0.0, s * 0.12))
        p.add(-0.52, -0.02, a, -0.4, 0.0, b, sk)                                                  # the hind flippers
    p.bone(None)
    p.anim("tail", "rot", (0, 0, 16), 1.2).anim("finl", "rot", (0, 0, 18), 1.2).anim("finr", "rot", (0, 0, -18), 1.2, 0.5)
    return p


def orca(name, ice):
    p = Part(name, 32, 512)
    black = W.grad((24, 26, 32), (10, 10, 14), 4, (50, 54, 64), 0.04)
    white = solid((236, 240, 246), 4)
    rounded(p, -0.4, -0.15, -0.15, 0.28, 0.15, 0.15, black, 0.08)
    rounded(p, 0.26, -0.12, -0.12, 0.44, 0.11, 0.12, black, 0.06)
    p.add(-0.3, -0.16, -0.11, 0.42, -0.1, 0.11, white)                                            # the white belly
    for s in (-1, 1):
        a, b = sorted((s * 0.115, s * 0.125))
        p.add(0.28, 0.0, a, 0.36, 0.06, b, white)                                                 # the eye patch
    eyes(p, 0.34, 0.37, 0.0, 0.025, 0.122, (220, 240, 255) if ice else (20, 20, 26), (120, 220, 255) if ice else (40, 40, 50), depth=0.006)
    p.add(-0.1, 0.14, -0.012, 0.06, 0.34, 0.012, black)                                          # the tall dorsal fin
    if ice:
        crust = glass_paint((170, 220, 255), 200)
        for i in range(7): x = 0.2 - i * 0.09; p.add(x - 0.03, 0.12, -0.03 + (i % 2) * 0.02, x + 0.02, 0.24 + (i % 3) * 0.04, 0.02 + (i % 2) * 0.02, crust)   # frost spikes
        for (x, y, z) in ((0.1, 0.0, 0.15), (-0.2, 0.05, -0.15), (0.3, -0.05, 0.12), (-0.1, -0.08, -0.15)): p.add(x - 0.05, y - 0.04, z - 0.01, x + 0.05, y + 0.04, z + 0.01, crust)   # ice crust
    p.add(0.4, -0.04, -0.11, 0.45, 0.0, 0.11, TEETH)
    p.bone("jaw", (0.28, -0.08, 0))
    p.add(0.28, -0.14, -0.1, 0.45, -0.08, 0.1, black)
    p.add(0.42, -0.09, -0.09, 0.45, -0.06, 0.09, TEETH)
    p.bone(None)
    side_bone(p, "fin", (0.14, -0.1, 0.14), [((0.04, -0.13, 0.14, 0.2, -0.09, 0.32), black)], True)
    p.bone("tail", (-0.4, 0, 0))
    p.add(-0.68, -0.08, -0.09, -0.38, 0.08, 0.09, black)
    p.add(-0.86, -0.02, -0.28, -0.66, 0.02, 0.28, black)
    p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.half = {"jaw"}
    p.anim("jaw", "rot", (0, 0, -18), 2.4).anim("tail", "rot", (0, 0, 14), 1.6).anim("finl", "rot", (10, 0, 14), 1.6).anim("finr", "rot", (-10, 0, -14), 1.6)
    return p


def frostorca():
    """A frost orca: a black-and-white hunter of the Frozen Shelf. Its bite freezes, and it hunts in pairs."""
    return orca("frostorca", False)


def rimeback():
    """THE RIMEBACK: a vast orca sheathed in ice, the Frozen Shelf's mini boss. Its eyes burn pale blue."""
    return orca("rimeback", True)


def rimejelly():
    """A rime jelly: a frosted white bell trailing icy threads. Its sting freezes you where you swim."""
    p = Part("rimejelly", 32, 256)
    bell = W.grad((236, 246, 255), (190, 214, 236), 4, (255, 255, 255), 0.06)
    p.bone("bell", (0, 0, 0))
    rounded(p, -0.2, 0.0, -0.2, 0.2, 0.2, 0.2, bell, 0.08)
    for i in range(8):
        a = i * math.pi / 4
        x, z = math.cos(a) * 0.18, math.sin(a) * 0.18
        p.add(x - 0.02, -0.04, z - 0.02, x + 0.02, 0.0, z + 0.02, W.glow((170, 230, 255)))
    p.bone(None)
    for i, nm in enumerate(("t1", "t2", "t3", "t4", "t5")):
        a = i * 2 * math.pi / 5
        x, z = math.cos(a) * 0.1, math.sin(a) * 0.1
        p.bone(nm, (x, 0.0, z))
        W.tube(p, [(x, 0.0, z), (x * 1.2, -0.3, z * 1.2), (x, -0.7, z)], 0.016, 0.006, solid((200, 230, 255), 2))
        p.bone(None)
    p.pivots["body"] = (0, 0, 0)
    p.anim("bell", "pos", (0, 0.6, 0), 2.0)
    for i, nm in enumerate(("t1", "t2", "t3", "t4", "t5")): p.anim(nm, "rot", (8, 0, 8), 2.0, i / 5)
    p.anim("body", "pos", (0, 1.0, 0), 4.0)
    return p


def magmaeel():
    """A magma eel: a black eel cracked with glowing orange seams, born in the volcanoes' vents. It burns what it bites."""
    p = Part("magmaeel", 32, 256)
    sk = W.glow_lines((22, 16, 16), (255, 130, 30), 4, 0.05) if hasattr(W, "glow_lines") else solid((40, 30, 30), 4)
    rounded(p, 0.12, -0.06, -0.055, 0.4, 0.07, 0.055, sk, 0.03)
    eyes(p, 0.3, 0.35, 0.02, 0.05, 0.056, (255, 200, 60), (255, 90, 20), depth=0.01)
    p.bone("jaw", (0.14, -0.05, 0))
    p.add(0.14, -0.1, -0.05, 0.4, -0.05, 0.05, sk)
    p.add(0.16, -0.06, -0.045, 0.38, -0.05, 0.045, W.glow((255, 160, 40)))                       # the glowing throat
    p.bone(None)
    p.add(-0.08, -0.055, -0.05, 0.14, 0.06, 0.05, sk)
    for nm, x0, x1, hh in (("s1", -0.36, -0.06, 0.05), ("s2", -0.66, -0.34, 0.042), ("s3", -0.98, -0.64, 0.03)):
        p.bone(nm, (x1, 0, 0)); p.add(x0, -hh, -hh * 0.9, x1, hh, hh * 0.9, sk); p.bone(None)
    p.half = {"jaw"}
    p.anim("jaw", "rot", (0, 0, -22), 1.6).anim("s1", "rot", (0, 10, 0), 1.0).anim("s2", "rot", (0, 16, 0), 1.0, 0.15).anim("s3", "rot", (0, 24, 0), 1.0, 0.3)
    return p


def cindercrab():
    """A cinder crab: a crab with a shell of cooling lava, still glowing in its cracks. Hurt it and it bursts into embers."""
    p = Part("cindercrab", 32, 256)
    shell = W.glow_lines((26, 20, 18), (255, 120, 30), 4, 0.07) if hasattr(W, "glow_lines") else solid((50, 40, 38), 4)
    dark = solid((30, 24, 22), 3)
    rounded(p, -0.18, -0.04, -0.2, 0.16, 0.14, 0.2, shell, 0.06)
    for s in (-1, 1):
        a, b = sorted((s * 0.05, s * 0.09))
        p.add(0.15, 0.1, a, 0.2, 0.14, b, W.glow((255, 220, 80)))
    legs = [((x - 0.012, -0.14, 0.16, x + 0.012, -0.02, 0.32), dark) for x in (0.08, 0.0, -0.08, -0.15)]
    side_bone(p, "legs", (0.0, -0.04, 0.16), legs, True)
    side_bone(p, "claw", (0.14, 0.0, 0.14), [((0.14, -0.05, 0.14, 0.3, 0.04, 0.22), shell), ((0.28, -0.08, 0.15, 0.4, 0.08, 0.25), shell)], True)
    p.anim("legsl", "rot", (0, 0, 14), 0.45).anim("legsr", "rot", (0, 0, -14), 0.45, 0.5)
    p.anim("clawl", "rot", (0, 14, 0), 1.4).anim("clawr", "rot", (0, -14, 0), 1.4, 0.4)
    return p


OUTER_MOBS = [marroweel, scavcrab, ossuaryray, glassjelly, prismfish, crystalcrab, shardback, narwhal, iceseal, frostorca, rimeback,
              rimejelly, magmaeel, cindercrab]


NEW_SUNLIT = [sailfin, mantaglider, leafydragon, pearlhorse, hammerjaw, razorcuda, stingray, mirrorfin, kelpstrangler, siren]
NEW_ABYSS = [gulper, isopod, ghostshark, vampsquid, dumbo, swallower, chainlight, viperfang, hagfish, watcher]


SULPHUR_MOBS = [acideel, trasher, radiator, flakcrab, nucleartoad, gammaslime, sulphurskater, acidjelly, orthocera, trilobite,
                skyfin, leech, boglurker, corrosionshark, urchin, crabling, bloatgut, sulphurmoth, hazardturtle, sludgesnail]


MOBS = [gawker, bloatfin, jellywing, driftback, clam, lumenbell, gasbloom, fangstalker, blastfin, burrowshark,
        cavecrawler, glowgrub, shellsnake, lampsquid, volteel, needlejaw, devilfish, riftwarper, hollowkin, sharkquid] + SULPHUR_MOBS + RAIN_MOBS + NEW_SUNLIT + NEW_ABYSS + DEN_MOBS + TRENCH_MOBS + OUTER_MOBS


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


# --------------------------------------------------------------------------------------------- 1.9.0: the outer ring's items
_OP = {"k": (22, 24, 30), "w": (235, 240, 245), "c": (120, 220, 235), "C": (60, 160, 190), "b": (40, 90, 140), "g": (200, 160, 60),
       "G": (150, 110, 30), "y": (250, 220, 110), "o": (110, 150, 70), "O": (70, 105, 50), "t": (90, 160, 150), "T": (50, 110, 105),
       "r": (170, 40, 40), "p": (230, 120, 200), "P": (180, 70, 160), "s": (200, 190, 160), "S": (150, 140, 110), "l": (180, 220, 255),
       "L": (110, 170, 230), "e": (255, 70, 60), "n": (90, 60, 40), "u": (70, 70, 90), "U": (120, 120, 140), "a": (255, 255, 255), "m": (40, 60, 70)}


def _pix(grid):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(grid):
        for x, ch in enumerate(row[:16]):
            if ch in _OP: im.putpixel((x, y), _OP[ch] + (255,))
    return im


OUTER_ICONS = {
    "air_coral": [
        "......a..a......", ".....a.a..a.....", "....a..pp..a....", ".....pPppPp.....", "....pPp..pPp....", "...pP.pPPp.Pp...",
        "...p.pPppPp.p...", "....pP.pp.Pp....", ".....pPppPp.....", "......pPPp......", ".......pp.......", "......PppP......",
        ".....SsssSs.....", "....SssssssS....", "....SSSSSSSS....", "................"],
    "dome_kit_small": [
        "................", "................", ".....llllll.....", "....lLaaaalL....", "...lLa....aLl...", "...lL......Ll...",
        "..lL........Ll..", "..lL........Ll..", "..yLLLLLLLLLLy..", "..nnnnnnnnnnnn..", "..nGGGGGGGGGGn..", "..nGGnnnnnnGGn..",
        "..nnnnnnnnnnnn..", "................", "................", "................"],
    "dome_kit_large": [
        "................", "....llllllll....", "...lLaaaaaaLl...", "..lLa......aLl..", ".lL..........Ll.", ".lL..........Ll.",
        "lL............Ll", "lL............Ll", "yLLLLLLLLLLLLLLy", "nnnnnnnnnnnnnnnn", "nuuuuuuuuuuuuuun", "nuUUUUUUUUUUUUun",
        "nuuuuuuuuuuuuuun", "nnnnnnnnnnnnnnnn", "................", "................"],
    "drowned_vault_key": [
        "................", "....tttt........", "...tTTTTt.......", "..tT....Tt......", "..tT....Tt......", "..tT....Tt......",
        "...tTTTTt.......", "....tTTt........", ".....tTt........", ".....tTtt.......", ".....tTTTt......", ".....tTt........",
        ".....tTtt.......", ".....tTTTt......", "......tt........", "................"],
    "ancient_fossil": [
        "................", "......nnnn......", "....nnSSSSnn....", "...nSSs..sSSn...", "..nS.ssssss.Sn..", "..nS.s.ss.s.Sn..",
        "..nS.ssssss.Sn..", "..nS.s.ss.s.Sn..", "..nS.ssssss.Sn..", "...nS.s..s.Sn...", "...nSs.ss.sSn...", "....nSSssSSn....",
        "......nSSn......", "................", "................", "................"],
    # 1.12.0: the Abyssal Chart's Index icon (the item itself is a vanilla filled map) and the Glimmertide's token
    "abyssal_chart": [
        "................", ".SSSSSSSSSSSSSS.", ".SssssssssssssS.", ".SsbbbbmmmkkksS.", ".SsbCcbbmmkkksS.", ".SsbccCbbmmkksS.",
        ".SsbbccbbbmmksS.", ".SsbbbbbebbmmsS.", ".SsbbbbeeebbmsS.", ".SsbbbbbebbbbsS.", ".SsbCcbbbbbbbsS.", ".SsbcCcbbbccbsS.",
        ".SsbbbbbbbcCbsS.", ".SssssssssssssS.", ".SSSSSSSSSSSSSS.", "................"],
    "glimmer_pearl": [
        "................", ".......a........", "......ala.......", ".....a.l.a......", "....lllllll.....", "...llcaaacll....",
        "..llcaaaaaccl...", "..lcaaaaaaacl...", "..lcaaapaaacl.a.", "..lccaapaaccl.l.", "...lccappcclalal", "....lllcclll..l.",
        "......llll...a..", "................", "................", "................"],
}
OUTER_NO_ITEM = {"abyssal_chart"}  # Index icon only
# the Drowned City's people are plain drowned (no model): their eggs / Index icons are heads
OUTER_HEADS = {
    "drowncitizen": [
        "................", "................", "...TTTTTTTTTT...", "...TttTTttTTT...", "...TtttttttTT...", "...ttcttttcttt..",
        "...tteettteett..", "...tttttttttt...", "...ttTTTTTTtt...", "...tTkkkkkkTt...", "...ttTTTTTTtt...", "...TttttttttT...",
        "....tttttttt....", "................", "................", "................"],
    "drownguard": [
        "................", "...uuuuuuuuuu...", "..uUUUUUUUUUUu..", "..uUuuuuuuuuUu..", "..uu.tttttt.uu..", "..u.ttcttctt.u..",
        "..u.teettee.tu..", "..uutttttttuuu..", "..uuTkkkkkkTuu..", "..uuttttttttuu..", "...uuuuuuuuuu...", "................",
        "................", "................", "................", "................"],
    "drownpriest": [
        "................", ".....PPPPPP.....", "....PPPPPPPP....", "...PPPgggPPPP...", "...PPtttttPPP...", "..PPttcttcttPP..",
        "..PPteettee tPP.", "..PPtttttttPP...", "..PPtTkkkTtPP...", "..PPPttttttPP...", "..PPPPPPPPPPP...", "...PPPPPPPPP....",
        "................", "................", "................", "................"],
    "drownking": [
        "..g...g..g...g..", "..gg.ggggg.gg...", "..gyggyggyggyg..", "..gggggggggggg..", "...TTTTTTTTTT...", "...ttcttttcttt..",
        "...tteettteett..", "...tttttttttt...", "...ttTTTTTTtt...", "...tTkkkkkkTt...", "...TtTTTTTTtT...", "...TTttttttTT...",
        "....TTttttTT....", ".....TTTTTT.....", "................", "................"],
}


# --------------------------------------------------------------------------------------------- 1.10.0: the Dark Ocean's fish
# (FaultlineItems Fishing: COD / SALMON / TROPICAL_FISH with item model faultline:fish/<id>)
FISH = {  # id: (base item, style, body, belly, fin, accent)
    "gloomfin": ("cod", "fish", (95, 115, 140), (170, 185, 200), (60, 80, 110), None),
    "shadow_mackerel": ("cod", "slim", (60, 75, 95), (190, 200, 210), (40, 50, 65), (20, 22, 30)),
    "pearl_snapper": ("salmon", "fish", (235, 150, 170), (250, 225, 230), (215, 110, 140), (255, 250, 245)),
    "lanternjaw": ("cod", "lantern", (40, 45, 70), (70, 75, 100), (30, 32, 50), (150, 255, 230)),
    "grenadier": ("salmon", "rattail", (120, 110, 120), (170, 160, 165), (90, 80, 90), None),
    "ghost_eel": ("salmon", "eel", (200, 225, 235), (230, 245, 250), (170, 200, 215), (230, 70, 90)),
    "acid_gar": ("salmon", "slim", (110, 160, 50), (200, 220, 90), (70, 110, 30), (230, 240, 60)),
    "sulphur_sprat": ("cod", "small", (230, 205, 60), (250, 240, 150), (190, 160, 30), None),
    "mermaid_tetra": ("tropical_fish", "round", (60, 120, 230), (240, 200, 70), (240, 190, 60), None),
    "coral_princess": ("tropical_fish", "frill", (245, 120, 160), (255, 205, 220), (200, 80, 200), (255, 240, 120)),
    "hadal_snailfish": ("cod", "snail", (235, 215, 215), (250, 235, 235), (215, 185, 190), None),
    "coelacanth": ("salmon", "lobed", (60, 70, 110), (110, 120, 150), (40, 50, 85), (225, 225, 235)),
    "titan_isopod": ("cod", "isopod", (190, 175, 160), (220, 210, 195), (150, 135, 120), None),
    "blind_king": ("salmon", "king", (55, 50, 60), (95, 90, 100), (35, 32, 40), (230, 190, 70)),
}


def fish_icon(style, body, belly, fin, accent):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = {}
    def put(x, y, c):
        if 0 <= x < 16 and 0 <= y < 16: px[(x, y)] = c
    shapes = {  # half-height of the body at each x (head at the right), tail start
        "fish": ([0, 0, 0, 1, 2, 2, 3, 3, 3, 3, 3, 3, 2, 2, 1, 0], 3),
        "slim": ([0, 0, 0, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 0], 3),
        "small": ([0, 0, 0, 0, 0, 1, 1, 2, 2, 2, 2, 2, 1, 1, 0, 0], 5),
        "round": ([0, 0, 0, 0, 2, 3, 4, 4, 4, 4, 4, 3, 3, 2, 1, 0], 4),
        "frill": ([0, 0, 0, 0, 2, 3, 3, 4, 4, 4, 4, 3, 3, 2, 1, 0], 4),
        "lantern": ([0, 0, 0, 1, 1, 2, 2, 3, 3, 3, 4, 4, 4, 3, 2, 0], 3),
        "rattail": ([0, 1, 1, 1, 1, 1, 1, 1, 2, 2, 3, 3, 3, 3, 2, 1], 0),
        "eel": ([0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 1, 0], 0),
        "snail": ([0, 0, 0, 1, 1, 2, 2, 3, 3, 3, 3, 3, 3, 3, 2, 0], 3),
        "lobed": ([0, 0, 1, 1, 2, 2, 3, 3, 3, 3, 3, 3, 3, 2, 1, 0], 2),
        "king": ([0, 0, 1, 2, 3, 3, 4, 4, 5, 5, 5, 5, 4, 4, 3, 1], 2),
    }
    cy = 8
    if style == "isopod":
        for x in range(2, 14):
            hh = 4 if 4 <= x <= 11 else 3
            for y in range(cy - hh, cy + hh + 1):
                band = (x % 2 == 0)
                put(x, y, _c(body, -18) if band else body)
            put(x, cy - hh - 1, _c(body, -60)); put(x, cy + hh + 1, _c(body, -60))
        for y in range(cy - 3, cy + 4): put(14, y, _c(body, -60)); put(1, y, _c(body, -60))
        for x in (4, 7, 10): put(x, cy + 6, fin); put(x + 1, cy + 6, fin)
        put(13, cy - 2, (20, 20, 25)); put(13, cy + 2, (20, 20, 25))
        put(15, cy - 3, fin); put(15, cy + 3, fin)
    else:
        prof, tail = shapes[style]
        for x, hh in enumerate(prof):
            if hh == 0: continue
            for y in range(cy - hh, cy + hh + 1):
                put(x, y, belly if y > cy else body)
            put(x, cy - hh - 1, _c(body, -70)); put(x, cy + hh + 1, _c(body, -70))
        # the tail
        if style not in ("eel", "rattail"):
            for k in range(3):
                put(tail - k, cy - 1 - k, fin); put(tail - k, cy + 1 + k, fin); put(tail - k, cy, fin)
                put(tail - k, cy - 2 - k, _c(fin, -60)); put(tail - k, cy + 2 + k, _c(fin, -60))
        else:
            put(0, cy, fin)
        # fins
        top = min(y for (x, y) in px if 6 <= x <= 9)
        for x in range(6, 10 if style != "frill" else 12): put(x, top - 1, fin)
        if style in ("frill", "round"):
            for x in range(6, 11): put(x, top - 2, fin if x % 2 else _c(fin, 30)); put(x, cy + 6, fin)
        if style == "lobed":
            put(7, cy + 4, fin); put(7, cy + 5, fin); put(9, cy + 4, fin); put(9, cy + 5, fin)
        # the eye
        if style == "king":
            put(13, cy - 1, _c(body, -30))  # eyeless: a scar
            for x in range(9, 14): put(x, cy - 5, accent)  # a crown of old hooks
            put(10, cy - 6, accent); put(12, cy - 6, accent)
        elif style == "snail":
            put(13, cy - 1, (30, 30, 35))
        else:
            put(13 if style != "rattail" else 14, cy - 1, (15, 15, 20))
            if style in ("round", "lantern"): put(12, cy - 1, (240, 240, 245))
        if style == "lantern":
            put(15, cy - 4, accent); put(14, cy - 4, _c(body, -40)); put(15, cy - 3, _c(body, -40))
        if style == "slim" and accent:
            for x in range(4, 13, 2): put(x, cy - 1, accent)
        if style == "eel" and accent:
            put(9, cy, accent)  # its heart
        if style == "fish" and accent:
            for x in range(5, 12, 3): put(x, cy, accent)
        if style == "frill" and accent:
            put(10, cy, accent); put(8, cy + 1, accent)
        if style == "lobed" and accent:
            for x in range(5, 12, 2): put(x, cy - 2 + (x % 3), accent)
    for (x, y), c in px.items(): im.putpixel((x, y), tuple(int(max(0, min(255, v))) for v in c[:3]) + (255,))
    return im


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
    # the Brine Scourge's giant bubbles (Java only: item displays)
    for i, (bn, bc) in enumerate((("air_bubble", (200, 235, 255)), ("pink_bubble", (255, 120, 200)))):
        part = bubble(bn, bc)
        img, boxes = W.build(part, 7100 + i)
        img.save(os.path.join(A, "textures/item/wild", bn + ".png"))
        model = java_model(part, boxes)
        model["textures"] = {"t": f"{NS}:item/wild/{bn}", "particle": f"{NS}:item/wild/{bn}"}
        W.jw(os.path.join(A, "models/item/wild", bn + ".json"), model)
        W.jw(os.path.join(A, "items/wild", bn + ".json"), {"model": {"type": "minecraft:model", "model": f"{NS}:item/wild/{bn}"}})
    # the Dark Loot Bag (FaultlineItems DarkLoot, paper + faultline:dark_loot_bag)
    bag = bag_icon()
    W.flat_item(A, "dark_loot_bag", bag)
    mappings["minecraft:paper"].append({"type": "definition", "model": f"{NS}:dark_loot_bag", "bedrock_identifier": "faultline:dark_loot_bag",
        "display_name": "Dark Loot Bag", "bedrock_options": {"icon": bed_icon("dark_loot_bag", bag), "allow_offhand": True},
        "components": {"minecraft:max_stack_size": 64}})
    # 1.9.0: the outer ring's items + the Drowned City's people (heads; they're plain drowned, no model)
    outer_items = {}
    for k, g in OUTER_ICONS.items():
        im = _pix(g)
        outer_items[k] = im
        if k in OUTER_NO_ITEM: continue
        W.flat_item(A, k, im)
        mappings["minecraft:paper"].append({"type": "definition", "model": f"{NS}:{k}", "bedrock_identifier": f"faultline:{k}",
            "display_name": k, "bedrock_options": {"icon": bed_icon(k, im), "allow_offhand": True},
            "components": {"minecraft:max_stack_size": 1 if k.startswith("dome_kit") else 64}})
    for k, g in OUTER_HEADS.items():
        im = _pix(g)
        W.flat_item(A, k + "_icon", im, folder="deep")
        icons[k] = im
        mappings["minecraft:paper"].append({"type": "definition", "model": f"{NS}:deep/{k}_icon", "bedrock_identifier": f"faultline:deep_{k}_egg",
            "display_name": k + " egg", "bedrock_options": {"icon": bed_icon("deep_" + k, im), "allow_offhand": True}, "components": {"minecraft:max_stack_size": 64}})
    # 1.10.0: the Dark Ocean's fish (Fishing.hadalItem: faultline:fish/<id> on cod / salmon / tropical fish)
    fish_icons = {}
    for k, (base, style, body, belly, fin, acc) in FISH.items():
        im = fish_icon(style, body, belly, fin, acc)
        W.flat_item(A, k, im, folder="fish")
        fish_icons["fish_" + k] = im
        mappings.setdefault("minecraft:" + base, []).append({"type": "definition", "model": f"{NS}:fish/{k}", "bedrock_identifier": f"faultline:fish_{k}",
            "display_name": k, "bedrock_options": {"icon": bed_icon("fish_" + k, im), "allow_offhand": True}, "components": {"minecraft:max_stack_size": 64}})
    # Index icons
    index = {"deep_" + k: v for k, v in icons.items()}
    index.update(fish_icons)
    index["dark_loot_bag"] = bag
    index.update(outer_items)
    for k, im in index.items(): im.save(os.path.join(A, "textures/index", k + ".png"))
    W.glyphs(A, index)
    # music for Bedrock (Java gets FaultlineDeepMusic.zip)
    if music:
        sd = {}
        for nm in ("sunken", "abyss", "acid"):
            src = os.path.join(music, nm + ".ogg")
            if not os.path.exists(src): continue
            dst = os.path.join(rp, "sounds/faultline/deep", nm + ".ogg"); os.makedirs(os.path.dirname(dst), exist_ok=True); shutil.copy(src, dst)
            sd[f"faultline:deep.{nm}"] = {"category": "music", "sounds": [{"name": f"sounds/faultline/deep/{nm}", "stream": True}]}
        src = os.path.join(music, "coralia.ogg")   # Coralia's fight (FaultlineBosses plays faultline:coralia.music)
        if os.path.exists(src):
            dst = os.path.join(rp, "sounds/faultline/coralia/music.ogg"); os.makedirs(os.path.dirname(dst), exist_ok=True); shutil.copy(src, dst)
            sd["faultline:coralia.music"] = {"category": "record", "sounds": [{"name": "sounds/faultline/coralia/music", "stream": True}]}
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
