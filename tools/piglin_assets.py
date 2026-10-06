#!/usr/bin/env python3
"""
The Piglin Raid's art (FaultlineRaids, Piglins.java): 16x16 pixel art from character grids.

Writes into an unpacked FaultlineSMP pack:
  items/piglin_war_horn.json + models/item/piglin_war_horn.json + textures/item/piglin_war_horn.png   the War Horn item
  textures/index/<id>.png    Index icons: piglin_war_horn, piglin_mage, piglin_summoner, piglin_balloonist,
                             hoglin_rider, the_bulwark, great_hog, piglin_sapper, piglin_shieldbearer, piglin_lobber,
                             piglin_runt, piglin_banner_bearer, piglin_medic
Usage: python3 tools/piglin_assets.py <unpacked-pack-dir> [--preview <dir>]
"""
import json, os, sys
from PIL import Image

PAL = {
    "#": (20, 14, 12), "k": (45, 32, 28),                       # outlines, dark horn
    "h": (92, 66, 52), "H": (130, 98, 76),                      # horn
    "G": (250, 204, 60), "g": (196, 140, 30), "y": (255, 236, 140),  # gold
    "P": (232, 150, 140), "p": (196, 110, 104), "n": (150, 70, 70),  # piglin skin
    "e": (255, 255, 255), "b": (24, 24, 24),                    # eyes
    "R": (190, 40, 30), "r": (130, 24, 20), "o": (240, 120, 40),     # red / orange
    "V": (110, 40, 150), "v": (70, 20, 95), "m": (200, 120, 255),    # purple, magic
    "W": (120, 84, 50), "w": (84, 56, 32),                      # wood / basket
    "C": (140, 140, 150), "c": (90, 90, 100),                   # rope / iron
    "N": (70, 60, 64), "M": (44, 38, 42),                       # netherite
    "T": (150, 104, 84), "t": (110, 74, 60), "f": (240, 230, 210),   # hoglin hide, tusks
}

ICONS = {
    "piglin_war_horn": [
        "................",
        "............##..",
        "...........#yG#.",
        "..........#Gg#..",
        ".........#hk#...",
        "........#hhk#...",
        ".......#GGg#....",
        "......#hhk#.....",
        ".....#hHhk#.....",
        "....#GGGg#......",
        "...#hHhk#.......",
        "..#hHHhk#.......",
        "..#hHhk#........",
        "..#kkk#.........",
        "...###..........",
        "................",
    ],
    "piglin_mage": [
        "................",
        "....##GGGG##....",
        "...#GGyGGyGG#...",
        "...#gGGGGGGg#...",
        "..##PPPPPPPP##..",
        "..#PPePbbPePP#..",
        "..#PPbPPPPbPP#..",
        "..#PPPnnnnPPP#..",
        "..#PPnbPPbnPP#..",
        "..#PPPnnnnPPP#..",
        "..#fPPPPPPPPf#..",
        "...#RRRRRRRR#...",
        "..#RRrRRRRrRR#..",
        "..#RRRoooRRRR#..",
        "...##########...",
        "................",
    ],
    "piglin_summoner": [
        "................",
        "....########....",
        "...#VVVVVVVV#...",
        "..#VVvvvvvvVV#..",
        "..#Vv#PPPP#vV#..",
        "..#VvPePPePvV#..",
        "..#VvPbPPbPvV#..",
        "..#VvPnnnnPvV#..",
        "..#VvnbPPbnvV#..",
        "..#VvPnnnnPvV#..",
        "..#VVvfPPfvVV#..",
        "...#VVVmmVVV#...",
        "..m#VVmVVmVV#m..",
        "...#VVVmmVVV#...",
        "....########....",
        "................",
    ],
    "piglin_balloonist": [
        "....########....",
        "...#RRRRRRRR#...",
        "..#RRRrRRRRRR#..",
        "..#RRRRRRrRRR#..",
        "..#GGGGGGGGGG#..",
        "..#RRrRRRRRRR#..",
        "...#RRRRRrRR#...",
        "....#RRRRRR#....",
        ".....#oooo#.....",
        ".....C....C.....",
        ".....C.PP.C.....",
        ".....CPbbPC.....",
        "....#WWWWWW#....",
        "....#WwWwWw#....",
        "....#WWWWWW#....",
        ".....######.....",
    ],
    "hoglin_rider": [
        "................",
        ".......GGG......",
        "......#PPP#.....",
        "......#PbP#.....",
        ".......#P#......",
        "..##########....",
        ".#TTTTTTTTTT#...",
        "#tTTTTTTTTTTT#..",
        "#TTeTTTTTTTTT#..",
        "#TTbTTTTTTTTT#..",
        "#ppppTTTTTTTT#..",
        "#pnnpTTTTTTTT#..",
        "f#pp#TTTTTTT#...",
        "f.##.#tt##tt#...",
        ".....#tt##tt#...",
        "................",
    ],
    "the_bulwark": [
        "................",
        "..############..",
        "..#NNNNNNNNNN#..",
        "..#NGGGGGGGGN#..",
        "..#NGNNNNNNGN#..",
        "..#NGNyGGyNGN#..",
        "..#NGNGGGGNGN#..",
        "..#NGNNGGNNGN#..",
        "..#NGNNGGNNGN#..",
        "..#NGNNNNNNGN#..",
        "..#NGGGGGGGGN#..",
        "...#NNNNNNNN#...",
        "....#NNNNNN#....",
        ".....#NNNN#.....",
        "......####......",
        "................",
    ],
    "great_hog": [
        "....G..G..G.....",
        "....GyGGGyG.....",
        "...#GGGGGGG#....",
        "..#TTTTTTTTT#...",
        ".#TtTTTTTTTtT#..",
        ".#TTRbTTTbRTT#..",
        ".#TTTTTTTTTTT#..",
        ".#TTpppppppTT#..",
        ".#TpnnpppnnpT#..",
        "f#TppppppppppT#f",
        "ff#TTpppppTT#ff.",
        ".ff#TTTTTTT#ff..",
        "...##TTTTT##....",
        ".....#####......",
        "................",
        "................",
    ],
    "piglin_sapper": [
        "....########....",
        "...#RRRRRRRR#...",
        "...#RyyRRyyR#...",
        "...#RRRRRRRR#...",
        "..##PPPPPPPP##..",
        "..#PPePbbPePP#..",
        "..#PPbPPPPbPP#..",
        "..#PPPnnnnPPP#..",
        "..#PPnbPPbnPP#..",
        "..#PPPnnnnPPP#..",
        "..#fPPPPPPPPf#..",
        "...##########...",
        "......#C#.......",
        ".....#oyo#......",
        "......#o#.......",
        "................",
    ],
    "piglin_shieldbearer": [
        "................",
        "...##########...",
        "..#GGGGGGGGGG#..",
        "..#GgggggggggG#.",
        "..#Gg##gg##gG#..",
        "..#Gg#PP#gggG#..",
        "..#GgPbbPgggG#..",
        "..#Gg#PP#gggG#..",
        "..#Gg##gg##gG#..",
        "..#GggggggggG#..",
        "...#GgggggggG#..",
        "....#GggggG#....",
        ".....#GggG#.....",
        "......#GG#......",
        ".......##.......",
        "................",
    ],
    "piglin_lobber": [
        "................",
        "......####......",
        "....##oooo##....",
        "...#ooRRRRoo#...",
        "..#oRRrrrRRRo#..",
        "..#oRryyrrRRo#..",
        ".#oRRryyrrRRRo#.",
        ".#oRRrrrrrRRRo#.",
        ".#oRRRrrRRRrRo#.",
        "..#oRRRRRRrRo#..",
        "..#ooRRRRRRoo#..",
        "...##oooooo##...",
        ".....######.....",
        "...o......o.....",
        ".o....o.....o...",
        "................",
    ],
    "piglin_runt": [
        "................",
        "................",
        "................",
        "....########....",
        "...#PPPPPPPP#...",
        "...#PePPPPeP#...",
        "...#PbPPPPbP#...",
        "...#PPnnnnPP#...",
        "...#PnbPPbnP#...",
        "...#PPnnnnPP#...",
        "....#fPPPPf#....",
        ".....######.....",
        ".....#GGGG#.....",
        "......#GG#......",
        ".......##.......",
        "................",
    ],
    "piglin_banner_bearer": [
        "..#.............",
        "..#############.",
        "..#ooooooooooo#.",
        "..#oyyyyyyyyyo#.",
        "..#oy#######yo#.",
        "..#oy#PPPPP#yo#.",
        "..#oy#PbPbP#yo#.",
        "..#oy#nnnnn#yo#.",
        "..#oy##PPP##yo#.",
        "..#oyy#####yyo#.",
        "..#oyyyyyyyyyo#.",
        "..#ooooooooooo#.",
        "..#.o.o.o.o.o...",
        "..#.............",
        "..#.............",
        "..#.............",
    ],
    "piglin_medic": [
        "................",
        "....########....",
        "...#ffffffff#...",
        "...#fffRRfff#...",
        "...#ffRRRRff#...",
        "..##fffRRfff##..",
        "..#PPPPPPPPPP#..",
        "..#PPePbbPePP#..",
        "..#PPbPPPPbPP#..",
        "..#PPPnnnnPPP#..",
        "..#PPnbPPbnPP#..",
        "..#PPPnnnnPPP#..",
        "...#ffffffff#...",
        "..#ffffGGffff#..",
        "...##########...",
        "................",
    ],
}


def draw(grid):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(grid):
        for x, ch in enumerate(row[:16]):
            if ch in PAL: im.putpixel((x, y), PAL[ch] + (255,))
    return im


def main():
    pack = sys.argv[1]
    prev = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    A = os.path.join(pack, "assets", "faultline")
    for d in ("items", "models/item", "textures/item", "textures/index"): os.makedirs(os.path.join(A, d), exist_ok=True)
    imgs = {k: draw(v) for k, v in ICONS.items()}
    for k, im in imgs.items(): im.save(os.path.join(A, "textures/index", k + ".png"))
    imgs["piglin_war_horn"].save(os.path.join(A, "textures/item/piglin_war_horn.png"))
    with open(os.path.join(A, "models/item/piglin_war_horn.json"), "w") as f:
        json.dump({"parent": "minecraft:item/handheld", "textures": {"layer0": "faultline:item/piglin_war_horn"}}, f, indent=2)
    with open(os.path.join(A, "items/piglin_war_horn.json"), "w") as f:
        json.dump({"model": {"type": "minecraft:model", "model": "faultline:item/piglin_war_horn"}}, f, indent=2)
    if prev:
        os.makedirs(prev, exist_ok=True)
        sheet = Image.new("RGBA", (len(imgs) * 136, 136), (200, 180, 140, 255))
        for i, im in enumerate(imgs.values()): sheet.alpha_composite(im.resize((128, 128), Image.NEAREST), (i * 136 + 4, 4))
        sheet.save(os.path.join(prev, "piglin_icons.png"))


if __name__ == "__main__":
    main()
