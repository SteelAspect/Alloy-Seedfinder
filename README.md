# Cytra Mesa Finder

Finds the biggest mesa (badlands) areas for a Minecraft **1.21.11** seed, without running the game.
It uses Minecraft's own world generator, so the biomes match the real world (checked against a vanilla
server: 30/30 mesa test points correct).

## Run

```bash
./mesafinder <seed>
```

First run copies the Minecraft 1.21.11 jars from your Gradle/Loom cache into `lib/` (needs one of the
1.21.11 mods built once) and compiles. Uses the Java 21 from PrismLauncher (override with `JAVA_HOME`).

## Options

| Option | Default | Meaning |
|---|---|---|
| `--radius <blocks>` | 10000 | how far to search |
| `--center <x,z>` | 0,0 | where to search around |
| `--step <blocks>` | 32 | one check every N blocks (smaller = more exact, slower) |
| `--top <n>` | 15 | how many areas to list |
| `--min-size <blocks>` | 128 | smallest area to list, as a square side |
| `--biomes <a,b>` | all 3 badlands | e.g. `eroded_badlands` or `mushroom_fields` |
| `--large-biomes` / `--amplified` | | world type |
| `--map <file>` / `--no-map` | `mesa-<seed>.png` | map picture |

Speed: radius 20000 (1.5M checks) takes about 8 s.

## Witch huts and ocean monuments

```bash
./mesafinder huts <seed> --max-y -50          # huts at or below y -50, with their real height
./mesafinder monuments <seed> --min 3          # triple (3) or quad (4) monument AFK spots
```

Both use the game's own structure placement and biome checks. A witch hut's height is the average ground
height over its 7x9 footprint (same rule as the game), so huts over big cave openings can generate deep
underground. Checked against a real server: 6/6 huts at the exact floor height, including one at y -53.
Options: `--radius` (default 100000), `--center x,z`, `--top`, `--details` (monument centres).

Output lists each area's centre (always inside the biome), box size, area, distance, and the share of
plain / eroded (spikes) / wooded badlands. The map shows matches in orange with rank numbers.
