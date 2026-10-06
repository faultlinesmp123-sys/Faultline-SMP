#!/usr/bin/env bash
# Writes tools/ships/layouts.json from the plugin's real ship layouts (FaultlineShips ShipType.java), for ship_assets.py.
# Needs the paper-api build from tools/compile_check.sh (run that once first).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
P="${CACHE:-${TMPDIR:-/tmp}/faultline-paper-api}/1.21.11"
CP="$P/papi:$(ls "$P"/deps/lib/*.jar | tr '\n' ':')"
OUT="$(mktemp -d)"
javac -proc:none --release 21 -nowarn -d "$OUT" -cp "$CP" \
  "$ROOT/plugins/FaultlineShips/src/main/java/net/faultlinesmp/ships/ShipType.java" "$ROOT/tools/ships/Dump.java"
java -cp "$OUT:$CP" net.faultlinesmp.ships.Dump > "$ROOT/tools/ships/layouts.json"
rm -rf "$OUT"
echo "wrote tools/ships/layouts.json"
