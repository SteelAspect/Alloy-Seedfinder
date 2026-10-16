package dev.steelaspect.seedfinder.engine;

/** Where to search: a square around a centre point. */
public record Search(long seed, WorldType type, int centerX, int centerZ, int radius) {
    public boolean inside(int x, int z) {
        return Math.abs((long) x - centerX) <= radius && Math.abs((long) z - centerZ) <= radius;
    }

    public long distance(int x, int z) {
        return Math.round(Math.hypot((double) x - centerX, (double) z - centerZ));
    }
}
