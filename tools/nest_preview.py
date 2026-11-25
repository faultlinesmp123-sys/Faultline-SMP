import sys, math
from PIL import Image, ImageDraw, ImageFilter

lines = open(sys.argv[1]).read().split("\n")
minX, minY, minZ, maxX, maxY, maxZ, tx, ty, tz = map(int, lines[0].split())
B = {}
for l in lines[1:]:
    if not l: continue
    x, y, z, m = l.split()
    B[(int(x), int(y), int(z))] = m
out = sys.argv[2]
AIR = {"CAVE_AIR"}
LIGHT = {"OCHRE_FROGLIGHT": (255, 190, 90), "SOUL_LANTERN": (120, 220, 255), "PEARLESCENT_FROGLIGHT": (240, 200, 230)}
COL = {"DEEPSLATE": (70, 78, 92), "COBBLED_DEEPSLATE": (60, 64, 76), "DEEPSLATE_TILES": (52, 56, 66), "TUFF": (110, 114, 104),
       "COBWEB": (225, 228, 232), "WHITE_CARPET": (210, 210, 214), "WHITE_WOOL": (230, 230, 230), "BONE_BLOCK": (220, 214, 190),
       "SKELETON_SKULL": (200, 200, 190), "DECORATED_POT": (170, 100, 70), "CHEST": (150, 100, 40), "SPAWNER": (40, 50, 70),
       "OCHRE_FROGLIGHT": (250, 190, 90), "SOUL_LANTERN": (120, 220, 255), "IRON_CHAIN": (80, 80, 90), "SNIFFER_EGG": (170, 60, 40), "STONE": (100, 100, 100), "CALCITE": (215, 215, 205), "COARSE_DIRT": (110, 80, 55), "GRAVEL": (120, 115, 112), "PEARLESCENT_FROGLIGHT": (235, 200, 225), "BROWN_MUSHROOM": (150, 110, 80), "DEAD_BUSH": (140, 100, 50), "GLOW_LICHEN": (120, 160, 130), "POINTED_DRIPSTONE": (130, 100, 85), "BEDROCK": (40, 40, 40), "OAK_PLANKS": (160, 130, 80)}

def air(x, y, z):
    m = B.get((x, y, z))
    return m in AIR or m == "GLOW_LICHEN" or m == "POINTED_DRIPSTONE" or m == "BROWN_MUSHROOM" or m == "DEAD_BUSH" or m == "COBWEB" or m == "WHITE_CARPET" or m == "SOUL_LANTERN" or m == "IRON_CHAIN" or m == "SKELETON_SKULL" or m == "DECORATED_POT"

caveY = {}
for (x, y, z), m in B.items():
    if m in AIR: caveY.setdefault((x, z), []).append(y)
lights = [(x, y, z, LIGHT[m]) for (x, y, z), m in B.items() if m in LIGHT]

# ------------------------------------------------------------------ 1. the map (top down, Terraria style)
S = 5
W, H = (maxX - minX + 1) * S, (maxZ - minZ + 1) * S
img = Image.new("RGB", (W, H), (0, 0, 0))
px = img.load()
for (x, z), ys in caveY.items():
    # light at this column: nearest lights
    lx = 0
    for (a, b, c, col) in lights:
        d = math.hypot(a - x, c - z)
        if d < 12: lx = max(lx, 1 - d / 12)
    base = (34, 28, 20)
    c = tuple(int(v + lx * 60) for v in base)
    for i in range(S):
        for j in range(S): px[(x - minX) * S + i, (z - minZ) * S + j] = c
# walls: cave columns next to non-cave columns
edge = Image.new("RGB", (W, H), (0, 0, 0)); ep = edge.load()
for (x, z) in caveY:
    for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        if (x + dx, z + dz) not in caveY:
            for i in range(S):
                for j in range(S): ep[(x + dx - minX) * S + i, (z + dz - minZ) * S + j] = (120, 150, 185)
glow = edge.filter(ImageFilter.GaussianBlur(6))
img = Image.blend(img, glow, 0.0)
from PIL import ImageChops
img = ImageChops.add(img, glow)
img = ImageChops.lighter(img, edge)
dr = ImageDraw.Draw(img)
for (x, y, z), m in B.items():
    X, Y = (x - minX) * S + S // 2, (z - minZ) * S + S // 2
    if m == "COBWEB" and (x * 7 + z * 13 + y) % 5 == 0: dr.point((X, Y), fill=(200, 200, 205))
    elif m == "SKELETON_SKULL": dr.rectangle([X - 2, Y - 2, X + 2, Y + 2], fill=(220, 215, 200))
    elif m == "CHEST": dr.rectangle([X - 3, Y - 2, X + 3, Y + 2], fill=(190, 130, 50))
    elif m == "DECORATED_POT": dr.ellipse([X - 2, Y - 2, X + 2, Y + 2], fill=(190, 110, 70))
for (x, y, z, col) in lights:
    X, Y = (x - minX) * S + S // 2, (z - minZ) * S + S // 2
    dr.ellipse([X - 3, Y - 3, X + 3, Y + 3], fill=col)
X, Y = (tx - minX) * S, (tz - minZ) * S
dr.ellipse([X - 14, Y - 9, X + 14, Y + 9], outline=(200, 60, 220), width=2)
dr.text((X - 18, Y + 12), "QUEEN", fill=(220, 120, 240))
img.save(out + "/nest_map.png")

# ------------------------------------------------------------------ 2. a 3D cutaway: everything below the cut, from above at an angle
cut = ty + 7
surf = []
for (x, y, z), m in B.items():
    if y > cut or m in AIR: continue
    if m in ("DEEPSLATE", "COBBLED_DEEPSLATE", "DEEPSLATE_TILES", "TUFF"):
        if not any(air(x + d[0], y + d[1], z + d[2]) for d in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1))): continue
    surf.append((x, y, z, m))
ca, sa = math.cos(0.7), math.sin(0.7)
K = 11
def proj(x, y, z):
    rx = x * ca - z * sa; rz = x * sa + z * ca
    return rx * K, (rz * 0.5 - y) * K * 0.9, rz + y * 0.6
pts = [(proj(x - tx, y - ty, z - tz), x, y, z, m) for (x, y, z, m) in surf]
xs = [p[0][0] for p in pts]; ys = [p[0][1] for p in pts]
ox, oy = -min(xs) + 20, -min(ys) + 20
iw, ih = int(max(xs) + ox + 20), int(max(ys) + oy + 20)
im3 = Image.new("RGB", (iw, ih), (6, 6, 10)); d3 = ImageDraw.Draw(im3, "RGBA")
for (p, x, y, z, m) in sorted(pts, key=lambda t: t[0][2]):
    c = COL.get(m, (150, 0, 150))
    lit = 0.35
    for (a, b, cc, col) in lights:
        dd = math.dist((a, b, cc), (x, y, z))
        if dd < 10: lit = max(lit, 1.0 - dd / 14)
    shade = tuple(int(v * lit) for v in c)
    X, Y = p[0] + ox, p[1] + oy
    if m == "COBWEB": d3.rectangle([X - K * 0.5, Y - K * 0.5, X + K * 0.5, Y + K * 0.5], fill=shade + (110,))
    elif m in LIGHT: d3.ellipse([X - K, Y - K, X + K, Y + K], fill=c + (255,))
    else:
        d3.rectangle([X - K * 0.55, Y - K * 0.55, X + K * 0.55, Y + K * 0.55], fill=shade + (255,))
        d3.rectangle([X - K * 0.55, Y - K * 0.55, X + K * 0.55, Y - K * 0.2], fill=tuple(min(255, int(v * 1.2)) for v in shade) + (255,))
im3 = im3.resize((int(iw * 0.8), int(ih * 0.8)), Image.LANCZOS)
im3.save(out + "/nest_cutaway.png")

# ------------------------------------------------------------------ 3. a slice through the throne cavern (side view)
S2 = 6
sl = Image.new("RGB", ((maxX - minX + 1) * S2, (maxY - minY + 1) * S2), (14, 14, 18)); d2 = ImageDraw.Draw(sl)
for x in range(minX, maxX + 1):
    for y in range(minY, maxY + 1):
        best = None
        for z in range(tz - 3, tz + 4):
            m = B.get((x, y, z), "STONE")
            if m in AIR: continue
            if m == "STONE" and z != tz: continue
            best = m
            if m != "STONE" and z == tz: break
        if best is None and B.get((x, y, tz)) in AIR: col = (30, 24, 18)
        else: col = COL.get(best, (60, 60, 64)) if best != "STONE" else (40, 40, 44)
        X, Y = (x - minX) * S2, (maxY - y) * S2
        d2.rectangle([X, Y, X + S2 - 1, Y + S2 - 1], fill=col)
sl.save(out + "/nest_slice.png")
print("ok", len(surf), "surface voxels")
