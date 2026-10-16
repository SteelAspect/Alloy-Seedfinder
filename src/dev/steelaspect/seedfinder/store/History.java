package dev.steelaspect.seedfinder.store;

import dev.steelaspect.seedfinder.engine.Result;
import dev.steelaspect.seedfinder.engine.WorldType;
import dev.steelaspect.seedfinder.launcher.AppDirs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Every finished search, saved in ~/.local/share/cytra-seedfinder/history/ so it can be reopened. */
public final class History {
    public static final Path DIR = AppDirs.data().toPath().resolve("history");

    public static final class Entry {
        public String page;
        public String title;
        public String seedName;
        public long seed;
        public WorldType type;
        public Map<String, String> params;
        public long time;
        public double seconds;
        public String note;
        public List<Result> results;
        transient Path file;

        public Path file() {
            return file;
        }
    }

    private History() {}

    public static void save(Entry e) {
        try {
            e.file = DIR.resolve(e.time + "-" + e.page + ".json");
            Json.write(e.file, e);
        } catch (IOException ignored) {
            // History is a convenience; a failed save must not break the search result on screen.
        }
    }

    /** All saved searches, newest first. */
    public static List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        if (!Files.isDirectory(DIR)) return out;
        try (Stream<Path> files = Files.list(DIR)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                Entry e = Json.read(f, Entry.class);
                if (e != null && e.results != null) {
                    e.file = f;
                    out.add(e);
                }
            }
        } catch (IOException ignored) {
            return out;
        }
        out.sort((a, b) -> Long.compare(b.time, a.time));
        return out;
    }

    public static void delete(Entry e) {
        try {
            if (e.file != null) Files.deleteIfExists(e.file);
        } catch (IOException ignored) {
            // Nothing else to do; it will show up again in the list.
        }
    }

    /** Re-saves an entry in place (e.g. after Verify added results). */
    public static void update(Entry e) {
        if (e.file == null) {
            save(e);
            return;
        }
        try {
            Json.write(e.file, e);
        } catch (IOException ignored) {
            // See save().
        }
    }
}
