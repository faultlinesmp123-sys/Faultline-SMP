#!/usr/bin/env python3
"""
Faultline Ships art (FaultlineShips plugin): Index icons for the three blueprints and the Shipwright's Hammer.
The ships themselves are block displays and the items use vanilla models, so this is all the pack needs.

Writes into an unpacked FaultlineSMP pack:
  assets/faultline/textures/index/<id>.png   sloop_blueprint, brigantine_blueprint, galleon_blueprint, shipwright_hammer
  assets/faultline/font/index.json            two bitmap providers per icon (small 8 px, large 16 px), added if missing
Glyph code points (must match FaultlineIndex/glyphs.yml): small 0xE345.., large 0xE745..
Usage: python3 tools/ship_assets.py <unpacked-pack-dir> [--preview <dir>]
"""
import json, os, sys
from PIL import Image

ICONS = ["sloop_blueprint", "brigantine_blueprint", "galleon_blueprint", "shipwright_hammer"]
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
    }
    for k, im in imgs.items():
        im.save(os.path.join(A, "textures/index", k + ".png"))
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
        sheet = Image.new("RGBA", (len(imgs) * 136, 136), (200, 180, 140, 255))
        for i, im in enumerate(imgs.values()):
            sheet.alpha_composite(im.resize((128, 128), Image.NEAREST), (i * 136 + 4, 4))
        sheet.save(os.path.join(prev, "ship_icons.png"))


if __name__ == "__main__":
    main()
