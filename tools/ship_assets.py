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


# ====================================================================== Bedrock
TILE = {"planks": (0, 0), "log_side": (16, 0), "log_top": (32, 0), "wool": (48, 0),
        "barrel_side": (0, 16), "barrel_top": (16, 16), "lantern": (32, 16), "dark": (48, 16)}


def atlas():
    """64x32: oak-ish planks, spruce-ish log, white wool, a barrel, a lantern."""
    rnd = random.Random(7)
    im = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
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
        "faultline.shipwright_hammer": {"textures": "textures/items/faultline/shipwright_hammer"}}}
    paper, stick = [], []
    for name, lay in layouts.items():
        for wreck in (False, True):
            key = name + ("_wreck" if wreck else "")
            cubes = ship_cubes(lay["cells"], wreck)
            geo = {"format_version": "1.16.0", "minecraft:geometry": [{
                "description": {"identifier": f"geometry.faultline.ship_{key}", "texture_width": 64, "texture_height": 32,
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
