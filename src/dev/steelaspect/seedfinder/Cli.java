package dev.steelaspect.seedfinder;

import dev.steelaspect.seedfinder.engine.BiomeSearch;
import dev.steelaspect.seedfinder.engine.Dim;
import dev.steelaspect.seedfinder.engine.FortressSearch;
import dev.steelaspect.seedfinder.engine.HutSearch;
import dev.steelaspect.seedfinder.engine.Mc;
import dev.steelaspect.seedfinder.engine.MonumentSearch;
import dev.steelaspect.seedfinder.engine.NearestSearch;
import dev.steelaspect.seedfinder.engine.Slime;
import dev.steelaspect.seedfinder.engine.SlimeHunt;
import dev.steelaspect.seedfinder.engine.SlimeSearch;
import dev.steelaspect.seedfinder.engine.Progress;
import dev.steelaspect.seedfinder.engine.QuadHunt;
import dev.steelaspect.seedfinder.engine.Result;
import dev.steelaspect.seedfinder.engine.RoofSearch;
import dev.steelaspect.seedfinder.engine.Search;
import dev.steelaspect.seedfinder.engine.SolidSearch;
import dev.steelaspect.seedfinder.engine.WorldType;
import dev.steelaspect.seedfinder.engine.Workers;
import dev.steelaspect.seedfinder.store.History;
import dev.steelaspect.seedfinder.store.Settings;
import dev.steelaspect.seedfinder.ui.SeedFinderApp;
import dev.steelaspect.seedfinder.ui.Theme;
import dev.steelaspect.seedfinder.verify.Verifier;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The terminal version: same searches as the app, printed as a table. */
final class Cli {
    private static final PrintStream OUT = Mc.OUT;
    private static final String HELP = """
            Alloy Seed Finder %s - finds biomes and structures in a Minecraft 1.21.11 seed.

            Run with no arguments to open the app. Command line:

              seedfinder biomes <seed> [--biomes badlands,eroded_badlands] [--joined]   biggest connected biome areas
              seedfinder nether <seed>                                                 biggest area of each nether biome
              seedfinder solid <seed> --biomes crimson_forest [--nether]               10 biggest solid squares + rectangles
              seedfinder huts <seed> [--max-y -50]                                     witch huts at or below a height
              seedfinder monuments <seed> [--min 3] [--min-dist 0]                     double/triple/quad monument AFK spots
              seedfinder quadhuts <seed> [--min 2] [--min-dist 0]                      double/triple/quad witch hut AFK spots
              seedfinder slime <seed> [--stand level|y39] [--no-biomes]               best slime farm AFK spots (cq, like kyoubitt's slime tools)
              seedfinder slimeseeds [--center 0,776] [--families 5] [--positions 1]    seeds with the best slime farm at a spot
                         [--climb <seed> --positions 15]                               or the best of a seed's 8,192 top-bit variants
              seedfinder slimerig <seed> --center x,z [--stand level|y39]              in-game test rig commands for a farm (Carpet + WorldEdit)
              seedfinder slimepicture <seed> --center x,z --out farm.png               picture of a farm's 17x17 chunks
              seedfinder nearest <seed> --type END_SHIP [--count 10 --within 1000000]   nearest structures of a kind
              seedfinder quadseeds [--from 0] [--hut-range 30000] [--seeds 1]           seeds with a quad monument at 0,0 and a quad witch hut near
              seedfinder roof <seed> [--pattern "?????,...,?????/..."]                 nether roof bedrock spots (default: nether9's wither cage)
              seedfinder lowhuts <seed> [--radius 10000] [--max-y -50] [--any]        low huts near 0,0 in all 65,536 top-bit versions (--any: quad monument not needed)
              seedfinder fortresses <seed> [--crossroads|--lines [--max-y 60] [--biome soul_sand_valley --biome-y 48-93]]  most floor, joined crossroads, straight lines

            Options for all: --center x,z (default 0,0)  --radius blocks (default 50000)  --step blocks (biome grid, default 32)
                             --top n (default 25)  --min-size blocks (biomes)  --large-biomes / --amplified
                             --speed fast|balanced|light (default fast)
                             --serial-fortresses   build fortresses one at a time the old way (same results, slower)
                             --exact   skip the quick first pass for biome/solid searches (same results, about 2x slower)
                             --cpu     don't use the graphics card (same results, slower)
                             --verify n --accept-eula   check the first n results on a real server (accepts the Minecraft EULA)
            """;

    private Cli() {}

    static int run(String[] args) throws Exception {
        if (args[0].equals("-h") || args[0].equals("--help")) {
            OUT.printf(HELP, SeedFinderApp.VERSION);
            return 0;
        }
        if (args[0].equals("screenshot")) return screenshot(args);
        String mode = args[0];
        if (args[0].matches("-?\\d+")) mode = "biomes"; // old style: just a seed
        else args = Arrays.copyOfRange(args, 1, args.length);
        // The slime seed hunt has no seed to start from.
        if ((mode.equals("slimeseeds") || mode.equals("quadseeds")) && (args.length == 0 || args[0].startsWith("--"))) {
            String[] with = new String[args.length + 1];
            with[0] = "0";
            System.arraycopy(args, 0, with, 1, args.length);
            args = with;
        }
        if (args.length == 0) {
            OUT.printf(HELP, SeedFinderApp.VERSION);
            return 2;
        }
        Map<String, String> opt = new HashMap<>();
        boolean cpuOnly = false;
        Settings.Seed seed = new Settings.Seed("seed", args[0], WorldType.NORMAL);
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--large-biomes")) seed.type = WorldType.LARGE_BIOMES;
            else if (a.equals("--amplified")) seed.type = WorldType.AMPLIFIED;
            else if (a.equals("--nether") || a.equals("--any") || a.equals("--lines") || a.equals("--crossroads") || a.equals("--joined") || a.equals("--accept-eula") || a.equals("--no-biomes")) opt.put(a, "true");
            else if (a.equals("--exact")) BiomeSearch.forceFullExact = true;
            else if (a.equals("--cpu")) cpuOnly = true;
            else if (a.equals("--serial-fortresses")) FortressSearch.serial = true;
            else if (a.equals("--speed") && i + 1 < args.length) Workers.set(Workers.Speed.valueOf(args[++i].toUpperCase(Locale.ROOT)));
            else if (a.startsWith("--") && i + 1 < args.length) opt.put(a, args[++i]);
            else {
                System.err.println("Unknown option " + a);
                return 2;
            }
        }
        String[] c = opt.getOrDefault("--center", mode.equals("slimeseeds") ? "0,776" : "0,0").split(",");
        Search area = new Search(seed.value(), seed.type, Integer.parseInt(c[0].trim()), Integer.parseInt(c[1].trim()),
                Integer.parseInt(opt.getOrDefault("--radius", "50000")));
        int top = Integer.parseInt(opt.getOrDefault("--top", "25"));
        int step = BiomeSearch.safeStep(area.radius(), Integer.parseInt(opt.getOrDefault("--step", "32")));
        List<String> biomes = opt.containsKey("--biomes") ? List.of(opt.get("--biomes").split(",")) : null;
        // The graphics card, like in the app: not with --cpu or at Light speed.
        dev.steelaspect.seedfinder.engine.GpuWorld.setEnabled(!cpuOnly && Workers.speed() != Workers.Speed.LIGHT);
        Slime.Stand stand = opt.getOrDefault("--stand", "level").toLowerCase(Locale.ROOT).contains("39") ? Slime.Stand.Y39 : Slime.Stand.LEVEL;
        if (mode.equals("slimerig")) {
            OUT.println(Slime.rig(seed.value(), area.centerX(), area.centerZ(), stand));
            return 0;
        }
        if (mode.equals("slimepicture")) {
            String out = opt.getOrDefault("--out", "slime-farm.png");
            ImageIO.write(Slime.picture(seed.value(), area.centerX(), area.centerZ(), stand), "png", new File(out));
            OUT.println("Saved " + out);
            return 0;
        }
        OUT.println("Loading the Minecraft 1.21.11 world generator...");
        if (mode.equals("lowhuts")) {
            int maxY = Integer.parseInt(opt.getOrDefault("--max-y", "-50"));
            boolean any = opt.containsKey("--any");
            List<QuadHunt.LowHut> huts = QuadHunt.lowHuts(seed.value(), area.radius(), maxY, !any, progress());
            OUT.println();
            // Grouped by seed: most low huts first, then the lowest.
            Map<Long, List<QuadHunt.LowHut>> bySeed = new java.util.LinkedHashMap<>();
            for (QuadHunt.LowHut h : huts) bySeed.computeIfAbsent(h.seed(), k -> new ArrayList<>()).add(h);
            List<List<QuadHunt.LowHut>> groups = new ArrayList<>(bySeed.values());
            groups.sort((a, b) -> a.size() != b.size() ? b.size() - a.size() : Integer.compare(a.get(0).y(), b.get(0).y()));
            for (List<QuadHunt.LowHut> g : groups.subList(0, Math.min(top, groups.size()))) {
                OUT.printf("  seed %d: %d low hut%s%s%n", g.get(0).seed(), g.size(), g.size() == 1 ? "" : "s", g.get(0).quadMonument() ? " + quad monument at 0,0" : "");
                for (QuadHunt.LowHut h : g)
                    OUT.printf("      /tp %d %d %d  (floor y %d, %d from 0,0)%n", h.x() + 3, h.y() + 2, h.z() + 3, h.y() + 1, Math.round(Math.hypot(h.x(), h.z())));
            }
            OUT.println(huts.size() + " huts at or below y " + maxY + " within " + area.radius() + " of 0,0" + (any ? "" : " (quad monument at 0,0 kept)"));
            return 0;
        }
        if (mode.equals("quadseeds")) {
            List<Long> seeds = QuadHunt.run(new QuadHunt.Params(Long.parseLong(opt.getOrDefault("--from", "0")),
                    Long.parseLong(opt.getOrDefault("--count", "281474976710656")), Integer.parseInt(opt.getOrDefault("--hut-range", "30000")),
                    Integer.parseInt(opt.getOrDefault("--seeds", "1")), OUT), Progress.NONE);
            OUT.println("Seeds: " + seeds);
            return 0;
        }
        Progress p = progress();
        long start = System.nanoTime();
        String m = mode;
        List<Result> results = Workers.run(() -> switch (m) {
            case "biomes" -> BiomeSearch.run(new BiomeSearch.Params(area, Dim.OVERWORLD, step,
                    biomes == null ? List.of("badlands", "eroded_badlands", "wooded_badlands") : biomes, biomes == null || opt.containsKey("--joined"),
                    Integer.parseInt(opt.getOrDefault("--min-size", "128")), top), p);
            case "nether" -> BiomeSearch.run(new BiomeSearch.Params(area, Dim.NETHER, step,
                    biomes == null ? List.of("nether_wastes", "crimson_forest", "warped_forest", "soul_sand_valley", "basalt_deltas") : biomes,
                    false, 64, top), p);
            case "solid" -> SolidSearch.run(new SolidSearch.Params(area, opt.containsKey("--nether") ? Dim.NETHER : Dim.OVERWORLD, step,
                    biomes == null ? List.of("badlands") : biomes), p);
            case "huts" -> HutSearch.run(new HutSearch.Params(area, Integer.parseInt(opt.getOrDefault("--max-y", "-50")), top), p);
            case "monuments", "quadhuts" -> MonumentSearch.run(new MonumentSearch.Params(area,
                    m.equals("quadhuts") ? MonumentSearch.Kind.HUT : MonumentSearch.Kind.MONUMENT,
                    Integer.parseInt(opt.getOrDefault("--min", m.equals("quadhuts") ? "2" : "3")), Integer.parseInt(opt.getOrDefault("--min-dist", "0")), top), p);
            case "slime" -> SlimeSearch.run(new SlimeSearch.Params(area, stand, !opt.containsKey("--no-biomes"), top), p);
            case "slimeseeds" -> SlimeHunt.run(new SlimeHunt.Params(area.centerX(), area.centerZ(), stand,
                    Double.parseDouble(opt.getOrDefault("--families", "5")), Integer.parseInt(opt.getOrDefault("--positions", "1")),
                    !opt.containsKey("--no-biomes"), top, opt.containsKey("--climb") ? Long.valueOf(opt.get("--climb")) : null), p);
            case "nearest" -> NearestSearch.run(new NearestSearch.Params(seed.value(), seed.type,
                    NearestSearch.Target.valueOf(opt.getOrDefault("--type", "VILLAGE").toUpperCase(Locale.ROOT)), area.centerX(), area.centerZ(),
                    Integer.parseInt(opt.getOrDefault("--count", "10")), Integer.parseInt(opt.getOrDefault("--within", "1000000"))), p);
            case "roof" -> RoofSearch.run(new RoofSearch.Params(area, opt.getOrDefault("--pattern", RoofSearch.WITHER_CAGE), top), p);
            case "fortresses" -> FortressSearch.run(new FortressSearch.Params(area, Integer.parseInt(opt.getOrDefault("--min-dist", "0")), top,
                    opt.containsKey("--crossroads") || opt.containsKey("--lines"), Integer.parseInt(opt.getOrDefault("--max-y", "255")), opt.containsKey("--lines"),
                    opt.get("--biome"), Integer.parseInt(opt.getOrDefault("--biome-y", "48-93").split("-")[0]), Integer.parseInt(opt.getOrDefault("--biome-y", "48-93").split("-")[1])), p);
            default -> {
                System.err.println("Unknown command " + m);
                yield null;
            }
        });
        if (results == null) return 2;
        OUT.printf(Locale.ROOT, "%n  done in %.1f s%s%n%n", (System.nanoTime() - start) / 1e9,
                dev.steelaspect.seedfinder.engine.GpuWorld.used() ? " (graphics card: " + dev.steelaspect.seedfinder.gpu.Gpu.status() + ")" : "");
        print(results);
        if (opt.containsKey("--verify")) return verify(results, Integer.parseInt(opt.get("--verify")), seed, opt.containsKey("--accept-eula"));
        return 0;
    }

    /** Checks the first n results on a real server with the same seed. */
    private static int verify(List<Result> results, int n, Settings.Seed seed, boolean eula) throws Exception {
        if (!eula) {
            System.err.println("--verify runs a real Minecraft server. Add --accept-eula to accept the Minecraft EULA (https://aka.ms/MinecraftEULA).");
            return 2;
        }
        java.nio.file.Path jar = Verifier.findServerJar();
        if (jar == null) {
            System.err.println("Couldn't find the Minecraft 1.21.11 server jar.");
            return 1;
        }
        List<Result> list = results.subList(0, Math.min(n, results.size()));
        OUT.println("\nVerifying " + list.size() + " on a test server...");
        new Verifier(jar, seed.value(), seed.type).verify(list, progress());
        OUT.println();
        int i = 1, ok = 0;
        for (Result r : list) {
            OUT.println("  #" + i++ + "  " + r.coords() + "  " + r.verified);
            if ("ok".equals(r.verified)) ok++;
        }
        OUT.println(ok + " of " + list.size() + " verified");
        return ok == list.size() ? 0 : 3;
    }

    private static Progress progress() {
        return new Progress() {
            int last = -1;
            String lastNote;

            @Override
            public synchronized void update(double f, String m) {
                int pct = (int) (f * 10) * 10;
                if (pct != last && f < 1) {
                    last = pct;
                    OUT.print("\r  " + pct + "%  " + m + "          ");
                }
                if (f >= 1 && !m.equals(lastNote) && !m.equals("Done")) {
                    lastNote = m;
                    OUT.print("\r  " + m + "                    ");
                }
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        };
    }

    private static void print(List<Result> results) {
        if (results.isEmpty()) {
            OUT.println("Nothing found.");
            return;
        }
        List<String> keys = new ArrayList<>();
        for (Result r : results) for (String k : r.details.keySet()) if (!keys.contains(k)) keys.add(k);
        StringBuilder head = new StringBuilder(String.format(Locale.ROOT, "%-4s %-18s %-26s %-12s", "#", "Name", "Coords", "Size"));
        for (String k : keys) head.append(String.format(Locale.ROOT, " %-14s", k));
        OUT.println(head);
        int i = 1;
        for (Result r : results) {
            StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "%-4d %-18s %-26s %-12s", i++, r.name.length() > 18 ? r.name.substring(0, 18) : r.name, r.coords(), r.size));
            for (String k : keys) line.append(String.format(Locale.ROOT, " %-14s", r.details.getOrDefault(k, "")));
            OUT.println(line);
        }
        OUT.println("\nTeleport to #1: " + results.get(0).tp());
    }

    /** seedfinder screenshot <file.png> [page|latest] - draws the app window into a PNG without showing it. */
    private static int screenshot(String[] args) throws Exception {
        Theme.install();
        SeedFinderApp[] app = new SeedFinderApp[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> app[0] = new SeedFinderApp());
        String page = args.length > 2 ? args[2] : "biomes";
        History.Entry open = null;
        if (page.equals("latest") || page.startsWith("latest:")) {
            String want = page.contains(":") ? page.substring(7) : null;
            for (History.Entry e : History.list())
                if (want == null || e.page.equals(want)) {
                    open = e;
                    break;
                }
        }
        ImageIO.write(app[0].screenshot(page, open, Integer.parseInt(args.length > 3 ? args[3] : "12000")), "png", new File(args[1]));
        OUT.println("Saved " + args[1]);
        return 0;
    }
}
