# Cytra Seed Finder

A desktop app (and command line) that finds the biggest biomes and the best structures in a Minecraft
**1.21.11** seed. It runs Minecraft's own world generator, so results match the real game; a **Verify** button
checks them on a real server with the same seed.

**Map** (the first page): a cubiomes-viewer style explorer for the Overworld, Nether and End, with the biome under
the cursor, a go-to box and 16 switchable structure icon layers (villages, monuments, huts, outposts, mansions,
temples, igloos, ancient cities, trial chambers, strongholds, ruined portals, shipwrecks, fortresses, bastions,
end cities). Positions use the game's own placement code and biome checks (checked with /locate: 101/101).

Tools: biggest biome areas (mesa by default) · the 10 biggest **solid** squares and rectangles of a biome · witch
huts below a height (huts over cave openings generate deep) · double/triple/quad ocean monument and witch hut AFK
spots (Quad finder), up to the whole world · slime farm AFK spots · nearest structures of a kind (village types,
treasure bastions, end cities with a ship and where its elytra hangs) · nether biomes · biggest nether fortresses (floor area, height, volume).
The map can shade slime chunks and draw a chunk grid; clicking it shows the chunk, region file and nether/overworld
coordinates.

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

Monuments (ideas from [DoubleTripleQuadMonumentFinder](https://github.com/BrianBLee1201/DoubleTripleQuadMonumentFinder),
MIT): an AFK spot counts for a monument when it is within 128 blocks (sideways) of its middle, and spots are ranked
by the exact number of guardian spawning blocks in range (more than 24 and at most 128 blocks away, as in
NaturalSpawner). Regions are scanned in tiles with just the placement maths, only monuments with close neighbours
get the game's biome check (read point by point, stopping at the first failure), and triples/quads try every block
for the AFK spot. Whole world, triples, Balanced: about 3 minutes. Witch huts use the same engine; a hut's height
is worked out like the game does (average ground under it), on the chunk's terrain with its caves carved
(`CarvedGround`: the game's own noise fill and carver loop for that chunk). Whole world triple/quad huts: about
2.5 minutes. Block and fluid tags are read from the game jar too (carvers need them).

Slime farms: spots are ranked by the exact number of blocks a slime can spawn in within range (slime chunk from
WorldgenRandom.seedSlimeChunk, y -64 to 39, 24 to 128 blocks away, and a biome whose monster list has slimes, looked
up per block with the game's BiomeManager, so deep dark and mushroom fields don't count). Every chunk is scored by
slime chunks within 8 chunks, the best 300 are climbed to the best block and height, the best 60 recounted with
biomes. Counts match a plain loop over every block (12/12 spots); the #1 spot matches trying every 4th block over
20k x 20k on two seeds. 400k x 400k: 13 s, 2M x 2M: 87 s (Balanced).

Nearest structures: rings of regions outwards from the point, with the map icons' placement code; bastion type and
end city ships come from the game building the pieces (the ship's "Elytra" data marker gives the item frame spot).
Verify uses /locate, plus the elytra item frame for ships (all 13 kinds 8/8, ships 6/6). The game's template code has
an unsynchronised cache (StructureTemplate.Palette), so jigsaw lookups by name and piece builds take a lock.

Gotchas: fortress pieces share static counters in Minecraft's code (FortressBuilder gives each fortress its own).
The code-built registries have no tags, so biome tags are read from the game jar and bound by reflection, and
each structure gets a plain biome list. Minecraft breaks exact biome ties by lookup order (Climate.RTree's
lastResult), so a handful of tie cells can differ between runs, as in the game itself.
`seedfinder screenshot <file> [page|search:<page>]` draws the window to a PNG without showing it.

## Files

Settings: `~/.config/cytra-seedfinder` (Windows `%APPDATA%\CytraSeedFinder`). Saved results and Minecraft files:
`~/.local/share/cytra-seedfinder` (Windows `%LOCALAPPDATA%\CytraSeedFinder`).
