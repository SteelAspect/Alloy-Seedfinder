#!/usr/bin/env bash
# Cytra Seed Finder for Linux and Mac: finds Java 21 (the Minecraft launcher's own Java works) and opens the app.
# With arguments it runs the command line instead, e.g.: ./cytra-seedfinder.sh huts 12345 --max-y -50
DIR="$(cd "$(dirname "$0")" && pwd)"
candidates=(
  "${JAVA_HOME:+$JAVA_HOME/bin/java}"
  "$HOME/.minecraft/runtime/java-runtime-delta/linux/java-runtime-delta/bin/java"
  "$HOME/.local/share/PrismLauncher/java/java-runtime-delta/bin/java"
  "$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/java/java-runtime-delta/bin/java"
  "$HOME/Library/Application Support/minecraft/runtime/java-runtime-delta/mac-os-arm64/java-runtime-delta/jre.bundle/Contents/Home/bin/java"
  "$HOME/Library/Application Support/minecraft/runtime/java-runtime-delta/mac-os/java-runtime-delta/jre.bundle/Contents/Home/bin/java"
)
JAVA=""
for c in "${candidates[@]}"; do
  if [ -n "$c" ] && [ -x "$c" ]; then JAVA="$c"; break; fi
done
[ -z "$JAVA" ] && command -v java >/dev/null && JAVA="$(command -v java)"
if [ -z "$JAVA" ]; then
  echo "Cytra Seed Finder needs Java 21. Play Minecraft 1.21 once (its launcher installs Java 21), or install it from https://adoptium.net" >&2
  exit 1
fi
exec "$JAVA" -XX:MaxRAMPercentage=60 -jar "$DIR/CytraSeedFinder.jar" "$@"
