#!/usr/bin/env python3
"""Generates the "Patty Cottage" starter house for Burger Empire.

One part list feeds two outputs so they never drift apart:
  - src/server/BuildPattyCottage.server.lua  (builds the house in Roblox Studio)
  - preview/house_parts.json                  (feeds the 3D web preview)

Units are Roblox studs. Y is up, the lot's grass top is y=0, the street is +Z.
Every box is given as min/max corners, which keeps the layout readable.
"""
import json
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

PARTS = []

# ---------------------------------------------------------------- palette
C = {
    "grass": (88, 160, 72), "grass2": (75, 145, 62), "soil": (105, 72, 48),
    "sidewalk": (205, 205, 200), "curb": (160, 160, 158), "asphalt": (58, 58, 64),
    "line": (245, 205, 48), "concrete": (180, 178, 172),
    "foundation": (96, 92, 96), "wall": (125, 190, 205), "wallbase": (80, 130, 150),
    "trim": (244, 244, 240), "roof": (196, 48, 36), "roof2": (172, 38, 30),
    "ridge": (120, 28, 24), "shutter": (245, 182, 40), "door": (150, 60, 40),
    "door2": (125, 48, 32), "gold": (240, 200, 60), "glass": (165, 215, 240),
    "floor": (176, 124, 78), "floor2": (160, 110, 68), "carpet": (110, 150, 200),
    "porch": (150, 104, 66), "wood": (122, 82, 50), "darkwood": (88, 58, 36),
    "brick": (165, 78, 58), "stone": (140, 138, 140), "white": (248, 248, 248),
    "black": (32, 32, 36), "steel": (175, 180, 186), "red": (210, 40, 40),
    "leaf": (52, 142, 64), "leaf2": (40, 120, 52), "trunk": (110, 72, 44),
    "pink": (240, 120, 170), "yellow": (250, 210, 60), "orange": (245, 140, 40),
    "purple": (150, 100, 210), "bun": (222, 160, 82), "bun2": (205, 140, 66),
    "patty": (102, 58, 34), "cheese": (252, 196, 40), "lettuce": (110, 190, 60),
    "tomato": (220, 52, 44), "seed": (250, 244, 220), "cream": (250, 240, 220),
    "warm": (255, 214, 140), "screen": (60, 110, 180), "sofa": (230, 120, 60),
    "sofa2": (245, 160, 90), "blanket": (60, 120, 200), "rug": (190, 60, 70),
    "noobYellow": (245, 205, 48), "noobBlue": (13, 105, 172), "noobGreen": (75, 151, 75),
}


def box(name, group, x0, y0, z0, x1, y1, z1, color, material="Plastic",
        shape="Block", transparency=0.0, studs=None, glow=0, preview_only=False):
    """Add a part from its min/max corners. Corners may be given in any order."""
    xa, xb = sorted((x0, x1))
    ya, yb = sorted((y0, y1))
    za, zb = sorted((z0, z1))
    col = C[color] if isinstance(color, str) else color
    if studs is None:
        studs = material == "Plastic" and shape == "Block"
    PARTS.append({
        "n": name, "g": group,
        "p": [round((xa + xb) / 2, 3), round((ya + yb) / 2, 3), round((za + zb) / 2, 3)],
        "s": [round(xb - xa, 3), round(yb - ya, 3), round(zb - za, 3)],
        "c": list(col), "m": material, "sh": shape, "t": transparency,
        "st": bool(studs), "gl": glow, "po": preview_only,
    })


def cyl_y(name, group, cx, cz, y0, y1, d, color, **kw):
    """Upright cylinder (disc) centred on cx,cz."""
    box(name, group, cx - d / 2, y0, cz - d / 2, cx + d / 2, y1, cz + d / 2, color,
        shape="CylY", studs=False, **kw)


def cyl_x(name, group, x0, x1, cy, cz, d, color, **kw):
    """Cylinder lying along X (Roblox's native cylinder axis), e.g. a wheel."""
    box(name, group, x0, cy - d / 2, cz - d / 2, x1, cy + d / 2, cz + d / 2, color,
        shape="CylX", studs=False, **kw)


def ball(name, group, cx, cy, cz, d, color, **kw):
    box(name, group, cx - d / 2, cy - d / 2, cz - d / 2, cx + d / 2, cy + d / 2, cz + d / 2,
        color, shape="Ball", studs=False, **kw)


# ------------------------------------------------- walls with openings
def wbox(name, group, axis, a0, a1, n0, n1, y0, y1, color, **kw):
    """Box in wall-local coords: a runs along the wall, n is across it."""
    if axis == "x":
        box(name, group, a0, y0, n0, a1, y1, n1, color, **kw)
    else:
        box(name, group, n0, y0, a0, n1, y1, a1, color, **kw)


def wall(name, group, axis, n0, n1, a0, a1, y0, y1, openings, color):
    """Solid wall from a0..a1 with rectangular holes [(oa0, oa1, oy0, oy1)]."""
    cuts = {a0, a1}
    for oa0, oa1, _, _ in openings:
        cuts.update(v for v in (oa0, oa1) if a0 < v < a1)
    cuts = sorted(cuts)
    for ca, cb in zip(cuts, cuts[1:]):
        holes = sorted((oy0, oy1) for oa0, oa1, oy0, oy1 in openings
                       if oa0 <= ca and oa1 >= cb)
        y = y0
        for hy0, hy1 in holes:
            if hy0 > y:
                wbox(name, group, axis, ca, cb, n0, n1, y, hy0, color)
            y = max(y, hy1)
        if y < y1:
            wbox(name, group, axis, ca, cb, n0, n1, y, y1, color)


def window(group, axis, outer, out, a0, a1, y0, y1, shutters=True, flowers=False):
    """Glass, cross mullions, trim frame, sill, shutters and an optional flower box.

    outer: coordinate of the wall's outside face. out: +1/-1, which way is outside.
    """
    mid = outer - out * 0.5
    wbox("WindowGlass", group, axis, a0, a1, mid - 0.1, mid + 0.1, y0, y1, "glass",
         material="Glass", transparency=0.45, glow=2)
    am, ym = (a0 + a1) / 2, (y0 + y1) / 2
    wbox("Mullion", group, axis, am - 0.15, am + 0.15, mid - 0.2, mid + 0.2, y0, y1, "trim")
    wbox("Mullion", group, axis, a0, a1, mid - 0.2, mid + 0.2, ym - 0.15, ym + 0.15, "trim")
    f0, f1 = outer, outer + out * 0.35
    wbox("WindowTrim", group, axis, a0 - 0.4, a1 + 0.4, f0, f1, y1, y1 + 0.4, "trim")
    wbox("WindowTrim", group, axis, a0 - 0.4, a0, f0, f1, y0, y1, "trim")
    wbox("WindowTrim", group, axis, a1, a1 + 0.4, f0, f1, y0, y1, "trim")
    wbox("WindowSill", group, axis, a0 - 0.6, a1 + 0.6, outer - out * 0.2, outer + out * 0.8,
         y0 - 0.4, y0, "trim")
    if shutters:
        sw = (a1 - a0) * 0.4
        for s0, s1 in ((a0 - 0.4 - sw, a0 - 0.4), (a1 + 0.4, a1 + 0.4 + sw)):
            wbox("Shutter", group, axis, s0, s1, f0, outer + out * 0.3, y0, y1, "shutter")
            for k in range(4):  # louvre slats
                yy = y0 + (y1 - y0) * (k + 0.6) / 4.4
                wbox("ShutterSlat", group, axis, s0 + 0.2, s1 - 0.2, f0,
                     outer + out * 0.42, yy, yy + 0.25, (220, 160, 30))
    if flowers:
        b0, b1 = outer, outer + out * 1.3
        wbox("FlowerBox", group, axis, a0 - 0.2, a1 + 0.2, b0, b1, y0 - 1.6, y0 - 0.4, "wood")
        wbox("FlowerSoil", group, axis, a0, a1, b0 + out * 0.2, b1 - out * 0.2,
             y0 - 0.4, y0 - 0.3, "soil")
        cols = ["red", "pink", "yellow", "purple", "orange"]
        n = int(a1 - a0)
        for k in range(n):
            fa = a0 + 0.5 + k
            fn = (b0 + b1) / 2
            wbox("Leaves", group, axis, fa - 0.3, fa + 0.3, fn - 0.3, fn + 0.3,
                 y0 - 0.3, y0 + 0.3, "leaf")
            wbox("Flower", group, axis, fa - 0.25, fa + 0.25, fn - 0.25, fn + 0.25,
                 y0 + 0.3, y0 + 0.75, cols[k % len(cols)])


# ================================================================ STREET
G = "Street"
box("Road", G, -32, -1, 31, 32, 0, 47, "asphalt")
for x in range(-30, 32, 8):
    box("LaneDash", G, x, 0, 38.75, x + 4, 0.2, 39.25, "line", studs=False)
box("Curb", G, -32, -1, 30, 32, 0.6, 31, "curb")
box("Sidewalk", G, -32, -1, 24, 32, 0.4, 30, "sidewalk")
for x in range(-28, 32, 6):  # expansion joints
    box("SidewalkJoint", G, x - 0.1, 0.4, 24, x + 0.1, 0.42, 30, "curb", studs=False)

# Street lamps
for lx in (-22, 28):
    box("LampBase", G, lx - 0.8, 0.4, 28.2, lx + 0.8, 1.2, 29.8, "black")
    box("LampPole", G, lx - 0.3, 1.2, 28.7, lx + 0.3, 11, 29.3, "black", studs=False)
    box("LampArm", G, lx - 0.25, 10.6, 29.3, lx + 0.25, 11, 32, "black", studs=False)
    box("LampHood", G, lx - 0.9, 10.4, 31.2, lx + 0.9, 11.2, 33, "black")
    box("LampBulb", G, lx - 0.6, 9.9, 31.5, lx + 0.6, 10.4, 32.7, "warm", material="Neon", glow=1)

# Fire hydrant
cyl_y("Hydrant", G, -27, 27, 0.4, 2.6, 1.2, "red")
cyl_y("HydrantCap", G, -27, 27, 2.6, 3.0, 0.9, "red")
box("HydrantNozzle", G, -27.9, 1.5, 26.7, -26.1, 2.0, 27.3, (180, 30, 30), studs=False)

# ================================================================ LOT
G = "Lot"
box("Lawn", G, -32, -1, -32, 32, 0, 24, "grass")
# Picket fence along the front, with gaps for the path and the driveway
for fx0, fx1 in ((-31.5, -6.5), (-1.5, 10.5), (23.5, 31.5)):
    box("FenceRail", G, fx0, 0.9, 23.5, fx1, 1.2, 23.7, "trim", studs=False)
    box("FenceRail", G, fx0, 2.3, 23.5, fx1, 2.6, 23.7, "trim", studs=False)
    x = fx0
    while x <= fx1 - 0.5 + 1e-6:
        box("Picket", G, x, 0, 23.6, x + 0.6, 3.0, 24.0, "trim")
        box("PicketTip", G, x + 0.15, 3.0, 23.7, x + 0.45, 3.3, 23.9, "trim", studs=False)
        x += 1.25
# Wooden privacy fence around the sides and back
for name, x0, z0, x1, z1 in (("FenceBack", -32, -32, 32, -31.6),
                             ("FenceLeft", -32, -32, -31.6, 23.6),
                             ("FenceRight", 31.6, -32, 32, 23.6)):
    box(name, G, x0, 0, z0, x1, 4.4, z1, "wood")
for z in range(-32, 24, 6):
    for x in (-32.2, 31.4):
        box("FencePost", G, x, 0, z, x + 0.8, 5.0, z + 0.8, "darkwood")
for x in range(-32, 33, 6):
    box("FencePost", G, x - 0.4, 0, -32.2, x + 0.4, 5.0, -31.4, "darkwood")

# Stepping-stone path from the porch to the sidewalk
for i, z in enumerate((15.6, 18.4, 21.2)):
    off = 0.4 if i % 2 else -0.4
    box("PathStone", G, -5.6 + off, 0, z, -1.6 + off, 0.25, z + 2, "stone")

# Driveway
box("Driveway", G, 11, 0, 6, 23, 0.3, 24, "concrete")
for z in (12, 18):
    box("DrivewayJoint", G, 11, 0.3, z - 0.1, 23, 0.32, z + 0.1, "curb", studs=False)

# Burger mailbox
box("MailPost", G, 7.6, 0, 21.6, 8.4, 3.4, 22.4, "wood", studs=False)
cyl_y("MailBunBottom", G, 8, 22, 3.4, 3.9, 2.2, "bun")
cyl_y("MailPatty", G, 8, 22, 3.9, 4.35, 2.4, "patty")
box("MailCheese", G, 7.0, 4.35, 21.0, 9.0, 4.5, 23.0, "cheese", studs=False)
cyl_y("MailLettuce", G, 8, 22, 4.5, 4.7, 2.5, "lettuce")
cyl_y("MailBunTop", G, 8, 22, 4.7, 5.5, 2.2, "bun")
cyl_y("MailBunDome", G, 8, 22, 5.5, 5.85, 1.5, "bun2")
box("MailFlag", G, 9.1, 4.8, 21.9, 9.3, 6.0, 22.1, "red", studs=False)
box("MailFlag", G, 9.1, 5.6, 22.1, 9.3, 6.0, 22.8, "red", studs=False)

# Blocky trees
def tree(x, z, h=6):
    box("Trunk", G, x - 1, 0, z - 1, x + 1, h, z + 1, "trunk")
    y = h - 0.6
    for k, w in enumerate((9, 7, 5, 3)):
        t = 1.2 if w == 3 else 2.4
        box("Leaves", G, x - w / 2, y, z - w / 2, x + w / 2, y + t, z + w / 2,
            "leaf" if k % 2 == 0 else "leaf2")
        y += t
    for dx, dz in ((-4.5, 1), (2, 4.5), (4.5, -2), (-1, -4.5)):  # little apples
        box("Apple", G, x + dx - 0.35, h + 0.4, z + dz - 0.35, x + dx + 0.35, h + 1.1,
            z + dz + 0.35, "red", studs=False)


tree(-25, 12)
tree(-25, -10, 7)
tree(26, -24, 8)

# Garden beds with round bushes and flowers on both sides of the porch
for bx0, bx1 in ((-15.5, -10.5), (3.5, 9.5)):
    box("GardenBed", G, bx0, 0, 6.2, bx1, 0.5, 9.2, "soil")
    box("BedEdge", G, bx0 - 0.3, 0, 9.2, bx1 + 0.3, 0.8, 9.6, "stone")
    ball("Bush", G, bx0 + 1.4, 1.5, 7.6, 2.6, "leaf2")
    ball("Bush", G, bx1 - 1.4, 1.5, 7.6, 2.6, "leaf2")
    cols = ["pink", "yellow", "red", "purple"]
    x, k = bx0 + 3, 0
    while x < bx1 - 2.6:
        box("Stem", G, x - 0.1, 0.5, 8.3, x + 0.1, 1.5, 8.5, "leaf", studs=False)
        box("Flower", G, x - 0.35, 1.5, 8.05, x + 0.35, 2.0, 8.75, cols[k % 4], studs=False)
        x += 1.0
        k += 1

# Backyard patio: checkerboard tiles, BBQ grill, table with umbrella
for i in range(4):
    for j in range(2):
        box("PatioTile", G, -14 + i * 4, 0, -24 + j * 4, -10 + i * 4, 0.3, -20 + j * 4,
            "stone" if (i + j) % 2 else "concrete")
box("PatioTile", G, -14, 0, -16.6, 2, 0.3, -16, "concrete")
# BBQ grill
for lx, lz in ((-6.8, -21.8), (-4.2, -21.8), (-6.8, -20.2), (-4.2, -20.2)):
    box("GrillLeg", G, lx - 0.15, 0.3, lz - 0.15, lx + 0.15, 2.6, lz + 0.15, "black", studs=False)
box("GrillBody", G, -7.2, 2.6, -22.2, -3.8, 3.8, -19.8, "black")
box("GrillLid", G, -7.0, 3.8, -22.0, -4.0, 4.6, -20.0, (150, 30, 30))
box("GrillHandle", G, -6.2, 4.3, -19.8, -4.8, 4.5, -19.5, "steel", studs=False)
box("GrillShelf", G, -9.0, 3.2, -22.0, -7.2, 3.4, -20.0, "wood")
box("GrillShelf", G, -3.8, 3.2, -22.0, -2.0, 3.4, -20.0, "wood")
cyl_y("PropaneTank", G, -2.8, -19.2, 0.3, 2.0, 1.1, "white")
box("GrillPatty", G, -8.6, 3.4, -21.6, -7.8, 3.6, -20.8, "patty", studs=False)
box("GrillPatty", G, -8.6, 3.4, -20.7, -7.8, 3.6, -20.2, "patty", studs=False)
# Table + umbrella
cyl_y("PatioTableBase", G, 2, -21, 0.3, 0.5, 1.6, "white")
box("PatioTablePole", G, 1.85, 0.5, -21.15, 2.15, 8.2, -20.85, "white", studs=False)
cyl_y("PatioTableTop", G, 2, -21, 2.6, 2.9, 4.2, "white")
cyl_y("Umbrella", G, 2, -21, 7.0, 7.4, 7.0, "red")
cyl_y("Umbrella", G, 2, -21, 7.4, 7.8, 5.0, "white")
cyl_y("Umbrella", G, 2, -21, 7.8, 8.2, 3.0, "red")
for cx in (-0.6, 4.6):
    box("PatioChairSeat", G, cx - 0.8, 1.6, -21.8, cx + 0.8, 1.9, -20.2, "white")
    box("PatioChairBase", G, cx - 0.6, 0.3, -21.6, cx + 0.6, 1.6, -20.4, "white", studs=False)
    back = cx - 0.8 if cx < 2 else cx + 0.5
    box("PatioChairBack", G, back, 1.9, -21.8, back + 0.3, 3.6, -20.2, "white", studs=False)

# Doghouse with a stepped roof
DX, DZ = 22, -26
box("DogBase", G, DX - 2.5, 0, DZ - 3, DX + 2.5, 0.4, DZ + 3, "darkwood")
box("DogHouse", G, DX - 2, 0.4, DZ - 2.5, DX + 2, 3.6, DZ + 2.5, (210, 170, 120))
box("DogDoor", G, DX - 0.9, 0.4, DZ + 2.45, DX + 0.9, 2.6, DZ + 2.6, "black", studs=False)
box("DogRoof", G, DX - 2.4, 3.6, DZ - 2.9, DX + 2.4, 4.4, DZ + 2.9, "roof")
box("DogRoof", G, DX - 1.6, 4.4, DZ - 2.9, DX + 1.6, 5.2, DZ + 2.9, "roof2")
box("DogRoof", G, DX - 0.8, 5.2, DZ - 2.9, DX + 0.8, 6.0, DZ + 2.9, "ridge")
box("DogNamePlate", G, DX - 0.7, 2.8, DZ + 2.5, DX + 0.7, 3.3, DZ + 2.65, "yellow", studs=False)
cyl_y("DogBowl", G, DX + 1.8, DZ + 4.4, 0, 0.4, 1.2, "red")
box("DogBone", G, DX - 2.2, 0, DZ + 4.2, DX - 0.8, 0.3, DZ + 4.6, "white", studs=False)

# Trash bins beside the garage
for i, col in enumerate(((60, 120, 70), (50, 90, 160))):
    bx = 26 + i * 2.6
    box("Bin", G, bx - 1, 0, 0, bx + 1, 3.2, 2.2, col)
    box("BinLid", G, bx - 1.1, 3.2, -0.1, bx + 1.1, 3.5, 2.3, tuple(int(v * 0.8) for v in col))
    cyl_x("BinWheel", G, bx - 1.05, bx - 0.85, 0.35, 0.2, 0.7, "black")

# ================================================================ HOUSE
G = "House"
HX0, HX1, HZ0, HZ1 = -16, 10, -16, 6   # outer footprint
FY, WY = 1, 14                          # floor top, wall top
box("Foundation", G, HX0 - 0.4, 0, HZ0 - 0.4, HX1 + 0.4, FY, HZ1 + 0.4, "foundation")

# Outer walls with openings (a0, a1, y0, y1)
FRONT_WIN = [(-13, -9, 4, 9.5), (3, 7, 4, 9.5)]
DOOR = (-6, -1, FY, 9.5)
wall("WallFront", G, "x", 5, 6, HX0, HX1, FY, WY, FRONT_WIN + [DOOR], "wall")
BACK_WIN = [(-8.5, -4.5, 4, 9.5), (0, 4, 6, 10)]
wall("WallBack", G, "x", -16, -15, HX0, HX1, FY, WY, BACK_WIN, "wall")
LEFT_WIN = [(-13, -9, 4, 9.5)]
wall("WallLeft", G, "z", -16, -15, HZ0 + 1, HZ1 - 1, FY, WY, LEFT_WIN, "wall")
GARAGE_DOOR = (-9, -5.5, FY, 9)
wall("WallRight", G, "z", 9, 10, HZ0 + 1, HZ1 - 1, FY, WY, [GARAGE_DOOR], "wall")

for a0, a1, y0, y1 in FRONT_WIN:
    window(G, "x", 6, +1, a0, a1, y0, y1, flowers=True)
for a0, a1, y0, y1 in BACK_WIN:
    window(G, "x", -16, -1, a0, a1, y0, y1)
for a0, a1, y0, y1 in LEFT_WIN:
    window(G, "z", -16, -1, a0, a1, y0, y1)

# Corner posts, base band and top fascia
for cx, cz in ((HX0, HZ0), (HX0, HZ1), (HX1, HZ0), (HX1, HZ1)):
    box("CornerTrim", G, cx - 0.6, FY, cz - 0.6, cx + 0.6, WY, cz + 0.6, "trim", studs=False)
for name, x0, z0, x1, z1 in (("BaseBand", HX0, HZ1, -6, HZ1 + 0.2),
                             ("BaseBand", -1, HZ1, HX1, HZ1 + 0.2),
                             ("BaseBand", HX0, HZ0 - 0.2, HX1, HZ0),
                             ("BaseBand", HX0 - 0.2, HZ0, HX0, HZ1)):
    box(name, G, x0, FY, z0, x1, FY + 1.2, z1, "wallbase", studs=False)
for name, x0, z0, x1, z1 in (("Fascia", HX0, HZ1, HX1, HZ1 + 0.25),
                             ("Fascia", HX0, HZ0 - 0.25, HX1, HZ0),
                             ("Fascia", HX0 - 0.25, HZ0, HX0, HZ1)):
    box(name, G, x0, WY - 0.8, z0, x1, WY, z1, "trim", studs=False)

# Front door: panelled slab, brass knob, trim, transom light and a lantern
d0, d1, dy0, dy1 = DOOR
box("FrontDoor", G, d0, dy0, 5.3, d1, dy1 - 1.4, 5.7, "door")
for py0, py1 in ((dy0 + 0.8, dy0 + 3.6), (dy0 + 4.2, dy1 - 2.2)):
    box("DoorPanel", G, d0 + 0.6, py0, 5.7, (d0 + d1) / 2 - 0.3, py1, 5.85, "door2", studs=False)
    box("DoorPanel", G, (d0 + d1) / 2 + 0.3, py0, 5.7, d1 - 0.6, py1, 5.85, "door2", studs=False)
ball("DoorKnob", G, d1 - 0.8, 4.4, 5.95, 0.45, "gold", material="Metal")
box("Transom", G, d0, dy1 - 1.4, 5.4, d1, dy1, 5.6, "glass", material="Glass",
    transparency=0.45, glow=2)
box("DoorTrim", G, d0 - 0.5, dy1, 6, d1 + 0.5, dy1 + 0.5, 6.35, "trim", studs=False)
box("DoorTrim", G, d0 - 0.5, dy0, 6, d0, dy1, 6.35, "trim", studs=False)
box("DoorTrim", G, d1, dy0, 6, d1 + 0.5, dy1, 6.35, "trim", studs=False)
box("HouseNumber", G, d0 + 1.6, dy1 + 0.6, 6, d1 - 1.6, dy1 + 1.5, 6.15, "gold", studs=False)
box("Lantern", G, 0.0, 6.4, 6.0, 0.8, 7.8, 6.8, "black", studs=False)
box("LanternGlass", G, 0.1, 6.6, 6.1, 0.7, 7.5, 6.7, "warm", material="Neon", glow=1)

# Porch: deck, steps, railings, columns, little roof and a welcome mat
box("PorchDeck", G, -10.5, 0, 6, 3.5, FY, 12, "porch")
for z in range(7, 12, 1):
    box("DeckBoard", G, -10.5, FY, z - 0.04, 3.5, FY + 0.03, z + 0.04, "darkwood", studs=False)
box("Step", G, -6, 0, 12, -1, 0.66, 13.5, "porch")
box("Step", G, -6, 0, 13.5, -1, 0.33, 15, "porch")
box("WelcomeMat", G, -5.2, FY, 6.6, -1.8, FY + 0.1, 8.6, "rug", studs=False)
for x0, x1 in ((-10.5, -6), (-1, 3.5)):
    box("PorchRail", G, x0, 3.4, 11.6, x1, 3.8, 12, "trim")
    x = x0 + 0.6
    while x < x1 - 0.4:
        box("Baluster", G, x, FY, 11.7, x + 0.3, 3.4, 11.9, "trim", studs=False)
        x += 0.9
box("PorchRail", G, -10.5, 3.4, 6, -10.1, 3.8, 12, "trim")
for cx in (-10, 3):
    box("PorchColumn", G, cx - 0.6, FY, 11.0, cx + 0.6, 10.2, 12.2, "trim")
    box("ColumnBase", G, cx - 0.8, FY, 10.8, cx + 0.8, FY + 0.6, 12.4, "trim")
box("PorchRoof", G, -11, 10.2, 6, 4, 11.0, 12.8, "roof")
box("PorchRoof", G, -10.4, 11.0, 6, 3.4, 11.8, 10.8, "roof2")
box("PorchFascia", G, -11, 10.2, 12.8, 4, 10.8, 13.0, "trim", studs=False)

# Chimney (pokes through the roof)
box("Chimney", G, -13, WY, -12, -10, 24.5, -9, "brick")
box("ChimneyCap", G, -13.4, 24.5, -12.4, -9.6, 25.1, -8.6, "stone")
box("ChimneyBand", G, -13.2, 20.5, -12.2, -9.8, 20.9, -8.8, (140, 64, 48), studs=False)

# ================================================================ INTERIOR
G = "Interior"
box("FloorWood", G, -15, FY, -5.5, -2.5, FY + 0.2, 5, "floor")
box("FloorWood", G, -2.5, FY, -15, 9, FY + 0.2, 5, "floor")
box("FloorCarpet", G, -15, FY, -15, -3.5, FY + 0.2, -5.5, "carpet")
F = FY + 0.2  # furniture sits here
wall("Partition", G, "x", -5.5, -4.5, -15, -2.5, FY, WY, [(-7.5, -4, FY, 9)], "cream")
wall("Partition", G, "z", -3.5, -2.5, -15, -5.5, FY, WY, [], "cream")

# Living room
box("Rug", G, -12.5, F, -3.5, -6, F + 0.12, 3.5, "rug", studs=False)
box("RugBorder", G, -12, F + 0.12, -3, -6.5, F + 0.14, 3, (230, 190, 90), studs=False)
box("RugInner", G, -11.6, F + 0.14, -2.6, -6.9, F + 0.16, 2.6, "rug", studs=False)
box("TVStand", G, -15, F, -3, -13.4, F + 2, 3, "darkwood")
box("TV", G, -14.7, F + 2.2, -2.8, -14.2, F + 5.6, 2.8, "black", studs=False)
box("TVScreen", G, -14.2, F + 2.45, -2.55, -14.1, F + 5.35, 2.55, "screen", material="Neon", glow=0)
box("TVFoot", G, -14.8, F + 2, -0.6, -14.0, F + 2.2, 0.6, "black", studs=False)
box("CoffeeTable", G, -11.4, F + 1.4, -1.5, -9, F + 1.8, 1.5, "wood")
box("CoffeeTableBase", G, -11, F, -1.1, -9.4, F + 1.4, 1.1, "darkwood", studs=False)
box("BurgerBook", G, -10.8, F + 1.8, -1, -9.6, F + 2.1, 0.4, "red", studs=False)
box("Sofa", G, -8, F, -3.6, -5, F + 1.6, 3.6, "sofa")
box("SofaBack", G, -6, F + 1.6, -3.6, -5, F + 4, 3.6, "sofa")
box("SofaArm", G, -8, F + 1.6, -3.6, -6, F + 2.6, -2.8, "sofa")
box("SofaArm", G, -8, F + 1.6, 2.8, -6, F + 2.6, 3.6, "sofa")
box("Cushion", G, -7.8, F + 1.6, -2.8, -6, F + 2.1, -0.05, "sofa2")
box("Cushion", G, -7.8, F + 1.6, 0.05, -6, F + 2.1, 2.8, "sofa2")
box("Pillow", G, -6.6, F + 2.1, -2.5, -6.0, F + 3.2, -1.3, "yellow", studs=False)
box("FloorLampBase", G, -14.8, F, 3.4, -13.6, F + 0.3, 4.6, "black", studs=False)
box("FloorLampPole", G, -14.35, F + 0.3, 3.85, -14.05, F + 6, 4.15, "black", studs=False)
cyl_y("FloorLampShade", G, -14.2, 4, F + 6, F + 7.4, 1.8, "warm", material="Neon", glow=1)
box("Plant", G, -3.6, F, 3.6, -2.4, F + 1.2, 4.8, "brick")
ball("PlantLeaves", G, -3.0, F + 2.2, 4.2, 2.0, "leaf")

# Bedroom
box("BedFrame", G, -14.5, F, -15, -9.5, F + 1.2, -8, "wood")
box("Mattress", G, -14.3, F + 1.2, -14.6, -9.7, F + 2.2, -8.2, "white")
box("Blanket", G, -14.4, F + 2.2, -12.2, -9.6, F + 2.45, -8.1, "blanket", studs=False)
box("BlanketFold", G, -14.4, F + 2.45, -12.2, -9.6, F + 2.6, -11.6, "white", studs=False)
box("Pillow", G, -14, F + 2.2, -14.4, -10, F + 2.8, -13.1, "white", studs=False)
box("Headboard", G, -14.7, F, -15, -9.3, F + 4.2, -14.5, "darkwood")
box("Nightstand", G, -9.1, F, -15, -7.5, F + 2.2, -13.5, "wood")
cyl_y("LampBase", G, -8.3, -14.2, F + 2.2, F + 3.0, 0.4, "black")
cyl_y("LampShade", G, -8.3, -14.2, F + 3.0, F + 3.9, 1.1, "warm", material="Neon", glow=1)
box("Wardrobe", G, -5.6, F, -13, -3.5, F + 8, -8.5, "wood")
box("WardrobeSeam", G, -5.75, F + 0.4, -10.8, -5.6, F + 7.6, -10.7, "darkwood", studs=False)
ball("WardrobeKnob", G, -5.75, F + 4, -11.2, 0.3, "gold", material="Metal")
ball("WardrobeKnob", G, -5.75, F + 4, -10.3, 0.3, "gold", material="Metal")
box("BedRug", G, -14, F, -7.8, -10, F + 0.1, -6, "pink", studs=False)

# Kitchen: counter run, sink, stove, fridge, upper cabinets, table and chairs
box("Cabinets", G, -2.5, F, -15, 4, F + 3, -12, "white")
box("Countertop", G, -2.5, F + 3, -15, 4, F + 3.4, -11.8, (90, 90, 96))
for cx in (-1.2, 2.0):
    box("CabinetDoor", G, cx - 1.1, F + 0.4, -12, cx + 1.1, F + 2.6, -11.9, (230, 230, 228), studs=False)
    box("CabinetHandle", G, cx - 0.3, F + 2.1, -11.9, cx + 0.3, F + 2.3, -11.75, "steel", studs=False)
box("Sink", G, 1, F + 3.15, -14.5, 3, F + 3.42, -12.6, (60, 70, 80), studs=False)
box("Faucet", G, 1.8, F + 3.4, -14.9, 2.2, F + 4.6, -14.5, "steel", studs=False)
box("Faucet", G, 1.8, F + 4.3, -14.5, 2.2, F + 4.6, -13.6, "steel", studs=False)
box("Stove", G, 4, F, -15, 6.5, F + 3.4, -12, "black")
for sx, sz in ((4.6, -14.3), (5.9, -14.3), (4.6, -12.9), (5.9, -12.9)):
    cyl_y("Burner", G, sx, sz, F + 3.4, F + 3.5, 0.8, (90, 90, 96))
box("OvenWindow", G, 4.4, F + 0.8, -12, 6.1, F + 2.2, -11.9, (70, 50, 40), studs=False)
box("Pan", G, 5.3, F + 3.5, -13.4, 6.3, F + 3.75, -12.4, (50, 50, 55), studs=False)
box("Patty", G, 5.45, F + 3.75, -13.25, 6.15, F + 3.9, -12.55, "patty", studs=False)
box("Hood", G, 4, F + 8, -15, 6.5, F + 9, -13.4, "steel")
box("HoodChimney", G, 4.7, F + 9, -15, 5.8, WY, -14.2, "steel", studs=False)
box("Fridge", G, 6.6, F, -15, 9, F + 9.5, -12, "white")
box("FridgeSeam", G, 6.6, F + 6, -12, 9, F + 6.1, -11.9, (190, 190, 190), studs=False)
box("FridgeHandle", G, 6.9, F + 3.5, -11.95, 7.2, F + 5.6, -11.7, "steel", studs=False)
box("FridgeHandle", G, 6.9, F + 6.6, -11.95, 7.2, F + 8, -11.7, "steel", studs=False)
box("FridgeMagnet", G, 7.8, F + 7.2, -11.95, 8.4, F + 7.8, -11.85, "cheese", studs=False)
box("UpperCabinet", G, -2.5, F + 7, -15, -0.2, F + 10.5, -13.6, "white")
box("DiningTable", G, 1, F + 2.6, -6.5, 6, F + 3, -2, "wood")
for lx, lz in ((1.3, -6.2), (5.7, -6.2), (1.3, -2.3), (5.7, -2.3)):
    box("TableLeg", G, lx - 0.2, F, lz - 0.2, lx + 0.2, F + 2.6, lz + 0.2, "darkwood", studs=False)
for cx in (2.3, 4.7):
    for cz, back in ((-7.5, -8.1), (-1.0, -0.6)):
        box("ChairSeat", G, cx - 0.8, F + 1.6, cz - 0.8, cx + 0.8, F + 2.0, cz + 0.8, "red")
        box("ChairBase", G, cx - 0.5, F, cz - 0.5, cx + 0.5, F + 1.6, cz + 0.5, "darkwood", studs=False)
        box("ChairBack", G, cx - 0.8, F + 2.0, back - 0.25, cx + 0.8, F + 4.2, back + 0.25, "red", studs=False)
cyl_y("Plate", G, 2.6, -4.3, F + 3, F + 3.08, 1.2, "white")
cyl_y("Plate", G, 4.4, -4.3, F + 3, F + 3.08, 1.2, "white")
cyl_y("PlateBurger", G, 2.6, -4.3, F + 3.08, F + 3.45, 0.7, "bun")
cyl_y("PlateBurger", G, 4.4, -4.3, F + 3.08, F + 3.45, 0.7, "bun")
box("KitchenPlant", G, 7.8, F, 3.6, 8.8, F + 1, 4.6, "brick")
ball("KitchenPlantLeaves", G, 8.3, F + 1.8, 4.1, 1.6, "leaf2")
# Interior door to the garage
g0, g1, gy0, gy1 = GARAGE_DOOR
box("GarageAccessDoor", G, 9.3, gy0, g0, 9.7, gy1, g1, (240, 240, 236))
ball("GarageAccessKnob", G, 9.1, 4.4, g1 - 0.6, 0.35, "gold", material="Metal")

# ================================================================ GARAGE
G = "Garage"
GX0, GX1, GZ0, GZ1, GWY = 10, 24, -12, 6, 11
box("GarageSlab", G, GX0, 0, GZ0, GX1, 0.4, GZ1, "concrete")
GDOOR = (11.5, 22.5, 0.4, 9)
wall("GarageFront", G, "x", 5, 6, GX0, GX1, 0.4, GWY, [GDOOR], "wall")
wall("GarageBack", G, "x", -12, -11, GX0, GX1, 0.4, GWY, [], "wall")
GSIDE_WIN = [(-6, -2, 4, 8)]
wall("GarageSide", G, "z", 23, 24, GZ0 + 1, GZ1 - 1, 0.4, GWY, GSIDE_WIN, "wall")
window(G, "z", 24, +1, -6, -2, 4, 8, shutters=False)
for cx, cz in ((GX1, GZ0), (GX1, GZ1)):
    box("CornerTrim", G, cx - 0.6, 0.4, cz - 0.6, cx + 0.6, GWY, cz + 0.6, "trim", studs=False)
box("Fascia", G, GX0, GWY - 0.8, GZ1, GX1, GWY, GZ1 + 0.25, "trim", studs=False)
gd0, gd1, gdy0, gdy1 = GDOOR
box("GarageDoor", G, gd0, gdy0, 5.3, gd1, gdy1, 5.7, "white")
for gy in (2.5, 4.6, 6.7):
    box("DoorGroove", G, gd0, gy - 0.06, 5.7, gd1, gy + 0.06, 5.78, (205, 205, 205), studs=False)
for k in range(4):
    wx = gd0 + 0.8 + k * 2.6
    box("GarageDoorWindow", G, wx, 7.3, 5.7, wx + 1.8, 8.4, 5.8, "glass", material="Glass",
        transparency=0.3, glow=2, studs=False)
box("GarageHandle", G, 16.4, 1.2, 5.7, 17.6, 1.4, 5.95, "steel", studs=False)
box("GarageTrim", G, gd0 - 0.5, gdy1, 6, gd1 + 0.5, gdy1 + 0.5, 6.3, "trim", studs=False)
box("GarageTrim", G, gd0 - 0.5, gdy0, 6, gd0, gdy1, 6.3, "trim", studs=False)
box("GarageTrim", G, gd1, gdy0, 6, gd1 + 0.5, gdy1, 6.3, "trim", studs=False)
for lx in (10.3, 22.9):
    box("GarageLight", G, lx, 9.6, 6.0, lx + 0.8, 10.6, 6.6, "black", studs=False)
    box("GarageLightGlow", G, lx + 0.1, 9.7, 6.5, lx + 0.7, 10.5, 6.7, "warm", material="Neon", glow=1)
# Inside: workbench, pegboard tools, shelves and a tyre stack
box("Workbench", G, 13, 0.4, -11, 19, 3.6, -9, "wood")
box("WorkbenchTop", G, 12.8, 3.6, -11, 19.2, 4.0, -8.8, "darkwood")
box("Pegboard", G, 13, 5, -11, 19, 8.5, -10.8, (200, 170, 120), studs=False)
for k, col in enumerate(("red", "steel", "yellow", "steel", "orange")):
    tx = 13.6 + k * 1.1
    box("Tool", G, tx, 5.6 + (k % 2) * 0.6, -10.8, tx + 0.35, 7.6, -10.6, col, studs=False)
box("Toolbox", G, 16.5, 4.0, -10.6, 18.5, 5.0, -9.4, "red")
for sy in (0.4, 3.4, 6.4):
    box("Shelf", G, 21, sy + 0.2 if sy > 0.4 else 0.4, -10, 23, sy + 0.5, -4, "steel")
box("ShelfPost", G, 21, 0.4, -10, 21.2, 7, -9.8, "steel", studs=False)
box("ShelfPost", G, 21, 0.4, -4.2, 21.2, 7, -4, "steel", studs=False)
box("Crate", G, 21.3, 3.9, -9.6, 22.8, 5.2, -8, (190, 150, 90))
box("Crate", G, 21.3, 0.9, -7.6, 22.8, 2.6, -5, (190, 150, 90))
box("BunBag", G, 21.4, 6.9, -6.8, 22.8, 8.2, -4.4, "bun")
for k in range(3):
    cyl_y("Tyre", G, 12.2, 3.2, 0.4 + k * 1.0, 1.4 + k * 1.0, 2.4, "black")
cyl_y("TyreHub", G, 12.2, 3.2, 3.4, 3.45, 1.0, "steel")

# ================================================================ ROOF
G = "Roof"
box("Ceiling", G, HX0, WY, HZ0, HX1, WY + 1, HZ1, "white")
box("CeilingLight", G, -10, WY - 0.3, -1, -8, WY, 1, "warm", material="Neon", glow=1, studs=False)
box("CeilingLight", G, 2.5, WY - 0.3, -5, 4.5, WY, -3, "warm", material="Neon", glow=1, studs=False)
box("GarageCeiling", G, GX0, GWY, GZ0, GX1, GWY + 0.4, GZ1, "white", studs=False)
box("CeilingLight", G, 16, GWY - 0.3, -4, 18, GWY, -2, "warm", material="Neon", glow=1, studs=False)
# Main roof: stepped gable, ridge along X, studs on every step
y = WY + 1
for k in range(6):
    z0, z1 = HZ0 - 1 + 2 * k, HZ1 + 1 - 2 * k
    box("RoofStep", G, HX0 - 1, y, z0, HX1 + 1, y + 1.2, z1, "roof" if k % 2 == 0 else "roof2")
    y += 1.2
box("RoofRidge", G, HX0 - 1.2, y, -5.6, HX1 + 1.2, y + 0.8, -4.4, "ridge")
RIDGE_TOP = y + 0.8
box("Gutter", G, HX0 - 1, WY + 0.6, HZ1 + 1, HX1 + 1, WY + 1, HZ1 + 1.3, "trim", studs=False)
# Spatula weathervane on the ridge
box("VanePole", G, 4.85, RIDGE_TOP, -5.15, 5.15, RIDGE_TOP + 3.5, -4.85, "black", studs=False)
box("VaneArm", G, 2.4, RIDGE_TOP + 2.4, -5.1, 7.6, RIDGE_TOP + 2.6, -4.9, "black", studs=False)
box("Spatula", G, 6.9, RIDGE_TOP + 2.1, -5.05, 8.6, RIDGE_TOP + 3.1, -4.95, "steel", studs=False)
box("SpatulaHandle", G, 1.8, RIDGE_TOP + 2.3, -5.08, 2.6, RIDGE_TOP + 2.7, -4.92, "red", studs=False)
# Garage roof: smaller stepped gable, ridge along Z
y = GWY + 0.4
for k in range(4):
    x0, x1 = GX0 - 0.5 + 2 * k, GX1 + 1 - 2 * k
    box("GarageRoofStep", G, x0, y, GZ0 - 1, x1, y + 1.2, GZ1 + 1, "roof" if k % 2 == 0 else "roof2")
    y += 1.2
box("GarageRidge", G, 17.2, y, GZ0 - 1.2, 18.4, y + 0.8, GZ1 + 1.2, "ridge")

# ================================================================ FOOD TRUCK
G = "FoodTruck"
TX0, TX1 = 13.5, 20.5
for wz in (11.5, 20.3):
    for wx0, wx1, hx0, hx1 in ((12.8, 14.0, 12.7, 12.9), (20.0, 21.2, 21.1, 21.3)):
        cyl_x("Wheel", G, wx0, wx1, 1.7, wz, 2.8, "black")
        cyl_x("Hubcap", G, hx0, hx1, 1.7, wz, 1.4, "steel", material="Metal")
box("Chassis", G, 13.9, 1.4, 9.4, 20.1, 2.4, 22.6, (60, 60, 64), studs=False)
box("Kitchen", G, TX0, 2.4, 9, TX1, 10, 18.5, "cream")
for sx0, sx1 in ((TX0 - 0.1, TX0), (TX1, TX1 + 0.1)):
    box("Stripe", G, sx0, 3.4, 9, sx1, 4.2, 18.5, "red", studs=False)
    box("Stripe", G, sx0, 4.2, 9, sx1, 4.6, 18.5, "cheese", studs=False)
box("Stripe", G, TX0, 3.4, 8.9, TX1, 4.2, 9, "red", studs=False)
box("RoofTrim", G, TX0 - 0.2, 10, 8.8, TX1 + 0.2, 10.4, 18.7, "red")
box("Cab", G, TX0, 2.4, 18.5, TX1, 8.2, 21.5, "red")
box("CabRoof", G, TX0 + 0.2, 8.2, 18.5, TX1 - 0.2, 8.6, 21.3, (180, 30, 30))
box("Hood", G, TX0, 2.4, 21.5, TX1, 5.4, 23.5, "red")
box("Windshield", G, 14.2, 5.7, 21.45, 19.8, 7.9, 21.6, "glass", material="Glass",
    transparency=0.35, studs=False)
for wx0, wx1 in ((TX0 - 0.1, TX0), (TX1, TX1 + 0.1)):
    box("CabWindow", G, wx0, 5.7, 19, wx1, 7.9, 21.1, "glass", material="Glass",
        transparency=0.35, studs=False)
    box("Mirror", G, wx0 - 0.6 if wx0 < 17 else wx1, 6.2, 21.2, wx0 if wx0 < 17 else wx1 + 0.6,
        7.2, 21.5, "black", studs=False)
box("Grille", G, 15.5, 2.8, 23.5, 18.5, 4.8, 23.6, (40, 40, 44), studs=False)
for gx in (15.9, 16.6, 17.3, 18.0):
    box("GrilleBar", G, gx, 2.9, 23.6, gx + 0.25, 4.7, 23.65, "steel", studs=False)
for hx in (14.6, 19.4):
    box("Headlight", G, hx - 0.6, 3.6, 23.5, hx + 0.6, 4.6, 23.7, "warm", material="Neon", glow=1, studs=False)
box("Bumper", G, TX0 - 0.1, 2.0, 23.5, TX1 + 0.1, 2.8, 24.0, "steel", material="Metal", studs=False)
box("Bumper", G, TX0 - 0.1, 2.0, 8.5, TX1 + 0.1, 2.8, 9.0, "steel", material="Metal", studs=False)
for tx in (14.0, 19.2):
    box("TailLight", G, tx, 4.8, 8.9, tx + 0.8, 6.0, 9.0, "red", material="Neon", studs=False)
box("RearDoorSeam", G, 16.95, 2.8, 8.95, 17.05, 9.4, 9.0, (190, 180, 160), studs=False)
box("Exhaust", G, 20.6, 7.5, 18.2, 21.0, 11.5, 18.6, "steel", studs=False)
# Serving hatch on the +X side with a striped awning
box("ServingHatch", G, TX1 - 0.05, 5, 11, TX1 + 0.05, 8.5, 17, (45, 35, 30), studs=False)
box("HatchCounter", G, TX1, 4.7, 10.8, TX1 + 0.9, 5.0, 17.2, "steel", material="Metal", studs=False)
for k in range(6):
    az = 10.6 + k * (6.8 / 6)
    box("Awning", G, TX1, 8.8, az, TX1 + 1.9, 9.2, az + 6.8 / 6,
        "red" if k % 2 == 0 else "white", studs=False)
box("MenuBoard", G, TX1, 5.2, 9.4, TX1 + 0.1, 8.0, 10.6, "black", studs=False)
for k in range(3):
    box("MenuLine", G, TX1 + 0.1, 5.7 + k * 0.7, 9.6, TX1 + 0.15, 5.9 + k * 0.7, 10.4, "cheese", studs=False)
# Mini burger logo on the -X side
ly = 5.2
for col, w, h in (("bun", 2.6, 0.5), ("lettuce", 3.0, 0.3), ("patty", 2.8, 0.6),
                  ("cheese", 3.0, 0.25), ("bun", 2.6, 0.8)):
    box("Logo", G, TX0 - 0.2, ly, 13.75 - w / 2, TX0, ly + h, 13.75 + w / 2, col, studs=False)
    ly += h
# Giant burger on the roof
cyl_y("SignPost", G, 17, 13.75, 10.4, 11.2, 1.0, "steel", material="Metal")
cyl_y("BunBottom", G, 17, 13.75, 11.2, 12.2, 6.0, "bun")
cyl_y("Patty", G, 17, 13.75, 12.2, 13.0, 6.4, "patty")
box("Cheese", G, 14.2, 13.0, 10.95, 19.8, 13.3, 16.55, "cheese", studs=False)
cyl_y("Lettuce", G, 17, 13.75, 13.3, 13.7, 6.6, "lettuce")
cyl_y("Tomato", G, 17, 13.75, 13.7, 14.1, 5.8, "tomato")
cyl_y("BunTop", G, 17, 13.75, 14.1, 15.5, 6.0, "bun")
cyl_y("BunDome", G, 17, 13.75, 15.5, 16.3, 4.6, "bun2")
cyl_y("BunCrown", G, 17, 13.75, 16.3, 16.7, 2.6, "bun2")
for a in range(10):  # sesame seeds around the dome
    ang = a * math.tau / 10
    r = 2.6 if a % 2 else 1.7
    sx, sz = 17 + r * math.cos(ang), 13.75 + r * math.sin(ang)
    sy = 15.5 if r > 2 else 16.3
    box("Seed", G, sx - 0.2, sy, sz - 0.3, sx + 0.2, sy + 0.2, sz + 0.3, "seed", studs=False)

# ================================================================ SCALE DUMMY (preview only)
G = "Dummy"
DXC, DZC = -3.6, 18.6
for side in (-0.5, 0.5):
    box("Leg", G, DXC + side - 0.5, 0.25, DZC - 0.5, DXC + side + 0.5, 2.25, DZC + 0.5,
        "noobGreen", studs=False, preview_only=True)
box("Torso", G, DXC - 1, 2.25, DZC - 0.5, DXC + 1, 4.25, DZC + 0.5, "noobBlue",
    studs=False, preview_only=True)
for side in (-1.5, 1.5):
    box("Arm", G, DXC + side - 0.5, 2.25, DZC - 0.5, DXC + side + 0.5, 4.25, DZC + 0.5,
        "noobYellow", studs=False, preview_only=True)
box("Head", G, DXC - 0.6, 4.25, DZC - 0.6, DXC + 0.6, 5.45, DZC + 0.6, "noobYellow",
    studs=False, preview_only=True)

# ================================================================ OUTPUT
def lua_num(v):
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def write_lua(path):
    rows = []
    for p in PARTS:
        if p["po"]:
            continue
        rows.append("\t{%s}," % ", ".join([
            f'"{p["n"]}"', f'"{p["g"]}"',
            *map(lua_num, p["p"]), *map(lua_num, p["s"]),
            *map(str, p["c"]), f'"{p["m"]}"', f'"{p["sh"]}"',
            lua_num(p["t"]), "true" if p["st"] else "false", str(p["gl"]),
        ]))
    count = len(rows)
    with open(path, "w") as f:
        f.write(LUA_HEADER.replace("{{COUNT}}", str(count)))
        f.write("\n".join(rows))
        f.write(LUA_FOOTER)
    return count


LUA_HEADER = '''-- Patty Cottage: Burger Empire starter house ({{COUNT}} parts, all studded Plastic).
-- GENERATED by tools/gen_house.py. Edit the generator, not this file.
--
-- Use it either way:
--   * Studio: paste this whole file into the Command Bar (View > Command Bar) and press Enter.
--   * Game:   drop it in ServerScriptService. It builds once on server start.
-- Move ORIGIN to place the plot anywhere (the lot is 64 x 79 studs, street on +Z).

local ORIGIN = CFrame.new(0, 0, 0)

-- {name, group, px, py, pz, sx, sy, sz, r, g, b, material, shape, transparency, studs, glow}
-- glow 1 = light fixture (Neon + PointLight), glow 2 = window (lit by the day/night system later)
local PARTS = {
'''

LUA_FOOTER = '''
}

local model = Instance.new("Model")
model.Name = "PattyCottage"

local groups = {}
local function groupModel(name)
	local g = groups[name]
	if not g then
		g = Instance.new("Model")
		g.Name = name
		g.Parent = model
		groups[name] = g
	end
	return g
end

local UPRIGHT = CFrame.Angles(0, 0, math.rad(90))

for _, d in PARTS do
	local name, group = d[1], d[2]
	local pos = Vector3.new(d[3], d[4], d[5])
	local size = Vector3.new(d[6], d[7], d[8])
	local shape, studs, glow = d[13], d[15], d[16]

	local part = Instance.new("Part")
	part.Name = name
	part.Anchored = true
	part.Color = Color3.fromRGB(d[9], d[10], d[11])
	part.Material = Enum.Material[d[12]]
	part.Transparency = d[14]
	part.CastShadow = d[14] < 0.3

	if shape == "Ball" then
		part.Shape = Enum.PartType.Ball
		part.Size = size
		part.CFrame = ORIGIN * CFrame.new(pos)
	elseif shape == "CylX" then
		part.Shape = Enum.PartType.Cylinder
		part.Size = size
		part.CFrame = ORIGIN * CFrame.new(pos)
	elseif shape == "CylY" then
		-- Roblox cylinders run along X, so stand them up.
		part.Shape = Enum.PartType.Cylinder
		part.Size = Vector3.new(size.Y, size.X, size.Z)
		part.CFrame = ORIGIN * CFrame.new(pos) * UPRIGHT
	else
		part.Size = size
		part.CFrame = ORIGIN * CFrame.new(pos)
	end

	-- Classic studs on top, inlets underneath. Studs only render on Plastic.
	part.TopSurface = if studs then Enum.SurfaceType.Studs else Enum.SurfaceType.Smooth
	part.BottomSurface = if studs then Enum.SurfaceType.Inlet else Enum.SurfaceType.Smooth
	for _, face in {"Front", "Back", "Left", "Right"} do
		part[face .. "Surface"] = Enum.SurfaceType.Smooth
	end

	if glow == 1 then
		local light = Instance.new("PointLight")
		light.Color = part.Color
		light.Range = 14
		light.Brightness = 1.2
		light.Shadows = false
		light.Parent = part
	end

	part.Parent = groupModel(group)
end

model.WorldPivot = ORIGIN
model.Parent = workspace
print(("PattyCottage built: %d parts"):format(#PARTS))
'''

if __name__ == "__main__":
    lua_path = os.path.join(ROOT, "src", "server", "BuildPattyCottage.server.lua")
    json_path = os.path.join(ROOT, "preview", "house_parts.json")
    n = write_lua(lua_path)
    with open(json_path, "w") as f:
        json.dump(PARTS, f, separators=(",", ":"))
    tpl_path = os.path.join(ROOT, "preview", "viewer.template.html")
    with open(tpl_path) as f:
        html = f.read().replace("/*PARTS*/[]", json.dumps(PARTS, separators=(",", ":")))
    with open(os.path.join(ROOT, "preview", "PattyCottage.html"), "w") as f:
        f.write(html)
    studs = 0
    for p in PARTS:
        if p["st"]:
            studs += math.floor(p["s"][0] + 1e-6) * math.floor(p["s"][2] + 1e-6)
    print(f"{n} Roblox parts, {len(PARTS)} preview parts, ~{studs} studs")
