#!/usr/bin/env bash
# Rebuilds src/main/resources/updater/packupdater-bootstrap.jar from ./src.
#
# The bootstrap is a small, self-contained program that PackUpdater extracts to a temp dir
# and runs in a separate JVM. It self-updates the PackWiz installer from a GitHub release and
# then hands control to it. Its two dependencies (commons-cli, minimal-json) are shaded in, so
# the result is a single runnable jar.
#
# Usage: ./build.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
out="$here/build"
repo_root="$(cd "$here/.." && pwd)"
target="$repo_root/src/main/resources/updater/packupdater-bootstrap.jar"

MAVEN="${MAVEN_REPO:-${HOME}/.m2/repository}"
commons_cli="${MAVEN}/commons-cli/commons-cli/1.4/commons-cli-1.4.jar"
minimal_json="${MAVEN}/com/eclipsesource/minimal-json/minimal-json/0.9.5/minimal-json-0.9.5.jar"
CENTRAL="https://repo1.maven.org/maven2"

fetch() {
  local path="$1"
  if [[ -f "$path" ]]; then
    return
  fi
  echo "Fetching $(basename "$path")"
  mkdir -p "$(dirname "$path")"
  curl -fsSL -o "$path" "${CENTRAL}/${path#"$MAVEN"/}"
}

fetch "$commons_cli"
fetch "$minimal_json"

rm -rf "$out"
mkdir -p "$out/classes" "$out/jar"

echo "Compiling bootstrap"
find "$here/src" -name '*.java' > "$out/sources.txt"
# Pinned deliberately. Minecraft 1.21.1 runs on Java 21, so this jar has to load there. Building
# it with a newer local JDK silently emits a higher class file version and the whole fork path
# dies at startup with UnsupportedClassVersionError.
javac --release 21 -nowarn -cp "$commons_cli:$minimal_json" -d "$out/classes" "@$out/sources.txt"

echo "Assembling jar"
for dep in "$commons_cli" "$minimal_json"; do
  unzip -oq "$dep" -d "$out/jar"
  # Drop dependency metadata that does not belong in the shaded jar.
  rm -rf "$out/jar/META-INF/maven"
done

# Our own classes must win over anything unpacked from a dependency.
cp -r "$out/classes/." "$out/jar/"

cat > "$out/manifest.txt" <<'EOF'
Manifest-Version: 1.0
Main-Class: link.infra.packwiz.installer.bootstrap.Main
EOF

mkdir -p "$(dirname "$target")"
# --date pins entry timestamps so repeated builds are byte-identical. Without it CI cannot tell
# a genuinely stale committed jar from a fresh one that merely records a different build time.
jar --create --file "$target" \
  --manifest="$out/manifest.txt" \
  --date "2024-01-01T00:00:00Z" \
  -C "$out/jar" .

echo "Built $target"