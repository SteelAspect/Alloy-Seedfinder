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
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces;
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
     * the same time through the game's normal path corrupt each other's size. {@link FortressBuilder} gives each
     * fortress private counters so they can be built on every core; the old one-at-a-time path stays as a fallback.
     */
    private static final Object LOCK = new Object();
    /** Use the old one-at-a-time path (also used automatically if FortressBuilder can't work on this version). */
    public static volatile boolean serial;
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

    /**
     * @param minDist      only fortresses at least this far out on x or z
     * @param byCrossroads rank by the biggest group of joined crossroads (then lowest) instead of floor area
     * @param maxY         only count crossroads at or below this height (their floor)
     * @param lines        with byCrossroads: rank by the longest straight line of joined crossroads instead
     * @param biome        with byCrossroads: only keep fortresses whose ranked crossroads are all in this biome
     *                     (every 4 blocks across them) at every height from biomeFrom to biomeTo; null for any
     */
    public record Params(Search area, int minDist, int top, boolean byCrossroads, int maxY, boolean lines,
                         String biome, int biomeFrom, int biomeTo) {
        public Params(Search area, int minDist, int top, boolean byCrossroads, int maxY, boolean lines) {
            this(area, minDist, top, byCrossroads, maxY, lines, null, 0, 0);
        }

        public Params(Search area, int minDist, int top, boolean byCrossroads, int maxY) {
            this(area, minDist, top, byCrossroads, maxY, false);
        }

        public Params(Search area, int minDist, int top) {
            this(area, minDist, top, false, 255, false);
        }
    }

    /** crossings: 4-way bridge crossroads, crossing rooms, small corridor crossings; group: the biggest joined crossroads. */
    private record Fortress(int x, int z, long floor, BoundingBox box, List<BoundingBox> parts, int[] crossings, Group group, Group line) {
        long rank(Params p) {
            if (!p.byCrossroads()) return floor;
            if (p.lines()) return (long) line.size << 40 | (long) (255 - line.y) << 30 | floor;
            return (long) group.size << 40 | (long) Group.score(group) << 38 | (long) (255 - group.y) << 30 | floor;
        }
    }

    private FortressSearch() {}

    public static List<Result> run(Params p, Progress progress) {
        WorldGen w = WorldGen.of(p.area().seed(), Dim.NETHER, WorldType.NORMAL);
        RandomSpreadStructurePlacement placement = Structures.placement(w, BuiltinStructureSets.NETHER_COMPLEXES);
        Holder<Structure> fortress = Structures.structure(w, BuiltinStructures.FORTRESS);
        Holder<Structure> bastion = Structures.structure(w, BuiltinStructures.BASTION_REMNANT);
        StructureTemplatePool starts = w.lookup.lookupOrThrow(Registries.TEMPLATE_POOL)
                .getOrThrow(ResourceKey.create(Registries.TEMPLATE_POOL, Identifier.withDefaultNamespace("bastion/starts"))).value();
        int[] r = Structures.regions(p.area(), placement.spacing());
        PriorityQueue<Fortress> best = new PriorityQueue<>(Comparator.comparingLong(f -> f.rank(p)));
        AtomicInteger count = new AtomicInteger();
        Structures.eachRow(r, progress, 0, 0.97, "Building fortresses", rx -> {
            for (int rz = r[2]; rz <= r[3]; rz++) {
                ChunkPos chunk = Structures.chunkIn(placement, w.seed, rx, rz);
                int x = chunk.getMinBlockX(), z = chunk.getMinBlockZ();
                if (!p.area().inside(x, z) || Math.max(Math.abs(x), Math.abs(z)) < p.minDist()) continue;
                if (!isFortress(w, chunk, starts, bastion)) continue;
                List<StructurePiece> pieceList;
                if (serial || !FortressBuilder.AVAILABLE) {
                    StructureStart s;
                    synchronized (LOCK) {
                        s = Structures.start(w, fortress, chunk, false);
                    }
                    if (s == null) continue;
                    pieceList = s.getPieces();
                } else {
                    pieceList = FortressBuilder.build(w.seed, chunk);
                }
                if (pieceList.isEmpty()) continue;
                BoundingBox all = new net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer(pieceList).calculateBoundingBox();
                if (Math.max(Math.max(Math.abs(all.minX()), Math.abs(all.maxX())), Math.max(Math.abs(all.minZ()), Math.abs(all.maxZ()))) > Structures.WORLD_EDGE)
                    continue; // sticks out past the edge of the world
                long area = 0;
                List<BoundingBox> parts = new ArrayList<>();
                int[] crossings = new int[4];
                List<BoundingBox> roads = new ArrayList<>();
                for (StructurePiece piece : pieceList) {
                    BoundingBox pb = piece.getBoundingBox();
                    if (piece instanceof NetherFortressPieces.BridgeCrossing) {
                        crossings[0]++;
                        if (pb.minY() <= p.maxY()) roads.add(pb);
                    }
                    else if (piece instanceof NetherFortressPieces.RoomCrossing) crossings[1]++;
                    else if (piece instanceof NetherFortressPieces.CastleSmallCorridorCrossingPiece) crossings[2]++;
                    BoundingBox b = piece.getBoundingBox();
                    parts.add(b);
                    area += (long) b.getXSpan() * b.getZSpan();
                }
                Group group = Group.of(roads), line = Group.line(roads);
                if (p.biome() != null && p.byCrossroads()) {
                    Group ranked = p.lines() ? line : group;
                    if (ranked.size < 2 || !inBiome(w, ranked.boxes, p.biome(), p.biomeFrom(), p.biomeTo())) continue;
                }
                count.incrementAndGet();
                synchronized (best) {
                    // Keep only the biggest few (with their pieces) so memory stays small.
                    Fortress f = new Fortress(x, z, area, all, parts, crossings, group, line);
                    if (best.size() < KEEP || f.rank(p) > best.peek().rank(p)) {
                        best.add(f);
                        if (best.size() > KEEP) best.poll();
                    }
                }
            }
        });
        progress.update(0.98, "Measuring the biggest");
        // Wither skeleton farms: fortress mobs spawn anywhere in the fortress's box on nether bricks
        // (NaturalSpawner.isInNetherFortressBounds), so the best AFK spot covers the most of the box.
        Map<Fortress, MonumentSearch.Spot> afk = new java.util.concurrent.ConcurrentHashMap<>();
        new ArrayList<>(best).parallelStream().forEach(f -> {
            MonumentSearch.Spot s = MonumentSearch.bestSpot(List.of(new MonumentSearch.Monument(0, 0,
                    (f.box.minX() + f.box.maxX()) / 2, (f.box.minZ() + f.box.maxZ()) / 2, f.box)));
            if (s != null) afk.put(f, s);
        });
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
            res.details.put("Crossroads", f.crossings[0] + " (" + f.crossings[1] + " crossing rooms, " + f.crossings[2] + " corridor crossings)");
            if (f.group.size > 1) res.details.put("Joined crossroads", f.group.label() + " at y " + f.group.y + ", corner " + f.group.x + ", " + f.group.z);
            if (f.line.size > 1) res.details.put("Straight line", f.line.size + " " + f.line.shape + " at y " + f.line.y + ", stand " + (f.line.x + 9) + " " + (f.line.y + 5) + " " + (f.line.z + 9));
            MonumentSearch.Spot s = afk.get(f);
            if (s != null) {
                res.details.put("Farm AFK spot", s.x() + ", " + s.y() + ", " + s.z());
                res.details.put("Spawn blocks in range", Fmt.n(s.total()));
            }
            res.details.put("Distance", Fmt.n(p.area().distance(res.x, res.z)));
            res.ref = new int[]{f.x, f.z};
            out.add(res);
        }
        if (p.byCrossroads()) {
            List<Fortress> order = new ArrayList<>(best);
            order.sort(Comparator.comparingLong((Fortress f) -> f.rank(p)).reversed());
            List<Result> sorted = new ArrayList<>();
            for (Fortress f : order) for (Result res : out) if (res.ref[0] == f.x && res.ref[1] == f.z) sorted.add(res);
            out = sorted;
        } else out.sort((a, c) -> Double.compare(c.value, a.value));
        if (out.size() > p.top()) out = new ArrayList<>(out.subList(0, p.top()));
        progress.update(1, Fmt.n(count.get()) + " fortresses in the area");
        return out;
    }

    /**
     * The biggest group of crossroads (19 x 19 bridge crossings) joined side by side at the same height, like
     * Gaider10's CrossroadFinder shapes: 2 = double, 3 = triple line or corner, 4 = quad line, square or other, 5 = quint.
     * x, z, y: the north-west crossroad's corner and floor height.
     */
    /** True if the biome is the same everywhere in these boxes (sideways), every 4 blocks, at every height from y0 to y1. */
    private static boolean inBiome(WorldGen w, List<BoundingBox> boxes, String biome, int y0, int y1) {
        for (BoundingBox b : boxes)
            for (int y = y0; ; y = Math.min(y + 4, y1)) {
                for (int x = b.minX(); ; x = Math.min(x + 4, b.maxX())) {
                    for (int z = b.minZ(); ; z = Math.min(z + 4, b.maxZ())) {
                        if (!w.name(w.biome(x, y, z)).equals(biome)) return false;
                        if (z == b.maxZ()) break;
                    }
                    if (x == b.maxX()) break;
                }
                if (y == y1) break;
            }
        return true;
    }

    /** boxes: the crossroads in the group. */
    record Group(int size, String shape, int x, int y, int z, List<BoundingBox> boxes) {
        static final Group NONE = new Group(0, "", 0, 0, 0, List.of());

        String label() {
            return switch (size) {
                case 2 -> "double";
                case 3 -> "triple " + shape;
                case 4 -> "quad " + shape;
                case 5 -> "quint " + shape;
                default -> size + " " + shape;
            };
        }

        /** The longest straight run of crossroads side by side at the same height; x, z: the first one's corner. */
        static Group line(List<BoundingBox> roads) {
            Group best = NONE;
            java.util.Set<String> at = new java.util.HashSet<>();
            for (BoundingBox b : roads) at.add(b.minX() + "," + b.minY() + "," + b.minZ());
            for (BoundingBox b : roads)
                for (int axis = 0; axis < 2; axis++) {
                    int dx = axis == 0 ? 19 : 0, dz = axis == 0 ? 0 : 19;
                    if (at.contains((b.minX() - dx) + "," + b.minY() + "," + (b.minZ() - dz))) continue; // not the start of a run
                    int n = 1;
                    while (at.contains((b.minX() + n * dx) + "," + b.minY() + "," + (b.minZ() + n * dz))) n++;
                    List<BoundingBox> run = new ArrayList<>();
                    for (int k = 0; k < n; k++)
                        run.add(new BoundingBox(b.minX() + k * dx, b.minY(), b.minZ() + k * dz, b.maxX() + k * dx, b.maxY(), b.maxZ() + k * dz));
                    Group g = new Group(n, axis == 0 ? "east-west" : "north-south", b.minX(), b.minY(), b.minZ(), run);
                    if (n > best.size || n == best.size && b.minY() < best.y) best = g;
                }
            return best;
        }

        static Group of(List<BoundingBox> roads) {
            Group best = NONE;
            int n = roads.size();
            boolean[] seen = new boolean[n];
            for (int i = 0; i < n; i++) {
                if (seen[i]) continue;
                List<BoundingBox> g = new ArrayList<>();
                java.util.ArrayDeque<Integer> todo = new java.util.ArrayDeque<>(List.of(i));
                seen[i] = true;
                while (!todo.isEmpty()) {
                    BoundingBox a = roads.get(todo.poll());
                    g.add(a);
                    for (int j = 0; j < n; j++) {
                        BoundingBox b = roads.get(j);
                        if (seen[j] || b.minY() != a.minY()) continue;
                        int dx = Math.abs(b.minX() - a.minX()), dz = Math.abs(b.minZ() - a.minZ());
                        if (dx == 19 && dz == 0 || dx == 0 && dz == 19) {
                            seen[j] = true;
                            todo.add(j);
                        }
                    }
                }
                int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
                for (BoundingBox b : g) {
                    minX = Math.min(minX, b.minX());
                    maxX = Math.max(maxX, b.minX());
                    minZ = Math.min(minZ, b.minZ());
                    maxZ = Math.max(maxZ, b.minZ());
                }
                int w = (maxX - minX) / 19 + 1, d = (maxZ - minZ) / 19 + 1;
                String shape = w == 1 || d == 1 ? "line" : g.size() == 3 ? "corner" : g.size() == 4 && w == 2 && d == 2 ? "square" : "blob";
                int y = g.get(0).minY();
                Group cand = new Group(g.size(), shape, minX, y, minZ, g);
                if (cand.size > best.size || cand.size == best.size && (score(cand) > score(best) || score(cand) == score(best) && y < best.y)) best = cand;
            }
            return best;
        }

        /** Squares beat lines beat corners/blobs of the same size. */
        static int score(Group g) {
            return switch (g.shape) {
                case "square" -> 3;
                case "line" -> 2;
                default -> 1;
            };
        }
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
            return !bastion.value().biomes().contains(w.noiseBiome(cx, 33, cz));
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
