package dev.steelaspect.seedfinder.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.WorldgenRandom;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

/**
 * Slime farm AFK spots: where the most blocks a slime can spawn in are in range. Minecraft's rules
 * (Slime.checkSlimeSpawnRules, NaturalSpawner): a slime chunk, below y 40, in a biome whose monster list has slimes
 * (so not deep dark or mushroom fields), more than 24 and at most 128 blocks from the player.
 *
 * <p>Three steps: every chunk of the area is sorted by how many slime chunks are within 8 chunks of it (cheap, from
 * the seed alone); the best few hundred are climbed to the AFK block and height with the most spawnable blocks; the
 * best 60 are then counted again block by block with the biomes, and climbed once more.
 */
public final class SlimeSearch {
    /** Slimes spawn in slime chunks below y 40, down to the bottom of the world. */
    static final int MIN_Y = -64, MAX_Y = 39;
    private static final int NEAR2 = 24 * 24, FAR = 128, FAR2 = FAR * FAR;
    /** Chunks per side of one work tile, and the margin around it (128 blocks is 8 chunks). */
    private static final int TILE = 256, MARGIN = 9;
    /** How many chunk candidates get climbed, and how many of those get the biome-exact count. */
    private static final int CLIMB = 300, EXACT = 60;
    private static final int[] ISQRT = new int[FAR2 + 1];
    /** Row half-widths of the 8-chunk disc used for the first sort. */
    private static final int[] DISC = new int[17];

    static {
        for (int i = 0; i <= FAR2; i++) ISQRT[i] = (int) Math.sqrt(i);
        for (int d = -8; d <= 8; d++) DISC[d + 8] = (int) Math.sqrt(64 - d * d);
    }

    public record Params(Search area, int top) {}

    /** A chunk's slime spawning blocks after the biome check: all of them, none, or the ones in this set. */
    private record Mask(BitSet allowed) {}

    private static final Mask ALL = new Mask(null), NONE = new Mask(new BitSet());

    record Spot(int x, int y, int z, long blocks) {}

    /** Minecraft's slime chunk rule (WorldgenRandom.seedSlimeChunk, as in Slime.checkSlimeSpawnRules). */
    public static boolean isSlimeChunk(long seed, int cx, int cz) {
        return WorldgenRandom.seedSlimeChunk(cx, cz, seed, 987234911L).nextInt(10) == 0;
    }

    private final WorldGen w;
    private final long zoomSeed;
    private final Map<Long, Mask> masks = new ConcurrentHashMap<>();
    private final Map<Holder<Biome>, Boolean> slimeBiome = new ConcurrentHashMap<>();

    SlimeSearch(WorldGen w) {
        this.w = w;
        zoomSeed = BiomeManager.obfuscateSeed(w.seed);
    }

    public static List<Result> run(Params p, Progress progress) {
        WorldGen w = WorldGen.of(p.area().seed(), Dim.OVERWORLD, p.area().type());
        return new SlimeSearch(w).search(p, progress);
    }

    private List<Result> search(Params p, Progress progress) {
        Search a = p.area();
        int c0 = Math.floorDiv(a.centerX() - a.radius(), 16), c1 = Math.floorDiv(a.centerX() + a.radius(), 16);
        int d0 = Math.floorDiv(a.centerZ() - a.radius(), 16), d1 = Math.floorDiv(a.centerZ() + a.radius(), 16);
        int tilesX = Math.ceilDiv(c1 - c0 + 1, TILE), tilesZ = Math.ceilDiv(d1 - d0 + 1, TILE);
        long tiles = (long) tilesX * tilesZ;
        // 1. Every chunk, by slime chunks within 8 chunks (from the seed alone).
        List<long[]> candidates = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicLong done = new AtomicLong();
        long every = Math.max(1, tiles / 300);
        progress.update(0, "Finding slime chunks");
        LongStream.range(0, tiles).parallel().forEach(t -> {
            progress.check();
            int i = c0 + (int) (t / tilesZ) * TILE, j = d0 + (int) (t % tilesZ) * TILE;
            candidates.addAll(tile(i, j, Math.min(TILE, c1 - i + 1), Math.min(TILE, d1 - j + 1)));
            long d = done.incrementAndGet();
            if (d % every == 0) progress.update(0.7 * d / tiles, "Finding slime chunks");
        });
        List<long[]> best = new ArrayList<>(candidates);
        best.sort((x, y) -> Long.compare(y[2], x[2]));
        best = apart(best, 3, CLIMB);

        // 2. Climb each to its best block and height (slime chunks only, no biomes yet).
        progress.update(0.7, "Finding the best AFK spots");
        List<Spot> climbed = best.parallelStream().map(c -> {
            progress.check();
            return climb(new Spot((int) c[0] * 16 + 8, -12, (int) c[1] * 16 + 8, 0), false);
        }).toList();

        // 3. The best of those again with the biomes, block by block.
        progress.update(0.85, "Checking biomes for the best spots");
        List<Spot> top = new ArrayList<>(climbed);
        top.sort((x, y) -> Long.compare(y.blocks, x.blocks));
        top = distinct(top, EXACT);
        List<Spot> exact = new ArrayList<>(top.parallelStream().map(s -> {
            progress.check();
            return climb(new Spot(s.x, s.y, s.z, count(s.x, s.y, s.z, true)), true);
        }).toList());
        exact.sort((x, y) -> x.blocks != y.blocks ? Long.compare(y.blocks, x.blocks) : Long.compare(a.distance(x.x, x.z), a.distance(y.x, y.z)));
        exact.removeIf(s -> !a.inside(s.x, s.z));
        exact = distinct(exact, p.top());
        List<Result> out = new ArrayList<>();
        for (Spot s : exact) out.add(result(s, p));
        progress.update(1, out.isEmpty() ? "No slime spawning space here" : "Best spot has " + Fmt.n((long) out.get(0).value) + " spawnable blocks in range");
        return out;
    }

    /** Candidate chunks of one tile: {chunkX, chunkZ, slime chunks in the 8-chunk disc}, the best few, apart. */
    private List<long[]> tile(int i0, int j0, int n, int m) {
        int s = n + 2 * MARGIN, t = m + 2 * MARGIN;
        // 2D prefix sums of slime chunks, margin included.
        int[] sum = new int[(s + 1) * (t + 1)];
        for (int i = 0; i < s; i++) {
            int row = 0;
            for (int j = 0; j < t; j++) {
                if (isSlimeChunk(w.seed, i0 - MARGIN + i, j0 - MARGIN + j)) row++;
                sum[(i + 1) * (t + 1) + j + 1] = sum[i * (t + 1) + j + 1] + row;
            }
        }
        java.util.PriorityQueue<long[]> keep = new java.util.PriorityQueue<>((x, y) -> Long.compare(x[2], y[2]));
        for (int i = 0; i < n; i++)
            for (int j = 0; j < m; j++) {
                int ci = i + MARGIN, cj = j + MARGIN;
                // The 17x17 square holds the disc, so it's an upper bound: skip what can't make the list.
                int box = rect(sum, t, ci - 8, cj - 8, ci + 8, cj + 8);
                if (keep.size() >= 200 && box <= keep.peek()[2]) continue;
                int disc = 0;
                for (int d = -8; d <= 8; d++) disc += rect(sum, t, ci + d, cj - DISC[d + 8], ci + d, cj + DISC[d + 8]);
                if (keep.size() < 200) keep.add(new long[]{i0 + i, j0 + j, disc});
                else if (disc > keep.peek()[2]) {
                    keep.poll();
                    keep.add(new long[]{i0 + i, j0 + j, disc});
                }
            }
        List<long[]> list = new ArrayList<>(keep);
        list.sort((x, y) -> Long.compare(y[2], x[2]));
        return apart(list, 3, 24);
    }

    private static int rect(int[] sum, int t, int i0, int j0, int i1, int j1) {
        return sum[(i1 + 1) * (t + 1) + j1 + 1] - sum[i0 * (t + 1) + j1 + 1] - sum[(i1 + 1) * (t + 1) + j0] + sum[i0 * (t + 1) + j0];
    }

    /** Keeps up to n candidates (best first) at least gap chunks from every kept one. */
    private static List<long[]> apart(List<long[]> sorted, int gap, int n) {
        List<long[]> out = new ArrayList<>();
        for (long[] c : sorted) {
            if (out.size() >= n) break;
            if (out.stream().noneMatch(k -> Math.abs(k[0] - c[0]) < gap && Math.abs(k[1] - c[1]) < gap)) out.add(c);
        }
        return out;
    }

    /** Keeps up to n spots (best first) more than 128 blocks from every kept one, so each is its own farm. */
    private static List<Spot> distinct(List<Spot> sorted, int n) {
        List<Spot> out = new ArrayList<>();
        for (Spot s : sorted) {
            if (out.size() >= n) break;
            if (out.stream().noneMatch(k -> (long) (k.x - s.x) * (k.x - s.x) + (long) (k.z - s.z) * (k.z - s.z) <= FAR2)) out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ AFK spot

    /** Moves to the best neighbour (steps 8, 4, 2, 1), then the best height, then climbs once more. */
    Spot climb(Spot s, boolean biomes) {
        if (s.blocks == 0) s = new Spot(s.x, s.y, s.z, count(s.x, s.y, s.z, biomes));
        s = sideways(s, biomes ? 4 : 8, biomes);
        s = height(s, biomes);
        s = sideways(s, 2, biomes);
        return height(s, biomes);
    }

    private Spot sideways(Spot s, int firstStep, boolean biomes) {
        for (int step = firstStep; step >= 1; step /= 2) {
            boolean moved = true;
            while (moved) {
                moved = false;
                Spot next = s;
                for (int dx = -step; dx <= step; dx += step)
                    for (int dz = -step; dz <= step; dz += step) {
                        if (dx == 0 && dz == 0) continue;
                        long c = count(s.x + dx, s.y, s.z + dz, biomes);
                        if (c > next.blocks) next = new Spot(s.x + dx, s.y, s.z + dz, c);
                    }
                if (next != s) {
                    s = next;
                    moved = true;
                }
            }
        }
        return s;
    }

    /** Best AFK height for this column, from the bottom of the world to 24 above the spawning space. */
    private Spot height(Spot s, boolean biomes) {
        for (int y = MIN_Y; y <= MAX_Y + 24; y++) {
            if (y == s.y) continue;
            long c = count(s.x, y, s.z, biomes);
            if (c > s.blocks || (c == s.blocks && Math.abs(y + 12) < Math.abs(s.y + 12))) s = new Spot(s.x, y, s.z, c);
        }
        return s;
    }

    /**
     * Blocks a slime could spawn in for a player standing in the middle of block (ax, ay, az): in a slime chunk,
     * y -64 to 39, squared distance from the player's feet to (x+0.5, y, z+0.5) more than 576 and at most 16384, and
     * (with biomes) in a biome whose monster list has slimes.
     */
    long count(int ax, int ay, int az, boolean biomes) {
        long n = 0;
        for (int cx = (ax - FAR) >> 4; cx <= (ax + FAR) >> 4; cx++)
            for (int cz = (az - FAR) >> 4; cz <= (az + FAR) >> 4; cz++) {
                if (!isSlimeChunk(w.seed, cx, cz)) continue;
                Mask mask = biomes ? mask(cx, cz) : ALL;
                if (mask == NONE) continue;
                int xa = Math.max(cx * 16, ax - FAR), xb = Math.min(cx * 16 + 15, ax + FAR);
                for (int x = xa; x <= xb; x++) {
                    int dx2 = (x - ax) * (x - ax);
                    int reach = ISQRT[FAR2 - dx2];
                    int za = Math.max(cz * 16, az - reach), zb = Math.min(cz * 16 + 15, az + reach);
                    for (int z = za; z <= zb; z++) {
                        int h = dx2 + (z - az) * (z - az);
                        int up = ISQRT[FAR2 - h];
                        int hole = h <= NEAR2 ? ISQRT[NEAR2 - h] : -1;
                        if (mask == ALL) {
                            n += overlap(MIN_Y, MAX_Y, ay - up, ay + up);
                            if (hole >= 0) n -= overlap(MIN_Y, MAX_Y, ay - hole, ay + hole);
                        } else {
                            int base = ((z & 15) * 16 + (x & 15)) * (MAX_Y - MIN_Y + 1) - MIN_Y;
                            for (int y = Math.max(MIN_Y, ay - up); y <= Math.min(MAX_Y, ay + up); y++)
                                if (Math.abs(y - ay) > hole && mask.allowed.get(base + y)) n++;
                        }
                    }
                }
            }
        return n;
    }

    private static int overlap(int a0, int a1, int b0, int b1) {
        return Math.max(0, Math.min(a1, b1) - Math.max(a0, b0) + 1);
    }

    /**
     * Which of a chunk's blocks (y -64 to 39) are in a biome slimes can spawn in, by the game's own block biome lookup
     * (BiomeManager, which picks one of the 8 surrounding 4x4x4 biome cells per block). When every cell it can pick
     * from allows slimes, or none does, there's no need to look block by block.
     */
    private Mask mask(int cx, int cz) {
        return masks.computeIfAbsent(((long) cx << 32) | (cz & 0xFFFFFFFFL), k -> {
            int qx0 = cx * 4 - 1, qz0 = cz * 4 - 1, qy0 = -16, qy1 = ((MAX_Y - 2) >> 2) + 1;
            @SuppressWarnings("unchecked")
            Holder<Biome>[] cells = new Holder[6 * 6 * (qy1 - qy0 + 1)];
            boolean any = false, all = true;
            for (int qx = 0; qx < 6; qx++)
                for (int qz = 0; qz < 6; qz++)
                    for (int qy = qy0; qy <= qy1; qy++) {
                        Holder<Biome> b = w.source.getNoiseBiome(qx0 + qx, qy, qz0 + qz, w.random.sampler());
                        cells[(qx * 6 + qz) * (qy1 - qy0 + 1) + qy - qy0] = b;
                        boolean ok = allowsSlimes(b);
                        any |= ok;
                        all &= ok;
                    }
            if (all) return ALL;
            if (!any) return NONE;
            // A level clamps biome cells below the world to the lowest one, like ChunkAccess.getNoiseBiome.
            BiomeManager manager = new BiomeManager((qx, qy, qz) ->
                    cells[((qx - qx0) * 6 + qz - qz0) * (qy1 - qy0 + 1) + Math.max(qy0, Math.min(qy1, qy)) - qy0], zoomSeed);
            BitSet allowed = new BitSet(256 * (MAX_Y - MIN_Y + 1));
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    for (int y = MIN_Y; y <= MAX_Y; y++)
                        if (allowsSlimes(manager.getBiome(pos.set(cx * 16 + x, y, cz * 16 + z))))
                            allowed.set((z * 16 + x) * (MAX_Y - MIN_Y + 1) + y - MIN_Y);
            return new Mask(allowed);
        });
    }

    /** Whether the biome's monster spawn list has slimes (deep dark and mushroom fields don't). */
    private boolean allowsSlimes(Holder<Biome> b) {
        return slimeBiome.computeIfAbsent(b, h -> h.value().getMobSettings().getMobs(MobCategory.MONSTER).unwrap().stream()
                .anyMatch(e -> e.value().type() == EntityType.SLIME));
    }

    // ------------------------------------------------------------------ output

    private Result result(Spot s, Params p) {
        Result r = new Result();
        r.kind = Result.Kind.SLIME;
        r.dim = Dim.OVERWORLD;
        r.name = "Slime farm";
        r.x = s.x;
        r.y = s.y;
        r.z = s.z;
        r.minX = s.x - FAR;
        r.maxX = s.x + FAR;
        r.minZ = s.z - FAR;
        r.maxZ = s.z + FAR;
        r.value = s.blocks;
        r.size = Fmt.n(s.blocks);
        int chunks = 0;
        for (int cx = (s.x - FAR) >> 4; cx <= (s.x + FAR) >> 4; cx++)
            for (int cz = (s.z - FAR) >> 4; cz <= (s.z + FAR) >> 4; cz++) {
                if (!isSlimeChunk(w.seed, cx, cz)) continue;
                // A chunk counts if any of its spawning blocks is in range.
                int nx = Math.max(cx * 16, Math.min(s.x, cx * 16 + 15)), nz = Math.max(cz * 16, Math.min(s.z, cz * 16 + 15));
                int ny = Math.max(MIN_Y, Math.min(s.y, MAX_Y));
                if ((long) (nx - s.x) * (nx - s.x) + (long) (nz - s.z) * (nz - s.z) + (long) (ny - s.y) * (ny - s.y) <= FAR2) chunks++;
            }
        long lost = count(s.x, s.y, s.z, false) - s.blocks;
        r.details.put("Slime chunks", Integer.toString(chunks));
        r.details.put("Lost to biomes", lost == 0 ? "-" : Fmt.n(lost));
        r.details.put("Stand on", s.x + ", " + (s.y - 1) + ", " + s.z);
        r.details.put("Nether", Math.floorDiv(s.x, 8) + ", " + Math.floorDiv(s.z, 8));
        r.details.put("Distance", Fmt.n(p.area().distance(s.x, s.z)));
        r.ref = new int[]{s.x, s.y, s.z};
        return r;
    }
}
