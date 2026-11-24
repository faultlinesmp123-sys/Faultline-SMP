#!/usr/bin/env python3
"""
FaultlineShips 1.7.0: each ship as ONE Java item model, for players far away (the LOD model). Up close a ship is one block
display per block (a Pirate Ship is 882 entities); past ~48 blocks the plugin hides those and shows this single ItemDisplay
instead, so far-off players don't track (or get rotation packets for) hundreds of entities.

Writes into an unpacked FaultlineSMP pack:
  assets/faultline/models/item/ship_far/<type>[_wreck].json         the base: one element per visible cell, texture VARIABLES
  assets/faultline/models/item/ship_far/<type>[_wreck]_<wood>.json  tiny children that only set the wood textures
  assets/faultline/items/ship_far/<type>[_wreck].json                item definition: custom_model_data string 0 = the wood,
                                                                      colour 0 = the sails (tintindex 0 on the wool faces)
  ship_far.json (in the pack root's assets/faultline/): the scale + centre per model, which the plugin reads? no: the plugin
  computes the same numbers from ShipType (see Ship.farTransform). FIT = units per block, CENTRE = the bounds' middle.
Model units: m = (p - centre) * f + 8, with f chosen so every axis fits Java's -16..32 limit; the plugin scales the display by
16 / f. Layouts: tools/ships/layouts.json. Usage: python3 tools/ship_far_models.py <unpacked-pack> [--preview <dir>]
"""
import json, os, sys

WOODS = ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak", "bamboo", "crimson", "warped"]
FULL = {"PLANKS", "LOG", "WOOL", "SAIL_BLACK", "SAIL_WHITE", "STAIRS", "CHEST"}
SAILS = {"WOOL", "SAIL_BLACK", "SAIL_WHITE"}
FACES = {"east": (1, 0, 0), "west": (-1, 0, 0), "up": (0, 1, 0), "down": (0, -1, 0), "south": (0, 0, 1), "north": (0, 0, -1)}
LIMIT = 23.5  # half of the 48-unit box, with a little margin


def wood_tex(w):
    if w == "bamboo": return "minecraft:block/bamboo_planks", "minecraft:block/bamboo_block", "minecraft:block/bamboo_block_top"
    if w in ("crimson", "warped"): return f"minecraft:block/{w}_planks", f"minecraft:block/{w}_stem", f"minecraft:block/{w}_stem_top"
    return f"minecraft:block/{w}_planks", f"minecraft:block/{w}_log", f"minecraft:block/{w}_log_top"


def bounds(cells):
    xs = [c[0] for c in cells]; ys = [c[1] for c in cells]; zs = [c[2] for c in cells]
    lo = (min(xs) - 0.5, min(ys), min(zs) - 0.5); hi = (max(xs) + 0.5, max(ys) + 1, max(zs) + 0.5)
    centre = tuple((a + b) / 2 for a, b in zip(lo, hi))
    half = max((b - a) / 2 for a, b in zip(lo, hi))
    return centre, LIMIT / half


def elements(cells, wreck, centre, f):
    torn = set()
    if wreck:  # Ship.scaleOf: torn sails
        for i, c in enumerate(cells):
            if c[3] in SAILS and (i % 2 == 0 or c[1] % 3 == 0): torn.add(i)
    full = {(c[0], c[1], c[2]) for i, c in enumerate(cells) if c[3] in FULL and i not in torn}

    def m(p):
        return [round((p[k] - centre[k]) * f + 8, 4) for k in range(3)]

    out = []

    def box(a, b, faces):
        lo, hi = m(a), m(b)
        out.append({"from": lo, "to": hi, "faces": faces})

    def allf(tex, tint=False):
        d = {"texture": tex}
        if tint: d["tintindex"] = 0
        return {k: dict(d) for k in FACES}

    for i, (x, y, z, need, props) in enumerate(cells):
        if i in torn: continue
        pr = dict(kv.split("=") for kv in props.split(",") if "=" in kv)
        if need in FULL:
            side, top, tint = {"LOG": ("#log", "#log_top", False), "WOOL": ("#sail", "#sail", True),
                               "SAIL_WHITE": ("#sail_white", "#sail_white", False), "SAIL_BLACK": ("#sail_black", "#sail_black", False),
                               "CHEST": ("#barrel", "#barrel_top", False)}.get(need, ("#planks", "#planks", False))
            faces = {}
            for f_, (dx, dy, dz) in FACES.items():
                if (x + dx, y + dy, z + dz) in full: continue  # hidden face
                d = {"texture": top if f_ in ("up", "down") else side}
                if tint: d["tintindex"] = 0
                faces[f_] = d
            if faces: box((x - 0.5, y, z - 0.5), (x + 0.5, y + 1, z + 0.5), faces)
        elif need == "FENCE":
            box((x - 0.125, y, z - 0.125), (x + 0.125, y + 1, z + 0.125), allf("#planks"))
            for d, (a0, a1, zr) in {"east": (0.125, 0.5, None), "west": (-0.5, -0.125, None), "south": (None, None, (0.125, 0.5)), "north": (None, None, (-0.5, -0.125))}.items():
                if pr.get(d) != "true": continue
                for h0, h1 in ((6 / 16, 9 / 16), (12 / 16, 15 / 16)):
                    if zr is None: box((x + a0, y + h0, z - 0.0625), (x + a1, y + h1, z + 0.0625), allf("#planks"))
                    else: box((x - 0.0625, y + h0, z + zr[0]), (x + 0.0625, y + h1, z + zr[1]), allf("#planks"))
        elif need == "LADDER":
            s_ = 1 if "south" in props else -1
            wall = z - s_ * 0.5
            z0, z1 = sorted((wall, wall + s_ * 0.0625))
            box((x - 0.5, y, z0), (x + 0.5, y + 1, z1), allf("#ladder"))
        elif need in ("TRAPDOOR", "HATCH"):
            box((x - 0.5, y + 13 / 16, z - 0.5), (x + 0.5, y + 1, z + 0.5), allf("#planks"))
        elif need == "PANE":
            along_x = pr.get("east") == "true" or pr.get("west") == "true"
            if along_x: box((x - 0.5, y, z - 0.0625), (x + 0.5, y + 1, z + 0.0625), allf("#glass"))
            else: box((x - 0.0625, y, z - 0.5), (x + 0.0625, y + 1, z + 0.5), allf("#glass"))
        elif need == "CANNON":
            s_ = 1 if "south" in props else -1
            z0, z1 = sorted((z + s_ * -0.4, z + s_ * 1.1))
            box((x - 0.3, y, z0), (x + 0.3, y + 0.36, z1 - 0.7), allf("#planks"))
            box((x - 0.17, y + 0.3, z0), (x + 0.17, y + 0.64, z1), allf("#iron"))
        elif need == "LANTERN":
            box((x - 0.1875, y, z - 0.1875), (x + 0.1875, y + 0.4375, z + 0.1875), allf("#lantern"))
    return out


def main():
    pack = sys.argv[1]
    prev = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    here = os.path.dirname(os.path.abspath(__file__))
    layouts = json.load(open(os.path.join(here, "ships", "layouts.json")))
    A = os.path.join(pack, "assets", "faultline")
    md = os.path.join(A, "models", "item", "ship_far"); os.makedirs(md, exist_ok=True)
    idf = os.path.join(A, "items", "ship_far"); os.makedirs(idf, exist_ok=True)
    fit = {}
    for t, lay in layouts.items():
        cells = lay["cells"]
        centre, f = bounds(cells)
        fit[t] = {"centre": centre, "f": f}
        for wreck in (False, True):
            name = t + ("_wreck" if wreck else "")
            p, l, lt = wood_tex("oak")
            base = {"textures": {"particle": p, "planks": p, "log": l, "log_top": lt, "sail": "minecraft:block/white_wool",
                                 "sail_white": "minecraft:block/white_wool", "sail_black": "minecraft:block/black_wool",
                                 "barrel": "minecraft:block/barrel_side", "barrel_top": "minecraft:block/barrel_top",
                                 "ladder": "minecraft:block/ladder", "glass": "minecraft:block/glass",
                                 "iron": "minecraft:block/iron_block", "lantern": "minecraft:block/lantern"},
                    "elements": elements(cells, wreck, centre, f)}
            with open(os.path.join(md, name + ".json"), "w") as fh: json.dump(base, fh, separators=(",", ":"))
            cases = []
            for w in WOODS:
                p, l, lt = wood_tex(w)
                child = {"parent": f"faultline:item/ship_far/{name}", "textures": {"particle": p, "planks": p, "log": l, "log_top": lt}}
                with open(os.path.join(md, f"{name}_{w}.json"), "w") as fh: json.dump(child, fh, separators=(",", ":"))
                cases.append({"when": w, "model": {"type": "minecraft:model", "model": f"faultline:item/ship_far/{name}_{w}",
                                                   "tints": [{"type": "minecraft:custom_model_data", "index": 0, "default": 16777215}]}})
            d = {"model": {"type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0, "cases": cases,
                           "fallback": {"type": "minecraft:model", "model": f"faultline:item/ship_far/{name}",
                                        "tints": [{"type": "minecraft:custom_model_data", "index": 0, "default": 16777215}]}}}
            with open(os.path.join(idf, name + ".json"), "w") as fh: json.dump(d, fh, separators=(",", ":"))
        print(t, "elements", len(elements(cells, False, centre, f)), "f", round(f, 4), "centre", centre)
    if prev:
        preview(layouts, fit, prev)


def preview(layouts, fit, out):
    """Side views of the generated models (elements drawn back to front), to check they look like the ships."""
    from PIL import Image, ImageDraw
    os.makedirs(out, exist_ok=True)
    col = {"#planks": (176, 140, 88), "#log": (92, 66, 40), "#log_top": (120, 90, 55), "#sail": (235, 235, 230), "#sail_white": (235, 235, 230),
           "#sail_black": (30, 30, 34), "#barrel": (120, 86, 50), "#barrel_top": (140, 104, 62), "#ladder": (140, 104, 62), "#glass": (170, 215, 235),
           "#iron": (80, 82, 90), "#lantern": (255, 196, 90)}
    sheet = Image.new("RGB", (len(layouts) * 260, 520), (120, 170, 220))
    for n, (t, lay) in enumerate(layouts.items()):
        for row, wreck in enumerate((False, True)):
            els = elements(lay["cells"], wreck, tuple(fit[t]["centre"]), fit[t]["f"])
            img = Image.new("RGB", (256, 256), (120, 170, 220)); dr = ImageDraw.Draw(img)
            k = 256 / 48
            for e in sorted(els, key=lambda e: -e["to"][2]):  # far side (+z) first: we look from -z
                tex = (e["faces"].get("north") or next(iter(e["faces"].values())))["texture"]
                x0, y0 = (e["from"][0] + 16) * k, 256 - (e["to"][1] + 16) * k
                x1, y1 = (e["to"][0] + 16) * k, 256 - (e["from"][1] + 16) * k
                c = col.get(tex, (200, 0, 200))
                dr.rectangle([x0, y0, max(x0 + 0.5, x1), max(y0 + 0.5, y1)], fill=c, outline=tuple(max(0, v - 40) for v in c))
            sheet.paste(img, (n * 260, row * 260))
    sheet.save(os.path.join(out, "ships_far_java.png"))


if __name__ == "__main__":
    main()
