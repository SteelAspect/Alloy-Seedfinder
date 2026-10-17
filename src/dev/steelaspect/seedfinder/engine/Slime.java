package dev.steelaspect.seedfinder.engine;

import net.minecraft.world.level.levelgen.WorldgenRandom;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Slime farms measured the way kyoubitt's slime toolchain measures them (github.com/kyoubitt/slime): one floor of
 * slime-chunk blocks at the bottom of the world, everything else empty, and the player standing still. A block counts
 * when a slime could spawn on it: it's in a slime chunk and, from the player's feet to the middle of the block the
 * slime stands in, more than 24 and at most 128 blocks (NaturalSpawner). cq = counted blocks / 256.
 *
 * <p>Two places to stand: level with the floor (the toolchain's cq, which its records use; verified against
 * SlimeFinder.jar), or with your feet 24 blocks up at y -39 over a y -64 floor, which is what Carpet's /perimeterinfo
 * reads in game (calibrated in game: a full floor reads 49,669; /perimeterinfo also counts the block exactly 24 below
 * you, which the game itself leaves out).
 */
public final class Slime {
    public static final long MULT = 0x5DEECE66DL, MASK48 = (1L << 48) - 1;
    /** The game's salt for slime chunks (Slime.checkSlimeSpawnRules). */
    public static final long SALT = 987234911L;
    /** The test rig: an obsidian floor at the bottom of the world, so slimes spawn standing at y -63. */
    public static final int FLOOR_Y = -64;
    /** Chunks around the player's chunk that can hold counted blocks: -8 to 8 both ways. */
    public static final int REACH = 8, SIDE = 17, CELLS = SIDE * SIDE;

    private Slime() {}

    /** Where the player stands, relative to where the slimes stand. */
    public enum Stand {
        LEVEL("Level with the floor (cq)", 0, false),
        Y39("Feet at y -39 (in game)", 24, true);

        public final String label;
        /** How far the player's feet are above the slimes' feet. */
        public final int dy;
        /** Count the block exactly 24 away (as /perimeterinfo does). */
        public final boolean inclusive;

        Stand(String label, int dy, boolean inclusive) {
            this.label = label;
            this.dy = dy;
            this.inclusive = inclusive;
        }

        /** Where /tp puts you: your feet dy above the slimes, who stand on the floor. */
        public int tpY() {
            return FLOOR_Y + 1 + dy;
        }

        /** Blocks from the player within which slime blocks are counted, along the floor. */
        public double reach() {
            return Math.sqrt(16384.0 - dy * dy);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    // ------------------------------------------------------------------ slime chunks

    /** Minecraft's slime chunk rule, by the game's own code (WorldgenRandom.seedSlimeChunk, as in Slime.checkSlimeSpawnRules). */
    public static boolean isSlimeChunk(long seed, int cx, int cz) {
        return WorldgenRandom.seedSlimeChunk(cx, cz, seed, SALT).nextInt(10) == 0;
    }

    /** The chunk's offset in the slime seed: seedSlimeChunk adds this to the world seed (before the salt). */
    public static long offset(int cx, int cz) {
        return (long) (cx * cx * 4987142) + (long) (cx * 5947611) + (long) (cz * cz) * 4392871L + (long) (cz * 389711);
    }

    /**
     * The same answer as {@link #isSlimeChunk}, written out for speed: new Random(seed + offset ^ salt).nextInt(10) == 0.
     * Checked against the game's code before any search uses it ({@link #check()}).
     */
    public static boolean fast(long seed, int cx, int cz) {
        return slimeSeed(seed + offset(cx, cz));
    }

    /** nextInt(10) == 0 for new Random(s ^ salt), s being the world seed plus the chunk's offset. */
    static boolean slimeSeed(long sum) {
        long s = ((sum ^ SALT) ^ MULT) & MASK48;
        while (true) {
            s = s * MULT + 0xBL & MASK48;
            int bits = (int) (s >>> 17), val = bits % 10;
            if (bits - val + 9 >= 0) return val == 0;
        }
    }

    private static volatile String checked;

    /** Compares {@link #fast} with the game's code on a million chunks (once); throws if any differs. */
    public static void check() {
        if (checked != null) return;
        synchronized (Slime.class) {
            if (checked != null) return;
            java.util.SplittableRandom r = new java.util.SplittableRandom(20261017);
            for (int i = 0; i < 1_000_000; i++) {
                long seed = r.nextLong();
                int cx = i % 3 == 0 ? r.nextInt(-2_000_000, 2_000_000) : r.nextInt(-1000, 1000);
                int cz = i % 5 == 0 ? r.nextInt(-2_000_000, 2_000_000) : r.nextInt(-1000, 1000);
                if (fast(seed, cx, cz) != isSlimeChunk(seed, cx, cz))
                    throw new IllegalStateException("Slime chunk shortcut differs from Minecraft at seed " + seed + " chunk " + cx + ", " + cz);
            }
            checked = "ok";
        }
    }

    // ------------------------------------------------------------------ weights

    /**
     * For each of the 256 places to stand inside a chunk, how many blocks of each of the 17x17 chunks around count.
     * Standing at block (x, z) of chunk (cx, cz) with ox = x & 15, oz = z & 15, block i, j of the chunk dx, dz away is
     * a = 16 dx + i - ox, b = 16 dz + j - oz blocks off (both +0.5s cancel), and counts when a^2 + b^2 + dy^2 is more
     * than (or with inclusive, at least) 576 and at most 16384.
     */
    public static final class Weights {
        public final Stand stand;
        private final int[] w = new int[256 * CELLS];
        /** Per standing place: the cells with any weight, as (dz + 8) << 8 | (dx + 8), and their weights. */
        final int[][] cell = new int[256][], weight = new int[256][];

        private Weights(Stand s) {
            stand = s;
            int dy2 = s.dy * s.dy, lo = s.inclusive ? 576 : 577, hi = 16384;
            for (int oz = 0; oz < 16; oz++)
                for (int ox = 0; ox < 16; ox++) {
                    int base = (oz * 16 + ox) * CELLS, n = 0;
                    for (int dz = -REACH; dz <= REACH; dz++)
                        for (int dx = -REACH; dx <= REACH; dx++) {
                            int c = 0;
                            for (int i = 0; i < 16; i++) {
                                int a = 16 * dx + i - ox;
                                for (int j = 0; j < 16; j++) {
                                    int b = 16 * dz + j - oz, d2 = a * a + b * b + dy2;
                                    if (d2 >= lo && d2 <= hi) c++;
                                }
                            }
                            w[base + (dz + REACH) * SIDE + dx + REACH] = c;
                            if (c > 0) n++;
                        }
                    int[] cs = new int[n], ws = new int[n];
                    for (int k = 0, m = 0; k < CELLS; k++)
                        if (w[base + k] > 0) {
                            cs[m] = (k / SIDE) << 8 | k % SIDE;
                            ws[m++] = w[base + k];
                        }
                    cell[oz * 16 + ox] = cs;
                    weight[oz * 16 + ox] = ws;
                }
        }

        /** Counted blocks in the chunk dx, dz away, standing at (ox, oz) of your chunk. */
        public int at(int ox, int oz, int dx, int dz) {
            if (Math.abs(dx) > REACH || Math.abs(dz) > REACH) return 0;
            return w[(oz * 16 + ox) * CELLS + (dz + REACH) * SIDE + dx + REACH];
        }

        /** A full floor: every block counted. */
        public int full(int ox, int oz) {
            int t = 0;
            for (int v : weight[oz * 16 + ox]) t += v;
            return t;
        }
    }

    private static final Weights[] WEIGHTS = new Weights[Stand.values().length];

    public static synchronized Weights weights(Stand s) {
        if (WEIGHTS[s.ordinal()] == null) WEIGHTS[s.ordinal()] = new Weights(s);
        return WEIGHTS[s.ordinal()];
    }

    // ------------------------------------------------------------------ scoring

    /** Slime chunks of a rectangle of chunks, row by row along x. */
    public static final class Grid {
        public final int cx0, cz0, nx, nz;
        final byte[] slime;

        public Grid(long seed, int cx0, int cz0, int nx, int nz) {
            this.cx0 = cx0;
            this.cz0 = cz0;
            this.nx = nx;
            this.nz = nz;
            slime = new byte[nx * nz];
            // offset(cx, cz) is an x part plus a z part: work each out once per column and row.
            long[] fx = new long[nx];
            for (int i = 0; i < nx; i++) {
                int cx = cx0 + i;
                fx[i] = (long) (cx * cx * 4987142) + (long) (cx * 5947611);
            }
            for (int j = 0; j < nz; j++) {
                int cz = cz0 + j;
                long base = seed + (long) (cz * cz) * 4392871L + (long) (cz * 389711);
                for (int i = 0; i < nx; i++) slime[j * nx + i] = (byte) (slimeSeed(base + fx[i]) ? 1 : 0);
            }
        }

        /** The grid that can score every standing block from (x0, z0) to (x1, z1). */
        public static Grid around(long seed, int x0, int z0, int x1, int z1) {
            int cx0 = (x0 >> 4) - REACH, cz0 = (z0 >> 4) - REACH;
            return new Grid(seed, cx0, cz0, (x1 >> 4) + REACH - cx0 + 1, (z1 >> 4) + REACH - cz0 + 1);
        }

        public boolean slime(int cx, int cz) {
            return slime[(cz - cz0) * nx + cx - cx0] != 0;
        }

        /** Counted blocks standing at block (x, z). */
        public int blocks(Weights wt, int x, int z) {
            int o = (z & 15) * 16 + (x & 15), base = ((z >> 4) - REACH - cz0) * nx + (x >> 4) - REACH - cx0, n = 0;
            int[] cs = wt.cell[o], ws = wt.weight[o];
            for (int k = 0; k < cs.length; k++) {
                int c = cs[k];
                if (slime[base + (c >> 8) * nx + (c & 255)] != 0) n += ws[k];
            }
            return n;
        }

        /** Slime chunks with any counted block, standing at (x, z). */
        public int chunks(Weights wt, int x, int z) {
            int o = (z & 15) * 16 + (x & 15), base = ((z >> 4) - REACH - cz0) * nx + (x >> 4) - REACH - cx0, n = 0;
            for (int c : wt.cell[o]) if (slime[base + (c >> 8) * nx + (c & 255)] != 0) n++;
            return n;
        }
    }

    /** One place to stand and what it counts. */
    public record Spot(int x, int z, int blocks, int chunks) {
        public double cq() {
            return blocks / 256.0;
        }
    }

    /** Counted blocks and slime chunks standing at (x, z). */
    public static Spot score(long seed, int x, int z, Stand s) {
        Grid g = Grid.around(seed, x, z, x, z);
        Weights wt = weights(s);
        return new Spot(x, z, g.blocks(wt, x, z), g.chunks(wt, x, z));
    }

    /** The best place to stand within window blocks of (x, z) (ties: the nearest to (x, z)). */
    public static Spot best(long seed, int x, int z, int window, Stand s) {
        Grid g = Grid.around(seed, x - window, z - window, x + window, z + window);
        return best(g, weights(s), x, z, window);
    }

    static Spot best(Grid g, Weights wt, int x, int z, int window) {
        int bx = x, bz = z, bb = -1;
        long bd = Long.MAX_VALUE;
        for (int zz = z - window; zz <= z + window; zz++)
            for (int xx = x - window; xx <= x + window; xx++) {
                int b = g.blocks(wt, xx, zz);
                long d = (long) (xx - x) * (xx - x) + (long) (zz - z) * (zz - z);
                if (b > bb || (b == bb && d < bd)) {
                    bb = b;
                    bd = d;
                    bx = xx;
                    bz = zz;
                }
            }
        return new Spot(bx, bz, bb, g.chunks(wt, bx, bz));
    }

    /** cq to 2 decimals, the way the toolchain rounds it. */
    public static String cq(int blocks) {
        return String.format(Locale.ROOT, "%.2f", Math.floor(blocks / 256.0 * 100 + 0.5) / 100);
    }

    /**
     * What Carpet's /perimeterinfo reads over the rig's floor with your feet dy above the slimes' feet: floor blocks of
     * slime chunks with 576 <= d^2 <= 16384 (it counts exactly 24 away; the game itself doesn't spawn there).
     */
    public static int perimeterInfo(long seed, int x, int z, int dy) {
        int n = 0, cx = x >> 4, cz = z >> 4;
        for (int dz = -REACH; dz <= REACH; dz++)
            for (int dx = -REACH; dx <= REACH; dx++) {
                if (!isSlimeChunk(seed, cx + dx, cz + dz)) continue;
                for (int j = 0; j < 16; j++)
                    for (int i = 0; i < 16; i++) {
                        long a = (cx + dx) * 16L + i - x, c = (cz + dz) * 16L + j - z, d2 = a * a + c * c + (long) dy * dy;
                        if (d2 >= 576 && d2 <= 16384) n++;
                    }
            }
        return n;
    }

    // ------------------------------------------------------------------ layout picture

    /**
     * The 17x17 chunks around a farm, 32 pixels per chunk (2 per block), like the toolchain's farm images: slime chunks
     * green, chunk lines dark, the counting circle red, a red cross where you stand. North is up.
     */
    public static BufferedImage picture(long seed, int x, int z, Stand s) {
        int tile = 32, size = SIDE * tile;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(245, 245, 240));
        g.fillRect(0, 0, size, size);
        int cx = x >> 4, cz = z >> 4;
        g.setColor(new Color(90, 200, 90));
        for (int dz = -REACH; dz <= REACH; dz++)
            for (int dx = -REACH; dx <= REACH; dx++)
                if (isSlimeChunk(seed, cx + dx, cz + dz)) g.fillRect((dx + REACH) * tile, (dz + REACH) * tile, tile, tile);
        g.setColor(new Color(60, 60, 60));
        for (int i = 0; i <= SIDE; i++) {
            g.drawLine(i * tile, 0, i * tile, size);
            g.drawLine(0, i * tile, size, i * tile);
        }
        // Where you stand: the middle of block (x, z).
        double px = REACH * tile + (x & 15) * 2 + 1, pz = REACH * tile + (z & 15) * 2 + 1;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(2f));
        g.setColor(new Color(200, 0, 0, 170));
        double r = s.reach() * 2;
        g.draw(new Ellipse2D.Double(px - r, pz - r, r * 2, r * 2));
        if (s.dy < 24) {
            double in = Math.sqrt(576.0 - s.dy * s.dy) * 2;
            g.draw(new Ellipse2D.Double(px - in, pz - in, in * 2, in * 2));
        }
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(new Color(220, 30, 30));
        g.fillRect((int) px - 8, (int) pz - 1, 17, 3);
        g.fillRect((int) px - 1, (int) pz - 8, 3, 17);
        g.dispose();
        return img;
    }

    // ------------------------------------------------------------------ test rig

    /**
     * Commands to build the toolchain's test rig in a creative world (needs Carpet and WorldEdit): empty the chunks
     * around the spot, lay obsidian at y -64 in the slime chunks only (one command block, using command block minecarts
     * on an activator rail, which then remove themselves), and a repeating command block that runs /perimeterinfo, the
     * game's own count of spawning spaces. A superflat world with this seed is quickest (nothing to clear above y -40).
     */
    public static String rig(long seed, int x, int z, Stand s) {
        // Only blocks within 128 count, so the 17x17 chunks around yours are all the rig needs (all loaded at render distance 9).
        int cx0 = (x >> 4) - REACH, cx1 = (x >> 4) + REACH, cz0 = (z >> 4) - REACH, cz1 = (z >> 4) + REACH;
        List<int[]> chunks = new ArrayList<>();
        for (int cz = cz0; cz <= cz1; cz++)
            for (int cx = cx0; cx <= cx1; cx++)
                if (isSlimeChunk(seed, cx, cz)) chunks.add(new int[]{cx, cz});
        StringBuilder b = new StringBuilder();
        b.append("# Slime farm test rig for Minecraft 1.21.11 (needs Carpet and WorldEdit; a creative superflat world is quickest)\n");
        b.append("# Seed ").append(seed).append(", stand at ").append(x).append(", ").append(s.tpY()).append(", ").append(z)
                .append(". /perimeterinfo there should read ").append(String.format(Locale.ROOT, "%,d", perimeterInfo(seed, x, z, s.dy)));
        b.append("\n# ").append(chunks.size()).append(" slime chunks in the ").append(cx1 - cx0 + 1).append(" x ").append(cz1 - cz0 + 1)
                .append(" chunks around the spot.\n");
        b.append("# Render distance 9 or more, so every chunk of the floor is loaded.\n\n");
        b.append("# 1. Go there (in creative, flying) and stop mobs spawning\n");
        b.append("/tp @s ").append(x).append(' ').append(s.tpY()).append(' ').append(z).append('\n');
        b.append("/gamerule spawn_mobs false\n\n");
        b.append("# 2. Empty the area (WorldEdit), one line at a time\n");
        b.append("//pos1 ").append(cx0 * 16).append(',').append(FLOOR_Y).append(',').append(cz0 * 16).append('\n');
        b.append("//pos2 ").append(cx1 * 16 + 15).append(',').append(Math.max(-40, s.tpY() - 1)).append(',').append(cz1 * 16 + 15).append('\n');
        b.append("//set air\n\n");
        b.append("# 3. The slime chunk floor: paste this into a command block placed in the air, power it, then break the command block\n");
        StringBuilder m = new StringBuilder("summon falling_block ~ ~1 ~ {BlockState:{Name:\"minecraft:redstone_block\"},Time:1,"
                + "Passengers:[{id:\"minecraft:falling_block\",BlockState:{Name:\"minecraft:activator_rail\"},Time:1,Passengers:[");
        for (int[] c : chunks)
            m.append("{id:\"minecraft:command_block_minecart\",Command:\"fill ").append(c[0] * 16).append(' ').append(FLOOR_Y).append(' ')
                    .append(c[1] * 16).append(' ').append(c[0] * 16 + 15).append(' ').append(FLOOR_Y).append(' ').append(c[1] * 16 + 15)
                    .append(" obsidian\"},");
        // Last, one command takes away the rail and the redstone block (a minecart only runs while on the powered rail,
        // so nothing after that would run: the minecarts are removed from chat).
        m.append("{id:\"minecraft:command_block_minecart\",Command:\"fill ~ ~-1 ~ ~ ~ ~ air\"}]}]}");
        b.append(m).append("\n\n");
        b.append("# 4. Remove the helper minecarts, and break the command block (slimes could spawn on it)\n");
        b.append("/kill @e[type=minecraft:command_block_minecart]\n\n");
        b.append("# 5. Back at the spot, place this block: it shows /perimeterinfo's count all the time\n");
        b.append("/give @s repeating_command_block[block_entity_data={id:\"minecraft:command_block\",Command:\"execute at @p run perimeterinfo\",auto:1b}]\n");
        return b.toString();
    }
}
