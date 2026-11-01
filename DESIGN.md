# 2.6.0: everything the CLI can find, in the app (done: released as 2.6.0)

Goal: someone with only the zip can find what the owner found by hand on 2026-10-23 .. 11-01 (quad seeds, low huts,
joined crossroads in one biome, overlapping fortresses, world-border corner farms, roof shapes), without the CLI.

## Changes

1. **Fortresses page**: Rank by (floor area / joined crossroads / straight lines / overlapping pairs), crossroads at or
   below y, and a biome filter: biome (any or one of the 5), heights (e.g. 48-93), and "Whole fortress" (every piece)
   or just the crossroads. Engine already has all of it (`FortressSearch.Params`).
2. **Where** (every nether page): "Around a point" or one of 4 **world-border corners**. A corner searches the
   square that starts at the 3,750,000 line (overworld border / 8) and goes out by the chosen size, so every result's
   portal lands on that corner of the overworld border. Sizes past the nether border are clamped.
3. **Big areas**: the solid square search samples a coarser grid when the area is huge and then misses the biggest
   squares (5M x 5M gave 432 where 1M x 1M tiles give 748). Areas over 1M x 1M run as 1M tiles and merge.
4. **Nether roof presets**: Wither cage (nether9), 3x3 solid 2 high (y 126 + roof), 3x3x2 (y 125-126),
   3x3x3 (y 124-126), Custom (the pattern box).
5. **Seed hunts page** (no seed needed): quad monument at 0,0 + quad witch hut within a range (graphics card), and
   low witch huts near 0,0 across the 65,536 top-bit versions of a seed. Results carry their own seed (map follows).
6. **Seed overview page**: one search around a point that runs the usual checks and lists the best of each: nearest
   village / trial chambers / ancient city / stronghold / mansion / monument, triple+ monument and hut quads, lowest
   huts, biggest fortress and best crossroad line, treasure bastion, nearest wither cage roof. Nether rows show on the
   nether map (the map follows the selected row's dimension).

## Not doing
- Mineshaft-aware hut heights (the -55 .. -59 huts): the finder reads terrain + caves only; the README says so.
- A free-form "combine any conditions" query language: the overview page plus per-tool filters cover the cases asked.

## Check
Each page: screenshot render, a search that reproduces a known result from README / the chats (SMP seed
1473884015873122059: y48 soul sand straight quad line at 7881, -28023; roof 3x3 at 1147, -55; corner fortresses).
