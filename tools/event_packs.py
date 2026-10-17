#!/usr/bin/env python3
"""Event sky packs, sent by FaultlineItems while an event is on (EventPacks.java):
  FaultlineEclipse.zip  every moon phase = a black sun in a burning corona (the Eclipse sets the player's sky to night,
                        so the moon is what hangs overhead)
  FaultlineAurora.zip   the cloud layer turned into green / teal / violet aurora curtains
(FaultlineBloodMoon.zip, the red moon, was made by hand and is kept as it is.)
Usage: python3 tools/event_packs.py <repo root> [--preview tools/previews]"""
import io, json, math, random, sys, zipfile
from PIL import Image

PHASES = ["full_moon", "waning_gibbous", "third_quarter", "waning_crescent", "new_moon", "waxing_crescent", "first_quarter", "waxing_gibbous"]
MCMETA = {"pack": {"pack_format": 75, "min_format": 69, "max_format": 75, "description": ""}}


def eclipse_sun(n=32):
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    c = (n - 1) / 2
    rnd = random.Random(7)
    for y in range(n):
        for x in range(n):
            d = math.hypot(x - c, y - c)
            a = math.atan2(y - c, x - c)
            flare = 1.2 * max(0, math.sin(a * 6 + 0.4)) ** 6 + 0.8 * max(0, math.sin(a * 11 + 2.1)) ** 8
            if d < 7.6:
                im.putpixel((x, y), (6, 4, 6, 255))                    # the black sun
            elif d < 9.2:
                im.putpixel((x, y), (255, 236, 170, 255))              # the bright rim
            elif d < 11.5 + flare * 3:
                t = (d - 9.2) / (2.3 + flare * 3)
                r, g, b = 255, int(170 - 120 * t), int(60 - 50 * t)
                if rnd.random() < 0.15: g = max(0, g - 40)
                im.putpixel((x, y), (r, max(0, g), max(0, b), 255))
            elif d < 14 + flare * 2.5 and rnd.random() < 0.35:
                im.putpixel((x, y), (180, 40, 10, 255))
    return im


def aurora_clouds(n=256):
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    rnd = random.Random(11)
    curtains = []
    for i in range(5):
        curtains.append((i * n / 5 + rnd.uniform(-8, 8), rnd.uniform(10, 18), rnd.uniform(0, 6.28), rnd.uniform(3.5, 5.5), rnd.choice([1, 2])))
    for y in range(n):
        for x in range(n):
            best = None
            for (y0, amp, ph, width, freq) in curtains:
                cy = y0 + amp * math.sin(x / n * math.pi * 2 * freq + ph) + 3 * math.sin(x / n * math.pi * 2 * 5 + ph * 3)
                dy = ((y - cy + n / 2) % n) - n / 2                     # wraps: the cloud texture tiles
                if abs(dy) <= width and (best is None or abs(dy) < abs(best)):
                    best = dy / width
            if best is None:
                continue
            gap = (math.sin(x / 5.1 + y * 0.3) + math.sin(x / 13.7)) > 1.55  # a few breaks in the curtain
            if gap:
                continue
            t = (best + 1) / 2                                           # 0 = top edge, 1 = bottom edge
            if t < 0.35: col = (150, 90, 255)                            # violet fringe
            elif t < 0.75: col = (70, 255, 150)                          # green core
            else: col = (60, 220, 230)                                   # teal hem
            im.putpixel((x, y), col + (220,))
    return im


def write(path, files, desc):
    meta = json.loads(json.dumps(MCMETA)); meta["pack"]["description"] = desc
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        info = zipfile.ZipInfo("pack.mcmeta", (2026, 1, 1, 0, 0, 0)); z.writestr(info, json.dumps(meta, indent=2))
        for name, im in files:
            b = io.BytesIO(); im.save(b, "PNG")
            z.writestr(zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0)), b.getvalue())


def main():
    root = sys.argv[1]
    prev = sys.argv[sys.argv.index("--preview") + 1] if "--preview" in sys.argv else None
    sun = eclipse_sun()
    write(f"{root}/FaultlineEclipse.zip", [(f"assets/minecraft/textures/environment/celestial/moon/{p}.png", sun) for p in PHASES],
          "Faultline SMP - Eclipse")
    clouds = aurora_clouds()
    write(f"{root}/FaultlineAurora.zip", [("assets/minecraft/textures/environment/clouds.png", clouds)], "Faultline SMP - Aurora")
    if prev:
        bg = Image.new("RGBA", (256 + 32 * 8 + 16, 256), (8, 10, 24, 255))
        bg.alpha_composite(clouds, (0, 0))
        bg.alpha_composite(sun.resize((256, 256), Image.NEAREST), (272, 0))
        bg.save(f"{prev}/event_packs.png")
    print("written FaultlineEclipse.zip, FaultlineAurora.zip")


if __name__ == "__main__":
    main()
