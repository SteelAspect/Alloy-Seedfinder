package dev.steelaspect.seedfinder.engine;

import dev.steelaspect.seedfinder.gpu.Gpu;
import dev.steelaspect.seedfinder.gpu.GpuException;
import dev.steelaspect.seedfinder.gpu.OpenCl;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minecraft's biome generation for one world on the graphics card (see GpuCode). Before it's used, a sample of
 * spots in the world is checked against the game's own code (WorldGen on the CPU); if any differs, this world stays
 * on the CPU. Spots where two biomes are exactly as close are left out of that check: there the game itself answers
 * by the order it happened to look things up in (Climate.RTree keeps the last answer per thread), and the GPU always
 * answers like a fresh game thread.
 */
public final class GpuWorld {
    /** Biome reading: at a fixed height; at the surface (WorldGen.biomeAt); or the map's way (WorldGen.mapBiomeAt). */
    public static final int FIXED = 0, SURFACE = 1, MAP = 2;
    /**
     * Work items per run, worked out as it goes so each run takes about RUN_MS: one long run can make the graphics
     * driver reset the card (Windows does after 2 seconds), and short runs keep the desktop smooth.
     */
    private static final double RUN_MS = 40;
    private static final int MIN_RUN = 1 << 12, MAX_RUN = 1 << 20;
    private final int[] runSize = {1 << 15, 1 << 15, 1 << 15};
    private static final GpuWorld NONE = new GpuWorld();
    private static final Map<WorldGen, GpuWorld> WORLDS = new ConcurrentHashMap<>();
    private static final Map<String, OpenCl.Program> PROGRAMS = new ConcurrentHashMap<>();
    private static volatile boolean enabled = true;
    private static volatile String lastProblem;

    private final WorldGen w;
    private final GpuCode code;
    private final OpenCl.Program program;
    private final OpenCl.Kernel biomes, targets, surfaces;
    private final OpenCl.Buffer perm, offsets, ranges, links, cave;
    private final String[] names;
    private OpenCl.Buffer out;
    /** How the self-check went, e.g. "4096 spots, all the same as Minecraft". */
    public final String check;

    private GpuWorld() {
        w = null;
        code = null;
        program = null;
        biomes = targets = surfaces = null;
        perm = offsets = ranges = links = cave = null;
        names = null;
        check = null;
    }

    private GpuWorld(WorldGen w, OpenCl cl) throws GpuException, ReflectiveOperationException {
        this.w = w;
        code = new GpuCode(w);
        OpenCl.Program program = PROGRAMS.get(code.source);
        if (program == null) {
            synchronized (PROGRAMS) {
                program = PROGRAMS.get(code.source);
                if (program == null) {
                    program = cl.program(code.source, "-cl-std=CL1.2", dev.steelaspect.seedfinder.launcher.AppDirs.cache().toPath().resolve("gpu"));
                    PROGRAMS.put(code.source, program);
                }
            }
        }
        this.program = program;
        biomes = program.kernel("biomes");
        targets = program.kernel("targets");
        perm = upload(cl, code.perm);
        offsets = cl.buffer(8L * code.offsets.length);
        offsets.write(code.offsets);
        ranges = cl.buffer(8L * code.treeRanges.length);
        ranges.write(code.treeRanges);
        links = cl.buffer(4L * code.treeLinks.length);
        links.write(code.treeLinks);
        names = new String[code.leaves.size()];
        byte[] caves = new byte[names.length];
        for (int i = 0; i < names.length; i++) {
            names[i] = w.name(code.leaves.get(i));
            caves[i] = (byte) (WorldGen.isCaveBiome(names[i]) ? 1 : 0);
        }
        cave = upload(cl, caves);
        surfaces = code.hasSurface ? program.kernel("surfaces") : null;
        // seedfinder.gpu.nocheck: tests only (they compare far more spots themselves).
        check = Boolean.getBoolean("seedfinder.gpu.nocheck") ? "not checked" : selfCheck();
    }

    private static OpenCl.Buffer upload(OpenCl cl, byte[] data) throws GpuException {
        OpenCl.Buffer b = cl.buffer(data.length);
        b.write(data);
        return b;
    }

    /** Turns the graphics card on or off for searches and the map (the Settings switch and the Light speed). */
    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean enabled() {
        return enabled;
    }

    private static volatile boolean used;

    /** Whether any grid was worked out on the graphics card in this run. */
    public static boolean used() {
        return used;
    }

    /** Why the GPU isn't used for the last world that tried, or null. */
    public static String lastProblem() {
        return lastProblem;
    }

    /** For a tester: builds the card's world generator for this seed (overworld and nether) and reports its self-checks. */
    public static String selfTest(long seed) {
        StringBuilder b = new StringBuilder();
        for (Dim d : new Dim[]{Dim.OVERWORLD, Dim.NETHER}) {
            GpuWorld g = of(WorldGen.of(seed, d, WorldType.NORMAL));
            if (b.length() > 0) b.append(" · ");
            b.append(d).append(": ").append(g == null ? "FAILED: " + lastProblem : g.check);
        }
        String r = b.toString();
        return (r.contains("FAILED") ? "" : "ok · ") + r;
    }

    /** The GPU version of this world, or null (no card, switched off, End, or the self-check found a difference). */
    public static GpuWorld of(WorldGen w) {
        if (!enabled || w.dim == Dim.END) return null;
        GpuWorld g = WORLDS.computeIfAbsent(w, k -> {
            OpenCl cl = Gpu.get();
            if (cl == null) {
                lastProblem = Gpu.status();
                return NONE;
            }
            try {
                return new GpuWorld(k, cl);
            } catch (Throwable t) {
                lastProblem = t.getMessage() == null ? t.toString() : t.getMessage();
                Mc.ERR.println("Graphics card not used for " + k.dim + " seed " + k.seed + ": " + lastProblem);
                return NONE;
            }
        });
        return g == NONE ? null : g;
    }

    /**
     * Biomes of a grid of blocks (x0 + i * step, z0 + j * step), row by row along x, as WorldGen's shared names.
     * qy: the quart height for FIXED.
     */
    public String[] grid(int mode, int x0, int z0, int step, int nx, int nz, int qy) throws GpuException {
        int[] leaves = leaves(mode, x0, z0, step, nx, nz, qy);
        used = true;
        String[] out = new String[leaves.length];
        for (int i = 0; i < leaves.length; i++) out[i] = names[leaves[i]];
        return out;
    }

    /** The parameter tree leaf (biome) of each grid spot. */
    public synchronized int[] leaves(int mode, int x0, int z0, int step, int nx, int nz, int qy) throws GpuException {
        if (mode == MAP) qy = WorldGen.MAP_Y >> 2; // the map always tries y 64 first
        long total = (long) nx * nz;
        if (total > Integer.MAX_VALUE) throw new GpuException("grid too big");
        int[] result = new int[(int) total];
        // In bands of whole rows, so each run is one plain grid.
        int[] band = null;
        for (int row = 0; row < nz; ) {
            int rows = Math.max(1, Math.min(nz - row, runSize[mode] / Math.max(1, nx))), n = rows * nx;
            long start = System.nanoTime();
            if (out == null || out.size() < 4L * n) {
                if (out != null) out.close();
                out = Gpu.get().buffer(4L * Math.max(n, 1 << 16));
            }
            biomes.set(0, perm);
            biomes.set(1, offsets);
            biomes.set(2, ranges);
            biomes.set(3, links);
            biomes.set(4, cave);
            biomes.set(5, mode);
            biomes.set(6, x0);
            biomes.set(7, z0 + row * step);
            biomes.set(8, step);
            biomes.set(9, nx);
            biomes.set(10, qy);
            biomes.set(11, n);
            biomes.set(12, out);
            biomes.run(n);
            if (band == null || band.length < n) band = new int[n];
            out.read(band, n);
            System.arraycopy(band, 0, result, row * nx, n);
            row += rows;
            double ms = (System.nanoTime() - start) / 1e6;
            if (n >= runSize[mode] / 2 || ms > RUN_MS)
                runSize[mode] = (int) Math.max(MIN_RUN, Math.min(MAX_RUN, n * RUN_MS / Math.max(0.5, ms)));
        }
        // Exact ties: the game's own lookup, the way chunk generation settles them (ClimateTree.inGame).
        for (int i = 0; i < result.length; i++) {
            if (result[i] >= 0) continue;
            int x = x0 + (i % nx) * step, z = z0 + (i / nx) * step;
            Holder<Biome> b = switch (mode) {
                case SURFACE -> w.biome(x, w.surfaceY(x, z), z);
                case MAP -> {
                    Holder<Biome> h = w.biome(x, WorldGen.MAP_Y, z);
                    yield WorldGen.isCaveBiome(w.name(h)) ? w.biome(x, w.surfaceY(x, z), z) : h;
                }
                default -> w.biome(x, qy * 4, z);
            };
            result[i] = leafOf(b);
        }
        return result;
    }

    private final Map<Holder<Biome>, Integer> leafIndex = new ConcurrentHashMap<>();

    /** A leaf with this biome (several leaves can share a biome; any gives the same name). */
    private int leafOf(Holder<Biome> b) {
        return leafIndex.computeIfAbsent(b, k -> code.leaves.indexOf(k));
    }

    /** The game's climate values (Climate.TargetPoint, 7 longs each) at quart positions: for checking. */
    synchronized long[] targets(int[] quartXyz) throws GpuException {
        int n = quartXyz.length / 3;
        OpenCl cl = Gpu.get();
        try (OpenCl.Buffer in = cl.buffer(4L * quartXyz.length); OpenCl.Buffer res = cl.buffer(8L * 7 * n)) {
            in.write(quartXyz);
            targets.set(0, perm);
            targets.set(1, offsets);
            targets.set(2, in);
            targets.set(3, n);
            targets.set(4, res);
            targets.run(n);
            long[] t = new long[7 * n];
            res.read(t, t.length);
            return t;
        }
    }

    /** The six climate density values (before rounding) at quart positions: for checking. */
    synchronized double[] raw(int[] quartXyz) throws GpuException {
        int n = quartXyz.length / 3;
        OpenCl cl = Gpu.get();
        try (OpenCl.Kernel k = program.kernel("raw"); OpenCl.Buffer in = cl.buffer(4L * quartXyz.length); OpenCl.Buffer res = cl.buffer(8L * 6 * n)) {
            in.write(quartXyz);
            k.set(0, perm);
            k.set(1, offsets);
            k.set(2, in);
            k.set(3, n);
            k.set(4, res);
            k.run(n);
            double[] d = new double[6 * n];
            res.read(d, d.length);
            return d;
        }
    }

    /** The parameter tree search alone, on given climate values (7 longs each): for checking. */
    synchronized int[] search(long[] values) throws GpuException {
        int n = values.length / 7;
        OpenCl cl = Gpu.get();
        try (OpenCl.Kernel k = program.kernel("search"); OpenCl.Buffer in = cl.buffer(8L * values.length); OpenCl.Buffer res = cl.buffer(4L * n)) {
            in.write(values);
            k.set(0, ranges);
            k.set(1, links);
            k.set(2, in);
            k.set(3, n);
            k.set(4, res);
            k.run(n);
            int[] d = new int[n];
            res.read(d, n);
            return d;
        }
    }

    /** The preliminary surface level at block (x, z) pairs: for checking. */
    synchronized double[] surfaces(int[] xz) throws GpuException {
        int n = xz.length / 2;
        OpenCl cl = Gpu.get();
        try (OpenCl.Buffer in = cl.buffer(4L * xz.length); OpenCl.Buffer res = cl.buffer(8L * n)) {
            in.write(xz);
            surfaces.set(0, perm);
            surfaces.set(1, offsets);
            surfaces.set(2, in);
            surfaces.set(3, n);
            surfaces.set(4, res);
            surfaces.run(n);
            double[] d = new double[n];
            res.read(d, n);
            return d;
        }
    }

    // ------------------------------------------------------------------ the check

    /**
     * Compares random spots (surface biomes, map biomes and fixed heights from the bottom to the top of the world)
     * with the game's own code. Throws if any differs.
     */
    private String selfCheck() throws GpuException {
        Random r = new Random(w.seed ^ 0x5EEDF1DL);
        int n = 64, checked = 0;
        int x0 = r.nextInt(2_000_000) - 1_000_000, z0 = r.nextInt(2_000_000) - 1_000_000;
        int[] modes = w.dim == Dim.OVERWORLD ? new int[]{SURFACE, MAP, FIXED, FIXED} : new int[]{FIXED};
        int[] heights = {0, 0, -14, 30};
        for (int m = 0; m < modes.length; m++) {
            int step = 4 + 52 * m, qy = w.dim == Dim.OVERWORLD ? heights[m] : 16;
            String[] gpu = grid(modes[m], x0, z0, step, n, n, qy);
            for (int j = 0; j < n; j++)
                for (int i = 0; i < n; i++) {
                    int x = x0 + i * step, z = z0 + j * step;
                    String cpu = switch (modes[m]) {
                        case SURFACE -> w.biomeAt(x, z);
                        case MAP -> w.mapBiomeAt(x, z);
                        default -> w.name(w.biome(x, qy * 4, z));
                    };
                    if (!cpu.equals(gpu[j * n + i]))
                        throw new GpuException("the graphics card's biome at " + x + ", " + z + " (" + gpu[j * n + i] + ") differs from Minecraft's (" + cpu + ")");
                    checked++;
                }
        }
        return checked + " spots, all the same as Minecraft";
    }

}
