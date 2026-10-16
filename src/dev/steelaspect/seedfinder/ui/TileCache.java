package dev.steelaspect.seedfinder.ui;

import dev.steelaspect.seedfinder.launcher.AppDirs;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Finished map tiles: a memory cache shared by all maps, and PNG files in the app's cache folder so places you've
 * looked at before draw instantly. The disk cache is trimmed (oldest first) when it grows past its limit.
 */
final class TileCache {
    static final Path DIR = AppDirs.cache().toPath().resolve("map");
    static final long LIMIT = 500L * 1024 * 1024;
    private static final int MEMORY_TILES = 400;
    private static final Map<String, BufferedImage> MEMORY = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) {
            return size() > MEMORY_TILES;
        }
    };
    /** Bytes written since the last trim; trim every ~20 MB. */
    private static final AtomicLong WRITTEN = new AtomicLong(LIMIT);

    private TileCache() {}

    static String key(TileRenderer.Spec spec, int level, long tx, long tz) {
        return spec.id() + "/" + level + "/" + tx + "_" + tz;
    }

    static BufferedImage memory(String key) {
        synchronized (MEMORY) {
            return MEMORY.get(key);
        }
    }

    static void remember(String key, BufferedImage img) {
        synchronized (MEMORY) {
            MEMORY.put(key, img);
        }
    }

    /** The tile from disk, or null. */
    static BufferedImage load(String key) {
        Path f = DIR.resolve(key + ".png");
        if (!Files.isRegularFile(f)) return null;
        try {
            BufferedImage img = ImageIO.read(f.toFile());
            Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis()));
            return img;
        } catch (IOException e) {
            return null;
        }
    }

    static void save(String key, BufferedImage img) {
        Path f = DIR.resolve(key + ".png");
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            ImageIO.write(img, "png", tmp.toFile());
            long size = Files.size(tmp);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (WRITTEN.addAndGet(size) > 20L * 1024 * 1024) {
                WRITTEN.set(0);
                trim();
            }
        } catch (IOException ignored) {
            // The cache is only a speed-up.
        }
    }

    /** Size of the disk cache in bytes. */
    static long size() {
        if (!Files.isDirectory(DIR)) return 0;
        try (Stream<Path> s = Files.walk(DIR)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        } catch (IOException | UncheckedIOException e) {
            return 0;
        }
    }

    /** Deletes the oldest tiles until the cache is under 80% of its limit. */
    static synchronized void trim() {
        if (!Files.isDirectory(DIR)) return;
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(DIR)) {
            s.filter(p -> p.toString().endsWith(".png")).forEach(files::add);
        } catch (IOException | UncheckedIOException e) {
            return;
        }
        long total = files.stream().mapToLong(p -> p.toFile().length()).sum();
        if (total <= LIMIT) return;
        files.sort(Comparator.comparingLong(p -> p.toFile().lastModified()));
        for (Path p : files) {
            if (total <= LIMIT * 8 / 10) break;
            long len = p.toFile().length();
            try {
                Files.deleteIfExists(p);
                total -= len;
            } catch (IOException ignored) {
                // Try the next one.
            }
        }
    }

    /** "Clear map cache" in Settings: deletes every cached tile (memory and disk). */
    static synchronized void clear() {
        synchronized (MEMORY) {
            MEMORY.clear();
        }
        if (!Files.isDirectory(DIR)) return;
        try (Stream<Path> s = Files.walk(DIR)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException | UncheckedIOException ignored) {
            // Whatever is left gets replaced as tiles are redrawn.
        }
    }
}
