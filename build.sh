#!/usr/bin/env bash
# Builds the shareable zip: dist/CytraSeedFinder-<version>.zip
# Needs: JDK 21 (PrismLauncher's by default), lib/ filled by ./seedfinder once (Minecraft jars to compile against,
# never packaged), and the converter libraries from the Gradle cache (packaged; LGPL/Apache/BSD licensed).
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"
VERSION=$(grep -oP 'VERSION = "\K[^"]+' src/dev/steelaspect/seedfinder/ui/SeedFinderApp.java)
JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/PrismLauncher/java/java-runtime-delta}"
J="$JAVA_HOME/bin"
G="$HOME/.gradle/caches/modules-2/files-2.1"
find_jar() { ls "$G/$1/$2/$3"/*/"$2-$3.jar" 2>/dev/null | head -1; }
DEPS=(
  "$(find_jar net.fabricmc tiny-remapper 0.14.1)"
  "$(find_jar net.fabricmc mapping-io 0.8.0)"
  "$(find_jar org.ow2.asm asm 9.10.1)"
  "$(find_jar org.ow2.asm asm-commons 9.10.1)"
  "$(find_jar org.ow2.asm asm-tree 9.10.1)"
  "$(find_jar org.ow2.asm asm-util 9.10.1)"
  "$(find_jar org.ow2.asm asm-analysis 9.10.1)"
)
for d in "${DEPS[@]}"; do [ -f "$d" ] || { echo "Missing a library in the Gradle cache: $d" >&2; exit 1; }; done
[ -f lib/.ready ] || { echo "Run ./seedfinder once first (it fills lib/ with the Minecraft jars to compile against)." >&2; exit 1; }

OUT="$DIR/build/dist-classes"
rm -rf "$OUT" && mkdir -p "$OUT"
L=src/dev/steelaspect/seedfinder/launcher
# The launcher's first class runs on any Java, so it can tell people when their Java is too old.
"$J/javac" -nowarn --release 8 -d "$OUT" "$L/Launcher.java" "$L/AppDirs.java"
mapfile -t SRC < <(find src -name "*.java" ! -path "*/launcher/Launcher.java" ! -path "*/launcher/AppDirs.java")
CP="$OUT:lib/*:$(IFS=:; echo "${DEPS[*]}")"
"$J/javac" -nowarn --release 21 -d "$OUT" -cp "$CP" "${SRC[@]}"
# The graphics card code (src-ffm) twice: for Java 21 (a preview there, run with --enable-preview) and Java 22+.
GPU_TMP=$(mktemp -d)
FFM=src-ffm/dev/steelaspect/seedfinder/gpu/ffm/OpenClFfm.java
mkdir -p "$GPU_TMP/21/dev/steelaspect/seedfinder/gpu/ffm21" "$GPU_TMP/22/dev/steelaspect/seedfinder/gpu/ffm22"
sed 's/^package dev.steelaspect.seedfinder.gpu.ffm;/package dev.steelaspect.seedfinder.gpu.ffm21;/' "$FFM" > "$GPU_TMP/21/dev/steelaspect/seedfinder/gpu/ffm21/OpenClFfm.java"
sed 's/^package dev.steelaspect.seedfinder.gpu.ffm;/package dev.steelaspect.seedfinder.gpu.ffm22;/' "$FFM" > "$GPU_TMP/22/dev/steelaspect/seedfinder/gpu/ffm22/OpenClFfm.java"
"$J/javac" -nowarn -XDsuppressNotes --release 21 --enable-preview -d "$OUT" -cp "$OUT" "$GPU_TMP/21/dev/steelaspect/seedfinder/gpu/ffm21/OpenClFfm.java"
JDK22="${JAVA22_HOME:+$JAVA22_HOME/bin/javac}"
[ -n "$JDK22" ] || JDK22=$(for j in /usr/lib/jvm/*/bin/javac; do v=$("$j" -version 2>&1 | grep -oP '\d+' | head -1); [ "${v:-0}" -ge 22 ] && echo "$j" && break; done || true)
[ -n "$JDK22" ] || { echo "Need a JDK 22 or newer for the Java 22+ graphics card code (set JAVA22_HOME)." >&2; exit 1; }
"$JDK22" -nowarn --release 22 -d "$OUT" -cp "$OUT" "$GPU_TMP/22/dev/steelaspect/seedfinder/gpu/ffm22/OpenClFfm.java"
rm -rf "$GPU_TMP"
cp resources/* "$OUT/"

NAME="CytraSeedFinder-$VERSION"
STAGE="$DIR/dist/$NAME"
rm -rf "$STAGE" && mkdir -p "$STAGE/libs" "$STAGE/licenses"
LIBS=()
for d in "${DEPS[@]}"; do cp "$d" "$STAGE/libs/"; LIBS+=("libs/$(basename "$d")"); done
# Manifest lines may be at most 72 bytes; longer ones continue on lines starting with a space.
python3 - "$VERSION" "${LIBS[@]}" > build/manifest.txt <<'PY'
import sys
def wrap(line):
    out, first = [], True
    while line:
        n = 72 if first else 71
        out.append(("" if first else " ") + line[:n]); line = line[n:]; first = False
    return "\n".join(out)
print("Main-Class: dev.steelaspect.seedfinder.launcher.Launcher")
print(wrap("Class-Path: " + " ".join(sys.argv[2:])))
print("Implementation-Title: Cytra Seed Finder")
print("Implementation-Version: " + sys.argv[1])
PY
"$J/jar" --create --file "$STAGE/CytraSeedFinder.jar" --manifest build/manifest.txt -C "$OUT" .
cp packaging/* "$STAGE/"
sed -i 's/$/\r/' "$STAGE/Cytra Seed Finder.bat" "$STAGE/README.txt"
"$J/java" -cp "$OUT" dev.steelaspect.seedfinder.ui.AppIcon "$STAGE/icon.png"
cp packaging-licenses/* "$STAGE/licenses/" 2>/dev/null || true
chmod +x "$STAGE"/*.sh
rm -f "dist/$NAME.zip"
# Zip with Python so the .sh files keep their "executable" mark for Linux and Mac users.
python3 - "dist/$NAME" "dist/$NAME.zip" <<'PY'
import os, sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
base = os.path.dirname(src)
with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(src):
        for f in sorted(files):
            path = os.path.join(root, f)
            info = zipfile.ZipInfo(os.path.relpath(path, base))
            info.external_attr = (0o755 if f.endswith(".sh") else 0o644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(path, "rb") as fh:
                z.writestr(info, fh.read())
PY
echo "Built dist/$NAME.zip ($(du -h "dist/$NAME.zip" | cut -f1))"
