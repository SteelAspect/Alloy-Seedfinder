package dev.steelaspect.seedfinder.engine;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

/**
 * AFK spots for 2, 3 or 4 ocean monuments or witch huts: within 128 blocks (sideways) of the middle of every one, so
 * each gives a real share of mobs. Ranked by how many blocks of spawning space are in range, by Minecraft's rules
 * (NaturalSpawner): a mob spawns more than 24 and at most 128 blocks from the player, guardians spawn anywhere in a
 * monument's box (58 x 23 x 58) and witches anywhere in a hut's box (7 x 7 x 9, at the hut's real height).
 *
 * <p>Ideas from DoubleTripleQuadMonumentFinder by BrianBLee1201 (MIT licence): the 128-from-every-middle rule, count
 * the spawnable blocks exactly, start the AFK search from several points and refine it in steps of 4, 2 and 1, drop
 * lone monuments by distance before any biome check, and give the nether coordinates for a portal. Monument
 * positions, biome checks and boxes here still come from Minecraft's own code.
 *
 * <p>Scales to the whole world for triples and quads: regions are scanned in tiles, so memory stays small, and only
 * monuments with close neighbours ever get a biome check.
 */
public final class MonumentSearch {
    /** No spawning within 24 blocks of the player; mobs further than 128 despawn at once, so none spawn there. */
    private static final int NEAR2 = 24 * 24, FAR = 128, FAR2 = FAR * FAR;
    /** A monument box reaches 29 blocks west/north of its chunk's corner, which is its middle (OceanMonumentStructure). */
    private static final int BOX_WEST = 29;
    /** Two monuments can only share an AFK spot if their middles are at most 2 x 128 blocks apart. */
    private static final long PAIR2 = (long) (2 * FAR) * (2 * FAR);
    /** Regions per side of one work tile. */
    private static final int TILE = 64;
    /** floor(sqrt(i)) for every distance that matters. */
    private static final int[] ISQRT = new int[FAR2 + 1];

    static {
        for (int i = 0; i <= FAR2; i++) ISQRT[i] = (int) Math.sqrt(i);
    }

    /** What to group. slack: how far (blocks) the middle can be from where the placement maths alone puts it. */
    public enum Kind {
        MONUMENT("monuments", BuiltinStructureSets.OCEAN_MONUMENTS, BuiltinStructures.OCEAN_MONUMENT, 0),
        HUT("witch huts", BuiltinStructureSets.SWAMP_HUTS, BuiltinStructures.SWAMP_HUT, 2);

        public final String label;
        final ResourceKey<StructureSet> set;
        final ResourceKey<Structure> structure;
        final int slack;

        Kind(String label, ResourceKey<StructureSet> set, ResourceKey<Structure> structure, int slack) {
            this.label = label;
            this.set = set;
            this.structure = structure;
            this.slack = slack;
        }
    }

    /** @param minDist only spots at least this far out on x or z (skips the middle of the area) */
    public record Params(Search area, Kind kind, int minCount, int minDist, int top) {}

    /**
     * One monument or hut: its region, its chunk's corner (where /locate points), its middle (mx, mz) and its box
     * from the game. A monument's middle is its chunk's corner; a hut's is the middle of its box.
     */
    record Monument(int rx, int rz, int x, int z, int mx, int mz, BoundingBox box) {
        Monument(int rx, int rz, int x, int z, BoundingBox box) {
            this(rx, rz, x, z, x, z, box);
        }

        long key() {
            return pack(rx, rz);
        }
    }

    /** An AFK spot: where to stand (feet), and how many spawnable blocks of each monument are in range. */
    record Spot(List<Monument> monuments, int x, int y, int z, int[] covered, long total) {}

    private static final Spot NONE = new Spot(List.of(), 0, 0, 0, new int[0], 0);
    static final BoundingBox NO_MONUMENT = new BoundingBox(0, 0, 0, 0, 0, 0);
    private static final Monument NOTHING = new Monument(0, 0, 0, 0, NO_MONUMENT);

    private MonumentSearch() {}

    public static List<Result> run(Params p, Progress progress) {
        WorldGen w = WorldGen.of(p.area().seed(), Dim.OVERWORLD, p.area().type());
        RandomSpreadStructurePlacement placement = Structures.placement(w, p.kind().set);
        Holder<Structure> monument = Structures.structure(w, p.kind().structure);
        int[] r = Structures.regions(p.area(), placement.spacing());
        // 2x2 blocks of regions, by their top-left region; one extra on the low side so groups that straddle the
        // area's edge are seen too (their AFK spot still has to be inside).
        int i0 = r[0] - 1, j0 = r[2] - 1, rows = r[1] - i0 + 1, cols = r[3] - j0 + 1;
        int tilesX = Math.ceilDiv(rows, TILE), tilesZ = Math.ceilDiv(cols, TILE);
        long tiles = (long) tilesX * tilesZ;
        Map<String, Spot> spots = new ConcurrentHashMap<>();
        AtomicLong done = new AtomicLong();
        long every = Math.max(1, tiles / 400);
        progress.update(0, "Finding " + p.kind().label);
        LongStream.range(0, tiles).parallel().forEach(t -> {
            progress.check();
            int ti = (int) (t / tilesZ), tj = (int) (t % tilesZ);
            int a = i0 + ti * TILE, b = j0 + tj * TILE;
            int n = Math.min(TILE, i0 + rows - a), m = Math.min(TILE, j0 + cols - b);
            if (!ringSkips(p, a, b, n, m, placement.spacing() * 16)) scanTile(w, placement, monument, p, a, b, n, m, spots);
            long d = done.incrementAndGet();
            if (d % every == 0) progress.update((double) d / tiles, "Finding " + p.kind().label + " · " + Fmt.n(spots.size()) + " spots so far");
        });

        List<Spot> list = new ArrayList<>();
        for (Spot s : spots.values()) if (s != NONE) list.add(s);
        dropContained(list);
        list.sort((x, y) -> x.total != y.total ? Long.compare(y.total, x.total)
                : Long.compare(p.area().distance(x.x, x.z), p.area().distance(y.x, y.z)));
        List<Result> out = new ArrayList<>();
        for (Spot s : list.subList(0, Math.min(p.top(), list.size()))) out.add(result(s, p));
        progress.update(1, list.isEmpty() ? "No spots with " + p.minCount() + " or more " + p.kind().label + " here"
                : count(list, 4) + " quad, " + count(list, 3) + " triple and " + count(list, 2) + " double spots");
        return out;
    }

    private static long count(List<Spot> list, int n) {
        return list.stream().filter(s -> s.monuments.size() == n).count();
    }

    /** True if every AFK spot this tile could give is closer than minDist on both axes. */
    private static boolean ringSkips(Params p, int a, int b, int n, int m, int size) {
        if (p.minDist() <= 0) return false;
        long reach = BOX_WEST + FAR + 1;
        long x0 = (long) a * size - reach, x1 = (long) (a + n + 1) * size + reach;
        long z0 = (long) b * size - reach, z1 = (long) (b + m + 1) * size + reach;
        return Math.max(Math.abs(x0), Math.abs(x1)) < p.minDist() && Math.max(Math.abs(z0), Math.abs(z1)) < p.minDist();
    }

    /** All 2x2 region blocks whose top-left region is in [a, a+n) x [b, b+m). */
    private static void scanTile(WorldGen w, RandomSpreadStructurePlacement placement, Holder<Structure> monument, Params p,
                                 int a, int b, int n, int m, Map<String, Spot> spots) {
        // Where each region's monument would be, if its biomes allow one (cheap: just the placement maths).
        int w1 = m + 1;
        int[] cx = new int[(n + 1) * w1], cz = new int[(n + 1) * w1];
        for (int i = 0; i <= n; i++)
            for (int j = 0; j <= m; j++) {
                ChunkPos c = Structures.chunkIn(placement, w.seed, a + i, b + j);
                cx[i * w1 + j] = c.x;
                cz[i * w1 + j] = c.z;
            }
        Map<Long, Monument> real = new HashMap<>();
        int[] node = new int[4];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < m; j++) {
                node[0] = i * w1 + j;
                node[1] = (i + 1) * w1 + j;
                node[2] = i * w1 + j + 1;
                node[3] = (i + 1) * w1 + j + 1;
                // Which pairs are close enough to share an AFK spot.
                int close = 0;
                for (int k = 0, e = 0; k < 4; k++)
                    for (int l = k + 1; l < 4; l++, e++)
                        if (near(cx[node[k]], cz[node[k]], cx[node[l]], cz[node[l]], p.kind().slack)) close |= 1 << e;
                if (Integer.bitCount(close) < p.minCount() * (p.minCount() - 1) / 2) continue;
                List<Integer> groups = groups(close, p.minCount());
                if (groups.isEmpty()) continue;
                // Biome checks (the expensive part), only for regions in a possible group, and a group stops at its
                // first region without one.
                Monument[] ms = new Monument[4];
                int checked = 0, ok = 0;
                List<Integer> worked = new ArrayList<>();
                for (int g : groups) {
                    // Biggest groups first; a group inside one that worked here isn't tried.
                    if ((g & checked & ~ok) != 0 || worked.stream().anyMatch(x -> (x & g) == g)) continue;
                    boolean all = true;
                    for (int k = 0; k < 4 && all; k++) {
                        if ((g & (1 << k)) == 0) continue;
                        if ((checked & (1 << k)) == 0) {
                            int ri = a + i + (k & 1), rj = b + j + (k >> 1), k0 = k;
                            Monument found = real.computeIfAbsent(pack(ri, rj),
                                    key -> find(w, p.kind(), monument, ri, rj, new ChunkPos(cx[node[k0]], cz[node[k0]])));
                            checked |= 1 << k;
                            if (found != NOTHING) {
                                ms[k] = found;
                                ok |= 1 << k;
                            }
                        }
                        all = (ok & (1 << k)) != 0;
                    }
                    if (!all) continue;
                    List<Monument> group = new ArrayList<>();
                    for (int k = 0; k < 4; k++) if ((g & (1 << k)) != 0) group.add(ms[k]);
                    String key = key(group);
                    Spot s = spots.get(key);
                    if (s == null) {
                        s = bestSpot(group);
                        if (s == null || !p.area().inside(s.x, s.z) || Math.max(Math.abs(s.x), Math.abs(s.z)) < p.minDist()) s = NONE;
                        spots.putIfAbsent(key, s);
                    }
                    if (s != NONE) worked.add(g);
                }
            }
    }

    /** Groups of the 4 nodes (bit masks) with at least min members where every pair is close, biggest first. */
    private static List<Integer> groups(int close, int min) {
        List<Integer> out = new ArrayList<>();
        for (int size = 4; size >= min; size--)
            for (int g = 1; g < 16; g++) {
                if (Integer.bitCount(g) != size) continue;
                boolean all = true;
                for (int k = 0, e = 0; k < 4; k++)
                    for (int l = k + 1; l < 4; l++, e++)
                        if ((g & (1 << k)) != 0 && (g & (1 << l)) != 0 && (close & (1 << e)) == 0) all = false;
                if (all) out.add(g);
            }
        return out;
    }

    /** Whether things in these two chunks can have middles at most 256 blocks apart. */
    private static boolean near(int cx1, int cz1, int cx2, int cz2, int slack) {
        long dx = Math.max(0, Math.abs(cx1 - cx2) * 16L - slack), dz = Math.max(0, Math.abs(cz1 - cz2) * 16L - slack);
        return dx * dx + dz * dz <= PAIR2;
    }

    /** The monument or hut that generates in this chunk (the game's own generation), or NOTHING. */
    private static Monument find(WorldGen w, Kind kind, Holder<Structure> structure, int rx, int rz, ChunkPos chunk) {
        int x = chunk.getMinBlockX(), z = chunk.getMinBlockZ();
        if (kind == Kind.MONUMENT) {
            BoundingBox box = monumentBox(w, structure, chunk);
            return box == NO_MONUMENT ? NOTHING : new Monument(rx, rz, x, z, box);
        }
        StructureStart s = Structures.start(w, structure, chunk, true);
        if (s == null) return NOTHING;
        // The hut settles on the average ground height of its footprint when its chunk is decorated.
        BoundingBox b = s.getPieces().get(0).getBoundingBox();
        BoundingBox box = b.moved(0, HutSearch.exactY(w, b) - b.minY(), 0);
        return new Monument(rx, rz, x, z, (box.minX() + box.maxX()) / 2, (box.minZ() + box.maxZ()) / 2, box);
    }

    /** The monument's box if one generates in this chunk (the game's own generation), else NO_MONUMENT. */
    static BoundingBox monumentBox(WorldGen w, Holder<Structure> monument, ChunkPos chunk) {
        if (!surroundingOk(w, chunk)) return NO_MONUMENT;
        StructureStart s = Structures.start(w, monument, chunk, false);
        return s == null ? NO_MONUMENT : s.getBoundingBox();
    }

    /**
     * The first biome rule of OceanMonumentStructure: every biome within 29 blocks of the chunk's (9, 9) at sea level
     * must be ocean or river. The game reads all those biomes (BiomeSource.getBiomesWithin) and then checks them;
     * this reads the same points but stops at the first one that fails: first a spread-out few of them (most places
     * fail there), then all of them in the game's order. If it passes, the game's full generation still runs (and
     * checks everything again).
     */
    static boolean surroundingOk(WorldGen w, ChunkPos chunk) {
        int x = chunk.getBlockX(9), y = w.generator.getSeaLevel(), z = chunk.getBlockZ(9), r = 29;
        int x0 = QuartPos.fromBlock(x - r), y0 = QuartPos.fromBlock(y - r), z0 = QuartPos.fromBlock(z - r);
        int x1 = QuartPos.fromBlock(x + r), y1 = QuartPos.fromBlock(y + r), z1 = QuartPos.fromBlock(z + r);
        Climate.Sampler sampler = w.random.sampler();
        int[] ys = {y0, (y0 + y1) / 2, y1};
        for (int qz = z0; qz <= z1; qz = qz == z1 ? z1 + 1 : Math.min(z1, qz + 4))
            for (int qx = x0; qx <= x1; qx = qx == x1 ? x1 + 1 : Math.min(x1, qx + 4))
                for (int qy : ys)
                    if (!w.source.getNoiseBiome(qx, qy, qz, sampler).is(BiomeTags.REQUIRED_OCEAN_MONUMENT_SURROUNDING)) return false;
        for (int qz = z0; qz <= z1; qz++)
            for (int qx = x0; qx <= x1; qx++)
                for (int qy = y0; qy <= y1; qy++)
                    if (!w.source.getNoiseBiome(qx, qy, qz, sampler).is(BiomeTags.REQUIRED_OCEAN_MONUMENT_SURROUNDING)) return false;
        return true;
    }

    // ------------------------------------------------------------------ AFK spot

    /**
     * The AFK spot within 128 blocks of every monument's middle with the most spawnable blocks in range. Null if no
     * block is close enough to all of them.
     *
     * <p>Triples and quads are rare and their shared area is small, so every block and height is tried: the exact
     * best (about half a second each). Doubles are common: a grid every 8 blocks at the boxes' middle height, a climb
     * from the best few in steps of 4, 2 and 1, then the best height and another climb (the same as trying every
     * block on 80 of 80 tested).
     */
    static Spot bestSpot(List<Monument> ms) {
        int x0 = Integer.MIN_VALUE, x1 = Integer.MAX_VALUE, z0 = Integer.MIN_VALUE, z1 = Integer.MAX_VALUE, y0 = 999, y1 = -999;
        for (Monument m : ms) {
            x0 = Math.max(x0, m.mx - FAR);
            x1 = Math.min(x1, m.mx + FAR);
            z0 = Math.max(z0, m.mz - FAR);
            z1 = Math.min(z1, m.mz + FAR);
            y0 = Math.min(y0, m.box.minY());
            y1 = Math.max(y1, m.box.maxY());
        }
        if (x0 > x1 || z0 > z1) return null;
        if (ms.size() >= 3) return everyBlock(ms, x0, x1, z0, z1, y0, y1);
        int y = (y0 + y1) / 2;
        List<Spot> starts = new ArrayList<>();
        long area = (long) (x1 - x0 + 1) * (z1 - z0 + 1);
        for (int step : new int[]{8, area <= 40_000 ? 1 : 2}) {
            for (int x = x0; x <= x1; x += step)
                for (int z = z0; z <= z1; z += step) {
                    Spot s = spot(ms, x, y, z);
                    if (s != null) keepBest(starts, s, 6);
                }
            if (!starts.isEmpty()) break; // a finer grid only when no grid point was close to all of them (a thin overlap)
        }
        if (starts.isEmpty()) return null;
        Spot best = null;
        for (Spot s : starts) {
            Spot c = climb(ms, s, 4);
            if (best == null || c.total > best.total) best = c;
        }
        best = bestHeight(ms, best, y0, y1);
        best = bestHeight(ms, climb(ms, best, 2), y0, y1);
        return best;
    }

    private static Spot everyBlock(List<Monument> ms, int x0, int x1, int z0, int z1, int y0, int y1) {
        Spot best = null;
        int mid = (y0 + y1) / 2;
        for (int d = 0; d <= y1 - y0; d++) // middle height first, so ties keep it
            for (int y : d == 0 ? new int[]{mid} : new int[]{mid + d, mid - d}) {
                if (y < y0 || y > y1) continue;
                for (int x = x0; x <= x1; x++)
                    for (int z = z0; z <= z1; z++) {
                        Spot s = spot(ms, x, y, z);
                        if (s != null && (best == null || s.total > best.total)) best = s;
                    }
            }
        return best;
    }

    private static void keepBest(List<Spot> list, Spot s, int n) {
        if (list.size() >= n && s.total <= list.get(list.size() - 1).total) return;
        list.add(s);
        list.sort((a, b) -> Long.compare(b.total, a.total));
        if (list.size() > n) list.remove(n);
    }

    /** Moves to the best of the 8 neighbours while that's better, at this step and then each smaller one. */
    private static Spot climb(List<Monument> ms, Spot s, int firstStep) {
        for (int step = firstStep; step >= 1; step /= 2) {
            boolean moved = true;
            while (moved) {
                moved = false;
                Spot next = s;
                for (int dx = -step; dx <= step; dx += step)
                    for (int dz = -step; dz <= step; dz += step) {
                        if (dx == 0 && dz == 0) continue;
                        Spot c = spot(ms, s.x + dx, s.y, s.z + dz);
                        if (c != null && c.total > next.total) next = c;
                    }
                if (next != s) {
                    s = next;
                    moved = true;
                }
            }
        }
        return s;
    }

    private static Spot bestHeight(List<Monument> ms, Spot s, int y0, int y1) {
        for (int y = y0; y <= y1; y++) {
            Spot c = spot(ms, s.x, y, s.z);
            if (c != null && c.total > s.total) s = c;
        }
        return s;
    }

    /** The spot at this block, or null if it's more than 128 blocks (sideways) from a monument's middle. */
    private static Spot spot(List<Monument> ms, int x, int y, int z) {
        for (Monument m : ms) if ((long) (x - m.mx) * (x - m.mx) + (long) (z - m.mz) * (z - m.mz) > FAR2) return null;
        int[] covered = new int[ms.size()];
        long total = 0;
        for (int k = 0; k < ms.size(); k++) {
            covered[k] = covered(ms.get(k).box, x, y, z);
            total += covered[k];
        }
        return new Spot(ms, x, y, z, covered, total);
    }

    /**
     * Blocks of the box where a mob could spawn for a player standing in the middle of block (ax, ay, az): squared
     * distance from the player's feet to the block's spawn point (x+0.5, y, z+0.5) more than 576 and at most 16384,
     * exactly as NaturalSpawner measures it. Counted per column.
     */
    static int covered(BoundingBox b, int ax, int ay, int az) {
        int n = 0;
        int xa = Math.max(b.minX(), ax - FAR), xb = Math.min(b.maxX(), ax + FAR);
        for (int x = xa; x <= xb; x++) {
            int dx2 = (x - ax) * (x - ax);
            int reach = ISQRT[FAR2 - dx2];
            int za = Math.max(b.minZ(), az - reach), zb = Math.min(b.maxZ(), az + reach);
            for (int z = za; z <= zb; z++) {
                int h = dx2 + (z - az) * (z - az);
                int up = ISQRT[FAR2 - h];
                n += overlap(b.minY(), b.maxY(), ay - up, ay + up);
                if (h <= NEAR2) {
                    int hole = ISQRT[NEAR2 - h];
                    n -= overlap(b.minY(), b.maxY(), ay - hole, ay + hole);
                }
            }
        }
        return n;
    }

    private static int overlap(int a0, int a1, int b0, int b1) {
        return Math.max(0, Math.min(a1, b1) - Math.max(a0, b0) + 1);
    }

    // ------------------------------------------------------------------ output

    /** Drops a group when a bigger group with all of its monuments was found (a triple inside a quad...). */
    private static void dropContained(List<Spot> list) {
        Map<Long, List<Spot>> byMonument = new HashMap<>();
        for (Spot s : list) for (Monument m : s.monuments) byMonument.computeIfAbsent(m.key(), k -> new ArrayList<>()).add(s);
        list.removeIf(s -> byMonument.get(s.monuments.get(0).key()).stream().anyMatch(big -> big.monuments.size() > s.monuments.size()
                && s.monuments.stream().allMatch(m -> big.monuments.stream().anyMatch(o -> o.key() == m.key()))));
    }

    private static Result result(Spot s, Params p) {
        Result res = new Result();
        res.kind = p.kind() == Kind.HUT ? Result.Kind.HUTS : Result.Kind.MONUMENTS;
        res.dim = Dim.OVERWORLD;
        res.name = new String[]{"", "Single", "Double", "Triple", "Quad"}[s.monuments.size()] + " " + p.kind().label;
        res.x = s.x;
        res.y = s.y;
        res.z = s.z;
        res.minX = s.monuments.stream().mapToInt(m -> m.box.minX()).min().orElse(s.x);
        res.minZ = s.monuments.stream().mapToInt(m -> m.box.minZ()).min().orElse(s.z);
        res.maxX = s.monuments.stream().mapToInt(m -> m.box.maxX()).max().orElse(s.x);
        res.maxZ = s.monuments.stream().mapToInt(m -> m.box.maxZ()).max().orElse(s.z);
        res.value = s.total;
        res.size = Fmt.n(s.total);
        long max = 0;
        StringBuilder each = new StringBuilder();
        for (int k = 0; k < s.monuments.size(); k++) {
            BoundingBox b = s.monuments.get(k).box;
            long volume = (long) b.getXSpan() * b.getYSpan() * b.getZSpan();
            max += volume;
            each.append(Math.round(100.0 * s.covered[k] / volume)).append("% ");
        }
        res.details.put("Count", Integer.toString(s.monuments.size()));
        res.details.put("In range", String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * s.total / max));
        res.details.put("Each", each.toString().trim());
        res.details.put("Stand on", s.x + ", " + (s.y - 1) + ", " + s.z);
        res.details.put("Nether", Math.floorDiv(s.x, 8) + ", " + Math.floorDiv(s.z, 8));
        res.details.put("Distance", Fmt.n(p.area().distance(s.x, s.z)));
        // Monuments: chunk corner x, z each (for /locate). Huts: also the box corner and floor height, to check the
        // hut stands where we say: x, z, boxMinX, y, boxMinZ each.
        int per = p.kind() == Kind.HUT ? 5 : 2;
        res.ref = new int[s.monuments.size() * per];
        for (int k = 0; k < s.monuments.size(); k++) {
            Monument m = s.monuments.get(k);
            res.ref[k * per] = m.x;
            res.ref[k * per + 1] = m.z;
            if (per == 5) {
                res.ref[k * per + 2] = m.box.minX();
                res.ref[k * per + 3] = m.box.minY();
                res.ref[k * per + 4] = m.box.minZ();
            }
        }
        return res;
    }

    private static String key(List<Monument> group) {
        return group.stream().map(m -> m.rx + "," + m.rz).sorted().reduce("", (x, y) -> x + y + ";");
    }

    private static long pack(int a, int b) {
        return ((long) a << 32) | (b & 0xFFFFFFFFL);
    }
}
