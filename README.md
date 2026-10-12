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

Output lists each area's centre (always inside the biome), box size, area, distance, and the share of
plain / eroded (spikes) / wooded badlands. The map shows matches in orange with rank numbers.
