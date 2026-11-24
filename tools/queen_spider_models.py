#!/usr/bin/env python3
"""
The Queen Spider's rig (FaultlineItems 1.3.44, QueenRig.java): an original blocky spider in purple-black chitin. Banded,
spiky legs in three segments, a raised carapace, a big patterned abdomen, eight glowing red eyes, curved fangs and
pedipalps. Every part is its own Java item model (one ItemDisplay each), modelled around its joint at model (8,8,8):
  thorax, head, eyes (lit), fang, palp, abdomen (centred: the plugin offsets it), femur, tibia, tarsus.
Front = +z, up = +y, right = -x. Units = pixels (the plugin scales them).

Writes into an unpacked FaultlineSMP pack:
  assets/faultline/models/item/queen/<part>.json, assets/faultline/items/queen/<part>.json,
  assets/faultline/textures/item/queen/*.png
Usage: python3 tools/queen_spider_models.py <unpacked-pack> [--preview <dir>]
"""
import json, os, random, sys, math
from PIL import Image, ImageDraw

BASE = (64, 48, 84)      # purple-black chitin
DARK = (30, 22, 40)
LIGHT = (118, 96, 150)
PALE = (156, 132, 186)
RED = (220, 24, 48)


def tex_chitin(seed, base=BASE, spots=LIGHT):
    r = random.Random(seed)
    im = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            n = r.randint(-8, 8)
            c = tuple(max(0, min(255, v + n)) for v in base)
            if r.random() < 0.10: c = tuple((a + b) // 2 for a, b in zip(c, spots))
            if r.random() < 0.08: c = DARK
            im.putpixel((x, y), c + (255,))
    return im


def tex_band():
    im = tex_chitin(3, LIGHT, PALE)
    for x in range(16):
        im.putpixel((x, 0), DARK + (255,)); im.putpixel((x, 15), DARK + (255,))
    return im


def tex_hair():
    """Dark chitin with pale bristles."""
    r = random.Random(9)
    im = tex_chitin(4, (46, 34, 60), (90, 72, 116))
    for _ in range(14):
        x, y = r.randint(0, 15), r.randint(0, 14)
        im.putpixel((x, y), PALE + (255,)); im.putpixel((x, y + 1), LIGHT + (255,))
    return im


def tex_abdomen():
    """The back of the abdomen: concentric pale rings over purple, like a target, with a dark centre stripe."""
    r = random.Random(5)
    im = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, (y - 7.5) * 1.3)
            ring = int(d) % 4
            c = [BASE, (84, 66, 108), LIGHT, (84, 66, 108)][ring]
            if abs(x - 7.5) < 1 and y > 3: c = DARK
            c = tuple(max(0, min(255, v + r.randint(-6, 6))) for v in c)
            im.putpixel((x, y), c + (255,))
    return im


def tex_eye():
    im = Image.new("RGBA", (16, 16), RED + (255,))
    for y in range(16):
        for x in range(16):
            if x < 2 or y < 2 or x > 13 or y > 13: im.putpixel((x, y), (120, 8, 24, 255))
    for (x, y) in ((4, 4), (5, 4), (4, 5)): im.putpixel((x, y), (255, 200, 210, 255))
    return im


def tex_fang():
    im = Image.new("RGBA", (16, 16))
    for y in range(16):
        t = y / 15
        c = tuple(int(a * (1 - t) + b * t) for a, b in zip((214, 200, 176), (24, 18, 26)))
        for x in range(16): im.putpixel((x, y), c + (255,))
    return im


TEX = {"chitin": lambda: tex_chitin(1), "chitin_dark": lambda: tex_chitin(2, DARK, BASE), "band": tex_band, "hair": tex_hair,
       "abdomen": tex_abdomen, "eye": tex_eye, "fang": tex_fang}


def box(a, b, tex, faces=None):
    """a, b: min/max in pixels around the joint; the model's joint is (8,8,8)."""
    lo = [round(8 + v, 3) for v in a]; hi = [round(8 + v, 3) for v in b]
    t = {k: tex for k in ("north", "south", "east", "west", "up", "down")}
    if faces: t.update(faces)
    return {"from": lo, "to": hi, "faces": {k: {"texture": "#" + v, "uv": [0, 0, 16, 16]} for k, v in t.items()}}


def spikes(z0, z1, r, n, seed):
    rnd = random.Random(seed)
    out = []
    for i in range(n):
        z = z0 + (z1 - z0) * (i + 0.5) / n
        side = rnd.choice([(r, 0), (-r, 0), (0, r)])
        x, y = side
        out.append(box((x - 0.35 + (0.3 if x > 0 else -0.3 if x < 0 else 0), y - 0.35, z), (x + 0.35 + (0.6 if x > 0 else -0.0 if x < 0 else 0), y + 0.35 + (1.2 if y > 0 else 0), z + 0.6), "hair"))
    return out


PARTS = {
    "thorax": [box((-7, -3, -7), (7, 4, 7), "chitin", {"up": "chitin_dark"}),
               box((-5, 4, -5), (5, 6, 5), "chitin"),
               box((-1, 6, -4), (1, 7, 4), "chitin_dark"),
               box((-6, -4, -5), (6, -3, 5), "chitin_dark")],
    "head": [box((-5, -3, 0), (5, 4, 6), "chitin"),
             box((-4, 4, 1), (4, 5, 5), "chitin_dark"),
             box((-4.5, -3.5, 4), (4.5, -2, 6.5), "hair")],
    "eyes": [box((-3.2, 0.6, 6), (-1.0, 2.8, 6.6), "eye"), box((1.0, 0.6, 6), (3.2, 2.8, 6.6), "eye"),
             box((-4.4, 1.2, 5.4), (-3.4, 2.2, 6.2), "eye"), box((3.4, 1.2, 5.4), (4.4, 2.2, 6.2), "eye"),
             box((-2.5, 3.2, 4.8), (-1.2, 4.3, 5.8), "eye"), box((1.2, 3.2, 4.8), (2.5, 4.3, 5.8), "eye"),
             box((-4.6, 3.0, 3.0), (-3.6, 4.0, 4.0), "eye"), box((3.6, 3.0, 3.0), (4.6, 4.0, 4.0), "eye")],
    # the chelicera (hairy) and its fang curving down and back in
    "fang": [box((-2, -6, -2), (2, 0, 2), "hair"),
             box((-1.2, -9, -0.6), (1.2, -6, 1.8), "fang"),
             box((-0.8, -11.5, -1.8), (0.8, -9, 0.4), "fang"),
             box((-0.45, -13, -3), (0.45, -11.5, -1.4), "fang")],
    "palp": [box((-1, -1, 0), (1, 1, 6), "hair"), box((-1.2, -1.2, 5), (1.2, 1.2, 6.5), "band"),
             box((-0.8, -4, 5.4), (0.8, -1, 7), "chitin_dark")],
    # centred on its own middle (the plugin puts it behind the thorax)
    "abdomen": [box((-9, -7, -11), (9, 8, 11), "chitin", {"up": "abdomen"}),
                box((-7, 8, -9), (7, 10.5, 8), "abdomen", {"north": "chitin", "south": "chitin", "east": "chitin", "west": "chitin"}),
                box((-7, -8, -9), (7, -7, 9), "chitin_dark"),
                box((-6, -5, 11), (6, 6, 13), "chitin"),
                box((-6, -5, -13), (6, 6, -11), "chitin"),
                box((-2, -3, -15), (2, 0, -13), "chitin_dark")],
    "femur": [box((-2, -2, 0), (2, 2, 18), "chitin"), box((-2.4, -2.4, 0), (2.4, 2.4, 2.5), "band"),
              box((-2.3, -2.3, 15.5), (2.3, 2.3, 18), "band")] + spikes(3, 15, 2, 7, 11),
    "tibia": [box((-1.6, -1.6, 0), (1.6, 1.6, 20), "chitin"), box((-1.9, -1.9, 17.5), (1.9, 1.9, 20), "band")] + spikes(2, 17, 1.6, 8, 12),
    "tarsus": [box((-1.2, -1.2, 0), (1.2, 1.2, 14), "chitin_dark"), box((-0.6, -2.2, 13), (0.6, 0.2, 16), "fang"),
               box((-1.4, -1.4, 0), (1.4, 1.4, 1.5), "band")] + spikes(2, 12, 1.2, 5, 13),
}


def main():
    pack = sys.argv[1]
    prev = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    A = os.path.join(pack, "assets", "faultline")
    td = os.path.join(A, "textures", "item", "queen"); os.makedirs(td, exist_ok=True)
    md = os.path.join(A, "models", "item", "queen"); os.makedirs(md, exist_ok=True)
    idf = os.path.join(A, "items", "queen"); os.makedirs(idf, exist_ok=True)
    for k, f in TEX.items(): f().save(os.path.join(td, k + ".png"))
    for name, els in PARTS.items():
        for e in els:
            for v in e["from"] + e["to"]:
                assert -16 <= v <= 32, (name, e)
        textures = {k: "faultline:item/queen/" + k for k in TEX}
        textures["particle"] = "faultline:item/queen/chitin"
        with open(os.path.join(md, name + ".json"), "w") as fh: json.dump({"textures": textures, "elements": els}, fh, separators=(",", ":"))
        with open(os.path.join(idf, name + ".json"), "w") as fh: json.dump({"model": {"type": "minecraft:model", "model": "faultline:item/queen/" + name}}, fh)
    print("parts", {k: len(v) for k, v in PARTS.items()})
    if prev: preview(prev)


# ---------------------------------------------------------------------------- a preview with the plugin's rest pose
def rot_x(a):
    c, s = math.cos(a), math.sin(a); return [[1, 0, 0], [0, c, -s], [0, s, c]]
def rot_y(a):
    c, s = math.cos(a), math.sin(a); return [[c, 0, s], [0, 1, 0], [-s, 0, c]]
def rot_z(a):
    c, s = math.cos(a), math.sin(a); return [[c, -s, 0], [s, c, 0], [0, 0, 1]]
def mm(a, b): return [[sum(a[i][k] * b[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
def mv(a, v): return [sum(a[i][k] * v[k] for k in range(3)) for i in range(3)]
def add(a, b): return [x + y for x, y in zip(a, b)]


def pose(sleep=False):
    """(part, R, origin) list: the same joints and rest angles as QueenRig.java."""
    out = []
    I = [[1, 0, 0], [0, 1, 0], [0, 0, 1]]
    body_y = 5 if sleep else 11.2
    o = [0, body_y, 0]
    out.append(("thorax", I, o))
    out.append(("head", I, add(o, [0, 1, 7])))
    out.append(("eyes", I, add(o, [0, 1, 7])))
    ab = mm(rot_x(-0.25), I)
    out.append(("abdomen", ab, add(add(o, [0, 3, -6]), mv(ab, [0, 2, -12]))))
    for s in (1, -1):
        fr = mm(rot_y(0.18 * s), rot_x(0.25 if not sleep else 0.1))
        out.append(("fang", fr, add(o, [2.2 * s, -1, 12])))
        pr = mm(rot_y(0.35 * s), rot_x(0.5))
        out.append(("palp", pr, add(o, [4 * s, -1, 10])))
    hips = [(5, 0.55), (2, 1.25), (-1, 1.9), (-4, 2.5)]  # z on the thorax, yaw out from +z
    for i, (hz, spread) in enumerate(hips):
        for s in (1, -1):
            yaw = spread * s
            elev = -0.95 if not sleep else -0.5
            knee = 1.6 if not sleep else 2.5
            foot = 0.45 if not sleep else 1.4
            R1 = mm(rot_y(yaw), rot_x(elev))
            p1 = add(o, [6.5 * s, 1, hz])
            out.append(("femur", R1, p1))
            R2 = mm(R1, rot_x(knee - 0.0))
            p2 = add(p1, mv(R1, [0, 0, 18]))
            out.append(("tibia", R2, p2))
            R3 = mm(R2, rot_x(foot))
            p3 = add(p2, mv(R2, [0, 0, 20]))
            out.append(("tarsus", R3, p3))
    return out


def preview(out):
    os.makedirs(out, exist_ok=True)
    cols = {"chitin": BASE, "chitin_dark": DARK, "band": LIGHT, "hair": (70, 54, 90), "abdomen": (98, 78, 128), "eye": RED, "fang": (190, 176, 150)}
    sheet = Image.new("RGB", (1500, 520), (20, 32, 24))
    views = [("front-left", -0.7, 0.35, False), ("side", -1.57, 0.12, False), ("asleep", -0.5, 0.45, True)]
    for vi, (name, yaw, pitch, sleep) in enumerate(views):
        img = Image.new("RGB", (500, 520), (20, 32, 24)); dr = ImageDraw.Draw(img)
        V = mm(rot_x(pitch), rot_y(yaw))
        polys = []
        for part, R, o in pose(sleep):
            for e in PARTS[part]:
                lo = [v - 8 for v in e["from"]]; hi = [v - 8 for v in e["to"]]
                corners = [[x, y, z] for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])]
                w = [mv(V, add(o, mv(R, c))) for c in corners]
                tex = e["faces"]["up"]["texture"][1:]
                for f in ((0, 1, 3, 2), (4, 5, 7, 6), (0, 1, 5, 4), (2, 3, 7, 6), (0, 2, 6, 4), (1, 3, 7, 5)):
                    pts = [w[k] for k in f]
                    depth = sum(p[2] for p in pts) / 4
                    polys.append((depth, [(250 + p[0] * 5, 330 - p[1] * 5) for p in pts], cols[tex]))
        for d, pts, c in sorted(polys, key=lambda t: t[0]):
            shade = max(0.45, min(1.1, 0.8 + d / 120))
            dr.polygon(pts, fill=tuple(int(v * shade) for v in c), outline=(12, 10, 16))
        dr.text((10, 10), name, fill=(220, 220, 220))
        sheet.paste(img, (vi * 500, 0))
    sheet.save(os.path.join(out, "queen_spider_rig.png"))


if __name__ == "__main__":
    main()
