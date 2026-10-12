package dev.steelaspect.mesafinder;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Finds witch huts (with their real generated height) and multi ocean monument spots for a 1.21.11 seed,
 * using the game's own structure placement and terrain code.
 */
public final class StructureFinder {
    private static final PrintStream OUT = System.out;
    /** Monuments further apart than this (centre to centre) can't share one AFK spot. */
    private static final int MONUMENT_PAIR_MAX = 340;
    private static final double AFK_RANGE = 128;
    /** A hut whose 9-spot guess is within this of the limit gets its exact height worked out. */
    private static final int QUICK_MARGIN = 60;
    private static final int[] PREFILTER_Y = {64, 96, 32, 0, -32, -60};

    record Hut(int x, int y, int z, int minX, int minZ, int maxX, int maxZ) {}

    record Monument(int x, int z, BoundingBox box) {}

    record Cluster(List<Monument> monuments, int afkX, int afkY, int afkZ, double[] cover, double score) {}

    private final Options o;
    private final HolderLookup.Provider lookup;
    private final RandomState random;
    private final NoiseBasedChunkGenerator generator;
    private final LevelHeightAccessor heights = LevelHeightAccessor.create(-64, 384);

    private StructureFinder(Options o) {
        this.o = o;
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        lookup = VanillaRegistries.createLookup();
        bindBiomeTags(lookup);
        ResourceKey<NoiseGeneratorSettings> type = switch (o.worldType) {
            case "large_biomes" -> NoiseGeneratorSettings.LARGE_BIOMES;
            case "amplified" -> NoiseGeneratorSettings.AMPLIFIED;
            default -> NoiseGeneratorSettings.OVERWORLD;
        };
        random = RandomState.create(lookup, type, o.seed);
        MultiNoiseBiomeSource source = MultiNoiseBiomeSource.createFromPreset(
                lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                        .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        generator = new NoiseBasedChunkGenerator(source, lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(type));
    }

    public static void run(String mode, String[] args) {
        Options o = Options.parse(mode, args);
        if (o == null) return;
        OUT.println("Loading the Minecraft 1.21.11 world generator...");
        StructureFinder f = new StructureFinder(o);
        if (mode.equals("huts")) f.huts();
        else f.monuments();
    }

    // ---------------------------------------------------------------- shared

    /**
     * The code-built registries have no tags (those come from data files), but structure biome checks need them.
     * Reads data/minecraft/tags/worldgen/biome from the game jar and binds them to the biome holders.
     */
    private static void bindBiomeTags(HolderLookup.Provider lookup) {
        String prefix = "data/minecraft/tags/worldgen/biome/";
        Map<String, List<String>> raw = new HashMap<>();
        try (ZipFile jar = new ZipFile(new File(Biome.class.getProtectionDomain().getCodeSource().getLocation().toURI()))) {
            for (ZipEntry e : Collections.list(jar.entries())) {
                if (!e.getName().startsWith(prefix) || !e.getName().endsWith(".json")) continue;
                String tag = "minecraft:" + e.getName().substring(prefix.length(), e.getName().length() - 5);
                try (Reader r = new InputStreamReader(jar.getInputStream(e), StandardCharsets.UTF_8)) {
                    List<String> values = new ArrayList<>();
                    for (JsonElement v : JsonParser.parseReader(r).getAsJsonObject().getAsJsonArray("values"))
                        values.add(v.isJsonObject() ? v.getAsJsonObject().get("id").getAsString() : v.getAsString());
                    raw.put(tag, values);
                }
            }
            Map<String, Set<String>> resolved = new HashMap<>();
            for (String tag : raw.keySet()) resolveTag(tag, raw, resolved, new HashSet<>());
            Method bind = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
            bind.setAccessible(true);
            for (Holder.Reference<Biome> biome : lookup.lookupOrThrow(Registries.BIOME).listElements().toList()) {
                String id = biome.key().identifier().toString();
                List<TagKey<Biome>> tags = new ArrayList<>();
                resolved.forEach((tag, members) -> {
                    if (members.contains(id)) tags.add(TagKey.create(Registries.BIOME, Identifier.parse(tag)));
                });
                bind.invoke(biome, tags);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't load biome tags from the Minecraft jar", e);
        }
    }

    private static Set<String> resolveTag(String tag, Map<String, List<String>> raw, Map<String, Set<String>> done, Set<String> visiting) {
        Set<String> have = done.get(tag);
        if (have != null) return have;
        Set<String> out = new HashSet<>();
        if (visiting.add(tag)) {
            for (String v : raw.getOrDefault(tag, List.of())) {
                if (v.startsWith("#")) out.addAll(resolveTag(v.substring(1), raw, done, visiting));
                else out.add(v);
            }
        }
        done.put(tag, out);
        return out;
    }

    /** Runs the real structure generation for the one possible spot in a region. Null if nothing generates there. */
    private StructureStart startIn(RandomSpreadStructurePlacement placement, Holder<Structure> structure, int regionX, int regionZ) {
        ChunkPos chunk = placement.getPotentialStructureChunk(o.seed, regionX * placement.spacing(), regionZ * placement.spacing());
        // Quick skip: the game checks the biome at the chunk middle at ground height. If the right biome isn't
        // at any of a few heights in that column (surface biomes fill the whole column), it can't generate, and we save the expensive height lookup.
        int qx = QuartPos.fromBlock(chunk.getMiddleBlockX()), qz = QuartPos.fromBlock(chunk.getMiddleBlockZ());
        boolean possible = false;
        for (int y : PREFILTER_Y) {
            if (possible) break;
            possible = structure.value().biomes().contains(generator.getBiomeSource().getNoiseBiome(qx, QuartPos.fromBlock(y), qz, random.sampler()));
        }
        if (!possible) return null;
        StructureStart start = structure.value().generate(structure, Level.OVERWORLD, null, generator, generator.getBiomeSource(),
                random, null, o.seed, chunk, 0, heights, b -> structure.value().biomes().contains(b));
        return start.isValid() ? start : null;
    }

    private RandomSpreadStructurePlacement placement(ResourceKey<StructureSet> set) {
        return (RandomSpreadStructurePlacement) lookup.lookupOrThrow(Registries.STRUCTURE_SET).getOrThrow(set).value().placement();
    }

    private int[] regionRange(int spacing) {
        int size = spacing * 16;
        return new int[]{Math.floorDiv(o.centerX - o.radius, size), Math.floorDiv(o.centerX + o.radius, size),
                Math.floorDiv(o.centerZ - o.radius, size), Math.floorDiv(o.centerZ + o.radius, size)};
    }

    private boolean inArea(int x, int z) {
        return Math.abs(x - o.centerX) <= o.radius && Math.abs(z - o.centerZ) <= o.radius;
    }

    private long dist(int x, int z) {
        return Math.round(Math.hypot(x - o.centerX, z - o.centerZ));
    }

    private static void progress(AtomicInteger done, int total, AtomicInteger last) {
        int pct = done.incrementAndGet() * 100 / total;
        if (pct % 10 == 0 && last.getAndSet(pct) != pct) OUT.print("\r  " + pct + "%");
    }

    // ---------------------------------------------------------------- witch huts

    private void huts() {
        RandomSpreadStructurePlacement placement = placement(BuiltinStructureSets.SWAMP_HUTS);
        Holder<Structure> hut = lookup.lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.SWAMP_HUT);
        int[] r = regionRange(placement.spacing());
        int rows = r[1] - r[0] + 1;
        OUT.printf(Locale.ROOT, "Seed %d, searching %d blocks around %d, %d for witch huts at or below y %d (%,d possible spots)%n",
                o.seed, o.radius, o.centerX, o.centerZ, o.maxY, (long) rows * (r[3] - r[2] + 1));
        long start = System.nanoTime();
        List<Hut> found = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger done = new AtomicInteger(), last = new AtomicInteger(-1);
        IntStream.rangeClosed(r[0], r[1]).parallel().forEach(rx -> {
            for (int rz = r[2]; rz <= r[3]; rz++) {
                StructureStart s = startIn(placement, hut, rx, rz);
                if (s == null) continue;
                BoundingBox b = s.getPieces().get(0).getBoundingBox();
                if (!inArea(b.minX(), b.minZ())) continue;
                found.add(new Hut(b.minX(), quickHutY(b), b.minZ(), b.minX(), b.minZ(), b.maxX(), b.maxZ()));
            }
            progress(done, rows, last);
        });
        List<Hut> all = new ArrayList<>(found);

        // Exact height (full footprint, like the game) for every hut that might be low enough.
        List<Hut> exact = all.parallelStream().map(h -> h.y <= o.maxY + QUICK_MARGIN
                ? new Hut(h.x, hutY(new BoundingBox(h.minX, 0, h.minZ, h.maxX, 0, h.maxZ)), h.z, h.minX, h.minZ, h.maxX, h.maxZ) : h).toList();
        all = new ArrayList<>(exact);
        OUT.printf(Locale.ROOT, "\r  done in %.1f s, %,d witch huts in the area%n%n", (System.nanoTime() - start) / 1e9, all.size());
        List<Hut> low = new ArrayList<>(all.stream().filter(h -> h.y <= o.maxY).toList());
        low.sort((a, b) -> a.y != b.y ? Integer.compare(a.y, b.y) : Long.compare(dist(a.x, a.z), dist(b.x, b.z)));
        if (low.isEmpty()) {
            List<Hut> sorted = new ArrayList<>(all);
            sorted.sort((a, b) -> Integer.compare(a.y, b.y));
            OUT.println("No witch huts at or below y " + o.maxY + ". The lowest ones are:");
            low = sorted;
        } else {
            OUT.printf(Locale.ROOT, "%d witch huts at or below y %d (lowest first):%n", low.size(), o.maxY);
        }
        OUT.printf(Locale.ROOT, "%n%-4s %-26s %-9s %-10s %s%n", "#", "Hut corner (x, y, z)", "Floor y", "Distance", "Nearest other hut");
        for (int i = 0; i < Math.min(o.top, low.size()); i++) {
            Hut h = low.get(i);
            long near = Long.MAX_VALUE;
            for (Hut other : all) if (other != h) near = Math.min(near, Math.round(Math.hypot(other.x - h.x, other.z - h.z)));
            OUT.printf(Locale.ROOT, "%-4d %-26s %-9d %-10d %s%n", i + 1, h.x + ", " + h.y + ", " + h.z, h.y + 1, dist(h.x, h.z),
                    near == Long.MAX_VALUE ? "-" : near + " blocks");
        }
        OUT.println("\nThe hut is 7x9 blocks from that corner; its floor is 1 above the corner y.");
        OUT.println("Teleport: /tp @s <x> <floor y + 1> <z>");
    }

    /** Average height of 9 spots (corners, edge middles, centre): a cheap first guess at the hut's height. */
    private int quickHutY(BoundingBox b) {
        int sum = 0;
        int[] xs = {b.minX(), (b.minX() + b.maxX()) / 2, b.maxX()}, zs = {b.minZ(), (b.minZ() + b.maxZ()) / 2, b.maxZ()};
        for (int x : xs) for (int z : zs) sum += generator.getBaseHeight(x, z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, heights, random);
        return sum / 9;
    }

    /** Same rule as the game: the average ground height (MOTION_BLOCKING_NO_LEAVES) over the hut's footprint. */
    private int hutY(BoundingBox b) {
        int sum = 0, n = 0;
        for (int z = b.minZ(); z <= b.maxZ(); z++) {
            for (int x = b.minX(); x <= b.maxX(); x++) {
                sum += generator.getBaseHeight(x, z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, heights, random);
                n++;
            }
        }
        return sum / n;
    }

    // ---------------------------------------------------------------- ocean monuments

    private void monuments() {
        RandomSpreadStructurePlacement placement = placement(BuiltinStructureSets.OCEAN_MONUMENTS);
        Holder<Structure> monument = lookup.lookupOrThrow(Registries.STRUCTURE).getOrThrow(BuiltinStructures.OCEAN_MONUMENT);
        int[] r = regionRange(placement.spacing());
        int rows = r[1] - r[0] + 1, cols = r[3] - r[2] + 1;
        OUT.printf(Locale.ROOT, "Seed %d, searching %d blocks around %d, %d for %s+ ocean monuments (%,d regions)%n",
                o.seed, o.radius, o.centerX, o.centerZ, o.minCount == 4 ? "quad" : o.minCount == 3 ? "triple" : "double",
                (long) rows * cols);
        long start = System.nanoTime();

        // 1. Where each region's monument WOULD be (cheap), as block centre.
        int[][] px = new int[rows][cols], pz = new int[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                ChunkPos c = placement.getPotentialStructureChunk(o.seed, (r[0] + i) * placement.spacing(), (r[2] + j) * placement.spacing());
                px[i][j] = c.getMinBlockX();
                pz[i][j] = c.getMinBlockZ();
            }
        }

        // 2. 2x2 region blocks where enough potential spots are close together, then check those for real.
        Map<String, Cluster> found = new java.util.concurrent.ConcurrentHashMap<>();
        AtomicInteger done = new AtomicInteger(), last = new AtomicInteger(-1);
        IntStream.range(0, rows - 1).parallel().forEach(i -> {
            for (int j = 0; j < cols - 1; j++) {
                int[][] cells = {{i, j}, {i + 1, j}, {i, j + 1}, {i + 1, j + 1}};
                List<int[]> close = closeGroup(cells, px, pz);
                if (close.size() < o.minCount) continue;
                List<Monument> real = new ArrayList<>();
                for (int[] c : close) {
                    StructureStart s = startIn(placement, monument, r[0] + c[0], r[2] + c[1]);
                    if (s != null) real.add(new Monument(px[c[0]][c[1]], pz[c[0]][c[1]], s.getBoundingBox()));
                }
                if (real.size() < o.minCount) continue;
                Cluster best = bestAfk(real);
                if (best != null && inArea(best.afkX, best.afkZ)) {
                    String key = real.stream().map(m -> m.x + "," + m.z).sorted().reduce("", String::concat);
                    found.merge(key, best, (a, b) -> a.monuments.size() >= b.monuments.size() ? a : b);
                }
            }
            progress(done, rows - 1, last);
        });
        OUT.printf(Locale.ROOT, "\r  done in %.1f s%n%n", (System.nanoTime() - start) / 1e9);

        List<Cluster> list = new ArrayList<>(found.values());
        // A triple that is part of a quad is listed with the quad only.
        list.removeIf(c -> c.monuments.size() < 4 && list.stream().anyMatch(q -> q.monuments.size() > c.monuments.size()
                && q.monuments.stream().map(Monument::x).toList().containsAll(c.monuments.stream().map(Monument::x).toList())));
        // Best total first; equal ones (e.g. perfect doubles) closest first.
        list.sort((a, b) -> Math.abs(a.score - b.score) > 0.005 ? Double.compare(b.score, a.score) : Long.compare(dist(a.afkX, a.afkZ), dist(b.afkX, b.afkZ)));
        if (list.isEmpty()) {
            OUT.println("None found. Try --min 3 (triples) or a bigger --radius.");
            return;
        }
        OUT.printf(Locale.ROOT, "%d quad, %d triple and %d double monument spots found (best first):%n%n",
                list.stream().filter(c -> c.monuments.size() == 4).count(), list.stream().filter(c -> c.monuments.size() == 3).count(),
                list.stream().filter(c -> c.monuments.size() == 2).count());
        OUT.printf(Locale.ROOT, "%-4s %-6s %-22s %-10s %-24s %s%n", "#", "Count", "AFK spot (x, y, z)", "Distance", "Coverage per monument", "Total");
        for (int i = 0; i < Math.min(o.top, list.size()); i++) {
            Cluster c = list.get(i);
            StringBuilder cov = new StringBuilder();
            for (double d : c.cover) cov.append(Math.round(d * 100)).append("% ");
            OUT.printf(Locale.ROOT, "%-4d %-6s %-22s %-10d %-24s %.2f%n", i + 1, new String[]{"", "single", "double", "triple", "quad"}[c.monuments.size()],
                    c.afkX + ", " + c.afkY + ", " + c.afkZ, dist(c.afkX, c.afkZ), cov.toString().trim(), c.score);
        }
        OUT.println("\nCoverage = how much of each monument's spawning area is within 128 blocks of the AFK spot.");
        OUT.println("Total = monuments' worth of spawning area (4.00 would be a perfect quad, which can't happen).");
        if (o.details) {
            OUT.println();
            for (int i = 0; i < Math.min(o.top, list.size()); i++) {
                OUT.print("#" + (i + 1) + " monument centres:");
                for (Monument m : list.get(i).monuments) OUT.print("  " + m.x + ", " + m.z);
                OUT.println();
            }
        }
    }

    /** The cells (of a 2x2 region block) whose potential monuments are all close to each other. */
    private List<int[]> closeGroup(int[][] cells, int[][] px, int[][] pz) {
        List<int[]> best = List.of();
        for (int mask = 1; mask < 16; mask++) {
            if (Integer.bitCount(mask) < Math.max(o.minCount, best.size() + 1)) continue;
            List<int[]> group = new ArrayList<>();
            for (int k = 0; k < 4; k++) if ((mask & (1 << k)) != 0) group.add(cells[k]);
            boolean ok = true;
            for (int a = 0; a < group.size() && ok; a++)
                for (int b = a + 1; b < group.size() && ok; b++) {
                    int[] p = group.get(a), q = group.get(b);
                    ok = Math.hypot(px[p[0]][p[1]] - px[q[0]][q[1]], pz[p[0]][p[1]] - pz[q[0]][q[1]]) <= MONUMENT_PAIR_MAX;
                }
            if (ok) best = group;
        }
        return best;
    }

    /** Finds the AFK spot that covers the most monument spawning area (guardians spawn in the monument's whole box). */
    private Cluster bestAfk(List<Monument> ms) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE, minY = 999, maxY = -999;
        for (Monument m : ms) {
            minX = Math.min(minX, m.box.minX());
            maxX = Math.max(maxX, m.box.maxX());
            minZ = Math.min(minZ, m.box.minZ());
            maxZ = Math.max(maxZ, m.box.maxZ());
            minY = Math.min(minY, m.box.minY());
            maxY = Math.max(maxY, m.box.maxY());
        }
        int afkY = (minY + maxY) / 2;
        Cluster best = null;
        for (int step : new int[]{16, 2}) {
            int x0 = best == null ? minX : best.afkX - 16, x1 = best == null ? maxX : best.afkX + 16;
            int z0 = best == null ? minZ : best.afkZ - 16, z1 = best == null ? maxZ : best.afkZ + 16;
            for (int x = x0; x <= x1; x += step) {
                for (int z = z0; z <= z1; z += step) {
                    double[] cover = new double[ms.size()];
                    double score = 0;
                    boolean all = true;
                    for (int k = 0; k < ms.size(); k++) {
                        cover[k] = coverage(ms.get(k).box, x, afkY, z);
                        score += cover[k];
                        if (cover[k] <= 0) all = false;
                    }
                    if (all && (best == null || score > best.score)) best = new Cluster(ms, x, afkY, z, cover, score);
                }
            }
        }
        return best;
    }

    /** Fraction of a monument box within 128 blocks (sphere) of the AFK spot, sampled every 4 blocks. */
    private static double coverage(BoundingBox b, int ax, int ay, int az) {
        int in = 0, total = 0;
        for (int x = b.minX(); x <= b.maxX(); x += 4) {
            for (int z = b.minZ(); z <= b.maxZ(); z += 4) {
                for (int y = b.minY(); y <= b.maxY(); y += 4) {
                    total++;
                    double dx = x + 0.5 - ax, dy = y + 0.5 - ay, dz = z + 0.5 - az;
                    if (dx * dx + dy * dy + dz * dz <= AFK_RANGE * AFK_RANGE) in++;
                }
            }
        }
        return (double) in / total;
    }

    // ---------------------------------------------------------------- options

    static final class Options {
        long seed;
        boolean seedSet;
        int radius = 100000;
        int centerX, centerZ;
        int top = 25;
        int maxY = -50;
        int minCount = 3;
        boolean details;
        String worldType = "overworld";

        static Options parse(String mode, String[] args) {
            Options o = new Options();
            try {
                for (int i = 0; i < args.length; i++) {
                    switch (args[i]) {
                        case "-h", "--help" -> {
                            help();
                            return null;
                        }
                        case "--seed" -> {
                            o.seed = MesaFinder.Options.parseSeed(args[++i]);
                            o.seedSet = true;
                        }
                        case "--radius" -> o.radius = Integer.parseInt(args[++i]);
                        case "--center" -> {
                            String[] p = args[++i].split(",");
                            o.centerX = Integer.parseInt(p[0].trim());
                            o.centerZ = Integer.parseInt(p[1].trim());
                        }
                        case "--top" -> o.top = Integer.parseInt(args[++i]);
                        case "--max-y" -> o.maxY = Integer.parseInt(args[++i]);
                        case "--min" -> o.minCount = Integer.parseInt(args[++i]);
                        case "--details" -> o.details = true;
                        case "--large-biomes" -> o.worldType = "large_biomes";
                        case "--amplified" -> o.worldType = "amplified";
                        default -> {
                            if (!o.seedSet && !args[i].startsWith("-")) {
                                o.seed = MesaFinder.Options.parseSeed(args[i]);
                                o.seedSet = true;
                            } else throw new IllegalArgumentException("Unknown option " + args[i]);
                        }
                    }
                }
            } catch (RuntimeException e) {
                System.err.println("Bad arguments: " + e.getMessage());
                help();
                System.exit(2);
            }
            if (!o.seedSet || o.minCount < 2 || o.minCount > 4) {
                help();
                System.exit(2);
            }
            return o;
        }

        static void help() {
            OUT.println("""
                    Witch huts and ocean monuments (Minecraft 1.21.11):

                      ./mesafinder huts <seed> [--max-y -50] [--radius 100000] [--center x,z] [--top 25]
                          witch huts at or below a height (their real generated height)
                      ./mesafinder monuments <seed> [--min 3|4] [--radius 100000] [--center x,z] [--top 25] [--details]
                          spots where 3 (triple) or 4 (quad) monuments are within one AFK range

                      --large-biomes / --amplified   world type, if not normal
                    """);
        }
    }
}
