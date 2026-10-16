package dev.steelaspect.seedfinder.engine;

import dev.steelaspect.seedfinder.launcher.AppDirs;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;

import com.mojang.serialization.Lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Structure positions for map icons, worked out the way the game places structures: the structure set's placement
 * (spacing, frequency, exclusion zones, stronghold rings), the weighted pick between the set's structures, and each
 * structure's own check for a valid spot (including its biome check). Pieces aren't built, so it's quick.
 */
public final class StructureLayers {
    /**
     * One kind of icon on the map. maxBlocksPerPixel: hide the layer when zoomed out further, so icons stay about
     * 20+ px apart (trial chambers are in almost every 544-block region, mansions only every 1280).
     */
    public enum Layer {
        VILLAGE("Villages", Dim.OVERWORLD, BuiltinStructureSets.VILLAGES, null, 0xE6B45A, "house", 32),
        MONUMENT("Ocean monuments", Dim.OVERWORLD, BuiltinStructureSets.OCEAN_MONUMENTS, null, 0x4FB6D8, "waves", 64),
        SWAMP_HUT("Witch huts", Dim.OVERWORLD, BuiltinStructureSets.SWAMP_HUTS, null, 0x7BC27B, "hut", 64),
        OUTPOST("Pillager outposts", Dim.OVERWORLD, BuiltinStructureSets.PILLAGER_OUTPOSTS, null, 0xB0B6C0, "flag", 64),
        MANSION("Woodland mansions", Dim.OVERWORLD, BuiltinStructureSets.WOODLAND_MANSIONS, null, 0xA0703C, "mansion", 256),
        DESERT_TEMPLE("Desert temples", Dim.OVERWORLD, BuiltinStructureSets.DESERT_PYRAMIDS, null, 0xF0D27A, "pyramid", 64),
        JUNGLE_TEMPLE("Jungle temples", Dim.OVERWORLD, BuiltinStructureSets.JUNGLE_TEMPLES, null, 0x5FA83C, "pyramid", 64),
        IGLOO("Igloos", Dim.OVERWORLD, BuiltinStructureSets.IGLOOS, null, 0xDCEBF5, "dome", 64),
        ANCIENT_CITY("Ancient cities", Dim.OVERWORLD, BuiltinStructureSets.ANCIENT_CITIES, null, 0x3FA9A0, "city", 64),
        TRIAL_CHAMBER("Trial chambers", Dim.OVERWORLD, BuiltinStructureSets.TRIAL_CHAMBERS, null, 0xD98A4A, "trial", 16),
        STRONGHOLD("Strongholds", Dim.OVERWORLD, BuiltinStructureSets.STRONGHOLDS, null, 0x9D7CE0, "eye", 4096),
        RUINED_PORTAL("Ruined portals", Dim.OVERWORLD, BuiltinStructureSets.RUINED_PORTALS, null, 0xB05CE0, "portal", 16),
        SHIPWRECK("Shipwrecks", Dim.OVERWORLD, BuiltinStructureSets.SHIPWRECKS, null, 0xC8A070, "ship", 16),
        FORTRESS("Fortresses", Dim.NETHER, BuiltinStructureSets.NETHER_COMPLEXES, "fortress", 0xE05A4A, "castle", 32),
        BASTION("Bastions", Dim.NETHER, BuiltinStructureSets.NETHER_COMPLEXES, "bastion_remnant", 0x8C8C9A, "tower", 32),
        END_CITY("End cities", Dim.END, BuiltinStructureSets.END_CITIES, null, 0xE6D8A0, "tower", 32);

        public final String label;
        public final Dim dim;
        public final ResourceKey<StructureSet> set;
        /** For sets with several structures (fortress/bastion): only this one; null = any. */
        public final String only;
        public final int color;
        public final String icon;
        public final int maxBlocksPerPixel;

        Layer(String label, Dim dim, ResourceKey<StructureSet> set, String only, int color, String icon, int maxBlocksPerPixel) {
            this.label = label;
            this.dim = dim;
            this.set = set;
            this.only = only;
            this.color = color;
            this.icon = icon;
            this.maxBlocksPerPixel = maxBlocksPerPixel;
        }
    }

    /** One structure on the map. name is the structure id, e.g. "village_plains". */
    public record Icon(Layer layer, String name, int x, int y, int z) {
        public String label() {
            return Fmt.biome(name);
        }

        public String tp() {
            String cmd = "tp @s " + x + " " + (y + 1) + " " + z;
            return layer.dim == Dim.OVERWORLD ? "/" + cmd : "/execute in " + layer.dim.id + " run " + cmd;
        }
    }

    /** What the game keeps per world for structure placement. */
    private record Context(WorldGen world, ChunkGeneratorStructureState state, RegistryAccess registries) {}

    private static final Map<WorldGen, Context> CONTEXTS = new ConcurrentHashMap<>();
    private static volatile StructureTemplateManager templates;

    private StructureLayers() {}

    /** Every icon of a layer whose structure start lies in the block area (inclusive). */
    public static List<Icon> find(WorldGen w, Layer layer, int minX, int minZ, int maxX, int maxZ) {
        Context c = context(w);
        Holder<StructureSet> set = w.lookup.lookupOrThrow(Registries.STRUCTURE_SET).getOrThrow(layer.set);
        StructurePlacement placement = set.value().placement();
        List<Icon> out = new ArrayList<>();
        if (placement instanceof RandomSpreadStructurePlacement spread) {
            int size = spread.spacing() * 16;
            for (int rx = Math.floorDiv(minX, size); rx <= Math.floorDiv(maxX, size); rx++)
                for (int rz = Math.floorDiv(minZ, size); rz <= Math.floorDiv(maxZ, size); rz++) {
                    ChunkPos chunk = spread.getPotentialStructureChunk(w.seed, rx * spread.spacing(), rz * spread.spacing());
                    int bx = chunk.getMinBlockX(), bz = chunk.getMinBlockZ();
                    if (bx < minX - 15 || bx > maxX || bz < minZ - 15 || bz > maxZ) continue;
                    if (!placement.isStructureChunk(c.state, chunk.x, chunk.z)) continue;
                    Icon icon = pick(c, set.value(), chunk);
                    if (icon != null && (layer.only == null || icon.name.equals(layer.only))) out.add(new Icon(layer, icon.name, icon.x, icon.y, icon.z));
                }
        } else if (placement instanceof ConcentricRingsStructurePlacement rings) {
            for (ChunkPos chunk : c.state.getRingPositionsFor(rings)) {
                int bx = chunk.getMinBlockX(), bz = chunk.getMinBlockZ();
                if (bx < minX - 15 || bx > maxX || bz < minZ - 15 || bz > maxZ) continue;
                Icon icon = pick(c, set.value(), chunk);
                if (icon != null) out.add(new Icon(layer, icon.name, icon.x, icon.y, icon.z));
            }
        }
        return out;
    }

    /**
     * Which structure of the set starts in this chunk: the game tries the set's structures in a weighted random
     * order (same random as ChunkGenerator.createStructures) and takes the first whose spot is valid.
     */
    private static Icon pick(Context c, StructureSet set, ChunkPos chunk) {
        List<StructureSet.StructureSelectionEntry> entries = new ArrayList<>(set.structures());
        if (entries.size() == 1) return tryOne(c, entries.get(0).structure(), chunk);
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureSeed(c.world.seed, chunk.x, chunk.z);
        int total = 0;
        for (StructureSet.StructureSelectionEntry e : entries) total += e.weight();
        while (!entries.isEmpty()) {
            int r = random.nextInt(total), k = 0;
            for (StructureSet.StructureSelectionEntry e : entries) {
                r -= e.weight();
                if (r < 0) break;
                k++;
            }
            StructureSet.StructureSelectionEntry e = entries.get(k);
            Icon icon = tryOne(c, e.structure(), chunk);
            if (icon != null) return icon;
            entries.remove(k);
            total -= e.weight();
        }
        return null;
    }

    private static Icon tryOne(Context c, Holder<Structure> structure, ChunkPos chunk) {
        WorldGen w = c.world;
        Structure.GenerationContext ctx = new Structure.GenerationContext(c.registries, w.generator, w.source, w.random, templates(),
                w.seed, chunk, w.heights, structure.value().biomes()::contains);
        Optional<Structure.GenerationStub> stub = structure.value().findValidGenerationPoint(ctx);
        if (stub.isEmpty()) return null;
        String name = structure.unwrapKey().map(k -> k.identifier().getPath()).orElse("structure");
        var p = stub.get().position();
        return new Icon(null, name, p.getX(), p.getY(), p.getZ());
    }

    private static Context context(WorldGen w) {
        return CONTEXTS.computeIfAbsent(w, k -> {
            ChunkGeneratorStructureState state = ChunkGeneratorStructureState.createForNormal(k.random, k.seed, k.source,
                    k.lookup.lookupOrThrow(Registries.STRUCTURE_SET));
            // Jigsaw structures (villages, bastions...) look up their template pools in a registry.
            MappedRegistry<StructureTemplatePool> pools = new MappedRegistry<>(Registries.TEMPLATE_POOL, Lifecycle.stable());
            k.lookup.lookupOrThrow(Registries.TEMPLATE_POOL).listElements()
                    .forEach(h -> pools.register(h.key(), h.value(), RegistrationInfo.BUILT_IN));
            pools.freeze();
            return new Context(k, state, new RegistryAccess.ImmutableRegistryAccess(List.<Registry<?>>of(pools)));
        });
    }

    /** The game's structure template loader, reading the templates inside the Minecraft jar. */
    private static StructureTemplateManager templates() {
        StructureTemplateManager t = templates;
        if (t != null) return t;
        synchronized (StructureLayers.class) {
            if (templates == null) {
                try {
                    MultiPackResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(ServerPacksSource.createVanillaPackSource()));
                    // The manager wants a world folder for "generated" templates; give it an empty one in our cache.
                    var level = LevelStorageSource.createDefault(AppDirs.cache().toPath().resolve("templates")).createAccess("process-" + ProcessHandle.current().pid());
                    templates = new StructureTemplateManager(resources, level, DataFixers.getDataFixer(), BuiltInRegistries.BLOCK);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("Couldn't set up structure templates", e);
                }
            }
            return templates;
        }
    }
}
