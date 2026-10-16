package dev.steelaspect.seedfinder.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Biggest SOLID square and rectangle of each biome: every block inside is that biome, no gaps.
 * Found on the coarse grid, then the best few are re-checked every 4 blocks (Minecraft's biome cell size).
 */
public final class SolidSearch {
    private static final int FINE = 4;
    private static final int CANDIDATES = 5;

    public record Params(Search area, Dim dim, int step, List<String> biomes) {}

    /** A solid rectangle on a grid: top-left cell (row, col) and size in cells. A square has width == height. */
    private record Box(String biome, int row, int col, int width, int height) {
        long cells() {
            return (long) width * height;
        }

        boolean overlaps(Box k) {
            return row < k.row + k.height && k.row < row + height && col < k.col + k.width && k.col < col + width;
        }
    }

    private SolidSearch() {}

    public static List<Result> run(Params p, Progress progress) {
        WorldGen world = WorldGen.of(p.area().seed(), p.dim(), p.area().type());
        progress.update(0, "Loading the world generator");
        BiomeSearch.Grid g = BiomeSearch.sample(world, p.area(), p.step(), progress);
        progress.update(0.9, "Finding solid squares");
        Map<String, List<Box>> squares = new HashMap<>(), rects = new HashMap<>();
        for (String b : p.biomes()) {
            squares.put(b, new ArrayList<>());
            rects.put(b, new ArrayList<>());
        }
        scan(g.cells(), g.n(), g.n(), squares, rects);

        List<Result> out = new ArrayList<>();
        int done = 0;
        for (String b : p.biomes()) {
            progress.check();
            progress.update(0.93 + 0.07 * done++ / p.biomes().size(), "Measuring " + Fmt.biome(b) + " exactly");
            int[] sq = null; // x, z, width, height in blocks
            for (Box s : squares.get(b)) sq = bigger(sq, refine(s, g, world, true));
            int[] rect = sq;
            for (Box r : rects.get(b)) rect = bigger(rect, refine(r, g, world, false));
            if (sq != null) out.add(result(Result.Kind.SQUARE, b, sq, p));
            if (rect != null) out.add(result(Result.Kind.RECTANGLE, b, rect, p));
        }
        out.sort((a, c) -> Double.compare(c.value, a.value));
        progress.update(1, "Done");
        return out;
    }

    private static int[] bigger(int[] a, int[] b) {
        if (b == null) return a;
        if (a == null) return b;
        return (long) b[2] * b[3] > (long) a[2] * a[3] ? b : a;
    }

    private static Result result(Result.Kind kind, String biome, int[] box, Params p) {
        Result r = new Result();
        r.kind = kind;
        r.dim = p.dim();
        r.name = biome;
        r.minX = box[0];
        r.minZ = box[1];
        r.maxX = box[0] + box[2] - 1;
        r.maxZ = box[1] + box[3] - 1;
        r.x = box[0] + box[2] / 2;
        r.z = box[1] + box[3] / 2;
        r.value = (double) box[2] * box[3];
        r.size = box[2] + "x" + box[3];
        r.details.put("Shape", kind == Result.Kind.SQUARE ? "Square" : "Rectangle");
        r.details.put("Area", Fmt.n((long) r.value));
        r.details.put("NW corner", box[0] + ", " + box[1]);
        r.details.put("Distance", Fmt.n(p.area().distance(r.x, r.z)));
        r.ref = new int[]{r.minX, r.minZ, r.maxX, r.maxZ};
        r.biomes = new String[]{biome};
        return r;
    }

    /** One pass: largest same-biome squares (dynamic programming) and rectangles (row histograms). */
    private static void scan(String[] g, int rowsN, int colsN, Map<String, List<Box>> squares, Map<String, List<Box>> rects) {
        int[] prev = new int[colsN], cur = new int[colsN], height = new int[colsN];
        for (int r = 0; r < rowsN; r++) {
            for (int c = 0; c < colsN; c++) {
                String b = g[r * colsN + c];
                int v = 1;
                if (r > 0 && c > 0 && b == g[(r - 1) * colsN + c] && b == g[r * colsN + c - 1] && b == g[(r - 1) * colsN + c - 1])
                    v = 1 + Math.min(prev[c], Math.min(cur[c - 1], prev[c - 1]));
                cur[c] = v;
                height[c] = r > 0 && b == g[(r - 1) * colsN + c] ? height[c] + 1 : 1;
                List<Box> list = squares.get(b);
                if (list != null) offer(list, new Box(b, r - v + 1, c - v + 1, v, v));
            }
            if (rects != null) rectanglesInRow(g, r, colsN, height, rects);
            int[] t = prev;
            prev = cur;
            cur = t;
        }
    }

    /** Keep the biggest few per biome, skipping ones that overlap a bigger one already kept. */
    private static void offer(List<Box> list, Box s) {
        if (list.size() >= CANDIDATES && s.cells() <= list.get(list.size() - 1).cells()) return;
        for (int i = 0; i < list.size(); i++) {
            Box k = list.get(i);
            if (s.overlaps(k)) {
                if (s.cells() <= k.cells()) return;
                list.remove(i--);
            }
        }
        list.add(s);
        list.sort((a, b) -> Long.compare(b.cells(), a.cells()));
        if (list.size() > CANDIDATES) list.remove(list.size() - 1);
    }

    /** Largest rectangle in each run of same-biome columns of this row, using the column heights. */
    private static void rectanglesInRow(String[] g, int r, int colsN, int[] height, Map<String, List<Box>> rects) {
        int c = 0;
        while (c < colsN) {
            String b = g[r * colsN + c];
            int end = c;
            while (end + 1 < colsN && g[r * colsN + end + 1] == b) end++;
            List<Box> keep = rects.get(b);
            if (keep != null) {
                int[] stack = new int[end - c + 2];
                int top = 0;
                for (int i = c; i <= end + 1; i++) {
                    int h = i <= end ? height[i] : 0;
                    while (top > 0 && height[stack[top - 1]] >= h) {
                        int hh = height[stack[--top]];
                        int left = top > 0 ? stack[top - 1] + 1 : c;
                        int w = i - left;
                        if (keep.size() < CANDIDATES || (long) w * hh > keep.get(keep.size() - 1).cells())
                            offer(keep, new Box(b, r - hh + 1, left, w, hh));
                    }
                    if (i <= end) stack[top++] = i;
                }
            }
            c = end + 1;
        }
    }

    /** Re-sample around a coarse box every 4 blocks; the biggest exact square/rectangle of that biome: x, z, w, h. */
    private static int[] refine(Box b, BiomeSearch.Grid g, WorldGen world, boolean square) {
        int step = g.step();
        int fx0 = Math.floorDiv(g.x0() + b.col() * step - step, FINE) * FINE;
        int fz0 = Math.floorDiv(g.z0() + b.row() * step - step, FINE) * FINE;
        int cols = (b.width() + 2) * step / FINE + 1, rows = (b.height() + 2) * step / FINE + 1;
        String[] fine = new String[cols * rows];
        IntStream.range(0, rows).parallel().forEach(r -> {
            for (int c = 0; c < cols; c++) fine[r * cols + c] = world.biomeAt(fx0 + c * FINE, fz0 + r * FINE);
        });
        Map<String, List<Box>> found = new HashMap<>();
        found.put(b.biome(), new ArrayList<>());
        if (square) {
            scan(fine, rows, cols, found, null);
        } else {
            int[] height = new int[cols];
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++)
                    height[c] = r > 0 && fine[r * cols + c] == fine[(r - 1) * cols + c] ? height[c] + 1 : 1;
                rectanglesInRow(fine, r, cols, height, found);
            }
        }
        List<Box> list = found.get(b.biome());
        if (list.isEmpty()) return null;
        Box f = list.get(0);
        return new int[]{fx0 + f.col() * FINE, fz0 + f.row() * FINE, f.width() * FINE, f.height() * FINE};
    }
}
