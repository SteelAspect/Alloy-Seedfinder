package dev.steelaspect.seedfinder.engine;

import dev.steelaspect.seedfinder.gpu.Gpu;
import dev.steelaspect.seedfinder.gpu.GpuException;
import dev.steelaspect.seedfinder.gpu.OpenCl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

/**
 * Seeds with the best slime farm at a spot, by kyoubitt's exact phase search (github.com/kyoubitt/slime, AI_CONTEXT
 * "THE PHASE MODEL"). Only the low 48 bits of a seed matter for slime chunks. Split them into family f (bits 0-19),
 * class c (bits 20-34) and top v (bits 35-47). For one (f, c), every chunk's random number moves by the same amount as
 * v changes: B(v) = B(0) + 2^18 w (mod 2^31) with w = M v mod 2^13. So a chunk can only ever be a slime chunk when
 * B(0) is even, it has a phase B(0) mod 5, and it is one exactly when w = phase (mod 5), or phase + 2 once B has
 * wrapped past 2^31 (w >= 8192 - (B(0) >> 18)). The best farm of all 8,192 seeds of a class comes from one sorted walk
 * over its chunks' wrap points, instead of trying 8,192 seeds.
 *
 * <p>Per spot: families are ranked by how many chunks can be slime at all (that depends on the family's low 18 bits
 * only) and the top few percent kept; every class of those is bounded cheaply (chunk counts per phase and quarter of
 * B(0)), and the walk runs only where the bound can reach the best found so far. Every layout found is counted again
 * with Minecraft's slime chunk rule, then the place to stand is refined to the block.
 */
public final class SlimeHunt {
    static final long M = Slime.MULT, MASK48 = Slime.MASK48, CX = Slime.SALT ^ M;
    static final int CXH = (int) (CX >>> 20), M8 = (int) (M << 3), MINV = minv13(), CLASSES = 1 << 15;
    /** Layouts kept while searching; how far the stand point may move when refining (chunks: the best spot is often 3 away). */
    private static final int KEEP = 400, R = 5;

    /**
     * x, z: the spot (blocks). topPct: share of families searched. positions: chunks searched around the spot (1 to 15).
     * climb: instead of hunting, the 8,192 seeds that differ from this one only in bits 35-47 (the toolchain's high-bit
     * climb: nearly the same slime chunks, sometimes a better farm), or null.
     */
    public record Params(int x, int z, Slime.Stand stand, double topPct, int positions, boolean biomes, int top, Long climb) {
        public Params(int x, int z, Slime.Stand stand, double topPct, int positions, boolean biomes, int top) {
            this(x, z, stand, topPct, positions, biomes, top, null);
        }
    }

    private SlimeHunt() {}

    /** M^-1 mod 2^13 (M is odd), to turn w back into the top bits v. */
    private static int minv13() {
        long inv = 1;
        for (int i = 0; i < 6; i++) inv *= 2 - M * inv;
        return (int) (inv & 0x1FFF);
    }

    // ------------------------------------------------------------------ one position

    /** Standing at the corner of chunk (cx, cz): the chunks that count, their offsets in the seed and weights. */
    static final class Position {
        final int cx, cz, n;
        final long[] p;
        final int[] w;
        private int[] possible;

        Position(int cx, int cz, Slime.Stand stand) {
            this.cx = cx;
            this.cz = cz;
            Slime.Weights wt = Slime.weights(stand);
            List<long[]> cells = new ArrayList<>();
            for (int dz = -Slime.REACH; dz <= Slime.REACH; dz++)
                for (int dx = -Slime.REACH; dx <= Slime.REACH; dx++) {
                    int k = wt.at(0, 0, dx, dz);
                    if (k > 0) cells.add(new long[]{Slime.offset(cx + dx, cz + dz) & MASK48, k});
                }
            n = cells.size();
            p = cells.stream().mapToLong(x -> x[0]).toArray();
            w = cells.stream().mapToInt(x -> (int) x[1]).toArray();
        }

        /** How many chunks can be slime at all, for each family's low 18 bits (bits 18-19 don't change it). */
        synchronized int[] possible() {
            if (possible != null) return possible;
            int[] out = new int[1 << 18];
            IntStream.range(0, 1 << 6).parallel().forEach(part -> {
                for (int f = part << 12; f < (part + 1) << 12; f++) {
                    int c = 0;
                    for (int i = 0; i < n; i++) {
                        long u = f + p[i];
                        if (((((u ^ CX) * M + 0xB) >>> 17) & 1) == 0) c++;
                    }
                    out[f] = c;
                }
            });
            return possible = out;
        }
    }

    /** The families to search (top pct by possible chunks, ties kept), richest first. */
    static int[] families(int[] possible, double pct) {
        long[] hist = new long[256];
        for (int v : possible) hist[v]++;
        long want = (long) Math.ceil(pct / 100.0 * possible.length), acc = 0;
        int cut = 0;
        for (int v = 255; v >= 0; v--) {
            acc += hist[v];
            cut = v;
            if (acc >= want) break;
        }
        int c = cut;
        int[] low = IntStream.range(0, possible.length).filter(f -> possible[f] >= c).boxed()
                .sorted((a, b) -> possible[b] != possible[a] ? Integer.compare(possible[b], possible[a]) : Integer.compare(a, b))
                .mapToInt(Integer::intValue).toArray();
        int[] fams = new int[low.length * 4];
        for (int i = 0; i < low.length; i++)
            for (int k = 0; k < 4; k++) fams[i * 4 + k] = low[i] | k << 18;
        return fams;
    }

    // ------------------------------------------------------------------ what's been found

    /** One layout: the seed (48 bits) and its blocks and chunks standing at its position's corner. */
    record Layout(long seed, int cx, int cz, int blocks, int chunks) {}

    /** The best layouts so far; its floor rises as better ones come in, so less needs checking. */
    static class Found {
        private final PriorityQueue<Layout> best = new PriorityQueue<>((a, b) -> Integer.compare(a.blocks, b.blocks));
        private final Set<Long> seeds = new HashSet<>();
        private final int keep;
        volatile int floor;

        Found(int keep, int floor) {
            this.keep = keep;
            this.floor = floor;
        }

        synchronized void offer(Layout l) {
            if (l.blocks < floor || !seeds.add(l.seed)) return;
            best.add(l);
            if (best.size() > keep) seeds.remove(best.poll().seed);
            if (best.size() == keep) floor = Math.max(floor, best.peek().blocks);
        }

        synchronized List<Layout> list() {
            List<Layout> l = new ArrayList<>(best);
            l.sort((a, b) -> Integer.compare(b.blocks, a.blocks));
            return l;
        }
    }

    static long seed(int fam, int cls, int w) {
        long v = ((long) w * MINV) & 0x1FFF;
        return fam | (long) cls << 20 | v << 35;
    }

    // ------------------------------------------------------------------ on the CPU

    /** Every class of one family, as the GPU does it: bound, then the walk where the bound reaches the floor. */
    static void family(Position pos, int fam, Found found, int fixedFloor) {
        family(pos, fam, found, fixedFloor, 0, CLASSES);
    }

    /** Classes [c0, c1) of one family. */
    static void family(Position pos, int fam, Found found, int fixedFloor, int c0, int c1) {
        int n = pos.n;
        int[] kh = new int[n], h0 = new int[n], idx = new int[n], b = new int[n], e = new int[n];
        int m = 0;
        for (int i = 0; i < n; i++) {
            long u = (fam + pos.p[i]) & MASK48;
            int k = (int) (((((u ^ CX) & 0xFFFFF) * M + 0xB) & MASK48) >>> 17);
            if ((k & 1) != 0) continue;
            kh[m] = k;
            h0[m] = (int) (u >>> 20);
            idx[m++] = i;
        }
        int[] nc = new int[5], nw = new int[5];
        for (int c = c0; c < c1; c++) {
            int tb = fixedFloor >= 0 ? fixedFloor : found.floor, tc = (tb + 255) / 256;
            int a0 = 0, a1 = 0, a2 = 0, a3 = 0, a4 = 0;
            for (int k = 0; k < m; k++) {
                int x = (kh[k] + ((h0[k] + c) ^ CXH) * M8) & 0x7FFFFFFF;
                b[k] = x;
                int inc = 1 << ((x >>> 29) << 3);
                switch (x % 5) {
                    case 0 -> a0 += inc;
                    case 1 -> a1 += inc;
                    case 2 -> a2 += inc;
                    case 3 -> a3 += inc;
                    default -> a4 += inc;
                }
            }
            int u = Math.max(Math.max(Math.max(bound(a0, a3), bound(a1, a4)), Math.max(bound(a2, a0), bound(a3, a1))), bound(a4, a2));
            if (u < tc) continue;
            Arrays.fill(nc, 0);
            Arrays.fill(nw, 0);
            for (int k = 0; k < m; k++) {
                int ph = b[k] % 5;
                e[k] = (8192 - (b[k] >>> 18)) << 11 | ph << 8 | idx[k];
                nc[ph]++;
                nw[ph] += pos.w[idx[k]];
            }
            Arrays.sort(e, 0, m);
            int cls = c;
            walk(e, m, nc, nw, pos.w, tb, (w, cnt, blk) -> found.offer(new Layout(seed(fam, cls, w), pos.cx, pos.cz, blk, cnt)));
        }
    }

    /** Upper bound on the chunks of a class for residue r, from phase r's and phase r+3's counts per quarter of B(0). */
    static int bound(int a, int b) {
        int a0 = a & 255, a1 = a >>> 8 & 255, a2 = a >>> 16 & 255, a3 = a >>> 24;
        int b0 = b & 255, b1 = b >>> 8 & 255, b2 = b >>> 16 & 255, b3 = b >>> 24;
        return Math.max(Math.max(a0 + a1 + a2 + a3 + b3, a0 + a1 + a2 + b2 + b3), Math.max(a0 + a1 + b1 + b2 + b3, a0 + b0 + b1 + b2 + b3));
    }

    interface Emit {
        void layout(int w, int chunks, int blocks);
    }

    /**
     * For top values w = r (mod 5) the slime chunks are phase r's not yet wrapped and phase r+3's wrapped ones. Walking
     * the chunks in wrap order, each layout is checked once (at the first w = r of each stretch); those with at least
     * floor blocks are reported.
     */
    static void walk(int[] e, int m, int[] nc, int[] nw, int[] weight, int floor, Emit emit) {
        for (int r = 0; r < 5; r++) {
            int r3 = (r + 3) % 5, cnt = nc[r], blk = nw[r], p = 0, w = r;
            while (true) {
                while (p < m && (e[p] >>> 11) <= w) {
                    int ph = e[p] >>> 8 & 7, wt = weight[e[p] & 255];
                    if (ph == r) {
                        cnt--;
                        blk -= wt;
                    } else if (ph == r3) {
                        cnt++;
                        blk += wt;
                    }
                    p++;
                }
                if (blk >= floor) emit.layout(w, cnt, blk);
                while (p < m && (e[p] >>> 8 & 7) != r && (e[p] >>> 8 & 7) != r3) p++;
                if (p >= m) break;
                int t = e[p] >>> 11, nw2 = t + ((r - t) % 5 + 5) % 5;
                if (nw2 >= 8192) break;
                w = nw2;
            }
        }
    }

    // ------------------------------------------------------------------ on the graphics card

    static final String SOURCE = """
            #define MASK48 0xFFFFFFFFFFFFUL
            #define MULT 0x5DEECE66DUL
            #define CX @CX@UL
            #define CXH @CXH@u
            #define M8 @M8@u
            #define MAXN 240

            /* Upper bound on a class's chunks for one residue: phase r's and phase r+3's counts per quarter (one byte each). */
            inline int bnd(uint a, uint b) {
                int a0 = a & 255u, a1 = (a >> 8) & 255u, a2 = (a >> 16) & 255u, a3 = a >> 24;
                int b0 = b & 255u, b1 = (b >> 8) & 255u, b2 = (b >> 16) & 255u, b3 = b >> 24;
                return max(max(a0 + a1 + a2 + a3 + b3, a0 + a1 + a2 + b2 + b3), max(a0 + a1 + b1 + b2 + b3, a0 + b0 + b1 + b2 + b3));
            }

            /* One work group = 256 classes of one family. The family's chunks are worked out once, into local memory. */
            __kernel __attribute__((reqd_work_group_size(256, 1, 1)))
            void bound(__global const ulong* P, const int n, __global const uint* fams, const ulong base, const int tc,
                       __global uint* queue, __global volatile uint* qn, const uint cap) {
                __local uint KH[MAXN], H0[MAXN];
                __local int count;
                ulong item = base + get_global_id(0);
                uint fam = fams[item >> 15], c = (uint) item & 0x7FFFu;
                int lid = get_local_id(0);
                if (lid == 0) count = 0;
                barrier(CLK_LOCAL_MEM_FENCE);
                for (int i = lid; i < n; i += 256) {
                    ulong u = (fam + P[i]) & MASK48;
                    uint kh = (uint) (((((u ^ CX) & 0xFFFFFUL) * MULT + 0xBUL) & MASK48) >> 17);
                    if ((kh & 1u) == 0u) {
                        int k = atomic_inc(&count);
                        KH[k] = kh;
                        H0[k] = (uint) (u >> 20);
                    }
                }
                barrier(CLK_LOCAL_MEM_FENCE);
                int m = count;
                uint a0 = 0u, a1 = 0u, a2 = 0u, a3 = 0u, a4 = 0u;
                for (int k = 0; k < m; k++) {
                    uint b = (KH[k] + ((H0[k] + c) ^ CXH) * M8) & 0x7FFFFFFFu;
                    uint ph = b % 5u, inc = 1u << ((b >> 29) << 3);
                    a0 += ph == 0u ? inc : 0u;
                    a1 += ph == 1u ? inc : 0u;
                    a2 += ph == 2u ? inc : 0u;
                    a3 += ph == 3u ? inc : 0u;
                    a4 += ph == 4u ? inc : 0u;
                }
                int u = max(max(max(bnd(a0, a3), bnd(a1, a4)), max(bnd(a2, a0), bnd(a3, a1))), bnd(a4, a2));
                if (u >= tc) {
                    uint k = atomic_inc(qn);
                    if (k < cap) queue[k] = (uint) get_global_id(0);
                }
            }

            /* The walk for each queued class: every layout with at least tb blocks goes out as (queue entry, w, chunks, blocks). */
            __kernel void exact(__global const ulong* P, __global const int* W, const int n, __global const uint* fams, const ulong base,
                                __global const uint* queue, const uint nq, const int tb, __global uint* out, __global volatile uint* on,
                                const uint cap) {
                uint g = get_global_id(0);
                if (g >= nq) return;
                uint rel = queue[g];
                ulong item = base + rel;
                uint fam = fams[item >> 15], c = (uint) item & 0x7FFFu;
                uint e[MAXN];
                int m = 0;
                int nc[5] = {0, 0, 0, 0, 0}, nw[5] = {0, 0, 0, 0, 0};
                for (int i = 0; i < n; i++) {
                    ulong u = (fam + P[i]) & MASK48;
                    uint kh = (uint) (((((u ^ CX) & 0xFFFFFUL) * MULT + 0xBUL) & MASK48) >> 17);
                    if (kh & 1u) continue;
                    uint b = (kh + (((uint) (u >> 20) + c) ^ CXH) * M8) & 0x7FFFFFFFu;
                    uint ph = b % 5u;
                    e[m++] = ((8192u - (b >> 18)) << 11) | (ph << 8) | (uint) i;
                    nc[ph]++;
                    nw[ph] += W[i];
                }
                for (int i = 1; i < m; i++) {
                    uint x = e[i];
                    int j = i - 1;
                    while (j >= 0 && e[j] > x) {
                        e[j + 1] = e[j];
                        j--;
                    }
                    e[j + 1] = x;
                }
                for (int r = 0; r < 5; r++) {
                    uint r3 = (uint) ((r + 3) % 5);
                    int cnt = nc[r], blk = nw[r], p = 0, w = r;
                    for (;;) {
                        while (p < m && (int) (e[p] >> 11) <= w) {
                            uint ph = (e[p] >> 8) & 7u;
                            int wt = W[e[p] & 255u];
                            if (ph == (uint) r) { cnt--; blk -= wt; }
                            else if (ph == r3) { cnt++; blk += wt; }
                            p++;
                        }
                        if (blk >= tb) {
                            uint k = atomic_inc(on);
                            if (k < cap) {
                                out[4 * k] = rel;
                                out[4 * k + 1] = (uint) w;
                                out[4 * k + 2] = (uint) cnt;
                                out[4 * k + 3] = (uint) blk;
                            }
                        }
                        while (p < m && ((e[p] >> 8) & 7u) != (uint) r && ((e[p] >> 8) & 7u) != r3) p++;
                        if (p >= m) break;
                        int t = (int) (e[p] >> 11), nw2 = t + (((r - t) % 5) + 5) % 5;
                        if (nw2 >= 8192) break;
                        w = nw2;
                    }
                }
            }
            """.replace("@CX@", Long.toString(CX)).replace("@CXH@", Long.toString(CXH & 0xFFFFFFFFL))
            .replace("@M8@", Long.toString(M8 & 0xFFFFFFFFL));

    /** The hunt kernels on the graphics card, or null (no card, switched off, or its self-check failed). */
    static final class Card implements AutoCloseable {
        private static final int QCAP = 1 << 22, ECAP = 1 << 20;
        private static volatile String problem;
        private final OpenCl cl;
        private final OpenCl.Kernel bound, exact;
        private final OpenCl.Buffer queue, qn, out, on;
        /** Families per run, adapted so each run takes about 40 ms. */
        private int perRun = 1;

        private Card(OpenCl cl, OpenCl.Program program) throws GpuException {
            this.cl = cl;
            bound = program.kernel("bound");
            exact = program.kernel("exact");
            queue = cl.buffer(4L * QCAP);
            qn = cl.buffer(4);
            out = cl.buffer(16L * ECAP);
            on = cl.buffer(4);
        }

        private static OpenCl.Program program;

        static synchronized Card open() {
            if (!GpuWorld.enabled()) return null;
            OpenCl cl = Gpu.get();
            if (cl == null) {
                problem = Gpu.status();
                return null;
            }
            try {
                if (program == null)
                    program = cl.program(SOURCE, "-cl-std=CL1.2", dev.steelaspect.seedfinder.launcher.AppDirs.cache().toPath().resolve("gpu"));
                Card card = new Card(cl, program);
                card.selfCheck();
                return card;
            } catch (Throwable t) {
                problem = t.getMessage() == null ? t.toString() : t.getMessage();
                Mc.ERR.println("Graphics card not used for the slime hunt: " + problem);
                return null;
            }
        }

        static String problem() {
            return problem;
        }

        /** Two families at a fixed floor: the card must find exactly what the CPU finds. */
        private void selfCheck() throws GpuException {
            Position pos = new Position(230, 436, Slime.Stand.LEVEL);
            int[] fams = {0x9E078, 0x12345};
            for (int fam : fams) {
                List<Layout> cpu = new ArrayList<>(), gpu = new ArrayList<>();
                Found a = new Found(Integer.MAX_VALUE, 0) {
                    @Override
                    synchronized void offer(Layout l) {
                        cpu.add(l);
                    }
                };
                family(pos, fam, a, 52 * 256);
                Found b = new Found(Integer.MAX_VALUE, 0) {
                    @Override
                    synchronized void offer(Layout l) {
                        gpu.add(l);
                    }
                };
                try (Run run = new Run(pos, new int[]{fam})) {
                    run.families(0, 1, 52 * 256, b);
                }
                java.util.Comparator<Layout> order = java.util.Comparator.comparingLong(Layout::seed).thenComparingInt(Layout::blocks);
                cpu.sort(order);
                gpu.sort(order);
                if (!cpu.equals(gpu))
                    throw new GpuException("the graphics card's slime hunt differs from the CPU's (" + gpu.size() + " vs " + cpu.size() + " layouts)");
            }
        }

        /** One position's buffers. */
        final class Run implements AutoCloseable {
            final Position pos;
            final int[] fams;
            private final OpenCl.Buffer p, w, f;

            Run(Position pos, int[] fams) throws GpuException {
                this.pos = pos;
                this.fams = fams;
                p = cl.buffer(8L * pos.n);
                p.write(pos.p);
                w = cl.buffer(4L * pos.n);
                w.write(pos.w);
                f = cl.buffer(4L * fams.length);
                f.write(fams);
            }

            /** Families [from, from + count) at this floor (or the found list's own floor, if floor < 0). */
            void families(int from, int count, int floor, Found found) throws GpuException {
                int tb = floor >= 0 ? floor : found.floor;
                long base = (long) from << 15, items = (long) count << 15;
                int[] one = new int[1];
                qn.write(new int[]{0});
                bound.set(0, p);
                bound.set(1, pos.n);
                bound.set(2, f);
                bound.set(3, base);
                bound.set(4, (tb + 255) / 256);
                bound.set(5, queue);
                bound.set(6, qn);
                bound.set(7, QCAP);
                bound.run(items, 256);
                qn.read(one, 1);
                int nq = one[0];
                if (Integer.compareUnsigned(nq, QCAP) > 0) {
                    // Too many classes passed: do this in halves.
                    if (count == 1) throw new GpuException("slime hunt queue overflow");
                    families(from, count / 2, floor, found);
                    families(from + count / 2, count - count / 2, floor, found);
                    return;
                }
                if (nq == 0) return;
                while (true) {
                    on.write(new int[]{0});
                    exact.set(0, p);
                    exact.set(1, w);
                    exact.set(2, pos.n);
                    exact.set(3, f);
                    exact.set(4, base);
                    exact.set(5, queue);
                    exact.set(6, nq);
                    exact.set(7, tb);
                    exact.set(8, out);
                    exact.set(9, on);
                    exact.set(10, ECAP);
                    exact.run((nq + 63L) / 64 * 64, 64);
                    on.read(one, 1);
                    int ne = Math.min(one[0], ECAP);
                    int[] res = new int[4 * ne];
                    if (ne > 0) out.read(res, res.length);
                    for (int k = 0; k < ne; k++) {
                        long item = base + (res[4 * k] & 0xFFFFFFFFL);
                        found.offer(new Layout(seed(fams[(int) (item >> 15)], (int) (item & 0x7FFF), res[4 * k + 1]), pos.cx, pos.cz,
                                res[4 * k + 3], res[4 * k + 2]));
                    }
                    if (one[0] <= ECAP) return;
                    // More layouts than fit: the ones read raised the floor; go again above it.
                    if (floor >= 0 || found.floor <= tb) throw new GpuException("slime hunt layout list overflow");
                    tb = found.floor;
                }
            }

            /** All families, in runs of about 40 ms. */
            void all(Found found, Progress progress, Runnable step) throws GpuException {
                for (int from = 0; from < fams.length; ) {
                    progress.check();
                    int count = Math.min(perRun, fams.length - from);
                    long start = System.nanoTime();
                    families(from, count, -1, found);
                    double ms = (System.nanoTime() - start) / 1e6;
                    if (count == perRun) perRun = (int) Math.max(1, Math.min(4096, count * 40 / Math.max(1.0, ms)));
                    from += count;
                    for (int i = 0; i < count; i++) step.run();
                }
            }

            @Override
            public void close() {
                p.close();
                w.close();
                f.close();
            }
        }

        @Override
        public void close() {
            bound.close();
            exact.close();
            queue.close();
            qn.close();
            out.close();
            on.close();
        }
    }

    // ------------------------------------------------------------------ the search

    /** Which of the chunks that count standing at (x, z) are slime chunks, as text. */
    private static String layoutKey(long seed, int x, int z, Slime.Weights wt) {
        int cx = x >> 4, cz = z >> 4;
        java.util.BitSet b = new java.util.BitSet(Slime.CELLS);
        for (int dz = -Slime.REACH; dz <= Slime.REACH; dz++)
            for (int dx = -Slime.REACH; dx <= Slime.REACH; dx++)
                if (wt.at(x & 15, z & 15, dx, dz) > 0 && Slime.fast(seed, cx + dx, cz + dz)) b.set((dz + Slime.REACH) * Slime.SIDE + dx + Slime.REACH);
        return b.toString();
    }

    private static String best(Found found) {
        List<Layout> l = found.list();
        return l.isEmpty() ? "" : ", best so far " + Slime.cq(l.get(0).blocks()) + " cq";
    }

    public static List<Result> run(Params p, Progress progress) throws GpuException {
        Slime.check();
        int pcx = p.x() >> 4, pcz = p.z() >> 4;
        // Positions: the spot's chunk, or the best of every 3rd chunk within 6 east-west and 3 north-south of it,
        // ranked by how many families can make big farms there (the toolchain's adaptive positions).
        List<Position> positions = new ArrayList<>();
        progress.update(0, "Looking at the spot");
        if (p.positions() <= 1) positions.add(new Position(pcx, pcz, p.stand()));
        else {
            List<Object[]> scored = new ArrayList<>();
            for (int dz = -3; dz <= 3; dz += 3)
                for (int dx = -6; dx <= 6; dx += 3) {
                    progress.check();
                    Position pos = new Position(pcx + dx, pcz + dz, p.stand());
                    int[] poss = pos.possible();
                    int thr = (int) Math.round(150.0 * pos.n / 222), c = 0;
                    for (int v : poss) if (v >= thr) c++;
                    scored.add(new Object[]{pos, c, Math.abs(dx) + Math.abs(dz)});
                }
            scored.sort((a, b) -> (int) b[1] != (int) a[1] ? Integer.compare((int) b[1], (int) a[1]) : Integer.compare((int) a[2], (int) b[2]));
            for (int i = 0; i < Math.min(p.positions(), scored.size()); i++) positions.add((Position) scored.get(i)[0]);
        }

        // A floor to start from (40 chunks' worth): the searched families always have far better farms than that.
        Found found = new Found(KEEP, p.climb() != null ? 0 : 40 * 256);
        Card card = p.climb() != null ? null : Card.open();
        String where = card != null ? "graphics card" : "CPU";
        try {
            for (int k = 0; k < positions.size(); k++) {
                Position pos = positions.get(k);
                if (p.climb() != null) {
                    // One class of one family: all its 8,192 top values at once, on the CPU.
                    family(pos, (int) (p.climb() & 0xFFFFF), found, -1, (int) (p.climb() >>> 20 & 0x7FFF), (int) (p.climb() >>> 20 & 0x7FFF) + 1);
                    // The seed itself too: where a variant only ties it, the list shows your own seed.
                    Slime.Grid own = new Slime.Grid(p.climb(), pos.cx - Slime.REACH, pos.cz - Slime.REACH, Slime.SIDE, Slime.SIDE);
                    Slime.Weights w0 = Slime.weights(p.stand());
                    found.offer(new Layout(p.climb() & MASK48, pos.cx, pos.cz, own.blocks(w0, pos.cx * 16, pos.cz * 16), own.chunks(w0, pos.cx * 16, pos.cz * 16)));
                    progress.update(0.95 * (k + 1) / positions.size(), "Position " + (k + 1) + " of " + positions.size());
                    continue;
                }
                int[] fams = families(pos.possible(), p.topPct());
                double lo = 0.95 * k / positions.size(), span = 0.95 / positions.size();
                String what = "Position " + (k + 1) + " of " + positions.size() + " (" + where + ")";
                AtomicLong done = new AtomicLong();
                long every = Math.max(1, fams.length / 200);
                Runnable step = () -> {
                    long d = done.incrementAndGet();
                    if (d % every == 0)
                        progress.update(lo + span * d / fams.length, what + ": " + Fmt.n(d) + " of " + Fmt.n(fams.length) + " families"
                                + best(found));
                };
                if (card != null) {
                    try (Card.Run run = card.new Run(pos, fams)) {
                        run.all(found, progress, step);
                    }
                } else {
                    IntStream.range(0, fams.length).parallel().forEach(i -> {
                        progress.check();
                        family(pos, fams[i], found, -1);
                        step.run();
                    });
                }
            }
        } finally {
            if (card != null) card.close();
        }

        // Count each layout again with the game's rule, then find the best block to stand on near it.
        progress.update(0.95, "Refining the best farms to the block");
        Slime.Weights wt = Slime.weights(p.stand());
        List<Layout> layouts = found.list();
        List<Object[]> refined = new ArrayList<>(layouts.parallelStream().map(l -> {
            progress.check();
            int mx = l.cx() * 16 + 8, mz = l.cz() * 16 + 8, win = R * 16 + 8;
            Slime.Grid g = Slime.Grid.around(l.seed(), mx - win, mz - win, mx + win, mz + win);
            int corner = g.blocks(wt, l.cx() * 16, l.cz() * 16);
            Slime.Spot s = Slime.best(g, wt, mx, mz, win);
            return new Object[]{l, s, corner};
        }).toList());
        long mismatches = refined.stream().filter(o -> (int) o[2] != ((Layout) o[0]).blocks()).count();
        if (mismatches > 0) Mc.ERR.println("Slime hunt: " + mismatches + " layouts counted differently by the game's rule (kept its count)");
        long own = p.climb() == null ? -1 : p.climb() & MASK48;
        refined.sort((a, b) -> ((Slime.Spot) b[1]).blocks() != ((Slime.Spot) a[1]).blocks()
                ? Integer.compare(((Slime.Spot) b[1]).blocks(), ((Slime.Spot) a[1]).blocks())
                : ((Layout) b[0]).seed() == own || ((Layout) a[0]).seed() == own
                ? Boolean.compare(((Layout) b[0]).seed() == own, ((Layout) a[0]).seed() == own)
                : Long.compare(((Layout) a[0]).seed(), ((Layout) b[0]).seed()));
        // Different seeds can give exactly the same slime chunks around the spot: the same farm, shown once. Their
        // worlds differ though, so with biomes on, the one losing the fewest blocks to biomes is kept.
        java.util.LinkedHashMap<String, List<Object[]>> farms = new java.util.LinkedHashMap<>();
        for (Object[] o : refined) {
            Slime.Spot s = (Slime.Spot) o[1];
            String key = s.x() + "," + s.z() + ":" + layoutKey(((Layout) o[0]).seed(), s.x(), s.z(), wt);
            List<Object[]> same = farms.get(key);
            if (same == null && farms.size() >= 2 * p.top()) continue;
            if (same == null) farms.put(key, same = new ArrayList<>());
            if (same.size() < 4) same.add(o);
        }
        Search at = new Search(0, WorldType.NORMAL, p.x(), p.z(), 0);
        progress.update(0.97, p.biomes() ? "Checking biomes in each seed" : "Finishing");
        List<Result> out = new ArrayList<>(new ArrayList<>(farms.values()).parallelStream().map(same -> {
            progress.check();
            Result best = null;
            for (Object[] o : same) {
                Layout l = (Layout) o[0];
                Slime.Spot s = (Slime.Spot) o[1];
                // A climb keeps the seed's bits 48-63 (they don't change slime chunks).
                long seed = p.climb() != null ? p.climb() & ~MASK48 | l.seed() : l.seed();
                int lost = p.biomes() ? SlimeSearch.lostToBiomes(seed, WorldType.NORMAL, p.stand(), s.x(), s.z()) : -1;
                if (best != null && s.blocks() - Math.max(0, lost) <= best.value) continue;
                Result r = SlimeSearch.result(seed, s, p.stand(), lost, at);
                r.seed = seed;
                java.util.LinkedHashMap<String, String> d = new java.util.LinkedHashMap<>();
                d.put("Seed", Long.toString(seed));
                if (p.climb() != null) d.put("Climb", seed == p.climb() ? "your seed" : "bits 35-47 changed");
                d.putAll(r.details);
                d.remove("Pick");
                // Bits 48-63 of a seed don't change slime chunks: the same farm is in 65,536 seeds (add any multiple of 2^48).
                d.put("Same farm", "65,536 seeds");
                r.details = d;
                best = r;
                if (!p.biomes()) break;
            }
            return best;
        }).toList());
        out.sort((a, b) -> a.value != b.value ? Double.compare(b.value, a.value) : Long.compare(a.seed, b.seed));
        if (out.size() > p.top()) out = new ArrayList<>(out.subList(0, p.top()));
        String cpu = card == null && Card.problem() != null ? " (on the CPU: " + Card.problem() + ")" : "";
        progress.update(1, out.isEmpty() ? "Nothing found" + cpu : p.stand() == Slime.Stand.LEVEL ? "Best: " + out.get(0).details.get("cq") + " cq" + cpu
                : "Best: " + out.get(0).size + " spawning spaces at y -39" + cpu);
        return out;
    }
}
