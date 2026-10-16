#!/usr/bin/env bash
# Adds Cytra Seed Finder to your Linux app menu (run it again after moving this folder).
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "$APPS"
cat > "$APPS/cytra-seedfinder.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Cytra Seed Finder
Comment=Find the biggest biomes and best structures in a Minecraft seed
Exec="$DIR/cytra-seedfinder.sh"
Icon=$DIR/icon.png
Terminal=false
Categories=Game;Utility;
StartupWMClass=dev-steelaspect-seedfinder-launcher-Launcher
DESKTOP
command -v update-desktop-database >/dev/null && update-desktop-database "$APPS" 2>/dev/null || true
echo "Added Cytra Seed Finder to your app menu."
