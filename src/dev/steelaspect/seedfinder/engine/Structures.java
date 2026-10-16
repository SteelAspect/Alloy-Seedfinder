package dev.steelaspect.seedfinder.engine;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.util.stream.IntStream;

/** Shared helpers for structure searches: where a structure could be, and whether it really generates there. */
final class Structures {
    /** Heights checked by the quick biome pre-check (surface biomes fill the whole column). */
    private static final int[] PREFILTER_Y = {64, 96, 32, 0, -32, -60};
    /** The world's hard limit (the default world border). */
    static final int WORLD_EDGE = 29_999_983;

    private Structures() {}

    static RandomSpreadStructurePlacement placement(WorldGen w, ResourceKey<StructureSet> set) {
        return (RandomSpreadStructurePlacement) w.lookup.lookupOrThrow(Registries.STRUCTURE_SET).getOrThrow(set).value().placement();
    }

    static Holder<Structure> structure(WorldGen w, ResourceKey<Structure> key) {
        return w.lookup.lookupOrThrow(Registries.STRUCTURE).getOrThrow(key);
    }

    /** Region index range covering the search square, for a placement with this spacing (in chunks). */
    static int[] regions(Search area, int spacing) {
        int size = spacing * 16;
        return new int[]{Math.floorDiv(area.centerX() - area.radius(), size), Math.floorDiv(area.centerX() + area.radius(), size),
                Math.floorDiv(area.centerZ() - area.radius(), size), Math.floorDiv(area.centerZ() + area.radius(), size)};
    }

    static ChunkPos chunkIn(RandomSpreadStructurePlacement placement, long seed, int regionX, int regionZ) {
        return placement.getPotentialStructureChunk(seed, regionX * placement.spacing(), regionZ * placement.spacing());
    }

    /** Runs the game's real structure generation in this chunk. Null if nothing generates there. */
    static StructureStart start(WorldGen w, Holder<Structure> structure, ChunkPos chunk, boolean precheck) {
        if (precheck) {
            // The game checks the biome at the chunk middle at ground height. If the right biome isn't at any of a
            // few heights in that column, it can't generate, and we skip the expensive height lookup.
            int qx = QuartPos.fromBlock(chunk.getMiddleBlockX()), qz = QuartPos.fromBlock(chunk.getMiddleBlockZ());
            boolean possible = false;
            for (int y : PREFILTER_Y) {
                if (possible) break;
                possible = structure.value().biomes().contains(w.source.getNoiseBiome(qx, QuartPos.fromBlock(y), qz, w.random.sampler()));
            }
            if (!possible) return null;
        }
        StructureStart s = structure.value().generate(structure, switch (w.dim) {
            case NETHER -> Level.NETHER;
            case END -> Level.END;
            case OVERWORLD -> Level.OVERWORLD;
        }, null, w.generator,
                w.source, w.random, null, w.seed, chunk, 0, w.heights, b -> structure.value().biomes().contains(b));
        return s.isValid() ? s : null;
    }

    /** Runs body for every region row in parallel, reporting progress over the given fraction range. */
    static void eachRow(int[] r, Progress progress, double from, double to, String message, java.util.function.IntConsumer body) {
        int rows = r[1] - r[0] + 1;
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        IntStream.rangeClosed(r[0], r[1]).parallel().forEach(rx -> {
            progress.check();
            body.accept(rx);
            int d = done.incrementAndGet();
            if (d % Math.max(1, rows / 100) == 0) progress.update(from + (to - from) * d / rows, message);
        });
    }
}
