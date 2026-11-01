package dev.steelaspect.seedfinder.engine;

import dev.steelaspect.seedfinder.gpu.Gpu;
import dev.steelaspect.seedfinder.gpu.GpuException;
import dev.steelaspect.seedfinder.gpu.OpenCl;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * Seeds with a quad ocean monument at 0,0 and a quad witch hut nearby.
 *
 * <p>Where these structures go depends only on the low 48 bits of the seed (the structure seed), and moving a quad
 * from one region corner to another is the same as adding a constant to the seed, so it's enough to look for quads
 * at the corner at 0,0. The graphics card tries every structure seed for 4 monuments that fit in a 16-chunk square
 * there; the CPU keeps the ones where one AFK spot is within 128 blocks of all 4 middles, then looks for a quad hut
 * within a range. Biomes need the full seed: the 65,536 top-bit versions of a hit are each checked with the game's
 * own structure generation (all 4 huts in a swamp, all 4 monuments really generating).
 */
public final class QuadHunt {
    private static final long MASK = (1L << 48) - 1;
    /** Items per graphics card run. */
    private static final long RUN = 1L << 30;
    private static final int CAP = 1 << 16;
    /** Monuments within 16 chunks on both axes can share an AFK spot (2 x 128 blocks). */
    private static final int SPAN = 16;
    private static final int FAR = 128;

    private static final String SOURCE = """
            #define MASK 0xFFFFFFFFFFFFUL
            #define MUL 0x5DEECE66DUL
            inline int nxt(ulong *s) {
                *s = (*s * MUL + 0xBUL) & MASK;
                return (int) (*s >> 17);
            }
            inline int nint(ulong *s, int n) {
                int b = nxt(s), v = b % n;
                while ((uint) b - (uint) v + (uint) (n - 1) > 0x7FFFFFFFu) { b = nxt(s); v = b % n; }
                return v;
            }
            inline int2 pos(ulong seed, long rx, long rz, long salt, int lim, int tri) {
                ulong s = (((ulong) (rx * 341873128712L + rz * 132897987541L + salt) + seed) ^ MUL) & MASK;
                int x, z;
                if (tri) { x = (nint(&s, lim) + nint(&s, lim)) / 2; z = (nint(&s, lim) + nint(&s, lim)) / 2; }
                else { x = nint(&s, lim); z = nint(&s, lim); }
                return (int2) (x, z);
            }
            __kernel void quad(ulong base, long salt, int spacing, int lim, int tri, int span,
                               __global ulong *out, __global uint *n, uint cap) {
                ulong seed = (base + get_global_id(0)) & MASK;
                int minx = 1 << 30, maxx = -(1 << 30), minz = 1 << 30, maxz = -(1 << 30);
                for (int k = 0; k < 4; k++) {
                    int rx = (k & 1) ? -1 : 0, rz = (k & 2) ? -1 : 0;
                    int2 p = pos(seed, rx, rz, salt, lim, tri);
                    int cx = rx * spacing + p.x, cz = rz * spacing + p.y;
                    minx = min(minx, cx); maxx = max(maxx, cx); minz = min(minz, cz); maxz = max(maxz, cz);
                    if (maxx - minx > span || maxz - minz > span) return;
                }
                uint i = atomic_inc(n);
                if (i < cap) out[i] = seed;
            }
            """;

    public record Params(long from, long count, int hutRange, int maxSeeds, PrintStream log) {}

    /** A structure seed with a quad monument at 0,0 and a quad hut whose 2x2 regions start at (hx, hz). */
    public record Hit(long structureSeed, int hx, int hz) {}

    /** A full seed with both quads: the quad hut's region corner (blocks) is about hutX, hutZ. */
    public record Found(long seed, int hutX, int hutZ) {}

    private QuadHunt() {}

    public static List<Found> run(Params p, Progress progress) throws GpuException {
        WorldGen w = WorldGen.of(0, Dim.OVERWORLD, WorldType.NORMAL);
        RandomSpreadStructurePlacement mon = Structures.placement(w, BuiltinStructureSets.OCEAN_MONUMENTS);
        RandomSpreadStructurePlacement hut = Structures.placement(w, BuiltinStructureSets.SWAMP_HUTS);
        // The graphics card if it works (any OpenCL 1.2 card: AMD, NVIDIA, Intel), checked against the game's own
        // placement first; otherwise the same maths on every CPU core (much slower, same results).
        OpenCl cl = GpuWorld.enabled() ? Gpu.get() : null;
        OpenCl.Program program = null;
        if (cl != null) {
            try {
                program = cl.program(SOURCE, "-cl-std=CL1.2", dev.steelaspect.seedfinder.launcher.AppDirs.cache().toPath().resolve("gpu"));
            } catch (GpuException e) {
                Mc.ERR.println("Graphics card not used for the quad hunt: " + e.getMessage());
            }
        }
        if (program == null) return cpuHunt(p, progress, mon, hut);
        try (OpenCl.Kernel k = program.kernel("quad"); OpenCl.Buffer out = cl.buffer(8L * CAP); OpenCl.Buffer n = cl.buffer(4)) {
            try {
                selfCheck(k, out, n, mon, hut);
            } catch (GpuException e) {
                Mc.ERR.println("Graphics card not used for the quad hunt: " + e.getMessage());
                return cpuHunt(p, progress, mon, hut);
            }
            return hunt(p, progress, mon, hut, (base, items, span) -> candidates(k, out, n, mon, base, items, span), cl.device());
        }
    }

    /** The CPU path, after checking its maths against the game's placement code. */
    private static List<Found> cpuHunt(Params p, Progress progress, RandomSpreadStructurePlacement mon, RandomSpreadStructurePlacement hut) throws GpuException {
        long base = 0x9E3779B97F4AL;
        for (RandomSpreadStructurePlacement pl : List.of(mon, hut)) {
            int span = pl == mon ? 22 : 20, slice = 1 << 20;
            long[] ours = cpuCandidates(pl, base, slice, span);
            java.util.Arrays.sort(ours);
            long[] game = IntStream.range(0, slice).parallel().mapToLong(i -> base + i).filter(s -> fits(chunks(pl, s, 0, 0), span)).toArray();
            if (game.length == 0 || !java.util.Arrays.equals(ours, game))
                throw new IllegalStateException("quad hunt maths differs from the game (" + ours.length + " vs " + game.length + ")");
        }
        return hunt(p, progress, mon, hut, (b, items, span) -> cpuCandidates(mon, b, items, span), "CPU");
    }

    /** One batch of structure seeds [base, base + items) whose 4 corner monuments fit in a span x span square. */
    private interface Batch {
        long[] candidates(long base, long items, int span) throws GpuException;
    }

    /** Wall time per batch: short enough that no graphics driver watchdog (about 2 s on Windows) resets the card. */
    private static final double TARGET_SECONDS = 0.04;

    private static List<Found> hunt(Params p, Progress progress, RandomSpreadStructurePlacement mon, RandomSpreadStructurePlacement hut,
                                    Batch batch, String on) throws GpuException {
        List<Found> found = new ArrayList<>();
        ConcurrentLinkedQueue<Hit> hits = new ConcurrentLinkedQueue<>();
        long start = System.nanoTime(), last = start, quads = 0, items = 1L << 20;
        for (long done = 0; done < p.count() && found.size() < p.maxSeeds(); ) {
            progress.check();
            long base = (p.from() + done) & MASK;
            // Whole work groups of 256 (a few seeds past the end of a short run don't matter).
            long size = (Math.min(items, p.count() - done) + 255) / 256 * 256;
            long t0 = System.nanoTime();
            long[] cands = batch.candidates(base, size, SPAN);
            double took = (System.nanoTime() - t0) / 1e9;
            done += size;
            // Next batch sized for about 40 ms on this card (or CPU), whatever it is.
            items = Math.max(1L << 16, Math.min(RUN, (long) (size * Math.min(4, Math.max(0.25, TARGET_SECONDS / Math.max(took, 1e-4))))));
            for (long c : cands) {
                if (!quad(chunks(mon, c, 0, 0), 0)) continue;
                quads++;
                p.log().println("quad monument " + c);
                for (Hit h : huts(hut, c, p.hutRange())) {
                    p.log().printf("structure seed %d: quad monument at 0,0, quad hut regions %d,%d (~%d, %d)%n",
                            c, h.hx, h.hz, (h.hx + 1) * 512, (h.hz + 1) * 512);
                    List<Long> full = biomes(h);
                    p.log().printf("  %d full seeds with all 8 structures: %s%n", full.size(), full.subList(0, Math.min(20, full.size())));
                    for (long seed : full) found.add(new Found(seed, (h.hx + 1) * 512, (h.hz + 1) * 512));
                    hits.add(h);
                }
            }
            double rate = done / ((System.nanoTime() - start) / 1e9);
            progress.update(Math.min(1, (double) done / p.count()), String.format(java.util.Locale.ROOT,
                    "%.2e seeds tried (%.1e/s, %s) · %d quad monuments · %d quad huts near one · %d seeds found", (double) done, rate, on, quads, hits.size(), found.size()));
            long now = System.nanoTime();
            if (now - last > 30e9 || done >= p.count()) {
                last = now;
                p.log().printf("[%s] %.3e seeds (next --from %d), %.2e seeds/s on %s, %d quad monuments, %d hut hits, %d full seeds%n",
                        java.time.LocalTime.now().withNano(0), (double) done, p.from() + done, rate, on, quads, hits.size(), found.size());
            }
        }
        return found;
    }

    /**
     * The graphics card's test in Java: the 4 monuments around 0,0 (Java's Random, as the game seeds it per region) fit in a
     * span x span chunk square. Checked against the game's own placement code at start.
     */
    private static long[] cpuCandidates(RandomSpreadStructurePlacement pl, long base, long items, int span) {
        int salt = salt(pl), spacing = pl.spacing(), lim = spacing - pl.separation();
        boolean tri = pl.spreadType() == net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType.TRIANGULAR;
        return LongStream.range(0, items).parallel().map(i -> (base + i) & MASK).filter(seed -> {
            int minx = Integer.MAX_VALUE, maxx = Integer.MIN_VALUE, minz = Integer.MAX_VALUE, maxz = Integer.MIN_VALUE;
            for (int k = 0; k < 4; k++) {
                int rx = (k & 1) == 1 ? -1 : 0, rz = (k & 2) == 2 ? -1 : 0;
                long[] s = {((rx * 341873128712L + rz * 132897987541L + salt + seed) ^ 0x5DEECE66DL) & MASK};
                int x = tri ? (nint(s, lim) + nint(s, lim)) / 2 : nint(s, lim);
                int z = tri ? (nint(s, lim) + nint(s, lim)) / 2 : nint(s, lim);
                int cx = rx * spacing + x, cz = rz * spacing + z;
                minx = Math.min(minx, cx);
                maxx = Math.max(maxx, cx);
                minz = Math.min(minz, cz);
                maxz = Math.max(maxz, cz);
                if (maxx - minx > span || maxz - minz > span) return false;
            }
            return true;
        }).toArray();
    }

    /** java.util.Random.nextInt(n) for n not a power of 2, on a raw 48-bit state. */
    private static int nint(long[] s, int n) {
        int bits, val;
        do {
            s[0] = (s[0] * 0x5DEECE66DL + 0xBL) & MASK;
            bits = (int) (s[0] >>> 17);
            val = bits % n;
        } while (bits - val + (n - 1) < 0);
        return val;
    }

    /** Structure seeds in [base, base + RUN) whose 4 corner structures fit in a span x span chunk square. */
    private static long[] candidates(OpenCl.Kernel k, OpenCl.Buffer out, OpenCl.Buffer n, RandomSpreadStructurePlacement pl, long base, long items, int span) throws GpuException {
        n.write(new int[]{0});
        k.set(0, base);
        k.set(1, (long) salt(pl));
        k.set(2, pl.spacing());
        k.set(3, pl.spacing() - pl.separation());
        k.set(4, pl.spreadType() == net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType.TRIANGULAR ? 1 : 0);
        k.set(5, span);
        k.set(6, out);
        k.set(7, n);
        k.set(8, CAP);
        k.run(items, 256);
        int[] one = new int[1];
        n.read(one, 1);
        if (one[0] > CAP) throw new GpuException("quad hunt list overflow");
        long[] res = new long[one[0]];
        if (one[0] > 0) out.read(res, one[0]);
        return res;
    }

    /** The placement's salt (protected in the game's class). */
    private static int salt(RandomSpreadStructurePlacement pl) {
        try {
            java.lang.reflect.Method m = net.minecraft.world.level.levelgen.structure.placement.StructurePlacement.class.getDeclaredMethod("salt");
            m.setAccessible(true);
            return (int) m.invoke(pl);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The graphics card must find exactly the seeds the game's own placement code gives. */
    private static void selfCheck(OpenCl.Kernel k, OpenCl.Buffer out, OpenCl.Buffer n, RandomSpreadStructurePlacement mon,
                                  RandomSpreadStructurePlacement hut) throws GpuException {
        long base = 0x9E3779B97F4AL;
        for (RandomSpreadStructurePlacement pl : List.of(mon, hut)) {
            int span = pl == mon ? 22 : 20, slice = 1 << 22;
            long[] gpu = candidates(k, out, n, pl, base, slice, span);
            java.util.Arrays.sort(gpu);
            long[] cpu = IntStream.range(0, slice).parallel().mapToLong(i -> base + i).filter(s -> fits(chunks(pl, s, 0, 0), span)).toArray();
            if (cpu.length == 0 || !java.util.Arrays.equals(cpu, gpu))
                throw new GpuException("graphics card quad check differs from the game (" + gpu.length + " vs " + cpu.length + ")");
        }
    }

    /** Chunk positions of the 4 structures around the region corner whose bottom-right region is (rx, rz). */
    static int[][] chunks(RandomSpreadStructurePlacement pl, long seed, int rx, int rz) {
        int[][] c = new int[4][];
        for (int k = 0; k < 4; k++) {
            ChunkPos ch = Structures.chunkIn(pl, seed, rx - ((k & 1) == 1 ? 1 : 0), rz - ((k & 2) == 2 ? 1 : 0));
            c[k] = new int[]{ch.x, ch.z};
        }
        return c;
    }

    private static boolean fits(int[][] c, int span) {
        int minx = Integer.MAX_VALUE, maxx = Integer.MIN_VALUE, minz = Integer.MAX_VALUE, maxz = Integer.MIN_VALUE;
        for (int[] p : c) {
            minx = Math.min(minx, p[0]);
            maxx = Math.max(maxx, p[0]);
            minz = Math.min(minz, p[1]);
            maxz = Math.max(maxz, p[1]);
        }
        return maxx - minx <= span && maxz - minz <= span;
    }

    /**
     * True if one spot is within 128 blocks (sideways) of all 4 middles. off: where the middle is in its chunk
     * (monument: the chunk's corner, 0; hut: about the middle of its 7 x 9 box, 3.5, with 1 block to spare).
     */
    static boolean quad(int[][] c, double off) {
        double[][] p = new double[4][];
        for (int k = 0; k < 4; k++) p[k] = new double[]{c[k][0] * 16 + off, c[k][1] * 16 + off};
        return shareSpot(p, FAR + (off > 0 ? 1 : 0));
    }

    /** True if one spot is within r blocks (sideways) of every point. */
    static boolean shareSpot(double[][] p, double r) {
        List<double[]> tries = new ArrayList<>();
        for (int i = 0; i < p.length; i++) {
            tries.add(p[i]);
            for (int j = i + 1; j < p.length; j++) {
                double mx = (p[i][0] + p[j][0]) / 2, mz = (p[i][1] + p[j][1]) / 2;
                tries.add(new double[]{mx, mz});
                double dx = p[j][0] - p[i][0], dz = p[j][1] - p[i][1], len = Math.hypot(dx, dz);
                double h2 = r * r - len * len / 4;
                if (h2 < 0 || len == 0) continue;
                double h = Math.sqrt(h2);
                tries.add(new double[]{mx - dz / len * h, mz + dx / len * h});
                tries.add(new double[]{mx + dz / len * h, mz - dx / len * h});
            }
        }
        for (double[] t : tries) {
            boolean all = true;
            for (double[] q : p) all &= Math.hypot(q[0] - t[0], q[1] - t[1]) <= r + 1e-6;
            if (all) return true;
        }
        return false;
    }

    /** Quad huts whose region corner is within range blocks of 0,0. */
    static List<Hit> huts(RandomSpreadStructurePlacement hut, long seed, int range) {
        int size = hut.spacing() * 16, lim = range / size + 1;
        List<Hit> out = new ArrayList<>();
        for (int rx = -lim; rx <= lim; rx++)
            for (int rz = -lim; rz <= lim; rz++) {
                int[][] c = chunks(hut, seed, rx, rz);
                if (fits(c, SPAN + 1) && quad(c, 3.5)) out.add(new Hit(seed, rx - 1, rz - 1));
            }
        return out;
    }

    /** Full seeds (all 65,536 top-bit versions) where all 4 huts and all 4 monuments really generate. */
    public static List<Long> biomes(Hit h) {
        WorldGen any = WorldGen.of(0, Dim.OVERWORLD, WorldType.NORMAL);
        RandomSpreadStructurePlacement monP = Structures.placement(any, BuiltinStructureSets.OCEAN_MONUMENTS);
        RandomSpreadStructurePlacement hutP = Structures.placement(any, BuiltinStructureSets.SWAMP_HUTS);
        Holder<Structure> monS = Structures.structure(any, BuiltinStructures.OCEAN_MONUMENT);
        Holder<Structure> hutS = Structures.structure(any, BuiltinStructures.SWAMP_HUT);
        int[][] huts = chunks(hutP, h.structureSeed, h.hx + 1, h.hz + 1), mons = chunks(monP, h.structureSeed, 0, 0);
        return IntStream.range(0, 1 << 16).parallel().mapToObj(top -> ((long) top << 48) | h.structureSeed).filter(seed -> {
            WorldGen w = WorldGen.once(seed, Dim.OVERWORLD, WorldType.NORMAL);
            for (int[] c : huts) if (Structures.start(w, hutS, new ChunkPos(c[0], c[1]), true) == null) return false;
            for (int[] c : mons) if (Structures.start(w, monS, new ChunkPos(c[0], c[1]), true) == null) return false;
            return true;
        }).sorted().toList();
    }

    /** A low witch hut in one top-bit version of a structure seed: the full seed, its box corner and floor height. */
    public record LowHut(long seed, int x, int y, int z, boolean quadMonument) {}

    /**
     * Low witch huts near 0,0 in every top-bit version of a structure seed where the quad monument at 0,0 still
     * generates. Hut positions are the same in every version; their height (the ground under them, caves carved)
     * isn't. Sorted lowest first.
     */
    public static List<LowHut> lowHuts(long structureSeed, int radius, int maxY, boolean needMonument, Progress progress) {
        WorldGen any = WorldGen.of(0, Dim.OVERWORLD, WorldType.NORMAL);
        RandomSpreadStructurePlacement monP = Structures.placement(any, BuiltinStructureSets.OCEAN_MONUMENTS);
        RandomSpreadStructurePlacement hutP = Structures.placement(any, BuiltinStructureSets.SWAMP_HUTS);
        Holder<Structure> monS = Structures.structure(any, BuiltinStructures.OCEAN_MONUMENT);
        Holder<Structure> hutS = Structures.structure(any, BuiltinStructures.SWAMP_HUT);
        long s48 = structureSeed & MASK;
        int[][] mons = chunks(monP, s48, 0, 0);
        int size = hutP.spacing() * 16, lim = radius / size + 1;
        List<ChunkPos> huts = new ArrayList<>();
        for (int rx = -lim; rx <= lim; rx++)
            for (int rz = -lim; rz <= lim; rz++) {
                ChunkPos c = Structures.chunkIn(hutP, s48, rx, rz);
                if (Math.max(Math.abs(c.getMinBlockX()), Math.abs(c.getMinBlockZ())) <= radius) huts.add(c);
            }
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(), kept = new java.util.concurrent.atomic.AtomicInteger();
        List<LowHut> out = IntStream.range(0, 1 << 16).parallel().mapToObj(top -> {
            progress.check();
            long seed = ((long) top << 48) | s48;
            List<LowHut> low = new ArrayList<>();
            WorldGen w = WorldGen.once(seed, Dim.OVERWORLD, WorldType.NORMAL);
            java.util.function.BooleanSupplier quad = () -> {
                for (int[] c : mons) if (Structures.start(w, monS, new ChunkPos(c[0], c[1]), true) == null) return false;
                return true;
            };
            boolean monuments = !needMonument || quad.getAsBoolean();
            if (monuments) kept.incrementAndGet();
            if (monuments)
                for (ChunkPos c : huts) {
                    StructureStart st = Structures.start(w, hutS, c, true);
                    if (st == null) continue;
                    net.minecraft.world.level.levelgen.structure.BoundingBox b = st.getPieces().get(0).getBoundingBox();
                    if (HutSearch.quickY(w, b) > maxY + 60) continue;
                    int y = HutSearch.exactY(w, b);
                    if (y <= maxY) low.add(new LowHut(seed, b.minX(), y, b.minZ(), needMonument || quad.getAsBoolean()));
                }
            int d = done.incrementAndGet();
            if (d % 2048 == 0) progress.update(d / 65536.0, "Top-bit versions");
            return low;
        }).flatMap(List::stream).sorted(java.util.Comparator.comparingInt(LowHut::y)).toList();
        progress.update(1, out.size() + " low huts in " + kept.get() + " versions (" + huts.size() + " hut spots in range)");
        return out;
    }
}
