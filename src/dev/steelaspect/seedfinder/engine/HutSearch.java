package dev.steelaspect.seedfinder.engine;

import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Witch huts at or below a height. A hut's height is the average ground height over its 7x9 footprint
 * (the game's rule), so huts over big cave openings can generate deep underground.
 */
public final class HutSearch {
    /** A hut whose 9-spot guess is within this of the limit gets its exact height worked out. */
    private static final int QUICK_MARGIN = 60;

    public record Params(Search area, int maxY, int top) {}

    private record Hut(BoundingBox box, int y) {}

    private HutSearch() {}

    public static List<Result> run(Params p, Progress progress) {
        WorldGen w = WorldGen.of(p.area().seed(), Dim.OVERWORLD, p.area().type());
        RandomSpreadStructurePlacement placement = Structures.placement(w, BuiltinStructureSets.SWAMP_HUTS);
        Holder<Structure> hut = Structures.structure(w, BuiltinStructures.SWAMP_HUT);
        int[] r = Structures.regions(p.area(), placement.spacing());
        List<Hut> found = Collections.synchronizedList(new ArrayList<>());
        Structures.eachRow(r, progress, 0, 0.85, "Finding witch huts", rx -> {
            for (int rz = r[2]; rz <= r[3]; rz++) {
                ChunkPos chunk = Structures.chunkIn(placement, w.seed, rx, rz);
                if (!p.area().inside(chunk.getMinBlockX(), chunk.getMinBlockZ())) continue;
                StructureStart s = Structures.start(w, hut, chunk, true);
                if (s == null) continue;
                BoundingBox b = s.getPieces().get(0).getBoundingBox();
                found.add(new Hut(b, quickY(w, b)));
            }
        });
        progress.update(0.86, "Measuring hut heights");
        List<Hut> all = found.parallelStream().map(h -> h.y <= p.maxY() + QUICK_MARGIN ? new Hut(h.box, exactY(w, h.box)) : h).toList();
        List<Hut> low = new ArrayList<>(all.stream().filter(h -> h.y <= p.maxY()).toList());
        String note = Fmt.n(all.size()) + " witch huts in the area, " + low.size() + " at or below y " + p.maxY();
        if (low.isEmpty()) {
            low = new ArrayList<>(all);
            note = "No witch huts at or below y " + p.maxY() + " (" + Fmt.n(all.size()) + " in the area). Showing the lowest.";
        }
        low.sort((a, b) -> a.y != b.y ? Integer.compare(a.y, b.y) : Long.compare(p.area().distance(a.box.minX(), a.box.minZ()), p.area().distance(b.box.minX(), b.box.minZ())));
        if (low.size() > p.top()) low = low.subList(0, p.top());
        List<Result> out = new ArrayList<>();
        for (Hut h : low) {
            Result res = new Result();
            res.kind = Result.Kind.HUT;
            res.dim = Dim.OVERWORLD;
            res.name = "Witch hut";
            BoundingBox b = h.box;
            res.minX = b.minX();
            res.minZ = b.minZ();
            res.maxX = b.maxX();
            res.maxZ = b.maxZ();
            res.x = (b.minX() + b.maxX()) / 2;
            res.z = (b.minZ() + b.maxZ()) / 2;
            res.y = h.y + 2;
            res.value = -h.y;
            res.size = "Floor y " + (h.y + 1);
            long near = Long.MAX_VALUE;
            for (Hut o : all) if (o != h) near = Math.min(near, Math.round(Math.hypot(o.box.minX() - b.minX(), o.box.minZ() - b.minZ())));
            res.details.put("Corner", b.minX() + ", " + h.y + ", " + b.minZ());
            res.details.put("Distance", Fmt.n(p.area().distance(res.x, res.z)));
            res.details.put("Nearest hut", near == Long.MAX_VALUE ? "-" : Fmt.n(near));
            res.ref = new int[]{b.minX(), h.y, b.minZ(), b.maxX(), b.maxZ()};
            out.add(res);
        }
        progress.update(1, note);
        return out;
    }

    /** Average height of 9 spots (corners, edge middles, centre): a cheap first guess. */
    private static int quickY(WorldGen w, BoundingBox b) {
        int sum = 0;
        int[] xs = {b.minX(), (b.minX() + b.maxX()) / 2, b.maxX()}, zs = {b.minZ(), (b.minZ() + b.maxZ()) / 2, b.maxZ()};
        for (int x : xs) for (int z : zs) sum += w.generator.getBaseHeight(x, z, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, w.heights, w.random);
        return sum / 9;
    }

    /**
     * Same rule as the game (ScatteredFeaturePiece.updateAverageGroundHeight): the average ground height
     * (MOTION_BLOCKING_NO_LEAVES) over the whole footprint, on the terrain with its caves carved (a hut always fits
     * in one chunk).
     */
    static int exactY(WorldGen w, BoundingBox b) {
        int[] ground = CarvedGround.heights(w, new ChunkPos(b.minX() >> 4, b.minZ() >> 4));
        int sum = 0, n = 0;
        for (int z = b.minZ(); z <= b.maxZ(); z++)
            for (int x = b.minX(); x <= b.maxX(); x++) {
                sum += ground[(z & 15) * 16 + (x & 15)];
                n++;
            }
        return sum / n;
    }
}
