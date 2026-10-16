#!/usr/bin/env python3
"""
The Wardrobe (FaultlineItems /accessories): the custom background drawn behind the 6-row chest menu.

Java draws it through the menu's title: font faultline:gui, a -8 space (U+F801) to reach the menu's left edge, then one
176x222 bitmap glyph (ascent 13 = its top on the menu's top edge). One glyph per tab (U+F810 + tab), since the left
column's slot outlines and the lit tab change with it. Bedrock (Geyser) can't draw it and gets glass panes instead.

Layout (chest grid col c, row r = pixel 7+18c, 17+18r):
  col 0      tabs: Accessories, Hat, Neck, Back, Body (row 5: take everything off)
  col 1      that tab's list: 3 accessory slots, or the cosmetics you own for that slot
  col 2      scroll up (row 0) / down (row 5)
  col 3      armour: helmet, chestplate, leggings, boots (rows 0-3)
  cols 4-7   the black preview: your outfit from the front (col 5) and the back (col 7), rows 0-3
  col 8      show/hide cosmetics (row 0), the seasonal shop (row 1), help (row 5)

Usage: python3 tools/gui_assets.py <unpacked-pack-dir> [--preview <dir>]
"""
import json, os, sys
from PIL import Image, ImageDraw

W, H = 176, 222
BG, HI, SH, EDGE = (198, 198, 198), (255, 255, 255), (85, 85, 85), (0, 0, 0)
SLOT, SLOT_D, SLOT_L = (139, 139, 139), (55, 55, 55), (255, 255, 255)
LINE = (98, 98, 98)
TABS = ["accessories", "hat", "neck", "back", "body"]

ICONS = {
    "ring": [
        "................",
        "......####......",
        ".....#....#.....",
        "......####......",
        ".....######.....",
        "....##....##....",
        "...##......##...",
        "...#........#...",
        "...#........#...",
        "...#........#...",
        "...##......##...",
        "....##....##....",
        ".....######.....",
        "................",
        "................",
        "................"],
    "hat": [
        "................",
        "................",
        "................",
        "......####......",
        ".....#....#.....",
        "....#......#....",
        "....#......#....",
        "....#......#....",
        "....########....",
        "..##........##..",
        ".#............#.",
        "..############..",
        "................",
        "................",
        "................",
        "................"],
    "neck": [
        "................",
        "..#..........#..",
        "..#..........#..",
        "...#........#...",
        "...#........#...",
        "....#......#....",
        ".....#....#.....",
        "......#..#......",
        ".......##.......",
        "......####......",
        ".....#....#.....",
        ".....#....#.....",
        "......####......",
        "................",
        "................",
        "................"],
    "back": [
        "................",
        "....########....",
        "...#........#...",
        "...#........#...",
        "...#........#...",
        "..#..........#..",
        "..#..........#..",
        "..#..........#..",
        ".#............#.",
        ".#............#.",
        ".#............#.",
        ".#..#..##..#..#.",
        "..##.##..##.##..",
        "................",
        "................",
        "................"],
    "body": [
        "................",
        "...####..####...",
        "..#....##....#..",
        ".#............#.",
        ".#............#.",
        "..##........##..",
        "....#......#....",
        "....#......#....",
        "....#......#....",
        "....#......#....",
        "....#......#....",
        "....########....",
        "................",
        "................",
        "................",
        "................"],
    "helmet": [
        "................",
        "................",
        "....########....",
        "...#........#...",
        "..#..........#..",
        "..#..........#..",
        "..#...####...#..",
        "..#..#....#..#..",
        "..#..#....#..#..",
        "..####....####..",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................"],
    "chest": [
        "................",
        "..####....####..",
        ".#....####....#.",
        ".#............#.",
        ".#............#.",
        "..#..........#..",
        "...#........#...",
        "...#........#...",
        "...#........#...",
        "...#........#...",
        "...#........#...",
        "...##########...",
        "................",
        "................",
        "................",
        "................"],
    "legs": [
        "................",
        "...##########...",
        "...#........#...",
        "...#........#...",
        "...#...##...#...",
        "...#..#..#..#...",
        "...#..#..#..#...",
        "...#..#..#..#...",
        "...#..#..#..#...",
        "...#..#..#..#...",
        "...#..#..#..#...",
        "...####..####...",
        "................",
        "................",
        "................",
        "................"],
    "boots": [
        "................",
        "................",
        "................",
        "...####..####...",
        "...#..#..#..#...",
        "...#..#..#..#...",
        "...#..#..#..#...",
        "...#..#..#..#...",
        "..#...#..#...#..",
        ".#....#..#....#.",
        ".######..######.",
        "................",
        "................",
        "................",
        "................",
        "................"],
    "eye": [
        "................",
        "................",
        "................",
        ".....######.....",
        "...##......##...",
        "..#...####...#..",
        ".#...#....#...#.",
        ".#...#.##.#...#.",
        ".#...#.##.#...#.",
        "..#...####...#..",
        "...##......##...",
        ".....######.....",
        "................",
        "................",
        "................",
        "................"],
    "bag": [
        "................",
        "......####......",
        ".....#....#.....",
        ".....#....#.....",
        "...##########...",
        "..#..........#..",
        "..#.#.#.#.#..#..",
        "..#..#.#.#.#.#..",
        "..#.#.#.#.#..#..",
        "..#..#.#.#.#.#..",
        "..#..........#..",
        "...##########...",
        "................",
        "................",
        "................",
        "................"],
    "up": [
        "................",
        "................",
        "................",
        "................",
        ".......##.......",
        "......####......",
        ".....######.....",
        "....########....",
        "...##########...",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................"],
    "x": [
        "................",
        "................",
        "...##......##...",
        "....##....##....",
        ".....##..##.....",
        "......####......",
        ".......##.......",
        "......####......",
        ".....##..##.....",
        "....##....##....",
        "...##......##...",
        "................",
        "................",
        "................",
        "................",
        "................"],
    "help": [
        "................",
        "......####......",
        ".....#....#.....",
        "..........#.....",
        ".........#......",
        "........#.......",
        ".......#........",
        ".......#........",
        "................",
        ".......#........",
        "................",
        "................",
        "................",
        "................",
        "................",
        "................"],
}
ICONS["down"] = ICONS["up"][::-1][4:] + ICONS["up"][::-1][:4]
TAB_ICON = {"accessories": "ring", "hat": "hat", "neck": "neck", "back": "back", "body": "body"}

FONT5 = {  # a 3x5 pixel font for the labels
    "A": ["###", "#.#", "###", "#.#", "#.#"], "B": ["##.", "#.#", "##.", "#.#", "##."], "C": ["###", "#..", "#..", "#..", "###"],
    "D": ["##.", "#.#", "#.#", "#.#", "##."], "E": ["###", "#..", "##.", "#..", "###"], "F": ["###", "#..", "##.", "#..", "#.."],
    "K": ["#.#", "#.#", "##.", "#.#", "#.#"], "N": ["#.#", "###", "###", "###", "#.#"], "O": ["###", "#.#", "#.#", "#.#", "###"],
    "R": ["##.", "#.#", "##.", "#.#", "#.#"], "S": ["###", "#..", "###", "..#", "###"], "T": ["###", ".#.", ".#.", ".#.", ".#."],
    "W": ["#.#", "#.#", "###", "###", "#.#"], "I": ["###", ".#.", ".#.", ".#.", "###"], "L": ["#..", "#..", "#..", "#..", "###"],
    "P": ["###", "#.#", "###", "#..", "#.."], "V": ["#.#", "#.#", "#.#", "#.#", ".#."], "Y": ["#.#", "#.#", ".#.", ".#.", ".#."],
    "H": ["#.#", "#.#", "###", "#.#", "#.#"], "M": ["#.#", "###", "###", "#.#", "#.#"], "U": ["#.#", "#.#", "#.#", "#.#", "###"],
    "G": ["###", "#..", "#.#", "#.#", "###"], " ": ["...", "...", "...", "...", "..."],
}


def text(img, x, y, s, col):
    for ch in s.upper():
        g = FONT5.get(ch, FONT5[" "])
        for j, row in enumerate(g):
            for i, c in enumerate(row):
                if c == "#": img.putpixel((x + i, y + j), col)
        x += 4


def icon(img, x, y, name, col=LINE):
    for j, row in enumerate(ICONS[name]):
        for i, c in enumerate(row):
            if c == "#": img.putpixel((x + i, y + j), col)


def panel(d, x0, y0, x1, y1, fill=BG, raised=True):
    d.rectangle([x0, y0, x1, y1], fill=fill)
    a, b = (HI, SH) if raised else (SH, HI)
    d.line([x0, y0, x1 - 1, y0], fill=a); d.line([x0, y0, x0, y1 - 1], fill=a)
    d.line([x0 + 1, y1, x1, y1], fill=b); d.line([x1, y0 + 1, x1, y1], fill=b)


def slot(d, x, y, fill=SLOT):
    """A vanilla slot: 18x18 with the item area at x+1..x+16."""
    d.rectangle([x, y, x + 17, y + 17], fill=fill)
    d.line([x, y, x + 16, y], fill=SLOT_D); d.line([x, y, x, y + 16], fill=SLOT_D)
    d.line([x + 1, y + 17, x + 17, y + 17], fill=SLOT_L); d.line([x + 17, y + 1, x + 17, y + 17], fill=SLOT_L)


def frame(d):
    """The outer menu frame with vanilla's rounded corners."""
    d.rectangle([0, 0, W - 1, H - 1], fill=(0, 0, 0, 0))
    d.rectangle([3, 0, W - 4, H - 1], fill=EDGE); d.rectangle([0, 3, W - 1, H - 4], fill=EDGE)
    d.rectangle([1, 1, W - 2, H - 2], fill=EDGE)
    d.rectangle([2, 2, W - 3, H - 3], fill=BG)
    d.rectangle([3, 1, W - 4, 1], fill=BG); d.rectangle([1, 3, 1, H - 4], fill=BG)
    d.rectangle([3, 1, W - 5, 2], fill=HI); d.rectangle([1, 3, 2, H - 5], fill=HI)
    d.rectangle([4, H - 3, W - 4, H - 2], fill=SH); d.rectangle([W - 3, 4, W - 2, H - 4], fill=SH)
    d.point([(2, 2), (3, 3)], fill=HI); d.point([(W - 3, H - 3), (W - 4, H - 4)], fill=SH)


def sx(c): return 7 + 18 * c
def sy(r): return 17 + 18 * r


def wardrobe(tab):
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    frame(d)
    text(img, 8, 6, "WARDROBE", (64, 64, 64))
    text(img, 46, 6, TABS[tab], (110, 110, 110))
    # col 0: the tabs (the open one sticks out to the right, joined to its list)
    for i, name in enumerate(TABS):
        x, y = sx(0), sy(i)
        if i == tab:
            panel(d, x - 1, y, x + 19, y + 17, (214, 214, 214))
            d.line([x + 19, y + 1, x + 19, y + 16], fill=(214, 214, 214))
            icon(img, x + 1, y + 1, TAB_ICON[name], (40, 40, 40))
        else:
            panel(d, x + 1, y + 1, x + 16, y + 16, (160, 160, 160))
            icon(img, x + 1, y + 1, TAB_ICON[name], (84, 84, 84))
    slot(d, sx(0), sy(5)); icon(img, sx(0) + 1, sy(5) + 1, "x", (150, 50, 50))
    # col 1: the list (accessories: three slots, then a recess)
    panel(d, sx(1) - 1, sy(0) - 1, sx(1) + 18, sy(6), (214, 214, 214), raised=False)
    for r in range(6):
        if tab == 0 and r >= 3:
            d.rectangle([sx(1) + 2, sy(r) + 2, sx(1) + 15, sy(r) + 15], fill=(176, 176, 176))
            continue
        slot(d, sx(1), sy(r))
        if tab == 0: icon(img, sx(1) + 1, sy(r) + 1, "ring")
        else: icon(img, sx(1) + 1, sy(r) + 1, TAB_ICON[TABS[tab]], (118, 118, 118))
    # col 2: the scroll track and buttons
    track_x = sx(2) + 5
    d.rectangle([track_x, sy(1), track_x + 7, sy(5) - 1], fill=(120, 120, 120))
    d.line([track_x, sy(1), track_x + 7, sy(1)], fill=SLOT_D); d.line([track_x, sy(1), track_x, sy(5) - 1], fill=SLOT_D)
    for r, name in ((0, "up"), (5, "down")):
        panel(d, sx(2) + 1, sy(r) + 1, sx(2) + 16, sy(r) + 16, (176, 176, 176))
        icon(img, sx(2) + 1, sy(r) + 1, name, (70, 70, 70))
    # col 3: the armour slots
    for r, name in enumerate(("helmet", "chest", "legs", "boots")):
        slot(d, sx(3), sy(r)); icon(img, sx(3) + 1, sy(r) + 1, name)
    # cols 4-7, rows 0-4: the black preview window
    x0, y0, x1, y1 = sx(4), sy(0) - 1, sx(8) - 1, sy(5)
    d.rectangle([x0, y0, x1, y1], fill=(0, 0, 0))
    d.line([x0 - 1, y0 - 1, x1, y0 - 1], fill=SH); d.line([x0 - 1, y0 - 1, x0 - 1, y1], fill=SH)
    d.line([x0, y1 + 1, x1 + 1, y1 + 1], fill=HI); d.line([x1 + 1, y0, x1 + 1, y1 + 1], fill=HI)
    for cx in (sx(5) + 8, sx(7) + 8):  # a soft floor shadow under each figure
        for k, w in enumerate((10, 8, 5)):
            d.line([cx - w, sy(4) + 4 + k, cx + w, sy(4) + 4 + k], fill=(22 + 6 * k, 22 + 6 * k, 30 + 6 * k))
    text(img, sx(5) - 1, sy(4) + 11, "FRONT", (90, 90, 110))
    text(img, sx(7) + 1, sy(4) + 11, "BACK", (90, 90, 110))
    # col 8: buttons
    for r, name in ((0, "eye"), (1, "bag"), (5, "help")):
        panel(d, sx(8) + 1, sy(r) + 1, sx(8) + 16, sy(r) + 16, (176, 176, 176))
        icon(img, sx(8) + 1, sy(r) + 1, name, (60, 60, 60))
    # row 5 under the preview: a label strip
    panel(d, sx(3), sy(5) + 2, sx(8) - 2, sy(5) + 15, (176, 176, 176), raised=False)
    text(img, sx(3) + 26, sy(5) + 6, "YOUR OUTFIT", (90, 90, 90))
    # the player's inventory (vanilla's label "Inventory" is drawn at 8, 128)
    for r in range(3):
        for c in range(9): slot(d, sx(c), 139 + 18 * r)
    for c in range(9): slot(d, sx(c), 197)
    return img


def main():
    pack = sys.argv[1]
    prev = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    A = os.path.join(pack, "assets", "faultline")
    os.makedirs(os.path.join(A, "textures/font/gui"), exist_ok=True)
    providers = [{"type": "space", "advances": {"": -8, "": -1}}]
    for t in range(len(TABS)):
        img = wardrobe(t)
        img.save(os.path.join(A, "textures/font/gui", f"wardrobe_{t}.png"))
        providers.append({"type": "bitmap", "file": f"faultline:font/gui/wardrobe_{t}.png", "ascent": 13, "height": H,
                          "chars": [chr(0xF810 + t)]})
        if prev:
            os.makedirs(prev, exist_ok=True)
            img.resize((W * 3, H * 3), Image.NEAREST).save(os.path.join(prev, f"wardrobe_{TABS[t]}.png"))
    os.makedirs(os.path.join(A, "font"), exist_ok=True)
    with open(os.path.join(A, "font", "gui.json"), "w") as f: json.dump({"providers": providers}, f, indent=1, ensure_ascii=True)
    # an invisible item model for the menu's filler slots (Java only)
    os.makedirs(os.path.join(A, "models/item/gui"), exist_ok=True); os.makedirs(os.path.join(A, "items/gui"), exist_ok=True)
    with open(os.path.join(A, "models/item/gui/blank.json"), "w") as f: json.dump({"textures": {}, "elements": []}, f)
    with open(os.path.join(A, "items/gui/blank.json"), "w") as f:
        json.dump({"model": {"type": "minecraft:model", "model": "faultline:item/gui/blank"}}, f)
    print("wardrobe: 5 tabs")


if __name__ == "__main__":
    main()
