package dev.steelaspect.seedfinder.verify;

import dev.steelaspect.seedfinder.engine.Dim;
import dev.steelaspect.seedfinder.engine.Progress;
import dev.steelaspect.seedfinder.engine.Result;
import dev.steelaspect.seedfinder.engine.WorldType;
import dev.steelaspect.seedfinder.launcher.AppDirs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Checks results against a real vanilla server with the same seed: biomes with "execute if biome", hut floors with
 * "execute if block", structures with "locate". The server runs hidden in a cache folder and is deleted afterwards.
 */
public final class Verifier {
    /**
     * Only lines the server console itself printed count. Console /say shows as "[Not Secure] [Server] ..." since
     * 1.19; player chat would show as "<name> ...", so it can't fake this (and nobody can join anyway).
     */
    private static final Pattern SAY = Pattern.compile("^\\[[\\d:]+] \\[Server thread/INFO]: (?:\\[Not Secure] )?\\[Server] SFV (\\S+)(?: (.*))?$");
    private static final Pattern LOCATED = Pattern.compile("is at \\[(-?\\d+), [^,]+, (-?\\d+)]");
    private static final int GRID = 7;

    private final Path serverJar;
    private final long seed;
    private final WorldType type;
    private Process process;
    private OutputStream stdin;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

    public Verifier(Path serverJar, long seed, WorldType type) {
        this.serverJar = serverJar;
        this.seed = seed;
        this.type = type;
    }

    /** The vanilla 1.21.11 server jar left by Fabric Loom when any 1.21.11 mod was built, or null. */
    public static Path findServerJar() {
        String setup = System.getProperty("seedfinder.serverJar");
        if (setup != null && Files.isRegularFile(Path.of(setup))) return Path.of(setup);
        Path own = AppDirs.minecraft("1.21.11").toPath().resolve("server.jar");
        if (Files.isRegularFile(own)) return own;
        Path loom = Path.of(System.getProperty("user.home"), ".gradle/caches/fabric-loom/1.21.11/minecraft-server.jar");
        return Files.isRegularFile(loom) ? loom : null;
    }

    /** Verifies the results, setting each result's {@code verified} to "ok" or a short reason. */
    public void verify(List<Result> results, Progress progress) throws IOException, InterruptedException {
        Path dir = AppDirs.cache().toPath().resolve("verify").resolve(seed + "-" + type.name().toLowerCase());
        deleteTree(dir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n");
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Files.writeString(dir.resolve("server.properties"), String.join("\n",
                "level-seed=" + seed,
                "level-type=minecraft\\:" + switch (type) {
                    case LARGE_BIOMES -> "large_biomes";
                    case AMPLIFIED -> "amplified";
                    case NORMAL -> "normal";
                },
                // Only reachable from this computer, and nobody may join: it exists just to answer our commands.
                "server-ip=127.0.0.1", "white-list=true", "enforce-whitelist=true", "enable-query=false", "enable-rcon=false",
                "max-players=1", "online-mode=false", "server-port=" + port, "view-distance=3", "simulation-distance=3",
                "spawn-protection=0", "sync-chunk-writes=false", "pause-when-empty-seconds=0", "max-tick-time=-1", ""));
        try {
            progress.update(0.02, "Starting a test server");
            String java = Path.of(System.getProperty("java.home"), "bin", AppDirs.windows() ? "java.exe" : "java").toString();
            process = new ProcessBuilder(java, "-Xmx2G", "-jar", serverJar.toString(), "nogui").directory(dir.toFile()).redirectErrorStream(true).start();
            stdin = process.getOutputStream();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) lines.add(l);
                } catch (IOException ignored) {
                    // Server closed.
                }
            }, "verify-server-output");
            reader.setDaemon(true);
            reader.start();
            waitFor("^\\[[\\d:]+] \\[Server thread/INFO]: Done \\(", 240, progress);
            run(results, progress);
        } finally {
            if (process != null) {
                try {
                    send("stop");
                    if (!process.waitFor(30, TimeUnit.SECONDS)) process.destroyForcibly();
                } catch (IOException e) {
                    process.destroyForcibly();
                }
            }
            deleteTree(dir);
        }
    }

    private void run(List<Result> results, Progress progress) throws IOException, InterruptedException {
        // 1. Load every chunk the block/biome checks need.
        List<Check> checks = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) checks.addAll(checksFor(i, results.get(i)));
        Set<String> chunks = new HashSet<>();
        for (Check c : checks) if (c.needsChunk) chunks.add(c.dim.id + " " + c.x + " " + c.z);
        int n = 0;
        for (String c : chunks) {
            String[] p = c.split(" ");
            send("execute in " + p[0] + " run forceload add " + p[1] + " " + p[2]);
            if (++n % 50 == 0) progress.update(0.1 + 0.2 * n / chunks.size(), "Generating test chunks");
        }
        Set<String> waiting = new HashSet<>(chunks);
        long deadline = System.currentTimeMillis() + 60_000L + chunks.size() * 1500L;
        while (!waiting.isEmpty() && System.currentTimeMillis() < deadline) {
            progress.check();
            int k = 0;
            for (String c : waiting) {
                String[] p = c.split(" ");
                send("execute in " + p[0] + " if loaded " + p[1] + " 0 " + p[2] + " run say SFV L " + c.replace(' ', '|'));
                if (++k > 200) break;
            }
            send("say SFV S");
            for (String[] m : collectUntilSync(30)) if (m[0].equals("L")) waiting.remove(m[1].replace('|', ' '));
            progress.update(0.3 + 0.3 * (chunks.size() - waiting.size()) / Math.max(1, chunks.size()), "Generating test chunks");
            if (!waiting.isEmpty()) Thread.sleep(1000);
        }

        // 2. Run every check; each one that passes says "SFV P <id>".
        progress.update(0.65, "Checking results");
        Map<Integer, String> located = new HashMap<>();
        for (Check c : checks) {
            if (c.command.contains("locate")) {
                send(c.command);
                String hit = waitFor("^\\[[\\d:]+] \\[Server thread/INFO]: .*(is at \\[|Could not find|no structure)", 120, progress);
                Matcher m = LOCATED.matcher(hit);
                located.put(c.id, m.find() ? m.group(1) + "," + m.group(2) : "none");
            } else {
                send(c.command.replace("%ID%", Integer.toString(c.id)));
            }
        }
        send("say SFV S");
        Set<Integer> passed = new HashSet<>();
        for (String[] m : collectUntilSync(120)) if (m[0].equals("P")) passed.add(Integer.parseInt(m[1]));
        for (Check c : checks) if (c.expect != null && c.expect.equals(located.get(c.id))) passed.add(c.id);

        // 3. A result is ok when all its points pass (any one allowed biome counts per point).
        for (int i = 0; i < results.size(); i++) {
            Map<Integer, Boolean> points = new HashMap<>();
            for (Check c : checks) if (c.result == i) points.merge(c.point, passed.contains(c.id), Boolean::logicalOr);
            long bad = points.values().stream().filter(b -> !b).count();
            results.get(i).verified = points.isEmpty() ? "skipped" : bad == 0 ? "ok" : bad + " of " + points.size() + " checks off";
        }
        progress.update(1, "Verified");
    }

    /** One server command. needsChunk: the block/biome must be loaded first. expect: locate answer for structures. */
    private record Check(int id, int result, int point, Dim dim, int x, int z, boolean needsChunk, String command, String expect) {}

    private int nextId;

    private List<Check> checksFor(int i, Result r) {
        List<Check> out = new ArrayList<>();
        String in = "execute in " + r.dim.id + " ";
        switch (r.kind) {
            case AREA -> {
                for (int p = 0; p * 2 < r.ref.length; p++) biomeChecks(out, i, p, r, r.ref[p * 2], r.ref[p * 2 + 1]);
            }
            case SQUARE, RECTANGLE -> {
                int p = 0;
                for (int a = 0; a < GRID; a++)
                    for (int b = 0; b < GRID; b++) {
                        int x = r.ref[0] + 2 + (int) Math.round(a * (r.ref[2] - r.ref[0] - 4) / (double) (GRID - 1));
                        int z = r.ref[1] + 2 + (int) Math.round(b * (r.ref[3] - r.ref[1] - 4) / (double) (GRID - 1));
                        biomeChecks(out, i, p++, r, x, z);
                    }
            }
            case HUT -> {
                int floor = r.ref[1] + 1;
                int[][] centres = {{r.ref[0] + 3, r.ref[2] + 4}, {r.ref[0] + 4, r.ref[2] + 3}};
                for (int[] c : centres)
                    out.add(new Check(nextId++, i, 0, Dim.OVERWORLD, c[0], c[1], true,
                            in + "if block " + c[0] + " " + floor + " " + c[1] + " minecraft:spruce_planks run say SFV P %ID%", null));
            }
            case MONUMENTS -> {
                for (int p = 0; p * 2 < r.ref.length; p++)
                    out.add(new Check(nextId++, i, p, Dim.OVERWORLD, r.ref[p * 2], r.ref[p * 2 + 1], false,
                            in + "positioned " + r.ref[p * 2] + " 60 " + r.ref[p * 2 + 1] + " run locate structure minecraft:monument",
                            r.ref[p * 2] + "," + r.ref[p * 2 + 1]));
            }
            case FORTRESS -> out.add(new Check(nextId++, i, 0, Dim.NETHER, r.ref[0], r.ref[1], false,
                    in + "positioned " + r.ref[0] + " 64 " + r.ref[1] + " run locate structure minecraft:fortress", r.ref[0] + "," + r.ref[1]));
        }
        return out;
    }

    private void biomeChecks(List<Check> out, int i, int point, Result r, int x, int z) {
        String pos = r.dim != Dim.OVERWORLD ? "positioned " + x + " 64 " + z + " "
                : "positioned " + x + " 0 " + z + " positioned over world_surface positioned ~ ~-1 ~ ";
        for (String b : r.biomes)
            out.add(new Check(nextId++, i, point, r.dim, x, z, true,
                    "execute in " + r.dim.id + " " + pos + "if biome ~ ~ ~ minecraft:" + b + " run say SFV P %ID%", null));
    }

    private void send(String command) throws IOException {
        stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private String waitFor(String regex, int seconds, Progress progress) throws InterruptedException, IOException {
        Pattern p = Pattern.compile(regex);
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            progress.check();
            String l = lines.poll(500, TimeUnit.MILLISECONDS);
            if (l == null) {
                if (!process.isAlive()) throw new IOException("The test server stopped unexpectedly");
                continue;
            }
            if (p.matcher(l).find()) return l;
        }
        throw new IOException("The test server took too long");
    }

    /** All "SFV x y" messages until the "SFV S" sync marker. */
    private List<String[]> collectUntilSync(int seconds) throws InterruptedException, IOException {
        List<String[]> out = new ArrayList<>();
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            String l = lines.poll(500, TimeUnit.MILLISECONDS);
            if (l == null) {
                if (!process.isAlive()) throw new IOException("The test server stopped unexpectedly");
                continue;
            }
            Matcher m = SAY.matcher(l);
            if (!m.find()) continue;
            if (m.group(1).equals("S")) return out;
            out.add(new String[]{m.group(1), m.group(2) == null ? "" : m.group(2).trim()});
        }
        throw new IOException("The test server took too long");
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException ignored) {
            // A leftover test folder only costs disk space; the next run starts by clearing it again.
        }
    }
}
