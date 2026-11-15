#!/usr/bin/env bash
# Makes .work/localrepo/: the official malilib 0.53.0 deobf jar from the masa maven, repacked with `jar cf` so that
# ForgeGradle 2.3.4 can read it (same classes, only the zip container differs). The pom is copied as is.
# Does nothing if the repo is already there. The raw downloads stay in .work/download-malilib-0.53.0/ and are never executed.
# Needs: curl, unzip, sha1sum and a JDK 8 (JAVA_HOME, default /usr/lib/jvm/java-8-openjdk).
set -euo pipefail

ENV_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$ENV_DIR/.work"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk}"

V=0.53.0   # build.properties: malilib_version
N="malilib-liteloader-1.12.2-$V"
BASE="https://masa.dy.fi/maven/fi/dy/masa/malilib/malilib-liteloader-1.12.2/$V"
# sha1 of the files published in the masa maven (they match the .sha1 files next to them)
JAR_SHA1=3869866a277091d4d1e3a3b5873da9c17979b342
POM_SHA1=4a89a889552dc51d5955693b6ceae7fcd4124519

DL="$WORK/download-malilib-$V"
REPO="$WORK/localrepo/fi/dy/masa/malilib/malilib-liteloader-1.12.2/$V"

[ -f "$REPO/$N-deobf.jar" ] && [ -f "$REPO/$N.pom" ] && exit 0

mkdir -p "$DL" "$REPO"
curl -sS -m 300 -fL -o "$DL/$N-deobf.jar" "$BASE/$N-deobf.jar"
curl -sS -m 300 -fL -o "$DL/$N.pom" "$BASE/$N.pom"
(cd "$DL" && printf '%s  %s\n%s  %s\n' "$JAR_SHA1" "$N-deobf.jar" "$POM_SHA1" "$N.pom" | sha1sum -c -)

TMP="$(mktemp -d "$WORK/repack.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
(cd "$TMP" && unzip -q "$DL/$N-deobf.jar" && "$JAVA_HOME/bin/jar" cf "$REPO/$N-deobf.jar" .)
cp "$DL/$N.pom" "$REPO/"
