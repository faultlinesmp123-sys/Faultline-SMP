"""
The seasonal cosmetics (Halloween, Winter) and the achievement cosmetics, used by cosmetics_assets.py.

HAT models are authored in HEAD pixels: the head is the box -4..4 on every axis around the joint (8, 8, 8), its FRONT is
-z (north, like a carved pumpkin's face) and +x is the wearer's RIGHT. They're worn as a fake helmet item; the item's
"head" display scale 1.6 makes one model unit one head pixel (Java draws a head item at 0.625 of a block).
NECK / BACK models use the torso layout of cosmetics_assets.py (centre of the torso at the joint, front +z, +x = left).
BODY cosmetics are 64x32 armor-layer textures.
"""
import random
import numpy as np
from PIL import Image

from jacob_models import Atlas, box, plate, noise_fill

ORANGE = dict(base=(232, 128, 30), light=(255, 170, 70), dark=(170, 80, 14), hi=(255, 200, 110))
GOLD = dict(base=(232, 184, 46), light=(255, 236, 140), dark=(150, 98, 14), hi=(255, 250, 210))
BLACKP = dict(base=(36, 30, 40), light=(60, 52, 66), dark=(18, 14, 20), hi=(84, 74, 92))


def solid(c, rng, amt=5):
    return lambda reg, face, wu, hu: noise_fill(reg, c, rng, amt)


def rot(el, axis, angle, origin):
    """An element rotation. Java only takes -45, -22.5, 0, 22.5 or 45 degrees."""
    assert angle in (-45, -22.5, 0, 22.5, 45), angle
    el["rotation"] = {"origin": [8 + o for o in origin], "axis": axis, "angle": angle}
    return el


# ===================================================================== HATS (head pixels, front -z)

def pumpkin_head(seed):
    """A carved jack-o'-lantern over the whole head, glowing face, curly stem."""
    atlas = Atlas(128, 2); rng = random.Random(seed)
    def skin(reg, face, wu, hu):
        h, w = reg.shape[:2]
        noise_fill(reg, ORANGE["base"], rng, 6)
        if face in ("north", "south", "east", "west"):
            for x in range(0, w, 4):  # the ridges
                reg[:, x, :3] = ORANGE["dark"]
                if x + 1 < w: reg[:, x + 1, :3] = np.clip(np.array(ORANGE["base"]) * 0.9, 0, 255)
            for x in range(2, w, 4): reg[:, x, :3] = ORANGE["light"]
        if face == "up":
            reg[:, :, :3] = np.clip(reg[:, :, :3] * 0.92, 0, 255)
        if face == "north":  # the carved face: triangle eyes, a nose, a jagged grin, lit from inside
            glow, deep = (255, 222, 90), (255, 150, 20)
            def px(x, y, c):
                if 0 <= x < w and 0 <= y < h: reg[y, x, :3] = c
            for (cx, sgn) in ((5, 1), (12, -1)):  # eyes
                for r in range(4):
                    for dx in range(-r, r + 1): px(cx + dx, 4 + r, glow if r < 3 else deep)
            for dx in (-1, 0, 1): px(8 + dx, 8, deep)
            px(8, 9, glow); px(9, 9, glow)
            for x in range(3, 15):  # the grin
                y0 = 11 + (0 if 5 < x < 12 else -1)
                for y in range(y0, 15): px(x, y, glow if y < 14 else deep)
            for x in (5, 8, 11): px(x, 11, ORANGE["base"]); px(x, 12, ORANGE["base"])   # teeth
            for x in (6, 10): px(x, 14, ORANGE["base"])
    def stem(reg, face, wu, hu):
        noise_fill(reg, (70, 110, 40), rng, 8)
        reg[0, :, :3] = (100, 150, 60)
    el = [box(atlas, (-4.6, -4.6, -4.6), (4.6, 4.4, 4.6), skin),
          box(atlas, (-0.8, 4.4, -0.6), (0.8, 6.4, 1.0), stem),
          rot(box(atlas, (0.6, 5.6, -0.4), (2.6, 6.4, 0.6), stem), "z", -22.5, (0.6, 5.6, 0))]
    return atlas, el


def witch_hat(seed):
    """A tall, bent, pointy purple-black hat with a wide brim and a gold buckle."""
    atlas = Atlas(128, 2); rng = random.Random(seed)
    HAT, BAND, BUCKLE = (52, 30, 70), (130, 50, 170), (240, 196, 60)
    def felt(reg, face, wu, hu): noise_fill(reg, HAT, rng, 7)
    def band(reg, face, wu, hu):
        noise_fill(reg, BAND, rng, 5)
        reg[0, :, :3] = (170, 90, 210)
        if face == "north":
            h, w = reg.shape[:2]; m = w // 2
            reg[:, m - 2:m + 2, :3] = BUCKLE; reg[1:-1, m - 1:m + 1, :3] = (90, 40, 110)
    el = [box(atlas, (-7.5, 3.2, -7.5), (7.5, 4.0, 7.5), felt),     # brim
          box(atlas, (-4.7, 4.0, -4.7), (4.7, 5.6, 4.7), band),
          box(atlas, (-4.2, 5.6, -4.2), (4.2, 8.4, 4.2), felt),
          box(atlas, (-3.2, 8.4, -3.0), (3.2, 10.8, 3.4), felt),
          rot(box(atlas, (-2.2, 10.6, -1.6), (2.2, 12.8, 2.8), felt), "x", 22.5, (0, 10.6, 0.6)),
          rot(box(atlas, (-1.3, 12.0, 0.8), (1.3, 14.0, 3.4), felt), "x", 45, (0, 12.0, 2.0)),
          rot(box(atlas, (-0.7, 12.6, 3.6), (0.7, 13.8, 6.0), felt), "x", 22.5, (0, 13.0, 4.6))]
    return atlas, el


def santa_hat(seed):
    """Red, floppy, white fur trim, a pompom hanging off the back."""
    atlas = Atlas(128, 2); rng = random.Random(seed)
    RED, FUR = (200, 30, 36), (244, 244, 248)
    def red(reg, face, wu, hu):
        noise_fill(reg, RED, rng, 7)
        reg[0, :, :3] = (230, 60, 60)
    def fur(reg, face, wu, hu):
        noise_fill(reg, FUR, rng, 9)
        h, w = reg.shape[:2]
        for _ in range(w * h // 6): reg[rng.randrange(h), rng.randrange(w), :3] = (214, 214, 226)
    el = [box(atlas, (-4.8, 2.6, -4.8), (4.8, 4.8, 4.8), fur),
          box(atlas, (-4.3, 4.8, -4.3), (4.3, 7.2, 4.3), red),
          box(atlas, (-3.4, 7.2, -2.8), (3.4, 9.2, 3.8), red),
          rot(box(atlas, (-2.4, 8.6, -0.6), (2.4, 10.6, 4.2), red), "x", 22.5, (0, 8.6, 1.8)),
          rot(box(atlas, (-1.6, 8.4, 3.4), (1.6, 10.2, 6.6), red), "x", 45, (0, 9.2, 5.0)),
          box(atlas, (-1.6, 6.0, 6.2), (1.6, 9.2, 9.4), fur)]           # pompom
    return atlas, el


def reindeer_antlers(seed):
    """A red headband with two branching brown antlers and little ears."""
    atlas = Atlas(128, 2); rng = random.Random(seed)
    ANT, BAND, EAR = (120, 84, 50), (190, 30, 34), (150, 104, 66)
    def ant(reg, face, wu, hu):
        noise_fill(reg, ANT, rng, 8)
        reg[0, :, :3] = (156, 116, 76)
    def band(reg, face, wu, hu): noise_fill(reg, BAND, rng, 5)
    def ear(reg, face, wu, hu):
        noise_fill(reg, EAR, rng, 6)
        if face in ("north",): reg[1:-1, 1:-1, :3] = (230, 160, 150)
    el = [box(atlas, (-4.5, 3.4, -1.0), (4.5, 4.4, 1.0), band)]
    for s in (-1, 1):
        x0 = 2.6 * s
        el.append(rot(box(atlas, (x0 - 0.6, 4.2, -0.6), (x0 + 0.6, 10.4, 0.6), ant), "z", -22.5 * s, (x0, 4.2, 0)))  # main beam
        el.append(rot(box(atlas, (x0 + 1.4 * s - 0.5, 7.0, -0.5), (x0 + 1.4 * s + 0.5, 10.0, 0.5), ant), "z", 22.5 * s, (x0 + 1.4 * s, 7.0, 0)))
        el.append(box(atlas, (x0 + (2.6 if s > 0 else -4.6), 8.8, -0.5), (x0 + (4.6 if s > 0 else -2.6), 9.8, 0.5), ant))   # top tine out
        el.append(rot(box(atlas, (x0 - 0.5, 6.0, -2.6), (x0 + 0.5, 7.0, -0.4), ant), "x", -22.5, (x0, 6.0, -0.4)))  # brow tine forward
        ex0, ex1 = (3.8, 5.6) if s > 0 else (-5.6, -3.8)
        el.append(rot(box(atlas, (ex0, 2.6, -0.9), (ex1, 4.0, 0.3), ear), "z", 22.5 * s, (4.2 * s, 3.0, 0)))
    return atlas, el


def slayer_crown(seed):
    """A heavy gold crown: a band with eight points, rubies front and back, sapphires on the sides."""
    atlas = Atlas(128, 2); rng = random.Random(seed)
    RUBY, SAPH = (220, 30, 50), (40, 90, 230)
    def gold(reg, face, wu, hu): plate(reg, GOLD, rng, sparkle=0.1)
    def band(reg, face, wu, hu):
        plate(reg, GOLD, rng, sparkle=0.08)
        h, w = reg.shape[:2]
        reg[0, :, :3] = GOLD["hi"]; reg[-1, :, :3] = GOLD["dark"]
        if face in ("north", "south", "east", "west"):
            c = RUBY if face in ("north", "south") else SAPH
            m, y = w // 2, h // 2
            reg[y - 1:y + 1, m - 1:m + 1, :3] = c
            reg[y - 1, m - 1, :3] = tuple(min(255, v + 80) for v in c)
            for gx in (w // 5, w - 1 - w // 5):
                reg[y, gx, :3] = GOLD["hi"]
    el = [box(atlas, (-4.7, 3.0, -4.7), (4.7, 5.4, 4.7), band, faces=["north", "south", "east", "west"]),
          box(atlas, (-4.2, 3.0, -4.2), (4.2, 3.4, 4.2), lambda reg, f, w, h: noise_fill(reg, (120, 20, 30), rng, 6), faces=["up"])]  # velvet
    pts = [(-3.9, -3.9), (0, -3.9), (3.9, -3.9), (3.9, 0), (3.9, 3.9), (0, 3.9), (-3.9, 3.9), (-3.9, 0)]
    for (x, z) in pts:
        tall = 2.6 if (x == 0 or z == 0) else 1.4
        el.append(box(atlas, (x - 0.7, 5.4, z - 0.7), (x + 0.7, 5.4 + tall, z + 0.7), gold))
    return atlas, el


def pirate_hat(seed):
    """A black tricorn with gold trim and a white skull and crossbones on the front."""
    atlas = Atlas(128, 2); rng = random.Random(seed)
    def felt(reg, face, wu, hu):
        plate(reg, BLACKP, rng, sparkle=0)
    def trimmed(reg, face, wu, hu):
        plate(reg, BLACKP, rng, sparkle=0)
        reg[0, :, :3] = GOLD["base"]
        if reg.shape[0] > 3: reg[1, :, :3] = GOLD["dark"]
    def crown(reg, face, wu, hu):
        plate(reg, BLACKP, rng, sparkle=0)
        if face == "north":  # skull and crossbones
            h, w = reg.shape[:2]; m = w // 2
            W = (238, 236, 226)
            def px(x, y):
                if 0 <= x < w and 0 <= y < h: reg[y, x, :3] = W
            for y in range(1, 4):
                for x in range(m - 2, m + 2): px(x, y)
            px(m - 1, 2), px(m, 2)
            reg[2, m - 1, :3] = (20, 20, 20); reg[2, m, :3] = (20, 20, 20)
            for i in range(-3, 4):
                px(m - 1 + i, 5 + i // 2 + 1); px(m - 1 - i, 5 + i // 2 + 1)
    el = [box(atlas, (-4.4, 3.4, -4.4), (4.4, 6.8, 4.4), crown),
          box(atlas, (-6.6, 3.0, -6.0), (6.6, 3.8, 6.6), felt)]
    # three upturned flaps make the tricorn
    el.append(rot(box(atlas, (-6.6, 3.0, 5.6), (6.6, 7.6, 6.6), trimmed), "x", -22.5, (0, 3.4, 6.0)))     # back
    for s in (-1, 1):  # the side flaps, turned up and leaning in
        x0, x1 = (5.6, 6.6) if s > 0 else (-6.6, -5.6)
        el.append(rot(box(atlas, (x0, 3.0, -5.6), (x1, 7.6, 5.6), trimmed), "z", 22.5 * s, (6.0 * s, 3.4, 0)))
    el.append(rot(box(atlas, (-1.2, 3.0, -7.0), (1.2, 5.6, -6.0), trimmed), "x", 22.5, (0, 3.4, -6.4)))  # the front point
    return atlas, el


# ===================================================================== NECK / BACK (torso layout, front +z)

def winter_scarf(seed):
    """A chunky red-and-white striped scarf around the neck, one end hanging down the front."""
    atlas = Atlas(128, 4); rng = random.Random(seed)
    RED, WHITE = (196, 28, 34), (240, 240, 244)
    def knit(reg, face, wu, hu):
        h, w = reg.shape[:2]
        noise_fill(reg, RED, rng, 6)
        for y in range(h):
            if (y // 3) % 2 == 1: reg[y, :, :3] = WHITE
        for x in range(0, w, 2): reg[:, x, :3] = np.clip(reg[:, x, :3] * 0.9, 0, 255)
    def tail(reg, face, wu, hu):
        h, w = reg.shape[:2]
        noise_fill(reg, RED, rng, 6)
        for y in range(h):
            if (y // 4) % 2 == 1: reg[y, :, :3] = WHITE
        reg[-2:, ::2, 3] = 0  # fringe
    el = [box(atlas, (-4.5, 4.3, 2.0), (4.5, 6.5, 3.0), knit),
          box(atlas, (-4.5, 4.3, -3.0), (4.5, 6.5, -2.0), knit),
          box(atlas, (-4.9, 4.3, -3.0), (-4.0, 6.5, 3.0), knit),
          box(atlas, (4.0, 4.3, -3.0), (4.9, 6.5, 3.0), knit),
          box(atlas, (0.8, -2.4, 2.4), (3.0, 4.4, 3.2), tail)]
    return atlas, el


def meteor_pendant(seed):
    """A dark iron chain with a chunk of meteorite: black rock, glowing orange and violet cracks."""
    atlas = Atlas(128, 4); rng = random.Random(seed)
    IRON = dict(base=(70, 70, 80), light=(120, 120, 134), dark=(36, 36, 44), hi=(160, 160, 176))
    def iron(reg, face, wu, hu): plate(reg, IRON, rng, sparkle=0.05)
    def rock(reg, face, wu, hu):
        h, w = reg.shape[:2]
        noise_fill(reg, (34, 28, 36), rng, 10)
        for _ in range(3):  # cracks
            x, y = rng.randrange(w), 0
            while y < h:
                reg[y, x % w, :3] = (255, 140, 30) if rng.random() < 0.6 else (190, 80, 255)
                y += 1; x += rng.choice((-1, 0, 1))
    el = []
    z0, z1 = 2.4, 3.0
    el.append(box(atlas, (-3.0, 5.5, z0 - 0.2), (3.0, 6.1, z1), iron))
    for s in (-1, 1):
        for i in range(6):
            f = i / 5.0
            x = s * (2.9 - 2.4 * f); y = 5.3 - 5.0 * f
            el.append(box(atlas, (x - 0.35, y - 0.45, z0), (x + 0.35, y + 0.45, z1), iron))
    el.append(box(atlas, (-1.3, -3.4, z0 - 0.2), (1.3, -0.6, z1 + 0.6), rock))
    el.append(box(atlas, (-0.5, -0.8, z0), (0.5, -0.1, z1), iron))
    return atlas, el


def bat_wings(seed):
    """Two leathery bat wings spread from the shoulder blades, scalloped at the bottom."""
    atlas = Atlas(256, 4); rng = random.Random(seed)
    SKIN, BONE = (54, 34, 56), (26, 16, 28)
    def membrane(reg, face, wu, hu):
        h, w = reg.shape[:2]
        noise_fill(reg, SKIN, rng, 7)
        for x in range(0, w, max(3, w // 4)): reg[:, x, :3] = BONE  # finger bones
        reg[0:2, :, :3] = BONE
        seg = max(4, w // 4)
        for x in range(w):  # scalloped bottom edge
            d = abs((x % seg) - seg / 2) / (seg / 2)
            cut = int((1 - d * d) * h * 0.28)
            if cut > 0: reg[h - cut:, x, 3] = 0
    def bone(reg, face, wu, hu): noise_fill(reg, BONE, rng, 5)
    el = [box(atlas, (-1.2, 0.0, -3.0), (1.2, 4.0, -2.2), bone)]
    for s in (-1, 1):
        x0, x1 = (0.8, 11.0) if s > 0 else (-11.0, -0.8)
        el.append(rot(box(atlas, (x0, -3.0, -3.0), (x1, 6.5, -2.6), membrane), "y", -22.5 * s, (0.8 * s, 0, -2.8)))
        el.append(rot(box(atlas, (x0, 6.0, -3.1), (x1, 6.8, -2.5), bone), "y", -22.5 * s, (0.8 * s, 0, -2.8)))
    return atlas, el


def gift_sack(seed):
    """A bulging burlap sack on the back, a wrapped present poking out the top, straps over the shoulders."""
    atlas = Atlas(256, 4); rng = random.Random(seed)
    BURLAP, STRAP, BOX, RIB = (150, 112, 70), (90, 60, 34), (40, 140, 60), (230, 40, 46)
    def sack(reg, face, wu, hu):
        h, w = reg.shape[:2]
        noise_fill(reg, BURLAP, rng, 9)
        for y in range(0, h, 2): reg[y, ::2, :3] = np.clip(reg[y, ::2, :3] * 0.88, 0, 255)
        if face == "north" and h > 8:  # a patch
            reg[h // 3:h // 3 + 5, w // 4:w // 4 + 6, :3] = (120, 84, 52)
    def strap(reg, face, wu, hu): noise_fill(reg, STRAP, rng, 4)
    def gift(reg, face, wu, hu):
        h, w = reg.shape[:2]
        noise_fill(reg, BOX, rng, 4)
        reg[:, w // 2 - 1:w // 2 + 1, :3] = RIB
        if face in ("up", "down"): reg[h // 2 - 1:h // 2 + 1, :, :3] = RIB
    def bow(reg, face, wu, hu): noise_fill(reg, RIB, rng, 6)
    el = [box(atlas, (-3.8, -5.6, -7.0), (3.8, 3.6, -2.2), sack),
          box(atlas, (-2.6, 3.6, -6.0), (2.6, 4.6, -3.0), sack),
          box(atlas, (-1.2, 4.6, -5.2), (1.2, 5.4, -3.8), strap),
          rot(box(atlas, (-2.2, 4.4, -6.2), (1.8, 7.8, -2.8), gift), "z", 22.5, (0, 4.4, -4.5)),
          box(atlas, (-1.2, 7.6, -5.0), (0.8, 8.6, -4.0), bow)]
    for s in (-1, 1):
        x0, x1 = (1.6, 2.8) if s > 0 else (-2.8, -1.6)
        el.append(box(atlas, (x0, -3.0, 2.0), (x1, 6.2, 2.4), strap))       # down the chest
        el.append(box(atlas, (x0, 5.8, -2.4), (x1, 6.4, 2.4), strap))       # over the shoulder
    return atlas, el


def champion_cape(seed):
    """A long red cape with a gold border and a gold crown on the back, held by two gold clasps."""
    atlas = Atlas(256, 4); rng = random.Random(seed)
    RED, REDD = (176, 20, 28), (120, 10, 18)
    def cape(reg, face, wu, hu):
        h, w = reg.shape[:2]
        noise_fill(reg, RED, rng, 6)
        for x in range(0, w, 6): reg[:, x, :3] = REDD  # folds
        if face in ("north", "south"):
            reg[:, :2, :3] = GOLD["base"]; reg[:, -2:, :3] = GOLD["base"]; reg[-3:, :, :3] = GOLD["base"]; reg[-1, :, :3] = GOLD["dark"]
        if face == "north" and h > 40:  # the outside: a gold crown
            m, y0 = w // 2, h // 6
            for dx in range(-6, 7):
                for dy in range(0, 4): reg[y0 + 6 + dy, m + dx, :3] = GOLD["base"]
            for px in (-6, -3, 0, 3, 6):
                for dy in range(0, 5 if px % 6 == 0 else 3): reg[y0 + 6 - dy, m + px, :3] = GOLD["light"]
            reg[y0 + 7:y0 + 9, m - 1:m + 1, :3] = (220, 30, 50)
    def gold(reg, face, wu, hu): plate(reg, GOLD, rng, sparkle=0.1)
    el = [box(atlas, (-4.8, -17.0, -3.2), (4.8, 6.0, -2.6), cape),
          box(atlas, (-4.6, 5.6, -2.8), (4.6, 6.6, 2.4), solid(RED, rng, 6))]
    for s in (-1, 1):
        el.append(box(atlas, (2.6 * s - 0.9, 4.6, 2.2), (2.6 * s + 0.9, 6.4, 2.9), gold))
    return atlas, el


# ===================================================================== BODY textures (64x32 humanoid armor layer)

def _body_canvas():
    return np.zeros((32, 64, 4))


def _fill(img, rng, x0, y0, x1, y1, c, n=5):
    for y in range(y0, y1):
        for x in range(x0, x1):
            img[y, x, :3] = [max(0, min(255, v + rng.uniform(-n, n))) for v in c]; img[y, x, 3] = 255


def skeleton_suit(seed):
    """A black costume with white bones painted on: ribs, spine, arm bones."""
    rng = random.Random(seed); img = _body_canvas()
    B, W, G = (24, 22, 28), (236, 232, 220), (180, 176, 166)
    # body: top (20,16) bottom (28,16) 8x4; right (16,20) 4x12, front (20,20) 8x12, left (28,20), back (32,20) 8x12
    _fill(img, rng, 20, 16, 36, 20, B); _fill(img, rng, 16, 20, 40, 32, B)
    for y in range(20, 30): img[y, 23, :3] = W; img[y, 24, :3] = W                    # sternum
    for i, y in enumerate((21, 23, 25, 27)):                                              # ribs
        for x in range(20, 28):
            if x not in (23, 24) and abs(x - 23.5) < 4 - i * 0.3: img[y, x, :3] = W if i % 2 == 0 else G
    for x in range(21, 27): img[30, x, :3] = W                                            # pelvis
    img[31, 22, :3] = W; img[31, 25, :3] = W
    for y in range(20, 32): img[y, 35, :3] = W; img[y, 36, :3] = G                       # spine on the back
    for y in range(21, 32, 2): img[y, 34, :3] = G; img[y, 37, :3] = G
    for x in range(33, 39): img[22, x, :3] = W                                            # shoulder blades
    # arms: top (44,16) bottom (48,16) 4x4, outer (40,20) front (44,20) inner (48,20) back (52,20) 4x12
    _fill(img, rng, 44, 16, 52, 20, B); _fill(img, rng, 40, 20, 56, 32, B)
    for x0 in (40, 44, 48, 52):
        for y in range(20, 31): img[y, x0 + 1, :3] = W; img[y, x0 + 2, :3] = G
        img[25, x0 + 1:x0 + 3, :3] = B; img[24, x0:x0 + 4, :3] = W; img[26, x0:x0 + 4, :3] = W   # elbow knobs
        img[31, x0:x0 + 4, :3] = W
    return Image.fromarray(img.astype(np.uint8), "RGBA")


def ugly_sweater(seed):
    """A green knit sweater: red-and-white zigzag bands, a little tree on the chest, red cuffs."""
    rng = random.Random(seed); img = _body_canvas()
    G, R, W, T, Y = (30, 120, 60), (196, 30, 40), (240, 240, 240), (20, 90, 40), (250, 210, 60)
    _fill(img, rng, 20, 16, 36, 20, G, 6); _fill(img, rng, 16, 20, 40, 32, G, 6)
    _fill(img, rng, 44, 16, 52, 20, G, 6); _fill(img, rng, 40, 20, 56, 32, G, 6)
    def zig(x0, x1, y, c1, c2):
        for x in range(x0, x1):
            img[y + (x % 2), x, :3] = c1
            img[y + 1 - (x % 2), x, :3] = c2
    for y in (20, 29):
        zig(16, 40, y, R, W); zig(40, 56, y, R, W)
    for x in range(16, 40): img[31, x, :3] = R            # waistband
    for x in range(40, 56): img[31, x, :3] = R; img[30, x, :3] = W   # cuffs
    # the tree on the chest (front 20..27, rows 22..28)
    tree = ["...Y....", "...TT...", "..TRTT..", "..TTTT..", ".TTWTRT.", ".TTTTTT.", "...tt..."]
    for dy, row in enumerate(tree):
        for dx, ch in enumerate(row):
            c = {"T": T, "R": R, "W": W, "Y": Y, "t": (110, 70, 40)}.get(ch)
            if c: img[22 + dy, 20 + dx, :3] = c
    for x, y in ((34, 24), (37, 26), (35, 27), (17, 25), (42, 24), (50, 26), (46, 27), (54, 24)):  # snowflakes
        img[y, x, :3] = W
    for x in range(20, 28): img[20, x, :3] = W            # collar
    return Image.fromarray(img.astype(np.uint8), "RGBA")


# ===================================================================== menu icons (16x16)

PAL = {
    "#": (20, 16, 22), "o": (232, 128, 30), "O": (255, 170, 70), "d": (170, 80, 14), "y": (255, 222, 90),
    "g": (70, 110, 40), "G": (232, 184, 46), "L": (255, 236, 140), "D": (150, 98, 14),
    "p": (52, 30, 70), "P": (130, 50, 170), "r": (200, 30, 36), "R": (240, 70, 70), "w": (244, 244, 248), "s": (190, 190, 200),
    "b": (120, 84, 50), "B": (156, 116, 76), "k": (36, 30, 40), "K": (70, 62, 76), "e": (40, 90, 230), "u": (220, 30, 50),
    "m": (54, 34, 56), "n": (26, 16, 28), "c": (150, 112, 70), "C": (110, 80, 50), "t": (40, 140, 60), "i": (70, 70, 80),
    "I": (120, 120, 134), "x": (34, 28, 36), "f": (255, 140, 30), "v": (190, 80, 255), "W": (236, 232, 220), "q": (30, 120, 60),
}

ICONS = {
    "pumpkin_head": [
        "................", ".......gg.......", "......gg........", "...##oooooo##...", "..#oOdoOOodOo#..",
        ".#oOdyyoodyydOo#", ".#oOyyyoodyyyOo#", ".#oOdoooyoodoOo#", ".#oOdooyyyodoOo#", ".#oyyyyyyyyyyyo#",
        ".#oOyoyyyyoyyOo#", ".#oOdyoyyoydoOo#", "..#oOdooooodo#..", "...##oooooo##...", ".....######.....", "................"],
    "witch_hat": [
        "..........pp....", ".........ppp....", "........pppp....", ".......ppp......", "......pppp......",
        "......pppp......", ".....pppppp.....", ".....pppppp.....", "....pppppppp....", "....PPPGGPPP....",
        "....PPPGGPPP....", "..pppppppppppp..", ".pppppppppppppp.", "..pppppppppppp..", "................", "................"],
    "bat_wings": [
        "................", "................", "n..............n", "nn....n..n....nn", "nmn...nnnn...nmn",
        "nmmn..nnnn..nmmn", "nmmmn.nnnn.nmmmn", "nmmmmnnnnnnmmmmn", "nmmmmmnnnnmmmmmn", "nmmmmmnnnnmmmmmn",
        ".nmmmmnnnnmmmmn.", ".nmnmmn..nmmnmn.", "..n.nm....mn.n..", "......n..n......", "................", "................"],
    "skeleton_suit": [
        "................", "...kkkkkkkkkk...", "..kkkkkWWkkkkk..", ".kkkWkkWWkkWkkk.", ".kkkWWWWWWWWkkk.",
        ".kkkkkkWWkkkkkk.", ".kkkWWWWWWWWkkk.", ".kWkkkkWWkkkkWk.", ".kWkWWWWWWWWkWk.", ".kWkkkkWWkkkkWk.",
        ".kWkkWWWWWWkkWk.", "..k.kkkWWkkk.k..", "....kWWWWWWk....", "....kkWkkWkk....", "................", "................"],
    "santa_hat": [
        "................", "............ww..", "..........rrwwww", ".........rrr.ww.", "........rrrr....",
        ".......rrrrr....", "......rrrrRr....", ".....rrrrrrr....", "....rrrRrrrrr...", "...rrrrrrrrrrr..",
        "..wwwwwwwwwwwww.", "..wswwswwwswwsw.", "..wwwwwwwwwwwww.", "................", "................", "................"],
    "reindeer_antlers": [
        "..b.b......b.b..", "..bbb......bbb..", "...b.b....b.b...", "...bbb....bbb...", "b...b......b...b",
        "bb..b......b..bb", ".bbbb......bbbb.", "....b......b....", "....b......b....", "....bb....bb....",
        "...BrrrrrrrrB...", "..BBrrrrrrrrBB..", "...B........B...", "................", "................", "................"],
    "winter_scarf": [
        "................", "................", "...rrrrrrrrrr...", "..rwwwwwwwwwwr..", "..rrrrrrrrrrrr..",
        "...rwwrrrrwwr...", ".......rrr......", ".......www......", ".......rrr......", ".......www......",
        ".......rrr......", ".......www......", ".......rrr......", ".......r.r......", "................", "................"],
    "gift_sack": [
        "......u..u......", ".......uu.......", ".....tttuttt....", ".....tttuttt....", "....CcuuuuucC...",
        "...ccccccccccc..", "..cccccCcccccc..", "..ccccccccCccc..", "..cccCcccccccc..", "..cccccccccCcc..",
        "..ccccccCccccc..", "..cccccccccccc..", "...cccccccccc...", "....CCCCCCCC....", "................", "................"],
    "ugly_sweater": [
        "................", "....wwww.wwww...", "..qqqqqqqqqqqq..", ".qqqrwrwrwrwqqq.", ".qqwrwrwrwrwrqq.",
        ".qq.qqqqyqqq.qq.", ".qq.qqqttqqq.qq.", ".qq.qqttrtqq.qq.", ".qq.qqtttwqq.qq.", ".rr.qtttttqq.rr.",
        "....qqqbbqqq....", "....rwrwrwrw....", "....wrwrwrwr....", "....rrrrrrrr....", "................", "................"],
    "slayer_crown": [
        "................", "................", "...G...G...G....", "..GLG.GLG.GLG...", "..GGG.GGG.GGG...",
        "..GGGGGGGGGGG...", "..GLGGGGGGGLG...", "..GGGeGuGeGGG...", "..GGGGGGGGGGG...", "..DDDDDDDDDDD...",
        "................", "................", "................", "................", "................", "................"],
    "champion_cape": [
        "................", "...GG......GG...", "..GrrrrrrrrrrG..", "..GrrrrrrrrrrG..", "..GrrrGrGrGrrG..",
        "..GrrrGGGGGrrG..", "..GrrrGuGGGrrG..", "..GrrrrrrrrrrG..", ".GrrrrrrrrrrrrG.", ".GrrrrrrrrrrrrG.",
        ".GrrrrrrrrrrrrG.", "GrrrrrrrrrrrrrrG", "GrrrrrrrrrrrrrrG", "GGGGGGGGGGGGGGGG", "................", "................"],
    "meteor_pendant": [
        "................", "..iI........Ii..", "...iI......Ii...", "....iI....Ii....", ".....iI..Ii.....",
        "......iIIi......", ".......ii.......", "......xxxx......", ".....xxfxxx.....", "....xxvxxfxx....",
        "....xfxxvxxx....", "....xxxfxxvx....", ".....xxvxfx.....", "......xxxx......", "................", "................"],
    "pirate_hat": [
        "................", "................", "................", ".....kkkkkk.....", "....kkkWWkkk....",
        "...kkkkWWkkkk...", "...kkkWkkWkkk...", "...kkkkWWkkkk...", "..kkkkWkkWkkkk..", "GkkkkkkkkkkkkkkG",
        "GGkkkkkkkkkkkkGG", ".GGkkkkkkkkkkGG.", "..GGGGGGGGGGGG..", "................", "................", "................"],
}

TOKENS = {  # seasonal currency (item textures + Index icons)
    "season_candy": [
        "................", "................", "......wwww......", ".....wwwwww.....", ".....yyyyyy.....",
        "....yyyyyyyy....", "....oooooooo....", "...oooooooooo...", "...oOooooooOo...", "...oooooooooo...",
        "....oooooooo....", ".....dooood.....", "......dddd......", "................", "................", "................"],
    "season_present": [
        "................", "......u..u......", ".....u.uu.u.....", "......uuuu......", "..ttttttuttttt..",
        "..tTttttuttTtt..", "..uuuuuuuuuuuu..", "..ttttttuttttt..", "..tttTttuttttt..", "..ttttttuttTtt..",
        "..tTttttuttttt..", "..ttttttuttttt..", "..ttttttuttttt..", "..CCCCCCCCCCCC..", "................", "................"],
}


def draw(grid, pal=PAL):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(grid):
        for x, ch in enumerate(row[:16]):
            if ch in pal: im.putpixel((x, y), pal[ch] + (255,))
    return im


PAL["T"] = (60, 170, 80)

HATS = {"pumpkin_head": pumpkin_head, "witch_hat": witch_hat, "santa_hat": santa_hat, "reindeer_antlers": reindeer_antlers,
        "slayer_crown": slayer_crown, "pirate_hat": pirate_hat}
NECKS = {"winter_scarf": winter_scarf, "meteor_pendant": meteor_pendant}
BACKS = {"bat_wings": bat_wings, "gift_sack": gift_sack, "champion_cape": champion_cape}
BODIES = {"skeleton_suit": skeleton_suit, "ugly_sweater": ugly_sweater}
NAMES = {"pumpkin_head": "Pumpkin Head", "witch_hat": "Witch Hat", "bat_wings": "Bat Wings", "skeleton_suit": "Skeleton Suit",
         "santa_hat": "Santa Hat", "reindeer_antlers": "Reindeer Antlers", "winter_scarf": "Winter Scarf", "gift_sack": "Gift Sack",
         "ugly_sweater": "Ugly Sweater", "slayer_crown": "Slayer's Crown", "champion_cape": "Champion's Cape",
         "meteor_pendant": "Meteor Pendant", "pirate_hat": "Pirate Captain's Hat"}
