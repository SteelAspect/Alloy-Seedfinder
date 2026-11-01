package dev.steelaspect.seedfinder.launcher;

/** The app's version, in one place for the app, the build script and the updater. */
public final class Version {
    public static final String VALUE = "2.6.0";

    private Version() {}

    /** Compares versions like 2.10.1 and 2.9 number by number; negative if a is older than b. */
    public static int compare(String a, String b) {
        String[] x = a.replaceFirst("^v", "").split("\\."), y = b.replaceFirst("^v", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
