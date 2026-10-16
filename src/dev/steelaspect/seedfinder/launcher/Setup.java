package dev.steelaspect.seedfinder.launcher;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.NonClassCopyMode;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;
import net.fabricmc.tinyremapper.TinyUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * First run: downloads Minecraft's official server jar and mappings from Mojang, checks them, unpacks the libraries
 * and converts the game code to readable names (the same thing Fabric's tools do). Nothing of Minecraft ships with
 * the app; every user gets it straight from Mojang.
 */
public final class Setup {
    public static final String VERSION = "1.21.11";
    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String READY = ".ready-2";

    /** Progress for the setup window or the console. */
    public interface Listener {
        void step(String text, double fraction);
    }

    private final Path dir = AppDirs.minecraft(VERSION).toPath();
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();

    public Path dir() {
        return dir;
    }

    public boolean ready() {
        return Files.exists(dir.resolve(READY)) && Files.isRegularFile(gameJar());
    }

    /** The converted game jar plus its libraries: everything the search code needs on its classpath. */
    public List<Path> classpath() throws IOException {
        List<Path> out = new ArrayList<>();
        out.add(gameJar());
        try (Stream<Path> libs = Files.list(dir.resolve("libraries"))) {
            libs.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(out::add);
        }
        return out;
    }

    public Path gameJar() {
        return dir.resolve("minecraft-" + VERSION + "-mojmap.jar");
    }

    /** The original runnable server jar (the Verify button starts it). */
    public Path serverJar() {
        return dir.resolve("server.jar");
    }

    public void run(Listener l) throws Exception {
        Files.createDirectories(dir);
        l.step("Asking Mojang for Minecraft " + VERSION, 0.01);
        String manifest = get(MANIFEST);
        Matcher m = Pattern.compile("\\{\"id\":\\s*\"" + Pattern.quote(VERSION) + "\".*?\"url\":\\s*\"([^\"]+)\"", Pattern.DOTALL).matcher(manifest);
        if (!m.find()) throw new IOException("Mojang's version list has no " + VERSION);
        String version = get(m.group(1));
        Download server = download(version, "server");
        Download mappings = download(version, "server_mappings");

        fetch(server, serverJar(), l, "Downloading the Minecraft server jar", 0.03, 0.55);
        Path mappingFile = dir.resolve("server-mappings.txt");
        fetch(mappings, mappingFile, l, "Downloading Mojang's name mappings", 0.55, 0.65);

        l.step("Unpacking libraries", 0.66);
        Path libs = dir.resolve("libraries");
        Files.createDirectories(libs);
        Path obf = dir.resolve("server-" + VERSION + "-obf.jar");
        try (ZipFile zip = new ZipFile(serverJar().toFile())) {
            for (String line : read(zip, "META-INF/libraries.list")) {
                String[] p = line.split("\t");
                if (p.length < 3) continue;
                Path out = libs.resolve(Path.of(p[2]).getFileName().toString());
                if (!Files.exists(out)) extract(zip, "META-INF/libraries/" + p[2], out);
            }
            for (String line : read(zip, "META-INF/versions.list")) {
                String[] p = line.split("\t");
                if (p.length >= 3 && p[1].equals(VERSION)) extract(zip, "META-INF/versions/" + p[2], obf);
            }
        }
        if (!Files.exists(obf)) throw new IOException("The server jar has no " + VERSION + " game code inside");

        l.step("Converting Minecraft's code to readable names (one time, about a minute)", 0.7);
        MemoryMappingTree tree = new MemoryMappingTree();
        MappingReader.read(mappingFile, MappingFormat.PROGUARD_FILE, tree);
        TinyRemapper remapper = TinyRemapper.newRemapper()
                .withMappings(TinyUtils.createMappingProvider(tree, "target", "source"))
                .renameInvalidLocals(true)
                .rebuildSourceFilenames(true)
                .build();
        // The temp name must end in .jar: the converter writes a folder otherwise.
        Path tmp = dir.resolve("converting.jar");
        deleteTree(tmp);
        deleteTree(gameJar());
        try (OutputConsumerPath out = new OutputConsumerPath.Builder(tmp).assumeArchive(true).build()) {
            out.addNonClassFiles(obf, NonClassCopyMode.FIX_META_INF, remapper);
            remapper.readInputs(obf);
            try (Stream<Path> s = Files.list(libs)) {
                remapper.readClassPath(s.filter(p -> p.toString().endsWith(".jar")).toArray(Path[]::new));
            }
            remapper.apply(out);
        } finally {
            remapper.finish();
        }
        Files.move(tmp, gameJar(), StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(obf);
        Files.writeString(dir.resolve(READY), VERSION + "\n");
        l.step("Ready", 1);
    }

    private record Download(String url, String sha1, long size) {}

    private static Download download(String versionJson, String key) throws IOException {
        Matcher m = Pattern.compile("\"" + key + "\":\\s*\\{([^}]*)}").matcher(versionJson);
        if (!m.find()) throw new IOException("Mojang's version info has no " + key);
        String body = m.group(1);
        return new Download(field(body, "url"), field(body, "sha1"), Long.parseLong(field(body, "size")));
    }

    private static String field(String body, String name) throws IOException {
        Matcher m = Pattern.compile("\"" + name + "\":\\s*\"?([^\",}]+)\"?").matcher(body);
        if (!m.find()) throw new IOException("Missing " + name);
        return m.group(1).trim();
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) throw new IOException("Mojang's server answered " + r.statusCode() + " for " + url);
        return r.body();
    }

    /** Downloads to the target (skipping it if a file with the right SHA-1 is already there). */
    private void fetch(Download d, Path target, Listener l, String text, double from, double to) throws Exception {
        if (Files.exists(target) && sha1(target).equals(d.sha1)) return;
        HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(d.url)).timeout(Duration.ofMinutes(10)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) throw new IOException("Download failed (" + r.statusCode() + "): " + d.url);
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                sha.update(buf, 0, n);
                done += n;
                l.step(String.format("%s  ·  %.0f of %.0f MB", text, done / 1e6, d.size / 1e6), from + (to - from) * done / Math.max(1, d.size));
            }
        }
        String got = HexFormat.of().formatHex(sha.digest());
        if (!got.equalsIgnoreCase(d.sha1)) {
            Files.deleteIfExists(tmp);
            throw new IOException("The download was damaged (checksum didn't match). Try again.");
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path q : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(q);
        }
    }

    private static String sha1(Path p) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) sha.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static List<String> read(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) throw new IOException("The server jar is missing " + name);
        try (InputStream in = zip.getInputStream(e)) {
            return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R"));
        }
    }

    private static void extract(ZipFile zip, String name, Path out) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) throw new IOException("The server jar is missing " + name);
        try (InputStream in = zip.getInputStream(e)) {
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
