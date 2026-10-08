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
    return ver


if __name__ == "__main__":
    for p in sys.argv[1:]:
        stamp(p)
