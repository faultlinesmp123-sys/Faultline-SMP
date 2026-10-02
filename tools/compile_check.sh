#!/usr/bin/env bash
# Compiles every plugin in plugins/ against the real Paper API (default 26.2, what the server runs; PAPER=1.21.11
# for the version the poms target), for environments that can't reach
# repo.papermc.io (Claude Code cloud sessions). It builds paper-api from PaperMC's GitHub source and pulls its
# dependencies from Maven Central, caching everything in $CACHE. This only checks that the code compiles;
# build the real jars with `mvn package` on the server.
#
# Usage: tools/compile_check.sh [PluginName ...]     (default: all plugins)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PAPER="${PAPER:-26.2}"
case "$PAPER" in
  26.2)    GUAVA=33.6.0-jre GSON=2.14.0 FASTUTIL=8.5.18 LOG4J=2.26.0 SLF4J=2.0.17 ADVENTURE=5.2.0 ;;
  1.21.11) GUAVA=$GUAVA GSON=2.11.0 FASTUTIL=$FASTUTIL LOG4J=2.24.1 SLF4J=2.0.16 ADVENTURE=4.26.1 ;;
  *) echo "PAPER must be 26.2 or 1.21.11"; exit 2 ;;
esac
CACHE="${CACHE:-${TMPDIR:-/tmp}/faultline-paper-api}/$PAPER"
mkdir -p "$CACHE"

if [ ! -f "$CACHE/papi.ok" ]; then
  echo "Building paper-api $PAPER into $CACHE (one time)..."
  rm -rf "$CACHE/paper" "$CACHE/brig" "$CACHE/papi" "$CACHE/deps"
  git clone -q --depth 1 --filter=blob:none --sparse -b "ver/$PAPER" https://github.com/PaperMC/Paper.git "$CACHE/paper"
  (cd "$CACHE/paper" && git sparse-checkout set paper-api)
  mkdir -p "$CACHE/deps"
  cat > "$CACHE/deps/pom.xml" <<EOF
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>x</groupId><artifactId>deps</artifactId><version>1</version>
<dependencyManagement><dependencies><dependency><groupId>net.kyori</groupId><artifactId>adventure-bom</artifactId><version>$ADVENTURE</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>
<dependencies>
<dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>$GUAVA</version></dependency>
<dependency><groupId>com.google.code.gson</groupId><artifactId>gson</artifactId><version>$GSON</version></dependency>
<dependency><groupId>org.yaml</groupId><artifactId>snakeyaml</artifactId><version>2.2</version></dependency>
<dependency><groupId>org.joml</groupId><artifactId>joml</artifactId><version>1.10.8</version></dependency>
<dependency><groupId>it.unimi.dsi</groupId><artifactId>fastutil</artifactId><version>$FASTUTIL</version></dependency>
<dependency><groupId>org.apache.logging.log4j</groupId><artifactId>log4j-api</artifactId><version>$LOG4J</version></dependency>
<dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>$SLF4J</version></dependency>
<dependency><groupId>net.md-5</groupId><artifactId>bungeecord-chat</artifactId><version>1.21-R0.2</version></dependency>
<dependency><groupId>net.kyori</groupId><artifactId>adventure-api</artifactId></dependency>
<dependency><groupId>net.kyori</groupId><artifactId>adventure-text-minimessage</artifactId></dependency>
<dependency><groupId>net.kyori</groupId><artifactId>adventure-text-serializer-gson</artifactId></dependency>
<dependency><groupId>net.kyori</groupId><artifactId>adventure-text-serializer-legacy</artifactId></dependency>
<dependency><groupId>net.kyori</groupId><artifactId>adventure-text-serializer-plain</artifactId></dependency>
<dependency><groupId>net.kyori</groupId><artifactId>adventure-text-logger-slf4j</artifactId></dependency>
<dependency><groupId>org.apache.maven</groupId><artifactId>maven-resolver-provider</artifactId><version>3.9.6</version></dependency>
<dependency><groupId>org.apache.maven.resolver</groupId><artifactId>maven-resolver-connector-basic</artifactId><version>1.9.18</version></dependency>
<dependency><groupId>org.apache.maven.resolver</groupId><artifactId>maven-resolver-transport-http</artifactId><version>1.9.18</version></dependency>
<dependency><groupId>org.jetbrains</groupId><artifactId>annotations</artifactId><version>26.0.2</version></dependency>
<dependency><groupId>org.checkerframework</groupId><artifactId>checker-qual</artifactId><version>3.49.2</version></dependency>
<dependency><groupId>org.jspecify</groupId><artifactId>jspecify</artifactId><version>1.0.0</version></dependency>
</dependencies></project>
EOF
  (cd "$CACHE/deps" && mvn -q -B dependency:copy-dependencies -DoutputDirectory=lib)
  # Brigadier isn't on Maven Central: build it from Mojang's source
  git clone -q --depth 1 https://github.com/Mojang/brigadier.git "$CACHE/brig"
  mkdir -p "$CACHE/brig/out"
  javac -nowarn -d "$CACHE/brig/out" $(find "$CACHE/brig/src/main/java" -name '*.java')
  (cd "$CACHE/brig/out" && jar cf "$CACHE/deps/lib/brigadier.jar" .)
  find "$CACHE/paper/paper-api/src/main/java" "$CACHE/paper/paper-api/src/generated/java" -name '*.java' > "$CACHE/papi-src.txt"
  mkdir -p "$CACHE/papi"
  javac -J-Xmx3g -proc:none --release 21 -nowarn -encoding UTF-8 -d "$CACHE/papi" \
    -cp "$(ls "$CACHE"/deps/lib/*.jar | tr '\n' ':')" @"$CACHE/papi-src.txt"
  touch "$CACHE/papi.ok"
fi

CP="$CACHE/papi:$(ls "$CACHE"/deps/lib/*.jar | tr '\n' ':')"
cd "$ROOT/plugins"
PLUGINS=("$@"); [ ${#PLUGINS[@]} -eq 0 ] && PLUGINS=(*/)
status=0
for p in "${PLUGINS[@]}"; do
  p="${p%/}"
  out="$CACHE/build/$p"; rm -rf "$out"; mkdir -p "$out"
  if javac -proc:none --release 21 -nowarn -encoding UTF-8 -d "$out" -cp "$CP" $(find "$p/src/main/java" -name '*.java') 2> "$out.log"; then
    echo "OK    $p"
  else
    echo "FAIL  $p"; grep -E "error" "$out.log" | head -20; status=1
  fi
done
exit $status
