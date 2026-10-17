package dev.steelaspect.seedfinder.engine;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A world's biome parameter tree (MultiNoiseBiomeSource's Climate.RTree), flattened for the graphics card, and the
 * game's way of settling exact ties.
 *
 * <p>Where two or more biomes are exactly as close to the climate, Climate.RTree keeps the one it found last on that
 * thread. When the game fills a chunk's biomes (LevelChunkSection.fillBiomesFromNoise: sections from the bottom, then
 * x, y, z inside each), the last one is the cell filled just before. {@link #inGame} does the same: it runs the game's
 * own lookup for the cells before this one, back to the first that isn't a tie. So ties come out as in a world the
 * game generates, the same every time, instead of depending on what a search thread looked up before.
 */
final class ClimateTree {
    /** 14 longs per node: min and max of the 7 climate values. */
    final long[] ranges;
    /** 3 ints per node: first child, child count, leaf index (or -1 for an inner node). Node 0 is the root. */
    final int[] links;
    /** Each leaf's biome, and the game's leaf object (for the last-result slot). */
    final List<Holder<Biome>> leaves = new ArrayList<>();
    private final List<Object> leafObjects = new ArrayList<>();
    private final Climate.ParameterList<Holder<Biome>> parameters;
    private final ThreadLocal<Object> lastResult;
    private final WorldGen w;
    private final int minQuartY;
    /** How far back a chain of ties is followed. */
    private static final int CHAIN = 64;

    @SuppressWarnings("unchecked")
    ClimateTree(WorldGen w) throws ReflectiveOperationException {
        this.w = w;
        minQuartY = w.heights.getMinY() >> 2;
        Method m = w.source.getClass().getDeclaredMethod("parameters");
        m.setAccessible(true);
        parameters = (Climate.ParameterList<Holder<Biome>>) m.invoke(w.source);
        Object rtree = field(parameters, "index");
        lastResult = (ThreadLocal<Object>) field(rtree, "lastResult");
        List<Object> nodes = new ArrayList<>();
        nodes.add(field(rtree, "root"));
        List<long[]> r = new ArrayList<>();
        List<int[]> l = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            Object node = nodes.get(i);
            Object[] space = (Object[]) field(node, "parameterSpace");
            long[] box = new long[14];
            for (int k = 0; k < 7; k++) {
                Climate.Parameter p = (Climate.Parameter) space[k];
                box[k * 2] = p.min();
                box[k * 2 + 1] = p.max();
            }
            r.add(box);
            if (node.getClass().getSimpleName().equals("Leaf")) {
                l.add(new int[]{0, 0, leaves.size()});
                leaves.add((Holder<Biome>) field(node, "value"));
                leafObjects.add(node);
            } else {
                Object[] children = (Object[]) field(node, "children");
                l.add(new int[]{nodes.size(), children.length, -1});
                Collections.addAll(nodes, children);
            }
        }
        ranges = new long[r.size() * 14];
        links = new int[l.size() * 3];
        for (int i = 0; i < r.size(); i++) {
            System.arraycopy(r.get(i), 0, ranges, i * 14, 14);
            System.arraycopy(l.get(i), 0, links, i * 3, 3);
        }
    }

    static long[] values(Climate.TargetPoint t) {
        return new long[]{t.temperature(), t.humidity(), t.continentalness(), t.erosion(), t.depth(), t.weirdness(), 0L};
    }

    private long distance(int node, long[] t) {
        long s = 0;
        for (int k = 0; k < 7; k++) {
            long a = t[k] - ranges[node * 14 + k * 2 + 1], b = ranges[node * 14 + k * 2] - t[k];
            long d = a > 0 ? a : Math.max(b, 0L);
            s += d * d;
        }
        return s;
    }

    /** Whether two or more biomes are exactly the closest to these climate values. */
    boolean isTie(long[] t) {
        if (links[2] >= 0) return false;
        long best = Long.MAX_VALUE;
        int count = 0;
        int[] stack = new int[32], next = new int[32];
        int sp = 1;
        while (sp > 0) {
            int node = stack[sp - 1], c = next[sp - 1];
            if (c >= links[node * 3 + 1]) {
                sp--;
                continue;
            }
            next[sp - 1] = c + 1;
            int child = links[node * 3] + c;
            long d = distance(child, t);
            if (d > best) continue;
            if (links[child * 3 + 2] >= 0) {
                if (d < best) {
                    best = d;
                    count = 1;
                } else {
                    count++;
                }
            } else {
                stack[sp] = child;
                next[sp] = 0;
                sp++;
            }
        }
        return count > 1;
    }

    /** The biome the game stores for this cell when it generates the chunk (see the class comment). */
    Holder<Biome> inGame(int qx, int qy, int qz) {
        int[][] chain = new int[CHAIN][];
        long[][] targets = new long[CHAIN][];
        chain[0] = new int[]{qx, qy, qz};
        targets[0] = values(w.random.sampler().sample(qx, qy, qz));
        int k = 0;
        while (k < CHAIN - 1) {
            int[] prev = previous(chain[k]);
            if (prev == null) break;
            chain[k + 1] = prev;
            targets[k + 1] = values(w.random.sampler().sample(prev[0], prev[1], prev[2]));
            k++;
            if (!isTie(targets[k])) break;
        }
        // From the oldest cell forward, each with the cell before as the game's last result.
        Object last = null;
        for (int i = k; i >= 0; i--) {
            lastResult.set(last);
            Climate.TargetPoint t = new Climate.TargetPoint(targets[i][0], targets[i][1], targets[i][2], targets[i][3], targets[i][4], targets[i][5]);
            parameters.findValue(t);
            last = lastResult.get();
        }
        @SuppressWarnings("unchecked")
        Holder<Biome> biome = (Holder<Biome>) leafValue(last);
        return biome;
    }

    private Object leafValue(Object leaf) {
        return leaves.get(leafObjects.indexOf(leaf));
    }

    /** The cell the game fills just before this one in the same chunk, or null for the chunk's first. */
    private int[] previous(int[] q) {
        int ly = Math.floorMod(q[1] - minQuartY, 4), section = Math.floorDiv(q[1] - minQuartY, 4);
        int lx = q[0] & 3, lz = q[2] & 3;
        if (lz > 0) return new int[]{q[0], q[1], q[2] - 1};
        if (ly > 0) return new int[]{q[0], q[1] - 1, q[2] + 3};
        if (lx > 0) return new int[]{q[0] - 1, q[1] + 3, q[2] + 3};
        if (section > 0) return new int[]{q[0] + 3, q[1] - 1, q[2] + 3};
        return null;
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                // look in the superclass
            }
        }
        throw new NoSuchFieldException(o.getClass().getName() + "." + name);
    }
}
