#!/usr/bin/env python3
"""
Faultline Ships art (FaultlineShips plugin): Index icons for the three blueprints and the Shipwright's Hammer.
The ships themselves are block displays and the items use vanilla models, so this is all the pack needs.

Writes into an unpacked FaultlineSMP pack:
  assets/faultline/textures/index/<id>.png   sloop_blueprint, brigantine_blueprint, galleon_blueprint, shipwright_hammer
  assets/faultline/font/index.json            two bitmap providers per icon (small 8 px, large 16 px), added if missing
Glyph code points (must match FaultlineIndex/glyphs.yml): small 0xE345.., large 0xE745..

With --bedrock <dir> it also writes the Bedrock (Geyser) side, which can't see the block displays Java players see:
  <dir>/FaultlineShips.mcpack            each ship (and its wreck) as one model, worn on the head of an armor stand that
                                          the plugin shows only to Bedrock players; plus icons for the hammer
  <dir>/faultline_ships_mappings.json    Geyser custom items: paper with item model faultline:ship/<type>[_wreck] -> that
                                          model; the hammer (a stick with the mace model) -> its icon
The layouts come from tools/ships/layouts.json (regenerate with tools/ship_layouts.sh after changing ShipType.java).
Usage: python3 tools/ship_assets.py <unpacked-pack-dir> [--preview <dir>] [--bedrock <dir>]
"""
import json, os, random, sys, zipfile
from PIL import Image

ICONS = ["sloop_blueprint", "brigantine_blueprint", "galleon_blueprint", "shipwright_hammer", "ship_cannon", "cannonball"]
SMALL0, LARGE0 = 0xE345, 0xE745

PAL = {
    "#": (20, 24, 40), "B": (40, 92, 170), "b": (28, 64, 128), "L": (90, 150, 220),  # blueprint paper
    "w": (235, 240, 250), "s": (180, 200, 230),                                        # chalk lines
    "W": (230, 230, 225), "G": (250, 200, 60), "D": (90, 230, 230),                    # tier trims
    "h": (120, 84, 50), "H": (160, 116, 70), "k": (70, 48, 28),                        # handle
    "I": (200, 205, 215), "i": (140, 145, 160), "j": (90, 95, 110),                    # iron head
}

BLUEPRINT = [
    "................",
    "..############..",
    ".#TTTTTTTTTTTT#.",
    ".#TBBBBBBBBBBT#.",
    ".#TBBBBwBBBBBT#.",
    ".#TBBBBwwBBBBT#.",
    ".#TBBBBwwwBBBT#.",
    ".#TBBBBwBBBBBT#.",
    ".#TBBBBwBBBBBT#.",
    ".#TBwwwwwwwwBT#.",
    ".#TBBwsssswBBT#.",
    ".#TBBBwwwwBBBT#.",
    ".#TLLLLLLLLLLT#.",
    ".#TTTTTTTTTTTT#.",
    "..############..",
    "................",
]

HAMMER = [
    "................",
    "......####......",
    ".....#IIII#.....",
    "....#IIIIIi#....",
    "....#iIIIij#....",
    ".....#ijj#h#....",
    "......###hk#....",
    ".........#hk#...",
    "..........#hk#..",
    "...........#hk#.",
    "............#hk#",
    ".............#h#",
    "..............##",
    "................",
    "................",
    "................",
]


def draw(grid, trim=None):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(grid):
        for x, ch in enumerate(row[:16]):
            if ch == "T":
                ch = trim
            if ch in PAL:
                im.putpixel((x, y), PAL[ch] + (255,))
    return im


def main():
    pack = sys.argv[1]
    prev = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    A = os.path.join(pack, "assets", "faultline")
    os.makedirs(os.path.join(A, "textures/index"), exist_ok=True)
    imgs = {
        "sloop_blueprint": draw(BLUEPRINT, "W"),
        "brigantine_blueprint": draw(BLUEPRINT, "G"),
        "galleon_blueprint": draw(BLUEPRINT, "D"),
        "shipwright_hammer": draw(HAMMER),
        "ship_cannon": icon_of(CANNON_ICON),
        "cannonball": icon_of(BALL_ICON),
    }
    for k, im in imgs.items():
        im.save(os.path.join(A, "textures/index", k + ".png"))
    ctex = java_cannons(A)
    font = os.path.join(A, "font/index.json")
    if os.path.exists(font):
        d = json.load(open(font))
        have = {(p.get("file"), p.get("height")) for p in d["providers"]}
        for n, k in enumerate(ICONS):
            f = "faultline:index/" + k + ".png"
            if (f, 8) not in have:
                d["providers"].append({"type": "bitmap", "file": f, "ascent": 7, "height": 8, "chars": [chr(SMALL0 + n)]})
            if (f, 16) not in have:
                d["providers"].append({"type": "bitmap", "file": f, "ascent": 7, "height": 16, "chars": [chr(LARGE0 + n)]})
        with open(font, "w") as fh:
            json.dump(d, fh, indent=1)
    if prev:
        os.makedirs(prev, exist_ok=True)
        preview_cannon(ctex, os.path.join(prev, "ship_cannon.png"))
    if "--bedrock" in sys.argv:
        bout = sys.argv[sys.argv.index("--bedrock") + 1]
        layouts = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "ships", "layouts.json")))
        rp, tex = bedrock_pack(bout, layouts, imgs)
        if prev:
            os.makedirs(prev, exist_ok=True)
            preview_bedrock(rp, tex, os.path.join(prev, "ships_bedrock.png"))
        import shutil
        shutil.rmtree(rp)
    if prev:
        os.makedirs(prev, exist_ok=True)
        sheet = Image.new("RGBA", (len(imgs) * 136, 136), (200, 180, 140, 255))
        for i, im in enumerate(imgs.values()):
            sheet.alpha_composite(im.resize((128, 128), Image.NEAREST), (i * 136 + 4, 4))
        sheet.save(os.path.join(prev, "ship_icons.png"))


# ====================================================================== cannons (Java item models + Index icons)
def cannon_texture():
    """32x32: iron, dark iron (rings), wood, dark wood, the muzzle, a wheel."""
    rnd = random.Random(11)
    im = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    def fill(x0, y0, base, n, fn=None):
        for y in range(16):
            for x in range(16):
                c = fn(x, y) if fn else base
                im.putpixel((x0 + x, y0 + y), tuple(max(0, min(255, v + rnd.randint(-n, n))) for v in c) + (255,))
    fill(0, 0, (78, 80, 88), 7, lambda x, y: (96, 98, 108) if (x + y) % 7 == 0 else (74, 76, 84))       # iron
    fill(16, 0, (40, 40, 46), 5)                                                                          # dark iron
    fill(0, 16, (128, 92, 54), 7, lambda x, y: (100, 70, 40) if y % 5 == 0 else (132, 96, 58))          # wood
    fill(16, 16, (86, 60, 36), 6, lambda x, y: (70, 48, 28) if x % 4 == 0 else (90, 62, 38))            # dark wood
    # the muzzle face (uv 12,4 -> 16,8 = pixels 24..31, 8..15): a black bore in the dark iron
    for y in range(10, 14):
        for x in range(26, 30): im.putpixel((x, y), (8, 8, 10, 255))
    return im


UV = {"iron": [0, 0, 8, 8], "dark": [8, 0, 12, 4], "wood": [0, 8, 8, 16], "wood_dark": [8, 8, 16, 16]}


def el(frm, to, tex, rot=None, muzzle=False):
    faces = {f: {"uv": UV[tex], "texture": "#t"} for f in ("north", "south", "east", "west", "up", "down")}
    if muzzle: faces["south"] = {"uv": [12, 4, 16, 8], "texture": "#t"}  # the bore
    e = {"from": [round(v, 3) for v in frm], "to": [round(v, 3) for v in to], "faces": faces}
    if rot: e["rotation"] = rot
    return e


def cannon_elements():
    """A naval cannon on a wooden carriage, barrel pointing south (+z), out past the block edge like it pokes over the rail."""
    e = []
    # carriage
    e.append(el([3.5, 0.5, 1.5], [5.5, 6, 12.5], "wood"))
    e.append(el([10.5, 0.5, 1.5], [12.5, 6, 12.5], "wood"))
    e.append(el([5.5, 1.5, 2.5], [10.5, 3, 11.5], "wood_dark"))
    e.append(el([2, 1.5, 9.5], [14, 3, 11], "wood_dark"))
    e.append(el([2, 1.5, 3], [14, 3, 4.5], "wood_dark"))
    # wheels: a box and the same box turned 45 degrees, so they read as round
    for wx0, wx1 in ((1.25, 3), (13, 14.75)):
        for zc in (3.75, 10.25):
            e.append(el([wx0, 0, zc - 2.25], [wx1, 4.5, zc + 2.25], "wood_dark"))
            e.append(el([wx0, 0, zc - 2.25], [wx1, 4.5, zc + 2.25], "wood_dark", {"angle": 45, "axis": "x", "origin": [8, 2.25, zc]}))
    # barrel
    e.append(el([5, 4.5, 0], [11, 10.5, 5.5], "iron"))
    e.append(el([5, 4.5, 0], [11, 10.5, 5.5], "iron", {"angle": 45, "axis": "z", "origin": [8, 7.5, 2.75]}))
    e.append(el([5.5, 5, 5.5], [10.5, 10, 11.5], "iron"))
    e.append(el([5.5, 5, 5.5], [10.5, 10, 11.5], "iron", {"angle": 45, "axis": "z", "origin": [8, 7.5, 8.5]}))
    e.append(el([6, 5.5, 11.5], [10, 9.5, 18], "iron"))
    e.append(el([5.25, 4.75, 5.25], [10.75, 10.25, 6.25], "dark"))
    e.append(el([5.75, 5.25, 11.25], [10.25, 9.75, 12.25], "dark"))
    e.append(el([5.5, 5, 17.5], [10.5, 10, 19.5], "dark", muzzle=True))
    e.append(el([7, 6.5, -1.5], [9, 8.5, 0], "dark"))            # the knob at the back
    e.append(el([4, 7, 7.5], [12, 8, 8.5], "dark"))              # trunnions
    return e


def ball_elements():
    return [el([5.5, 5.5, 5.5], [10.5, 10.5, 10.5], "dark"), el([5, 6, 6], [11, 10, 10], "dark"),
            el([6, 5, 6], [10, 11, 10], "dark"), el([6, 6, 5], [10, 10, 11], "dark")]


CANNON_ICON = [
    "................",
    "................",
    "................",
    "..........#.....",
    "...#######i#....",
    "..#IIIIIIIIi##..",
    ".#IIIIIIIIIIdd#.",
    ".#iIIIIIIIIidb#.",
    "..#iiiiiiiiid#..",
    "...#WWW##WWW#...",
    "..#WwwW#WwwW#...",
    "..#WwkW#WwkW#...",
    "..#WwwW#WwwW#...",
    "...#WW#..#WW#...",
    "....##....##....",
    "................",
]
BALL_ICON = [
    "................",
    "................",
    "................",
    "................",
    "......####......",
    ".....#iIIi#.....",
    "....#iIwIIi#....",
    "....#IIIIIj#....",
    "....#IIIIij#....",
    "....#iIIijj#....",
    ".....#ijjj#.....",
    "......####......",
    "................",
    "................",
    "................",
    "................",
]
ICON_PAL = {"#": (16, 16, 20), "I": (96, 98, 110), "i": (66, 68, 78), "j": (44, 44, 52), "d": (34, 34, 40), "b": (10, 10, 12),
            "w": (220, 220, 230), "W": (130, 92, 54), "k": (70, 48, 28)}


def icon_of(grid):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(grid):
        for x, ch in enumerate(row[:16]):
            if ch in ICON_PAL: im.putpixel((x, y), ICON_PAL[ch] + (255,))
    return im


def java_cannons(A):
    """Writes the cannon and cannonball item models (and their item definitions) into the Java pack."""
    tex = cannon_texture()
    os.makedirs(os.path.join(A, "textures/item"), exist_ok=True)
    tex.save(os.path.join(A, "textures/item/ship_cannon.png"))
    gui = {"gui": {"rotation": [25, 135, 0], "translation": [0, -1, 0], "scale": [0.5, 0.5, 0.5]},
           "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.4, 0.4, 0.4]},
           "fixed": {"rotation": [0, 180, 0], "scale": [0.6, 0.6, 0.6]},
           "thirdperson_righthand": {"rotation": [70, 0, 0], "translation": [0, 1, -2], "scale": [0.35, 0.35, 0.35]},
           "firstperson_righthand": {"rotation": [0, 135, 0], "translation": [2, 2, 0], "scale": [0.35, 0.35, 0.35]}}
    _w(os.path.join(A, "models/item/ship_cannon.json"), {"textures": {"t": "faultline:item/ship_cannon", "particle": "faultline:item/ship_cannon"},
                                                         "elements": cannon_elements(), "display": gui})
    _w(os.path.join(A, "items/ship_cannon.json"), {"model": {"type": "minecraft:model", "model": "faultline:item/ship_cannon"}})
    ball_disp = {"ground": {"translation": [0, 0, 0], "scale": [1.0, 1.0, 1.0]},
                 "gui": {"rotation": [30, 45, 0], "scale": [1.1, 1.1, 1.1]},
                 "thirdperson_righthand": {"translation": [0, 2, 1], "scale": [0.6, 0.6, 0.6]},
                 "firstperson_righthand": {"translation": [1, 2, 0], "scale": [0.6, 0.6, 0.6]}}
    _w(os.path.join(A, "models/item/cannonball.json"), {"textures": {"t": "faultline:item/ship_cannon", "particle": "faultline:item/ship_cannon"},
                                                        "elements": ball_elements(), "display": ball_disp})
    _w(os.path.join(A, "items/cannonball.json"), {"model": {"type": "minecraft:model", "model": "faultline:item/cannonball"}})
    return tex


def preview_cannon(tex, out):
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import numpy as np
    import jacob_models as jm
    jm.set_anchor(0.62)
    t = np.asarray(tex).astype(float)
    shots = [jm.render([(cannon_elements(), t, (0, 0, 0))], yaw, 22, 14, (360, 300)) for yaw in (30, 150, 250)]
    shots.append(jm.render([(ball_elements(), t, (0, 0, 0))], 30, 22, 14, (360, 300)))
    sheet = Image.new("RGBA", (360 * len(shots), 300))
    for i, sh in enumerate(shots): sheet.paste(sh, (i * 360, 0))
    sheet.save(out)


# ====================================================================== Bedrock
TILE = {"planks": (0, 0), "log_side": (16, 0), "log_top": (32, 0), "wool": (48, 0),
        "barrel_side": (0, 16), "barrel_top": (16, 16), "lantern": (32, 16), "dark": (48, 16),
        "iron": (0, 32), "iron_dark": (16, 32), "glass": (32, 32), "ladder": (48, 32)}
ATLAS_H = 48


def atlas():
    """64x32: oak-ish planks, spruce-ish log, white wool, a barrel, a lantern."""
    rnd = random.Random(7)
    im = Image.new("RGBA", (64, ATLAS_H), (0, 0, 0, 0))
    px = im.putpixel

    def noise(c, n):
        return tuple(max(0, min(255, v + rnd.randint(-n, n))) for v in c) + (255,)
    ox, oy = TILE["planks"]
    for y in range(16):
        for x in range(16):
            c = (176, 140, 88) if (y % 4) else (122, 92, 54)
            if y % 4 and x == (5 if (y // 4) % 2 else 11): c = (130, 100, 60)
            px((ox + x, oy + y), noise(c, 8))
    ox, oy = TILE["log_side"]
    for y in range(16):
        for x in range(16):
            c = (92, 66, 40) if x % 3 else (64, 44, 26)
            px((ox + x, oy + y), noise(c, 8))
    ox, oy = TILE["log_top"]
    for y in range(16):
        for x in range(16):
            r = max(abs(x - 7.5), abs(y - 7.5))
            c = (92, 66, 40) if r > 6 else ((150, 116, 72) if int(r) % 2 else (128, 96, 58))
            px((ox + x, oy + y), noise(c, 6))
    ox, oy = TILE["wool"]
    for y in range(16):
        for x in range(16):
            px((ox + x, oy + y), noise((232, 232, 226), 7))
    ox, oy = TILE["barrel_side"]
    for y in range(16):
        for x in range(16):
            c = (70, 70, 74) if y in (2, 13) else ((120, 86, 50) if x % 4 else (90, 62, 36))
            px((ox + x, oy + y), noise(c, 6))
    ox, oy = TILE["barrel_top"]
    for y in range(16):
        for x in range(16):
            c = (70, 70, 74) if x in (0, 15) or y in (0, 15) else ((40, 30, 20) if 6 <= x <= 9 and 6 <= y <= 9 else (140, 104, 62))
            px((ox + x, oy + y), noise(c, 5))
    ox, oy = TILE["lantern"]
    for y in range(16):
        for x in range(16):
            edge = x in (0, 15) or y in (0, 15)
            px((ox + x, oy + y), (50, 52, 60, 255) if edge else noise((255, 196, 90), 20))
    ox, oy = TILE["dark"]
    for y in range(16):
        for x in range(16):
            px((ox + x, oy + y), noise((70, 52, 34), 6))
    ox, oy = TILE["iron"]
    for y in range(16):
        for x in range(16):
            px((ox + x, oy + y), noise((80, 82, 90), 7))
    ox, oy = TILE["iron_dark"]
    for y in range(16):
        for x in range(16):
            px((ox + x, oy + y), (10, 10, 12, 255) if 5 <= x <= 10 and 5 <= y <= 10 else noise((42, 42, 48), 4))
    ox, oy = TILE["glass"]
    for y in range(16):
        for x in range(16):
            edge = x in (0, 15) or y in (0, 15)
            px((ox + x, oy + y), (200, 210, 220, 255) if edge else ((170, 215, 235, 255) if (x - y) % 6 else (215, 240, 250, 255)))
    ox, oy = TILE["ladder"]
    for y in range(16):
        for x in range(16):
            rail = x in (2, 3, 12, 13)
            rung = y % 4 == 1 and 2 <= x <= 13
            px((ox + x, oy + y), noise((140, 104, 62), 6) if rail or rung else (0, 0, 0, 0))
    return im


FULL = {"PLANKS", "LOG", "WOOL", "STAIRS", "CHEST"}
# local +x (bow) -> Bedrock -z (an entity's front), local +z (starboard) -> Bedrock -x (its right)
FACE_OF = {(1, 0, 0): "north", (-1, 0, 0): "south", (0, 0, 1): "west", (0, 0, -1): "east", (0, 1, 0): "up", (0, -1, 0): "down"}


def face_uv(tile, w=16, h=16, u0=0, v0=0):
    ox, oy = TILE[tile]
    return {"uv": [ox + u0, oy + v0], "uv_size": [w, h]}


def to_bedrock(x0, y0, z0, x1, y1, z1):
    """A local box (blocks) -> Bedrock cube origin/size (pixels)."""
    bx0, bx1 = -z1 * 16, -z0 * 16
    bz0, bz1 = -x1 * 16, -x0 * 16
    return [round(bx0, 3), round(y0 * 16, 3), round(bz0, 3)], [round(bx1 - bx0, 3), round((y1 - y0) * 16, 3), round(bz1 - bz0, 3)]


def ship_cubes(cells, wreck):
    torn = set()
    if wreck:  # same rule as Ship.scaleOf: torn sails
        for i, c in enumerate(cells):
            if c[3] == "WOOL" and (i % 2 == 0 or c[1] % 3 == 0): torn.add(i)
    full = {(c[0], c[1], c[2]) for i, c in enumerate(cells) if c[3] in FULL and i not in torn}
    cubes = []
    for i, (x, y, z, need, props) in enumerate(cells):
        if i in torn: continue
        pr = dict(kv.split("=") for kv in props.split(",") if "=" in kv)
        if need in FULL:
            side, top = {"LOG": ("log_side", "log_top"), "WOOL": ("wool", "wool"), "CHEST": ("barrel_side", "barrel_top")}.get(need, ("planks", "planks"))
            o, sz = to_bedrock(x - 0.5, y, z - 0.5, x + 0.5, y + 1, z + 0.5)
            uv = {}
            for (dx, dy, dz), f in FACE_OF.items():
                if (x + dx, y + dy, z + dz) in full: continue  # hidden face
                uv[f] = face_uv(top if f in ("up", "down") else side)
            if uv: cubes.append({"origin": o, "size": sz, "uv": uv})
        elif need == "FENCE":
            o, sz = to_bedrock(x - 0.125, y, z - 0.125, x + 0.125, y + 1, z + 0.125)
            cubes.append({"origin": o, "size": sz, "uv": {f: face_uv("planks", 4, 16) for f in FACE_OF.values()}})
            arms = {"east": (0.125, 0.5, None), "west": (-0.5, -0.125, None), "south": (None, None, (0.125, 0.5)), "north": (None, None, (-0.5, -0.125))}
            for d, (a0, a1, zr) in arms.items():
                if pr.get(d) != "true": continue
                for (h0, h1) in ((6 / 16, 9 / 16), (12 / 16, 15 / 16)):
                    if zr is None: box = (x + a0, y + h0, z - 0.0625, x + a1, y + h1, z + 0.0625)
                    else: box = (x - 0.0625, y + h0, z + zr[0], x + 0.0625, y + h1, z + zr[1])
                    o, sz = to_bedrock(*box)
                    cubes.append({"origin": o, "size": sz, "uv": {f: face_uv("planks", 2, 2) for f in FACE_OF.values()}})
        elif need == "LADDER":
            s_ = 1 if "south" in props else -1
            wall = z - s_ * 0.5
            z0, z1 = sorted((wall, wall + s_ * 0.0625))
            o, sz = to_bedrock(x - 0.5, y, z0, x + 0.5, y + 1, z1)
            cubes.append({"origin": o, "size": sz, "uv": {f: face_uv("ladder") for f in FACE_OF.values()}})
        elif need == "TRAPDOOR":
            o, sz = to_bedrock(x - 0.5, y + 13 / 16, z - 0.5, x + 0.5, y + 1, z + 0.5)
            cubes.append({"origin": o, "size": sz, "uv": {f: face_uv("planks", 16, 3 if f not in ("up", "down") else 16) for f in FACE_OF.values()}})
        elif need == "PANE":
            along_x = pr.get("east") == "true" or pr.get("west") == "true"
            box = (x - 0.5, y, z - 0.0625, x + 0.5, y + 1, z + 0.0625) if along_x else (x - 0.0625, y, z - 0.5, x + 0.0625, y + 1, z + 0.5)
            o, sz = to_bedrock(*box)
            cubes.append({"origin": o, "size": sz, "uv": {f: face_uv("glass") for f in FACE_OF.values()}})
        elif need == "CANNON":
            s_ = 1 if "south" in props else -1
            def zb(a, b): return tuple(sorted((z + s_ * a, z + s_ * b)))
            parts = [((x - 0.31, y, *zb(-0.4, 0.3)), (x + 0.31, y + 0.36), "planks"),         # carriage
                     ((x - 0.19, y + 0.28, *zb(-0.45, 0.35)), (x + 0.19, y + 0.66), "iron"),   # breech
                     ((x - 0.14, y + 0.33, *zb(0.35, 1.05)), (x + 0.14, y + 0.61), "iron"),    # chase
                     ((x - 0.17, y + 0.30, *zb(1.0, 1.2)), (x + 0.17, y + 0.64), "iron_dark")]  # muzzle
            for wz in (-0.28, 0.2):                                                            # wheels
                for wx in (-0.37, 0.31):
                    parts.append(((x + wx, y, *zb(wz - 0.13, wz + 0.13)), (x + wx + 0.06, y + 0.28), "dark"))
            for (a0, b0, za, zb_), (a1, b1), t in parts:
                o, sz = to_bedrock(a0, b0, za, a1, b1, zb_)
                cubes.append({"origin": o, "size": sz, "uv": {f: face_uv(t, 4, 4) for f in FACE_OF.values()}})
        elif need == "LANTERN":
            o, sz = to_bedrock(x - 0.1875, y, z - 0.1875, x + 0.1875, y + 0.4375, z + 0.1875)
            cubes.append({"origin": o, "size": sz, "uv": {f: face_uv("lantern", 6, 7) for f in FACE_OF.values()}})
    return cubes


def bedrock_pack(bout, layouts, icons):
    rp = os.path.join(bout, "rp_ships")
    tex = atlas()
    os.makedirs(os.path.join(rp, "textures/faultline"), exist_ok=True)
    tex.save(os.path.join(rp, "textures/faultline/ship_atlas.png"))
    os.makedirs(os.path.join(rp, "textures/items/faultline"), exist_ok=True)
    icons["galleon_blueprint"].save(os.path.join(rp, "textures/items/faultline/ship_icon.png"))
    icons["shipwright_hammer"].save(os.path.join(rp, "textures/items/faultline/shipwright_hammer.png"))
    item_tex = {"resource_pack_name": "faultline_ships", "texture_name": "atlas.items", "texture_data": {
        "faultline.ship_icon": {"textures": "textures/items/faultline/ship_icon"},
        "faultline.shipwright_hammer": {"textures": "textures/items/faultline/shipwright_hammer"},
        "faultline.ship_cannon": {"textures": "textures/items/faultline/ship_cannon"},
        "faultline.cannonball": {"textures": "textures/items/faultline/cannonball"}}}
    icons["ship_cannon"].save(os.path.join(rp, "textures/items/faultline/ship_cannon.png"))
    icons["cannonball"].save(os.path.join(rp, "textures/items/faultline/cannonball.png"))
    paper, stick = [], []
    for name, lay in layouts.items():
        for wreck in (False, True):
            key = name + ("_wreck" if wreck else "")
            cubes = ship_cubes(lay["cells"], wreck)
            geo = {"format_version": "1.16.0", "minecraft:geometry": [{
                "description": {"identifier": f"geometry.faultline.ship_{key}", "texture_width": 64, "texture_height": ATLAS_H,
                                "visible_bounds_width": 48, "visible_bounds_height": 40, "visible_bounds_offset": [0, 10, 0]},
                "bones": [{"name": "body", "pivot": [0, 24, 0], "cubes": cubes}]}]}
            _w(os.path.join(rp, "models/entity/faultline", f"ship_{key}.geo.json"), geo)
            _w(os.path.join(rp, "attachables", f"faultline.ship_{key}.json"), {"format_version": "1.10.0", "minecraft:attachable": {"description": {
                "identifier": f"faultline:ship_{key}", "materials": {"default": "armor", "enchanted": "armor_enchanted"},
                "textures": {"default": "textures/faultline/ship_atlas", "enchanted": "textures/misc/enchanted_actor_glint"},
                "geometry": {"default": f"geometry.faultline.ship_{key}"}, "scripts": {"parent_setup": "v.helmet_layer_visible = 0.0;"},
                "render_controllers": ["controller.render.armor"]}}})
            paper.append({"type": "definition", "model": f"faultline:ship/{key}", "bedrock_identifier": f"faultline:ship_{key}",
                          "display_name": lay["title"] + (" (wrecked)" if wreck else ""),
                          "bedrock_options": {"icon": "faultline.ship_icon", "allow_offhand": False},
                          "components": {"minecraft:equippable": {"slot": "head"}, "minecraft:max_stack_size": 1}})
    for key, title in (("ship_cannon", "Ship Cannon"), ("cannonball", "Cannonball")):
        paper.append({"type": "definition", "model": f"faultline:{key}", "bedrock_identifier": f"faultline:{key}", "display_name": title,
                      "bedrock_options": {"icon": f"faultline.{key}", "allow_offhand": key == "cannonball"},
                      "components": {"minecraft:max_stack_size": 64}})
    stick.append({"type": "definition", "model": "minecraft:mace", "bedrock_identifier": "faultline:shipwright_hammer",
                  "display_name": "Shipwright's Hammer", "bedrock_options": {"icon": "faultline.shipwright_hammer", "allow_offhand": False},
                  "components": {"minecraft:max_stack_size": 1}})
    _w(os.path.join(rp, "textures/item_texture.json"), item_tex)
    _w(os.path.join(rp, "manifest.json"), {"format_version": 2, "header": {
        "name": "Faultline SMP Ships", "description": "Ships for Bedrock players (Geyser)",
        "uuid": "2b7e5d10-4c8a-4f3e-9d21-7a6b0c9e5f14", "version": [1, 0, 0], "min_engine_version": [1, 21, 0]},
        "modules": [{"description": "Ships", "type": "resources", "uuid": "9e1f3a5c-6b2d-4a8e-b7c0-3d5f7a9b1c26", "version": [1, 0, 0]}]})
    with zipfile.ZipFile(os.path.join(bout, "FaultlineShips.mcpack"), "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(rp):
            for fn in sorted(files):
                full = os.path.join(root, fn); z.write(full, os.path.relpath(full, rp))
    _w(os.path.join(bout, "faultline_ships_mappings.json"), {"format_version": 2, "items": {"minecraft:paper": paper, "minecraft:stick": stick}})
    return rp, tex


def _w(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f: json.dump(obj, f, indent=1)


def preview_bedrock(rp, tex, out):
    """Draws each Bedrock ship model as Bedrock would place it on an armor stand facing north (-z): the bow must point up the sheet."""
    sheets = []
    for name in ("sloop", "brigantine", "galleon", "galleon_wreck"):
        geo = json.load(open(os.path.join(rp, "models/entity/faultline", f"ship_{name}.geo.json")))
        cubes = geo["minecraft:geometry"][0]["bones"][0]["cubes"]
        S = 3.0
        def iso(x, y, z): return ((x - z) * 0.87 * S / 1.0, (x + z) * 0.5 * S - y * S)
        pts = [iso(c["origin"][0] + dx * c["size"][0], c["origin"][1] + dy * c["size"][1], c["origin"][2] + dz * c["size"][2]) for c in cubes for dx in (0, 1) for dy in (0, 1) for dz in (0, 1)]
        mnx, mxx = min(p[0] for p in pts) - 10, max(p[0] for p in pts) + 10
        mny, mxy = min(p[1] for p in pts) - 30, max(p[1] for p in pts) + 10
        im = Image.new("RGB", (int(mxx - mnx), int(mxy - mny)), (40, 90, 140))
        from PIL import ImageDraw
        d = ImageDraw.Draw(im)
        def P(x, y, z): a = iso(x, y, z); return (a[0] - mnx, a[1] - mny)
        def avg(fu):
            u, v = fu["uv"]; w, h = fu["uv_size"]
            reg = tex.crop((int(u), int(v), int(u + max(1, w)), int(v + max(1, h)))).convert("RGB").resize((1, 1))
            return reg.getpixel((0, 0))
        for c in sorted(cubes, key=lambda c: (c["origin"][0] + c["origin"][2], c["origin"][1])):
            (x0, y0, z0), (sx, sy, sz) = c["origin"], c["size"]
            x1, y1, z1 = x0 + sx, y0 + sy, z0 + sz
            uv = c["uv"]
            def col(f, k): fc = avg(uv[f]) if f in uv else None; return None if fc is None else tuple(int(v * k) for v in fc)
            for f, poly, k in (("south", [P(x0, y0, z1), P(x1, y0, z1), P(x1, y1, z1), P(x0, y1, z1)], 0.72),
                               ("east", [P(x1, y0, z0), P(x1, y0, z1), P(x1, y1, z1), P(x1, y1, z0)], 0.86),
                               ("up", [P(x0, y1, z0), P(x1, y1, z0), P(x1, y1, z1), P(x0, y1, z1)], 1.0)):
                cc = col(f, k)
                if cc: d.polygon(poly, fill=cc)
        a, b = P(0, 0, 40), P(0, 0, -40)
        d.line([a, b], fill=(255, 60, 60), width=2)
        d.text((b[0] + 4, b[1] - 6), "front (-z)", fill=(255, 255, 255))
        d.text((6, 4), name, fill=(255, 255, 255))
        sheets.append(im)
    W = max(i.width for i in sheets); H = sum(i.height for i in sheets)
    sheet = Image.new("RGB", (W, H), (40, 90, 140)); yy = 0
    for i in sheets: sheet.paste(i, (0, yy)); yy += i.height
    sheet.save(out)


if __name__ == "__main__":
    main()
