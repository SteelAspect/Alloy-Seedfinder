package dev.steelaspect.seedfinder.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Biggest connected areas of chosen biomes. The world is checked on a grid (one spot every `step` blocks);
 * touching spots of a wanted biome form one area.
 */
public final class BiomeSearch {
    /** Bigger grids would need more memory than a normal PC has; the step is raised to stay under this. */
    public static final long MAX_CELLS = 250_000_000L;

    /**
     * @param biomes wanted biome ids
     * @param joined true: all wanted biomes join into one area (mesa = 3 kinds); false: each biome is its own area
     * @param minSize smallest area to list, as the side of a square in blocks
     */
    public record Params(Search area, Dim dim, int step, List<String> biomes, boolean joined, int minSize, int top) {}

    /** A sampled grid: row-major biome names (shared strings), cell (0,0) at block (x0, z0). */
    public record Grid(String[] cells, int n, int x0, int z0, int step) {}

    private BiomeSearch() {}

    /** Picks a step so the grid stays within memory. */
    public static int safeStep(int radius, int step) {
        int s = step;
        while ((long) (radius * 2L / s + 1) * (radius * 2L / s + 1) > MAX_CELLS) s *= 2;
        return s;
    }

    public static Grid sample(WorldGen world, Search area, int step, Progress progress) {
        int n = (int) (area.radius() * 2L / step + 1);
        int x0 = area.centerX() - (n / 2) * step, z0 = area.centerZ() - (n / 2) * step;
        String[] cells = new String[n * n];
        AtomicInteger rows = new AtomicInteger();
        IntStream.range(0, n).parallel().forEach(row -> {
            progress.check();
            int z = z0 + row * step;
            for (int col = 0; col < n; col++) cells[row * n + col] = world.biomeAt(x0 + col * step, z);
            int done = rows.incrementAndGet();
            if (done % Math.max(1, n / 100) == 0) progress.update(0.9 * done / n, "Checking biomes");
        });
        return new Grid(cells, n, x0, z0, step);
    }

    public static List<Result> run(Params p, Progress progress) {
        WorldGen world = WorldGen.of(p.area().seed(), p.dim(), p.area().type());
        progress.update(0, "Loading the world generator");
        Grid g = sample(world, p.area(), p.step(), progress);
        progress.update(0.92, "Finding areas");
        List<Result> results = areas(g, p);
        progress.update(1, "Done");
        return results;
    }

    private static List<Result> areas(Grid g, Params p) {
        int n = g.n(), step = g.step();
        String[] cells = g.cells();
        Set<String> wanted = new HashSet<>(p.biomes());
        int[] queue = new int[1024];
        java.util.BitSet seen = new java.util.BitSet(cells.length);
        long minCells = Math.max(1, (long) p.minSize() * p.minSize() / ((long) step * step));
        List<Result> out = new ArrayList<>();
        Map<String, Result> bestOfEach = new HashMap<>();
        for (int i = 0; i < cells.length; i++) {
            if (seen.get(i) || !wanted.contains(cells[i])) continue;
            int head = 0, tail = 0;
            queue[tail++] = i;
            seen.set(i);
            long count = 0, sumX = 0, sumZ = 0;
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            boolean edge = false;
            Map<String, Long> mix = new HashMap<>();
            while (head < tail) {
                int c = queue[head++];
                int row = c / n, col = c % n;
                int x = g.x0() + col * step, z = g.z0() + row * step;
                count++;
                sumX += x;
                sumZ += z;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
                mix.merge(cells[c], 1L, Long::sum);
                if (row == 0 || col == 0 || row == n - 1 || col == n - 1) edge = true;
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        int r2 = row + dr, c2 = col + dc;
                        if (r2 < 0 || c2 < 0 || r2 >= n || c2 >= n) continue;
                        int nb = r2 * n + c2;
                        if (seen.get(nb)) continue;
                        if (p.joined() ? wanted.contains(cells[nb]) : cells[nb] == cells[c]) {
                            seen.set(nb);
                            if (tail == queue.length) queue = Arrays.copyOf(queue, queue.length * 2);
                            queue[tail++] = nb;
                        }
                    }
                }
            }
            if (count < minCells) continue;
            // Centre = the cell closest to the centroid, so the coords are always inside the biome.
            double cx = (double) sumX / count, cz = (double) sumZ / count, best = Double.MAX_VALUE;
            int bx = 0, bz = 0;
            for (int k = 0; k < tail; k++) {
                int c = queue[k];
                int x = g.x0() + (c % n) * step, z = g.z0() + (c / n) * step;
                double d = (x - cx) * (x - cx) + (z - cz) * (z - cz);
                if (d < best) {
                    best = d;
                    bx = x;
                    bz = z;
                }
            }
            Result r = new Result();
            r.kind = Result.Kind.AREA;
            r.dim = p.dim();
            r.name = p.joined() ? String.join("+", p.biomes()) : cells[queue[0]];
            r.x = bx;
            r.z = bz;
            r.minX = minX;
            r.minZ = minZ;
            r.maxX = maxX + step - 1;
            r.maxZ = maxZ + step - 1;
            r.value = (double) count * step * step;
            r.size = Fmt.big(r.value) + (edge ? "+" : "");
            r.details.put("Across", (maxX - minX + step) + "x" + (maxZ - minZ + step));
            r.details.put("Distance", Fmt.n(p.area().distance(bx, bz)));
            if (p.joined() && p.biomes().size() > 1) {
                StringBuilder label = new StringBuilder(), val = new StringBuilder();
                for (String b : p.biomes()) {
                    String kind = b.equals("badlands") ? "plain" : b.equals("eroded_badlands") ? "eroded" : b.equals("wooded_badlands") ? "wooded" : Fmt.biome(b);
                    label.append(label.isEmpty() ? Fmt.biome(kind) : " · " + kind);
                    val.append(val.isEmpty() ? "" : " · ").append(mix.getOrDefault(b, 0L) * 100 / count).append('%');
                }
                r.details.put(label.toString(), val.toString());
            }
            if (!p.joined()) r.details.put("Biome", Fmt.biome(r.name));
            // Verify: the centre plus up to 8 spread-out cells of the area.
            int pts = (int) Math.min(9, tail);
            r.ref = new int[pts * 2];
            r.ref[0] = bx;
            r.ref[1] = bz;
            for (int k = 1; k < pts; k++) {
                int c = queue[(int) ((long) k * tail / pts)];
                r.ref[k * 2] = g.x0() + (c % n) * step;
                r.ref[k * 2 + 1] = g.z0() + (c / n) * step;
            }
            r.biomes = p.joined() ? p.biomes().toArray(String[]::new) : new String[]{r.name};
            out.add(r);
            bestOfEach.merge(r.name, r, (a, b) -> a.value >= b.value ? a : b);
            // Keep memory in check on huge searches with millions of tiny patches.
            if (out.size() > p.top() * 50L + 5000) trim(out, p.top());
        }
        trim(out, p.top());
        // Separate biomes: also keep the biggest of every biome, even if it's not in the top list.
        if (!p.joined()) for (Result r : bestOfEach.values()) if (!out.contains(r)) out.add(r);
        out.sort((a, b) -> Double.compare(b.value, a.value));
        return out;
    }

    private static void trim(List<Result> list, int top) {
        list.sort((a, b) -> Double.compare(b.value, a.value));
        if (list.size() > top) list.subList(top, list.size()).clear();
    }
}
