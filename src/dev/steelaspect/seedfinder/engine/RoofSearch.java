package dev.steelaspect.seedfinder.engine;

import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

/**
 * Bedrock patterns in the nether roof (y 123 to 126), e.g. for a wither cage. Idea and default pattern from nether9
 * by spadix0 (MIT-0): a hole at y 123, a full 3 x 3 of bedrock at y 124 and a ring of 8 at y 125. The bedrock comes
 * from the game's own rule (SurfaceRuleData.nether, "bedrock_roof"): below y 123 never, at y 127 always, in between
 * bedrock if the block's own random float is at least the gradient (20% more likely each layer up).
 */
public final class RoofSearch {
    /** The roof layers a pattern covers, bottom first. */
    public static final int Y0 = 123, LAYERS = 4, SIZE = 5;
    /** The gradient's ends: true (no bedrock) at and below, false (bedrock) at and above. */
    private static final int TRUE_AT = 122, FALSE_AT = 127;
    /**
     * nether9's default, one row of 5 per z (north first), layers bottom first separated by "/":
     * '#' bedrock, '.' no bedrock, '?' anything.
     */
    public static final String WITHER_CAGE = "?????,?????,??.??,?????,?????/?????,?###?,?###?,?###?,?????/?????,?###?,?#?#?,?###?,?????/?????,?????,?????,?????,?????";
    private static final int TILE = 64;

    public record Params(Search area, String pattern, int top) {}

    private RoofSearch() {}

    public static List<Result> run(Params p, Progress progress) {
        WorldGen w = WorldGen.of(p.area().seed(), Dim.NETHER, WorldType.NORMAL);
        PositionalRandomFactory roof = w.random.getOrCreateRandomFactory(Identifier.withDefaultNamespace("bedrock_roof"));
        char[][][] pat = parse(p.pattern());
        Search a = p.area();
        int x0 = a.centerX() - a.radius(), z0 = a.centerZ() - a.radius(), side = 2 * a.radius() + 1;
        int tiles = Math.ceilDiv(side, TILE);
        long all = (long) tiles * tiles;
        List<int[]> found = Collections.synchronizedList(new ArrayList<>());
        AtomicLong done = new AtomicLong();
        long every = Math.max(1, all / 200);
        progress.update(0, "Reading the nether roof");
        LongStream.range(0, all).parallel().forEach(t -> {
            progress.check();
            int tx = x0 + (int) (t / tiles) * TILE, tz = z0 + (int) (t % tiles) * TILE;
            int n = TILE + SIZE - 1;
            // Every layer the pattern looks at, for the tile plus the pattern's reach.
            boolean[][][] bed = new boolean[LAYERS][][];
            for (int l = 0; l < LAYERS; l++) {
                if (!uses(pat[l])) continue;
                bed[l] = new boolean[n][n];
                for (int i = 0; i < n; i++)
                    for (int j = 0; j < n; j++) bed[l][i][j] = bedrock(roof, tx - 2 + i, Y0 + l, tz - 2 + j);
            }
            for (int i = 0; i < TILE; i++)
                for (int j = 0; j < TILE; j++) {
                    int x = tx + i, z = tz + j;
                    if (x - x0 >= side || z - z0 >= side) continue;
                    if (matches(pat, bed, i, j)) found.add(new int[]{x, z});
                }
            long d = done.incrementAndGet();
            if (d % every == 0) progress.update((double) d / all, "Reading the nether roof · " + Fmt.n(found.size()) + " found");
        });
        List<int[]> list = new ArrayList<>(found);
        list.sort((u, v) -> Long.compare(a.distance(u[0], u[1]), a.distance(v[0], v[1])));
        List<Result> out = new ArrayList<>();
        for (int[] f : list.subList(0, Math.min(p.top(), list.size()))) {
            Result r = new Result();
            r.kind = Result.Kind.ROOF;
            r.dim = Dim.NETHER;
            r.name = "Roof pattern";
            r.x = f[0];
            r.z = f[1];
            r.y = Y0;
            r.minX = f[0] - 2;
            r.maxX = f[0] + 2;
            r.minZ = f[1] - 2;
            r.maxZ = f[1] + 2;
            r.value = -a.distance(f[0], f[1]);
            r.size = Fmt.n(a.distance(f[0], f[1]));
            r.details.put("Roof y 123 / 124 / 125 / 126", picture(roof, f[0], f[1]));
            r.ref = ref(pat, f[0], f[1]);
            out.add(r);
        }
        progress.update(1, Fmt.n(list.size()) + " matching spots in the area");
        return out;
    }

    /** Is there bedrock here? The game's nether roof rule. */
    public static boolean bedrock(PositionalRandomFactory roof, int x, int y, int z) {
        if (y <= TRUE_AT) return false;
        if (y >= FALSE_AT) return true;
        double d = Mth.map(y, TRUE_AT, FALSE_AT, 1.0, 0.0);
        return !(roof.at(x, y, z).nextFloat() < d);
    }

    private static boolean matches(char[][][] pat, boolean[][][] bed, int i, int j) {
        for (int l = 0; l < LAYERS; l++) {
            if (bed[l] == null) continue;
            for (int dz = 0; dz < SIZE; dz++)
                for (int dx = 0; dx < SIZE; dx++) {
                    char c = pat[l][dz][dx];
                    if (c == '?') continue;
                    if (bed[l][i + dx][j + dz] != (c == '#')) return false;
                }
        }
        return true;
    }

    private static boolean uses(char[][] layer) {
        for (char[] row : layer) for (char c : row) if (c != '?') return true;
        return false;
    }

    /** Layers bottom first separated by "/", rows north first separated by ",", 5 of '#', '.' or '?' each. */
    public static char[][][] parse(String s) {
        String[] layers = s.replace(" ", "").split("/");
        if (layers.length != LAYERS) throw new IllegalArgumentException("A roof pattern has 4 layers (y 123 to 126), separated by /");
        char[][][] out = new char[LAYERS][SIZE][];
        for (int l = 0; l < LAYERS; l++) {
            String[] rows = layers[l].split(",");
            if (rows.length != SIZE) throw new IllegalArgumentException("Each roof layer has 5 rows, separated by commas");
            for (int r = 0; r < SIZE; r++) {
                if (!rows[r].matches("[#.?]{5}")) throw new IllegalArgumentException("Each row is 5 of # (bedrock), . (none) or ? (anything)");
                out[l][r] = rows[r].toCharArray();
            }
        }
        return out;
    }

    /**
     * For the in-game check: x, z, then one number per block the pattern cares about,
     * ((layer * 5 + row) * 5 + column) * 2 + (1 if it must be bedrock).
     */
    private static int[] ref(char[][][] pat, int x, int z) {
        List<Integer> cells = new ArrayList<>(List.of(x, z));
        for (int l = 0; l < LAYERS; l++)
            for (int r = 0; r < SIZE; r++)
                for (int c = 0; c < SIZE; c++)
                    if (pat[l][r][c] != '?') cells.add(((l * SIZE + r) * SIZE + c) * 2 + (pat[l][r][c] == '#' ? 1 : 0));
        return cells.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The real roof around a spot, layers y 123 to 126, as text (# bedrock, . none). */
    static String picture(PositionalRandomFactory roof, int x, int z) {
        StringBuilder b = new StringBuilder();
        for (int l = 0; l < LAYERS; l++) {
            if (l > 0) b.append(" / ");
            for (int dz = -2; dz <= 2; dz++) {
                if (dz > -2) b.append(',');
                for (int dx = -2; dx <= 2; dx++) b.append(bedrock(roof, x + dx, Y0 + l, z + dz) ? '#' : '.');
            }
        }
        return b.toString();
    }
}
