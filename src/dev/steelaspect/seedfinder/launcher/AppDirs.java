package dev.steelaspect.seedfinder.launcher;

import java.io.File;

/**
 * Where the app keeps its files, per operating system. Kept to Java 8 so the launcher can use it before the
 * Java version check. SEEDFINDER_HOME overrides everything (used for testing).
 */
public final class AppDirs {
    private AppDirs() {}

    public static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    public static boolean mac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    /** Settings (small). */
    public static File config() {
        String home = System.getenv("SEEDFINDER_HOME");
        if (home != null && !home.isEmpty()) return new File(home, "config");
        if (windows()) return named(env("APPDATA", userHome() + "\\AppData\\Roaming"), "AlloySeedFinder", "CytraSeedFinder");
        if (mac()) return named(userHome() + "/Library/Application Support", "AlloySeedFinder", "CytraSeedFinder");
        return named(env("XDG_CONFIG_HOME", userHome() + "/.config"), "alloy-seedfinder", "cytra-seedfinder");
    }

    /** Saved results and the downloaded Minecraft files (bigger). */
    public static File data() {
        String home = System.getenv("SEEDFINDER_HOME");
        if (home != null && !home.isEmpty()) return new File(home, "data");
        if (windows()) return named(env("LOCALAPPDATA", userHome() + "\\AppData\\Local"), "AlloySeedFinder", "CytraSeedFinder");
        if (mac()) return named(userHome() + "/Library/Application Support", "AlloySeedFinder", "CytraSeedFinder");
        return named(env("XDG_DATA_HOME", userHome() + "/.local/share"), "alloy-seedfinder", "cytra-seedfinder");
    }

    /** Throwaway files (the Verify test server). */
    public static File cache() {
        String home = System.getenv("SEEDFINDER_HOME");
        if (home != null && !home.isEmpty()) return new File(home, "cache");
        if (windows()) return new File(data(), "cache");
        if (mac()) return named(userHome() + "/Library/Caches", "AlloySeedFinder", "CytraSeedFinder");
        return named(env("XDG_CACHE_HOME", userHome() + "/.cache"), "alloy-seedfinder", "cytra-seedfinder");
    }

    /**
     * The folder with this name. The app was called Cytra Seed Finder before: a folder under the old name is moved to
     * the new one the first time (or, if it can't be moved, used as it is), so settings and saved results carry over.
     */
    private static File named(String parent, String name, String old) {
        File now = new File(parent, name);
        if (!now.exists()) {
            File before = new File(parent, old);
            if (before.isDirectory() && !before.renameTo(now)) return before;
        }
        return now;
    }

    /** The converted Minecraft files for one version. */
    public static File minecraft(String version) {
        return new File(new File(data(), "minecraft"), version);
    }

    public static String userHome() {
        return System.getProperty("user.home");
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }
}
