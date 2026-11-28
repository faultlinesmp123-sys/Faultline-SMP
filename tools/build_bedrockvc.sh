#!/usr/bin/env bash
# Builds release/BedrockSimpleVoicechat.jar (plugins/BedrockSimpleVoicechat). It compiles against Simple Voice Chat's
# API, which is taken from the voicechat-bukkit jar you give it (only the de/maxhenkel/voicechat/api classes, used to
# compile; they are NOT put in our jar: the server's own Simple Voice Chat provides them at runtime).
# Usage: tools/build_bedrockvc.sh path/to/voicechat-bukkit-2.6.x.jar
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SVC="${1:?give the voicechat-bukkit jar}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/api" "$WORK/jar"
(cd "$WORK/api" && unzip -q "$(cd "$(dirname "$SVC")" && pwd)/$(basename "$SVC")" 'de/maxhenkel/voicechat/api/*' && jar cf "$WORK/voicechat-api.jar" de)
for V in 26.3 1.21.11; do (cd "$ROOT/plugins" && SVC_API="$WORK/voicechat-api.jar" PAPER=$V "$ROOT/tools/compile_check.sh" BedrockSimpleVoicechat); done
CACHE="${CACHE:-${TMPDIR:-/tmp}/faultline-paper-api}/1.21.11"
cp -r "$CACHE/build/BedrockSimpleVoicechat/." "$ROOT/plugins/BedrockSimpleVoicechat/src/main/resources/." "$WORK/jar/"
mkdir -p "$ROOT/release"
(cd "$WORK/jar" && jar cf "$ROOT/release/BedrockSimpleVoicechat.jar" .)
ls -la "$ROOT/release/BedrockSimpleVoicechat.jar"
