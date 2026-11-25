#!/usr/bin/env python3
"""The Umbrella (FaultlineItems 2.0.0): the item icon and the open umbrella worn over your head (an ItemDisplay).

  python3 tools/umbrella_assets.py FaultlineSMP.zip [--preview tools/previews]

Adds to the pack (in place):
  assets/faultline/items/umbrella.json            -> models/item/umbrella (flat icon)
  assets/faultline/items/umbrella_open.json       -> models/item/umbrella_open (3D: a domed canopy + handle)
  assets/faultline/textures/item/umbrella.png, umbrella_canopy.png, textures/index/umbrella.png
It also raises the pack's max_format to 97 (Minecraft 26.3)."""
import argparse
import io
import json
import os
import zipfile

from PIL import Image

CANOPY = [(36, 70, 52), (52, 96, 70), (230, 214, 120)]   # dark green, green, the gold rim


def icon():
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = im.load()
    # a closed umbrella, diagonal: canopy folded along the shaft, a hooked handle
    for i in range(3, 13):
        x, y = i, 15 - i
        px[x, y] = (110, 80, 50, 255)
        if 4 <= i <= 11:
            w = 2 if i < 10 else 1
            for d in range(1, w + 1):
                px[min(15, x + d), y] = CANOPY[1] + (255,)
                px[x, max(0, y - d)] = CANOPY[0] + (255,)
    px[12, 3] = CANOPY[2] + (255,); px[13, 2] = (200, 200, 200, 255)
    for x, y in [(2, 14), (1, 13), (1, 12), (2, 11)]:
        px[x, y] = (90, 60, 35, 255)
    return im


def canopy_tex():
    im = Image.new("RGBA", (16, 16), CANOPY[0] + (255,))
    px = im.load()
    for x in range(16):
        for y in range(16):
            if (x // 2) % 2 == 0:
                px[x, y] = CANOPY[1] + (255,)
            if y >= 14:
                px[x, y] = CANOPY[2] + (255,)
    return im


def open_model():
    els = []
    # canopy: stacked rings (a stepped dome), 16 px = 1 block; the model is drawn above the head
    rings = [(14, 0), (11, 1), (8, 2), (5, 3), (2, 4)]  # (half width, height step)
    for hw, step in rings:
        y = 10 + step * 1.2
        els.append({"from": [8 - hw, y, 8 - hw], "to": [8 + hw, y + 1.2, 8 + hw],
                    "faces": {f: {"uv": [0, 0, 16, 16], "texture": "#c"} for f in ("north", "south", "east", "west", "up", "down")}})
    # rim drip edge
    for x0, z0, x1, z1 in [(-6, -6, 22, -5), (-6, 21, 22, 22), (-6, -6, -5, 22), (21, -6, 22, 22)]:
        els.append({"from": [x0, 9, z0], "to": [x1, 10, z1], "faces": {f: {"uv": [0, 14, 16, 16], "texture": "#c"} for f in ("north", "south", "east", "west", "up", "down")}})
    # shaft and tip
    els.append({"from": [7.5, -8, 7.5], "to": [8.5, 17, 8.5], "faces": {f: {"uv": [0, 0, 1, 16], "texture": "#h"} for f in ("north", "south", "east", "west", "up", "down")}})
    return {"textures": {"c": "faultline:item/umbrella_canopy", "h": "minecraft:block/stripped_dark_oak_log", "particle": "faultline:item/umbrella_canopy"},
            "elements": els,
            "display": {"head": {"scale": [1, 1, 1]}, "fixed": {"scale": [1, 1, 1]}}}


def png(im):
    b = io.BytesIO(); im.save(b, "PNG"); return b.getvalue()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pack")
    ap.add_argument("--preview")
    a = ap.parse_args()
    add = {
        "assets/faultline/items/umbrella.json": json.dumps({"model": {"type": "minecraft:model", "model": "faultline:item/umbrella"}}, indent=1),
        "assets/faultline/items/umbrella_open.json": json.dumps({"model": {"type": "minecraft:model", "model": "faultline:item/umbrella_open"}}, indent=1),
        "assets/faultline/models/item/umbrella.json": json.dumps({"parent": "minecraft:item/handheld", "textures": {"layer0": "faultline:item/umbrella"}}, indent=1),
        "assets/faultline/models/item/umbrella_open.json": json.dumps(open_model(), indent=1),
        "assets/faultline/textures/item/umbrella.png": png(icon()),
        "assets/faultline/textures/item/umbrella_canopy.png": png(canopy_tex()),
        "assets/faultline/textures/index/umbrella.png": png(icon()),
    }
    src = a.pack
    tmp = src + ".tmp"
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            if item.filename in add:
                continue
            data = zin.read(item.filename)
            if item.filename == "pack.mcmeta":
                m = json.loads(data)
                m["pack"]["max_format"] = max(int(m["pack"].get("max_format", 75)), 97)
                data = json.dumps(m, indent=2).encode()
            zout.writestr(item, data)
        for k, v in add.items():
            zout.writestr(zipfile.ZipInfo(k, (2026, 1, 1, 0, 0, 0)), v if isinstance(v, bytes) else v.encode())
    os.replace(tmp, src)
    if a.preview:
        os.makedirs(a.preview, exist_ok=True)
        icon().resize((128, 128), Image.NEAREST).save(os.path.join(a.preview, "umbrella_icon.png"))
    print("umbrella added to", src, os.path.getsize(src), "bytes")


if __name__ == "__main__":
    main()
