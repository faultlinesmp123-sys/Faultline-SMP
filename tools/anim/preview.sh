#!/usr/bin/env bash
# Renders Rocco's, Werner's (VendettaAnims.java) and Swarm's (Below.java) animations to PNG strips, using the real Java pose code.
# Usage: tools/anim/preview.sh <unpacked-pack-dir> <out-dir> [anim-name ...]
# The pack dir needs the rocco_*/werner_* models + skins (tools/vendetta_models.py writes them).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
"$ROOT/tools/compile_check.sh" FaultlineBosses >/dev/null
CACHE="${CACHE:-${TMPDIR:-/tmp}/faultline-paper-api}/${PAPER:-26.2}"
CP="$CACHE/build/FaultlineBosses:$CACHE/papi:$(ls "$CACHE"/deps/lib/*.jar | tr '\n' ':')"
OUT="$(mktemp -d)"
javac -proc:none -nowarn -d "$OUT" -cp "$CP" "$ROOT/tools/anim/AnimDump.java" 2>/dev/null
java -cp "$OUT:$CP" net.faultlinesmp.bosses.AnimDump > "$OUT/anims.json" 2>/dev/null
python3 "$ROOT/tools/anim/render_anims.py" "$OUT/anims.json" "$1" "$2" "${@:3}"
