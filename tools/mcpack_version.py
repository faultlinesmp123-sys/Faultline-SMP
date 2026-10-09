#!/usr/bin/env python3
"""Give a Bedrock .mcpack a version that changes whenever its content does.

Bedrock clients cache resource packs by UUID + version. A pack that changes but keeps its version (it was always
1.0.0) is never downloaded again by anyone who already has it: Bedrock players keep the OLD models, icons and sounds.
stamp() sets header + module version to [major, a, b] where a, b come from a hash of every other file in the pack,
so the same content always gets the same version and any change gets a new one.

Usage: python3 tools/mcpack_version.py bedrock/*.mcpack   (the asset tools call stamp() themselves)
"""
import hashlib
import json
import os
import sys
import zipfile


def stamp(path):
    with zipfile.ZipFile(path) as z:
        entries = [(i, z.read(i.filename)) for i in z.infolist()]
    h = hashlib.sha1()
    manifest = None
    for info, data in sorted(entries, key=lambda e: e[0].filename):
        if info.filename == "manifest.json":
            manifest = json.loads(data)
            continue
        h.update(info.filename.encode()); h.update(b"\0"); h.update(data)
    if manifest is None:
        raise SystemExit(f"{path}: no manifest.json")
    d = h.hexdigest()
    major = manifest["header"].get("version", [1])[0]
    ver = [major, int(d[:4], 16), int(d[4:8], 16)]
    manifest["header"]["version"] = ver
    for m in manifest.get("modules", []):
        m["version"] = ver
    with zipfile.ZipFile(path, "w") as z:
        for info, data in entries:
            if info.filename == "manifest.json":
                data = json.dumps(manifest, indent=1).encode()
            z.writestr(info, data, compress_type=info.compress_type)
    print(f"{path}: version {'.'.join(map(str, ver))}")
    record(os.path.basename(path)[:-len(".mcpack")], manifest["header"]["uuid"], ver)
    return ver


EXPECTED = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "plugins", "FaultlineItems", "src", "main", "resources", "bedrock_packs.yml")


def record(name, uuid, ver):
    """FaultlineItems' /bedrockcheck compares the server's packs with these (name without .mcpack: uuid + version;
    Bukkit's YAML would read the dot in "x.mcpack" as a path)."""
    rows = {}
    if os.path.exists(EXPECTED):
        for line in open(EXPECTED):
            if ": {" in line and not line.startswith("#"):
                k, v = line.split(": ", 1)
                rows[k.strip()] = v.strip()
    rows[name] = "{uuid: %s, version: %s}" % (uuid, ".".join(map(str, ver)))
    with open(EXPECTED, "w") as f:
        f.write("# Written by tools/mcpack_version.py: the Bedrock packs this build expects in plugins/Geyser-Spigot/packs/\n")
        for k in sorted(rows):
            f.write(f"{k}: {rows[k]}\n")


if __name__ == "__main__":
    for p in sys.argv[1:]:
        stamp(p)
