#!/usr/bin/env python3
"""
Pictures of the void world under the bedrock (FaultlineBosses Below.java), drawn from the same layout rules as its
generator: a first-person view from the spawn platform down the white path, and a map from above.
Usage: python3 tools/void_preview.py <out-dir> [<unpacked-pack-dir> to draw the Lost Explorer at the end]
"""
import math, os, sys
import numpy as np
from PIL import Image, ImageDraw

PATH_Y, PATH_END, ARENA_Z, ARENA_R = 40, 158, 174, 18  # keep in step with Below.java


def center(z): return int(round(7 * math.sin((z - 4) / 24.0)))


def is_path(x, z):
    if abs(x) <= 3 and abs(z) <= 3: return True
    if 3 <= z <= PATH_END and abs(x - center(z)) <= 1: return True
    dz = z - ARENA_Z
    return x * x + dz * dz <= ARENA_R * ARENA_R


def inlay(x, z):
    r = math.hypot(x, z - ARENA_Z)
    return 12.5 <= r < 13.5


def first_person(eye, yaw_deg, pitch_deg, size=(960, 540), fov=70):
    W, H = size
    img = np.zeros((H, W, 3))
    f = 0.5 * W / math.tan(math.radians(fov / 2))
    cy, sy = math.cos(math.radians(yaw_deg)), math.sin(math.radians(yaw_deg))
    cp, sp = math.cos(math.radians(pitch_deg)), math.sin(math.radians(pitch_deg))
    ys, xs = np.mgrid[0:H, 0:W]
    dx, dy, dz = (xs - W / 2) / f, -(ys - H / 2) / f, np.ones_like(xs, dtype=float)
    dy, dz = dy * cp - dz * sp, dy * sp + dz * cp            # pitch (looking down a little)
    dx, dz = dx * cy - dz * sy, dx * sy + dz * cy            # yaw
    t = (PATH_Y + 1 - eye[1]) / np.where(dy < -1e-6, dy, -1e-6)
    hx, hz = eye[0] + dx * t, eye[2] + dz * t
    dist = np.sqrt((hx - eye[0]) ** 2 + (hz - eye[2]) ** 2)
    for j in range(H):
        for i in range(W):
            if dy[j, i] >= -1e-6 or dist[j, i] > 260: continue
            bx, bz = math.floor(hx[j, i]), math.floor(hz[j, i])
            if not is_path(bx, bz): continue
            fog = max(0.0, 1 - dist[j, i] / 110) ** 1.5     # the dark swallows it with distance
            edge = min(hx[j, i] - bx, bx + 1 - hx[j, i], hz[j, i] - bz, bz + 1 - hz[j, i])
            c = (16, 16, 18) if inlay(bx, bz) else (232, 232, 238) if edge > 0.03 else (196, 196, 204)
            img[j, i] = np.array(c) * fog
    return Image.fromarray(img.astype(np.uint8))


def top_down(scale=3):
    xs, zs = range(-24, 25), range(-6, 196)
    im = Image.new("RGB", (len(xs) * scale, len(zs) * scale), (0, 0, 0))
    d = ImageDraw.Draw(im)
    for zi, z in enumerate(zs):
        for xi, x in enumerate(xs):
            if is_path(x, z):
                d.rectangle([xi * scale, zi * scale, xi * scale + scale - 1, zi * scale + scale - 1], fill=(20, 20, 24) if inlay(x, z) else (230, 230, 236))
    ex, ez = (0 - xs[0]) * scale, (ARENA_Z - zs[0]) * scale
    d.ellipse([ex - 4, ez - 4, ex + 4, ez + 4], fill=(150, 60, 60))   # where he stands
    sx, sz = (0 - xs[0]) * scale, (-3 - zs[0]) * scale
    d.ellipse([sx - 3, sz - 3, sx + 3, sz + 3], fill=(120, 200, 255))  # the way back
    return im.rotate(180, expand=True)  # spawn at the bottom, him at the top


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    fp = first_person((0.5, PATH_Y + 2.62, 0.5), 0, 8)
    cam_z = ARENA_Z - 19.0                                   # at the end of the path, him 19.5 blocks ahead
    fp2 = first_person((0.5, PATH_Y + 2.62, cam_z), 0, 4)
    if len(sys.argv) > 2:  # the Lost Explorer standing at the end, as seen from the end of the path
        sys.path.insert(0, os.path.join(os.path.dirname(__file__), "anim"))
        from render_anims import place, render, load_model, PIECES
        pack = sys.argv[2]
        models = {p: load_model(pack, f"explorer_{p}") for p in PIECES}
        r = [[0, 0, 0] for _ in range(6)]
        r[3] = [-32, -8, -4]; r[4] = [-30, 34, 8]
        parts = place({"r": r, "drop": 0}, models, [])
        f = 0.5 * 960 / math.tan(math.radians(35))
        d = ARENA_Z + 0.5 - cam_z
        px = f / d / (16 / 1.5)                              # screen pixels per model pixel (he's drawn 1.5x)
        horizon = 270 - f * math.tan(math.radians(4))
        feet = horizon + f * 1.62 / d
        fig = render(parts, 0, 4, px, (260, 300), anchor=0.92)
        a = np.asarray(fig).copy()
        bg = (a[:, :, 0] == 34) & (a[:, :, 1] == 38) & (a[:, :, 2] == 46)
        line = (a[:, :, 0] == 70) & (a[:, :, 1] == 76) & (a[:, :, 2] == 90)
        a[:, :, 3] = np.where(bg | line, 0, 255)
        fig = Image.fromarray(a)
        fp2.paste(fig, (480 - 130, int(feet - 0.92 * 300)), fig)
    sheet = Image.new("RGB", (960 + 150, 1080 + 8), (0, 0, 0))
    sheet.paste(fp, (0, 0)); sheet.paste(fp2, (0, 548))
    td = top_down(3).resize((150, int(top_down(3).height * 150 / top_down(3).width)))
    sheet.paste(td, (960, 0))
    sheet.save(os.path.join(out, "void_world.png"))


if __name__ == "__main__":
    main()
