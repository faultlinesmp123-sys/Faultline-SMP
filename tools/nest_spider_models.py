#!/usr/bin/env python3
"""
The Spider Nest's breeds (FaultlineItems 1.3.47): an original blocky spider in black, segmented chitin with glowing
markings, in three breeds that only change the glow: VENOM (green), EMBER (orange), SWIFT (blue). Same parts and joints
as the Queen (tools/queen_spider_models.py), so QueenRig animates them: thorax, head, eyes, fang, palp, abdomen, femur,
tibia, tarsus, each modelled around its joint at model (8,8,8). Front = +z, up = +y.

Writes assets/faultline/{models/item,items,textures/item}/nestspider/<breed>/... into an unpacked FaultlineSMP pack.
Usage: python3 tools/nest_spider_models.py <unpacked-pack> [--preview <dir>]
"""
import json, os, random, sys, math
from PIL import Image, ImageDraw

BLACK = (26, 26, 30)
GREY = (52, 54, 60)
BREEDS = {"venom": ((90, 230, 60), (190, 255, 110)), "ember": ((255, 120, 20), (255, 210, 90)), "swift": ((50, 150, 255), (150, 220, 255))}


def noise_tex(seed, base, dark=BLACK, n=7):
    r = random.Random(seed)
    im = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            c = tuple(max(0, min(255, v + r.randint(-n, n))) for v in base)
            if r.random() < 0.07: c = dark
            im.putpixel((x, y), c + (255,))
    return im


def textures(glow, bright):
    T = {}
    T["chitin"] = noise_tex(1, GREY)
    T["chitin_dark"] = noise_tex(2, BLACK, (12, 12, 14))
    seg = noise_tex(3, BLACK)                         # leg segment: black with a lit ring at each end
    for x in range(16):
        for y in (0, 1, 14, 15): seg.putpixel((x, y), glow + (255,))
    T["band"] = seg
    hair = noise_tex(4, (38, 38, 44))
    r = random.Random(9)
    for _ in range(10):
        x, y = r.randint(0, 15), r.randint(0, 14)
        hair.putpixel((x, y), glow + (255,))
    T["hair"] = hair
    ab = Image.new("RGBA", (16, 16))                  # abdomen back: a glowing hourglass blotch over black
    for y in range(16):
        for x in range(16):
            dx, dy = abs(x - 7.5), abs(y - 7.5)
            c = BLACK
            if dx < 2.2 + (dy * 0.45 if dy > 2 else 0) and dy < 6.5: c = glow
            if dx < 1.0 and dy < 4.5: c = bright
            if (x in (2, 13) and 3 < y < 12) and y % 3 == 0: c = glow
            im_n = random.Random(x * 31 + y).randint(-6, 6)
            c = tuple(max(0, min(255, v + im_n)) for v in c)
            ab.putpixel((x, y), c + (255,))
    T["abdomen"] = ab
    eye = Image.new("RGBA", (16, 16), bright + (255,))
    for y in range(16):
        for x in range(16):
            if x < 2 or y < 2 or x > 13 or y > 13: eye.putpixel((x, y), glow + (255,))
    T["eye"] = eye
    fang = Image.new("RGBA", (16, 16))
    for y in range(16):
        t = y / 15
        c = tuple(int(a * (1 - t) + b * t) for a, b in zip(bright, glow))
        for x in range(16): fang.putpixel((x, y), c + (255,))
    T["fang"] = fang
    return T


def box(a, b, tex, faces=None):
    lo = [round(8 + v, 3) for v in a]; hi = [round(8 + v, 3) for v in b]
    t = {k: tex for k in ("north", "south", "east", "west", "up", "down")}
    if faces: t.update(faces)
    return {"from": lo, "to": hi, "faces": {k: {"texture": "#" + v, "uv": [0, 0, 16, 16]} for k, v in t.items()}}


def thorn(z, x, y, h):
    return box((x - 0.4, y, z - 0.4), (x + 0.4, y + h, z + 0.4), "hair")


PARTS = {
    "thorax": [box((-6, -3, -6), (6, 3, 6), "chitin", {"up": "chitin_dark"}),
               box((-4, 3, -4), (4, 4.5, 4), "chitin_dark"),
               box((-1, 4.5, -3), (1, 5.2, 3), "band"),
               thorn(-2, -3, 4.5, 2), thorn(-2, 3, 4.5, 2), thorn(2, -3, 4.5, 1.6), thorn(2, 3, 4.5, 1.6)],
    "head": [box((-4, -2.5, 0), (4, 3.5, 5), "chitin"),
             box((-3, 3.5, 0.5), (3, 4.5, 4), "chitin_dark")],
    "eyes": [box((-2.6, 0.8, 5), (-0.8, 2.6, 5.6), "eye"), box((0.8, 0.8, 5), (2.6, 2.6, 5.6), "eye"),
             box((-3.8, 1.5, 4.2), (-2.9, 2.4, 5.1), "eye"), box((2.9, 1.5, 4.2), (3.8, 2.4, 5.1), "eye"),
             box((-1.6, 2.9, 4.2), (-0.6, 3.9, 5.0), "eye"), box((0.6, 2.9, 4.2), (1.6, 3.9, 5.0), "eye")],
    "fang": [box((-1.6, -5, -1.6), (1.6, 0, 1.6), "chitin_dark"),
             box((-1, -8, -0.4), (1, -5, 1.4), "fang"),
             box((-0.5, -10, -1.6), (0.5, -8, 0.2), "fang")],
    "palp": [box((-0.8, -0.8, 0), (0.8, 0.8, 6), "chitin_dark"), box((-1, -1, 5), (1, 1, 6.5), "band")],
    "abdomen": [box((-8, -6, -11), (8, 7, 11), "chitin_dark", {"up": "abdomen"}),
                box((-6, 7, -9), (6, 9, 8), "abdomen", {"north": "chitin_dark", "south": "chitin_dark", "east": "chitin_dark", "west": "chitin_dark"}),
                box((-6, -7, -9), (6, -6, 9), "chitin"),
                box((-5, -4, 11), (5, 5, 12.5), "chitin"),
                box((-5, -4, -12.5), (5, 5, -11), "chitin"),
                box((-1.5, -2, -14), (1.5, 0.5, -12.5), "band"),
                box((-8.5, -1, -6), (-8, 3, 6), "band"), box((8, -1, -6), (8.5, 3, 6), "band")],
    # segmented legs: dark joints with lit rings, thin shafts
    "femur": [box((-1.4, -1.4, 0), (1.4, 1.4, 18), "chitin_dark"), box((-1.8, -1.8, 0), (1.8, 1.8, 2), "band"),
              box((-1.7, -1.7, 16), (1.7, 1.7, 18), "band"), box((-1.5, -1.5, 8), (1.5, 1.5, 9.5), "chitin")],
    "tibia": [box((-1.1, -1.1, 0), (1.1, 1.1, 20), "chitin_dark"), box((-1.4, -1.4, 18), (1.4, 1.4, 20), "band"),
              box((-1.2, -1.2, 9), (1.2, 1.2, 10.5), "chitin")],
    "tarsus": [box((-0.8, -0.8, 0), (0.8, 0.8, 13), "chitin_dark"), box((-0.6, -0.6, 12), (0.6, 0.6, 16), "fang"),
               box((-1, -1, 0), (1, 1, 1.4), "band")],
}


def main():
    pack = sys.argv[1]
    prev = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    A = os.path.join(pack, "assets", "faultline")
    for breed, (glow, bright) in BREEDS.items():
        td = os.path.join(A, "textures", "item", "nestspider", breed); os.makedirs(td, exist_ok=True)
        md = os.path.join(A, "models", "item", "nestspider", breed); os.makedirs(md, exist_ok=True)
        idf = os.path.join(A, "items", "nestspider", breed); os.makedirs(idf, exist_ok=True)
        T = textures(glow, bright)
        for k, im in T.items(): im.save(os.path.join(td, k + ".png"))
        for name, els in PARTS.items():
            tx = {k: f"faultline:item/nestspider/{breed}/{k}" for k in T}
            tx["particle"] = f"faultline:item/nestspider/{breed}/chitin_dark"
            with open(os.path.join(md, name + ".json"), "w") as fh: json.dump({"textures": tx, "elements": els}, fh, separators=(",", ":"))
            with open(os.path.join(idf, name + ".json"), "w") as fh:
                json.dump({"model": {"type": "minecraft:model", "model": f"faultline:item/nestspider/{breed}/{name}"}}, fh)
    eye_icon(A)
    print("breeds", list(BREEDS), "parts", len(PARTS))
    if prev: preview(prev)


def eye_icon(A):
    """The Broodmother's Eye accessory: a clump of black chitin holding eight eyes, the big one green and glowing."""
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    r = random.Random(21)
    for y in range(16):
        for x in range(16):
            d = math.hypot(x - 7.5, (y - 8) * 1.1)
            if d < 7.2:
                c = tuple(max(0, min(255, v + r.randint(-8, 8))) for v in (40, 30, 52))
                if d > 6.2: c = (18, 14, 24)
                im.putpixel((x, y), c + (255,))
    for (cx, cy, rr, col) in ((7.5, 8.5, 3.3, (90, 230, 60)), (3.5, 5, 1.2, (200, 30, 60)), (11.5, 5, 1.2, (200, 30, 60)),
                              (4, 11.5, 1.0, (200, 30, 60)), (11, 11.5, 1.0, (200, 30, 60)), (7.5, 3.2, 1.0, (200, 30, 60))):
        for y in range(16):
            for x in range(16):
                if math.hypot(x - cx, y - cy) < rr: im.putpixel((x, y), col + (255,))
    for (x, y) in ((7, 8), (8, 8), (7, 9), (8, 9)): im.putpixel((x, y), (10, 30, 8, 255))   # the pupil
    for (x, y) in ((6, 7), (3, 4), (11, 4)): im.putpixel((x, y), (240, 255, 230, 255))     # glints
    os.makedirs(os.path.join(A, "textures", "item"), exist_ok=True)
    im.save(os.path.join(A, "textures", "item", "brood_eye.png"))
    with open(os.path.join(A, "models", "item", "brood_eye.json"), "w") as fh:
        json.dump({"parent": "minecraft:item/generated", "textures": {"layer0": "faultline:item/brood_eye"}}, fh)
    with open(os.path.join(A, "items", "brood_eye.json"), "w") as fh:
        json.dump({"model": {"type": "minecraft:model", "model": "faultline:item/brood_eye"}}, fh)


def preview(out):
    sys.path.insert(0, os.path.dirname(__file__))
    import queen_spider_models as Q
    os.makedirs(out, exist_ok=True)
    sheet = Image.new("RGB", (1500, 420), (16, 18, 22))
    for vi, (breed, (glow, bright)) in enumerate(BREEDS.items()):
        cols = {"chitin": GREY, "chitin_dark": BLACK, "band": glow, "hair": (38, 38, 44), "abdomen": glow, "eye": bright, "fang": bright}
        img = Image.new("RGB", (500, 420), (16, 18, 22)); dr = ImageDraw.Draw(img)
        V = Q.mm(Q.rot_x(0.45), Q.rot_y(-0.6))
        polys = []
        for part, R, o in Q.pose(False):
            for e in PARTS[part]:
                lo = [v - 8 for v in e["from"]]; hi = [v - 8 for v in e["to"]]
                corners = [[x, y, z] for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])]
                w = [Q.mv(V, Q.add(o, Q.mv(R, c))) for c in corners]
                tex = e["faces"]["up"]["texture"][1:]
                for f in ((0, 1, 3, 2), (4, 5, 7, 6), (0, 1, 5, 4), (2, 3, 7, 6), (0, 2, 6, 4), (1, 3, 7, 5)):
                    pts = [w[k] for k in f]
                    polys.append((sum(p[2] for p in pts) / 4, [(250 + p[0] * 5, 260 - p[1] * 5) for p in pts], cols[tex]))
        for d, pts, c in sorted(polys, key=lambda t: t[0]):
            shade = max(0.5, min(1.15, 0.85 + d / 120))
            dr.polygon(pts, fill=tuple(min(255, int(v * shade)) for v in c), outline=(8, 8, 10))
        dr.text((10, 10), breed, fill=(220, 220, 220))
        sheet.paste(img, (vi * 500, 0))
    sheet.save(os.path.join(out, "nest_spiders.png"))


if __name__ == "__main__":
    main()
