package dev.steelaspect.mesafinder;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Finds the biggest areas of a biome (mesa / badlands by default) in a Minecraft 1.21.11 overworld,
 * using the game's own world generator, so the result matches the real world for that seed.
 */
public final class MesaFinder {
    // Captured before Bootstrap wraps System.out with the game logger.
    private static final PrintStream OUT = System.out;
    private static final int SEA_LEVEL = 62;
    private static final int MAX_MAP = 4000;
    private static final List<String> MESA = List.of("badlands", "eroded_badlands", "wooded_badlands");

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && (args[0].equals("huts") || args[0].equals("monuments") || args[0].equals("fortresses"))) {
            StructureFinder.run(args[0], Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        Options o = Options.parse(args);
        if (o == null) return;
        System.setProperty("java.awt.headless", "true");

        OUT.println("Loading the Minecraft 1.21.11 world generator...");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        HolderLookup.Provider lookup = VanillaRegistries.createLookup();

        Set<String> known = new LinkedHashSet<>();
        lookup.lookupOrThrow(Registries.BIOME).listElementIds().forEach(k -> known.add(k.identifier().getPath()));
        for (String b : o.biomes) {
            if (!known.contains(b)) {
                System.err.println("Unknown biome '" + b + "'. Overworld biomes include: " + String.join(", ", known));
                System.exit(2);
            }
        }

        ResourceKey<NoiseGeneratorSettings> worldType = switch (o.worldType) {
            case "large_biomes" -> NoiseGeneratorSettings.LARGE_BIOMES;
            case "amplified" -> NoiseGeneratorSettings.AMPLIFIED;
            case "nether" -> NoiseGeneratorSettings.NETHER;
            default -> NoiseGeneratorSettings.OVERWORLD;
        };
        boolean nether = o.worldType.equals("nether");
        RandomState random = RandomState.create(lookup, worldType, o.seed);
        MultiNoiseBiomeSource source = MultiNoiseBiomeSource.createFromPreset(
                lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                        .getOrThrow(nether ? MultiNoiseBiomeSourceParameterLists.NETHER : MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        // One shared name string per biome, so a huge grid only holds references.
        Map<Holder<Biome>, String> names = new java.util.concurrent.ConcurrentHashMap<>();
        Climate.Sampler sampler = random.sampler();
        DensityFunction surface = random.router().preliminarySurfaceLevel();

        int n = o.radius * 2 / o.step + 1;
        int x0 = o.centerX - (n / 2) * o.step;
        int z0 = o.centerZ - (n / 2) * o.step;
        String[] grid = new String[n * n];

        OUT.printf(Locale.ROOT, "Seed %d, %s world, searching %d blocks around %d, %d (one check every %d blocks, %,d checks)%n",
                o.seed, o.worldName, o.radius, o.centerX, o.centerZ, o.step, (long) n * n);
        long start = System.nanoTime();
        AtomicInteger rowsDone = new AtomicInteger();
        AtomicInteger lastPct = new AtomicInteger(-1);
        IntStream.range(0, n).parallel().forEach(row -> {
            int z = z0 + row * o.step;
            for (int col = 0; col < n; col++) {
                int x = x0 + col * o.step;
                // Biome at the top block you'd see: land surface, or the water surface (sea level) over oceans and rivers.
                // Nether biomes don't change with height.
                int y = nether ? 64 : Math.max(SEA_LEVEL, (int) surface.compute(new DensityFunction.SinglePointContext(x, 0, z)));
                Holder<Biome> biome = source.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), sampler);
                grid[row * n + col] = names.computeIfAbsent(biome, h -> h.unwrapKey().map(k -> k.identifier().getPath()).orElse("unknown"));
            }
            int pct = rowsDone.incrementAndGet() * 100 / n;
            if (pct % 10 == 0 && lastPct.getAndSet(pct) != pct) OUT.print("\r  " + pct + "%");
        });
        OUT.printf(Locale.ROOT, "\r  done in %.1f s%n%n", (System.nanoTime() - start) / 1e9);

        List<Area> areas = findAreas(grid, n, x0, z0, o);
        areas.sort((a, b) -> Long.compare(b.cells, a.cells));
        long minCells = (long) o.minSize * o.minSize / ((long) o.step * o.step);
        areas.removeIf(a -> a.cells < Math.max(1, minCells));

        printTable(areas, o);
        if (o.separate && o.biomes.size() > 1) printBestPerBiome(areas, o);
        if (o.sampleFile != null) writeSamples(grid, n, x0, z0, o);
        if (o.mapFile != null) {
            writeMap(grid, n, x0, z0, areas, o);
            OUT.println("\nMap saved to " + new File(o.mapFile).getAbsolutePath());
        }
    }

    /** One connected patch of matching biome. */
    static final class Area {
        long cells;
        long sumX, sumZ;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        boolean touchesEdge;
        final Map<String, Long> perBiome = new HashMap<>();
        int centerX, centerZ;
        int rank;
    }

    private static List<Area> findAreas(String[] grid, int n, int x0, int z0, Options o) {
        Set<String> wanted = new LinkedHashSet<>(o.biomes);
        int[] label = new int[n * n];
        int[] queue = new int[n * n];
        List<Area> areas = new ArrayList<>();
        for (int i = 0; i < grid.length; i++) {
            if (label[i] != 0 || !wanted.contains(grid[i])) continue;
            Area a = new Area();
            int id = areas.size() + 1;
            int head = 0, tail = 0;
            queue[tail++] = i;
            label[i] = id;
            while (head < tail) {
                int c = queue[head++];
                int row = c / n, col = c % n;
                int x = x0 + col * o.step, z = z0 + row * o.step;
                a.cells++;
                a.sumX += x;
                a.sumZ += z;
                a.minX = Math.min(a.minX, x);
                a.maxX = Math.max(a.maxX, x);
                a.minZ = Math.min(a.minZ, z);
                a.maxZ = Math.max(a.maxZ, z);
                a.perBiome.merge(grid[c], 1L, Long::sum);
                if (row == 0 || col == 0 || row == n - 1 || col == n - 1) a.touchesEdge = true;
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        int r2 = row + dr, c2 = col + dc;
                        if (r2 < 0 || c2 < 0 || r2 >= n || c2 >= n) continue;
                        int nb = r2 * n + c2;
                        if (label[nb] == 0 && (o.separate ? grid[nb].equals(grid[c]) : wanted.contains(grid[nb]))) {
                            label[nb] = id;
                            queue[tail++] = nb;
                        }
                    }
                }
            }
            // Centre = the cell of this area closest to its centroid, so the coords are always inside the biome.
            double cx = (double) a.sumX / a.cells, cz = (double) a.sumZ / a.cells;
            double best = Double.MAX_VALUE;
            for (int k = 0; k < tail; k++) {
                int c = queue[k];
                int x = x0 + (c % n) * o.step, z = z0 + (c / n) * o.step;
                double d = (x - cx) * (x - cx) + (z - cz) * (z - cz);
                if (d < best) {
                    best = d;
                    a.centerX = x;
                    a.centerZ = z;
                }
            }
            areas.add(a);
        }
        return areas;
    }

    private static void printTable(List<Area> areas, Options o) {
        if (areas.isEmpty()) {
            OUT.println("No " + String.join("/", o.biomes) + " areas found. Try a bigger --radius or a smaller --min-size.");
            return;
        }
        int shown = Math.min(o.top, areas.size());
        OUT.printf(Locale.ROOT, "Biggest %s areas (%d found, showing %d):%n%n", String.join(" + ", o.biomes), areas.size(), shown);
        StringBuilder head = new StringBuilder(String.format(Locale.ROOT, "%-3s %-16s %-13s %-12s %-9s", "#", "Centre (x, z)", "Box (x by z)", "Area", "Distance"));
        boolean mixed = o.biomes.size() > 1 && !o.separate;
        if (o.separate) head.append(" Biome");
        if (mixed) for (String b : o.biomes) head.append(String.format(Locale.ROOT, " %-8s", shortName(b)));
        OUT.println(head);
        for (int i = 0; i < shown; i++) {
            Area a = areas.get(i);
            a.rank = i + 1;
            long area = a.cells * o.step * o.step;
            long dist = Math.round(Math.hypot(a.centerX - o.centerX, a.centerZ - o.centerZ));
            String box = (a.maxX - a.minX + o.step) + "x" + (a.maxZ - a.minZ + o.step);
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%-3d %-16s %-13s %-12s %-9s",
                    a.rank, a.centerX + ", " + a.centerZ, box, formatArea(area) + (a.touchesEdge ? "+" : ""), dist));
            if (o.separate) line.append(" ").append(a.perBiome.keySet().iterator().next());
            if (mixed) for (String b : o.biomes) {
                long c = a.perBiome.getOrDefault(b, 0L);
                line.append(String.format(Locale.ROOT, " %-8s", c * 100 / a.cells + "%"));
            }
            OUT.println(line);
        }
        OUT.println("\nArea is in blocks (k = thousand, M = million). '+' = touches the search edge, so it may be even bigger.");
        OUT.println("Teleport: /tp @s <x> ~ <z>   (the centre is always inside the biome)");
    }

    private static void printBestPerBiome(List<Area> areas, Options o) {
        OUT.println("\nBiggest of each biome:");
        for (String b : o.biomes) {
            areas.stream().filter(a -> a.perBiome.containsKey(b)).findFirst().ifPresentOrElse(
                    a -> OUT.printf(Locale.ROOT, "  %-18s %-22s %s%n", b, a.centerX + ", " + a.centerZ, formatArea(a.cells * o.step * o.step) + (a.touchesEdge ? "+" : "")),
                    () -> OUT.printf(Locale.ROOT, "  %-18s none%n", b));
        }
    }

    /** Test helper: random grid points with the biome we predicted, to compare against a real server. */
    private static void writeSamples(String[] grid, int n, int x0, int z0, Options o) throws Exception {
        java.util.Random r = new java.util.Random(o.seed);
        Set<String> wanted = new LinkedHashSet<>(o.biomes);
        try (PrintStream w = new PrintStream(o.sampleFile)) {
            if (o.separate) {
                // Every biome is wanted, so just take random points.
                for (int k = 0; k < o.samples; k++) {
                    int i = r.nextInt(grid.length);
                    w.println((x0 + (i % n) * o.step) + " " + (z0 + (i / n) * o.step) + " " + grid[i]);
                }
                return;
            }
            int mesa = 0, other = 0;
            while (mesa < o.samples / 2 || other < o.samples / 2) {
                int i = r.nextInt(grid.length);
                boolean m = wanted.contains(grid[i]);
                if (m ? mesa++ >= o.samples / 2 : other++ >= o.samples / 2) continue;
                w.println((x0 + (i % n) * o.step) + " " + (z0 + (i / n) * o.step) + " " + grid[i]);
            }
        }
    }

    private static String shortName(String biome) {
        return switch (biome) {
            case "badlands" -> "plain";
            case "eroded_badlands" -> "eroded";
            case "wooded_badlands" -> "wooded";
            default -> biome.length() > 8 ? biome.substring(0, 8) : biome;
        };
    }

    private static String formatArea(long blocks) {
        if (blocks >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", blocks / 1e6);
        if (blocks >= 1_000) return String.format(Locale.ROOT, "%.0fk", blocks / 1e3);
        return Long.toString(blocks);
    }

    private static void writeMap(String[] grid, int n, int x0, int z0, List<Area> areas, Options o) throws Exception {
        // Small searches are blown up to ~1200 px; huge ones are shrunk to at most 4000 px (a match anywhere
        // in a shrunk pixel still shows, so small mesas don't vanish).
        int scale = Math.max(1, 1200 / n);
        int skip = Math.max(1, (n + MAX_MAP - 1) / MAX_MAP);
        int cells = (n + skip - 1) / skip;
        int size = cells * scale;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Set<String> wanted = new LinkedHashSet<>(o.biomes);
        for (int row = 0; row < cells; row++) {
            for (int col = 0; col < cells; col++) {
                String b = grid[(row * skip) * n + col * skip];
                for (int dy = 0; dy < skip && row * skip + dy < n; dy++)
                    for (int dx = 0; dx < skip && col * skip + dx < n; dx++) {
                        String c = grid[(row * skip + dy) * n + col * skip + dx];
                        if (wanted.contains(c)) b = c;
                    }
                int rgb = wanted.contains(b) ? highlight(b) : dim(colour(b));
                for (int dy = 0; dy < scale; dy++)
                    for (int dx = 0; dx < scale; dx++) img.setRGB(col * scale + dx, row * scale + dy, rgb);
            }
        }
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        double px = (double) scale / (o.step * skip); // pixels per block

        // Grid line every 1000 blocks (5000 for big maps), with labels.
        int every = o.radius > 100000 ? 50000 : o.radius > 15000 ? 5000 : 1000;
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        g.setColor(new Color(255, 255, 255, 40));
        for (int b = Math.floorDiv(x0, every) * every; b <= x0 + n * o.step; b += every) {
            int p = (int) ((b - x0) * px);
            g.drawLine(p, 0, p, size);
        }
        for (int b = Math.floorDiv(z0, every) * every; b <= z0 + n * o.step; b += every) {
            int p = (int) ((b - z0) * px);
            g.drawLine(0, p, size, p);
        }

        // Search centre.
        int ox = (int) ((o.centerX - x0) * px), oz = (int) ((o.centerZ - z0) * px);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2));
        g.drawLine(ox - 8, oz, ox + 8, oz);
        g.drawLine(ox, oz - 8, ox, oz + 8);

        // Rank labels.
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        for (Area a : areas) {
            if (a.rank == 0) continue;
            int ax = (int) ((a.centerX - x0) * px), az = (int) ((a.centerZ - z0) * px);
            String t = Integer.toString(a.rank);
            int w = g.getFontMetrics().stringWidth(t);
            g.setColor(Color.BLACK);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) g.drawString(t, ax - w / 2 + dx, az + 6 + dy);
            g.setColor(Color.WHITE);
            g.drawString(t, ax - w / 2, az + 6);
        }

        // Legend.
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        String legend = String.format(Locale.ROOT, "Seed %d  |  %d, %d to %d, %d  |  grid = %d blocks  |  + = search centre  |  north is up",
                o.seed, x0, z0, x0 + (n - 1) * o.step, z0 + (n - 1) * o.step, every);
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRect(0, size - 20, size, 20);
        g.setColor(Color.WHITE);
        g.drawString(legend, 6, size - 6);
        g.dispose();
        ImageIO.write(img, "png", new File(o.mapFile));
    }

    private static int highlight(String b) {
        return switch (b) {
            case "eroded_badlands" -> 0xFF5A1F;
            case "wooded_badlands" -> 0xC9A040;
            case "badlands" -> 0xF08A2C;
            case "nether_wastes" -> 0x8A2A22;
            case "crimson_forest" -> 0xE0342C;
            case "warped_forest" -> 0x1FA59A;
            case "soul_sand_valley" -> 0x9C7A55;
            case "basalt_deltas" -> 0x6E6A70;
            default -> 0xFF3B3B;
        };
    }

    private static int dim(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int grey = (r + g + b) / 3;
        r = (r + grey) / 2 * 55 / 100;
        g = (g + grey) / 2 * 55 / 100;
        b = (b + grey) / 2 * 55 / 100;
        return (r << 16) | (g << 8) | b;
    }

    /** Rough map colour per biome, used for everything that isn't highlighted. */
    private static int colour(String b) {
        if (b.contains("ocean")) return b.contains("deep") ? 0x1E3A8A : 0x2F5FC4;
        if (b.contains("river")) return 0x4A7FE0;
        if (b.contains("beach") || b.contains("shore")) return 0xE8DCA0;
        if (b.contains("desert")) return 0xE3D28A;
        if (b.contains("badlands")) return 0xD07030;
        if (b.contains("savanna")) return 0xB8B04A;
        if (b.contains("jungle") || b.contains("bamboo")) return 0x2F9E2F;
        if (b.contains("mushroom")) return 0xB070B0;
        if (b.contains("swamp")) return 0x4F6B3A;
        if (b.contains("snow") || b.contains("frozen") || b.contains("ice") || b.contains("grove") || b.contains("peaks")) return 0xE8EEF5;
        if (b.contains("taiga")) return 0x3E6E55;
        if (b.contains("cherry")) return 0xF0A8C8;
        if (b.contains("pale")) return 0xA8B0A8;
        if (b.contains("dark_forest")) return 0x2E5A1E;
        if (b.contains("forest") || b.contains("birch")) return 0x3F8F3A;
        if (b.contains("stony") || b.contains("windswept") || b.contains("slopes")) return 0x8A8A8A;
        if (b.contains("meadow") || b.contains("plains")) return 0x7FB84A;
        return 0x6A8A5A;
    }

    static final class Options {
        long seed;
        boolean seedSet;
        int radius = 10000;
        int centerX, centerZ;
        int step = 32;
        int top = 15;
        int minSize = 128;
        List<String> biomes = new ArrayList<>(MESA);
        String worldType = "overworld";
        String worldName = "normal";
        boolean separate;
        boolean biomesSet;
        String mapFile;
        boolean noMap;
        String sampleFile;
        int samples = 60;

        static Options parse(String[] args) {
            Options o = new Options();
            try {
                for (int i = 0; i < args.length; i++) {
                    String a = args[i];
                    switch (a) {
                        case "-h", "--help" -> {
                            help();
                            return null;
                        }
                        case "--seed" -> {
                            o.seed = parseSeed(args[++i]);
                            o.seedSet = true;
                        }
                        case "--radius" -> o.radius = Integer.parseInt(args[++i]);
                        case "--center" -> {
                            String[] p = args[++i].split(",");
                            o.centerX = Integer.parseInt(p[0].trim());
                            o.centerZ = Integer.parseInt(p[1].trim());
                        }
                        case "--step" -> o.step = Integer.parseInt(args[++i]);
                        case "--top" -> o.top = Integer.parseInt(args[++i]);
                        case "--min-size" -> o.minSize = Integer.parseInt(args[++i]);
                        case "--nether" -> {
                            o.worldType = "nether";
                            o.worldName = "nether";
                            o.separate = true;
                        }
                        case "--separate" -> o.separate = true;
                        case "--biome", "--biomes" -> {
                            o.biomesSet = true;
                            o.biomes = new ArrayList<>(Arrays.stream(args[++i].split(","))
                                    .map(s -> s.trim().toLowerCase(Locale.ROOT).replace("minecraft:", "")).filter(s -> !s.isEmpty()).toList());
                        }
                        case "--large-biomes" -> {
                            o.worldType = "large_biomes";
                            o.worldName = "large biomes";
                        }
                        case "--amplified" -> {
                            o.worldType = "amplified";
                            o.worldName = "amplified";
                        }
                        case "--map" -> o.mapFile = args[++i];
                        case "--no-map" -> o.noMap = true;
                        case "--sample" -> o.sampleFile = args[++i];
                        default -> {
                            if (!o.seedSet && !a.startsWith("-")) {
                                o.seed = parseSeed(a);
                                o.seedSet = true;
                            } else throw new IllegalArgumentException("Unknown option " + a);
                        }
                    }
                }
            } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
                System.err.println("Bad arguments: " + e.getMessage());
                help();
                System.exit(2);
            } catch (IllegalArgumentException e) {
                System.err.println(e.getMessage());
                help();
                System.exit(2);
            }
            if (!o.seedSet) {
                help();
                System.exit(2);
            }
            if (o.step < 4 || o.radius < o.step || o.top < 1) {
                System.err.println("--step must be at least 4 and --radius at least --step.");
                System.exit(2);
            }
            if (o.worldType.equals("nether") && !o.biomesSet)
                o.biomes = new ArrayList<>(List.of("nether_wastes", "crimson_forest", "warped_forest", "soul_sand_valley", "basalt_deltas"));
            if (!o.noMap && o.mapFile == null) o.mapFile = (o.worldType.equals("nether") ? "nether-" : "mesa-") + o.seed + ".png";
            if (o.noMap) o.mapFile = null;
            return o;
        }

        /** Same rule as the game's seed box: a number is used as-is, any other text is hashed. */
        static long parseSeed(String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return s.hashCode();
            }
        }

        static void help() {
            OUT.println("""
                    Cytra Mesa Finder - finds the biggest mesa (badlands) areas for a Minecraft 1.21.11 seed.

                    Usage: ./mesafinder <seed> [options]

                      --radius <blocks>     how far to search from the centre (default 10000)
                      --center <x,z>        where to search around (default 0,0)
                      --step <blocks>       check one spot every N blocks; smaller = more exact, slower (default 32)
                      --top <n>             how many areas to list (default 15)
                      --min-size <blocks>   only list areas at least this big, as a square side (e.g. 500 = 500x500, default 128)
                      --biomes <a,b,...>    biomes to look for (default badlands,eroded_badlands,wooded_badlands)
                      --large-biomes        the world uses the Large Biomes world type
                      --amplified           the world uses the Amplified world type
                      --nether              search the nether (default: all 5 nether biomes, each biome its own area)
                      --separate            count each biome as its own area instead of joining them
                      --map <file.png>      where to save the map picture (default mesa-<seed>.png)
                      --no-map              don't save a map picture

                    Examples:
                      ./mesafinder 123456789
                      ./mesafinder 123456789 --radius 30000 --min-size 800
                      ./mesafinder 123456789 --biomes eroded_badlands
                      ./mesafinder 123456789 --biomes mushroom_fields --map mushroom.png
                    """);
        }
    }
}
