package dev.steelaspect.seedfinder.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing a search found. Plain fields so it saves to JSON as-is.
 * (x, y, z) is where to teleport; the box is what the map outlines; ref is what the verifier checks.
 */
public final class Result {
    public enum Kind { AREA, SQUARE, RECTANGLE, HUT, MONUMENTS, FORTRESS, SLIME, STRUCTURE, HUTS }

    public Kind kind;
    public Dim dim;
    /** Biome id for biome results, or a short name like "Witch hut". */
    public String name;
    public int x, z;
    /** Teleport height, or null to keep the player's height (~). */
    public Integer y;
    /** Outline on the map (blocks, inclusive). */
    public int minX, minZ, maxX, maxZ;
    /** What the list sorts by (area, floor area, coverage...). */
    public double value;
    /** Short size text, e.g. "880x880" or "5.1M". */
    public String size;
    /** Extra columns, in display order. */
    public Map<String, String> details = new LinkedHashMap<>();
    /** Points or positions the verifier checks, kind-specific (see Verifier). */
    public int[] ref;
    /** Structure id for structure results, e.g. "minecraft:village_plains" (Verify locates it). */
    public String structure;
    /** Biomes allowed at the ref points (biome results). */
    public String[] biomes;
    /** Set after "Verify": "ok", "fail" or a message. */
    public String verified;

    public String tp() {
        String cmd = "tp @s " + x + " " + (y == null ? "~" : y) + " " + z;
        return dim == Dim.OVERWORLD ? "/" + cmd : "/execute in " + dim.id + " run " + cmd;
    }

    public String coords() {
        return y == null ? x + ", " + z : x + ", " + y + ", " + z;
    }
}
