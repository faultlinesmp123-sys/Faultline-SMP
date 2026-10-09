#!/usr/bin/env python3
"""Whole-boss previews of the wild models, posed with the same math as FaultlineBosses' Wild.Part.pose:

    world = pos + Ry(-yaw) * Rx(pitch) * Rz(roll) * Q0 * local * (k * p)        (Q0 = Ry(-90 deg): local +x -> south)

so a part placed the way the plugin places it shows here where it shows in game. Each scene below mirrors a boss's
place()/bodyTick() code. Faces are flat-shaded with their texture's average colour, painter's-sorted.

Usage: python3 tools/wild_rig_preview.py <out-dir>       (writes rig_<boss>.png, several poses side by side)
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wild_assets as W  # noqa: E402


def Ry(a): c, s = math.cos(a), math.sin(a); return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
def Rx(a): c, s = math.cos(a), math.sin(a); return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
def Rz(a): c, s = math.cos(a), math.sin(a); return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


Q0 = Ry(math.radians(-90))
I3 = np.eye(3)


def dir_of(yaw):
    r = math.radians(yaw)
    return np.array([-math.sin(r), 0.0, math.cos(r)])


def offset(base, yaw, f, u, r):
    fw = dir_of(yaw)
    right = np.array([-fw[2], 0, fw[0]])
    return np.asarray(base, float) + fw * f + np.array([0, u, 0]) + right * r


def yaw_of(v):
    return math.degrees(math.atan2(-v[0], v[2]))


def pitch_of(v):
    h = math.hypot(v[0], v[2])
    return math.degrees(-math.atan2(v[1], h))


class Built:
    def __init__(self):
        self.parts = {}
        for p in W.ALL_PARTS:
            img, boxes = W.build(p, hash(p.name) & 0xffff)
            tex = np.array(img).astype(float)
            faces = []
            for (a, b), regs in boxes:
                cols = {}
                for key, (x, y, w, h) in regs.items():
                    t = tex[y:y + h, x:x + w].reshape(-1, 4)
                    t = t[t[:, 3] > 0]
                    cols[key] = t[:, :3].mean(0) if len(t) else None
                faces.append((np.array(a), np.array(b), cols))
            self.parts[p.name] = faces


CORNERS = {"+x": [(1, 0, 0), (1, 1, 0), (1, 1, 1), (1, 0, 1)], "-x": [(0, 0, 0), (0, 0, 1), (0, 1, 1), (0, 1, 0)],
           "+y": [(0, 1, 0), (0, 1, 1), (1, 1, 1), (1, 1, 0)], "-y": [(0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)],
           "+z": [(0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1)], "-z": [(0, 0, 0), (0, 1, 0), (1, 1, 0), (1, 0, 0)]}
NORMALS = {"+x": (1, 0, 0), "-x": (-1, 0, 0), "+y": (0, 1, 0), "-y": (0, -1, 0), "+z": (0, 0, 1), "-z": (0, 0, -1)}


class Scene:
    def __init__(self, built):
        self.b = built
        self.polys = []   # (world corners (4,3), colour, normal)

    def pose(self, name, k, pos, yaw, pitch=0.0, roll=0.0, local=None):
        D = Ry(math.radians(-yaw)) @ Rx(math.radians(pitch)) @ Rz(math.radians(roll)) @ Q0 @ (local if local is not None else I3)
        pos = np.asarray(pos, float)
        for a, b, cols in self.b.parts[name]:
            for key, cs in CORNERS.items():
                c = cols.get(key)
                if c is None: continue
                pts = np.array([[a[i] + (b[i] - a[i]) * cc[i] for i in range(3)] for cc in cs]) * k
                w = (D @ pts.T).T + pos
                n = D @ np.array(NORMALS[key], float)
                self.polys.append((w, c, n))

    def render(self, az=35, el=25, size=520, title=None, centre=None, span=None):
        if not self.polys: return Image.new("RGB", (size, size), (40, 44, 54))
        allp = np.concatenate([p[0] for p in self.polys])
        cen = np.asarray(centre, float) if centre is not None else (allp.min(0) + allp.max(0)) / 2
        R = Rx(math.radians(el)) @ Ry(math.radians(az))
        proj = [((R @ (p[0] - cen).T).T, p[1], R @ p[2]) for p in self.polys]
        ext = span or max(1e-3, max(np.abs(q[0][:, :2]).max() for q in proj)) * 2.1
        S = size / ext
        light = np.array([0.4, 0.8, 0.45]); light /= np.linalg.norm(light)
        img = Image.new("RGB", (size * 2, size * 2), (40, 44, 54))
        dr = ImageDraw.Draw(img)
        proj.sort(key=lambda q: q[0][:, 2].mean())  # far (smaller z after R? camera looks down -z) first
        for pts, col, n in proj:
            if n[2] < -1e-6: continue  # facing away
            shade = 0.55 + 0.45 * max(0.0, float(np.dot(Rx(math.radians(el)) @ Ry(math.radians(az)) @ light, n)))
            c = tuple(int(min(255, v * shade)) for v in col)
            xy = [((x * S + size) , (-y * S + size)) for x, y, _ in pts]
            dr.polygon(xy, fill=c)
        img = img.resize((size, size), Image.LANCZOS)
        if title:
            ImageDraw.Draw(img).text((8, 6), title, fill=(230, 230, 230))
        return img


def sheet(images, out):
    w = sum(i.width for i in images); h = max(i.height for i in images)
    s = Image.new("RGB", (w, h), (30, 32, 40))
    x = 0
    for i in images: s.paste(i, (x, 0)); x += i.width
    s.save(out)


# ---------------------------------------------------------------------------------------------------- scenes

def wyrm(built, flap_deg, landed=False, breath=False):
    """Mirrors FrostWyrm.place()."""
    sc = Scene(built)
    k, yaw, pitch = 1.4, 0.0, 0.0
    pos = np.array([0.0, 0.0, 0.0])
    sc.pose("wyrm_body", k, pos, yaw, pitch)
    base = offset(pos, yaw, 1.75 * k, 0.35 * k, 0)
    fwd, up = (2.9, 0.1) if breath else (2.5, 1.9) if landed else (2.4, 1.3)
    head = offset(pos, yaw, (1.75 + fwd) * k, (0.35 + up) * k, 0)
    ctrl = offset(pos, yaw, (1.75 + fwd * 0.45) * k, (0.35 + up * 0.95) * k, 0)
    pts = []
    for i in range(3):
        t = (i + 0.5) / 3
        p = (1 - t) ** 2 * base + 2 * (1 - t) * t * ctrl + t * t * head
        d = 2 * (1 - t) * (ctrl - base) + 2 * t * (head - ctrl)
        sc.pose("wyrm_neck", k * (1 - 0.06 * i), p, yaw_of(d), pitch_of(d))
    hp = 25 if breath else (-30 if landed else 8)
    sc.pose("wyrm_head", k, head, yaw, hp)
    fl = flap_deg
    shL, shR = offset(pos, yaw, 0.35 * k, 0.65 * k, -0.95 * k), offset(pos, yaw, 0.35 * k, 0.65 * k, 0.95 * k)
    sc.pose("wyrm_wing_l", k * 1.2, shL, yaw, pitch, 0, Rx(math.radians(fl)))
    sc.pose("wyrm_wing_r", k * 1.2, shR, yaw, pitch, 0, Rx(math.radians(-fl)))
    p = offset(pos, yaw, -2.2 * k, 0, 0)
    for i in range(6):
        name = "wyrm_tail_tip" if i == 5 else "wyrm_tail"
        kk = k * (1.0 - 0.1 * i)
        sway = math.sin(i * 0.8) * 0.4
        nxt = p + np.array([0, -0.12 * k if landed else 0.0, -1.0 * kk])
        d = p - nxt + np.array([sway, 0, 0])
        sc.pose(name, kk, p, yaw_of(d), pitch_of(d))
        p = p + np.array([sway * 0.5, -0.12 * k if landed else 0.0, -1.05 * kk])
    return sc


def serpent(built, head, body, tail, k, gap, segs, rise, tailk):
    """A chain body (Leviathan / Sandworm): the head rears up, the body trails back in a gentle S."""
    sc = Scene(built)
    pts = [np.array([0.0, rise * k, 0.0])]
    for i in range(1, segs + 2):
        ang = math.sin(i * 0.55) * 0.5
        y = max(0.0, rise * k - i * gap * 0.55)
        prev = pts[-1]
        step = np.array([math.sin(ang) * gap, 0, -math.cos(ang) * gap])
        nxt = prev + step
        nxt[1] = y
        pts.append(nxt)
    d0 = pts[0] - pts[1]
    sc.pose(head, k, pts[0], yaw_of(d0), pitch_of(d0) - 10)
    for i in range(1, segs + 1):
        d = pts[i - 1] - pts[i]
        sc.pose(body, k * (1 - 0.03 * i), pts[i], yaw_of(d), pitch_of(d))
    d = pts[segs] - pts[segs + 1]
    sc.pose(tail, k * tailk, pts[segs + 1], yaw_of(d), pitch_of(d))
    return sc


def lich(built):
    sc = Scene(built)
    sc.pose("lich", 1.3, [0, 0, 0], 0)
    return sc


def golem(built, aL, aR, sL, sR, lL, lR, bp, drop, hy=0.0, hp=0.0):
    """Mirrors StoneGolem.place()."""
    sc = Scene(built)
    k, yaw = 1.0, 0.0
    hip = np.array([0, (2.3 - drop) * k, 0])
    p = math.radians(bp)
    sc.pose("golem_body", k, hip, yaw, bp)
    sc.pose("golem_leg", k, offset(hip, yaw, 0, 0, -0.65 * k), yaw, 0, 0, Rz(math.radians(lL)))
    sc.pose("golem_leg", k, offset(hip, yaw, 0, 0, 0.65 * k), yaw, 0, 0, Rz(math.radians(lR)))
    sh, top = 2.45 * k, 2.82 * k
    sc.pose("golem_arm", k, offset(hip, yaw, math.sin(p) * sh, math.cos(p) * sh, -2.15 * k), yaw, bp, 0, Rz(math.radians(aL)) @ Rx(math.radians(sL)))
    sc.pose("golem_arm", k, offset(hip, yaw, math.sin(p) * sh, math.cos(p) * sh, 2.15 * k), yaw, bp, 0, Rz(math.radians(aR)) @ Rx(math.radians(-sR)))
    sc.pose("golem_head", k, offset(hip, yaw, math.sin(p) * top, math.cos(p) * top, 0), yaw + hy, bp + hp)
    return sc


SCENES = {
    "leviathan": lambda b: [serpent(b, "lev_head", "lev_body", "lev_tail", 1.0, 2.2, 7, 2.5, 0.75).render(title="the Leviathan"),
                            serpent(b, "lev_head", "lev_body", "lev_tail", 1.0, 2.2, 7, 2.5, 0.75).render(az=100, el=8, title="side")],
    "sandworm": lambda b: [serpent(b, "worm_head", "worm_body", "worm_tail", 1.5, 2.4 * 1.5, 7, 4, 0.8).render(title="the Sandworm King"),
                           serpent(b, "worm_head", "worm_body", "worm_tail", 1.5, 2.4 * 1.5, 7, 4, 0.8).render(az=-80, el=5, title="maw (front)")],
    "lich": lambda b: [lich(b).render(az=-60, el=12, title="the Lich (front)"), lich(b).render(az=30, el=12, title="side"), lich(b).render(az=150, el=12, title="back")],
    "golem": lambda b: [golem(b, 3, -3, 6, 6, 0, 0, 0, 0).render(az=-50, el=12, title="idle", span=11),
                        golem(b, -20, 20, 6, 6, 24, -24, 5, 0.1).render(az=-50, el=12, title="walking", span=11),
                        golem(b, 168, 168, 16, 16, 0, 0, -11, 0.13).render(az=-50, el=12, title="pound: wind-up", span=11),
                        golem(b, -8, -8, 10, 10, 0, 0, 20, 0.45, hp=18).render(az=-50, el=12, title="pound: SLAM", span=11),
                        golem(b, 20, 175, 6, 12, 0, 0, -14, 0.05).render(az=-50, el=12, title="boulder: heave", span=11),
                        golem(b, 30, 30, 14, 14, 38, -28, 18, 0.75, hp=22).render(az=-50, el=12, title="overgrowth: kneel", span=11)],
    "frostwyrm": lambda b: [wyrm(b, 35).render(title="flight, wings up"), wyrm(b, -30).render(title="flight, wings down"),
                            wyrm(b, 0, breath=True).render(az=90, el=10, title="frost breath (side)"),
                            wyrm(b, 62, landed=True).render(az=-30, el=15, title="landed, roaring")],
}


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "tools/previews"
    os.makedirs(out, exist_ok=True)
    only = sys.argv[2:] or list(SCENES)
    built = Built()
    for name in only:
        sheet(SCENES[name](built), os.path.join(out, f"rig_{name}.png"))
        print("wrote", os.path.join(out, f"rig_{name}.png"))


if __name__ == "__main__":
    main()
