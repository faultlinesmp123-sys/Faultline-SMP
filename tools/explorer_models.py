#!/usr/bin/env python3
"""
The Lost Explorer (the final boss; inspired by Deltarune's Roaring Knight, detailed like Elden Ring's Godfrey):
an enormous knight in solid black plate. Every plate is engraved with scrollwork, a circlet of points rings his helm,
a wild black mane spills down his back, a fur mantle sits on his shoulders, and a tiger medallion is on his chest.
His face can't be seen at all: there's only darkness inside the helm.
Phase 2 brings his black tiger, who sits behind him.

Writes into an unpacked FaultlineSMP pack:
  explorer_{head,body,arm_r,arm_l,leg_r,leg_l}   the 6-piece rig (same joints as Jacob/Don: renderRig draws it)
  explorer_axe                                    his great axe (double crescent heads, white edges)
  explorer_blade                                  his greatsword (black, with white edges)
  explorer_tiger_{body,head,jaw,tail}             the black tiger, sitting (joints in TIGER_JOINT)
Usage: python3 tools/explorer_models.py <unpacked-pack-dir> [--preview <dir>]
"""
import math, os, random, sys
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from jacob_models import Atlas, box, noise_fill, write_model, write_tex, set_anchor, preview, JOINT  # noqa: E402

# Almost black: just enough light on the edges and the engraving for the shapes to read in the dark.
PAL = dict(base=(25, 25, 32), light=(66, 66, 82), dark=(8, 8, 12), hi=(104, 104, 128),
           trim=(42, 42, 54), trim_hi=(84, 84, 104),
           eng=(56, 56, 72), eng_hi=(96, 96, 120), eng_dark=(5, 5, 8),
           void=(3, 3, 5),
           cape=(15, 14, 19), cape_hi=(32, 30, 40), cape_dark=(6, 6, 8),
           fur=(18, 18, 22), fur_hi=(40, 40, 50), fur_dark=(6, 6, 8),
           cloth=(20, 19, 25), cloth_hi=(48, 46, 58),
           white=(236, 236, 244), white_dim=(150, 150, 168))

TIGER = dict(fur=(16, 16, 20), fur_hi=(30, 30, 38), stripe=(44, 44, 54), dark=(5, 5, 7),
             eye=(210, 214, 230), nose=(10, 10, 12), claw=(150, 150, 168), fang=(220, 220, 232))

SIDES = ("north", "south", "east", "west")


def tilt(e, axis, angle, origin):
    """Angle an element (Minecraft allows -45..45 in 22.5 steps) about a point given in offsets from the joint."""
    e["rotation"] = {"origin": [8 + origin[0], 8 + origin[1], 8 + origin[2]], "axis": axis, "angle": angle}
    return e


class Painter:
    """Face painters for black armor. Each takes (reg, face, w_units, h_units, **style)."""

    def __init__(self, seed, pal=PAL):
        self.rng = random.Random(seed)
        self.P = pal

    def px(self, reg, x, y, c):
        h, w = reg.shape[:2]
        if 0 <= x < w and 0 <= y < h: reg[y, x, :3] = c

    def emboss(self, reg, x, y):
        """One raised pixel of engraving: lit on top, its shadow just under it."""
        self.px(reg, x, y, self.P["eng_hi"]); self.px(reg, x + 1, y + 1, self.P["eng_dark"])

    def engrave(self, reg, dense=1.0):
        """Godfrey-style scrollwork: an inset border, spirals in the corners, a vine winding between them."""
        h, w = reg.shape[:2]
        if w < 10 or h < 10: return
        P, rng = self.P, self.rng
        i = 2 if min(w, h) < 20 else 3
        reg[i, i:w - i, :3] = P["eng"]; reg[h - 1 - i, i:w - i, :3] = P["eng"]
        reg[i:h - i, i, :3] = P["eng"]; reg[i:h - i, w - 1 - i, :3] = P["eng"]
        reg[i + 1, i + 1:w - i - 1, :3] = P["eng_dark"]; reg[i + 1:h - i - 1, i + 1, :3] = P["eng_dark"]
        if w < 16 or h < 16: return
        r = max(3, min(w, h) // 7)
        centers = [(i + r + 2, i + r + 2), (w - i - r - 3, h - i - r - 3)]
        if w * h > 900: centers += [(w - i - r - 3, i + r + 2), (i + r + 2, h - i - r - 3)]
        for cx, cy in centers:
            turn = rng.choice((-1, 1))
            for k in range(int(70 * dense)):
                a = k * 0.18
                rr = r * (1 - k / (70 * dense))
                self.emboss(reg, int(cx + math.cos(a * turn) * rr), int(cy + math.sin(a * turn) * rr))
        # the vine: a wave from one spiral to the other, with little leaf curls
        (x0, y0), (x1, y1) = centers[0], centers[1]
        n = max(abs(x1 - x0), abs(y1 - y0))
        amp = max(2, min(w, h) // 10)
        for k in range(n):
            f = k / max(1, n)
            x = int(x0 + (x1 - x0) * f + math.sin(f * math.pi * 4) * amp * (abs(y1 - y0) > abs(x1 - x0)))
            y = int(y0 + (y1 - y0) * f + math.sin(f * math.pi * 4) * amp * (abs(y1 - y0) <= abs(x1 - x0)))
            self.emboss(reg, x, y)
            if k % max(6, n // 6) == 3:
                for j in range(6):
                    self.emboss(reg, x + int(math.cos(j * 0.9) * 2), y + int(math.sin(j * 0.9) * 2))

    def plate(self, reg, face, wu, hu, ornate=False, ridge=False, lames=0, rivets=False, edge=True):
        """Black plate: a soft top-down falloff, a thin cold highlight along the top/left, deep shadow bottom/right."""
        P, rng = self.P, self.rng
        h, w = reg.shape[:2]
        noise_fill(reg, P["base"], rng, 3)
        for y in range(h):
            reg[y, :, :3] = np.clip(reg[y, :, :3] * (1.12 - 0.3 * y / max(1, h - 1)), 0, 255)
        if ornate and face in SIDES: self.engrave(reg)
        elif ornate and face == "up": self.engrave(reg, 0.6)
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
                for x in range(3, w - 2, 6): self.emboss(reg, x, y + 2)  # a row of tiny studs per lame
        if rivets and face in SIDES and w > 6 and h > 6:
            for x in (2, w - 3):
                for y in (2, h - 3):
                    reg[y, x, :3] = P["trim_hi"]
        for _ in range(int(w * h * 0.003)):  # a few cold glints
            reg[rng.randrange(1, max(2, h - 1)), rng.randrange(1, max(2, w - 1)), :3] = P["hi"]

    def trim(self, reg, face, wu, hu, beads=True):
        P = self.P
        h, w = reg.shape[:2]
        noise_fill(reg, P["trim"], self.rng, 3)
        reg[0, :, :3] = P["trim_hi"]; reg[-1, :, :3] = P["dark"]
        if beads and h >= 3:  # a beaded band
            m = h // 2
            for x in range(1, w, 3):
                reg[m, x, :3] = P["eng_hi"]
                if x + 1 < w: reg[m, x + 1, :3] = P["eng_dark"]

    def spike(self, reg, face, wu, hu):
        P = self.P
        noise_fill(reg, P["base"], self.rng, 3)
        reg[:, 0, :3] = P["light"]; reg[:, -1, :3] = P["dark"]
        if reg.shape[1] > 4: reg[:, reg.shape[1] // 2, :3] = P["trim_hi"]
        if face == "up": reg[:, :, :3] = P["hi"]

    def void(self, reg, face, wu, hu):
        reg[:, :, :3] = self.P["void"]; reg[:, :, 3] = 255

    def helm(self, reg, face, wu, hu):
        P = self.P
        self.plate(reg, face, wu, hu, ornate=face != "south", ridge=face in ("up", "north"))
        if face != "south": return
        h, w = reg.shape[:2]
        # brow scrolls over the arch
        for x in range(2, w - 2):
            self.emboss(reg, x, 2 + int(1.5 * math.sin(x * 0.5)))
        # the face: a tall pointed arch of pure darkness, rimmed with a raised edge. Nothing inside.
        top, bot = int(h * 0.14), int(h * 0.96)
        m = w // 2
        for y in range(top, bot):
            f = (y - top) / max(1, bot - top)
            half = int((w * 0.30) * min(1, 0.45 + f * 1.6))  # pointed at the top, widening down
            reg[y, m - half:m + half, :3] = P["void"]
            if m - half - 1 >= 0: reg[y, m - half - 1, :3] = P["hi"]
            if m - half - 2 >= 0: reg[y, m - half - 2, :3] = P["eng_dark"]
            if m + half < w: reg[y, m + half, :3] = P["light"]
            if m + half + 1 < w: reg[y, m + half + 1, :3] = P["eng_dark"]
        reg[max(0, top - 1), m - 1:m + 1, :3] = P["hi"]

    def mane(self, reg, face, wu, hu):
        """Wild black hair: long strands, a few lit ones, a ragged end."""
        P, rng = self.P, self.rng
        h, w = reg.shape[:2]
        noise_fill(reg, P["fur"], rng, 3)
        for x in range(w):
            c = rng.choice((P["fur_hi"], P["fur_dark"], P["fur"], P["fur_dark"]))
            reg[:, x, :3] = np.clip(np.array(c) + rng.uniform(-3, 3), 0, 255)
            if x % 3 == 0 and rng.random() < 0.5: reg[:, x, :3] = P["cape_hi"]
        if face in SIDES:
            for x in range(w):
                for y in range(h - rng.randrange(0, max(2, h // 5)), h): reg[y, x, 3] = 0

    def fur(self, reg, face, wu, hu):
        """A thick fur mantle: tufts, ragged at the bottom edge."""
        P, rng = self.P, self.rng
        h, w = reg.shape[:2]
        noise_fill(reg, P["fur"], rng, 4)
        for _ in range(w * h // 5):
            x, y = rng.randrange(w), rng.randrange(h)
            ln = rng.randrange(2, 5)
            c = rng.choice((P["fur_hi"], P["fur_dark"]))
            for k in range(ln):
                if y + k < h: reg[y + k, min(w - 1, x + k // 2), :3] = c
        if face in SIDES:
            for x in range(w):
                for y in range(h - rng.randrange(0, max(2, h // 3)), h): reg[y, x, 3] = 0

    def cloth(self, reg, face, wu, hu):
        """The tabard: heavy dark cloth, chevrons stitched down the middle, frayed at the hem."""
        P, rng = self.P, self.rng
        h, w = reg.shape[:2]
        noise_fill(reg, P["cloth"], rng, 3)
        for x in range(0, w, 4): reg[:, x, :3] = P["cape_dark"]
        if face in ("south", "north"):
            reg[:, 1, :3] = P["cloth_hi"]; reg[:, w - 2, :3] = P["cloth_hi"]
            m = w // 2
            for y0 in range(4, h - 6, 7):  # chevrons
                for k in range(m - 2):
                    self.px(reg, m - k, y0 + k // 2, P["cloth_hi"]); self.px(reg, m + k, y0 + k // 2, P["cloth_hi"])
        if face in SIDES:
            for x in range(w):
                for y in range(h - rng.randrange(0, max(2, h // 8)), h): reg[y, x, 3] = 0

    def cape(self, reg, face, wu, hu):
        P, rng = self.P, self.rng
        h, w = reg.shape[:2]
        noise_fill(reg, P["cape"], rng, 4)
        for x in range(0, w, 5): reg[:, x, :3] = P["cape_dark"]     # heavy folds
        for x in range(2, w, 5): reg[:, x, :3] = P["cape_hi"]
        reg[0:2, :, :3] = P["trim"]
        if face in SIDES:                                           # torn, ragged hem
            for x in range(w):
                cut = rng.randrange(0, max(2, h // 4))
                if rng.random() < 0.35: cut += rng.randrange(2, max(3, h // 3))
                for y in range(h - cut, h): reg[y, x, 3] = 0

    def medallion(self, reg, face, wu, hu):
        """A round medallion with a tiger's face on it (his emblem)."""
        P = self.P
        self.plate(reg, face, wu, hu, edge=False)
        if face != "south": return
        h, w = reg.shape[:2]
        cx, cy, r = (w - 1) / 2, (h - 1) / 2, min(w, h) / 2 - 0.5
        for y in range(h):
            for x in range(w):
                d = math.hypot(x - cx, y - cy)
                if d > r: reg[y, x, :3] = P["dark"]
                elif d > r - 1.5: reg[y, x, :3] = P["eng_hi"] if y < cy else P["eng"]
        s = max(1, int(r / 4))
        for ex in (-1, 1):  # ears, eyes, nose
            self.px(reg, int(cx + ex * r * 0.5), int(cy - r * 0.55), P["eng_hi"])
            for k in range(s): self.px(reg, int(cx + ex * r * 0.35) + k, int(cy - r * 0.1), P["white_dim"])
        for k in range(-s, s + 1): self.px(reg, int(cx) + k, int(cy + r * 0.3), P["eng_hi"])
        self.px(reg, int(cx), int(cy + r * 0.45), P["eng_hi"])


def explorer(seed):
    atlas = Atlas(1024, 4)
    pt = Painter(seed)
    plate, trim, spike, helm = pt.plate, pt.trim, pt.spike, pt.helm
    parts = {}

    # ---------------- head: a pointed great helm, a circlet of points, a spire, swept horns, a wild mane, the void
    head = [
        box(atlas, (-3.8, 0.0, -3.8), (3.8, 8.8, 3.8), helm),
        box(atlas, (-4.1, -0.3, -4.1), (4.1, 0.9, 4.1), trim),                         # lower rim
        box(atlas, (-4.05, 6.6, -4.05), (4.05, 7.8, 4.05), trim),                      # the circlet
        box(atlas, (-3.0, 8.8, -3.0), (3.0, 10.2, 3.0), plate, ornate=True, ridge=True),  # the helm narrows...
        box(atlas, (-2.0, 10.2, -2.0), (2.0, 11.6, 2.0), plate, ridge=True),
        box(atlas, (-1.1, 11.6, -1.1), (1.1, 13.2, 1.1), spike),
        box(atlas, (-0.45, 13.2, -0.45), (0.45, 15.4, 0.45), spike),                   # ...into a spire
        box(atlas, (-4.3, 0.0, -4.5), (4.3, 3.2, -3.6), plate),                        # neck guard at the back
    ]
    for x, z in ((0, -4.05), (-2.6, -4.05), (2.6, -4.05), (-4.05, -1.6), (4.05, -1.6), (-4.05, 1.4), (4.05, 1.4)):
        dx = 0.25 if abs(x) > 4 else 0.35; dz = 0.35 if abs(x) > 4 else 0.25           # the circlet's points
        head.append(box(atlas, (x - dx, 7.8, z - dz), (x + dx, 10.0 if x == 0 else 9.4, z + dz), spike))
    for s in (-1, 1):  # horns: out of the temples, then one long sweep up and back
        head.append(box(atlas, (min(s * 3.8, s * 5.2), 5.4, -0.9), (max(s * 3.8, s * 5.2), 7.0, 0.9), spike))
        head.append(tilt(box(atlas, (min(s * 4.4, s * 5.4), 6.2, -0.7), (max(s * 4.4, s * 5.4), 13.0, 0.7), spike), "x", -45, (s * 4.9, 6.6, 0)))
        head.append(tilt(box(atlas, (min(s * 4.6, s * 5.2), 12.0, -0.4), (max(s * 4.6, s * 5.2), 15.0, 0.4), spike), "x", -45, (s * 4.9, 6.6, 0)))
    # the mane: thick locks spilling from under the back of the helm, flaring out behind him
    for i, x in enumerate((-3.6, -2.4, -1.2, 0.0, 1.2, 2.4, 3.6)):
        ln = 7.5 if i in (0, 6) else (9.5 if i % 2 else 11.0)
        a = 22.5
        head.append(tilt(box(atlas, (x - 0.75, 4.5 - ln, -5.2), (x + 0.75, 4.5, -3.6), pt.mane), "x", a, (x, 4.5, -4.4)))
    for s in (-1, 1):  # side locks falling past the cheeks onto the mantle
        head.append(tilt(box(atlas, (min(s * 3.9, s * 5.0), -4.0, -2.4), (max(s * 3.9, s * 5.0), 3.0, 0.6), pt.mane), "z", 22.5 * s, (s * 4.4, 3.0, -1)))
    parts["head"] = head

    # ---------------- body: engraved cuirass, a tiger medallion, faulds, tabard, fur mantle and a long torn cape
    body = [
        box(atlas, (-3.9, 0.0, -2.3), (3.9, 7.0, 2.3), plate),                                     # waist
        box(atlas, (-4.9, 6.2, -2.9), (4.9, 12.5, 2.9), plate, ornate=True, ridge=True),           # cuirass
        box(atlas, (-4.6, 8.4, 2.9), (-0.4, 12.0, 3.3), plate, ornate=True),                       # pectoral plates
        box(atlas, (0.4, 8.4, 2.9), (4.6, 12.0, 3.3), plate, ornate=True),
        box(atlas, (-1.5, 5.8, 3.0), (1.5, 8.8, 3.7), pt.medallion),                               # the tiger medallion
        box(atlas, (-4.3, 3.0, -2.65), (4.3, 6.2, 2.65), plate, lames=3, edge=False),              # faulds
        box(atlas, (-4.4, 1.6, -2.75), (4.4, 2.9, 2.75), trim),                                    # belt
        box(atlas, (-1.0, 1.1, 2.75), (1.0, 3.3, 3.15), pt.medallion),                             # buckle
        box(atlas, (-2.0, -7.5, 2.3), (2.0, 1.5, 2.8), pt.cloth),                                  # tabard, front
        box(atlas, (-2.0, -6.0, -2.75), (2.0, 1.5, -2.3), pt.cloth),                               # and back
        box(atlas, (-4.0, -3.6, 2.2), (-2.0, 1.6, 2.9), plate, lames=2),                           # tassets
        box(atlas, (2.0, -3.6, 2.2), (4.0, 1.6, 2.9), plate, lames=2),
        box(atlas, (-4.5, -3.2, -2.6), (-3.9, 1.6, 2.4), plate, lames=2),                          # side tassets
        box(atlas, (3.9, -3.2, -2.6), (4.5, 1.6, 2.4), plate, lames=2),
        # the high collar stands up around the helm (lower in front so the void stays visible)
        box(atlas, (-3.4, 12.4, -3.4), (3.4, 15.0, -2.6), plate, ornate=True),                     # back
        box(atlas, (-3.8, 12.4, -3.4), (-3.0, 14.4, 2.6), plate),                                  # sides
        box(atlas, (3.0, 12.4, -3.4), (3.8, 14.4, 2.6), plate),
        box(atlas, (-3.0, 12.4, 2.2), (3.0, 13.2, 3.0), trim),                                     # front lip
        # the fur mantle draped over both shoulders and the back
        box(atlas, (-5.4, 11.0, -3.6), (5.4, 13.6, 3.2), pt.fur),
        box(atlas, (-4.6, 13.0, -3.9), (4.6, 14.2, -1.0), pt.fur),
        box(atlas, (-5.4, -9.5, -3.9), (5.4, 12.6, -3.0), pt.cape),                                # the long cape
        box(atlas, (-5.8, 11.6, -4.1), (5.8, 12.8, -2.8), trim),                                   # cape clasp bar
    ]
    for s in (-1, 1):
        body.append(box(atlas, (s * 4.6 - 0.8, 10.6, 3.0), (s * 4.6 + 0.8, 12.2, 3.5), pt.medallion))  # mantle clasps
    parts["body"] = body

    # ---------------- arms: engraved vambraces, huge pauldrons with fur ruffs and raking spikes, clawed gauntlets
    def arm(side):
        o = side  # +1 = his left (+X), -1 = his right
        def X(a, b): return (min(a * o, b * o), max(a * o, b * o))
        el = [
            box(atlas, (-1.8, -11.6, -1.8), (1.8, 0.4, 1.8), plate),                               # arm
            box(atlas, (-2.0, -7.0, -2.0), (2.0, -5.6, 2.0), trim),                                # elbow cop
            box(atlas, (-2.15, -7.6, -2.15), (2.15, -3.0, 2.15), plate, ornate=True),              # upper vambrace
            box(atlas, (-2.3, -12.4, -2.3), (2.3, -8.0, 2.3), plate, ornate=True),                 # gauntlet
            box(atlas, (-2.45, -8.6, -2.45), (2.45, -7.8, 2.45), trim),                            # its cuff
        ]
        x0, x1 = X(-2.6, 4.0)
        el.append(box(atlas, (x0, -3.0, -3.3), (x1, 1.2, 3.3), plate, ornate=True, lames=5))      # pauldron, wider outside
        x0, x1 = X(-2.2, 4.4)
        el.append(box(atlas, (x0, 1.2, -3.0), (x1, 2.8, 3.0), plate, ornate=True, ridge=True))    # its top plate
        x0, x1 = X(-2.8, 4.3)
        el.append(box(atlas, (x0, -3.6, -3.5), (x1, -2.8, 3.5), trim))                            # its rim
        x0, x1 = X(-2.6, 1.0)
        el.append(box(atlas, (x0, 2.4, -3.1), (x1, 3.8, 3.1), pt.fur))                             # fur ruff
        for zc, ln, x in ((-1.8, 5.0, 2.0), (0.0, 7.4, 2.9), (1.8, 5.0, 2.0)):                    # raking spikes
            x0, x1 = X(x - 0.6, x + 0.6)
            el.append(tilt(box(atlas, (x0, 2.6, zc - 0.55), (x1, 2.6 + ln, zc + 0.55), spike), "z", -22.5 * o, (o * x, 2.6, zc)))
        for i in range(4):                                                                         # claws
            x = -1.65 + i * 1.1
            el.append(box(atlas, (x, -13.8, 1.0), (x + 0.6, -12.4, 2.0), spike))
        return el

    parts["arm_r"], parts["arm_l"] = arm(-1), arm(1)

    # ---------------- legs: engraved cuisses, a medallion on the knee, greaves, pointed sabatons
    def leg():
        return [
            box(atlas, (-2.0, -12.0, -2.0), (2.0, 0.0, 2.0), plate),
            box(atlas, (-2.25, -6.2, -2.25), (2.25, -1.0, 2.25), plate, ornate=True),             # cuisse
            box(atlas, (-1.6, -7.6, 1.9), (1.6, -5.2, 2.8), pt.medallion),                         # knee cop
            box(atlas, (-0.5, -7.0, 2.8), (0.5, -5.8, 3.8), spike),                                # knee spike
            box(atlas, (-2.2, -9.6, -2.2), (2.2, -7.6, 2.2), plate, ornate=True),                  # greave
            box(atlas, (-2.4, -12.0, -2.4), (2.4, -9.6, 2.4), plate, lames=3),                     # sabaton
            box(atlas, (-1.9, -12.0, 2.4), (1.9, -10.8, 3.6), plate),                              # pointed toe
            box(atlas, (-0.9, -12.0, 3.6), (0.9, -11.3, 4.6), spike),
        ]
    parts["leg_r"], parts["leg_l"] = leg(), leg()
    return atlas, parts


# ---------------- his great axe: a long black haft, double crescent heads with white edges, a spike on top
AXE_DISPLAY = {"fixed": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
               "gui": {"rotation": [0, 0, -45], "translation": [0, 0, 0], "scale": [0.45, 0.45, 0.45]},
               "thirdperson_righthand": {"rotation": [0, 90, 0], "translation": [0, 6, 1], "scale": [0.6, 0.6, 0.6]},
               "firstperson_righthand": {"rotation": [0, 90, 20], "translation": [1, 4, 1], "scale": [0.5, 0.5, 0.5]}}


def axe(seed):
    atlas = Atlas(512, 4)
    pt = Painter(seed); P = PAL

    def haft(reg, face, wu, hu):
        noise_fill(reg, (12, 11, 14), pt.rng, 3)
        for y in range(0, reg.shape[0], 3): reg[y, :, :3] = (28, 26, 34)  # leather wraps

    def head(reg, face, wu, hu, edge=False):
        pt.plate(reg, face, wu, hu, ornate=face in ("north", "south"))
        if edge and face in ("east", "west", "up", "down"):
            reg[:, :, :3] = P["white"]
        elif edge:
            reg[:, -2:, :3] = P["white_dim"]; reg[:, :2, :3] = P["white_dim"]

    el = [
        box(atlas, (-1.0, -21.0, -1.0), (1.0, -19.4, 1.0), pt.spike),     # butt spike/cap
        box(atlas, (-0.75, -19.4, -0.75), (0.75, 18.0, 0.75), haft),      # haft
        box(atlas, (-1.0, -8.0, -1.0), (1.0, -6.8, 1.0), pt.trim),        # bands
        box(atlas, (-1.0, 4.0, -1.0), (1.0, 5.2, 1.0), pt.trim),
        box(atlas, (-1.3, 9.0, -1.3), (1.3, 21.0, 1.3), pt.plate, ornate=True),  # the socket
        box(atlas, (-0.9, 21.0, -0.9), (0.9, 22.6, 0.9), pt.spike),       # top spike
        box(atlas, (-0.45, 22.6, -0.45), (0.45, 24.0, 0.45), pt.spike),
    ]
    for s in (-1, 1):  # the crescents: stepped boxes that grow taller toward the edge
        for (a, b, y0, y1, th, edge) in ((1.3, 3.0, 12.0, 18.0, 0.55, False), (3.0, 5.0, 10.6, 19.4, 0.5, False),
                                          (5.0, 7.0, 9.4, 20.6, 0.45, False), (7.0, 8.6, 8.4, 21.6, 0.4, False),
                                          (8.6, 9.6, 7.4, 22.6, 0.3, True)):
            x0, x1 = (a, b) if s > 0 else (-b, -a)
            el.append(box(atlas, (x0, y0, -th), (x1, y1, th), head, edge=edge))
        for (y0, y1) in ((5.8, 7.4), (22.6, 23.8)):  # bearded points top and bottom
            x0, x1 = (8.0, 9.6) if s > 0 else (-9.6, -8.0)
            el.append(box(atlas, (x0, y0, -0.25), (x1, y1, 0.25), head, edge=True))
    return atlas, el


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


# ---------------- the black tiger: sits behind him (phase 2). Faces +z, feet at y = 0.
TIGER_JOINT = {"body": (0, 0, 0), "head": (0, 19.5, 9.0), "jaw": (0, 15.6, 13.6), "tail": (0, 1.5, -9.0)}


def tiger(seed):
    atlas = Atlas(1024, 4)
    rng = random.Random(seed); T = TIGER

    def coat(reg, face, wu, hu, stripes=True, belly=False):
        """Black fur with slightly lighter tiger stripes, wavy and tapering."""
        h, w = reg.shape[:2]
        noise_fill(reg, T["fur_hi"] if belly else T["fur"], rng, 3)
        for _ in range(w * h // 16):  # short hair strokes
            x, y = rng.randrange(w), rng.randrange(h)
            for k in range(2):
                if y + k < h: reg[y + k, x, :3] = T["fur_hi"] if rng.random() < 0.5 else T["dark"]
        if not stripes or face in ("up", "down") and w * h < 200: return
        across = face in ("east", "west", "north", "south")
        n = max(2, (w if across else h) // 7)
        for k in range(n):
            c0 = (k + 0.5) * (w if across else h) / n + rng.uniform(-1.5, 1.5)
            ln = (h if across else w) * rng.uniform(0.45, 0.85)
            st = 0 if rng.random() < 0.5 else (h if across else w) - ln
            for j in range(int(ln)):
                f = j / max(1, ln)
                th = max(1, int(2.6 * (1 - f) + 0.5))
                c = int(c0 + 2 * math.sin(j * 0.35))
                for d in range(th):
                    x, y = (c + d, int(st + j)) if across else (int(st + j), c + d)
                    if 0 <= x < w and 0 <= y < h: reg[y, x, :3] = T["stripe"]

    def skull(reg, face, wu, hu):
        coat(reg, face, wu, hu)
        if face != "south": return
        h, w = reg.shape[:2]
        reg[: h // 3, :, :3] = np.clip(reg[: h // 3, :, :3], 0, 255)
        for s in (-1, 1):  # pale, narrowed eyes and a dark brow over them
            cx = w // 2 + s * int(w * 0.29)
            ey = int(h * 0.30)
            for k in range(-3, 4):
                y = ey + (k * s > 0) * 1
                if 0 <= cx + k < w: reg[y, cx + k, :3] = T["eye"]
                if 0 <= cx + k < w: reg[y - 2, cx + k, :3] = T["dark"]
            reg[ey, cx, :3] = T["dark"]  # the slit pupil
        for y in range(0, int(h * 0.2), 3):  # forehead stripes
            reg[y, w // 2 - 1:w // 2 + 1, :3] = T["stripe"]

    def muzzle(reg, face, wu, hu):
        coat(reg, face, wu, hu, stripes=False, belly=True)
        h, w = reg.shape[:2]
        if face == "south":
            reg[: max(2, h // 3), w // 2 - 3:w // 2 + 3, :3] = T["nose"]
            reg[h // 3:, w // 2, :3] = T["dark"]
            for s in (-1, 1):  # whisker dots
                for k in range(3): reg[h // 2 + k * 2 // 2, w // 2 + s * (4 + k * 2), :3] = T["fur_hi"]

    def plain(c):
        def f(reg, face, wu, hu):
            noise_fill(reg, c, rng, 4)
        return f

    def ruff(reg, face, wu, hu):
        coat(reg, face, wu, hu, stripes=False, belly=True)
        h, w = reg.shape[:2]
        if face in SIDES:
            for x in range(w):
                for y in range(h - rng.randrange(0, max(2, h // 3)), h): reg[y, x, 3] = 0

    claw, fang = plain(T["claw"]), plain(T["fang"])
    parts = {}

    # body: haunches on the ground, chest up and leaning forward, two straight front legs
    body = [
        box(atlas, (-6.0, 0.0, -9.0), (6.0, 6.0, 1.0), coat),                           # haunches
        box(atlas, (-5.0, 6.0, -8.0), (5.0, 8.5, 0.5), coat),                           # rounding off the rump
        tilt(box(atlas, (-3.9, 4.0, -2.0), (3.9, 16.5, 4.6), coat), "x", 22.5, (0, 5, 1)),   # the chest, leaning up/forward
        tilt(box(atlas, (-2.8, 6.0, 4.4), (2.8, 13.5, 5.6), ruff), "x", 22.5, (0, 5, 1)),    # pale chest ruff
        box(atlas, (-3.4, 14.5, 4.0), (3.4, 19.0, 9.6), coat),                          # neck
    ]
    for s in (-1, 1):
        X = lambda a, b: (min(a * s, b * s), max(a * s, b * s))
        x0, x1 = X(4.5, 7.4); body.append(box(atlas, (x0, 0.0, -8.5), (x1, 8.5, 0.5), coat))       # thighs
        x0, x1 = X(4.3, 7.6); body.append(box(atlas, (x0, 0.0, 0.5), (x1, 1.8, 5.0), coat))        # hind paws
        x0, x1 = X(1.0, 3.8); body.append(box(atlas, (x0, 1.4, 5.4), (x1, 13.0, 8.2), coat))       # front legs
        x0, x1 = X(0.8, 4.1); body.append(box(atlas, (x0, 0.0, 5.2), (x1, 2.0, 9.6), coat))        # front paws
        for k in range(3):
            cx = s * (1.3 + k * 1.1)
            body.append(box(atlas, (cx - 0.25, 0.0, 9.6), (cx + 0.25, 0.8, 10.4), claw))
    parts["body"] = body

    # head (joint at the top of the neck): broad skull, cheek ruffs, a heavy muzzle, short ears, upper fangs
    head = [
        box(atlas, (-4.4, -3.0, -3.0), (4.4, 4.2, 4.6), skull),
        box(atlas, (-3.0, -3.6, 4.6), (3.0, 0.6, 8.6), muzzle),
        box(atlas, (-1.4, 0.6, 4.6), (1.4, 2.0, 7.8), coat),                             # bridge of the nose
        box(atlas, (-3.6, 3.6, -2.6), (3.6, 4.6, 3.6), coat),                            # brow
    ]
    for s in (-1, 1):
        X = lambda a, b: (min(a * s, b * s), max(a * s, b * s))
        x0, x1 = X(3.8, 5.4); head.append(box(atlas, (x0, -4.0, -1.6), (x1, 0.8, 2.6), ruff))      # cheek ruffs
        x0, x1 = X(2.6, 4.6); head.append(box(atlas, (x0, 4.2, -1.4), (x1, 5.6, 0.2), coat))       # ears: a wide base...
        x0, x1 = X(3.2, 4.2); head.append(box(atlas, (x0, 5.6, -1.0), (x1, 6.6, -0.2), coat))     # ...and a point
        x0, x1 = X(1.2, 2.0); head.append(box(atlas, (x0, -5.4, 6.8), (x1, -3.6, 7.6), fang))      # fangs
    parts["head"] = head

    # jaw (hinged under the muzzle) and tail (curling round his right side along the ground)
    parts["jaw"] = [box(atlas, (-2.3, -1.6, -1.2), (2.3, 0.2, 3.0), coat),
                    box(atlas, (-1.6, 0.2, 2.2), (-1.0, 0.9, 2.7), fang), box(atlas, (1.0, 0.2, 2.2), (1.6, 0.9, 2.7), fang)]
    parts["tail"] = [
        box(atlas, (-1.1, -1.5, -6.0), (1.1, 0.7, 0.0), coat),
        box(atlas, (-7.0, -1.5, -7.6), (1.1, 0.5, -5.6), coat),
        box(atlas, (-9.0, -1.5, -7.0), (-7.0, 0.3, 1.0), coat),
        box(atlas, (-9.6, -1.5, 1.0), (-6.4, 1.4, 3.6), plain(T["dark"])),              # the black tip
    ]
    return atlas, parts


def lighten(path):
    """Swap the dark preview background for grey so the black armor can actually be judged."""
    im = np.asarray(Image.open(path).convert("RGBA")).copy()
    bg = (im[:, :, 0] == 34) & (im[:, :, 1] == 38) & (im[:, :, 2] == 46)
    im[bg, :3] = (150, 156, 168)
    Image.fromarray(im).save(path)


def scene(prev, parts, img, ael, aimg, tparts, timg):
    """Him standing with the axe in his right hand, the tiger sitting behind him (as in phase 2)."""
    sys.path.insert(0, os.path.join(os.path.dirname(__file__), "anim"))
    from render_anims import place, render, rx
    tex = np.asarray(img).astype(float)
    models = {k: (v, tex) for k, v in parts.items()}
    r = [[0, 0, 0] for _ in range(6)]
    r[3] = [-12, 0, -22]; r[4] = [4, 0, 8]  # right arm a little forward and out, left at his side
    placed = place({"r": r, "drop": 0}, models, [])
    _, _, m, off = placed[3]
    hand = m @ np.array([0, -12, 0]) + off
    placed.append((ael, np.asarray(aimg).astype(float), np.eye(3), hand + [-3, 6, 1]))
    ts, toff = 1.5, np.array([14.0, 0, -30.0])  # behind him and off to his left, turned a little toward him
    from render_anims import ry
    q = ry(math.radians(-20))
    for k, el in tparts.items():
        j = np.array(TIGER_JOINT[k], dtype=float)
        placed.append((el, np.asarray(timg).astype(float), q * ts, q @ (j * ts) + toff))
    sheet = Image.new("RGBA", (3 * 460, 640))
    for i, (yw, pt) in enumerate(((15, 6), (-30, 10), (45, 6))):
        sheet.paste(render(placed, yw, pt, 8.5, (460, 640), anchor=0.95), (i * 460, 0))
    sheet.save(os.path.join(prev, "explorer_scene.png"))


def main():
    pack = sys.argv[1]
    prev = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    for d in ("models/item", "textures/item", "items"): os.makedirs(os.path.join(pack, "assets/faultline", d), exist_ok=True)
    atlas, parts = explorer(41)
    img = write_tex(pack, "explorer_armor", atlas)
    for part, el in parts.items(): write_model(pack, f"explorer_{part}", "explorer_armor", el)
    aatlas, ael = axe(43)
    aimg = write_tex(pack, "explorer_axe", aatlas)
    write_model(pack, "explorer_axe", "explorer_axe", ael, AXE_DISPLAY)
    batlas, bel = blade(42)
    bimg = write_tex(pack, "explorer_blade", batlas)
    write_model(pack, "explorer_blade", "explorer_blade", bel, BLADE_DISPLAY)
    tatlas, tparts = tiger(44)
    timg = write_tex(pack, "explorer_tiger", tatlas)
    for part, el in tparts.items(): write_model(pack, f"explorer_tiger_{part}", "explorer_tiger", el)
    if prev:
        os.makedirs(prev, exist_ok=True)
        set_anchor(0.97)
        preview(prev, "explorer", parts, img, JOINT, [(20, 8), (160, 8), (-40, 20), (90, 4)], 11, (380, 640))
        preview(prev, "explorer_tiger", tparts, timg, TIGER_JOINT, [(-15, 8), (35, 12), (150, 10), (90, 4)], 11, (380, 400))
        set_anchor(0.62)
        preview(prev, "explorer_axe", {"a": ael}, aimg, {"a": (0, 0, 0)}, [(20, 10), (90, 10)], 7, (260, 420))
        preview(prev, "explorer_blade", {"b": bel}, bimg, {"b": (0, 0, 0)}, [(20, 10), (90, 10)], 7, (260, 420))
        scene(prev, parts, img, ael, aimg, tparts, timg)
        for n in ("explorer", "explorer_tiger", "explorer_axe", "explorer_blade", "explorer_scene"): lighten(os.path.join(prev, n + ".png"))


if __name__ == "__main__":
    main()
