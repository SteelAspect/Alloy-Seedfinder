package dev.steelaspect.seedfinder.engine;

/** A dimension the finder can search. */
public enum Dim {
    OVERWORLD("Overworld", "minecraft:overworld"),
    NETHER("Nether", "minecraft:the_nether");

    public final String label;
    public final String id;

    Dim(String label, String id) {
        this.label = label;
        this.id = id;
    }
}
