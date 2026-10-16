package dev.steelaspect.seedfinder.engine;

import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spots where 2, 3 or 4 ocean monuments are within one AFK range (128 blocks). Guardians spawn in a monument's
 * whole box, so each spot is scored by how much of each box it covers.
 */
public final class MonumentSearch {
    /** Monuments further apart than this (centre to centre) can't share one AFK spot. */
    private static final int PAIR_MAX = 340;
    private static final double AFK_RANGE = 128;

    public record Params(Search area, int minCount, int top) {}

    private record Monument(int x, int z, BoundingBox box) {}

    private record Spot(List<Monument> monuments, int x, int y, int z, double[] cover, double score) {}

    private MonumentSearch() {}

    public static List<Result> run(Params p, Progress progress) {
        WorldGen w = WorldGen.of(p.area().seed(), Dim.OVERWORLD, p.area().type());
        RandomSpreadStructurePlacement placement = Structures.placement(w, BuiltinStructureSets.OCEAN_MONUMENTS);
        Holder<Structure> monument = Structures.structure(w, BuiltinStructures.OCEAN_MONUMENT);
        int[] r = Structures.regions(p.area(), placement.spacing());
        int rows = r[1] - r[0] + 1, cols = r[3] - r[2] + 1;
        // Where each region's monument WOULD be (cheap).
        int[][] px = new int[rows][cols], pz = new int[rows][cols];
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++) {
                ChunkPos c = Structures.chunkIn(placement, w.seed, r[0] + i, r[2] + j);
                px[i][j] = c.getMinBlockX();
                pz[i][j] = c.getMinBlockZ();
            }
        // 2x2 region blocks where enough potential spots are close together, then check those for real.
        Map<String, Spot> found = new ConcurrentHashMap<>();
        int[] blockRows = {0, rows - 2};
        Structures.eachRow(blockRows, progress, 0, 1, "Finding monuments", i -> {
            for (int j = 0; j < cols - 1; j++) {
                int[][] cells = {{i, j}, {i + 1, j}, {i, j + 1}, {i + 1, j + 1}};
                List<int[]> close = closeGroup(cells, px, pz, p.minCount());
                if (close.size() < p.minCount()) continue;
                List<Monument> real = new ArrayList<>();
                for (int[] c : close) {
                    StructureStart s = Structures.start(w, monument, new ChunkPos(px[c[0]][c[1]] >> 4, pz[c[0]][c[1]] >> 4), false);
                    if (s != null) real.add(new Monument(px[c[0]][c[1]], pz[c[0]][c[1]], s.getBoundingBox()));
                }
                if (real.size() < p.minCount()) continue;
                Spot best = bestAfk(real);
                if (best != null && p.area().inside(best.x, best.z)) {
                    String key = real.stream().map(m -> m.x + "," + m.z).sorted().reduce("", String::concat);
                    found.merge(key, best, (a, b) -> a.monuments.size() >= b.monuments.size() ? a : b);
                }
            }
        });
        List<Spot> list = new ArrayList<>(found.values());
        // A triple that is part of a quad is listed with the quad only.
        list.removeIf(c -> list.stream().anyMatch(q -> q.monuments.size() > c.monuments.size()
                && q.monuments.stream().map(Monument::x).toList().containsAll(c.monuments.stream().map(Monument::x).toList())));
        list.sort((a, b) -> Math.abs(a.score - b.score) > 0.005 ? Double.compare(b.score, a.score)
                : Long.compare(p.area().distance(a.x, a.z), p.area().distance(b.x, b.z)));
        List<Result> out = new ArrayList<>();
        for (Spot s : list.subList(0, Math.min(p.top(), list.size()))) {
            Result res = new Result();
            res.kind = Result.Kind.MONUMENTS;
            res.dim = Dim.OVERWORLD;
            res.name = new String[]{"", "Single", "Double", "Triple", "Quad"}[s.monuments.size()] + " monuments";
            res.x = s.x;
            res.y = s.y;
            res.z = s.z;
            res.minX = s.monuments.stream().mapToInt(m -> m.box.minX()).min().orElse(s.x);
            res.minZ = s.monuments.stream().mapToInt(m -> m.box.minZ()).min().orElse(s.z);
            res.maxX = s.monuments.stream().mapToInt(m -> m.box.maxX()).max().orElse(s.x);
            res.maxZ = s.monuments.stream().mapToInt(m -> m.box.maxZ()).max().orElse(s.z);
            res.value = s.score;
            res.size = String.format(java.util.Locale.ROOT, "%.2f", s.score);
            StringBuilder cov = new StringBuilder();
            for (double d : s.cover) cov.append(Math.round(d * 100)).append("% ");
            res.details.put("Count", Integer.toString(s.monuments.size()));
            res.details.put("Coverage", cov.toString().trim());
            res.details.put("Distance", Fmt.n(p.area().distance(s.x, s.z)));
            res.ref = new int[s.monuments.size() * 2];
            for (int k = 0; k < s.monuments.size(); k++) {
                res.ref[k * 2] = s.monuments.get(k).x;
                res.ref[k * 2 + 1] = s.monuments.get(k).z;
            }
            out.add(res);
        }
        progress.update(1, list.isEmpty() ? "No spots with " + p.minCount() + " or more monuments here"
                : list.stream().filter(c -> c.monuments.size() == 4).count() + " quad, "
                + list.stream().filter(c -> c.monuments.size() == 3).count() + " triple and "
                + list.stream().filter(c -> c.monuments.size() == 2).count() + " double spots");
        return out;
    }

    /** The cells (of a 2x2 region block) whose potential monuments are all close to each other. */
    private static List<int[]> closeGroup(int[][] cells, int[][] px, int[][] pz, int minCount) {
        List<int[]> best = List.of();
        for (int mask = 1; mask < 16; mask++) {
            if (Integer.bitCount(mask) < Math.max(minCount, best.size() + 1)) continue;
            List<int[]> group = new ArrayList<>();
            for (int k = 0; k < 4; k++) if ((mask & (1 << k)) != 0) group.add(cells[k]);
            boolean ok = true;
            for (int a = 0; a < group.size() && ok; a++)
                for (int b = a + 1; b < group.size() && ok; b++) {
                    int[] q = group.get(a), s = group.get(b);
                    ok = Math.hypot(px[q[0]][q[1]] - px[s[0]][s[1]], pz[q[0]][q[1]] - pz[s[0]][s[1]]) <= PAIR_MAX;
                }
            if (ok) best = group;
        }
        return best;
    }

    /** The AFK spot that covers the most monument spawning area. */
    private static Spot bestAfk(List<Monument> ms) {
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
        Spot best = null;
        for (int step : new int[]{16, 2}) {
            int x0 = best == null ? minX : best.x - 16, x1 = best == null ? maxX : best.x + 16;
            int z0 = best == null ? minZ : best.z - 16, z1 = best == null ? maxZ : best.z + 16;
            for (int x = x0; x <= x1; x += step)
                for (int z = z0; z <= z1; z += step) {
                    double[] cover = new double[ms.size()];
                    double score = 0;
                    boolean all = true;
                    for (int k = 0; k < ms.size(); k++) {
                        cover[k] = coverage(ms.get(k).box, x, afkY, z);
                        score += cover[k];
                        if (cover[k] <= 0) all = false;
                    }
                    if (all && (best == null || score > best.score)) best = new Spot(ms, x, afkY, z, cover, score);
                }
        }
        return best;
    }

    /** Fraction of a monument box within 128 blocks (sphere) of the AFK spot, sampled every 4 blocks. */
    private static double coverage(BoundingBox b, int ax, int ay, int az) {
        int in = 0, total = 0;
        for (int x = b.minX(); x <= b.maxX(); x += 4)
            for (int z = b.minZ(); z <= b.maxZ(); z += 4)
                for (int y = b.minY(); y <= b.maxY(); y += 4) {
                    total++;
                    double dx = x + 0.5 - ax, dy = y + 0.5 - ay, dz = z + 0.5 - az;
                    if (dx * dx + dy * dy + dz * dz <= AFK_RANGE * AFK_RANGE) in++;
                }
        return (double) in / total;
    }
}
