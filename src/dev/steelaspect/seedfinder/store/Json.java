package dev.steelaspect.seedfinder.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Small JSON file helpers; writes go through a temp file so a crash never leaves half a file. */
final class Json {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private Json() {}

    static <T> T read(Path file, Class<T> type) {
        try {
            return Files.exists(file) ? GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), type) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static void write(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(value), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

}
