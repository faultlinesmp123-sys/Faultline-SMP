#!/usr/bin/env python3
"""
Art for the way down to the Lost Explorer (FaultlineBosses, Below.java), written into an unpacked FaultlineSMP pack:

  swarm_*            Swarm, a citizen of the city Below the Bedrock: a 6-piece rig painted from a generated 64x64 skin
                     (same geometry as Rocco/Don, so renderRig() draws it). Deep teal hood over a shadowed face with
                     pale glowing eyes, ash-grey skin (no sun down there), a scarf, bandaged arms and shins, a ragged cloak,
                     and a little amber lantern on his belt.
  below_pickaxe      "a pickaxe you guys don't know": pitch black, crawling with pale runes (drawn with the enchant glint)
  textures/index/    Index icon: swarm

Usage: python3 tools/below_models.py <unpacked-pack-dir> [--preview <dir>]
"""
import json, math, os, sys
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from jacob_models import render, set_anchor, JOINT  # noqa: E402
from vendetta_models import (Skin, faces, paint_part, write_rig, face_icon, cuboid, grow, RIG, NS,  # noqa: E402
                             HEAD, HAT, BODY, JACKET, ARM_R, SLEEVE_R, ARM_L, SLEEVE_L, LEG_R, PANTS_R, LEG_L, PANTS_L)

SWARM = dict(skin=(150, 158, 170), skin_dk=(118, 124, 138), shadow=(26, 32, 36), eye=(178, 244, 255), eye_dk=(90, 170, 190),
             cloak=(34, 54, 60), cloak_dk=(19, 32, 36), cloak_hi=(64, 92, 98),
             wrap=(152, 142, 120), wrap_dk=(118, 108, 90), leather=(86, 62, 44), leather_dk=(56, 40, 30),
             tunic=(58, 50, 46), pants=(44, 42, 50), pants_dk=(30, 28, 34),
             lantern=(255, 194, 84), lantern_hi=(255, 236, 170), brass=(150, 118, 58))


def swarm(seed):
    s = Skin(seed)
    P = SWARM
    SK, SKD, SH = P["skin"], P["skin_dk"], P["shadow"]
    CL, CLD, CLH = P["cloak"], P["cloak_dk"], P["cloak_hi"]
    # ---- head: a face half lost in the hood's shadow
    hf = faces(HEAD, 8, 8, 8)
    paint_part(s, HEAD, 8, 8, 8, {"top": CLD, "bottom": SKD, "right": SH, "front": SK, "left": SH, "back": CLD})
    fx, fy, _, _ = hf["front"]
    s.fill(fx, fy, 8, 3, SH, 3)                                  # the hood's shadow over the brow
    s.fill(fx, fy + 3, 8, 2, (44, 50, 56), 3)                   # ...fading down over the eyes
    for x in (1, 2, 5, 6): s.px(fx + x, fy + 4, P["eye"])       # pale glowing eyes
    s.px(fx + 2, fy + 3, P["eye_dk"]); s.px(fx + 5, fy + 3, P["eye_dk"])
    s.px(fx + 3, fy + 5, SKD); s.px(fx + 4, fy + 5, SKD)         # nose shadow
    s.fill(fx, fy + 6, 8, 2, P["wrap"], 6)                       # a scarf up over the mouth
    for x in range(0, 8, 3): s.px(fx + x, fy + 6, P["wrap_dk"])
    # hat layer = the hood: closed everywhere but a frame around the face
    hh = faces(HAT, 8, 8, 8)
    for f in ("top", "right", "left", "back"):
        x, y, fw, fh = hh[f]
        s.fill(x, y, fw, fh, CL, 6)
        for i in range(0, fw, 3): s.fill(x + i, y, 1, fh, CLD, 4)   # folds
    x, y, fw, fh = hh["back"]; s.fill(x + 2, y + 5, 4, 3, CLD, 4)  # the hood's point hangs down the back
    x, y, _, _ = hh["front"]
    s.fill(x, y, 8, 2, CL, 6); s.fill(x, y, 8, 1, CLH, 4)       # the hood's rim
    s.fill(x, y + 2, 1, 6, CL, 6); s.fill(x + 7, y + 2, 1, 6, CL, 6)
    s.px(x + 1, y + 2, CLD); s.px(x + 6, y + 2, CLD)
    # ---- body: a worn tunic, crossed straps, a belt with his lantern
    bf = faces(BODY, 8, 12, 4)
    paint_part(s, BODY, 8, 12, 4, {"top": CLD, "bottom": P["pants_dk"], "right": P["tunic"], "front": P["tunic"], "left": P["tunic"], "back": P["tunic"]})
    fx, fy, _, _ = bf["front"]
    for j in range(8): s.px(fx + 1 + j * 6 // 8, fy + j, P["leather"]); s.px(fx + 6 - j * 6 // 8, fy + j, P["leather_dk"])  # straps
    s.fill(fx, fy + 8, 8, 1, P["leather_dk"], 3); s.px(fx + 3, fy + 8, P["brass"])                                     # belt
    s.px(fx + 6, fy + 9, P["brass"]); s.px(fx + 6, fy + 10, P["lantern_hi"]); s.px(fx + 6, fy + 11, P["lantern"])     # lantern on the hip
    s.px(fx + 5, fy + 10, P["lantern"]); s.px(fx + 7, fy + 10, P["lantern"])
    s.fill(fx + 1, fy + 9, 2, 2, P["leather"], 5)                                                                       # a pouch
    bx, by, _, _ = bf["back"]
    s.fill(bx + 1, by + 2, 6, 5, P["leather"], 6); s.fill(bx + 1, by + 2, 6, 1, P["leather_dk"], 3)                     # satchel
    # jacket layer = the cloak: a mantle over the shoulders, open in front, long at the back with a ragged hem
    jf = faces(JACKET, 8, 12, 4)
    for f in ("front", "back", "right", "left"):
        x, y, fw, fh = jf[f]
        if f == "back":
            for i in range(fw):
                ln = 10 + s.rng.randint(0, 2)
                s.fill(x + i, y, 1, ln, CL if i % 3 else CLD, 6)
        elif f == "front":
            s.fill(x, y, fw, 3, CL, 6); s.fill(x, y + 2, fw, 1, CLH, 4)
            s.fill(x, y + 3, 1, 8, CL, 6); s.fill(x + 7, y + 3, 1, 8, CL, 6)
            s.px(x + 3, y + 2, P["brass"]); s.px(x + 4, y + 2, P["brass"])                                                # clasp
        else:
            for i in range(fw): s.fill(x + i, y, 1, 9 + s.rng.randint(0, 2), CL if i % 2 else CLD, 6)
    tx, ty, tw, td = jf["top"]; s.fill(tx, ty, tw, td, CL, 6)
    # ---- arms: cloak sleeves to the elbow, bandaged forearms, ash-grey hands
    for net, sleeve in ((ARM_R, SLEEVE_R), (ARM_L, SLEEVE_L)):
        af = faces(net, 3, 12, 4)
        paint_part(s, net, 3, 12, 4, {"top": CL, "bottom": SK, "right": P["tunic"], "front": P["tunic"], "left": P["tunic"], "back": P["tunic"]})
        for f in ("right", "front", "left", "back"):
            x, y, fw, _ = af[f]
            s.fill(x, y + 5, fw, 5, P["wrap"], 6)
            for j in range(5, 10, 2): s.fill(x, y + j, fw, 1, P["wrap_dk"], 4)
            s.fill(x, y + 10, fw, 2, SK, 4)
        sf = faces(sleeve, 3, 12, 4)
        for f in ("right", "front", "left", "back", "top"):
            x, y, fw, fh = sf[f]
            s.fill(x, y, fw, 5 if f != "top" else fh, CL, 6)
            if f != "top": s.fill(x, y + 4, fw, 1, CLD, 4)
    # ---- legs: dark trousers, wrapped shins, leather boots; the cloak hangs behind
    for net, pants, outer in ((LEG_R, PANTS_R, "right"), (LEG_L, PANTS_L, "left")):
        lf = faces(net, 4, 12, 4)
        paint_part(s, net, 4, 12, 4, {"top": P["pants"], "bottom": P["leather_dk"], "right": P["pants"], "front": P["pants"], "left": P["pants"], "back": P["pants_dk"]})
        for f in ("right", "front", "left", "back"):
            x, y, fw, _ = lf[f]
            s.fill(x, y + 6, fw, 3, P["wrap"], 6); s.fill(x, y + 7, fw, 1, P["wrap_dk"], 4)
            s.fill(x, y + 9, fw, 3, P["leather"], 5); s.fill(x, y + 11, fw, 1, P["leather_dk"], 3)
        pf = faces(pants, 4, 12, 4)
        for f in (outer, "back"):
            x, y, fw, _ = pf[f]
            for i in range(fw): s.fill(x + i, y, 1, 3 + s.rng.randint(0, 3), CL if i % 2 else CLD, 6)
    return s.image()


def pickaxe():
    """32x32 handheld sprite: a black pick crawling with pale runes."""
    img = np.zeros((32, 32, 4))
    def px(x, y, c, a=255):
        if 0 <= x < 32 and 0 <= y < 32: img[y, x, :3] = c; img[y, x, 3] = a
    import random
    rng = random.Random(9)
    # the handle, bottom-left to the middle: black wood bound with pale wire
    for k in range(17):
        x, y = 4 + k, 27 - k
        for d in (-1, 0, 1): px(x + d, y, (22, 20, 26) if d == 0 else (40, 38, 48) if d < 0 else (12, 12, 14))
        px(x, y + 1, (8, 8, 10))
        if k % 5 == 2: px(x, y, (200, 200, 220)); px(x + 1, y, (150, 150, 170))
    px(3, 28, (60, 60, 74)); px(4, 28, (30, 30, 36)); px(3, 27, (30, 30, 36))      # the butt cap
    # the head: a wide curved blade across the top-right corner
    cx, cy = 3.0, 29.0
    for y in range(32):
        for x in range(32):
            r = math.hypot(x - cx, y - cy)
            a = math.degrees(math.atan2(cy - y, x - cx))
            if 20.4 <= r <= 26.2 and 7 <= a <= 83:
                edge = r > 25.4 or r < 21.2 or a < 10 or a > 80
                px(x, y, (64, 64, 80) if edge else (14, 14, 18))
    # white-hot points at both ends of the arc
    for k in range(3): px(25 + k, 25 - k, (230, 230, 245)); px(25 + k, 26 - k, (120, 120, 140))
    for k in range(3): px(4 - k, 6 - k, (230, 230, 245)); px(5 - k, 6 - k, (120, 120, 140))
    # the "weird enchanting": runes carved along the blade
    for t in range(14, 78, 7):
        rr = 23.2
        x = int(cx + math.cos(math.radians(t)) * rr); y = int(cy - math.sin(math.radians(t)) * rr)
        glyph = rng.choice(((0, 0), (1, 0), (0, 1))), rng.choice(((1, 1), (-1, 1), (1, -1)))
        px(x, y, (214, 200, 255))
        for dx, dy in glyph: px(x + dx, y + dy, (150, 120, 220))
    # the socket where the head meets the handle
    for dy in range(-1, 2):
        for dx in range(-1, 2): px(19 + dx, 12 + dy, (52, 52, 66) if dx or dy else (210, 196, 255))
    return Image.fromarray(img.astype(np.uint8), "RGBA")


def main():
    pack = sys.argv[1]
    prev = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    A = os.path.join(pack, "assets", NS)
    for d in ("models/item", "textures/item", "items", "textures/index"): os.makedirs(os.path.join(A, d), exist_ok=True)
    skin = swarm(51)
    write_rig(pack, "swarm", skin)
    pick = pickaxe()
    pick.save(os.path.join(A, "textures/item/below_pickaxe.png"))
    for sub, obj in (("models/item/below_pickaxe.json", {"parent": "minecraft:item/handheld", "textures": {"layer0": f"{NS}:item/below_pickaxe"}}),
                     ("items/below_pickaxe.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/below_pickaxe"}})):
        with open(os.path.join(A, sub), "w") as fh: json.dump(obj, fh, indent=1)
    face_icon(skin).save(os.path.join(A, "textures/index/swarm.png"))
    if prev:
        os.makedirs(prev, exist_ok=True)
        simg = np.asarray(skin).astype(float)
        placed = []
        for piece, (frm, to, net, onet, w, h, d, g) in RIG.items():
            o1, o2 = grow(frm, to, g)
            placed.append(([cuboid(frm, to, net, w, h, d), cuboid(o1, o2, onet, w, h, d)], simg, JOINT[piece]))
        set_anchor(0.92)
        figs = [render(placed, yaw, 10, 10, (300, 420)) for yaw in (20, 160, 90)]
        out = Image.new("RGBA", (300 * 3 + 300, 420), (34, 38, 46, 255))
        for i, f in enumerate(figs): out.paste(f, (i * 300, 0))
        big = pick.resize((256, 256), Image.NEAREST)
        out.paste(big, (920, 80), big)
        out.save(os.path.join(prev, "swarm.png"))


if __name__ == "__main__":
    main()
