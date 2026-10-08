#!/usr/bin/env python3
"""
The Bedrock (Geyser) resource pack for what the Java pack has that Bedrock can't get on its own:

  * every custom sound (boss music, boss sound effects, the Pirate Invasion music). Geyser passes a custom sound
    through by its Java name ("faultline:jacob.music"), so sound_definitions.json uses exactly those names.
  * the Faultline Index icons. They're private-use characters drawn by a Java font; Bedrock draws the same
    characters from glyph_XX.png sheets (16x16 cells for U+XX00..U+XXFF).

  * every custom item's icon. On Bedrock a custom item was its base item (a ring was a gold nugget, a staff a stick):
    each one gets a Geyser item mapping (bedrock/faultline_items_mappings.json, by base item + item model) and its
    inventory texture (or its Index icon for 3D models).

Usage: python3 tools/bedrock_pack.py FaultlineSMP.zip bedrock [--items tools/bedrock/items.tsv] [--preview tools/previews]
Writes bedrock/FaultlineBedrock.mcpack (plugins/Geyser-Spigot/packs/) and bedrock/faultline_items_mappings.json
(plugins/Geyser-Spigot/custom_mappings/). items.tsv (base, model, max stack, name per line) lists what FaultlineItems
makes; it comes from building every /itemsmenu item in a test server. The other plugins' items are found in their source.
"""
import glob, io, json, os, re, sys, zipfile
from PIL import Image

PACK_UUID, MODULE_UUID = "6f2c1e84-3b7a-4d59-9e10-5a8c2d7f4b31", "c41d9a26-8e5f-4b03-a7d2-1f6e8b9c0a57"
CELL = 32  # pixels per glyph cell (sheet = 512x512)


def main():
    src, out = sys.argv[1], sys.argv[2]
    prev = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    z = zipfile.ZipFile(src)
    names = set(z.namelist())
    files = {}  # path in the pack -> bytes

    # ---- sounds
    defs = {}
    for sj in sorted(n for n in names if n.startswith("assets/") and n.endswith("/sounds.json")):
        ns = sj.split("/")[1]
        if ns == "minecraft":
            continue  # vanilla overrides: Bedrock has its own
        for key, entry in json.loads(z.read(sj)).items():
            sounds = []
            for s in entry.get("sounds", []):
                s = {"name": s} if isinstance(s, str) else s
                ref = s["name"]
                rns, rpath = ref.split(":", 1) if ":" in ref else ("minecraft", ref)
                java_file = f"assets/{rns}/sounds/{rpath}.ogg"
                if java_file not in names:
                    print("  missing", java_file); continue
                bed = f"sounds/{rns}/{rpath}"
                files[bed + ".ogg"] = z.read(java_file)
                sd = {"name": bed, "volume": float(s.get("volume", 1.0)), "pitch": float(s.get("pitch", 1.0)), "load_on_low_memory": True}
                if s.get("stream"):
                    sd["stream"] = True
                sounds.append(sd)
            if not sounds:
                continue
            music = key.endswith("music")
            # music plays at the player, who keeps moving: no fall-off. Effects carry like Java's (about 16 blocks).
            defs[f"{ns}:{key}"] = {"category": "record" if music else "hostile", "sounds": sounds,
                                   "min_distance": 512.0 if music else 4.0, "max_distance": 1024.0 if music else 32.0}
    files["sounds/sound_definitions.json"] = json.dumps({"format_version": "1.14.0", "sound_definitions": defs}, indent=1).encode()
    print(f"{len(defs)} sounds")

    # ---- Index icons as Bedrock glyphs (the large 16 px versions: Bedrock scales glyphs to the text anyway)
    font = json.loads(z.read("assets/faultline/font/index.json"))
    sheets = {}
    for p in font["providers"]:
        if p.get("type") != "bitmap":
            continue
        ns, path = p["file"].split(":", 1)
        tex = f"assets/{ns}/textures/{path}"
        if tex not in names:
            continue
        icon = Image.open(io.BytesIO(z.read(tex))).convert("RGBA")
        for c in "".join(p["chars"]):
            cp = ord(c)
            sheet = sheets.setdefault(cp >> 8, Image.new("RGBA", (CELL * 16, CELL * 16), (0, 0, 0, 0)))
            i = cp & 0xFF
            sheet.paste(icon.resize((CELL, CELL), Image.NEAREST), ((i % 16) * CELL, (i // 16) * CELL))
    for hi, im in sheets.items():
        b = io.BytesIO(); im.save(b, "PNG")
        files[f"font/glyph_{hi:02X}.png"] = b.getvalue()
    print(f"{len(sheets)} glyph sheets: " + ", ".join(f"glyph_{h:02X}" for h in sorted(sheets)))

    # ---- custom items
    items_tsv = sys.argv[sys.argv.index("--items") + 1] if "--items" in sys.argv else None
    mapping, item_tex = custom_items(z, names, files, items_tsv, out)
    if mapping:
        with open(os.path.join(out, "faultline_items_mappings.json"), "w") as fh:
            json.dump({"format_version": 2, "items": mapping}, fh, indent=1)
        files["textures/item_texture.json"] = json.dumps({"resource_pack_name": "faultline", "texture_name": "atlas.items",
                                                           "texture_data": item_tex}, indent=1).encode()
        print(f"{sum(len(v) for v in mapping.values())} custom items mapped for Bedrock")

    files["manifest.json"] = json.dumps({"format_version": 2, "header": {
        "name": "Faultline SMP", "description": "Boss music, sounds and Index icons for Bedrock players (Geyser)",
        "uuid": PACK_UUID, "version": [1, 0, 0], "min_engine_version": [1, 21, 0]},
        "modules": [{"description": "Faultline sounds and glyphs", "type": "resources", "uuid": MODULE_UUID, "version": [1, 0, 0]}]}, indent=1).encode()
    os.makedirs(out, exist_ok=True)
    dest = os.path.join(out, "FaultlineBedrock.mcpack")
    with zipfile.ZipFile(dest, "w") as zz:
        for path in sorted(files):
            zz.writestr(path, files[path], compress_type=zipfile.ZIP_STORED if path.endswith(".ogg") else zipfile.ZIP_DEFLATED)
    from mcpack_version import stamp; stamp(dest)
    print(f"wrote {dest}: {os.path.getsize(dest) / 1e6:.1f} MB")
    if prev:
        os.makedirs(prev, exist_ok=True)
        tiles = [sheets[h] for h in sorted(sheets)]
        sheet = Image.new("RGBA", (len(tiles) * 520, 520), (40, 40, 48, 255))
        for k, t in enumerate(tiles):
            sheet.alpha_composite(t, (k * 520 + 4, 4))
        sheet.save(os.path.join(prev, "bedrock_glyphs.png"))


ONE = re.compile(r"^(NETHERITE_|DIAMOND_|IRON_|GOLDEN_|STONE_|WOODEN_).*(SWORD|AXE|PICKAXE|SHOVEL|HOE)$|^(POTION|SPLASH_POTION|LINGERING_POTION|GOAT_HORN|FISHING_ROD|BOW|CROSSBOW|TRIDENT|MACE|SHIELD|LEATHER_.*|.*_BOOTS|.*_HELMET|.*_CHESTPLATE|.*_LEGGINGS|ELYTRA|SPYGLASS|WRITTEN_BOOK|WRITABLE_BOOK)$")


def custom_items(z, names, files, items_tsv, out):
    """(base material, model) pairs -> Geyser v2 definitions + Bedrock icons."""
    pairs = {}  # (BASE, model) -> (max stack, name)
    if items_tsv and os.path.exists(items_tsv):
        for line in open(items_tsv):
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 4: pairs[(parts[0], parts[1])] = (int(parts[2]), parts[3])
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "plugins")
    for f in glob.glob(root + "/*/src/main/java/**/*.java", recursive=True):
        lines = open(f).read().split("\n")
        for i, l in enumerate(lines):
            for m in re.finditer(r'setItemModel\(new (?:org\.bukkit\.)?NamespacedKey\("faultline", "([a-z0-9_]+)"\)\)', l):
                for j in range(i, max(-1, i - 40), -1):
                    mm = re.findall(r"new ItemStack\(Material\.([A-Z_]+)", lines[j])
                    if mm: pairs.setdefault((mm[-1], m.group(1)), (1 if ONE.match(mm[-1]) else 64, "")); break
            for m in re.finditer(r'model\(Material\.([A-Z_]+), "([a-z0-9_]+)"', l):
                pairs.setdefault((m.group(1), m.group(2)), (1 if ONE.match(m.group(1)) else 64, ""))
    # already mapped by the ships / cosmetics packs
    taken = set()
    for mf in glob.glob(os.path.join(out, "*_mappings.json")):
        if mf.endswith("faultline_items_mappings.json"): continue
        for base, defs in json.load(open(mf)).get("items", {}).items():
            for d in defs: taken.add((base.split(":")[-1].upper(), str(d.get("model", "")).split(":")[-1]))
    mapping, item_tex = {}, {}
    for (base, model), (stack, name) in sorted(pairs.items()):
        if (base, model) in taken: continue
        png = icon_png(z, names, model)
        if png is None:
            print("  no icon for", model); continue
        key = "faultline." + model
        if key not in item_tex:
            files[f"textures/items/faultline/{model}.png"] = png
            item_tex[key] = {"textures": f"textures/items/faultline/{model}"}
        ident = f"faultline:{model}" + ("" if not any(m == model and b != base for b, m in pairs) else "_" + base.lower())
        d = {"type": "definition", "model": f"faultline:{model}", "bedrock_identifier": ident,
             "bedrock_options": {"icon": key, "allow_offhand": True}, "components": {"minecraft:max_stack_size": stack}}
        if name: d["display_name"] = name
        mapping.setdefault("minecraft:" + base.lower(), []).append(d)
    return mapping, item_tex


def icon_png(z, names, model):
    """The item's inventory texture (a flat item's layer0), else its Index icon (3D models)."""
    def tex_path(ref):
        ns, path = ref.split(":", 1) if ":" in ref else ("minecraft", ref)
        return f"assets/{ns}/textures/{path}.png"
    def model_json(ref):
        ns, path = ref.split(":", 1) if ":" in ref else ("minecraft", ref)
        p = f"assets/{ns}/models/{path}.json"
        return json.loads(z.read(p)) if p in names else None
    item = f"assets/faultline/items/{model}.json"
    if item in names:
        refs = re.findall(r'"model":\s*"([^"]+)"', z.read(item).decode())
        for r in refs:
            mj = model_json(r)
            if not mj: continue
            if "elements" not in mj and "layer0" in mj.get("textures", {}):
                t = tex_path(mj["textures"]["layer0"])
                if t in names: return square(z.read(t))
            break
    idx = f"assets/faultline/textures/index/{model}.png"
    if idx in names: return square(z.read(idx))
    return None


def square(data):
    """Bedrock icons: square, power-of-two (a 16x32 animated strip uses its first frame)."""
    im = Image.open(io.BytesIO(data)).convert("RGBA")
    w, h = im.size
    if h > w: im = im.crop((0, 0, w, w))
    b = io.BytesIO(); im.save(b, "PNG"); return b.getvalue()


if __name__ == "__main__":
    main()
