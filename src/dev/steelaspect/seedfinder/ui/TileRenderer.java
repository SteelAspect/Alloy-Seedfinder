package dev.steelaspect.seedfinder.ui;

import dev.steelaspect.seedfinder.engine.BiomeColors;
import dev.steelaspect.seedfinder.engine.Dim;
import dev.steelaspect.seedfinder.engine.WorldGen;
import dev.steelaspect.seedfinder.engine.WorldType;

import java.awt.image.BufferedImage;
import java.util.Set;
import java.util.TreeSet;

/**
 * Draws one 256 px map tile. Level L means 2^L blocks per pixel. Zoomed far out, each pixel takes the most common
 * biome of its 3x3 neighbourhood, so big areas read as shapes instead of noise.
 */
public final class TileRenderer {
    public static final int TILE = 256;
    /** A rough tile has one sample every this many pixels. */
    public static final int ROUGH = 8;

    /** What a map shows: the seed's world plus which biomes are highlighted (the rest greyed). */
    public record Spec(long seed, Dim dim, WorldType type, Set<String> highlight) {
        /** Stable name for the disk cache. */
        public String id() {
            String h = highlight.isEmpty() ? "all" : Integer.toHexString(String.join(",", new TreeSet<>(highlight)).hashCode());
            return seed + "-" + type.name().toLowerCase() + "-" + dim.name().toLowerCase() + "-" + h;
        }
    }

    private TileRenderer() {}

    /**
     * @param rough quick preview: one sample per 8 px, returned as a small image (draw it scaled up)
     * @param exact read biomes at the real surface height (slow; the map normally uses the fixed height)
     */
    public static BufferedImage render(Spec v, int lvl, long tx, long tz, boolean rough, boolean exact) {
        WorldGen world = WorldGen.of(v.seed(), v.dim(), v.type());
        double bpp = 1 << lvl;
        int res = rough ? ROUGH : (int) Math.max(1, 4 / bpp);
        int n = TILE / res, pad = !rough && bpp >= 32 ? 1 : 0, m = n + pad * 2;
        long ox = (long) (tx * TILE * bpp), oz = (long) (tz * TILE * bpp);
        double cell = res * bpp;
        String[] grid = new String[m * m];
        for (int r = 0; r < m; r++)
            for (int c = 0; c < m; c++) {
                long bx = ox + (long) ((c - pad + 0.5) * cell), bz = oz + (long) ((r - pad + 0.5) * cell);
                if (Math.abs(bx) > 30_000_000L || Math.abs(bz) > 30_000_000L) continue;
                grid[r * m + c] = exact ? world.biomeAt((int) bx, (int) bz) : world.mapBiomeAt((int) bx, (int) bz);
            }
        // Rough tiles stay small (one pixel per sample); full tiles are 256 px.
        int scale = rough ? 1 : res;
        BufferedImage img = new BufferedImage(n * scale, n * scale, BufferedImage.TYPE_INT_RGB);
        for (int r = 0; r < n; r++)
            for (int c = 0; c < n; c++) {
                String biome = pad == 0 ? grid[r * m + c] : majority(grid, m, r + 1, c + 1);
                if (biome == null) continue;
                int rgb = color(v.highlight(), biome);
                for (int y = r * scale; y < r * scale + scale; y++) for (int x = c * scale; x < c * scale + scale; x++) img.setRGB(x, y, rgb);
            }
        return img;
    }

    static int color(Set<String> highlight, String biome) {
        int rgb = BiomeColors.rgb(biome);
        return highlight.isEmpty() ? shade(rgb) : highlight.contains(biome) ? rgb : grey(rgb);
    }

    /** The most common biome in the 3x3 cells around (r, c); the centre wins ties. */
    private static String majority(String[] g, int m, int r, int c) {
        String centre = g[r * m + c], best = centre;
        int bestCount = 0;
        for (int i = -1; i <= 1; i++)
            for (int j = -1; j <= 1; j++) {
                String b = g[(r + i) * m + c + j];
                if (b == null) continue;
                int count = 0;
                for (int k = -1; k <= 1; k++) for (int l = -1; l <= 1; l++) if (g[(r + k) * m + c + l] == b) count++;
                if (count > bestCount || (count == bestCount && b == centre)) {
                    best = b;
                    bestCount = count;
                }
            }
        return best;
    }

    /** Other biomes when some are highlighted: half grey, dark. */
    private static int grey(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int avg = (r + g + b) / 3;
        return (((r + avg) / 2 * 45 / 100) << 16) | (((g + avg) / 2 * 45 / 100) << 8) | ((b + avg) / 2 * 45 / 100);
    }

    /** Biome colours a bit darker, so pins and outlines stand out. */
    private static int shade(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return ((r * 78 / 100) << 16) | ((g * 78 / 100) << 8) | (b * 78 / 100);
    }
}
