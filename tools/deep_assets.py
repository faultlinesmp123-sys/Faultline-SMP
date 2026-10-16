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
import json, os, sys, zipfile, shutil
import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wild_assets as W                                                          # noqa: E402
from wild_assets import Part, rounded, solid, glow, scales, membrane, stripes_h, teeth, bands   # noqa: E402

NS = "faultline"


def eye(p, x, y, z, r, col=(250, 250, 240), pupil=(16, 16, 20)):
    """A side eye on both flanks at (x, y, +-z), radius r."""
    for s in (-1, 1):
        p.add(x - r, y - r, s * z - (0.02 if s > 0 else 0.06), x + r, y + r, s * z + (0.06 if s > 0 else 0.02), solid(col, 3))
        p.add(x - r * 0.45 + r * 0.25, y - r * 0.45, s * z + (0.05 if s > 0 else -0.08), x + r * 0.45 + r * 0.25, y + r * 0.45,
              s * z + (0.08 if s > 0 else -0.05), solid(pupil, 2))


def fins(p, x0, x1, y, z, w, paint, down=0.0):
    for s in (-1, 1): p.add(x0, y - 0.03 - down, s * z, x1, y + 0.03 - down, s * (z + w), paint)


# --------------------------------------------------------------------------------------------- the Sunlit Sea
def gawker():
    """A round little fish that's mostly eye (Terraria's eyeball fish)."""
    p = Part("gawker", 10, 64)
    body = scales((236, 196, 60), (190, 140, 30))
    rounded(p, -0.3, -0.3, -0.28, 0.32, 0.3, 0.28, body, 0.1)
    for s in (-1, 1):
        p.add(-0.12, -0.2, s * 0.27 - 0.03, 0.26, 0.2, s * 0.27 + 0.03, solid((250, 250, 245), 2))   # the huge eye
        p.add(0.02, -0.08, s * 0.29 - 0.03, 0.18, 0.08, s * 0.29 + 0.03, solid((40, 120, 200), 2))
        p.add(0.07, -0.04, s * 0.31 - 0.02, 0.15, 0.04, s * 0.31 + 0.02, solid((10, 10, 14), 2))
    p.add(-0.62, -0.25, -0.03, -0.28, 0.25, 0.03, membrane((250, 170, 40), (200, 110, 20)))   # tail
    p.add(-0.1, 0.28, -0.02, 0.15, 0.45, 0.02, solid((250, 170, 40)))
    p.add(0.32, -0.08, -0.06, 0.36, 0.0, 0.06, solid((120, 40, 40), 3))
    p.bone_all("b"); p.anim("b", "rot", (0, 10, 0), 0.8)
    return p


def bloatfin():
    """A fish like a bubble with fins: a clear air sac you can pop."""
    p = Part("bloatfin", 10, 64)
    sac = glow((150, 220, 240))
    rounded(p, -0.32, -0.32, -0.32, 0.32, 0.32, 0.32, sac, 0.12)
    p.add(0.3, -0.12, -0.16, 0.46, 0.12, 0.16, solid((90, 160, 190)))
    eye(p, 0.36, 0.05, 0.16, 0.05)
    for (y, z) in ((0.32, 0), (-0.32, 0), (0, 0.32), (0, -0.32), (0.22, 0.22), (-0.22, -0.22), (0.22, -0.22), (-0.22, 0.22)):
        p.add(-0.03, y - 0.03, z - 0.03, 0.03, y + 0.03, z + 0.03, solid((240, 250, 255), 2))   # little spines
    fins(p, -0.05, 0.12, 0.0, 0.32, 0.14, solid((80, 200, 230)))
    p.add(-0.55, -0.12, -0.02, -0.3, 0.12, 0.02, solid((80, 200, 230)))
    p.bone_all("b"); p.anim("b", "pos", (0, 0.6, 0), 1.6)
    return p


def jellywing():
    """A glowing ray as clear as jelly, its spines a siren's comb."""
    p = Part("jellywing", 8, 64)
    jelly = glow((206, 140, 236))
    p.add(-0.25, -0.06, -0.2, 0.3, 0.08, 0.2, jelly)
    p.bone("wl", (0, 0, -0.2)); p.add(-0.2, -0.03, -0.62, 0.2, 0.04, -0.2, membrane((220, 170, 245), (170, 90, 210))); p.bone(None)
    p.bone("wr", (0, 0, 0.2)); p.add(-0.2, -0.03, 0.2, 0.2, 0.04, 0.62, membrane((220, 170, 245), (170, 90, 210))); p.bone(None)
    p.add(-0.95, -0.02, -0.02, -0.25, 0.02, 0.02, solid((180, 110, 220)))     # whip tail
    for x in (-0.15, 0.0, 0.15): p.add(x, 0.08, -0.02, x + 0.05, 0.16, 0.02, solid((255, 240, 250), 2))   # comb spines
    eye(p, 0.22, 0.06, 0.12, 0.03, (40, 20, 60), (250, 230, 255))
    p.anim("wl", "rot", (0, 0, 22), 1.4); p.anim("wr", "rot", (0, 0, -22), 1.4)
    return p


def driftback():
    """A gentle giant with a whole reef growing on its back."""
    p = Part("driftback", 8, 128)
    skin = stripes_h((96, 120, 140), (70, 90, 110), 4)
    rounded(p, -0.45, -0.17, -0.2, 0.42, 0.15, 0.2, skin, 0.06)
    p.add(0.42, -0.14, -0.15, 0.56, 0.1, 0.15, skin)
    p.add(-0.4, -0.2, -0.17, 0.5, -0.16, 0.17, solid((210, 210, 200)))         # pale belly
    eye(p, 0.42, 0.0, 0.15, 0.02)
    reef = [((222, 90, 110), 0.12), ((250, 170, 60), 0.09), ((120, 200, 90), 0.15), ((180, 90, 220), 0.1), ((60, 190, 200), 0.11)]
    for i, (c, h) in enumerate(reef):
        x = -0.32 + i * 0.15
        p.add(x, 0.15, -0.06 + (i % 2) * 0.06, x + 0.06, 0.15 + h, 0.0 + (i % 2) * 0.06, solid(c, 4))
        p.add(x + 0.03, 0.15 + h * 0.6, -0.08 + (i % 2) * 0.06, x + 0.05, 0.15 + h * 0.75, 0.05 + (i % 2) * 0.06, solid(c, 4))
    for x in (-0.2, 0.15): p.add(x, 0.15, 0.08, x + 0.03, 0.42, 0.11, solid((40, 130, 60), 4))   # kelp
    p.bone("fl", (0.15, -0.1, -0.2)); p.add(0.0, -0.14, -0.45, 0.25, -0.1, -0.2, solid((80, 100, 120))); p.bone(None)
    p.bone("fr", (0.15, -0.1, 0.2)); p.add(0.0, -0.14, 0.2, 0.25, -0.1, 0.45, solid((80, 100, 120))); p.bone(None)
    p.bone("tail", (-0.45, 0, 0)); p.add(-0.68, -0.04, -0.25, -0.45, 0.03, 0.25, solid((80, 100, 120))); p.bone(None)
    p.anim("fl", "rot", (0, 0, 12), 3.0); p.anim("fr", "rot", (0, 0, -12), 3.0); p.anim("tail", "rot", (14, 0, 0), 3.0)
    return p


def clam():
    """Sits shut on the sea floor."""
    p = Part("clam", 10, 64)
    def ribbed(c):
        def f(reg, face, rng):
            W._fill(reg, c, rng, 6)
            for x in range(reg.shape[1]):
                if x % 3 == 0: reg[:, x, :3] = np.clip(np.array(c) - 25, 0, 255)
        return f
    p.add(-0.42, -0.3, -0.4, 0.42, -0.05, 0.4, ribbed((200, 180, 156)))
    p.add(-0.42, -0.06, -0.4, 0.42, 0.18, 0.4, ribbed((214, 196, 172)))
    p.add(-0.3, 0.17, -0.28, 0.3, 0.26, 0.28, ribbed((226, 210, 186)))
    p.add(0.4, -0.08, -0.36, 0.44, -0.02, 0.36, solid((220, 120, 140), 3))      # a pink lip
    return p


def lumenbell():
    """A ghostly bell of a jellyfish."""
    p = Part("lumenbell", 8, 64)
    bell = glow((110, 160, 255))
    rounded(p, -0.3, 0.0, -0.3, 0.3, 0.32, 0.3, bell, 0.1)
    p.add(-0.2, 0.3, -0.2, 0.2, 0.4, 0.2, bell)
    p.add(-0.32, -0.04, -0.32, 0.32, 0.02, 0.32, solid((170, 210, 255), 3))
    p.bone("t", (0, 0, 0))
    for (x, z) in ((-0.2, -0.2), (0.2, -0.2), (-0.2, 0.2), (0.2, 0.2), (0, 0), (0.0, -0.25), (0.0, 0.25)):
        p.add(x - 0.015, -0.6, z - 0.015, x + 0.015, 0.0, z + 0.015, solid((200, 230, 255), 2))   # trailing threads
    p.bone(None)
    p.anim("t", "rot", (8, 0, 8), 2.0)
    p.anim("t", "pos", (0, 0.5, 0), 2.0, 0.25)
    return p


def gasbloom():
    """A slow, swollen floater full of poison gas."""
    p = Part("gasbloom", 8, 64)
    sk = scales((110, 150, 60), (70, 100, 30))
    rounded(p, -0.35, -0.3, -0.35, 0.35, 0.35, 0.35, sk, 0.14)
    for (x, y, z) in ((0.25, 0.2, 0.25), (-0.25, 0.25, -0.2), (0.1, -0.25, -0.3), (-0.2, -0.1, 0.32), (0.32, -0.05, -0.1)):
        p.add(x - 0.08, y - 0.08, z - 0.08, x + 0.08, y + 0.08, z + 0.08, glow((190, 230, 60)))   # gas pods
    eye(p, 0.3, 0.1, 0.2, 0.05, (230, 230, 120), (40, 30, 10))
    p.add(-0.03, -0.55, -0.03, 0.03, -0.3, 0.03, solid((80, 110, 40)))
    p.bone_all("b"); p.anim("b", "pos", (0, 0.8, 0), 3.2)
    return p


def shark(name, skin, dark, belly, teeth_col=(240, 236, 220), dorsal=0.32, stripes=False):
    p = Part(name, 8, 128)
    sk = stripes_h(skin, dark, 3) if stripes else scales(skin, dark)
    rounded(p, -0.4, -0.14, -0.14, 0.32, 0.14, 0.14, sk, 0.05)
    p.add(0.32, -0.11, -0.1, 0.55, 0.1, 0.1, sk)
    p.add(-0.38, -0.16, -0.11, 0.5, -0.12, 0.11, solid(belly))
    p.add(0.36, -0.1, -0.08, 0.54, -0.07, 0.08, solid((90, 20, 30), 3))         # mouth
    for x in (0.38, 0.43, 0.48): p.add(x, -0.08, -0.08, x + 0.025, -0.04, 0.08, solid(teeth_col, 2))
    eye(p, 0.44, 0.04, 0.1, 0.022, (20, 20, 20), (200, 40, 40))
    p.add(-0.1, 0.14, -0.02, 0.12, 0.14 + dorsal, 0.02, solid(dark))           # dorsal fin
    fins(p, 0.05, 0.22, -0.1, 0.14, 0.18, solid(dark), 0.04)
    p.bone("tail", (-0.4, 0, 0))
    p.add(-0.62, -0.06, -0.06, -0.4, 0.06, 0.06, sk)
    p.add(-0.8, 0.0, -0.02, -0.6, 0.3, 0.02, solid(dark))
    p.add(-0.75, -0.2, -0.02, -0.6, 0.0, 0.02, solid(dark))
    p.bone(None)
    p.anim("tail", "rot", (0, 18, 0), 0.9)
    return p


def fangstalker():
    """A lean shark with a jaw full of hooked teeth."""
    return shark("fangstalker", (120, 128, 140), (80, 86, 100), (210, 210, 210))


def blastfin():
    """A spiky little fish full of volatile gas."""
    p = Part("blastfin", 10, 64)
    sk = scales((240, 150, 40), (190, 90, 20))
    rounded(p, -0.28, -0.26, -0.26, 0.3, 0.26, 0.26, sk, 0.1)
    for y in (-0.3, 0.3):
        for z in (-0.18, 0.18): p.add(-0.03, y - 0.06, z - 0.03, 0.03, y + 0.06, z + 0.03, solid((255, 230, 120), 2))
    for x in (-0.15, 0.1):
        for s in (-1, 1): p.add(x - 0.03, -0.03, s * 0.3 - 0.06, x + 0.03, 0.03, s * 0.3 + 0.06, solid((255, 230, 120), 2))
    p.add(-0.05, -0.08, -0.27, 0.05, 0.08, 0.27, glow((255, 80, 40)))           # glowing belly band
    eye(p, 0.22, 0.08, 0.24, 0.05)
    p.add(-0.5, -0.15, -0.02, -0.26, 0.15, 0.02, solid((250, 110, 30)))
    p.bone_all("b"); p.anim("b", "rot", (0, 0, 8), 0.6)
    return p


def burrowshark():
    """Lies buried in the sand with only its fin showing."""
    return shark("burrowshark", (200, 170, 120), (150, 60, 50), (230, 214, 180), dorsal=0.4, stripes=True)


# --------------------------------------------------------------------------------------------- the caves
def cavecrawler():
    """A spindly crab that scuttles over cave walls."""
    p = Part("cavecrawler", 10, 64)
    sh = scales((190, 60, 40), (130, 30, 20))
    rounded(p, -0.25, -0.08, -0.3, 0.25, 0.12, 0.3, sh, 0.06)
    for s in (-1, 1):
        p.add(0.2, 0.12, s * 0.1 - 0.03, 0.26, 0.24, s * 0.1 + 0.03, solid((240, 230, 120), 2))   # eye stalks
        for i in range(3):
            x = -0.15 + i * 0.13
            p.add(x - 0.02, -0.06, s * 0.3, x + 0.02, 0.0, s * 0.52, solid((160, 50, 30)))
            p.add(x - 0.02, -0.3, s * 0.5, x + 0.02, -0.02, s * 0.54, solid((160, 50, 30)))
        p.add(0.25, -0.02, s * 0.2, 0.42, 0.04, s * 0.26, sh)                   # claws
        p.add(0.4, -0.06, s * 0.14, 0.56, 0.08, s * 0.3, sh)
    return p


def glowgrub():
    """A soft grub that glows."""
    p = Part("glowgrub", 10, 64)
    for i in range(5):
        x = -0.4 + i * 0.17
        r = 0.12 + 0.04 * min(i, 4 - i)
        p.add(x, -0.14, -r, x + 0.17, -0.14 + r * 1.6, r, glow((250, 230, 110)) if i % 2 else solid((220, 200, 120), 4))
    eye(p, 0.36, 0.05, 0.12, 0.025, (30, 30, 20), (250, 250, 250))
    p.add(0.4, 0.12, -0.04, 0.44, 0.24, -0.01, solid((200, 180, 100)))
    p.add(0.4, 0.12, 0.01, 0.44, 0.24, 0.04, solid((200, 180, 100)))
    p.bone_all("b"); p.anim("b", "rot", (0, 6, 0), 1.2)
    return p


def shellsnake():
    """Coils inside cracks in the rock: all teeth and armour plates."""
    p = Part("shellsnake", 8, 64)
    pl = scales((40, 130, 130), (20, 70, 80))
    p.add(0.15, -0.12, -0.12, 0.5, 0.12, 0.12, pl)                             # head
    p.add(0.5, -0.1, -0.08, 0.6, -0.04, 0.08, solid((90, 20, 30), 3))
    for x in (0.5, 0.54): p.add(x, -0.06, -0.08, x + 0.02, 0.0, 0.08, teeth)
    eye(p, 0.4, 0.05, 0.12, 0.025, (250, 230, 60), (10, 10, 10))
    for i in range(5):
        x = 0.15 - (i + 1) * 0.15
        r = 0.1 - i * 0.01
        p.add(x, -r, -r, x + 0.16, r, r, pl)
        p.add(x + 0.02, r - 0.01, -r * 0.6, x + 0.13, r + 0.04, r * 0.6, solid((180, 200, 190), 3))   # plates
    p.bone_all("b"); p.anim("b", "rot", (0, 9, 0), 1.0)
    return p


# --------------------------------------------------------------------------------------------- the Abyss
def lampsquid():
    """A squid with lanterns for eyes."""
    p = Part("lampsquid", 8, 64)
    sk = scales((40, 70, 110), (20, 40, 70))
    rounded(p, -0.5, -0.12, -0.14, 0.05, 0.16, 0.14, sk, 0.05)                 # mantle (points back)
    p.add(-0.65, -0.05, -0.2, -0.45, 0.0, 0.2, solid((60, 100, 150)))
    for s in (-1, 1):
        p.add(0.02, -0.04, s * 0.14 - 0.04, 0.12, 0.08, s * 0.14 + 0.04, glow((120, 255, 240)))   # lantern eyes
    p.bone("arms", (0.05, 0, 0))
    for (y, z) in ((-0.08, -0.08), (-0.08, 0.08), (0.06, -0.08), (0.06, 0.08), (0.0, 0.0)):
        p.add(0.05, y - 0.025, z - 0.025, 0.55, y + 0.025, z + 0.025, solid((70, 100, 150)))
    p.bone(None)
    p.anim("arms", "rot", (0, 8, 8), 1.4)
    return p


def volteel():
    """A long eel crackling with power."""
    p = Part("volteel", 8, 64)
    sk = bands((230, 210, 60), (60, 50, 20), 3)
    for i in range(6):
        x = 0.4 - i * 0.17
        r = 0.08 - i * 0.008
        p.add(x - 0.17, -r, -r, x, r, r, sk)
    p.add(0.4, -0.07, -0.07, 0.55, 0.06, 0.07, sk)
    eye(p, 0.48, 0.03, 0.07, 0.02, (120, 220, 255), (255, 255, 255))
    for i in range(3): p.add(0.2 - i * 0.3, 0.07, -0.01, 0.3 - i * 0.3, 0.12, 0.01, glow((150, 230, 255)))   # sparks
    p.bone_all("b"); p.anim("b", "rot", (0, 12, 0), 0.9)
    return p


def needlejaw():
    """A black fish with a lure of light and teeth like needles."""
    p = Part("needlejaw", 10, 64)
    sk = solid((24, 18, 30), 5)
    rounded(p, -0.3, -0.22, -0.2, 0.25, 0.22, 0.2, sk, 0.08)
    p.add(0.25, -0.2, -0.17, 0.38, 0.0, 0.17, sk)                                 # underbite jaw
    p.add(0.25, -0.02, -0.15, 0.36, 0.0, 0.15, solid((80, 20, 40), 3))
    for z in (-0.12, -0.05, 0.02, 0.09):
        p.add(0.32, -0.02, z, 0.35, 0.14, z + 0.02, solid((240, 240, 250), 2))   # needle teeth
    eye(p, 0.15, 0.08, 0.2, 0.03, (180, 120, 255), (250, 250, 255))
    p.bone("lure", (0.1, 0.22, 0))
    p.add(0.08, 0.22, -0.015, 0.11, 0.48, 0.015, sk)
    p.add(0.11, 0.45, -0.015, 0.3, 0.48, 0.015, sk)
    p.add(0.28, 0.38, -0.05, 0.38, 0.48, 0.05, glow((200, 160, 255)))
    p.bone(None)
    p.add(-0.5, -0.15, -0.02, -0.28, 0.15, 0.02, sk)
    p.anim("lure", "rot", (0, 0, 8), 1.8)
    return p


def devilfish():
    """Wears a mask of bone that turns most hits aside."""
    p = Part("devilfish", 8, 64)
    sk = scales((130, 30, 40), (80, 10, 20))
    rounded(p, -0.4, -0.18, -0.16, 0.25, 0.2, 0.16, sk, 0.06)
    p.add(0.22, -0.2, -0.18, 0.36, 0.22, 0.18, solid((230, 220, 190), 4))      # the bone mask
    for s in (-1, 1):
        p.add(0.34, 0.05, s * 0.1 - 0.035, 0.37, 0.12, s * 0.1 + 0.035, solid((20, 0, 0), 2))
        p.add(0.35, 0.07, s * 0.1 - 0.015, 0.375, 0.1, s * 0.1 + 0.015, glow((255, 60, 40)))
        p.add(0.15, 0.18, s * 0.12, 0.28, 0.4, s * 0.16, solid((230, 220, 190), 4))   # horns
    p.add(0.34, -0.15, -0.1, 0.38, -0.05, 0.1, solid((20, 0, 0), 2))
    p.add(-0.2, 0.2, -0.02, 0.15, 0.36, 0.02, solid((80, 10, 20)))
    p.bone("tail", (-0.4, 0, 0)); p.add(-0.65, -0.18, -0.02, -0.38, 0.18, 0.02, solid((80, 10, 20))); p.bone(None)
    p.anim("tail", "rot", (0, 16, 0), 1.0)
    return p


def riftwarper():
    """It blinks through the water."""
    p = Part("riftwarper", 8, 64)
    sk = glow((180, 90, 220))
    rounded(p, -0.3, -0.14, -0.12, 0.3, 0.14, 0.12, solid((60, 20, 90), 4), 0.05)
    p.add(-0.2, -0.05, -0.13, 0.2, 0.05, 0.13, sk)                                # rift stripe
    eye(p, 0.22, 0.05, 0.12, 0.025, (255, 200, 255), (60, 0, 80))
    for i, (y0, y1) in enumerate(((0.14, 0.42), (-0.42, -0.14))):
        p.add(-0.15, y0, -0.015, 0.15, y1, 0.015, membrane((140, 60, 200), (230, 150, 255)))
    for s in (-1, 1): p.add(-0.1, -0.02, s * 0.12, 0.1, 0.02, s * 0.45, membrane((140, 60, 200), (230, 150, 255)))
    p.add(-0.55, -0.2, -0.015, -0.28, 0.2, 0.015, sk)
    p.bone_all("b"); p.anim("b", "pos", (0, 0, 0.8), 0.7)
    return p


def hollowkin():
    """Shadows of children, drifting in the dark."""
    p = Part("hollowkin", 8, 64)
    sh = solid((28, 28, 36), 4)
    p.add(-0.12, -0.15, -0.16, 0.12, 0.25, 0.16, sh)                              # body
    p.add(-0.14, -0.45, -0.18, 0.14, -0.12, 0.18, W.cloth((34, 34, 44)))         # a ragged hem
    p.add(-0.14, 0.25, -0.14, 0.14, 0.52, 0.14, sh)                               # head
    for s in (-1, 1):
        p.add(0.13, 0.36, s * 0.06 - 0.03, 0.15, 0.42, s * 0.06 + 0.03, glow((240, 240, 255)))   # empty white eyes
        p.add(-0.05, -0.05, s * 0.16, 0.05, 0.22, s * 0.22, sh)                   # arms
    p.bone_all("b"); p.anim("b", "pos", (0, 0.8, 0), 2.6)
    return p


def sharkquid():
    """Half shark, half squid, all hunger."""
    p = shark("sharkquid", (50, 80, 150), (30, 50, 100), (180, 200, 230))
    p.bone("arms", (-0.62, 0, 0))
    for (y, z) in ((-0.05, -0.06), (-0.05, 0.06), (0.05, -0.06), (0.05, 0.06)):
        p.add(-1.0, y - 0.02, z - 0.02, -0.62, y + 0.02, z + 0.02, solid((70, 110, 190)))
    p.bone(None)
    p.anim("arms", "rot", (0, 10, 10), 1.2)
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
        W.jw(os.path.join(A, "models/item/deep", n + ".json"), java_model(part, boxes))
        W.jw(os.path.join(A, "items/deep", n + ".json"), {"model": {"type": "minecraft:model", "model": f"{NS}:item/deep/{n}"}})
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
