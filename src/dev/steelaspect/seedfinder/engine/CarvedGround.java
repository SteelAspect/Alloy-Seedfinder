package dev.steelaspect.seedfinder.engine;

import com.mojang.serialization.Lifecycle;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.QuartPos;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * The ground the game sees when it places a surface structure: the chunk's terrain with the caves and canyons carved
 * into it. Built with Minecraft's own code, the NOISE and CARVERS steps of chunk generation for one chunk
 * (NoiseBasedChunkGenerator.doFill, then the loop of applyCarvers over the carvers starting within 8 chunks).
 * Witch huts settle on the average of this over their footprint, so a cave opening under one drops it.
 *
 * <p>Not included: structure "beards" (no structure with terrain adaptation goes in swamps) and features that a
 * neighbouring chunk may already have placed, which depends on the order the game generates chunks in.
 */
final class CarvedGround {
    private static final Method DO_FILL;
    private static final Field FLUID_PICKER;
    /** "No structures here" for the noise (DensityFunctions.BeardifierMarker, which isn't public). */
    private static final DensityFunctions.BeardifierOrMarker NO_BEARD;

    static {
        try {
            DO_FILL = NoiseBasedChunkGenerator.class.getDeclaredMethod("doFill", Blender.class, StructureManager.class, RandomState.class,
                    ChunkAccess.class, int.class, int.class);
            DO_FILL.setAccessible(true);
            FLUID_PICKER = NoiseBasedChunkGenerator.class.getDeclaredField("globalFluidPicker");
            FLUID_PICKER.setAccessible(true);
            NO_BEARD = (DensityFunctions.BeardifierOrMarker) Class.forName(DensityFunctions.class.getName() + "$BeardifierMarker", true,
                    DensityFunctions.class.getClassLoader()).getEnumConstants()[0];
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("This Minecraft version's chunk generator isn't laid out as expected", e);
        }
    }

    private static volatile RegistryAccess access;
    private static volatile PalettedContainerFactory containers;

    private CarvedGround() {}

    /** MOTION_BLOCKING_NO_LEAVES height (top block + 1, as getHeightmapPos gives it) of each column, index z * 16 + x. */
    @SuppressWarnings("unchecked")
    static int[] heights(WorldGen w, ChunkPos pos) {
        setUp(w);
        ProtoChunk chunk = new ProtoChunk(pos, UpgradeData.EMPTY, w.heights, containers, null);
        NoiseGeneratorSettings settings = w.generator.generatorSettings().value();
        try {
            // NOISE: the game's own fill. The noise chunk it uses is created here without structure beards.
            Aquifer.FluidPicker fluids = ((Supplier<Aquifer.FluidPicker>) FLUID_PICKER.get(w.generator)).get();
            NoiseChunk noise = chunk.getOrCreateNoiseChunk(c -> NoiseChunk.forChunk(c, w.random, NO_BEARD,
                    settings, fluids, Blender.empty()));
            NoiseSettings ns = settings.noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
            int cellMin = Mth.floorDiv(ns.minY(), ns.getCellHeight()), cells = Mth.floorDiv(ns.height(), ns.getCellHeight());
            if (cells > 0) DO_FILL.invoke(w.generator, Blender.empty(), null, w.random, chunk, cellMin, cells);

            // CARVERS: the same loop, seeds and order as NoiseBasedChunkGenerator.applyCarvers.
            BiomeManager biomes = new BiomeManager((x, y, z) -> w.source.getNoiseBiome(x, y, z, w.random.sampler()), BiomeManager.obfuscateSeed(w.seed));
            WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
            CarvingContext context = new CarvingContext(w.generator, access, chunk.getHeightAccessorForGeneration(), noise, w.random, settings.surfaceRule());
            var mask = chunk.getOrCreateCarvingMask();
            Aquifer aquifer = noise.aquifer();
            for (int dx = -8; dx <= 8; dx++)
                for (int dz = -8; dz <= 8; dz++) {
                    ChunkPos from = new ChunkPos(pos.x + dx, pos.z + dz);
                    Holder<Biome> biome = w.source.getNoiseBiome(QuartPos.fromBlock(from.getMinBlockX()), 0, QuartPos.fromBlock(from.getMinBlockZ()),
                            w.random.sampler());
                    BiomeGenerationSettings generation = w.generator.getBiomeGenerationSettings(biome);
                    int k = 0;
                    for (Holder<ConfiguredWorldCarver<?>> carver : generation.getCarvers()) {
                        random.setLargeFeatureSeed(w.seed + k, from.x, from.z);
                        if (carver.value().isStartChunk(random)) carver.value().carve(context, chunk, biomes::getBiome, random, aquifer, from, mask);
                        k++;
                    }
                }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Couldn't generate the chunk", e);
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.of(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES));
        int[] out = new int[256];
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) out[z * 16 + x] = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
        return out;
    }

    /** Chunks need a biome registry for their (unused here) biome storage, and carving wants registries. */
    private static void setUp(WorldGen w) {
        if (containers != null) return;
        synchronized (CarvedGround.class) {
            if (containers != null) return;
            MappedRegistry<Biome> biomes = new MappedRegistry<>(Registries.BIOME, Lifecycle.stable());
            w.lookup.lookupOrThrow(Registries.BIOME).listElements().forEach(h -> biomes.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
            biomes.freeze();
            RegistryAccess a = new RegistryAccess.ImmutableRegistryAccess(List.<Registry<?>>of(biomes));
            access = a;
            containers = PalettedContainerFactory.create(a);
        }
    }
}
