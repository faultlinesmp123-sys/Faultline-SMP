#!/usr/bin/env bash
# Builds release/FAULTLINESMPJAR.jar: every Faultline plugin in one jar (see plugins/FAULTLINESMPJAR/.../Bundle.java).
# Each plugin is compiled against Paper 1.21.11 (what the release jars use) by tools/compile_check.sh, jarred with its
# resources (Items + Events carry the Mojang-mappings manifest line), and put inside the loader jar as bundled/<Name>.jar.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${CACHE:-${TMPDIR:-/tmp}/faultline-paper-api}/1.21.11"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PLUGINS=(FaultlineItems FaultlineRaids FaultlineCosmetics FaultlineBosses FaultlineShips FaultlineKingdoms LimboBlackMarket FaultlineDeep FaultlineEvents FaultlineSkills FaultlineIndex)
(cd "$ROOT/plugins" && PAPER=1.21.11 "$ROOT/tools/compile_check.sh" "${PLUGINS[@]}" FAULTLINESMPJAR)
printf 'paperweight-mappings-namespace: mojang\n' > "$WORK/mojang.mf"
mkdir -p "$WORK/outer/bundled"
for P in "${PLUGINS[@]}"; do
  mkdir -p "$WORK/$P"
  cp -r "$CACHE/build/$P/." "$WORK/$P/"
  cp -r "$ROOT/plugins/$P/src/main/resources/." "$WORK/$P/"
  if grep -q paperweight-mappings-namespace "$ROOT/plugins/$P/pom.xml"; then
    (cd "$WORK/$P" && jar cfm "$WORK/outer/bundled/$P.jar" "$WORK/mojang.mf" .)
  else
    (cd "$WORK/$P" && jar cf "$WORK/outer/bundled/$P.jar" .)
  fi
  echo "bundled $P ($(grep -m1 '^version' "$ROOT/plugins/$P/src/main/resources/plugin.yml"))"
done
cp -r "$CACHE/build/FAULTLINESMPJAR/." "$WORK/outer/"
cp -r "$ROOT/plugins/FAULTLINESMPJAR/src/main/resources/." "$WORK/outer/"
mkdir -p "$ROOT/release"
(cd "$WORK/outer" && jar cf "$ROOT/release/FAULTLINESMPJAR.jar" .)
ls -la "$ROOT/release/FAULTLINESMPJAR.jar"
