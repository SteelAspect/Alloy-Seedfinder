package dev.steelaspect.seedfinder.engine;

import java.util.Locale;

/** Number formatting shared by the app and the command line. */
public final class Fmt {
    private Fmt() {}

    /** 1234567 -> "1.2M", 45000 -> "45k". */
    public static String big(double v) {
        if (v >= 1e9) return String.format(Locale.ROOT, "%.1fB", v / 1e9);
        if (v >= 1e6) return String.format(Locale.ROOT, "%.1fM", v / 1e6);
        if (v >= 1e3) return String.format(Locale.ROOT, "%.0fk", v / 1e3);
        return String.format(Locale.ROOT, "%.0f", v);
    }

    /** 12936 -> "12,936". */
    public static String n(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    /** "crimson_forest" -> "Crimson forest". */
    public static String biome(String id) {
        String s = id.replace('_', ' ');
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
