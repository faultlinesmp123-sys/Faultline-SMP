#!/usr/bin/env python3
"""
The Lost Explorer (the final boss; inspired by Deltarune's Roaring Knight): an enormous knight in solid black plate.
His face can't be seen at all: there's only darkness inside the helm.

Writes into an unpacked FaultlineSMP pack:
  explorer_{head,body,arm_r,arm_l,leg_r,leg_l}   the 6-piece rig (same joints as Jacob/Don: renderRig draws it)
  explorer_blade                                  his greatsword (black, with white edges)
Usage: python3 tools/explorer_models.py <unpacked-pack-dir> [--preview <dir>]
"""
import os, random, sys
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from jacob_models import Atlas, box, noise_fill, write_model, write_tex, render, set_anchor, preview, JOINT  # noqa: E402

# Almost black: just enough light on the edges for the shapes to read in the dark.
PAL = dict(base=(25, 25, 32), light=(66, 66, 82), dark=(8, 8, 12), hi=(104, 104, 128),
           trim=(42, 42, 54), trim_hi=(84, 84, 104),
           void=(3, 3, 5),
           cape=(15, 14, 19), cape_hi=(32, 30, 40), cape_dark=(6, 6, 8),
           white=(236, 236, 244), white_dim=(150, 150, 168))


def explorer(seed):
    atlas = Atlas(1024, 4)
    rng = random.Random(seed)
    P = PAL

    def plate(reg, face, wu, hu, ridge=False, rivets=False, lames=0, edge=True):
        """Black plate: a soft top-down falloff, a thin cold highlight along the top/left edge, deep shadow bottom/right."""
        h, w = reg.shape[:2]
        noise_fill(reg, P["base"], rng, 3)
        for y in range(h):
            reg[y, :, :3] = np.clip(reg[y, :, :3] * (1.12 - 0.3 * y / max(1, h - 1)), 0, 255)
        if edge and w > 2 and h > 2:
            reg[0, :, :3] = P["light"]; reg[:, 0, :3] = P["trim_hi"]
            reg[-1, :, :3] = P["dark"]; reg[:, -1, :3] = P["dark"]
        if ridge and face in ("south", "north", "up"):  # a raised center ridge catching the light
            m = w // 2
            reg[:, m, :3] = P["hi"]; reg[:, m - 1, :3] = P["light"]
        if lames:
            for y in range(lames, h, lames):
                reg[y, :, :3] = P["dark"]
                if y + 1 < h: reg[y + 1, :, :3] = P["trim"]
        if rivets and face in ("south", "east", "west", "north") and w > 6 and h > 6:
            for x in (2, w - 3):
                for y in (2, h - 3):
                    reg[y, x, :3] = P["trim_hi"]
        for _ in range(int(w * h * 0.004)):  # a few cold glints
            reg[rng.randrange(1, max(2, h - 1)), rng.randrange(1, max(2, w - 1)), :3] = P["hi"]

    def trim(reg, face, wu, hu):
        noise_fill(reg, P["trim"], rng, 3)
        reg[0, :, :3] = P["trim_hi"]; reg[-1, :, :3] = P["dark"]

    def void(reg, face, wu, hu):
        reg[:, :, :3] = P["void"]; reg[:, :, 3] = 255

    def spike(reg, face, wu, hu):
        noise_fill(reg, P["base"], rng, 3)
        reg[:, 0, :3] = P["light"]; reg[:, -1, :3] = P["dark"]
        if face == "up": reg[:, :, :3] = P["hi"]

    def cape(reg, face, wu, hu):
        h, w = reg.shape[:2]
        noise_fill(reg, P["cape"], rng, 4)
        for x in range(0, w, 5): reg[:, x, :3] = P["cape_dark"]     # heavy folds
        for x in range(2, w, 5): reg[:, x, :3] = P["cape_hi"]
        reg[0:2, :, :3] = P["trim"]
        if face in ("north", "south", "east", "west"):              # torn, ragged hem
            for x in range(w):
                cut = rng.randrange(0, max(2, h // 4))
                if rng.random() < 0.35: cut += rng.randrange(2, max(3, h // 3))
                for y in range(h - cut, h): reg[y, x, 3] = 0

    def helm(reg, face, wu, hu):
        plate(reg, face, wu, hu, ridge=face in ("up", "north"))
        if face != "south": return
        h, w = reg.shape[:2]
        # the face: a tall pointed arch of pure darkness, rimmed with a raised edge. Nothing inside.
        top, bot = int(h * 0.12), int(h * 0.94)
        for y in range(top, bot):
            f = (y - top) / max(1, bot - top)
            half = int((w * 0.30) * min(1, 0.45 + f * 1.6))  # pointed at the top, widening down
            m = w // 2
            reg[y, m - half:m + half, :3] = P["void"]
            if m - half - 1 >= 0: reg[y, m - half - 1, :3] = P["hi"]
            if m + half < w: reg[y, m + half, :3] = P["light"]
        reg[top - 1 if top > 0 else 0, w // 2 - 1:w // 2 + 1, :3] = P["hi"]

    parts = {}

    def tilt(e, axis, angle, origin):
        """Angle an element (Minecraft allows -45..45 in 22.5 steps) about a point given in offsets from the joint."""
        e["rotation"] = {"origin": [8 + origin[0], 8 + origin[1], 8 + origin[2]], "axis": axis, "angle": angle}
        return e

    # ---------------- head: a tall pointed great helm, a spire, two swept-back horns, the void where a face should be
    head = [
        box(atlas, (-3.8, 0.0, -3.8), (3.8, 8.8, 3.8), helm),
        box(atlas, (-4.1, -0.3, -4.1), (4.1, 0.9, 4.1), trim),                     # lower rim
        box(atlas, (-3.0, 8.8, -3.0), (3.0, 10.2, 3.0), plate, ridge=True),        # the helm narrows...
        box(atlas, (-2.0, 10.2, -2.0), (2.0, 11.6, 2.0), plate, ridge=True),
        box(atlas, (-1.1, 11.6, -1.1), (1.1, 13.2, 1.1), spike),
        box(atlas, (-0.45, 13.2, -0.45), (0.45, 15.4, 0.45), spike),               # ...into a spire
        box(atlas, (-4.3, 0.0, -4.5), (4.3, 3.2, -3.6), plate),                    # neck guard at the back
    ]
    for s in (-1, 1):  # horns: out of the temples, then one long sweep up and back
        head.append(box(atlas, (min(s * 3.8, s * 5.2), 5.4, -0.9), (max(s * 3.8, s * 5.2), 7.0, 0.9), spike))
        head.append(tilt(box(atlas, (min(s * 4.4, s * 5.4), 6.2, -0.7), (max(s * 4.4, s * 5.4), 13.0, 0.7), spike), "x", -45, (s * 4.9, 6.6, 0)))
        head.append(tilt(box(atlas, (min(s * 4.6, s * 5.2), 12.0, -0.4), (max(s * 4.6, s * 5.2), 15.0, 0.4), spike), "x", -45, (s * 4.9, 6.6, 0)))
    parts["head"] = head

    # ---------------- body: a broad breastplate tapering to the waist, a high collar, faulds, and a long torn cape
    body = [
        box(atlas, (-3.9, 0.0, -2.3), (3.9, 7.0, 2.3), plate),                              # waist
        box(atlas, (-4.9, 6.2, -2.9), (4.9, 12.5, 2.9), plate, ridge=True, rivets=True),    # breastplate
        box(atlas, (-4.3, 3.0, -2.65), (4.3, 6.2, 2.65), plate, lames=3, edge=False),       # faulds
        box(atlas, (-4.4, 1.6, -2.75), (4.4, 2.9, 2.75), trim),                             # belt
        box(atlas, (-0.8, 1.5, 2.75), (0.8, 3.0, 3.05), trim),                              # buckle
        box(atlas, (-4.0, -3.6, 2.2), (-0.3, 1.6, 2.9), plate),                             # tassets
        box(atlas, (0.3, -3.6, 2.2), (4.0, 1.6, 2.9), plate),
        box(atlas, (-4.5, -3.2, -2.6), (-3.9, 1.6, 2.4), plate),                            # side tassets
        box(atlas, (3.9, -3.2, -2.6), (4.5, 1.6, 2.4), plate),
        # the high collar stands up around the helm (lower in front so the void stays visible)
        box(atlas, (-3.4, 12.4, -3.4), (3.4, 15.0, -2.6), plate),                           # back
        box(atlas, (-3.8, 12.4, -3.4), (-3.0, 14.4, 2.6), plate),                           # sides
        box(atlas, (3.0, 12.4, -3.4), (3.8, 14.4, 2.6), plate),
        box(atlas, (-3.0, 12.4, 2.2), (3.0, 13.2, 3.0), trim),                              # front lip
        box(atlas, (-5.4, -9.5, -3.9), (5.4, 12.6, -3.0), cape),                            # the long cape
        box(atlas, (-5.8, 11.6, -4.1), (5.8, 12.8, -2.8), trim),                            # cape clasp bar
    ]
    parts["body"] = body

    # ---------------- arms: huge layered pauldrons swept up and out, a heavy vambrace, clawed gauntlets (mirrored)
    def arm(side):
        o = side  # +1 = his left (+X), -1 = his right
        def X(a, b): return (min(a * o, b * o), max(a * o, b * o))
        el = [
            box(atlas, (-1.8, -11.6, -1.8), (1.8, 0.4, 1.8), plate),                       # arm
            box(atlas, (-2.0, -7.0, -2.0), (2.0, -5.6, 2.0), trim),                        # elbow cop
            box(atlas, (-2.3, -12.4, -2.3), (2.3, -8.0, 2.3), plate),                      # gauntlet
        ]
        x0, x1 = X(-2.6, 4.0)
        el.append(box(atlas, (x0, -3.0, -3.3), (x1, 1.2, 3.3), plate, lames=4))           # pauldron, wider on the outside
        x0, x1 = X(-2.2, 4.4)
        el.append(box(atlas, (x0, 1.2, -3.0), (x1, 2.8, 3.0), plate, ridge=True))         # its top plate
        # three long spikes raking up and outward
        for zc, ln, x in ((-1.6, 5.0, 2.2), (0.0, 7.0, 2.8), (1.6, 5.0, 2.2)):
            x0, x1 = X(x - 0.6, x + 0.6)
            el.append(tilt(box(atlas, (x0, 2.6, zc - 0.55), (x1, 2.6 + ln, zc + 0.55), spike), "z", -22.5 * o, (o * x, 2.6, zc)))
        for i in range(4):                                                                 # claws
            x = -1.65 + i * 1.1
            el.append(box(atlas, (x, -13.8, 1.0), (x + 0.6, -12.4, 2.0), spike))
        return el

    parts["arm_r"], parts["arm_l"] = arm(-1), arm(1)

    # ---------------- legs: plated, a spiked knee, pointed sabatons
    def leg():
        return [
            box(atlas, (-2.0, -12.0, -2.0), (2.0, 0.0, 2.0), plate),
            box(atlas, (-2.25, -6.2, -2.25), (2.25, -1.0, 2.25), plate),                   # cuisse
            box(atlas, (-1.6, -7.6, 1.9), (1.6, -5.2, 2.8), trim),                          # knee cop
            box(atlas, (-0.5, -7.0, 2.8), (0.5, -5.8, 3.8), spike),                         # knee spike
            box(atlas, (-2.2, -9.6, -2.2), (2.2, -7.6, 2.2), plate),                       # greave
            box(atlas, (-2.4, -12.0, -2.4), (2.4, -9.6, 2.4), plate),                      # sabaton
            box(atlas, (-1.9, -12.0, 2.4), (1.9, -10.8, 3.6), plate),                      # pointed toe
            box(atlas, (-0.9, -12.0, 3.6), (0.9, -11.3, 4.6), spike),
        ]
    parts["leg_r"], parts["leg_l"] = leg(), leg()
    return atlas, parts


# ---------------- his greatsword: a long black blade with white edges and a white fuller, a spiked crossguard
BLADE_DISPLAY = {"fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
                 "gui": {"rotation": [0, 0, -45], "translation": [0, 0, 0], "scale": [0.5, 0.5, 0.5]},
                 "thirdperson_righthand": {"rotation": [0, 90, 0], "translation": [0, 6, 1], "scale": [0.6, 0.6, 0.6]},
                 "firstperson_righthand": {"rotation": [0, 90, 20], "translation": [1, 4, 1], "scale": [0.5, 0.5, 0.5]}}


def blade(seed):
    atlas = Atlas(256, 4); rng = random.Random(seed); P = PAL

    def steel(reg, face, wu, hu):
        h, w = reg.shape[:2]
        noise_fill(reg, (18, 18, 24), rng, 3)
        if face in ("north", "south"):
            reg[:, 0, :3] = P["white"]; reg[:, -1, :3] = P["white"]             # edges
            if w > 4: reg[:, 1, :3] = P["white_dim"]; reg[:, -2, :3] = P["white_dim"]
            m = w // 2; reg[:, m, :3] = P["white_dim"]                           # fuller
        else:
            reg[:, :, :3] = P["white"]                                           # the edge itself

    def guard(reg, face, wu, hu):
        noise_fill(reg, P["base"], rng, 3); reg[0, :, :3] = P["light"]; reg[-1, :, :3] = P["dark"]

    def grip(reg, face, wu, hu):
        noise_fill(reg, (12, 11, 14), rng, 3)
        for y in range(0, reg.shape[0], 2): reg[y, :, :3] = (30, 28, 36)

    def tip(reg, face, wu, hu):
        reg[:, :, :3] = P["white"]; reg[:, :, 3] = 255

    # in item space: the handle at the bottom (y = -14), the blade up to y = 31; flat along z
    el = [
        box(atlas, (-1.1, -15.0, -1.1), (1.1, -13.2, 1.1), guard),       # pommel
        box(atlas, (-0.7, -13.2, -0.7), (0.7, -6.2, 0.7), grip),         # grip
        box(atlas, (-5.4, -6.2, -1.2), (5.4, -4.6, 1.2), guard),         # crossguard
        box(atlas, (-6.6, -5.9, -0.9), (-5.4, -3.2, 0.9), guard),        # its swept tips
        box(atlas, (5.4, -5.9, -0.9), (6.6, -3.2, 0.9), guard),
        box(atlas, (-2.6, -4.6, -0.9), (2.6, -3.2, 0.9), guard),         # ricasso collar
        box(atlas, (-2.2, -3.2, -0.5), (2.2, 24.0, 0.5), steel),         # the blade: broad and heavy
        box(atlas, (-1.6, 24.0, -0.45), (1.6, 27.0, 0.45), steel),       # tapering...
        box(atlas, (-1.0, 27.0, -0.4), (1.0, 29.4, 0.4), steel),
        box(atlas, (-0.45, 29.4, -0.3), (0.45, 31.4, 0.3), tip),         # ...to a white point
    ]
    return atlas, el


def main():
    pack = sys.argv[1]
    prev = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    for d in ("models/item", "textures/item", "items"): os.makedirs(os.path.join(pack, "assets/faultline", d), exist_ok=True)
    atlas, parts = explorer(41)
    img = write_tex(pack, "explorer_armor", atlas)
    for part, el in parts.items(): write_model(pack, f"explorer_{part}", "explorer_armor", el)
    batlas, bel = blade(42)
    bimg = write_tex(pack, "explorer_blade", batlas)
    write_model(pack, "explorer_blade", "explorer_blade", bel, BLADE_DISPLAY)
    if prev:
        os.makedirs(prev, exist_ok=True)
        set_anchor(0.97)
        preview(prev, "explorer", parts, img, JOINT, [(20, 8), (160, 8), (-40, 20), (90, 4)], 11, (380, 640))
        im = np.asarray(Image.open(os.path.join(prev, "explorer.png")).convert("RGBA")).copy()
        bg = (im[:, :, 0] == 34) & (im[:, :, 1] == 38) & (im[:, :, 2] == 46)
        im[bg, :3] = (150, 156, 168)
        Image.fromarray(im).save(os.path.join(prev, "explorer_light.png"))
        set_anchor(0.62)
        preview(prev, "explorer_blade", {"b": bel}, bimg, {"b": (0, 0, 0)}, [(20, 10), (90, 10)], 7, (260, 420))


if __name__ == "__main__":
    main()
