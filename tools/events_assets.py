#!/usr/bin/env python3
"""
Art for the meteor strikes, the Caravan, Grimtusk (the Piglin Warlord) and the achievements, as 16x16 pixel art.

Writes into an unpacked FaultlineSMP pack:
  textures/index/<id>.png      Index icons: grimtusk, ironhide, bastion_guard, bastion_crossbowman, warlord_challenge,
                               warlord_cleaver, molten_husk, meteor_crawler, star_sentinel, meteorite, caravan_merchant,
                               and the achievement cosmetics (slayer_crown, champion_cape, meteor_pendant, pirate_hat)
  items/ models/item/ textures/item/  warlord_challenge (flat) and warlord_cleaver (held like an axe)
  font/index.json              two bitmap providers per new icon (small 8 px, large 16 px)
and the matching glyph numbers into plugins/FaultlineIndex/src/main/resources/glyphs.yml.
Run cosmetics_assets.py first (it draws the cosmetic icons and the Candy / Present icons this copies).

Usage: python3 tools/events_assets.py <unpacked-pack-dir> [--preview <dir>]
"""
import json, os, re, sys
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cosmetics_new as COS

PAL = {
    "#": (20, 14, 12), "k": (45, 32, 28),
    "G": (250, 204, 60), "g": (196, 140, 30), "y": (255, 236, 140),
    "P": (232, 150, 140), "p": (196, 110, 104), "n": (150, 70, 70),
    "e": (255, 255, 255), "b": (24, 24, 24),
    "R": (190, 40, 30), "r": (130, 24, 20), "o": (240, 120, 40), "O": (255, 170, 60),
    "N": (70, 60, 64), "M": (44, 38, 42), "L": (100, 90, 96),
    "T": (150, 104, 84), "t": (110, 74, 60), "f": (240, 230, 210),
    "C": (140, 140, 150), "c": (90, 90, 100),
    "W": (120, 84, 50), "w": (84, 56, 32),
    "S": (196, 180, 140), "s": (150, 134, 100),                  # husk skin
    "m": (120, 40, 20), "h": (255, 110, 20),                     # magma
    "x": (34, 28, 36), "X": (60, 50, 66), "v": (190, 80, 255),   # meteor rock, violet
    "B": (255, 200, 40), "u": (60, 110, 200), "U": (110, 160, 230), "q": (40, 130, 70), "Q": (90, 180, 110),
    "d": (60, 40, 30), "D": (200, 170, 120),
}

ICONS = {
    "grimtusk": [
        "...GgGGGGgG.....",
        "..GNNNNNNNNG....",
        "..NMNNNNNNMN....",
        ".#PPPPPPPPPP#...",
        ".#PeePPPPeeP#...",
        ".#PbePPPPbeP#...",
        ".#PPnnnnnnPP#...",
        ".#PnbPPPPbnP#...",
        "#fPPnnnnnnPPf#..",
        "#ffPPPPPPPPff#..",
        ".#NGNNNNNNGN#.y.",
        ".#NNGNNNNGNN#yGy",
        ".#NNNGGGGNNN#.g.",
        "..#NNNNNNNN#..g.",
        "...########...g.",
        "..............g.",
    ],
    "ironhide": [
        "................",
        "....CC....CC....",
        "...#TTTTTTTT#...",
        "..#TTCCTTCCTT#..",
        "..#TeTTTTTTeT#..",
        "..#TbTTTTTTbT#..",
        ".#TTTTTTTTTTTT#.",
        ".#TTtttttttTTT#.",
        "f#TtpPPPPPPtTT#f",
        "ff#tPnPPPPnPt#ff",
        ".f#tPPPPPPPPt#f.",
        "..#tttttttttt#..",
        "..#CTCTCTCTCTC#.",
        "...#TT#..#TT#...",
        "...###....###...",
        "................",
    ],
    "bastion_guard": [
        "................",
        "....########....",
        "...#PPPPPPPP#...",
        "..#PPePPPPePP#..",
        "..#PPbPPPPbPP#..",
        "..#PPPnnnnPPP#..",
        "..#PPnbPPbnPP#..",
        "..#fPPnnnnPPf#..",
        "...#GGGGGGGG#...",
        "...#kkGGGGkk#.GG",
        "...#kkkkkkkk#GGg",
        "...#kkkkkkkk#.Gg",
        "....#kk##kk#...w",
        "....#kk##kk#...w",
        "....###..###....",
        "................",
    ],
    "bastion_crossbowman": [
        "................",
        "....GGGGGGGG....",
        "...#PPPPPPPP#...",
        "..#PPePPPPePP#..",
        "..#PPbPPPPbPP#..",
        "..#PPPnnnnPPP#..",
        "..#PPnbPPbnPP#..",
        "..#fPPnnnnPPf#..",
        "...#RRRRRRRR#...",
        "WWWWWWWW#RRR#...",
        ".c..WC..#RRR#...",
        "..c.W.c.#RRR#...",
        "...cWc..#kk#....",
        "....W...#kk#....",
        "........###.....",
        "................",
    ],
    "warlord_challenge": [
        "..W.............",
        "..WGGGGGGGGGG...",
        "..WGRRRRRRRRG...",
        "..WGRyRRRRyRG...",
        "..WGRRGGGGRRG...",
        "..WGRGPPPPGRG...",
        "..WGRGPbbPGRG...",
        "..WGRGnnnnGRG...",
        "..WGRRGGGGRRG...",
        "..WGRRRRRRRRG...",
        "..WGGGGGGGGGG...",
        "..W.G.G.G.G.....",
        "..W.............",
        "..W.............",
        ".www............",
        "................",
    ],
    "warlord_cleaver": [
        "........GGGG....",
        ".......GyyyGG...",
        "......GyGGGGG...",
        "......GyGGGgG...",
        ".......GGGGgg...",
        "......#wGgggG...",
        ".....#w#.GGG....",
        "....#w#.........",
        "...#w#..........",
        "..#w#...........",
        ".#w#............",
        "#N#.............",
        "NN..............",
        "................",
        "................",
        "................",
    ],
    "molten_husk": [
        "................",
        "...mhmmhmmhm....",
        "...mmhmmmhmm....",
        "...hmmhmhmmh....",
        "...SSSSSSSSS....",
        "...SbbSSSbbS....",
        "...SSSSSSSSS....",
        "...SSsssssSS....",
        "...sSSSSSSSs.GG.",
        "..#ssssssssss#G.",
        "..#sSSSSSSSSs#G.",
        "..#ssssssssss#..",
        "...#ss#..#ss#...",
        "...#ss#..#ss#...",
        "...###....###...",
        "................",
    ],
    "meteor_crawler": [
        "................",
        "................",
        "................",
        "................",
        ".....#####......",
        "...##xxxxx##....",
        "..#xxhxxxhxx#...",
        ".#xxxxxhxxxxx#..",
        "#xhxxxxxxxxhxx#.",
        "#xxxxhxxxhxxxx#.",
        ".#xxxxxxxxxxx#..",
        "..#x#x#x#x#x#...",
        "...#.#.#.#.#....",
        "................",
        "................",
        "................",
    ],
    "star_sentinel": [
        "......BBBB......",
        ".....BBBBBB.....",
        "....BByyyyBB....",
        "....ByybbyyB....",
        "....ByyyyyyB....",
        "....BBByyBBB....",
        ".B...BBBBBB...B.",
        "..B..O.BB.O..B..",
        "...O..O..O..O...",
        "..O.O.BBBB.O.O..",
        "O....O.BB.O....O",
        "...O..O..O..O...",
        "..O..O.BB.O..O..",
        ".O...O....O...O.",
        "................",
        "................",
    ],
    "meteorite": [
        "...........O.O..",
        "..........O.O...",
        ".........O.O....",
        "......####O.....",
        "....##xXxx##....",
        "...#xxhxxXxx#...",
        "..#xXxxxvxxxx#..",
        "..#xxxGGGxhxx#..",
        "..#xhxGyGxxxX#..",
        "..#xxxGGGxxvx#..",
        "..#xXxxxxXxxx#..",
        "...#xxvxxxhx#...",
        "....##xxxx##....",
        "......####......",
        "................",
        "................",
    ],
    "caravan_merchant": [
        "................",
        "....uuuuuuuu....",
        "...uuUUUUUUuu...",
        "...uU#PPPP#Uu...",
        "...u#PPPPPP#u...",
        "...u#PbPPbP#u...",
        "...u#PPPPPP#u...",
        "...u#PPnnPP#u...",
        "...uu#PPPP#uu...",
        "..uuuu####uuuu..",
        "..uUUUuGGuUUUu..",
        "..uUUuuGGuuUUu..",
        "..uUUuuuuuuUUu..",
        "...uuu#..#uuu...",
        "...www....www...",
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
    for d in ("items", "models/item", "textures/item", "textures/index", "font"): os.makedirs(os.path.join(A, d), exist_ok=True)
    imgs = {k: draw(v) for k, v in ICONS.items()}
    for k in ("slayer_crown", "champion_cape", "meteor_pendant", "pirate_hat"): imgs[k] = COS.draw(COS.ICONS[k])
    for k in ("season_candy", "season_present"): imgs[k] = COS.draw(COS.TOKENS[k])
    for k, im in imgs.items(): im.save(os.path.join(A, "textures/index", k + ".png"))
    # the two items
    for k, parent in (("warlord_challenge", "minecraft:item/generated"), ("warlord_cleaver", "minecraft:item/handheld")):
        imgs[k].save(os.path.join(A, "textures/item", k + ".png"))
        with open(os.path.join(A, "models/item", k + ".json"), "w") as f:
            json.dump({"parent": parent, "textures": {"layer0": "faultline:item/" + k}}, f, indent=2)
        with open(os.path.join(A, "items", k + ".json"), "w") as f:
            json.dump({"model": {"type": "minecraft:model", "model": "faultline:item/" + k}}, f, indent=2)
    # the Index font + glyph numbers
    glyphs_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "plugins", "FaultlineIndex", "src", "main", "resources", "glyphs.yml")
    gtext = open(glyphs_path).read()
    have = set(re.findall(r"^([a-z0-9_]+):", gtext, re.M))
    smalls = [int(v) for v in re.findall(r"small: (\d+)", gtext)]
    larges = [int(v) for v in re.findall(r"large: (\d+)", gtext)]
    nsmall, nlarge = max(smalls) + 1, max(larges) + 1
    font_path = os.path.join(A, "font/index.json")
    font = json.load(open(font_path)) if os.path.exists(font_path) else {"providers": []}
    provided = {(p.get("file"), p.get("height")) for p in font["providers"]}
    added = []
    for k in imgs:
        f = "faultline:index/" + k + ".png"
        if k not in have:
            gtext += f"{k}:\n  large: {nlarge}\n  small: {nsmall}\n"
            small, large = nsmall, nlarge
            nsmall += 1; nlarge += 1
            added.append(k)
        else:
            m = re.search(rf"^{k}:\n  large: (\d+)\n  small: (\d+)", gtext, re.M)
            large, small = int(m.group(1)), int(m.group(2))
        if (f, 8) not in provided: font["providers"].append({"type": "bitmap", "file": f, "ascent": 7, "height": 8, "chars": [chr(small)]})
        if (f, 16) not in provided: font["providers"].append({"type": "bitmap", "file": f, "ascent": 7, "height": 16, "chars": [chr(large)]})
    with open(glyphs_path, "w") as fh: fh.write(gtext)
    with open(font_path, "w") as fh: json.dump(font, fh, indent=1)
    print("new glyphs:", ", ".join(added) or "none")
    if prev:
        os.makedirs(prev, exist_ok=True)
        sheet = Image.new("RGBA", (len(imgs) * 136, 136), (200, 180, 140, 255))
        for i, im in enumerate(imgs.values()): sheet.alpha_composite(im.resize((128, 128), Image.NEAREST), (i * 136 + 4, 4))
        sheet.save(os.path.join(prev, "events_icons.png"))


if __name__ == "__main__":
    main()
