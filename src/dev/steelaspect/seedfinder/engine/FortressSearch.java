package dev.steelaspect.seedfinder.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Biggest nether fortresses, by the floor area of their pieces (where wither skeletons spawn).
 * Each spot is a fortress or a bastion, picked the same way as the game.
 */
public final class FortressSearch {
    /**
     * Fortress pieces share static counters (NetherFortressPieces.PieceWeight.placeCount), so two fortresses built at
     * the same time corrupt each other's size. They are built one at a time.
     */
    private static final Object LOCK = new Object();
    private static final int KEEP = 300;
    private static final Map<Identifier, Vec3i> TEMPLATE_SIZES = new ConcurrentHashMap<>();
    private static final Method TEMPLATE_BOX;

    static {
        try {
            TEMPLATE_BOX = StructureTemplate.class.getDeclaredMethod("getBoundingBox", BlockPos.class, Rotation.class, BlockPos.class, Mirror.class, Vec3i.class);
            TEMPLATE_BOX.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @param minDist only fortresses at least this far out on x or z */
    public record Params(Search area, int minDist, int top) {}

    private record Fortress(int x, int z, long floor, BoundingBox box, List<BoundingBox> parts) {}

    private FortressSearch() {}

    public static List<Result> run(Params p, Progress progress) {
        WorldGen w = WorldGen.of(p.area().seed(), Dim.NETHER, WorldType.NORMAL);
        RandomSpreadStructurePlacement placement = Structures.placement(w, BuiltinStructureSets.NETHER_COMPLEXES);
        Holder<Structure> fortress = Structures.structure(w, BuiltinStructures.FORTRESS);
        Holder<Structure> bastion = Structures.structure(w, BuiltinStructures.BASTION_REMNANT);
        StructureTemplatePool starts = w.lookup.lookupOrThrow(Registries.TEMPLATE_POOL)
                .getOrThrow(ResourceKey.create(Registries.TEMPLATE_POOL, Identifier.withDefaultNamespace("bastion/starts"))).value();
        int[] r = Structures.regions(p.area(), placement.spacing());
        PriorityQueue<Fortress> best = new PriorityQueue<>(Comparator.comparingLong(Fortress::floor));
        AtomicInteger count = new AtomicInteger();
        Structures.eachRow(r, progress, 0, 0.97, "Building fortresses", rx -> {
            for (int rz = r[2]; rz <= r[3]; rz++) {
                ChunkPos chunk = Structures.chunkIn(placement, w.seed, rx, rz);
                int x = chunk.getMinBlockX(), z = chunk.getMinBlockZ();
                if (!p.area().inside(x, z) || Math.max(Math.abs(x), Math.abs(z)) < p.minDist()) continue;
                if (!isFortress(w, chunk, starts, bastion)) continue;
                StructureStart s;
                synchronized (LOCK) {
                    s = Structures.start(w, fortress, chunk, false);
                }
                if (s == null) continue;
                BoundingBox all = s.getBoundingBox();
                if (Math.max(Math.max(Math.abs(all.minX()), Math.abs(all.maxX())), Math.max(Math.abs(all.minZ()), Math.abs(all.maxZ()))) > Structures.WORLD_EDGE)
                    continue; // sticks out past the edge of the world
                long area = 0;
                List<BoundingBox> parts = new ArrayList<>();
                for (StructurePiece piece : s.getPieces()) {
                    BoundingBox b = piece.getBoundingBox();
                    parts.add(b);
                    area += (long) b.getXSpan() * b.getZSpan();
                }
                count.incrementAndGet();
                synchronized (best) {
                    // Keep only the biggest few (with their pieces) so memory stays small.
                    if (best.size() < KEEP || area > best.peek().floor) {
                        best.add(new Fortress(x, z, area, all, parts));
                        if (best.size() > KEEP) best.poll();
                    }
                }
            }
        });
        progress.update(0.98, "Measuring the biggest");
        List<Result> out = new ArrayList<>();
        for (Fortress f : best) {
            Result res = new Result();
            res.kind = Result.Kind.FORTRESS;
            res.dim = Dim.NETHER;
            res.name = "Fortress";
            BoundingBox b = f.box;
            res.minX = b.minX();
            res.minZ = b.minZ();
            res.maxX = b.maxX();
            res.maxZ = b.maxZ();
            res.x = (b.minX() + b.maxX()) / 2;
            res.z = (b.minZ() + b.maxZ()) / 2;
            res.y = b.minY() + 10;
            long floor = unionArea(f.parts, b);
            res.value = floor;
            res.size = Fmt.n(floor);
            res.details.put("Box", b.getXSpan() + "x" + b.getZSpan());
            res.details.put("Height", b.getYSpan() + " (y " + b.minY() + " to " + b.maxY() + ")");
            res.details.put("Volume", Fmt.n(unionVolume(f.parts, b)));
            res.details.put("Distance", Fmt.n(p.area().distance(res.x, res.z)));
            res.ref = new int[]{f.x, f.z};
            out.add(res);
        }
        out.sort((a, c) -> Double.compare(c.value, a.value));
        if (out.size() > p.top()) out = new ArrayList<>(out.subList(0, p.top()));
        progress.update(1, Fmt.n(count.get()) + " fortresses in the area");
        return out;
    }

    /**
     * Fortress or bastion here? The game picks fortress (weight 2) or bastion (weight 3); a bastion whose start piece
     * centre (y 33) is in the wrong biome (basalt deltas) falls back to a fortress.
     */
    private static boolean isFortress(WorldGen w, ChunkPos chunk, StructureTemplatePool starts, Holder<Structure> bastion) {
        WorldgenRandom pick = new WorldgenRandom(new LegacyRandomSource(0L));
        pick.setLargeFeatureSeed(w.seed, chunk.x, chunk.z);
        if (pick.nextInt(5) < 2) return true;
        WorldgenRandom r = new WorldgenRandom(new LegacyRandomSource(0L));
        r.setLargeFeatureSeed(w.seed, chunk.x, chunk.z);
        Rotation rotation = Rotation.getRandom(r);
        Identifier id = ((SinglePoolElement) starts.getRandomTemplate(r)).getTemplateLocation();
        Vec3i size = TEMPLATE_SIZES.computeIfAbsent(id, FortressSearch::templateSize);
        try {
            BoundingBox box = (BoundingBox) TEMPLATE_BOX.invoke(null, new BlockPos(chunk.getMinBlockX(), 33, chunk.getMinBlockZ()), rotation, BlockPos.ZERO, Mirror.NONE, size);
            int cx = (box.maxX() + box.minX()) / 2, cz = (box.maxZ() + box.minZ()) / 2;
            return !bastion.value().biomes().contains(w.biome(cx, 33, cz));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Vec3i templateSize(Identifier id) {
        String path = "/data/" + id.getNamespace() + "/structure/" + id.getPath() + ".nbt";
        try (var in = FortressSearch.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing " + path);
            ListTag size = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap()).getListOrEmpty("size");
            return new Vec3i(size.getIntOr(0, 0), size.getIntOr(1, 0), size.getIntOr(2, 0));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long unionArea(List<BoundingBox> parts, BoundingBox all) {
        int l = all.getZSpan();
        BitSet cells = new BitSet(all.getXSpan() * l);
        for (BoundingBox b : parts)
            for (int x = b.minX(); x <= b.maxX(); x++)
                for (int z = b.minZ(); z <= b.maxZ(); z++) cells.set((x - all.minX()) * l + (z - all.minZ()));
        return cells.cardinality();
    }

    private static long unionVolume(List<BoundingBox> parts, BoundingBox all) {
        int h = all.getYSpan(), l = all.getZSpan();
        BitSet cells = new BitSet(all.getXSpan() * h * l);
        for (BoundingBox b : parts)
            for (int x = b.minX(); x <= b.maxX(); x++)
                for (int y = b.minY(); y <= b.maxY(); y++)
                    for (int z = b.minZ(); z <= b.maxZ(); z++) cells.set(((x - all.minX()) * h + (y - all.minY())) * l + (z - all.minZ()));
        return cells.cardinality();
    }
}
