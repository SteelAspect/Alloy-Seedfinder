#!/usr/bin/env bash
# Adds Alloy Seed Finder to your Linux app menu (run it again after moving this folder).
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "$APPS"
cat > "$APPS/alloy-seedfinder.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Alloy Seed Finder
Comment=Find the biggest biomes and best structures in a Minecraft seed
Exec="$DIR/alloy-seedfinder.sh"
Icon=$DIR/icon.png
Terminal=false
Categories=Game;Utility;
StartupWMClass=dev-steelaspect-seedfinder-launcher-Launcher
DESKTOP
# It was called Cytra Seed Finder before: drop the old menu entry.
rm -f "$APPS/cytra-seedfinder.desktop"
command -v update-desktop-database >/dev/null && update-desktop-database "$APPS" 2>/dev/null || true
echo "Added Alloy Seed Finder to your app menu."
