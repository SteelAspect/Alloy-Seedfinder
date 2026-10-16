# Cytra Seed Finder

A desktop app (and command line) that finds the biggest biomes and the best structures in a Minecraft
**1.21.11** seed. It runs Minecraft's own world generator, so results match the real game; a **Verify** button
checks them on a real server with the same seed.

**Map** (the first page): a cubiomes-viewer style explorer for the Overworld, Nether and End, with the biome under
the cursor, a go-to box and 16 switchable structure icon layers (villages, monuments, huts, outposts, mansions,
temples, igloos, ancient cities, trial chambers, strongholds, ruined portals, shipwrecks, fortresses, bastions,
end cities). Positions use the game's own placement code and biome checks (checked with /locate: 101/101).

Tools: biggest biome areas (mesa by default) · biggest **solid** square/rectangle of a biome · witch huts below
a height (huts over cave openings generate deep) · double/triple/quad ocean monument AFK spots · nether biomes ·
biggest nether fortresses (floor area, height, volume).

App: dark theme, saved seeds, zoomable biome map with numbered pins, Copy /tp, waypoints to Xaero's minimap
or `/cway add` commands, CSV and map image export, saved results, and a Speed setting (Fast / Balanced / Light).

## Sharing it

`./build.sh` makes `dist/CytraSeedFinder-<version>.zip` (about 1 MB). Windows users double-click
`Cytra Seed Finder.bat`; Linux/Mac users run `cytra-seedfinder.sh` (`install-linux.sh` adds an app menu entry).
Both find Java 21 by themselves, including the one the Minecraft launcher installs.

No Minecraft code is in the zip. On first start the app downloads the official 1.21.11 server jar and mappings
from Mojang (about 70 MB, checked by SHA-1), unpacks the libraries and converts the game code to Mojang's names
with tiny-remapper, into the app's data folder. The converter libraries ship in `libs/` with their licenses.

## Development

```bash
./seedfinder                      # open the app (uses the Minecraft jars from the Fabric Loom cache, lib/)
./seedfinder huts <seed> --max-y -50
./seedfinder --help
./build.sh                        # build the shareable zip
```

Code: `engine/` (searches, world generator access), `ui/` (Swing app), `launcher/` (Java check, first-run
download and conversion, class loader), `verify/` (test server checks), `export/`, `store/`.

Speed: map tiles read biomes at a fixed height (y 64) with a cave fallback, draw a quick preview first, nearest
tiles first, and are cached on disk (500 MB cap, Clear map cache in Settings). Biome and solid searches use two
passes (fixed height everywhere, then exact lookups on candidates plus a 2-cell margin): about 2x faster with
identical results (`--exact` skips the first pass). Fortresses are built on every core with private piece
counters (`FortressBuilder`; `--serial-fortresses` is the old path), identical on 2000 compared fortresses.

Gotchas: fortress pieces share static counters in Minecraft's code (FortressBuilder gives each fortress its own).
The code-built registries have no tags, so biome tags are read from the game jar and bound by reflection, and
each structure gets a plain biome list. Minecraft breaks exact biome ties by lookup order (Climate.RTree's
lastResult), so a handful of tie cells can differ between runs, as in the game itself.
`seedfinder screenshot <file> [page|search:<page>]` draws the window to a PNG without showing it.

## Files

Settings: `~/.config/cytra-seedfinder` (Windows `%APPDATA%\CytraSeedFinder`). Saved results and Minecraft files:
`~/.local/share/cytra-seedfinder` (Windows `%LOCALAPPDATA%\CytraSeedFinder`).
