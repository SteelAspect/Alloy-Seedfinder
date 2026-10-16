Cytra Seed Finder
=================

Finds the biggest biomes and the best structures in a Minecraft 1.21.11 seed: biggest mesas (or any biome),
the biggest solid squares of a biome, deep witch huts, double/triple/quad ocean monuments, nether biomes and
the biggest nether fortresses. It uses Minecraft's own world generator, so results match the real game.

Start it
  Windows:      double-click "Cytra Seed Finder.bat"
  Linux / Mac:  run ./cytra-seedfinder.sh   (Linux app menu icon: run ./install-linux.sh once)

It needs Java 21. If you've played Minecraft 1.21 with the normal launcher, that Java is found automatically.
Otherwise get Java 21 from https://adoptium.net

First start
  The app downloads Minecraft 1.21.11's official server files from Mojang (about 70 MB, one time) and converts
  them, so it can use the game's own code. Nothing from Minecraft is included in this download.

Speed
  The Speed box in the top bar: Fast uses every CPU core, Balanced about half, Light only 2 at low priority
  so you can keep playing or browsing while it searches.

Verify
  Checks results on a real Minecraft server with your seed (it starts one in the background and deletes it
  afterwards). Running a Minecraft server means accepting the Minecraft EULA; the app asks first.

Command line
  ./cytra-seedfinder.sh --help         (Windows: java -jar CytraSeedFinder.jar --help)

Files
  Settings and saved results:  Windows %APPDATA%\CytraSeedFinder and %LOCALAPPDATA%\CytraSeedFinder,
                               Linux ~/.config/cytra-seedfinder and ~/.local/share/cytra-seedfinder

Made by steelaspect. Not an official Minecraft product; not approved by or associated with Mojang or Microsoft.
Bundled libraries and their licenses are listed in licenses/NOTICE.txt.
