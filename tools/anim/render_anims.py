#!/usr/bin/env python3
"""
Renders the poses dumped by AnimDump.java (Rocco's and Werner's animations) with the same rig math as
FaultlineBosses.renderRig, so a pose can be checked without starting a server.

Usage: python3 tools/anim/render_anims.py <anims.json> <unpacked-pack-dir> <out-dir> [anim-name ...]
Each animation becomes <out-dir>/<name>.png: a front-3/4 row and a side row of frames across the move.
"""
import json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from jacob_models import CORNERS, SHADE, C  # noqa: E402

PIECES = ["leg_r", "leg_l", "body", "arm_r", "arm_l", "head"]
JOINT = [(-2, 12, 0), (2, 12, 0), (0, 12, 0), (-5.5, 24, 0), (5.5, 24, 0), (0, 24, 0)]
LEG_R, LEG_L, BODY, ARM_R, ARM_L, HEAD = range(6)


def rx(a): c, s = math.cos(a), math.sin(a); return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
def ry(a): c, s = math.cos(a), math.sin(a); return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
def rz(a): c, s = math.cos(a), math.sin(a); return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def euler(r):  # JOML: new Quaternionf().rotateY(y).rotateX(x).rotateZ(z)
    return ry(math.radians(r[1])) @ rx(math.radians(r[0])) @ rz(math.radians(r[2]))


def load_model(pack, name):
    A = os.path.join(pack, "assets", "faultline")
    m = json.load(open(os.path.join(A, "models", "item", name + ".json")))
    tex_id = list(m["textures"].values())[0].split(":")[1]
    tex = np.asarray(Image.open(os.path.join(A, "textures", tex_id + ".png")).convert("RGBA")).astype(float)
    return m["elements"], tex


def place(frame, models, worn):
    """Every part's (elements, texture, matrix, offset) for one pose, in model pixels (feet at y=0, facing +z)."""
    r, drop = frame["r"], frame["drop"] * 16
    qb = euler(r[BODY])
    hips = np.array([0, 12, 0])
    out = []
    for i, piece in enumerate(PIECES):
        j = np.array(JOINT[i], dtype=float)
        if i in (ARM_R, ARM_L, HEAD):
            j = qb @ (j - hips) + hips
            m = qb @ euler(r[i])
        elif i == BODY:
            m = qb
        else:
            m = euler(r[i])
        out.append((models[piece][0], models[piece][1], m, j + [0, drop, 0]))
    for els, tex in worn:  # cosmetics: centered on the torso, which hangs from the hips
        c = qb @ np.array([0, 6, 0]) + hips
        out.append((els, tex, qb, c + [0, drop, 0]))
    return out


def render(parts, yaw, pitch, scale, size, anchor=0.86):
    W, H = size
    img = np.zeros((H, W, 4)); img[:, :, :3] = (34, 38, 46); img[:, :, 3] = 255
    zb = np.full((H, W), -1e9)
    cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
    cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))

    def proj(p):
        x, y, z = p
        x, z = x * cy - z * sy, x * sy + z * cy
        y, z = y * cp - z * sp, y * sp + z * cp
        return np.array([W / 2 + x * scale, H * anchor - y * scale, z])

    for els, tex, m, off in parts:
        th, tw = tex.shape[:2]
        for e in els:
            f = [e["from"][k] - C for k in range(3)]; t = [e["to"][k] - C for k in range(3)]
            for face, fd in e["faces"].items():
                tl, tr, bl = [proj(m @ np.array(p) + off) for p in CORNERS[face](f, t)]
                u1, v1, u2, v2 = [v * tw / 16 for v in fd["uv"]]
                ex, ey = tr - tl, bl - tl
                det = ex[0] * ey[1] - ex[1] * ey[0]
                if abs(det) < 1e-6: continue
                xs = [tl[0], tr[0], bl[0], tr[0] + ey[0]]; ys = [tl[1], tr[1], bl[1], tr[1] + ey[1]]
                x0, x1 = max(0, int(min(xs))), min(W - 1, int(max(xs)) + 1)
                y0, y1 = max(0, int(min(ys))), min(H - 1, int(max(ys)) + 1)
                if x0 > x1 or y0 > y1: continue
                gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
                dx, dy = gx - tl[0], gy - tl[1]
                s = (dx * ey[1] - dy * ey[0]) / det
                tt = (ex[0] * dy - ex[1] * dx) / det
                mask = (s >= 0) & (s <= 1) & (tt >= 0) & (tt <= 1)
                if not mask.any(): continue
                depth = tl[2] + s * ex[2] + tt * ey[2]
                uu = np.clip((u1 + s * (u2 - u1)).astype(int), 0, tw - 1)
                vv = np.clip((v1 + tt * (v2 - v1)).astype(int), 0, th - 1)
                px = tex[vv, uu]
                mask &= px[:, :, 3] > 127
                sub = zb[y0:y1 + 1, x0:x1 + 1]
                mask &= depth > sub
                sub[mask] = depth[mask]
                o = img[y0:y1 + 1, x0:x1 + 1]
                o[mask, :3] = px[mask, :3] * SHADE[face]
    out = Image.fromarray(img.astype(np.uint8), "RGBA")
    d = ImageDraw.Draw(out)
    gy = int(H * anchor)
    d.line([(0, gy), (W, gy)], fill=(70, 76, 90))  # the ground
    return out


def main():
    anims = json.load(open(sys.argv[1]))
    pack, outdir = sys.argv[2], sys.argv[3]
    only = set(sys.argv[4:])
    os.makedirs(outdir, exist_ok=True)
    cache = {}
    for name, a in anims.items():
        if only and name not in only: continue
        skin = a["skin"]
        if skin not in cache:
            models = {p: load_model(pack, f"{skin}_{p}") for p in PIECES}
            worn = [load_model(pack, "cosmetic/rocco_chains")] + ([load_model(pack, "cosmetic/rocco_book")] if skin == "rocco" else [])
            cache[skin] = (models, worn)
        models, worn = cache[skin]
        frames = a["frames"]
        n = min(10, len(frames))
        picks = sorted(set(round(i * (len(frames) - 1) / max(1, n - 1)) for i in range(n)))
        fw, fh = 150, 210
        sheet = Image.new("RGBA", (fw * len(picks), fh * 2 + 14), (24, 26, 32, 255))
        d = ImageDraw.Draw(sheet)
        for col, k in enumerate(picks):
            parts = place(frames[k], models, worn)
            sheet.paste(render(parts, 20, 8, 4.0, (fw, fh)), (col * fw, 0))
            sheet.paste(render(parts, 90, 4, 4.0, (fw, fh)), (col * fw, fh))
            d.text((col * fw + 4, fh * 2 + 1), f"t={k}", fill=(200, 200, 210))
        sheet.save(os.path.join(outdir, name + ".png"))
        print("wrote", name)


if __name__ == "__main__":
    main()
