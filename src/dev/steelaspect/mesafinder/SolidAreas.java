package dev.steelaspect.mesafinder;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Biggest solid square and rectangle of each biome: every block inside is that biome, no gaps.
 * Squares are found on the coarse grid, then re-checked at 4-block (biome cell) resolution for exact size.
 */
final class SolidAreas {
    private static final int FINE = 4;
    private static final int CANDIDATES = 5;

    /** A solid square: top-left sample cell (row, col) and side in cells. */
    record Square(String biome, int row, int col, int side) {}

    /** A solid rectangle: top-left sample cell and size in cells. */
    record Rect(String biome, int row, int col, int width, int height) {
        long cells() {
            return (long) width * height;
        }
    }

    private SolidAreas() {}

    static void run(String[] grid, int n, int x0, int z0, MesaFinder.Options o, MesaFinder.BiomeAt at, PrintStream out) {
        Map<String, List<Square>> squares = new HashMap<>();
        Map<String, Rect> rects = new HashMap<>();
        for (String b : o.biomes) squares.put(b, new ArrayList<>());
        scan(grid, n, n, squares, rects);

        out.println("Biggest SOLID area of each biome (every block inside is that biome):\n");
        out.printf(Locale.ROOT, "%-18s %-30s %-14s %-30s %s%n", "Biome", "Square: corner (x, z)", "Square size", "Rectangle: corner (x, z)", "Rectangle size");
        List<String[]> rows = new ArrayList<>();
        for (String b : o.biomes) {
            // Re-check the best few squares at biome-cell resolution; keep the biggest exact one.
            int[] best = null; // x, z, side in blocks
            for (Square sq : squares.get(b)) {
                int[] fine = refine(sq, x0, z0, o.step, at);
                if (fine != null && (best == null || fine[2] > best[2])) best = fine;
            }
            Rect r = rects.get(b);
            String sqText = best == null ? "-" : best[0] + ", " + best[1];
            String sqSize = best == null ? "-" : best[2] + "x" + best[2];
            String rText = r == null ? "-" : (x0 + r.col() * o.step) + ", " + (z0 + r.row() * o.step);
            String rSize = r == null ? "-" : "~" + (r.width() * o.step) + "x" + (r.height() * o.step);
            out.printf(Locale.ROOT, "%-18s %-30s %-14s %-30s %s%n", b, sqText, sqSize, rText, rSize);
            if (best != null) rows.add(new String[]{b, Integer.toString(best[2]), best[0] + ", " + best[1]});
        }
        rows.sort((a, c) -> Integer.parseInt(c[1]) - Integer.parseInt(a[1]));
        if (!rows.isEmpty()) {
            String[] w = rows.get(0);
            int side = Integer.parseInt(w[1]);
            String[] c = w[2].split(", ");
            int cx = Integer.parseInt(c[0]) + side / 2, cz = Integer.parseInt(c[1]) + side / 2;
            out.printf(Locale.ROOT, "%nBiggest solid square: %s, %dx%d blocks (%,d blocks area), centre %d, %d%n", w[0], side, side, (long) side * side, cx, cz);
        }
        out.println("\nSquare corner = north-west corner; it runs +x (east) and +z (south) from there. Exact to Minecraft's");
        out.println("4-block biome cells (blocks right on a biome edge can blend by 1-2 blocks). Rectangle sizes are approximate.");
    }

    /** One pass over the grid: largest same-biome squares (dynamic programming) and rectangles (histograms). */
    private static void scan(String[] g, int rowsN, int colsN, Map<String, List<Square>> squares, Map<String, Rect> rects) {
        int[] prev = new int[colsN], cur = new int[colsN];
        int[] height = new int[colsN];
        for (int r = 0; r < rowsN; r++) {
            for (int c = 0; c < colsN; c++) {
                String b = g[r * colsN + c];
                int v = 1;
                if (r > 0 && c > 0 && b == g[(r - 1) * colsN + c] && b == g[r * colsN + c - 1] && b == g[(r - 1) * colsN + c - 1])
                    v = 1 + Math.min(prev[c], Math.min(cur[c - 1], prev[c - 1]));
                cur[c] = v;
                height[c] = r > 0 && b == g[(r - 1) * colsN + c] ? height[c] + 1 : 1;
                List<Square> list = squares.get(b);
                if (list != null) offer(list, new Square(b, r - v + 1, c - v + 1, v));
            }
            rectanglesInRow(g, r, colsN, height, rects);
            int[] t = prev;
            prev = cur;
            cur = t;
        }
    }

    /** Keep the biggest few squares per biome, skipping ones that overlap a bigger one already kept. */
    private static void offer(List<Square> list, Square s) {
        if (list.size() >= CANDIDATES && s.side() <= list.get(list.size() - 1).side()) return;
        for (int i = 0; i < list.size(); i++) {
            Square k = list.get(i);
            boolean overlap = s.row() < k.row() + k.side() && k.row() < s.row() + s.side()
                    && s.col() < k.col() + k.side() && k.col() < s.col() + s.side();
            if (overlap) {
                if (s.side() <= k.side()) return;
                list.remove(i--);
            }
        }
        list.add(s);
        list.sort((a, b) -> b.side() - a.side());
        if (list.size() > CANDIDATES) list.remove(list.size() - 1);
    }

    /** Largest rectangle in each run of same-biome columns of this row, using the column heights. */
    private static void rectanglesInRow(String[] g, int r, int colsN, int[] height, Map<String, Rect> rects) {
        int c = 0;
        while (c < colsN) {
            String b = g[r * colsN + c];
            int end = c;
            while (end + 1 < colsN && g[r * colsN + end + 1] == b) end++;
            if (rects.containsKey(b) || b != null) {
                int[] stack = new int[end - c + 2];
                int top = 0;
                for (int i = c; i <= end + 1; i++) {
                    int h = i <= end ? height[i] : 0;
                    while (top > 0 && height[stack[top - 1]] >= h) {
                        int hh = height[stack[--top]];
                        int left = top > 0 ? stack[top - 1] + 1 : c;
                        int w = i - left;
                        Rect best = rects.get(b);
                        if (best == null || (long) w * hh > best.cells()) rects.put(b, new Rect(b, r - hh + 1, left, w, hh));
                    }
                    if (i <= end) stack[top++] = i;
                }
            }
            c = end + 1;
        }
    }

    /** Re-sample around a coarse square every 4 blocks and return the biggest exact square of that biome: x, z, side. */
    private static int[] refine(Square sq, int x0, int z0, int step, MesaFinder.BiomeAt at) {
        int fx0 = Math.floorDiv(x0 + sq.col() * step - step, FINE) * FINE;
        int fz0 = Math.floorDiv(z0 + sq.row() * step - step, FINE) * FINE;
        int fn = (sq.side() + 2) * step / FINE + 1;
        String[] fine = new String[fn * fn];
        IntStream.range(0, fn).parallel().forEach(r -> {
            for (int c = 0; c < fn; c++) fine[r * fn + c] = at.at(fx0 + c * FINE, fz0 + r * FINE);
        });
        Map<String, List<Square>> one = new HashMap<>();
        one.put(sq.biome(), new ArrayList<>());
        scan(fine, fn, fn, one, new HashMap<>());
        List<Square> found = one.get(sq.biome());
        if (found.isEmpty()) return null;
        Square f = found.get(0);
        return new int[]{fx0 + f.col() * FINE, fz0 + f.row() * FINE, f.side() * FINE};
    }
}
