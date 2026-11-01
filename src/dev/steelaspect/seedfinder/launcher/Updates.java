package dev.steelaspect.seedfinder.launcher;

import java.io.File;

/**
 * Updates the app downloaded for itself: each one is a whole unpacked zip in the data folder's updates/&lt;version&gt;
 * folder. Nothing in the folder the user unpacked is replaced (a running jar can't be on Windows); the launcher starts
 * the newest one instead.
 */
public final class Updates {
    private Updates() {}

    public static File dir() {
        return new File(AppDirs.data(), "updates");
    }

    /** The newest downloaded app jar that is newer than this one, or null. */
    public static File newest() {
        File[] dirs = dir().listFiles(File::isDirectory);
        if (dirs == null) return null;
        File best = null;
        String bestVersion = Version.VALUE;
        for (File d : dirs) {
            File jar = new File(d, "AlloySeedFinder.jar");
            if (jar.isFile() && Version.compare(d.getName(), bestVersion) > 0) {
                best = jar;
                bestVersion = d.getName();
            }
        }
        return best;
    }
}
