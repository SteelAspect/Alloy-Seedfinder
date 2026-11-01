package dev.steelaspect.seedfinder.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.steelaspect.seedfinder.launcher.Updates;
import dev.steelaspect.seedfinder.launcher.Version;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Updates from the GitHub releases of the app's repository: a release tagged v&lt;version&gt; with the zip from
 * build.sh and its .sha256 file. A new version is unpacked into the data folder's updates folder ({@link Updates})
 * and the launcher starts it from then on.
 */
public final class Updater {
    public static final String REPO = "SteelAspect/cytra-seedfinder";
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10)).build();

    /** A published version: the zip, its checksum file and the release notes. */
    public record Release(String version, String zipUrl, String sha256Url, String notes, String page) {
        public boolean newer() {
            return Version.compare(version, Version.VALUE) > 0;
        }

        /** Already downloaded, waiting for a restart. */
        public boolean ready() {
            return Files.isRegularFile(Updates.dir().toPath().resolve(version).resolve("AlloySeedFinder.jar"));
        }
    }

    private Updater() {}

    /** The latest published release, or null if there is none yet or no zip on it. */
    public static Release latest() throws IOException, InterruptedException {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + REPO + "/releases/latest"))
                .header("Accept", "application/vnd.github+json").header("User-Agent", "AlloySeedFinder/" + Version.VALUE)
                .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404) return null;
        if (r.statusCode() != 200) throw new IOException("GitHub answered " + r.statusCode());
        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
        String zip = null, sum = null;
        JsonArray assets = o.getAsJsonArray("assets");
        for (JsonElement e : assets) {
            JsonObject a = e.getAsJsonObject();
            String name = a.get("name").getAsString(), url = a.get("browser_download_url").getAsString();
            if (name.endsWith(".zip")) zip = url;
            else if (name.endsWith(".zip.sha256")) sum = url;
        }
        if (zip == null || sum == null) return null;
        String notes = o.has("body") && !o.get("body").isJsonNull() ? o.get("body").getAsString() : "";
        return new Release(o.get("tag_name").getAsString().replaceFirst("^v", ""), zip, sum, notes, o.get("html_url").getAsString());
    }

    /**
     * Downloads and unpacks a release (checksum checked) into the updates folder, keeping only it. Returns its folder.
     * progress gets (what, 0..1).
     */
    public static Path install(Release r, BiConsumer<String, Double> progress) throws IOException, InterruptedException {
        Path dir = Updates.dir().toPath();
        Files.createDirectories(dir);
        String expected = text(r.sha256Url()).trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        Path zip = Files.createTempFile(dir, "download-", ".zip");
        try {
            HttpResponse<InputStream> resp = HTTP.send(HttpRequest.newBuilder(URI.create(r.zipUrl())).header("User-Agent", "AlloySeedFinder/" + Version.VALUE)
                    .timeout(Duration.ofMinutes(5)).build(), HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) throw new IOException("Download failed: " + resp.statusCode());
            long size = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
            MessageDigest sha;
            try {
                sha = MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException(e);
            }
            try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(zip)) {
                byte[] buf = new byte[1 << 16];
                long got = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    got += n;
                    if (size > 0) progress.accept("Downloading " + r.version(), (double) got / size);
                }
            }
            String actual = HexFormat.of().formatHex(sha.digest());
            if (!actual.equals(expected)) throw new IOException("The download doesn't match its checksum; nothing was changed");
            progress.accept("Unpacking", 1.0);
            Path part = dir.resolve(r.version() + ".part"), done = dir.resolve(r.version());
            delete(part);
            unzip(zip, part);
            if (!Files.isRegularFile(part.resolve("AlloySeedFinder.jar"))) throw new IOException("The download has no AlloySeedFinder.jar");
            delete(done);
            Files.move(part, done, StandardCopyOption.ATOMIC_MOVE);
            // Older downloaded versions aren't needed any more.
            try (var list = Files.list(dir)) {
                for (Path p : list.toList()) if (Files.isDirectory(p) && !p.equals(done) && Version.compare(p.getFileName().toString(), r.version()) < 0) delete(p);
            }
            return done;
        } finally {
            Files.deleteIfExists(zip);
        }
    }

    /** Starts the app again the way it was started (the launcher then picks the new version) and ends this one. */
    public static boolean restart() {
        ProcessHandle.Info info = ProcessHandle.current().info();
        if (info.command().isEmpty()) return false;
        List<String> cmd = new ArrayList<>();
        cmd.add(info.command().get());
        info.arguments().ifPresent(a -> cmd.addAll(List.of(a)));
        try {
            new ProcessBuilder(cmd).inheritIO().start();
        } catch (IOException e) {
            return false;
        }
        System.exit(0);
        return true;
    }

    private static String text(String url) throws IOException, InterruptedException {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "AlloySeedFinder/" + Version.VALUE)
                .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("Couldn't get the checksum: " + r.statusCode());
        return r.body();
    }

    /** Unpacks the zip's single top folder (AlloySeedFinder-x.y.z/) into to; refuses paths that leave it. */
    private static void unzip(Path zip, Path to) throws IOException {
        Files.createDirectories(to);
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                String name = e.getName();
                int slash = name.indexOf('/');
                if (slash < 0 || slash == name.length() - 1) continue;
                Path out = to.resolve(name.substring(slash + 1)).normalize();
                if (!out.startsWith(to)) throw new IOException("Bad path in the download: " + name);
                if (e.isDirectory()) Files.createDirectories(out);
                else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    if (out.getFileName().toString().endsWith(".sh")) out.toFile().setExecutable(true);
                }
            }
        }
    }

    private static void delete(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            for (Path q : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(q);
        }
    }
}
