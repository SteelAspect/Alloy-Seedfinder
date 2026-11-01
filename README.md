# Alloy Seed Finder

A desktop app (and command line) that finds the biggest biomes and the best structures in a Minecraft
**1.21.11** seed. It runs Minecraft's own world generator, so results match the real game; a **Verify** button
checks them on a real server with the same seed.

**Map** (the first page): a cubiomes-viewer style explorer for the Overworld, Nether and End, with the biome under
the cursor, a go-to box and 16 switchable structure icon layers (villages, monuments, huts, outposts, mansions,
temples, igloos, ancient cities, trial chambers, strongholds, ruined portals, shipwrecks, fortresses, bastions,
end cities). Positions use the game's own placement code and biome checks (checked with /locate: 101/101).

Tools: biggest biome areas (mesa by default) · the 10 biggest **solid** squares and rectangles of a biome · witch
huts below a height (huts over cave openings generate deep) · double/triple/quad ocean monument and witch hut AFK
spots (Quad finder), up to the whole world · slime farm AFK spots and seeds with the best slime farm · nearest structures of a kind (village types,
treasure bastions, blaze spawners, end cities with a ship and where its elytra hangs) · wither skeleton farm AFK
spot for each fortress · nether biomes · biggest nether fortresses (floor area, height, volume), joined crossroads,
straight crossroad lines and overlapping fortresses, optionally all in one biome through a height range (nether
biomes change with height) · nether roof bedrock shapes (wither cage, solid 3x3 blocks, your own pattern).

New in 2.6.0: **Seed overview** (one search: nearest structures, quads, lowest huts, biggest fortress, crossroad line,
wither cage roof around a point) · **Seed hunts** (new seeds with a quad monument at 0,0 and a quad witch hut nearby;
low witch huts across the 65,536 versions of a seed) · **Where: border corner** on nether pages (the nether past
3,750,000 on both axes, where a portal goes to that corner of the overworld world border) · big solid-square searches
run as 1M x 1M tiles so they don't miss the biggest squares · the form wraps when a page has many options.

Graphics card: any OpenCL 1.2 card (AMD, NVIDIA, Intel). Every graphics card search checks itself against the CPU or
the game's code first and falls back to the CPU if anything differs or no card works (same results, slower). Work is
sent in batches of about 40 ms so no driver watchdog resets the card. Tested on an AMD RX 9070 XT (Linux, Mesa).
The map can shade slime chunks and draw a chunk grid; clicking it shows the chunk, region file and nether/overworld
coordinates.

App: dark theme, saved seeds, zoomable biome map with numbered pins, Copy /tp, waypoints to Xaero's minimap
or `/cway add` commands, CSV and map image export, saved results, and a Speed setting (Fast / Balanced / Light).

## Sharing it

`./build.sh` makes `dist/AlloySeedFinder-<version>.zip` (about 1 MB). Windows users double-click
`Alloy Seed Finder.bat`; Linux/Mac users run `alloy-seedfinder.sh` (`install-linux.sh` adds an app menu entry).
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

Low witch huts: checked against [LowYSwampHut](https://github.com/M33Galaxy/LowYSwampHut) (MIT): on the SMP seed,
1M x 1M, both find the one hut at or below y -40 (-15792, -54, -17168). Its fast pre-filter is an approximation, so
the finder keeps its own exact path.

Quad seeds (`quadseeds`, graphics card): seeds with a quad monument at 0,0 and a quad witch hut within a range (default
30k). Structure positions only use the low 48 bits, and moving a quad to another region corner is the same as adding a
constant to the seed, so the card tries every structure seed for 4 monuments within 16 chunks of each other around
0,0 (about 2.3e10 seeds/s on an RX 9070, checked against the game's placement code at start). The CPU keeps those
with one AFK spot within 128 of all 4 middles, looks for quad huts nearby, then tries all 65,536 top-bit versions with
the game's own structure generation (all 4 huts in a swamp, all 4 monuments really generating). About 1 in 10 billion
structure seeds has a quad monument; about 1 in 1,500 of those has a quad hut within 30k.

Joined crossroads (Fortresses, "Rank by joined crossroads", `fortresses --crossroads [--max-y]`): the idea of
Gaider10's [CrossroadFinder](https://github.com/Gaider10/CrossroadFinder) (web version by Mews): 4-way bridge crossings
(19 x 19, the start piece is one too) side by side at the same height, as double, triple line/corner, quad line/square
or quint. Same results as CrossroadFinder on the seeds compared (quint line and quad squares).

Nether roof (`roof`): bedrock patterns in the roof, y 123 to 126, from [nether9](https://github.com/spadix0/nether9)
by spadix0 (MIT-0); the default is its wither cage. The bedrock is the game's own rule (`bedrock_roof` positional
random and gradient). Same 4 spots as nether9 on its README seed within 420 blocks. Only the low 48 bits of the seed
matter (the nether uses the legacy random).

Slime farms (Slime chunks and Slime seeds), ported from kyoubitt's slime toolchain (github.com/kyoubitt/slime,
used with permission). A farm is one floor of slime-chunk blocks at y -64; a block counts when its slime would stand
more than 24 and at most 128 blocks from your feet. cq = counted blocks / 256, standing level with the floor (the
toolchain's records); "At y -39" is what Carpet's /perimeterinfo reads with your feet at y -39. Checked against the
toolchain's numbers: SlimeFinder.jar's rows (16821/70, 16498/72, 16496/68), the 66.04 and 66.21 records, and the
in-game /perimeterinfo counts of 7 record farms (18,727 · 18,598 · 18,261 · 18,407 · 17,771 · 17,112 · 18,313), all exact.
Spot search: every chunk corner is scored (a 17x17 square count and row counts rule most out), the best 300 are
tried at every block within 2 chunks, the best get the biome check at the floor (deep dark and mushroom fields have no
slimes). 100k x 100k: 4 s, 1M x 1M: 8 s. The pick is the nearest result within 1 cq of the best.
Seed hunt: the toolchain's exact phase search. Only the low 48 bits of a seed matter; for one family (bits 0-19)
and class (20-34) the 8,192 top values move every chunk's random number in lockstep, so the best farm of a class
comes from one walk over its chunks' wrap points. The richest families (by chunks that can be slime at all) are
searched, every class bounded per phase and quarter, the walk run where the bound reaches the best so far; every
layout is recounted with the game's rule. OpenCL on the graphics card (234M classes/s on an RX 9070 XT, same results
as the CPU, checked before each hunt), the CPU otherwise. The walk matches all 8,192 seeds of 80 random classes by
brute force; at the toolchain's spots it re-finds the 69.39 record (seed 71791501344359, 17,764 blocks, 11 s for 10
positions) and, ranked in game, the 72.22 (seed 245821300723973, 18,727 at y -39). Farms also export as the
toolchain's picture and as an in-game test rig (WorldEdit void, obsidian floor from one command block of command
minecarts, a /perimeterinfo block), tested on a 1.21.11 server: the floor is obsidian in exactly the slime chunks.

Nearest structures: rings of regions outwards from the point, with the map icons' placement code; bastion type and
end city ships come from the game building the pieces (the ship's "Elytra" data marker gives the item frame spot).
Verify uses /locate, plus the elytra item frame for ships (all 13 kinds 8/8, ships 6/6). The game's template code has
an unsynchronised cache (StructureTemplate.Palette), so jigsaw lookups by name and piece builds take a lock.

Gotchas: fortress pieces share static counters in Minecraft's code (FortressBuilder gives each fortress its own).
The code-built registries have no tags, so biome tags are read from the game jar and bound by reflection, and
each structure gets a plain biome list. Minecraft breaks exact biome ties by lookup order (Climate.RTree's
lastResult), so a handful of tie cells can differ between runs, as in the game itself.
`seedfinder screenshot <file> [page|search:<page>]` draws the window to a PNG without showing it.

## Graphics card (GPU)

Map tiles, biome searches and solid-area refinement can run on the graphics card through OpenCL (any card with
64-bit floating point: `cl_khr_fp64`). Nothing native is shipped: `src-ffm/.../OpenClFfm` calls the system's OpenCL
driver through Java's foreign function API, built twice (`gpu.ffm21` for Java 21 with `--enable-preview`, `gpu.ffm22`
for Java 22+); the launchers set `JDK_JAVA_OPTIONS` and, for Mesa's rusticl, `RUSTICL_FEATURES=fp64`.

1:1 with the game: `GpuCode` writes the OpenCL program from the game's own objects for the world type (the climate
sampler's density functions, the preliminary surface level, every noise octave, spline and the biome parameter
tree), node for node in the same arithmetic order. Drivers aren't trusted with what they get wrong: double division,
double-to-float rounding and float subnormals are done with integer bit operations (rusticl's division is not
correctly rounded and it drops float rounding before conversions). Splines run through one table-driven loop so the
program stays small (the driver's compiler ran out of memory on the fully unrolled version).

Exact ties (two biomes exactly as close, about 3 cells in 10,000) are settled the way chunk generation does: the
game's lookup with the previous cell of the chunk's fill order as its last result (`ClimateTree.inGame`, used by the
CPU path too, so results no longer depend on thread scheduling). Checked against the game generating real chunks
(`doCreateBiomes` on ProtoChunks): 2.55M biome cells over 1,980 chunks (3 seeds, nether, large biomes, amplified,
the world border), 0 differences; 5M climate points bit for bit; each world is self-checked on 16,384 spots before
use. Each run is sized to about 40 ms so no driver watchdog resets the card; compiled programs are cached in
`<cache>/gpu`. Off in Settings or at Light speed; `--cpu` on the command line.

## Files

Settings: `~/.config/alloy-seedfinder` (Windows `%APPDATA%\AlloySeedFinder`). Saved results and Minecraft files:
`~/.local/share/alloy-seedfinder` (Windows `%LOCALAPPDATA%\AlloySeedFinder`).
