#!/usr/bin/env python3
"""
Index icons for the 25 mutants of Raids 1.3.0 (16x16): a head for the mob kind + a mark for the mutation.
Used by events_assets.py (icons(): {icon id: PIL image}); run alone to preview: python3 tools/mutant_icons.py <out.png>
"""
import sys
from PIL import Image

O = (20, 14, 12, 255)          # outline


def rgb(h, a=255):
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (a,)


def px(im, x, y, c):
    if 0 <= x < 16 and 0 <= y < 16: im.putpixel((x, y), c)


def rect(im, x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1): px(im, x, y, c)


def box(im, x0, y0, x1, y1, fill, edge=O):
    rect(im, x0, y0, x1, y1, edge)
    rect(im, x0 + 1, y0 + 1, x1 - 1, y1 - 1, fill)


def pts(im, c, *xy):
    for i in range(0, len(xy), 2): px(im, xy[i], xy[i + 1], c)


# ---------------------------------------------------------------- heads (face in 1..12 x 1..12)

def zombie_head(im, skin="5f9650", dark="3c6834", eyes="181818", x=1, y=1, s=12):
    box(im, x, y, x + s - 1, y + s - 1, rgb(skin))
    rect(im, x + 1, y + 1, x + s - 2, y + 2, rgb(dark))                       # hair
    e = rgb(eyes)
    rect(im, x + 2, y + 5, x + 3, y + 6, e); rect(im, x + s - 4, y + 5, x + s - 3, y + 6, e)
    rect(im, x + 4, y + 8, x + s - 5, y + 8, rgb(dark)); px(im, x + 5, y + 9, rgb(dark))


def skeleton_head(im, bone="e8e4d2", shade="aaa696", x=1, y=1):
    box(im, x, y, x + 11, y + 11, rgb(bone))
    rect(im, x + 1, y + 1, x + 10, y + 1, rgb(shade))
    b = rgb("181818")
    rect(im, x + 2, y + 4, x + 4, y + 6, b); rect(im, x + 7, y + 4, x + 9, y + 6, b)
    px(im, x + 5, y + 7, b); px(im, x + 6, y + 7, b)
    for i in range(2, 10, 2): px(im, x + i, y + 9, b)


def creeper_head(im, skin="64be50", dark="2c742c", face="102010", x=1, y=1, alpha=255):
    s, d, f = rgb(skin, alpha), rgb(dark, alpha), rgb(face, alpha)
    box(im, x, y, x + 11, y + 11, s, rgb("14100c", alpha))
    for (a, b) in ((2, 2), (8, 3), (4, 9), (9, 8), (1, 6)): px(im, x + a, y + b, d)
    rect(im, x + 2, y + 3, x + 4, y + 5, f); rect(im, x + 7, y + 3, x + 9, y + 5, f)
    rect(im, x + 5, y + 5, x + 6, y + 8, f); rect(im, x + 4, y + 7, x + 4, y + 9, f); rect(im, x + 7, y + 7, x + 7, y + 9, f)


def spider_head(im, body="3c3230", eye="d22828", x=1, y=2, s=12):
    box(im, x, y, x + s - 1, y + s - 4, rgb(body))
    e = rgb(eye)
    rect(im, x + 2, y + 3, x + 3, y + 4, e); rect(im, x + s - 4, y + 3, x + s - 3, y + 4, e)
    px(im, x + 5, y + 2, e); px(im, x + s - 6, y + 2, e)
    for lx in (x - 1, x + s):                                                   # legs
        for ly in (y + 2, y + 5, y + 7): px(im, lx, ly, O)


def pillager_head(im, x=1, y=1):
    box(im, x, y, x + 11, y + 11, rgb("9c9a90"))
    rect(im, x + 1, y + 1, x + 10, y + 2, rgb("50483e"))
    rect(im, x + 2, y + 4, x + 9, y + 4, rgb("2c2620"))                      # unibrow
    rect(im, x + 2, y + 5, x + 3, y + 5, rgb("2e5a2e")); rect(im, x + 8, y + 5, x + 9, y + 5, rgb("2e5a2e"))
    rect(im, x + 5, y + 5, x + 6, y + 8, rgb("7e7a70"))                      # nose
    rect(im, x + 4, y + 10, x + 7, y + 10, rgb("50483e"))


def blaze_head(im, x=2, y=2):
    box(im, x, y, x + 9, y + 9, rgb("fac83c"))
    rect(im, x + 1, y + 1, x + 8, y + 2, rgb("ffe88c"))
    rect(im, x + 2, y + 4, x + 3, y + 5, rgb("6a3a10")); rect(im, x + 6, y + 4, x + 7, y + 5, rgb("6a3a10"))
    rect(im, x + 3, y + 7, x + 6, y + 7, rgb("c48c1e"))
    for (a, b) in ((0, 0), (11, 1), (0, 11), (12, 12), (6, 13), (13, 6)): rect(im, a, b, a, b + 1, rgb("f07828"))


def phantom_head(im):
    c, d = rgb("44609a"), rgb("2c3c64")
    rect(im, 0, 5, 15, 9, O)
    rect(im, 1, 6, 14, 8, c)
    for (a, b) in ((1, 4), (3, 3), (12, 3), (14, 4)): rect(im, a, b, a + 1, b + 1, O)
    rect(im, 5, 4, 10, 10, O); rect(im, 6, 5, 9, 9, c); rect(im, 6, 8, 9, 9, d)
    px(im, 6, 6, rgb("78ff50")); px(im, 9, 6, rgb("78ff50"))


def hoglin_head(im, skin="b4785a", dark="7c4c36"):
    box(im, 1, 2, 13, 12, rgb(skin))
    rect(im, 2, 3, 12, 4, rgb(dark))
    px(im, 4, 6, rgb("181818")); px(im, 10, 6, rgb("181818"))
    box(im, 4, 8, 10, 12, rgb("d29a80"))
    px(im, 6, 10, rgb(dark)); px(im, 8, 10, rgb(dark))
    rect(im, 2, 9, 2, 13, rgb("f0e6d2")); rect(im, 12, 9, 12, 13, rgb("f0e6d2"))  # tusks


HEADS = {
    "zombie": lambda im: zombie_head(im), "husk": lambda im: zombie_head(im, "c4b48c", "968664"),
    "drowned": lambda im: zombie_head(im, "4e9688", "2c6460", "64dcdc"), "skeleton": lambda im: skeleton_head(im),
    "wither": lambda im: skeleton_head(im, "3c3a3e", "282628"), "creeper": lambda im: creeper_head(im),
    "spider": lambda im: spider_head(im), "pillager": lambda im: pillager_head(im), "blaze": lambda im: blaze_head(im),
    "phantom": lambda im: phantom_head(im), "hoglin": lambda im: hoglin_head(im),
}

# ---------------------------------------------------------------- marks (bottom-right)


def badge(im, fill):
    box(im, 10, 10, 15, 15, rgb(fill))


def mark(im, kind):
    W = rgb("ffffff")
    if kind == "plague":
        badge(im, "3c7a20"); pts(im, rgb("a6e650"), 11, 11, 13, 12, 12, 14, 14, 13)
        pts(im, rgb("78c832"), 3, 10, 9, 11)                                          # drool
    elif kind == "brute":
        badge(im, "aa2018"); rect(im, 11, 12, 14, 14, rgb("e8a080")); rect(im, 11, 11, 14, 11, rgb("c8806a"))
        rect(im, 2, 4, 5, 4, O); rect(im, 8, 4, 11, 4, O)                               # angry brows
    elif kind == "bloater":
        pts(im, rgb("f08c8c"), 2, 8, 3, 8, 10, 8, 11, 8)
        for (x, y) in ((11, 11), (13, 13), (14, 10)): box(im, x - 1, y - 1, x + 1, y + 1, rgb("9ad25a"))
    elif kind == "gravecaller":
        box(im, 10, 9, 15, 15, rgb("8c8c96")); rect(im, 12, 11, 13, 11, O); rect(im, 12, 12, 12, 13, O)
        pts(im, rgb("8c3cdc"), 3, 6, 10, 6, 4, 6, 11, 6)                                 # soul-lit eyes
    elif kind == "twin":
        pass
    elif kind == "troll":
        badge(im, "1e1e1e"); pts(im, rgb("e83232"), 11, 12, 12, 11, 13, 12, 14, 11, 11, 13, 12, 14, 13, 14, 12, 13, 14, 13, 13, 13)
    elif kind == "dustdevil":
        for (x, y) in ((10, 11), (12, 10), (14, 12), (11, 14), (13, 14), (15, 15), (10, 13)): px(im, x, y, rgb("e6c878"))
        rect(im, 3, 6, 4, 7, rgb("e6c878")); rect(im, 9, 6, 10, 7, rgb("e6c878"))
    elif kind == "abyssal":
        for (x, y) in ((12, 10), (14, 12), (11, 13)): box(im, x - 1, y - 1, x + 1, y + 1, rgb("b4f0ff"), rgb("3c8cc8"))
    elif kind == "sniper":
        rect(im, 9, 12, 15, 12, rgb("d23232")); rect(im, 12, 9, 12, 15, rgb("d23232")); px(im, 12, 12, W)
    elif kind == "volley":
        for i, y in enumerate((10, 12, 14)):
            rect(im, 9 + i % 2, y, 14, y, rgb("8c6440")); px(im, 15, y, rgb("dcdcdc")); px(im, 9 + i % 2, y, W)
    elif kind == "frost":
        c = rgb("a0e6ff")
        rect(im, 10, 12, 14, 12, c); rect(im, 12, 10, 12, 14, c); pts(im, c, 10, 10, 14, 10, 10, 14, 14, 14)
    elif kind == "hexbolt":
        c = rgb("c850ff")
        pts(im, c, 13, 9, 12, 10, 11, 11, 12, 11, 13, 11, 12, 12, 11, 13, 10, 14)
    elif kind == "phasing":
        for (x, y) in ((11, 10), (14, 11), (12, 13), (15, 14), (10, 15), (13, 15)): px(im, x, y, rgb("b450f0"))
    elif kind == "cursed":
        for (x, y) in ((0, 3), (13, 2), (14, 8), (0, 10), (2, 13), (10, 14), (7, 14)): px(im, x, y, rgb("6e28a0"))
        pts(im, rgb("b450f0"), 4, 6, 9, 6)
    elif kind == "storm":
        c = rgb("78b4ff", 255)
        for x in range(0, 14): px(im, x, 0, c); px(im, x, 13, c)
        for y in range(0, 14): px(im, 0, y, c); px(im, 13, y, c)
        pts(im, rgb("fff064"), 14, 6, 13, 7, 14, 8, 15, 8, 14, 9, 13, 10, 14, 10)
    elif kind == "magnetic":
        r, g = rgb("d22828"), rgb("c8c8c8")
        rect(im, 10, 10, 10, 14, r); rect(im, 14, 10, 14, 14, r); rect(im, 11, 14, 13, 14, r)
        px(im, 10, 10, g); px(im, 14, 10, g)
    elif kind == "stealth":
        pass
    elif kind == "ender":
        pts(im, rgb("c864ff"), 3, 4, 4, 4, 8, 4, 9, 4)
        for (x, y) in ((0, 2), (14, 3), (13, 12), (1, 13), (15, 8)): px(im, x, y, rgb("8c28dc"))
    elif kind == "weaver":
        c = rgb("f0f0f0", 230)
        for i in range(0, 16, 2): px(im, i, i, c); px(im, 15 - i, i, c)
        rect(im, 0, 15, 15, 15, c)
    elif kind == "leaper":
        for x in (10, 12, 14): rect(im, x, 11, x, 15, rgb("f0f0f0"))
        pts(im, rgb("f0f0f0"), 11, 10, 13, 10)
    elif kind == "broodmother":
        for (x, y) in ((11, 12), (14, 14)):
            box(im, x - 1, y - 1, x + 1, y + 1, rgb("1c4c54")); px(im, x, y, rgb("d22828"))
    elif kind == "inferno":
        for (x, y) in ((12, 11), (14, 14), (10, 14)): box(im, x - 1, y - 1, x + 1, y + 1, rgb("ff8c28"), rgb("a02c10"))
    elif kind == "shrieker":
        c = rgb("f0f0f0")
        for r, x in ((1, 11), (2, 13)):
            for y in range(12 - r, 13 + r): px(im, x + r, y, c)
    elif kind == "rampager":
        pts(im, rgb("e83232"), 4, 6, 10, 6)
        for (x, y) in ((0, 13), (1, 14), (14, 14), (15, 13), (3, 15)): px(im, x, y, rgb("a08c78"))
        rect(im, 3, 5, 5, 5, O); rect(im, 9, 5, 11, 5, O)
    elif kind == "vampiric":
        pts(im, rgb("e01e1e"), 3, 6, 4, 6, 9, 6, 10, 6)
        pts(im, W, 5, 9, 8, 9)
        box(im, 11, 10, 14, 15, rgb("b41414")); px(im, 12, 9, rgb("b41414"))


MUTANTS = {   # icon id: (head, mark)
    "plague_zombie": ("zombie", "plague"), "brute_zombie": ("zombie", "brute"), "bloated_zombie": ("zombie", "bloater"),
    "gravecaller": ("zombie", "gravecaller"), "twin_zombie": ("zombie", "twin"), "troll_zombie": ("zombie", "troll"),
    "dust_devil_husk": ("husk", "dustdevil"), "abyssal_drowned": ("drowned", "abyssal"),
    "sniper_skeleton": ("skeleton", "sniper"), "volley_skeleton": ("skeleton", "volley"), "frost_skeleton": ("skeleton", "frost"),
    "hexbolt_archer": ("pillager", "hexbolt"), "phasing_skeleton": ("skeleton", "phasing"), "cursed_wither_skeleton": ("wither", "cursed"),
    "storm_creeper": ("creeper", "storm"), "magnetic_creeper": ("creeper", "magnetic"), "stealth_creeper": ("creeper", "stealth"),
    "ender_creeper": ("creeper", "ender"), "weaver_spider": ("spider", "weaver"), "leaping_spider": ("spider", "leaper"),
    "broodmother": ("spider", "broodmother"), "inferno_mob": ("blaze", "inferno"), "shrieking_phantom": ("phantom", "shrieker"),
    "rampaging_hoglin": ("hoglin", "rampager"), "vampiric_mob": ("zombie", "vampiric"),
}


def icon(head, kind):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    if kind == "twin":
        zombie_head(im, x=5, y=4, s=10)
        zombie_head(im, x=0, y=0, s=11)
        return im
    if kind == "stealth":
        creeper_head(im, alpha=110)
        for (x, y) in ((13, 2), (14, 5), (13, 9)): px(im, x, y, rgb("ffffff", 160))
        return im
    if kind == "broodmother":
        spider_head(im, x=0, y=1, s=14)
    elif kind == "gravecaller":
        zombie_head(im, skin="4e7a48", dark="2e4a2a")
    elif kind == "bloater":
        zombie_head(im, skin="78aa50", dark="4a7834", x=0, y=1, s=13)
    elif kind == "troll":
        zombie_head(im, skin="3c7a34", dark="244a20")
    elif kind == "abyssal":
        zombie_head(im, "2e6a78", "1c4450", "64dcdc")
    elif kind == "ender":
        creeper_head(im, skin="3c4c3c", dark="1e2a1e", face="0a0a0a")
    elif kind == "vampiric":
        zombie_head(im, skin="8ca07c", dark="3c2e2e")
    else:
        HEADS[head](im)
    mark(im, kind)
    return im


def icons():
    return {k: icon(h, m) for k, (h, m) in MUTANTS.items()}


if __name__ == "__main__":
    imgs = icons()
    sheet = Image.new("RGBA", (len(imgs) * 136, 136), (200, 180, 140, 255))
    for i, im in enumerate(imgs.values()): sheet.alpha_composite(im.resize((128, 128), Image.NEAREST), (i * 136 + 4, 4))
    sheet.save(sys.argv[1] if len(sys.argv) > 1 else "mutant_icons.png")
