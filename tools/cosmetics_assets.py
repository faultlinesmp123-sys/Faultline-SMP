#!/usr/bin/env python3
"""
Builds every asset for FaultlineCosmetics, for BOTH editions:

  Java   -> files merged into FaultlineSMP.zip (worn models, menu icons, the coat's armor-layer texture)
  Bedrock-> bedrock/FaultlineCosmetics.mcpack  (goes in plugins/Geyser-Spigot/packs)
            bedrock/faultline_cosmetics_mappings.json (goes in plugins/Geyser-Spigot/custom_mappings)

Usage: python3 tools/cosmetics_assets.py <java-out-dir> <bedrock-out-dir> [--preview <dir>]

How cosmetics are shown (must match FaultlineCosmetics):
  BODY (coat)   Java + Bedrock: a fake chestplate item with an armor-layer texture (moves with the arms).
  NECK (chains) Java: an ItemDisplay that follows the torso.  Bedrock: fake LEGS item -> attachable on the body bone.
  BACK (book)   Java: an ItemDisplay that follows the torso.  Bedrock: fake FEET item -> attachable on the body bone.
  HAT           Java + Bedrock: fake helmet item (none yet).
Worn torso models are authored with the TORSO CENTER at model (8, 8, 8); front = +z; +x = the wearer's left.
"""
import json, os, random, sys, uuid, zipfile
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from jacob_models import Atlas, box, plate, noise_fill, render, set_anchor, C  # shared helpers
import cosmetics_new as NEW  # the seasonal + achievement cosmetics

GOLD = dict(base=(232, 184, 46), light=(255, 236, 140), dark=(150, 98, 14), hi=(255, 250, 210))
NS = "faultline"

# ------------------------------------------------------------------ the cosmetics

def chains(seed):
    """Rocco's Golden Chains: two strands of chunky links in a V down the chest, a collar ring, and a heavy pendant."""
    atlas = Atlas(128, 4); rng = random.Random(seed)
    def gold(reg, face, wu, hu):
        plate(reg, GOLD, rng, sparkle=0.08)
    def pendant(reg, face, wu, hu):
        plate(reg, GOLD, rng, sparkle=0.1)
        if face == "south":  # an engraved "V" for Vendetta
            h, w = reg.shape[:2]
            for i in range(h - 2):
                x = int(1 + i * (w / 2 - 1.5) / max(1, h - 3))
                reg[1 + i, x, :3] = GOLD["dark"]; reg[1 + i, w - 1 - x, :3] = GOLD["dark"]
    el = []
    z0, z1 = 2.55, 3.35  # just in front of the chest (and over a coat)
    # collar: across the top of the chest and over the shoulders toward the back
    el.append(box(atlas, (-3.2, 5.4, z0 - 0.3), (3.2, 6.3, z1), gold))
    el.append(box(atlas, (-3.9, 5.4, -1.5), (-3.1, 6.3, z1), gold))
    el.append(box(atlas, (3.1, 5.4, -1.5), (3.9, 6.3, z1), gold))
    # two strands of links, alternating flat/upright like a cuban chain
    for side in (-1, 1):
        for i in range(7):
            f = i / 6.0
            x = side * (3.0 - 2.6 * f); y = 5.2 - 6.4 * f
            wide = i % 2 == 0
            hx, hy = (0.75, 0.55) if wide else (0.45, 0.7)
            el.append(box(atlas, (x - hx, y - hy, z0 + (0 if wide else 0.15)), (x + hx, y + hy, z1 - (0 if wide else 0.15)), gold))
    el.append(box(atlas, (-1.3, -3.6, z0 - 0.1), (1.3, -1.0, z1 + 0.35), pendant))   # pendant
    el.append(box(atlas, (-0.4, -1.1, z0), (0.4, -0.4, z1), gold))                   # bail
    return atlas, el


def book(seed):
    """Rocco's Book: a thick leather tome with gold corners and a clasp, strapped across his back."""
    atlas = Atlas(256, 4); rng = random.Random(seed)
    LEATHER = dict(base=(98, 30, 30), light=(140, 52, 46), dark=(52, 14, 16), hi=(170, 70, 60))
    PAGES = dict(base=(232, 220, 190), light=(250, 244, 222), dark=(190, 172, 140), hi=(255, 255, 240))
    STRAP = dict(base=(60, 40, 26), light=(90, 62, 40), dark=(34, 22, 14), hi=(110, 80, 52))
    def cover(reg, face, wu, hu):
        plate(reg, LEATHER, rng, sparkle=0)
        h, w = reg.shape[:2]
        if face == "north":  # the outer cover (faces away from his back): a gold sigil
            m = w // 2; c = h // 2
            for d in range(4):
                reg[c - 3 + d, m - 1 - d:m + 1 + d, :3] = GOLD["base"]; reg[c + 4 - d, m - 1 - d:m + 1 + d, :3] = GOLD["base"]
            reg[1, :, :3] = GOLD["dark"]; reg[-2, :, :3] = GOLD["dark"]
    def pages(reg, face, wu, hu):
        noise_fill(reg, PAGES["base"], rng, 4)
        for y in range(0, reg.shape[0], 2): reg[y, :, :3] = PAGES["dark"]
    def gold(reg, face, wu, hu):
        plate(reg, GOLD, rng, sparkle=0.05)
    def strap(reg, face, wu, hu):
        plate(reg, STRAP, rng, sparkle=0)
    el = []
    # the book: spine along the bottom edge of his back; covers front/back, pages between
    el.append(box(atlas, (-3.6, -4.2, -4.9), (3.6, 4.6, -4.2), cover))   # outer cover
    el.append(box(atlas, (-3.6, -4.2, -2.9), (3.6, 4.6, -2.2), cover))   # inner cover (against his back)
    el.append(box(atlas, (-3.3, -3.9, -4.2), (3.3, 4.3, -2.9), pages))   # pages
    el.append(box(atlas, (3.3, -4.2, -4.2), (3.9, 4.6, -2.9), cover))    # spine (his left side)
    for x in (-3.8, 2.9):                                                # gold corner caps
        for y in (-4.4, 3.7):
            el.append(box(atlas, (x, y, -5.05), (x + 0.9, y + 0.9, -2.05), gold))
    el.append(box(atlas, (-4.1, -0.6, -4.95), (-3.3, 0.9, -2.15), gold))  # the clasp
    # straps: up over both shoulders to the front of the chest
    for x in (-2.6, 1.4):
        el.append(box(atlas, (x, 4.6, -3.0), (x + 1.2, 6.25, -2.0), strap))
        el.append(box(atlas, (x, 5.5, -2.0), (x + 1.2, 6.25, 2.3), strap))
        el.append(box(atlas, (x, 1.0, 2.0), (x + 1.2, 6.25, 2.3), strap))
    return atlas, el


def coat_texture(seed):
    """Rocco's Coat on the 64x32 humanoid armor layout: the brothers' long purple coat with a black fur collar and cuffs."""
    rng = random.Random(seed)
    img = np.zeros((32, 64, 4))
    P, PH, PD = (112, 40, 146), (156, 74, 188), (72, 22, 96)
    FUR, FURH, SHIRT = (24, 20, 26), (46, 40, 52), (60, 22, 80)
    GOLD_C, GOLD_D = (232, 184, 46), (150, 98, 14)
    def fill(x0, y0, x1, y1, c, n=5):
        for y in range(y0, y1):
            for x in range(x0, x1):
                img[y, x, :3] = [max(0, min(255, v + rng.uniform(-n, n))) for v in c]; img[y, x, 3] = 255
    # body: top (20,16) 8x4, bottom (28,16), right (16,20) 4x12, front (20,20) 8x12, left (28,20), back (32,20) 8x12
    fill(20, 16, 28, 20, FUR, 8); fill(28, 16, 36, 20, PD)
    fill(16, 20, 20, 32, P); fill(28, 20, 32, 32, P); fill(32, 20, 40, 32, P)
    fill(20, 20, 28, 32, P)
    fill(23, 20, 25, 32, SHIRT)                                       # open down the middle
    for y in range(20, 32): img[y, 22, :3] = PH; img[y, 25, :3] = PH  # lapel edges
    for x0, x1 in ((16, 40),):                                        # the fur collar, ragged
        for x in range(x0, x1):
            for y in range(20, 22 + rng.randint(0, 1)): img[y, x, :3] = FUR if x % 2 else FURH
    for y in (25, 28): img[y, 21, :3] = GOLD_C; img[y, 26, :3] = GOLD_C   # gold buttons
    img[29, 23, :3] = GOLD_C; img[29, 24, :3] = GOLD_D                    # belt buckle
    for y in range(20, 32): img[y, 36, :3] = PD                        # back seam
    for x in range(16, 40): img[31, x, :3] = FUR if x % 2 else FURH    # fur hem
    # arms: top (44,16) 4x4, bottom (48,16), outer (40,20) 4x12, front (44,20), inner (48,20), back (52,20)
    fill(44, 16, 48, 20, FUR, 8); fill(48, 16, 52, 20, PD)
    for x0 in (40, 44, 48, 52): fill(x0, 20, x0 + 4, 32, P)
    for x in range(40, 56):
        img[29, x, :3] = FURH; img[30, x, :3] = FUR; img[31, x, :3] = FUR if x % 2 else FURH   # fur cuffs
        img[20, x, :3] = FUR                                                                     # fur at the shoulder
    return Image.fromarray(img.astype(np.uint8), "RGBA")

# ------------------------------------------------------------------ menu icons (16x16)

def icon(kind):
    img = np.zeros((16, 16, 4))
    def px(x, y, c): img[y, x, :3] = c; img[y, x, 3] = 255
    G, GL, GD = (232, 184, 46), (255, 236, 140), (150, 98, 14)
    if kind == "rocco_chains":
        for i in range(7):  # a V of links
            for s in (-1, 1):
                x = 7.5 + s * (6 - i * 0.85); y = 2 + i * 1.5
                px(int(round(x)), int(round(y)), G if i % 2 else GL)
                px(int(round(x)), min(15, int(round(y)) + 1), GD)
        for x, y in [(6, 12), (7, 12), (8, 12), (9, 12), (6, 13), (9, 13), (7, 14), (8, 14), (7, 13), (8, 13)]: px(x, y, G)
        px(7, 13, GL)
    elif kind == "rocco_book":
        for y in range(2, 14):
            for x in range(3, 13): px(x, y, (98, 30, 30))
        for y in range(3, 13): px(12, y, (232, 220, 190))
        for x in range(3, 13): px(x, 13, (52, 14, 16))
        for x, y in [(3, 2), (11, 2), (3, 12), (11, 12)]: px(x, y, G)
        for x, y in [(7, 6), (6, 7), (8, 7), (7, 8), (7, 7)]: px(x, y, G)
        px(2, 7, GL); px(2, 8, G)
    elif kind == "rocco_coat":
        B, F, R, L = (112, 40, 146), (24, 20, 26), (60, 22, 80), (156, 74, 188)
        for y in range(2, 15):
            for x in range(4, 12): px(x, y, B)
        for y in range(4, 12): px(2, y, B); px(3, y, B); px(12, y, B); px(13, y, B)
        for y in range(3, 15): px(7, y, R); px(8, y, R)
        for y in range(3, 15): px(6, y, L); px(9, y, L)
        for x in range(3, 13): px(x, 2, F); px(x, 3, F if x % 2 else (46, 40, 52))   # fur collar
        for y in (7, 10): px(5, y, G); px(10, y, G)
        for x in range(4, 12): px(x, 14, F)                                          # fur hem
        for x in (2, 3, 12, 13): px(x, 11, F); px(x, 12, F)                          # fur cuffs
    return Image.fromarray(img.astype(np.uint8), "RGBA")

# ------------------------------------------------------------------ Bedrock geometry from Java elements

def to_bedrock_geo(identifier, elements, tex_w, tex_h, bone="body", pivot_y=24, center_y=18):
    """Java torso model (center at 8,8,8; front +z) -> Bedrock geometry on the player's body bone (front -z)."""
    cubes = []
    for e in elements:
        f, t = e["from"], e["to"]
        x0, x1 = f[0] - C, t[0] - C
        y0, y1 = f[1] - C + center_y, t[1] - C + center_y
        z0, z1 = -(t[2] - C), -(f[2] - C)  # mirror z: Bedrock's front is -z
        uv = {}
        swap = {"north": "south", "south": "north"}
        for face, fd in e["faces"].items():
            u0, v0, u1, v1 = [v * tex_w / 16 for v in fd["uv"][:2]] + [v * tex_w / 16 for v in fd["uv"][2:]]
            v0 = fd["uv"][1] * tex_h / 16; v1 = fd["uv"][3] * tex_h / 16
            uv[swap.get(face, face)] = {"uv": [round(u0, 3), round(v0, 3)], "uv_size": [round(u1 - u0, 3), round(v1 - v0, 3)]}
        cube = {"origin": [round(x0, 3), round(y0, 3), round(z0, 3)], "size": [round(x1 - x0, 3), round(y1 - y0, 3), round(z1 - z0, 3)], "uv": uv}
        if e.get("rotation"):
            r = e["rotation"]
            cube["pivot"] = [r["origin"][0] - C, r["origin"][1] - C + center_y, -(r["origin"][2] - C)]
            ang = {"x": [-r["angle"], 0, 0], "y": [0, -r["angle"], 0], "z": [0, 0, r["angle"]]}[r["axis"]]
            cube["rotation"] = ang
        cubes.append(cube)
    return {"format_version": "1.16.0", "minecraft:geometry": [{
        "description": {"identifier": identifier, "texture_width": tex_w, "texture_height": tex_h,
                        "visible_bounds_width": 3, "visible_bounds_height": 3, "visible_bounds_offset": [0, 1.5, 0]},
        "bones": [{"name": bone, "pivot": [0, pivot_y, 0], "cubes": cubes}]}]}

# ------------------------------------------------------------------ writing

def save_tex(atlas):
    return Image.fromarray(np.clip(atlas.img, 0, 255).astype(np.uint8), "RGBA")


def jwrite(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f: json.dump(obj, f, indent=1)


def to_bedrock_head_geo(identifier, elements, tex_w, tex_h):
    """Java hat model (head pixels, centre 8,8,8, front -z, +x = right) -> Bedrock geometry on the head bone
    (front -z, +x = the wearer's left: x is mirrored, so east/west faces swap)."""
    cubes = []
    for e in elements:
        f, t = e["from"], e["to"]
        x0, x1 = -(t[0] - C), -(f[0] - C)
        y0, y1 = f[1] - C + 28, t[1] - C + 28
        z0, z1 = f[2] - C, t[2] - C
        uv = {}
        swap = {"east": "west", "west": "east"}
        for face, fd in e["faces"].items():
            u0, u1 = fd["uv"][0] * tex_w / 16, fd["uv"][2] * tex_w / 16
            v0, v1 = fd["uv"][1] * tex_h / 16, fd["uv"][3] * tex_h / 16
            uv[swap.get(face, face)] = {"uv": [round(u0, 3), round(v0, 3)], "uv_size": [round(u1 - u0, 3), round(v1 - v0, 3)]}
        cube = {"origin": [round(x0, 3), round(y0, 3), round(z0, 3)], "size": [round(x1 - x0, 3), round(y1 - y0, 3), round(z1 - z0, 3)], "uv": uv}
        if e.get("rotation"):
            r = e["rotation"]
            cube["pivot"] = [-(r["origin"][0] - C), r["origin"][1] - C + 28, r["origin"][2] - C]
            cube["rotation"] = {"x": [r["angle"], 0, 0], "y": [0, -r["angle"], 0], "z": [0, 0, -r["angle"]]}[r["axis"]]
        cubes.append(cube)
    return {"format_version": "1.16.0", "minecraft:geometry": [{
        "description": {"identifier": identifier, "texture_width": tex_w, "texture_height": tex_h,
                        "visible_bounds_width": 3, "visible_bounds_height": 3.5, "visible_bounds_offset": [0, 1.75, 0]},
        "bones": [{"name": "head", "pivot": [0, 24, 0], "cubes": cubes}]}]}


def main():
    jout, bout = sys.argv[1], sys.argv[2]
    prev = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    A = os.path.join(jout, "assets", NS)
    rp = os.path.join(bout, "rp")
    seeds = iter(range(31, 99))
    hats = {k: f(next(seeds)) for k, f in NEW.HATS.items()}
    worn = {"rocco_chains": chains(21), "rocco_book": book(22)}                 # torso models: NECK / BACK
    torso_slot = {"rocco_chains": "neck", "rocco_book": "back"}
    for k, f in NEW.NECKS.items(): worn[k] = f(next(seeds)); torso_slot[k] = "neck"
    for k, f in NEW.BACKS.items(): worn[k] = f(next(seeds)); torso_slot[k] = "back"
    bodies = {"rocco_coat": coat_texture(23)}
    for k, f in NEW.BODIES.items(): bodies[k] = f(next(seeds))
    mappings = {"format_version": 2, "items": {"minecraft:paper": []}}
    item_tex = {"resource_pack_name": "faultline_cosmetics", "texture_name": "atlas.items", "texture_data": {}}

    def map_item(model, ident, name, icon_key, components=None):
        d = {"type": "definition", "model": model, "bedrock_identifier": ident, "display_name": name,
             "bedrock_options": {"icon": icon_key, "allow_offhand": False}}
        if components: d["components"] = components
        mappings["items"]["minecraft:paper"].append(d)

    names = {"rocco_chains": "Rocco's Golden Chains", "rocco_book": "Rocco's Book", "rocco_coat": "Rocco's Coat"}
    names.update(NEW.NAMES)
    def icon_img(kind):
        return icon(kind) if kind.startswith("rocco_") else NEW.draw(NEW.ICONS[kind])
    # ---- menu icons (both editions)
    for kind in names:
        ic = icon_img(kind)
        p = os.path.join(A, "textures/item/cosmetic", kind + "_icon.png"); os.makedirs(os.path.dirname(p), exist_ok=True); ic.save(p)
        jwrite(os.path.join(A, "models/item/cosmetic", kind + "_icon.json"), {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/cosmetic/{kind}_icon"}})
        jwrite(os.path.join(A, "items/cosmetic", kind + "_icon.json"), {"model": {"type": "minecraft:model", "model": f"{NS}:item/cosmetic/{kind}_icon"}})
        bp = os.path.join(rp, "textures/items/faultline", kind + "_icon.png"); os.makedirs(os.path.dirname(bp), exist_ok=True); ic.save(bp)
        item_tex["texture_data"][f"faultline.{kind}_icon"] = {"textures": f"textures/items/faultline/{kind}_icon"}
        if kind not in bodies: map_item(f"{NS}:cosmetic/{kind}_icon", f"faultline:{kind}_icon", names[kind], f"faultline.{kind}_icon")

    # ---- worn torso models: Java item models (for the ItemDisplay) + Bedrock attachables (fake legs/feet item)
    slot_for = {"neck": ("legs", "v.leg_layer_visible = 0.0;"), "back": ("feet", "v.boot_layer_visible = 0.0;")}
    for kind, (atlas, el) in worn.items():
        tex = save_tex(atlas)
        p = os.path.join(A, "textures/item/cosmetic", kind + ".png"); tex.save(p)
        jwrite(os.path.join(A, "models/item/cosmetic", kind + ".json"),
               {"textures": {"t": f"{NS}:item/cosmetic/{kind}", "particle": f"{NS}:item/cosmetic/{kind}"}, "elements": el})
        jwrite(os.path.join(A, "items/cosmetic", kind + ".json"), {"model": {"type": "minecraft:model", "model": f"{NS}:item/cosmetic/{kind}"}})
        bt = os.path.join(rp, "textures/faultline", kind + ".png"); os.makedirs(os.path.dirname(bt), exist_ok=True); tex.save(bt)
        jwrite(os.path.join(rp, "models/entity/faultline", kind + ".geo.json"), to_bedrock_geo(f"geometry.faultline.{kind}", el, atlas.size, atlas.size))
        slot, hide = slot_for[torso_slot[kind]]
        jwrite(os.path.join(rp, "attachables", f"faultline.{kind}.json"), {"format_version": "1.10.0", "minecraft:attachable": {"description": {
            "identifier": f"faultline:{kind}", "materials": {"default": "armor", "enchanted": "armor_enchanted"},
            "textures": {"default": f"textures/faultline/{kind}", "enchanted": "textures/misc/enchanted_actor_glint"},
            "geometry": {"default": f"geometry.faultline.{kind}"}, "scripts": {"parent_setup": hide},
            "render_controllers": ["controller.render.armor"]}}})
        map_item(f"{NS}:cosmetic/{kind}", f"faultline:{kind}", names[kind], f"faultline.{kind}_icon",
                 {"minecraft:equippable": {"slot": slot}, "minecraft:max_stack_size": 1})

    # ---- hats: Java item model worn on the head (display.head scale 1.6 = head pixels) + Bedrock helmet attachable
    for kind, (atlas, el) in hats.items():
        tex = save_tex(atlas)
        tex.save(os.path.join(A, "textures/item/cosmetic", kind + ".png"))
        jwrite(os.path.join(A, "models/item/cosmetic", kind + ".json"),
               {"textures": {"t": f"{NS}:item/cosmetic/{kind}", "particle": f"{NS}:item/cosmetic/{kind}"}, "elements": el,
                "display": {"head": {"scale": [1.6, 1.6, 1.6]}}})
        jwrite(os.path.join(A, "items/cosmetic", kind + ".json"), {"model": {"type": "minecraft:model", "model": f"{NS}:item/cosmetic/{kind}"}})
        tex.save(os.path.join(rp, "textures/faultline", kind + ".png"))
        jwrite(os.path.join(rp, "models/entity/faultline", kind + ".geo.json"), to_bedrock_head_geo(f"geometry.faultline.{kind}", el, atlas.size, atlas.size))
        jwrite(os.path.join(rp, "attachables", f"faultline.{kind}.json"), {"format_version": "1.10.0", "minecraft:attachable": {"description": {
            "identifier": f"faultline:{kind}", "materials": {"default": "armor", "enchanted": "armor_enchanted"},
            "textures": {"default": f"textures/faultline/{kind}", "enchanted": "textures/misc/enchanted_actor_glint"},
            "geometry": {"default": f"geometry.faultline.{kind}"}, "scripts": {"parent_setup": "v.helmet_layer_visible = 0.0;"},
            "render_controllers": ["controller.render.armor"]}}})
        map_item(f"{NS}:cosmetic/{kind}", f"faultline:{kind}", names[kind], f"faultline.{kind}_icon",
                 {"minecraft:equippable": {"slot": "head"}, "minecraft:max_stack_size": 1})

    # ---- body cosmetics: an armor-layer texture (Java equipment asset + Bedrock chestplate attachable)
    for kind, tex in bodies.items():
        p = os.path.join(A, "textures/entity/equipment/humanoid", kind + ".png"); os.makedirs(os.path.dirname(p), exist_ok=True); tex.save(p)
        jwrite(os.path.join(A, "equipment", kind + ".json"), {"layers": {"humanoid": [{"texture": f"{NS}:{kind}"}]}})
        tex.save(os.path.join(rp, "textures/faultline", kind + ".png"))
        jwrite(os.path.join(rp, "attachables", f"faultline.{kind}.json"), {"format_version": "1.21.50", "minecraft:attachable": {"description": {
            "identifier": f"faultline:{kind}", "materials": {"default": "armor", "enchanted": "armor_enchanted"},
            "textures": {"default": f"textures/faultline/{kind}", "enchanted": "textures/misc/enchanted_actor_glint"},
            "geometry": {"default": "geometry.player.armor.chestplate"}, "scripts": {"parent_setup": "v.chest_layer_visible = 0.0;"},
            "render_controllers": ["controller.render.armor"]}}})
        # the worn body item reuses its icon model (the armor layer is what shows), so it maps to its own Bedrock item
        map_item(f"{NS}:cosmetic/{kind}_icon", f"faultline:{kind}", names[kind], f"faultline.{kind}_icon",
                 {"minecraft:equippable": {"slot": "chest"}, "minecraft:max_stack_size": 1})

    # ---- seasonal currency (Candy, Presents): item textures for both editions (Index icons are copied by the caller)
    for kind, grid in NEW.TOKENS.items():
        im = NEW.draw(grid)
        p = os.path.join(A, "textures/item", kind + ".png"); os.makedirs(os.path.dirname(p), exist_ok=True); im.save(p)
        jwrite(os.path.join(A, "models/item", kind + ".json"), {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/{kind}"}})
        jwrite(os.path.join(A, "items", kind + ".json"), {"model": {"type": "minecraft:model", "model": f"{NS}:item/{kind}"}})
        ip = os.path.join(A, "textures/index", kind + ".png"); os.makedirs(os.path.dirname(ip), exist_ok=True); im.save(ip)

    # ---- the Bedrock pack
    jwrite(os.path.join(rp, "textures/item_texture.json"), item_tex)
    jwrite(os.path.join(rp, "manifest.json"), {"format_version": 2, "header": {
        "name": "Faultline SMP Cosmetics", "description": "Cosmetics for Bedrock players (Geyser)",
        "uuid": "5f0a1c2e-7b3d-4e8f-9a61-2c4d8e0b1f37", "version": [1, 1, 0], "min_engine_version": [1, 21, 0]},
        "modules": [{"description": "Cosmetics", "type": "resources", "uuid": "8c2e4a6b-1d3f-4b5a-8e7c-9f0a2b4c6d81", "version": [1, 1, 0]}]})
    os.makedirs(bout, exist_ok=True)
    with zipfile.ZipFile(os.path.join(bout, "FaultlineCosmetics.mcpack"), "w", zipfile.ZIP_DEFLATED) as z:
        for root, _, files in os.walk(rp):
            for fn in sorted(files):
                full = os.path.join(root, fn); z.write(full, os.path.relpath(full, rp))
    jwrite(os.path.join(bout, "faultline_cosmetics_mappings.json"), mappings)

    if prev:  # worn models on a stand-in torso / head, so the placement can be checked
        os.makedirs(prev, exist_ok=True)
        tatlas = Atlas(128, 2); torso = [box(tatlas, (-4, -6, -2), (4, 6, 2), lambda reg, f, w, h: noise_fill(reg, (90, 120, 160), random.Random(1), 4))]
        timg = np.asarray(save_tex(tatlas)).astype(float)
        set_anchor(0.62)
        sheets = []
        for kind, (atlas, el) in worn.items():
            img = np.asarray(save_tex(atlas)).astype(float)
            for yaw in (25, 155):
                sheets.append(render([(torso, timg, (0, 0, 0)), (el, img, (0, 0, 0))], yaw, 10, 16, (240, 300)))
        out = Image.new("RGBA", (240 * 6, 300 * ((len(sheets) + 5) // 6)), (34, 38, 46, 255))
        for i, s_ in enumerate(sheets): out.paste(s_, ((i % 6) * 240, (i // 6) * 300))
        out.save(os.path.join(prev, "cosmetics_worn.png"))
        hatlas = Atlas(64, 2)
        def skin(reg, f, w, h):
            noise_fill(reg, (196, 150, 120), random.Random(2), 4)
            if f == "north": reg[reg.shape[0] // 2, 2:5, :3] = (40, 40, 80); reg[reg.shape[0] // 2, -5:-2, :3] = (40, 40, 80)
        head = [box(hatlas, (-4, -4, -4), (4, 4, 4), skin)]
        himg = np.asarray(save_tex(hatlas)).astype(float)
        set_anchor(0.72)
        hs = []
        for kind, (atlas, el) in hats.items():
            img = np.asarray(save_tex(atlas)).astype(float)
            for yaw in (205, 335):  # the face is -z: these views show it from the front-left and front-right
                hs.append(render([(head, himg, (0, 0, 0)), (el, img, (0, 0, 0))], yaw, 15, 11, (220, 260)))
        out = Image.new("RGBA", (220 * 4, 260 * ((len(hs) + 3) // 4)), (34, 38, 46, 255))
        for i, s_ in enumerate(hs): out.paste(s_, ((i % 4) * 220, (i // 4) * 260))
        out.save(os.path.join(prev, "cosmetics_hats.png"))
        allicons = list(names) + list(NEW.TOKENS)
        icons = Image.new("RGBA", (8 * 136, 136 * ((len(allicons) + 7) // 8)), (34, 38, 46, 255))
        for i, k in enumerate(allicons):
            im = (icon_img(k) if k in names else NEW.draw(NEW.TOKENS[k])).resize((128, 128), Image.NEAREST)
            icons.paste(im, ((i % 8) * 136 + 4, (i // 8) * 136 + 4), im)
        icons.save(os.path.join(prev, "cosmetics_icons.png"))
        bsheet = Image.new("RGBA", (512 * len(bodies), 256))
        for i, (k, tex) in enumerate(bodies.items()): bsheet.paste(tex.resize((512, 256), Image.NEAREST), (i * 512, 0))
        bsheet.save(os.path.join(prev, "cosmetics_coat_texture.png"))


if __name__ == "__main__":
    main()
