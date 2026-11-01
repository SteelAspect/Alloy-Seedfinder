# Alloy Seed Finder (repo: cytra-seedfinder)

Standalone Swing desktop app + CLI that searches Minecraft 1.21.11 seeds by running the game's own world generator. Not a Fabric mod and not a Gradle project. Workspace rules: `../CLAUDE.md`. The user's SMP seed is `1473884015873122059` (`1486470357373204722` is the quad monument + quad hut seed found by the hunt; `795374283637013279` has a y -58 hut 7.3k out).

## What it is
- Name "Alloy Seed Finder" (was Cytra Seed Finder), version in `src/dev/steelaspect/seedfinder/launcher/Version.java` (`VALUE`, read by the app, `build.sh` and the updater). Shareable zip `dist/AlloySeedFinder-<version>.zip` (about 1 MB) plus `.zip.sha256`.
- Public repo `SteelAspect/cytra-seedfinder` (made public 2026-11-01; the owner is fine with the SMP seed being public), branch `main` only.
- Auto-update (`store/Updater`, `launcher/Updates`): the app reads the latest GitHub release (tag `v<version>`, assets: the zip and its `.zip.sha256`), downloads into the data folder's `updates/<version>`, and `Boot` starts the newest one there. A release = `./build.sh`, then `gh release create v<version> dist/AlloySeedFinder-<version>.zip dist/AlloySeedFinder-<version>.zip.sha256` (ask the owner first). Docs: `README.md` (long: features, verification numbers, gotchas, GPU). **No PROGRESS.txt, no tests folder, no CLAUDE-style notes before this file.** Not in `cytra-releases`.

## Stack
- JDK 21 (`JAVA_HOME=~/.local/share/PrismLauncher/java/java-runtime-delta`; `build.sh` also needs a JDK 22+ for the Java 22+ GPU code, `JAVA22_HOME`). No Gradle: plain `javac`. `lib/` (git-ignored) holds the Minecraft 1.21.11 jar + libraries copied from the Fabric Loom cache by `./seedfinder` on first run (never packaged). Packaged converter libs from the Gradle cache: tiny-remapper 0.14.1, mapping-io 0.8.0, ASM 9.10.1.
- On the user's machine the shipped app downloads the official server jar + mappings from Mojang on first start (about 70 MB, SHA-1 checked) and remaps them with tiny-remapper into the app data folder.

## Code map (`src/dev/steelaspect/seedfinder`)
- `Main`, `Cli` (all commands: `huts`, `lowhuts`, `quadseeds`, `fortresses --crossroads|--lines`, `roof`, slime, nearest, ... see `./seedfinder --help`), `launcher/` (`Launcher`+`AppDirs` compile with `--release 8` to tell old Java users off, `Boot`, `Setup`, `SetupWindow`, class loader), `ui/` (`SeedFinderApp`, `MapPage`/`MapView` + tile renderer/cache/scheduler, `SearchPage`, `SettingsPage`, `HistoryPage`, `ResultsTable`, `XaeroDialog`, `Theme`), `store/` (`Settings`, `History`, `Json`), `export/Exporters` (CSV, image, Xaero waypoints, `/cway add`), `verify/Verifier` (checks on a real test server with `/locate`).
- `engine/`: `WorldGen`, `Structures` + `StructureLayers` (placement via the game's code), `BiomeSearch`, `SolidSearch`, `MonumentSearch`, `HutSearch`, `CarvedGround`, `FortressSearch` + `FortressBuilder` (per-fortress counters), `NearestSearch`, `SlimeSearch`/`SlimeHunt`/`Slime`, `QuadHunt`, `RoofSearch`, `ClimateTree`, `Workers`, GPU: `GpuCode`, `GpuWorld`, `gpu/{Gpu,OpenCl}`, `src-ffm/.../OpenClFfm` (built twice: ffm21 with `--enable-preview`, ffm22).
- Packaging: `packaging/` (`Alloy Seed Finder.bat`, `alloy-seedfinder.sh`, `install-linux.sh`, README), `packaging-licenses/` (Apache-2.0, ASM BSD-3, GPL-3.0, LGPL-3.0, NOTICE). `resources/` copied into the classes.

## Build and test
```bash
./seedfinder                         # open the app (dev launcher; fills lib/ first time; needs a 1.21.11 mod built once so the Loom cache exists)
./seedfinder huts <seed> --max-y -50 # CLI example; ./seedfinder --help
./seedfinder screenshot out.png map  # draw a page to PNG without a window
./build.sh                           # dist/AlloySeedFinder-<version>.zip
```
There is **no automated test suite**. Correctness is checked by comparing with the real game: `Verify` (/locate on a test server), chunk-generation comparisons (2.55M biome cells, 0 differences), known seeds and records listed in README. Use `--cpu` to turn the GPU off. Mute nothing needed (no sound). Run long searches only on request (a whole-world run is minutes).

## Cross-mod links
- Exports waypoints to Xaero's minimap and `/cway add` commands for Alloy Waypoints (**waypoints** chat if the format changes). Otherwise independent.

## Decisions and gotchas
- Results must stay identical to the game: placement uses the game's own code; biome ties follow the game's `Climate.RTree` last-result order (`ClimateTree.inGame`), so a few tie cells differ between runs as in the game.
- Fortress pieces share static counters in Minecraft's code: always build via `FortressBuilder`. Block/biome tags are read from the game jar and bound by reflection. `StructureTemplate.Palette` has an unsynchronised cache: jigsaw lookups and piece builds take a lock.
- No Minecraft code or assets may be put in the zip or repo; `lib/` stays ignored.
- Ideas ported from other tools are credited in README (DoubleTripleQuadMonumentFinder MIT, LowYSwampHut MIT, CrossroadFinder, nether9 MIT-0, kyoubitt's slime toolchain used with permission): keep credits and licences.
- GPU runs are sized to about 40 ms so a driver watchdog doesn't reset the card.

## Release
- No `cytra-releases` step. A release is a zip from `./build.sh` that the user shares by hand; bump `VERSION`, never share or publish without asking.

## Gaps and improvement ideas
- No tests at all: `Slime`, `Structures` placement maths and `Exporters` are pure and could have JUnit regression cases from the README numbers (the 101/101 `/locate` check, slime records).
- No PROGRESS.txt or CHANGELOG; versions 2.0.0 to 2.5.0 exist only in `dist/` zips and git history. `dist/` holds old `CytraSeedFinder-*` and `AlloySeedFinder-2.4.0` builds (git-ignored, prunable).
- `build.sh` depends on the Gradle cache paths for tiny-remapper/ASM and on a JDK 22+ being installed; fail messages exist but there is no one-line prerequisites check.
- README is very long (about 135 lines of dense text); split into README + `docs/` (GPU, verification, search algorithms).
- `results/far2` and other generated results sit in the working tree (ignored); there is no documented place for saved hunts.
