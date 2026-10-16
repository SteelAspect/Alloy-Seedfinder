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
        int y = dim != Dim.OVERWORLD ? 64 : Math.max(SEA_LEVEL, (int) surface.compute(new DensityFunction.SinglePointContext(x, 0, z)));
        return name(biome(x, y, z));
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

    public Holder<Biome> biome(int x, int y, int z) {
        return source.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), sampler);
    }

    public String name(Holder<Biome> biome) {
        return names.computeIfAbsent(biome, h -> h.unwrapKey().map(k -> k.identifier().getPath()).orElse("unknown"));
    }
}
