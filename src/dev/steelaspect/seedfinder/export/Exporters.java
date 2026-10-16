package dev.steelaspect.seedfinder.export;

import dev.steelaspect.seedfinder.engine.Dim;
import dev.steelaspect.seedfinder.engine.Fmt;
import dev.steelaspect.seedfinder.engine.Result;
import dev.steelaspect.seedfinder.launcher.AppDirs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Sends results to Xaero's minimap, Cytra Waypoints (/cway commands) or a CSV file. */
public final class Exporters {
    public static final String XAERO_SET = "Seed Finder";

    private Exporters() {}

    /** A short waypoint name like "Crimson forest 1" or "Witch hut 3". */
    public static String name(Result r, int rank) {
        String base = switch (r.kind) {
            case AREA, SQUARE, RECTANGLE -> r.name.contains("+") ? "Mesa" : Fmt.biome(r.name);
            default -> r.name;
        };
        if (r.kind == Result.Kind.SQUARE) base += " square";
        if (r.kind == Result.Kind.RECTANGLE) base += " rectangle";
        return base + " " + rank;
    }

    /** Height used when a result has none (waypoints need one). */
    private static int y(Result r) {
        return r.y != null ? r.y : r.dim == Dim.OVERWORLD ? 100 : 64;
    }

    // ------------------------------------------------------------------ Xaero

    /**
     * Xaero minimap folders (…/xaero/minimap) of the normal Minecraft launcher and the common modded launchers
     * (Prism, CurseForge, Modrinth, Violet), on Windows, Mac and Linux.
     */
    public static List<Path> findXaeroDirs() {
        Path home = Path.of(System.getProperty("user.home"));
        List<Path> games = new ArrayList<>();
        List<Path> instanceFolders = new ArrayList<>();
        if (AppDirs.windows()) {
            Path roaming = Path.of(env("APPDATA", home.resolve("AppData/Roaming").toString()));
            games.add(roaming.resolve(".minecraft"));
            instanceFolders.add(roaming.resolve("PrismLauncher/instances"));
            instanceFolders.add(roaming.resolve("ModrinthApp/profiles"));
            instanceFolders.add(roaming.resolve("com.modrinth.theseus/profiles"));
            instanceFolders.add(home.resolve("curseforge/minecraft/Instances"));
        } else if (AppDirs.mac()) {
            Path support = home.resolve("Library/Application Support");
            games.add(support.resolve("minecraft"));
            instanceFolders.add(support.resolve("PrismLauncher/instances"));
            instanceFolders.add(support.resolve("ModrinthApp/profiles"));
            instanceFolders.add(home.resolve("Documents/curseforge/minecraft/Instances"));
        } else {
            games.add(home.resolve(".minecraft"));
            instanceFolders.add(home.resolve(".local/share/PrismLauncher/instances"));
            instanceFolders.add(home.resolve(".var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances"));
            instanceFolders.add(home.resolve(".local/share/ModrinthApp/profiles"));
            instanceFolders.add(home.resolve(".config/Violet Launcher/instances"));
        }
        for (Path folder : instanceFolders) {
            if (!Files.isDirectory(folder)) continue;
            try (Stream<Path> instances = Files.list(folder)) {
                for (Path inst : instances.toList()) {
                    games.add(inst);
                    games.add(inst.resolve(".minecraft"));
                    games.add(inst.resolve("minecraft"));
                }
            } catch (IOException ignored) {
                // Skip unreadable launcher folders.
            }
        }
        List<Path> out = new ArrayList<>();
        for (Path g : games) {
            Path p = g.resolve("xaero/minimap");
            if (Files.isDirectory(p) && !out.contains(p)) out.add(p);
        }
        return out;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    /** Server/world folders inside a Xaero minimap folder, e.g. "Multiplayer_cytrasmp.kinetic.host". */
    public static List<String> xaeroServers(Path minimapDir) {
        List<String> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(minimapDir)) {
            for (Path d : dirs.filter(Files::isDirectory).toList()) {
                String n = d.getFileName().toString();
                if (!n.equals("backup")) out.add(n);
            }
        } catch (IOException ignored) {
            return out;
        }
        out.sort((a, b) -> Boolean.compare(!a.startsWith("Multiplayer_"), !b.startsWith("Multiplayer_")) != 0
                ? Boolean.compare(!a.startsWith("Multiplayer_"), !b.startsWith("Multiplayer_")) : a.compareToIgnoreCase(b));
        return out;
    }

    /**
     * Writes the results as Xaero waypoints in a "Seed Finder" set, replacing what that set held before.
     * The old file is kept as a .bak copy first. Returns the file written.
     */
    public static Path toXaero(Path minimapDir, String server, List<Result> results) throws IOException {
        if (results.isEmpty()) throw new IOException("Nothing to export");
        Dim dim = results.get(0).dim;
        Path dimDir = minimapDir.resolve(server).resolve(switch (dim) {
            case NETHER -> "dim%-1";
            case END -> "dim%1";
            case OVERWORLD -> "dim%0";
        });
        Files.createDirectories(dimDir);
        Path file = dimDir.resolve("mw$default_1.txt");
        List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new ArrayList<>();
        if (Files.exists(file))
            Files.copy(file, file.resolveSibling(file.getFileName() + ".bak-" + System.currentTimeMillis()), StandardCopyOption.REPLACE_EXISTING);
        if (lines.isEmpty()) {
            lines.add("sets:gui.xaero_default:" + XAERO_SET);
            lines.add("#");
            lines.add("#waypoint:name:initials:x:y:z:color:disabled:type:set:rotate_on_tp:tp_yaw:visibility_type:destination");
            lines.add("#");
        } else if (lines.get(0).startsWith("sets:") && !List.of(lines.get(0).substring(5).split(":")).contains(XAERO_SET)) {
            lines.set(0, lines.get(0) + ":" + XAERO_SET);
        }
        lines.removeIf(l -> l.startsWith("waypoint:") && l.split(":", -1).length > 9 && l.split(":", -1)[9].equals(XAERO_SET));
        int rank = 1;
        for (Result r : results) {
            String name = name(r, rank++).replace(":", " ");
            String initials = name.substring(0, 1).toUpperCase(Locale.ROOT);
            lines.add(String.format(Locale.ROOT, "waypoint:%s:%s:%d:%s:%d:%d:false:0:%s:false:0:0:false",
                    name, initials, r.x, r.y == null ? "~" : r.y.toString(), r.z, 14, XAERO_SET));
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file;
    }

    /** True if a Minecraft client seems to be running (Xaero would overwrite the file when it saves). */
    public static boolean minecraftRunning() {
        try (Stream<ProcessHandle> ps = ProcessHandle.allProcesses()) {
            return ps.anyMatch(p -> p.info().commandLine().map(c -> c.contains("KnotClient") || c.contains("net.minecraft.client.main.Main")).orElse(false));
        }
    }

    // ------------------------------------------------------------------ Cytra Waypoints

    /** One /cway add command per result, to paste in game (Cytra Waypoints shares them with everyone). */
    public static String cwayCommands(List<Result> results, String category) {
        StringBuilder sb = new StringBuilder();
        int rank = 1;
        for (Result r : results) {
            String name = name(r, rank++).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
            sb.append("/cway add ").append(name).append(' ').append(category).append(' ')
                    .append(r.x).append(' ').append(y(r)).append(' ').append(r.z).append(' ').append(r.dim.id).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ CSV

    public static void toCsv(Path file, List<Result> results) throws IOException {
        List<String> keys = new ArrayList<>();
        for (Result r : results) for (String k : r.details.keySet()) if (!keys.contains(k)) keys.add(k);
        StringBuilder sb = new StringBuilder("rank,name,dimension,x,y,z,size");
        for (String k : keys) sb.append(',').append(csv(k));
        sb.append(",verified\n");
        int rank = 1;
        for (Result r : results) {
            sb.append(rank++).append(',').append(csv(r.name)).append(',').append(r.dim.id).append(',').append(r.x).append(',')
                    .append(r.y == null ? "" : r.y).append(',').append(r.z).append(',').append(csv(r.size));
            for (String k : keys) sb.append(',').append(csv(r.details.getOrDefault(k, "")));
            sb.append(',').append(r.verified == null ? "" : csv(r.verified)).append('\n');
        }
        Files.writeString(file, sb, StandardCharsets.UTF_8);
    }

    private static String csv(String s) {
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
