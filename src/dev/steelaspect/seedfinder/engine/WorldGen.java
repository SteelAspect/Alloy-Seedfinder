package dev.steelaspect.seedfinder.engine;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The world generator for one seed, dimension and world type. Thread-safe for biome lookups. */
public final class WorldGen {
    public static final int SEA_LEVEL = 62;
    /** Height the map reads biomes at. */
    public static final int MAP_Y = 64;
    private static final java.util.Set<String> CAVE_BIOMES = java.util.Set.of("lush_caves", "dripstone_caves", "deep_dark");

    private static final Map<String, WorldGen> CACHE = new ConcurrentHashMap<>();

    public final long seed;
    public final Dim dim;
    public final WorldType type;
    public final HolderLookup.Provider lookup;
    public final RandomState random;
    public final BiomeSource source;
    public final NoiseBasedChunkGenerator generator;
    public final LevelHeightAccessor heights;
    private final Climate.Sampler sampler;
    private final DensityFunction surface;
    /** One shared name string per biome, so huge grids only hold references (and == works). */
    private final Map<Holder<Biome>, String> names = new ConcurrentHashMap<>();

    public static WorldGen of(long seed, Dim dim, WorldType type) {
        WorldType t = dim == Dim.OVERWORLD ? type : WorldType.NORMAL;
        return CACHE.computeIfAbsent(seed + "/" + dim + "/" + t, k -> new WorldGen(seed, dim, t));
    }

    /** The same, but not kept afterwards (for a quick look at many seeds, like the slime seed hunt's biome check). */
    public static WorldGen once(long seed, Dim dim, WorldType type) {
        WorldType t = dim == Dim.OVERWORLD ? type : WorldType.NORMAL;
        WorldGen w = CACHE.get(seed + "/" + dim + "/" + t);
        return w != null ? w : new WorldGen(seed, dim, t);
    }

    private WorldGen(long seed, Dim dim, WorldType type) {
        this.seed = seed;
        this.dim = dim;
        this.type = type;
        lookup = Mc.lookup();
        ResourceKey<NoiseGeneratorSettings> settings = switch (dim) {
            case NETHER -> NoiseGeneratorSettings.NETHER;
            case END -> NoiseGeneratorSettings.END;
            case OVERWORLD -> switch (type) {
                case LARGE_BIOMES -> NoiseGeneratorSettings.LARGE_BIOMES;
                case AMPLIFIED -> NoiseGeneratorSettings.AMPLIFIED;
                case NORMAL -> NoiseGeneratorSettings.OVERWORLD;
            };
        };
        random = RandomState.create(lookup, settings, seed);
        source = dim == Dim.END ? TheEndBiomeSource.create(lookup.lookupOrThrow(Registries.BIOME))
                : MultiNoiseBiomeSource.createFromPreset(lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(dim == Dim.NETHER ? MultiNoiseBiomeSourceParameterLists.NETHER : MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        heights = dim == Dim.OVERWORLD ? LevelHeightAccessor.create(-64, 384) : LevelHeightAccessor.create(0, 256);
        generator = new NoiseBasedChunkGenerator(source, lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(settings));
        sampler = random.sampler();
        surface = random.router().preliminarySurfaceLevel();
    }

    /**
     * The biome you'd see at (x, z): at the land surface, or the water surface over oceans and rivers.
     * Nether and End biomes don't change with height. Returns a shared string like "badlands".
     */
    public String biomeAt(int x, int z) {
        return name(biome(x, surfaceY(x, z), z));
    }

    /**
     * {@link #biomeAt} (or with map = true, {@link #mapBiomeAt}) for every block (x0 + i * step, z0 + j * step) of a
     * grid, row by row along x, worked out on the graphics card: exactly the same answers (GpuWorld checks), much
     * faster. Null when there's no usable card (or it's switched off): then use the CPU methods.
     */
    public String[] gpuGrid(boolean map, int x0, int z0, int step, int nx, int nz) {
        GpuWorld g = GpuWorld.of(this);
        if (g == null) return null;
        try {
            if (dim != Dim.OVERWORLD) return g.grid(GpuWorld.FIXED, x0, z0, step, nx, nz, 64 >> 2);
            return g.grid(map ? GpuWorld.MAP : GpuWorld.SURFACE, x0, z0, step, nx, nz, 0);
        } catch (dev.steelaspect.seedfinder.gpu.GpuException e) {
            Mc.ERR.println("Graphics card failed, using the CPU: " + e.getMessage());
            return null;
        }
    }

    /** The height biomeAt reads at: the land or water surface (overworld), y 64 elsewhere. */
    public int surfaceY(int x, int z) {
        return dim != Dim.OVERWORLD ? 64 : Math.max(SEA_LEVEL, (int) surface.compute(new DensityFunction.SinglePointContext(x, 0, z)));
    }

    /**
     * The quick biome for maps: read at a fixed height (y 64), like cubiomes-viewer, which skips the expensive
     * ground-height step. Under mountains y 64 can land in a cave biome; those spots fall back to {@link #biomeAt}.
     * Searches always use {@link #biomeAt}.
     */
    public String mapBiomeAt(int x, int z) {
        if (dim != Dim.OVERWORLD) return biomeAt(x, z);
        String b = name(biome(x, MAP_Y, z));
        return CAVE_BIOMES.contains(b) ? biomeAt(x, z) : b;
    }

    /** Underground biomes that y 64 can land in under mountains (the map then reads the surface instead). */
    public static boolean isCaveBiome(String biome) {
        return CAVE_BIOMES.contains(biome);
    }

    /**
     * The biome the game stores for the 4x4x4 cell holding this block when it generates the chunk. The game's own
     * lookup; where two biomes are exactly as close (about 3 cells in 10,000) it's settled the way chunk generation
     * settles it (see ClimateTree), so the answer never depends on which thread asked first.
     */
    public Holder<Biome> biome(int x, int y, int z) {
        int qx = QuartPos.fromBlock(x), qy = QuartPos.fromBlock(y), qz = QuartPos.fromBlock(z);
        ClimateTree t = tree();
        if (t == null) return source.getNoiseBiome(qx, qy, qz, sampler);
        Climate.TargetPoint target = sampler.sample(qx, qy, qz);
        if (t.isTie(ClimateTree.values(target))) return t.inGame(qx, qy, qz);
        return ((MultiNoiseBiomeSource) source).getNoiseBiome(target);
    }

    /** The game's plain lookup, as structure placement uses it (ties go by lookup order, as in the game). */
    public Holder<Biome> noiseBiome(int x, int y, int z) {
        return source.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), sampler);
    }

    private volatile ClimateTree tree;
    private volatile boolean treeTried;

    /** The parameter tree (overworld and nether), or null (the End has none). */
    ClimateTree tree() {
        if (!treeTried) {
            synchronized (this) {
                if (!treeTried) {
                    if (source instanceof MultiNoiseBiomeSource) {
                        try {
                            tree = new ClimateTree(this);
                        } catch (ReflectiveOperationException e) {
                            throw new IllegalStateException("Couldn't read the biome parameter tree", e);
                        }
                    }
                    treeTried = true;
                }
            }
        }
        return tree;
    }

    public String name(Holder<Biome> biome) {
        return names.computeIfAbsent(biome, h -> h.unwrapKey().map(k -> k.identifier().getPath()).orElse("unknown"));
    }
}
