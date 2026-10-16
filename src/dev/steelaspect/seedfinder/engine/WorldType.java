package dev.steelaspect.seedfinder.engine;

/** The overworld world type chosen when the world was made. */
public enum WorldType {
    NORMAL("Normal"),
    LARGE_BIOMES("Large biomes"),
    AMPLIFIED("Amplified");

    public final String label;

    WorldType(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
