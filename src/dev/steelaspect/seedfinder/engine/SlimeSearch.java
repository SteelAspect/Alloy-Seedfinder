package dev.steelaspect.seedfinder.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

/**
 * The best slime farm AFK spots in one seed, measured like kyoubitt's slime toolchain (see {@link Slime}): one floor of
 * slime-chunk blocks at y -64, the player standing level with it (cq) or with their feet at y -39 (what Carpet's
 * /perimeterinfo reads in game).
 *
 * <p>Three steps, as the toolchain does it: every chunk corner in the area is scored (a quick count of slime chunks in
 * range rules out most without adding up blocks); the best few hundred are refined at every block within 2 chunks;
 * the best of those get the biome check (deep dark and mushroom fields have no slimes) at the floor.
 */
public final class SlimeSearch {
    /** Chunks per side of one work tile; candidates kept per tile; candidates refined at block precision. */
    private static final int TILE = 256, KEEP = 400, REFINE = 300, R = 2;

    public record Params(Search area, Slime.Stand stand, boolean biomes, int top) {
        public Params(Search area, int top) {
            this(area, Slime.Stand.LEVEL, true, top);
        }
    }

    private final long seed;
    private final Slime.Stand stand;
    private final Slime.Weights wt;
    private final WorldGen w;

    private SlimeSearch(long seed, WorldType type, Slime.Stand stand, boolean biomes) {
        this.seed = seed;
        this.stand = stand;
        wt = Slime.weights(stand);
        w = biomes ? WorldGen.of(seed, Dim.OVERWORLD, type) : null;
    }

    public static List<Result> run(Params p, Progress progress) {
        Slime.check();
        Search a = p.area();
        return new SlimeSearch(a.seed(), a.type(), p.stand(), p.biomes()).search(p, progress);
    }

    private List<Result> search(Params p, Progress progress) {
        Search a = p.area();
        int c0 = Math.floorDiv(a.centerX() - a.radius(), 16), c1 = Math.floorDiv(a.centerX() + a.radius(), 16);
        int d0 = Math.floorDiv(a.centerZ() - a.radius(), 16), d1 = Math.floorDiv(a.centerZ() + a.radius(), 16);
        int tilesX = Math.ceilDiv(c1 - c0 + 1, TILE), tilesZ = Math.ceilDiv(d1 - d0 + 1, TILE);
        long tiles = (long) tilesX * tilesZ;
        // 1. Every chunk corner.
        Best candidates = new Best(REFINE * 2);
        AtomicLong done = new AtomicLong();
        long every = Math.max(1, tiles / 400);
        progress.update(0, "Scoring every chunk");
        Rows rows = new Rows(wt);
        LongStream.range(0, tiles).parallel().forEach(t -> {
            progress.check();
            int i = c0 + (int) (t / tilesZ) * TILE, j = d0 + (int) (t % tilesZ) * TILE;
            for (long[] c : tile(rows, i, j, Math.min(TILE, c1 - i + 1), Math.min(TILE, d1 - j + 1), a, candidates)) candidates.offer(c);
            long d = done.incrementAndGet();
            if (d % every == 0) progress.update(0.75 * d / tiles, "Scoring every chunk");
        });
        List<long[]> best = apart(candidates.list(), 4, REFINE);

        // 2. Every block within 2 chunks of the best corners.
        progress.update(0.75, "Trying every block near the best chunks");
        List<Slime.Spot> refined = new ArrayList<>(best.parallelStream().map(c -> {
            progress.check();
            return refine((int) c[0], (int) c[1], a);
        }).filter(s -> s != null).toList());
        refined.sort((x, y) -> x.blocks() != y.blocks() ? Integer.compare(y.blocks(), x.blocks()) : Long.compare(a.distance(x.x(), x.z()), a.distance(y.x(), y.z())));
        refined = distinct(refined, Math.max(60, p.top() * 3));

        // 3. The biomes at the floor, for the best of them.
        progress.update(0.92, w == null ? "Finishing" : "Checking biomes at the floor");
        List<Result> out = new ArrayList<>(refined.parallelStream().map(s -> {
            progress.check();
            return result(seed, s, stand, w == null ? -1 : lost(s.x(), s.z()), a);
        }).toList());
        out.sort((x, y) -> x.value != y.value ? Double.compare(y.value, x.value) : Long.compare(a.distance(x.x, x.z), a.distance(y.x, y.z)));
        if (out.size() > p.top()) out = new ArrayList<>(out.subList(0, p.top()));
        pick(out, a);
        progress.update(1, out.isEmpty() ? "No slime chunks here" : stand == Slime.Stand.LEVEL
                ? "Best spot " + Slime.cq((int) out.get(0).value) + " cq" : "Best spot " + Fmt.n((long) out.get(0).value) + " spawning spaces at y -39");
        return out;
    }

    /** Row intervals of the cells that count when standing at a chunk corner, for the quick upper bound. */
    private static final class Rows {
        final int[] dz, lo, hi;

        Rows(Slime.Weights wt) {
            List<int[]> r = new ArrayList<>();
            for (int dz = -Slime.REACH; dz <= Slime.REACH; dz++)
                for (int dx = -Slime.REACH; dx <= Slime.REACH; dx++) {
                    if (wt.at(0, 0, dx, dz) == 0) continue;
                    int start = dx;
                    while (dx + 1 <= Slime.REACH && wt.at(0, 0, dx + 1, dz) > 0) dx++;
                    r.add(new int[]{dz, start, dx});
                }
            dz = r.stream().mapToInt(x -> x[0]).toArray();
            lo = r.stream().mapToInt(x -> x[1]).toArray();
            hi = r.stream().mapToInt(x -> x[2]).toArray();
        }
    }

    /** The best candidates of all tiles so far: anything below the worst of them can't make the list. */
    private static final class Best {
        private final PriorityQueue<long[]> heap = new PriorityQueue<>((x, y) -> Long.compare(x[2], y[2]));
        private final int keep;
        volatile long floor;

        Best(int keep) {
            this.keep = keep;
        }

        synchronized void offer(long[] c) {
            if (c[2] <= floor) return;
            heap.add(c);
            if (heap.size() > keep) heap.poll();
            if (heap.size() == keep) floor = heap.peek()[2];
        }

        synchronized List<long[]> list() {
            List<long[]> l = new ArrayList<>(heap);
            l.sort((x, y) -> Long.compare(y[2], x[2]));
            return l;
        }
    }

    /** The best chunk corners of one tile: {chunkX, chunkZ, blocks}, apart. */
    private List<long[]> tile(Rows rows, int i0, int j0, int n, int m, Search a, Best all) {
        int reach = Slime.REACH, s = n + 2 * reach, t = m + 2 * reach, side = Slime.SIDE;
        Slime.Grid g = new Slime.Grid(seed, i0 - reach, j0 - reach, s, t);
        // Slime chunks as prefix sums: along each row, and over rectangles (for a quick 17x17 square count first).
        int[] pre = new int[t * (s + 1)], box = new int[(t + 1) * (s + 1)];
        for (int z = 0; z < t; z++)
            for (int x = 0; x < s; x++) {
                int v = g.slime(i0 - reach + x, j0 - reach + z) ? 1 : 0;
                pre[z * (s + 1) + x + 1] = pre[z * (s + 1) + x] + v;
                box[(z + 1) * (s + 1) + x + 1] = box[z * (s + 1) + x + 1] + pre[z * (s + 1) + x + 1];
            }
        PriorityQueue<long[]> keep = new PriorityQueue<>((x, y) -> Long.compare(x[2], y[2]));
        long floor = 0;
        int k = rows.dz.length;
        for (int j = 0; j < m; j++) {
            long fl = Math.max(floor, all.floor);
            for (int i = 0; i < n; i++) {
                // Every counted chunk is in the 17x17 square around, and each counts at most 256 blocks.
                int sq = box[(j + side) * (s + 1) + i + side] - box[j * (s + 1) + i + side] - box[(j + side) * (s + 1) + i] + box[j * (s + 1) + i];
                if (sq * 256L <= fl) continue;
                int count = 0;
                for (int q = 0; q < k; q++) {
                    int row = (j + reach + rows.dz[q]) * (s + 1) + i + reach;
                    count += pre[row + rows.hi[q] + 1] - pre[row + rows.lo[q]];
                }
                if (count * 256L <= fl) continue;
                int x = (i0 + i) * 16, z = (j0 + j) * 16;
                if (!a.inside(x, z)) continue;
                int b = g.blocks(wt, x, z);
                if (b <= fl) continue;
                if (keep.size() < KEEP) keep.add(new long[]{i0 + i, j0 + j, b});
                else if (b > keep.peek()[2]) {
                    keep.poll();
                    keep.add(new long[]{i0 + i, j0 + j, b});
                }
                if (keep.size() == KEEP) fl = Math.max(fl, floor = keep.peek()[2]);
            }
        }
        List<long[]> list = new ArrayList<>(keep);
        list.sort((x, y) -> Long.compare(y[2], x[2]));
        return apart(list, 4, 32);
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
    static List<Slime.Spot> distinct(List<Slime.Spot> sorted, int n) {
        List<Slime.Spot> out = new ArrayList<>();
        for (Slime.Spot s : sorted) {
            if (out.size() >= n) break;
            if (out.stream().noneMatch(k -> (long) (k.x() - s.x()) * (k.x() - s.x()) + (long) (k.z() - s.z()) * (k.z() - s.z()) <= 16384)) out.add(s);
        }
        return out;
    }

    /** The best block to stand on within R chunks of chunk (cx, cz), inside the area (ties: nearest the centre). */
    private Slime.Spot refine(int cx, int cz, Search a) {
        int x0 = (cx - R) * 16, z0 = (cz - R) * 16, x1 = (cx + R) * 16 + 15, z1 = (cz + R) * 16 + 15;
        Slime.Grid g = Slime.Grid.around(seed, x0, z0, x1, z1);
        int bx = 0, bz = 0, bb = -1;
        long bd = Long.MAX_VALUE;
        for (int z = z0; z <= z1; z++)
            for (int x = x0; x <= x1; x++) {
                if (!a.inside(x, z)) continue;
                int b = g.blocks(wt, x, z);
                if (b < bb) continue;
                long d = a.distance(x, z);
                if (b > bb || d < bd) {
                    bb = b;
                    bd = d;
                    bx = x;
                    bz = z;
                }
            }
        return bb < 0 ? null : new Slime.Spot(bx, bz, bb, g.chunks(wt, bx, bz));
    }

    /** Marks the best result, and the nearest one within 1 cq (256 blocks) of it: usually the one to build. */
    static void pick(List<Result> out, Search a) {
        if (out.isEmpty()) return;
        double best = out.get(0).value;
        int near = 0;
        for (int i = 1; i < out.size(); i++)
            if (out.get(i).value >= best - 256 && a.distance(out.get(i).x, out.get(i).z) < a.distance(out.get(near).x, out.get(near).z)) near = i;
        out.get(0).details.put("Pick", near == 0 ? "best, nearest" : "best");
        if (near != 0) out.get(near).details.put("Pick", "nearest within 1 cq");
    }

    // ------------------------------------------------------------------ results

    /** A result for standing at s; lost: blocks lost to biomes (-1: not checked). */
    static Result result(long seed, Slime.Spot s, Slime.Stand stand, int lost, Search a) {
        Slime.Spot level = stand == Slime.Stand.LEVEL ? s : Slime.score(seed, s.x(), s.z(), Slime.Stand.LEVEL);
        Slime.Spot high = stand == Slime.Stand.Y39 ? s : Slime.score(seed, s.x(), s.z(), Slime.Stand.Y39);
        Result r = new Result();
        r.kind = Result.Kind.SLIME;
        r.dim = Dim.OVERWORLD;
        r.name = "Slime farm";
        r.x = s.x();
        r.y = stand.tpY();
        r.z = s.z();
        int reach = (int) Math.ceil(stand.reach());
        r.minX = s.x() - reach;
        r.maxX = s.x() + reach;
        r.minZ = s.z() - reach;
        r.maxZ = s.z() + reach;
        r.value = s.blocks() - Math.max(0, lost);
        r.size = Fmt.n((long) r.value);
        r.details.put("cq", Slime.cq(level.blocks()));
        r.details.put("Distance", Fmt.n(a.distance(s.x(), s.z())));
        r.details.put("Pick", "");
        r.details.put("At y -39", Fmt.n(high.blocks()));
        r.details.put("Slime chunks", Integer.toString(s.chunks()));
        r.details.put("Packing", level.chunks() == 0 ? "-" : String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * level.blocks() / 256 / level.chunks()));
        if (lost >= 0) r.details.put("Lost to biomes", lost == 0 ? "-" : Fmt.n(lost));
        r.ref = new int[]{s.x(), stand.tpY(), s.z(), stand.ordinal()};
        return r;
    }

    // ------------------------------------------------------------------ biomes

    /** Blocks lost to biomes (deep dark, mushroom fields at the floor) standing at (x, z) in this seed. */
    static int lostToBiomes(long seed, WorldType type, Slime.Stand stand, int x, int z) {
        return new SlimeSearch(seed, type, stand, true).lost(x, z);
    }

    /** A chunk's floor blocks where slimes can spawn by biome: all, none, or the ones in the set (z * 16 + x). */
    private record Mask(BitSet allowed) {}

    private static final Mask ALL = new Mask(null), NONE = new Mask(new BitSet());
    private final Map<Long, Mask> masks = new ConcurrentHashMap<>();
    private static final Map<Holder<Biome>, Boolean> SLIME_BIOME = new ConcurrentHashMap<>();

    /** Counted blocks standing at (x, z) whose biome at the floor has no slimes. */
    private int lost(int x, int z) {
        int n = 0, dy2 = stand.dy * stand.dy, lo = stand.inclusive ? 576 : 577;
        for (int dz = -Slime.REACH; dz <= Slime.REACH; dz++)
            for (int dx = -Slime.REACH; dx <= Slime.REACH; dx++) {
                if (wt.at(x & 15, z & 15, dx, dz) == 0) continue;
                int cx = (x >> 4) + dx, cz = (z >> 4) + dz;
                if (!Slime.fast(seed, cx, cz)) continue;
                Mask m = mask(cx, cz);
                if (m == ALL) continue;
                for (int j = 0; j < 16; j++)
                    for (int i = 0; i < 16; i++) {
                        long a = cx * 16L + i - x, b = cz * 16L + j - z, d2 = a * a + b * b + dy2;
                        if (d2 >= lo && d2 <= 16384 && !m.allowed.get(j * 16 + i)) n++;
                    }
            }
        return n;
    }

    /**
     * Which of a chunk's floor blocks (slimes standing at y -63) are in a biome slimes spawn in, by the game's own block
     * biome lookup (BiomeManager, which picks one of the 8 surrounding 4x4x4 biome cells per block).
     */
    private Mask mask(int cx, int cz) {
        return masks.computeIfAbsent(((long) cx << 32) | (cz & 0xFFFFFFFFL), k -> {
            int y = Slime.FLOOR_Y + 1, qx0 = cx * 4 - 1, qz0 = cz * 4 - 1;
            // A level keeps biome cells below the world at the lowest one (ChunkAccess.getNoiseBiome clamps), so near
            // the bottom every block looks at the y -64 cells.
            int qy0 = Math.max(-16, ((y - 2) >> 2)), qy1 = Math.max(-16, ((y - 2) >> 2) + 1), ny = qy1 - qy0 + 1;
            @SuppressWarnings("unchecked")
            Holder<Biome>[] cells = new Holder[6 * 6 * ny];
            boolean any = false, all = true;
            for (int qx = 0; qx < 6; qx++)
                for (int qz = 0; qz < 6; qz++)
                    for (int qy = qy0; qy <= qy1; qy++) {
                        Holder<Biome> b = w.biome((qx0 + qx) * 4, qy * 4, (qz0 + qz) * 4);
                        cells[(qx * 6 + qz) * ny + qy - qy0] = b;
                        boolean ok = allowsSlimes(b);
                        any |= ok;
                        all &= ok;
                    }
            if (all) return ALL;
            if (!any) return NONE;
            BiomeManager manager = new BiomeManager((qx, qy, qz) ->
                    cells[((qx - qx0) * 6 + qz - qz0) * ny + Math.max(qy0, Math.min(qy1, Math.max(-16, qy))) - qy0],
                    BiomeManager.obfuscateSeed(seed));
            BitSet allowed = new BitSet(256);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    if (allowsSlimes(manager.getBiome(pos.set(cx * 16 + x, y, cz * 16 + z)))) allowed.set(z * 16 + x);
            return new Mask(allowed);
        });
    }

    /** Whether the biome's monster spawn list has slimes (deep dark and mushroom fields don't). */
    private static boolean allowsSlimes(Holder<Biome> b) {
        return SLIME_BIOME.computeIfAbsent(b, h -> h.value().getMobSettings().getMobs(MobCategory.MONSTER).unwrap().stream()
                .anyMatch(e -> e.value().type() == EntityType.SLIME));
    }
}
