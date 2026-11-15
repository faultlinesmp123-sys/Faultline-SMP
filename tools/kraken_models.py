#!/usr/bin/env python3
"""
Builds the Kraken's models + textures (FaultlineBosses' Kraken class; also the Abyssal Kraken, scaled up):
  kraken_mantle_1 / _2 / _3 / _dead   his head + mantle per phase (2-3: glowing veins; dead: grey)
  kraken_tentacle, kraken_tentacle_tip  one arm segment (16 long along +z) and the curled tip
  kraken_pupil                          the horizontal slit pupil (the plugin slides it across the eye)
  kraken_beak                           one half of his beak

Usage:  python3 tools/kraken_models.py <unpacked-pack-dir> [--preview <dir>]
Needs Pillow + numpy (reuses tools/jacob_models.py's box/atlas/renderer).

The plugin's numbers this keeps: eyes centred on model (0.5, 8, z 24) and (15.5, 8, z 24) (pupils at +-7.5, 0, +16.6 from
the middle); the beak hangs at model (8, -4.5, 14); the siphon on his +x side; the front is +z; coordinates stay in -16..32.
"""
import math, os, random, sys
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import jacob_models as J
from jacob_models import Atlas, box, noise_fill, shade, write_model, write_tex

SKIN = {  # a deep crimson giant (rough, knobbly skin, a paler coral underside); phases 2-3: glowing ember veins
    1: dict(base=(138, 30, 34), light=(196, 72, 64), dark=(78, 14, 20), spot=(58, 10, 16), belly=(214, 128, 110),
            wart=(166, 50, 48), vein=None),
    2: dict(base=(128, 26, 30), light=(186, 64, 56), dark=(70, 12, 18), spot=(50, 8, 14), belly=(204, 118, 100),
            wart=(156, 44, 42), vein=((255, 196, 70), (200, 90, 20)), veins=0.6),
    3: dict(base=(116, 20, 26), light=(174, 54, 48), dark=(62, 8, 14), spot=(44, 6, 10), belly=(194, 106, 90),
            wart=(144, 36, 36), vein=((255, 236, 120), (240, 130, 30)), veins=1.3),
    "dead": dict(base=(110, 114, 118), light=(150, 154, 156), dark=(70, 72, 76), spot=(56, 58, 62), belly=(160, 162, 160),
                 wart=(126, 128, 130), vein=None),
}
EYE = dict(white=(255, 214, 60), ring=(255, 150, 20), rim=(20, 8, 8), lid=(70, 12, 18))


def skin_painter(P, rng):
    def paint(reg, face, wu, hu, belly=False, ridge=False, wart=False):
        h, w = reg.shape[:2]
        if wart:
            noise_fill(reg, P["wart"], rng, 10)
            reg[0, :, :3] = shade(P["wart"], 1.25)
            return
        noise_fill(reg, P["belly"] if (belly or face == "down") else P["base"], rng, 7)
        # soft vertical gradient: lighter at the top of each face (wet sheen), darker underneath
        if face not in ("up", "down"):
            for y in range(h):
                k = 1.12 - 0.3 * (y / max(1, h - 1))
                reg[y, :, :3] = np.clip(reg[y, :, :3] * k, 0, 255)
        if not (belly or face == "down"):
            # mottling: dark blotches with a lighter rim, and pale speckles
            for _ in range(max(1, w * h // 70)):
                cx, cy, r = rng.randrange(w), rng.randrange(h), rng.uniform(0.8, 2.4)
                for y in range(max(0, int(cy - r - 1)), min(h, int(cy + r + 2))):
                    for x in range(max(0, int(cx - r - 1)), min(w, int(cx + r + 2))):
                        d = math.hypot(x - cx, y - cy)
                        if d < r: reg[y, x, :3] = P["spot"]
                        elif d < r + 0.9: reg[y, x, :3] = np.maximum(reg[y, x, :3], np.array(P["dark"], float))
            for _ in range(max(1, w * h // 14)):  # knobbly bumps: a lit top, a shadow under
                x, y = rng.randrange(w), rng.randrange(h)
                reg[y, x, :3] = P["light"]
                if y + 1 < h: reg[y + 1, x, :3] = P["dark"]
        if ridge and face == "up":
            reg[:, w // 2 - 1:w // 2 + 1, :3] = P["dark"]
        if P.get("vein"):  # glowing veins crawling across him (phases 2-3)
            hot, dim = P["vein"]
            for _ in range(max(1, int(w * h / 90 * P["veins"]))):
                x, y = rng.randrange(w), rng.randrange(h)
                for _ in range(rng.randrange(4, 14)):
                    if 0 <= x < w and 0 <= y < h:
                        reg[y, x, :3] = hot
                        if x + 1 < w: reg[y, x + 1, :3] = np.maximum(reg[y, x + 1, :3], np.array(dim, float))
                    x += rng.choice((-1, 0, 1)); y += rng.choice((-1, 0, 1, 1))
    return paint


def eye_paint(reg, face, wu, hu, dead=False):
    h, w = reg.shape[:2]
    reg[:, :, 3] = 255
    reg[:, :, :3] = EYE["rim"]
    if face != "south": return
    cx, cy = (w - 1) / 2, (h - 1) / 2
    for y in range(h):
        for x in range(w):
            d = math.hypot((x - cx) / max(1, w / 2), (y - cy) / max(1, h / 2))
            if d < 0.5: reg[y, x, :3] = (70, 66, 60) if dead else EYE["white"]
            elif d < 0.72: reg[y, x, :3] = (50, 48, 46) if dead else EYE["ring"]
    reg[0:2, :, :3] = EYE["lid"]  # the heavy lid's shadow


def mantle(P, seed):
    atlas = Atlas(512, 2)
    rng = random.Random(seed)
    skin = skin_painter(P, rng)
    els = []
    B = lambda f, t, **kw: els.append(box(atlas, f, t, skin, **kw))
    # (coordinates are offsets from the model's middle (8, 8, 8): x -8 = model x 0)
    # ---- the head: a rounded mass built from overlapping, bevelled boxes
    B((-13, -8, -12), (13, 6, 14))
    B((-14, -6, -11), (14, 4, 13))
    B((-12, -9, -10), (12, 7, 15))
    B((-11, -12, -9), (11, -8, 11), belly=True)               # underside, where the arms root
    # the front "lip" between the eyes, down to the beak
    B((-4, -10, 12), (4, -4, 16), belly=True)
    # ---- the mantle: tiers swept up and BACK, narrowing (the bulb behind his eyes)
    B((-13, 6, -13), (13, 13, 11), ridge=True)
    B((-13.5, 9, -11), (13.5, 16, 8))
    B((-12, 13, -14), (12, 19, 6), ridge=True)
    B((-10, 19, -15), (10, 22, 3), ridge=True)
    B((-7.5, 22, -15), (7.5, 24, -2), ridge=True)             # the dome's crown
    B((-5, 18, -19), (5, 22, -12))                            # the back of the dome
    # fins along the mantle's top-back
    B((-15, 15, -18), (-11, 16, -6))
    B((11, 15, -18), (15, 16, -6))
    B((-16, 15.5, -16), (-14, 16.2, -9))
    B((14, 15.5, -16), (16, 16.2, -9))
    # ---- brow ridges over the eyes, heavy and scowling
    for sgn in (-1, 1):
        x0, x1 = (-13.5, -1.5) if sgn < 0 else (1.5, 13.5)
        e = box(atlas, (x0, 4.0, 11.5), (x1, 6.5, 17.2), skin, ridge=True)
        e["rotation"] = {"angle": 22.5 * (-sgn), "axis": "z", "origin": [8 + (x0 + x1) / 2, 8 + 5.0, 8 + 14]}
        els.append(e)
    # ---- eyes: big sockets proud of his face (centres at x -7.5 / +7.5, y 0, front z 16..16.4)
    for cx in (-7.5, 7.5):
        B((cx - 5.6, -5.0, 11), (cx + 5.6, 5.0, 15.6))            # the socket's swell
        els.append(box(atlas, (cx - 4.5, -4.0, 15.4), (cx + 4.5, 4.0, 16.2), eye_paint, dead=P is SKIN["dead"]))
    # ---- siphon on his +x side (the plugin puffs bubbles from there)
    B((13, -6, -2), (18, -1, 5))
    B((17.5, -5.2, -1), (20.5, -1.8, 4))
    # ---- warts / barnacle-like nodules
    for (x, y, z) in [(-12.5, 0, -6), (12.0, 2, -9), (-9, 9, -12), (8, 12, -14), (-5, 19, -16), (4, 21, -12), (-13.5, -4, 5),
                      (13.5, -5, 8), (0, 22.4, -18), (-10, 14, 0), (9, 15, 2), (-3, 8, 9.5), (5, 9, 8)]:
        r = rng.uniform(0.8, 1.4)
        B((x - r, y - r, z - r), (x + r, y + r, z + r), wart=True)
    # ---- stubs where the arms grow out (eight, round the base)
    for i in range(8):
        a = math.pi * 2 * i / 8 + math.pi / 8
        cx, cz = math.cos(a) * 9.5, math.sin(a) * 9.5 + 1.5
        B((cx - 2.6, -15.5, cz - 2.6), (cx + 2.6, -10, cz + 2.6), belly=True)
    return atlas, els


def tentacle(P, seed):
    atlas = Atlas(512, 4)
    rng = random.Random(seed)
    skin = skin_painter(P, rng)

    def sucker(reg, face, wu, hu):
        h, w = reg.shape[:2]
        noise_fill(reg, (226, 160, 140), rng, 6)
        if face == "down":
            cx, cy = (w - 1) / 2, (h - 1) / 2
            for y in range(h):
                for x in range(w):
                    d = math.hypot(x - cx, y - cy) / (min(w, h) / 2)
                    if d < 0.35: reg[y, x, :3] = (96, 22, 26)
                    elif d < 0.7: reg[y, x, :3] = (178, 96, 88)
                    elif d < 0.85: reg[y, x, :3] = (244, 196, 176)

    seg, tip = [], []
    # a tapered, bevelled segment along +z (0..16), centre (8, 8)
    seg.append(box(atlas, (-3, -3, -8), (3, 3, 8), skin, ridge=True))
    seg.append(box(atlas, (-3.6, -2.4, -7.5), (3.6, 2.4, 7.5), skin))
    seg.append(box(atlas, (-2.4, -3.5, -7.5), (2.4, 3.5, 7.5), skin))
    seg.append(box(atlas, (-2.6, -3.7, -8), (2.6, -3.0, 8), skin, belly=True))     # pale underside
    for z in (-7, -2.5, 2):                                                         # two rows of big suckers
        seg.append(box(atlas, (-2.6, -4.6, z), (-0.2, -3.6, z + 2.4), sucker))
        seg.append(box(atlas, (0.2, -4.6, z + 2.2), (2.6, -3.6, z + 4.6), sucker))
    # the tip: narrowing, then a hooked curl
    tip.append(box(atlas, (-2.2, -2.2, -8), (2.2, 2.2, 2), skin, ridge=True))
    tip.append(box(atlas, (-1.6, -1.6, 2), (1.6, 1.6, 6), skin))
    tip.append(box(atlas, (-1.9, -2.6, -8), (1.9, -2.1, 2), skin, belly=True))
    for z in (-6, -2):
        tip.append(box(atlas, (-1.5, -3.0, z), (1.5, -2.4, z + 1.6), sucker))
    e = box(atlas, (-1.0, -0.4, 5.5), (1.0, 2.4, 8.5), skin)
    e["rotation"] = {"angle": -45, "axis": "x", "origin": [8, 8, 8 + 6]}
    tip.append(e)
    e = box(atlas, (-0.6, 1.6, 6.5), (0.6, 4.0, 7.6), skin)
    e["rotation"] = {"angle": -22.5, "axis": "x", "origin": [8, 8, 8 + 7]}
    tip.append(e)
    return atlas, seg, tip


def bits(seed):
    atlas = Atlas(128, 4)
    rng = random.Random(seed)

    def pupil(reg, face, wu, hu):
        noise_fill(reg, (10, 6, 10), rng, 3)
        if face == "south":
            reg[0, :, :3] = (60, 20, 30)
            reg[:, 0, :3] = (40, 14, 22)

    def beak(reg, face, wu, hu):
        h, w = reg.shape[:2]
        noise_fill(reg, (44, 34, 40), rng, 6)
        for y in range(h):
            reg[y, :, :3] = np.clip(reg[y, :, :3] * (1.25 - 0.5 * y / max(1, h - 1)), 0, 255)
        reg[-1, :, :3] = (16, 12, 14)

    # a horizontal slit (octopus pupil): wide and low, on the eye's surface
    pupil_el = [box(atlas, (-3, -1.4, 0), (3, 1.4, 0.6), pupil)]
    beak_el = [box(atlas, (-4, -4, -4), (4, 0, 4), beak),
               box(atlas, (-2.6, -7, -2.6), (2.6, -4, 2.6), beak),
               box(atlas, (-1.2, -9, -1.2), (1.2, -7, 1.2), beak)]
    return atlas, pupil_el, beak_el


def main():
    pack = sys.argv[1]
    prev = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    for d in ("models/item", "textures/item", "items"): os.makedirs(os.path.join(pack, "assets/faultline", d), exist_ok=True)
    imgs = {}
    for k, P in SKIN.items():
        atlas, els = mantle(P, 31)
        tex = "kraken_skin_" + str(k)
        imgs[k] = (write_tex(pack, tex, atlas), els)
        write_model(pack, "kraken_mantle_" + str(k), tex, els)
    atlas, seg, tip = tentacle(SKIN[1], 37)
    timg = write_tex(pack, "kraken_tentacle", atlas)
    write_model(pack, "kraken_tentacle", "kraken_tentacle", seg)
    write_model(pack, "kraken_tentacle_tip", "kraken_tentacle", tip)
    atlas, pupil_el, beak_el = bits(41)
    bimg = write_tex(pack, "kraken_bits", atlas)
    write_model(pack, "kraken_pupil", "kraken_bits", pupil_el)
    write_model(pack, "kraken_beak", "kraken_bits", beak_el)
    if prev:
        os.makedirs(prev, exist_ok=True)
        J.set_anchor(0.62)
        b = np.asarray(bimg).astype(float)
        sheet = Image.new("RGBA", (300 * 3, 300 * 4))
        for row, k in enumerate((1, 2, 3, "dead")):
            img, els = imgs[k]
            t = np.asarray(img).astype(float)
            placed = [(els, t, (0, 0, 0)), (pupil_el, b, (-7.5, 0, 16.3)), (pupil_el, b, (7.5, 0, 16.3)), (beak_el, b, (0, -12.5, 6))]
            for col, (yw, pt) in enumerate(((25, 12), (160, 15), (90, 5))):
                sheet.paste(J.render(placed, yw, pt, 6, (300, 300)), (col * 300, row * 300))
        sheet.save(os.path.join(prev, "kraken_mantle.png"))
        t = np.asarray(timg).astype(float)
        placed = [(seg, t, (0, 0, -24)), (seg, t, (0, 0, -8)), (tip, t, (0, 0, 8))]
        J.set_anchor(0.5)
        arm = Image.new("RGBA", (800, 300))
        arm.paste(J.render(placed, 70, 25, 7, (400, 300)), (0, 0))
        arm.paste(J.render(placed, 90, -40, 7, (400, 300)), (400, 0))
        arm.save(os.path.join(prev, "kraken_arm.png"))


if __name__ == "__main__":
    main()
