package dev.steelaspect.seedfinder.ui;

import dev.steelaspect.seedfinder.engine.BiomeSearch;
import dev.steelaspect.seedfinder.engine.Dim;
import dev.steelaspect.seedfinder.engine.FortressSearch;
import dev.steelaspect.seedfinder.engine.HutSearch;
import dev.steelaspect.seedfinder.engine.MonumentSearch;
import dev.steelaspect.seedfinder.engine.NearestSearch;
import dev.steelaspect.seedfinder.engine.Progress;
import dev.steelaspect.seedfinder.engine.Result;
import dev.steelaspect.seedfinder.engine.Search;
import dev.steelaspect.seedfinder.engine.SlimeSearch;
import dev.steelaspect.seedfinder.engine.SolidSearch;
import dev.steelaspect.seedfinder.engine.Fmt;
import dev.steelaspect.seedfinder.store.Settings;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The search tools shown in the sidebar. */
final class Pages {
    static final String MESA = "Mesa (all 3 kinds)";
    static final String ALL_NETHER = "All nether biomes";
    static final List<String> NETHER = List.of("nether_wastes", "crimson_forest", "warped_forest", "soul_sand_valley", "basalt_deltas");
    static final List<String> MESA_KINDS = List.of("badlands", "eroded_badlands", "wooded_badlands");
    /** Overworld biomes you can see at the surface (cave biomes are underground, so they're left out). */
    static final List<String> OVERWORLD = List.of("badlands", "eroded_badlands", "wooded_badlands", "bamboo_jungle", "beach", "birch_forest",
            "cherry_grove", "cold_ocean", "dark_forest", "deep_cold_ocean", "deep_frozen_ocean", "deep_lukewarm_ocean", "deep_ocean", "desert",
            "flower_forest", "forest", "frozen_ocean", "frozen_peaks", "frozen_river", "grove", "ice_spikes", "jagged_peaks", "jungle",
            "lukewarm_ocean", "mangrove_swamp", "meadow", "mushroom_fields", "ocean", "old_growth_birch_forest", "old_growth_pine_taiga",
            "old_growth_spruce_taiga", "pale_garden", "plains", "river", "savanna", "savanna_plateau", "snowy_beach", "snowy_plains",
            "snowy_slopes", "snowy_taiga", "sparse_jungle", "stony_peaks", "stony_shore", "sunflower_plains", "swamp", "taiga", "warm_ocean",
            "windswept_forest", "windswept_gravelly_hills", "windswept_hills", "windswept_savanna");
    /** Detail options: label -> one check every N blocks. */
    static final Map<String, Integer> DETAIL = new LinkedHashMap<>();

    static {
        DETAIL.put("Fine · every 16", 16);
        DETAIL.put("Normal · every 32", 32);
        DETAIL.put("Fast · every 64", 64);
        DETAIL.put("Rough · every 128", 128);
    }

    private Pages() {}

    /** "Mesa (all 3 kinds)", then every biome by its friendly name. */
    static String[] biomeChoices(boolean withNether) {
        List<String> out = new ArrayList<>();
        if (withNether) {
            out.add(ALL_NETHER);
            for (String b : NETHER) out.add(Fmt.biome(b));
        }
        out.add(MESA);
        for (String b : OVERWORLD) out.add(Fmt.biome(b));
        return out.toArray(String[]::new);
    }

    /** Friendly name back to biome ids. */
    static List<String> biomesFor(String choice) {
        if (choice.equals(MESA)) return MESA_KINDS;
        if (choice.equals(ALL_NETHER)) return NETHER;
        String id = choice.toLowerCase().replace(' ', '_');
        return List.of(id);
    }

    static int step(SearchPage p, int radius) {
        Integer s = DETAIL.get(p.value("detail"));
        return BiomeSearch.safeStep(radius, s == null ? 32 : s);
    }

    // ------------------------------------------------------------------ overworld biomes

    static final class Biomes extends SearchPage {
        Biomes(SeedFinderApp app) {
            super(app, "biomes", "Biomes", "Biggest connected areas of a biome in the overworld");
        }

        @Override
        protected void buildForm(JPanel form) {
            combo(form, "biome", "Biome", biomeChoices(false), 190);
            areaFields(form, "100k × 100k");
            JComboBox<String> d = combo(form, "detail", "Detail", DETAIL.keySet().toArray(String[]::new), 160);
            d.setSelectedIndex(1);
            text(form, "minSize", "Smallest (blocks)", "128", 110);
        }

        @Override
        protected java.util.Set<String> highlight() {
            // All nether biomes at once: show their normal colours instead of highlighting everything.
            return value("biome").equals(ALL_NETHER) ? java.util.Set.of() : java.util.Set.copyOf(biomesFor(value("biome")));
        }

        @Override
        protected Dim dim() {
            return Dim.OVERWORLD;
        }

        @Override
        protected List<Result> run(Settings.Seed seed, Progress progress) {
            Search a = area(seed);
            List<String> biomes = biomesFor(value("biome"));
            return BiomeSearch.run(new BiomeSearch.Params(a, Dim.OVERWORLD, step(this, a.radius()), biomes, true,
                    number("minSize", "Smallest"), 50), progress);
        }

        @Override
        protected String emptyText() {
            return "Pick a biome and press Search";
        }
    }

    // ------------------------------------------------------------------ nether biomes

    static final class Nether extends SearchPage {
        Nether(SeedFinderApp app) {
            super(app, "nether", "Nether biomes", "Biggest area of each nether biome (nether wastes joins up into huge webs)");
        }

        @Override
        protected void buildForm(JPanel form) {
            JComboBox<String> b = combo(form, "biome", "Biome", new String[]{ALL_NETHER, "Crimson forest", "Warped forest", "Soul sand valley",
                    "Basalt deltas", "Nether wastes"}, 180);
            b.setSelectedIndex(0);
            areaFields(form, "100k × 100k");
            JComboBox<String> d = combo(form, "detail", "Detail", DETAIL.keySet().toArray(String[]::new), 160);
            d.setSelectedIndex(2);
        }

        @Override
        protected java.util.Set<String> highlight() {
            // All nether biomes at once: show their normal colours instead of highlighting everything.
            return value("biome").equals(ALL_NETHER) ? java.util.Set.of() : java.util.Set.copyOf(biomesFor(value("biome")));
        }

        @Override
        protected Dim dim() {
            return Dim.NETHER;
        }

        @Override
        protected List<Result> run(Settings.Seed seed, Progress progress) {
            Search a = area(seed);
            return BiomeSearch.run(new BiomeSearch.Params(a, Dim.NETHER, step(this, a.radius()), biomesFor(value("biome")), false, 64, 30), progress);
        }
    }

    // ------------------------------------------------------------------ solid squares

    static final class Solid extends SearchPage {
        Solid(SeedFinderApp app) {
            super(app, "solid", "Solid areas", "The 10 biggest squares and rectangles that are all one biome, each in a different spot (exact to 4 blocks)");
        }

        @Override
        protected void buildForm(JPanel form) {
            combo(form, "biome", "Biome", biomeChoices(true), 190);
            areaFields(form, "100k × 100k", true);
            JComboBox<String> d = combo(form, "detail", "Detail", DETAIL.keySet().toArray(String[]::new), 160);
            d.setSelectedIndex(2);
        }

        @Override
        protected java.util.Set<String> highlight() {
            // All nether biomes at once: show their normal colours instead of highlighting everything.
            return value("biome").equals(ALL_NETHER) ? java.util.Set.of() : java.util.Set.copyOf(biomesFor(value("biome")));
        }

        @Override
        protected Dim dim() {
            List<String> b = biomesFor(value("biome"));
            return NETHER.contains(b.get(0)) ? Dim.NETHER : Dim.OVERWORLD;
        }

        @Override
        protected List<Result> run(Settings.Seed seed, Progress progress) {
            Search a = area(seed);
            return SolidSearch.run(new SolidSearch.Params(a, dim(), step(this, a.radius()), biomesFor(value("biome"))), progress);
        }

        @Override
        protected String emptyText() {
            return "Pick a biome (or all nether biomes) and press Search";
        }
    }

    // ------------------------------------------------------------------ witch huts

    static final class Huts extends SearchPage {
        Huts(SeedFinderApp app) {
            super(app, "huts", "Witch huts", "Huts at or below a height (huts over big cave openings generate deep). For quad huts, see Quad finder.");
        }

        @Override
        protected void buildForm(JPanel form) {
            text(form, "maxY", "At or below y", "-50", 100);
            areaFields(form, "200k × 200k");
        }

        @Override
        protected java.util.Set<String> highlight() {
            return java.util.Set.of("swamp");
        }

        @Override
        protected Dim dim() {
            return Dim.OVERWORLD;
        }

        @Override
        protected List<Result> run(Settings.Seed seed, Progress progress) {
            return HutSearch.run(new HutSearch.Params(area(seed), number("maxY", "the height"), 50), progress);
        }
    }

    // ------------------------------------------------------------------ ocean monuments

    static final class Monuments extends SearchPage {
        private static final String[] COUNTS = {"4 · quad", "3 · triple", "2 · double"};
        private static final String[] KINDS = {"Ocean monuments", "Witch huts"};

        Monuments(SeedFinderApp app) {
            super(app, "monuments", "Quad finder", "AFK spots in range of 2, 3 or 4 ocean monuments or witch huts, ranked by spawning space in range");
        }

        @Override
        protected void buildForm(JPanel form) {
            combo(form, "kind", "Structure", KINDS, 160);
            JComboBox<String> c = combo(form, "count", "At least", COUNTS, 130);
            c.setSelectedIndex(1);
            areaFields(form, "400k × 400k", true);
            text(form, "minDist", "At least this far out", "0", 150);
        }

        private MonumentSearch.Kind kind() {
            return value("kind").equals(KINDS[1]) ? MonumentSearch.Kind.HUT : MonumentSearch.Kind.MONUMENT;
        }

        @Override
        protected java.util.Set<String> highlight() {
            return kind() == MonumentSearch.Kind.HUT ? java.util.Set.of("swamp")
                    : java.util.Set.of("deep_ocean", "deep_cold_ocean", "deep_lukewarm_ocean", "deep_frozen_ocean");
        }

        @Override
        protected Dim dim() {
            return Dim.OVERWORLD;
        }

        @Override
        protected List<Result> run(Settings.Seed seed, Progress progress) {
            int count = Integer.parseInt(value("count").substring(0, 1));
            Search a = area(seed);
            // Monument doubles are common (every few thousand blocks), so a huge search would just find millions.
            if (count == 2 && kind() == MonumentSearch.Kind.MONUMENT && a.radius() > 1_000_000)
                throw new BadInput("Monument doubles are common: search 2M × 2M or less, or pick triple");
            return MonumentSearch.run(new MonumentSearch.Params(a, kind(), count, number("minDist", "the distance"), 50), progress);
        }
    }

    // ------------------------------------------------------------------ nearest structures

    static final class Nearest extends SearchPage {
        private static final Map<String, Integer> WITHIN = new LinkedHashMap<>();

        static {
            WITHIN.put("10k blocks", 10_000);
            WITHIN.put("50k blocks", 50_000);
            WITHIN.put("200k blocks", 200_000);
            WITHIN.put("1M blocks", 1_000_000);
            WITHIN.put("5M blocks", 5_000_000);
            WITHIN.put("Whole world", 30_000_000);
        }

        Nearest(SeedFinderApp app) {
            super(app, "nearest", "Nearest structures", "The closest structures of a kind: village types, treasure bastions, end cities with a ship...");
        }

        @Override
        protected void buildForm(JPanel form) {
            String[] names = java.util.Arrays.stream(NearestSearch.Target.values()).map(x -> x.label).toArray(String[]::new);
            combo(form, "target", "Structure", names, 210);
            text(form, "center", "From x, z", "0, 0", 150);
            combo(form, "count", "How many", new String[]{"10", "25", "50", "100"}, 90).setSelectedIndex(1);
            combo(form, "within", "Within", WITHIN.keySet().toArray(String[]::new), 140).setSelectedIndex(3);
        }

        private NearestSearch.Target target() {
            for (NearestSearch.Target t : NearestSearch.Target.values()) if (t.label.equals(value("target"))) return t;
            return NearestSearch.Target.VILLAGE;
        }

        @Override
        protected Search area(Settings.Seed seed) {
            Search a = super.areaFrom(seed, WITHIN.getOrDefault(value("within"), 1_000_000));
            return a;
        }

        @Override
        protected Dim dim() {
            return target().dim();
        }

        @Override
        protected List<Result> run(Settings.Seed seed, Progress progress) {
            Search a = area(seed);
            return NearestSearch.run(new NearestSearch.Params(seed.value(), seed.type, target(), a.centerX(), a.centerZ(),
                    Integer.parseInt(value("count")), a.radius()), progress);
        }
    }

    // ------------------------------------------------------------------ slime chunks

    static final String[] STANDS = java.util.Arrays.stream(dev.steelaspect.seedfinder.engine.Slime.Stand.values())
            .map(x -> x.label).toArray(String[]::new);

    static dev.steelaspect.seedfinder.engine.Slime.Stand stand(SearchPage p) {
        for (dev.steelaspect.seedfinder.engine.Slime.Stand x : dev.steelaspect.seedfinder.engine.Slime.Stand.values())
            if (x.label.equals(p.value("stand"))) return x;
        return dev.steelaspect.seedfinder.engine.Slime.Stand.LEVEL;
    }

    static final class Slime extends SearchPage {
        Slime(SeedFinderApp app) {
            super(app, "slime", "Slime chunks", "Best slime farm AFK spots, scored like kyoubitt's slime tools: one floor of slime chunks at y -64. "
                    + "Pick the nearest one within 1 cq of the best.");
        }

        @Override
        protected void buildForm(JPanel form) {
            areaFields(form, "100k × 100k");
            combo(form, "stand", "Stand", STANDS, 200).setToolTipText("<html><b>Level with the floor</b>: the cq the slime tools and their records use.<br>"
                    + "<b>Feet at y -39</b>: what Carpet's /perimeterinfo reads in game over a y -64 floor.</html>");
            check(form, "biomes", "Biomes", true).setToolTipText("Deep dark and mushroom fields have no slimes. Turn off for a superflat test world.");
        }

        @Override
        protected boolean slimeChunks() {
            return true;
        }

        @Override
        protected Dim dim() {
            return Dim.OVERWORLD;
        }

        @Override
        protected List<javax.swing.JComponent> extraActions() {
            return List.of(farmButton(false));
        }

        @Override
        protected List<Result> run(Settings.Seed seed, Progress progress) {
            return SlimeSearch.run(new SlimeSearch.Params(area(seed), stand(this), Boolean.parseBoolean(value("biomes")), 20), progress);
        }
    }

    // ------------------------------------------------------------------ slime seeds

    static final class SlimeSeeds extends SearchPage {
        /** The toolchain's high-bit climb, from the seed in the top bar. */
        private static final String CLIMB = "Climb my seed · its 8,192 top-bit variants";
        /** label -> {families searched (%), positions searched}. */
        private static final Map<String, double[]> HOW = new LinkedHashMap<>();

        static {
            HOW.put("Quick · top 1% of families", new double[]{1, 1});
            HOW.put("Normal · top 5%", new double[]{5, 1});
            HOW.put("Deep · top 5%, best 5 of 15 chunks", new double[]{5, 5});
            HOW.put("Records · top 5%, best 10 of 15", new double[]{5, 10});
            HOW.put("Every family · 1 chunk", new double[]{100, 1});
            HOW.put(CLIMB, new double[]{0, 15});
        }

        SlimeSeeds(SeedFinderApp app) {
            super(app, "slimeseeds", "Slime seeds", "Seeds with the best slime farm at a spot, by kyoubitt's exact phase search: every seed of the "
                    + "richest families, all at once. Z of 1552k + 768 or 784 holds the best spots.");
        }

        @Override
        protected void buildForm(JPanel form) {
            text(form, "center", "Spot x, z", "0, 776", 150);
            combo(form, "how", "How much", HOW.keySet().toArray(String[]::new), 250).setSelectedIndex(1);
            combo(form, "stand", "Stand", STANDS, 200);
            check(form, "biomes", "Biomes", true).setToolTipText("Deep dark and mushroom fields have no slimes. Turn off for a superflat test world.");
        }

        @Override
        protected Search area(Settings.Seed seed) {
            return areaFrom(seed, 400);
        }

        @Override
        protected boolean slimeChunks() {
            return true;
        }

        @Override
        protected Dim dim() {
            return Dim.OVERWORLD;
        }

        @Override
        protected List<javax.swing.JComponent> extraActions() {
            return List.of(farmButton(true));
        }

        @Override
        protected String emptyText() {
            return "Pick a spot and press Search. Each result is a different seed: select one to see its slime chunks.";
        }

        @Override
        protected List<Result> run(Settings.Seed seed, Progress progress) throws Exception {
            Search a = area(seed);
            double[] how = HOW.getOrDefault(value("how"), new double[]{5, 1});
            return dev.steelaspect.seedfinder.engine.SlimeHunt.run(new dev.steelaspect.seedfinder.engine.SlimeHunt.Params(a.centerX(), a.centerZ(),
                    stand(this), how[0], (int) how[1], Boolean.parseBoolean(value("biomes")), 25,
                    value("how").equals(CLIMB) ? seed.value() : null), progress);
        }
    }

    // ------------------------------------------------------------------ fortresses

    static final class Fortresses extends SearchPage {
        Fortresses(SeedFinderApp app) {
            super(app, "fortresses", "Fortresses", "Biggest fortresses, joined crossroads, straight crossroad lines or overlapping fortresses, "
                    + "optionally all in one biome. About 3 min per 1M × 1M.");
        }

        private static final String[] RANKS = {"Floor area", "Joined crossroads", "Straight crossroad lines", "Overlapping fortresses"};
        private static final String[] BIOMES = {"Any biome", "soul_sand_valley", "nether_wastes", "crimson_forest", "warped_forest", "basalt_deltas"};
        private static final String[] BIOME_WHAT = {"Whole fortress", "Just the crossroads"};

        @Override
        protected void buildForm(JPanel form) {
            combo(form, "rank", "Rank by", RANKS, 190).setToolTipText("<html><b>Joined crossroads</b>: 4-way bridge crossings side by side at the same height"
                    + "<br>(double, triple line or corner, quad line or square, quint), like Gaider10's CrossroadFinder."
                    + "<br><b>Straight crossroad lines</b>: the longest straight run of them."
                    + "<br><b>Overlapping fortresses</b>: pairs whose areas overlap, biggest overlap first.</html>");
            text(form, "maxY", "Crossroads at or below y", "255", 100);
            combo(form, "biome", "All in biome", BIOMES, 160).setToolTipText("<html>Nether biomes change with height: the biome is checked every 4 blocks"
                    + "<br>at every height in the range below.</html>");
            text(form, "biomeY", "Heights", "48-93", 80);
            combo(form, "biomeWhat", "Check", BIOME_WHAT, 150);
            areaFields(form, "100k × 100k", true);
            text(form, "minDist", "At least this far out", "0", 150);
        }

        @Override
        protected Dim dim() {
            return Dim.NETHER;
        }

        @Override
        protected java.util.Set<String> highlight() {
            return value("biome").equals(BIOMES[0]) ? java.util.Set.of() : java.util.Set.of(value("biome"));
        }

        @Override
        protected List<Result> run(Settings.Seed seed, Progress progress) {
            String rank = value("rank"), biome = value("biome").equals(BIOMES[0]) ? null : value("biome");
            int[] ys;
            try {
                String[] h = value("biomeY").split("\\s*(-|to)\\s*");
                ys = new int[]{Integer.parseInt(h[0].trim()), Integer.parseInt(h[h.length - 1].trim())};
            } catch (RuntimeException e) {
                throw new BadInput("Enter the heights like 48-93");
            }
            boolean whole = value("biomeWhat").equals(BIOME_WHAT[0]);
            boolean crossroads = rank.equals(RANKS[1]) || rank.equals(RANKS[2]);
            if (biome != null && !whole && !crossroads) throw new BadInput("\"Just the crossroads\" needs Rank by joined crossroads or straight lines");
            return FortressSearch.run(new FortressSearch.Params(area(seed), number("minDist", "the distance"), 50, crossroads, number("maxY", "the height"),
                    rank.equals(RANKS[2]), biome, Math.min(ys[0], ys[1]), Math.max(ys[0], ys[1]), biome != null && whole, rank.equals(RANKS[3])), progress);
        }
    }

    // ------------------------------------------------------------------ nether roof

    static final class Roof extends SearchPage {
        Roof(SeedFinderApp app) {
            super(app, "roof", "Nether roof", "Bedrock shapes in the nether roof (y 123 to 126): a wither cage like nether9, solid 3x3 blocks, or your own pattern. Nearest first.");
        }

        @Override
        protected void buildForm(JPanel form) {
            java.util.List<String> shapes = new java.util.ArrayList<>(dev.steelaspect.seedfinder.engine.RoofSearch.PRESETS.keySet());
            shapes.add("Custom");
            JComboBox<String> shape = combo(form, "shape", "Shape", shapes.toArray(String[]::new), 250);
            javax.swing.JTextField pattern = text(form, "pattern", "Pattern", dev.steelaspect.seedfinder.engine.RoofSearch.WITHER_CAGE, 300);
            pattern.setToolTipText("<html>Layers y 123, 124, 125, 126 separated by <b>/</b>;"
                    + " each 5 rows (north first) of 5 blocks (west first), separated by commas.<br><b>#</b> bedrock, <b>.</b> no bedrock, <b>?</b> anything."
                    + "<br>y 127 is always bedrock. Pick a shape to fill this in, or type your own.</html>");
            shape.addActionListener(e -> {
                String p = dev.steelaspect.seedfinder.engine.RoofSearch.PRESETS.get(String.valueOf(shape.getSelectedItem()));
                if (p != null) pattern.setText(p);
            });
            pattern.addKeyListener(new java.awt.event.KeyAdapter() {
                @Override
                public void keyTyped(java.awt.event.KeyEvent e) {
                    shape.setSelectedItem("Custom");
                }
            });
            areaFields(form, "20k × 20k");
        }

        @Override
        protected Dim dim() {
            return Dim.NETHER;
        }

        @Override
        protected List<Result> run(Settings.Seed seed, Progress progress) {
            try {
                dev.steelaspect.seedfinder.engine.RoofSearch.parse(value("pattern"));
            } catch (IllegalArgumentException e) {
                throw new BadInput(e.getMessage());
            }
            return dev.steelaspect.seedfinder.engine.RoofSearch.run(new dev.steelaspect.seedfinder.engine.RoofSearch.Params(area(seed), value("pattern"), 50), progress);
        }
    }

    // ------------------------------------------------------------------ seed hunts

    static final class SeedHunts extends SearchPage {
        private static final String QUAD = "Quad monument + quad witch hut", LOW = "Low witch huts · versions of my seed";

        SeedHunts(SeedFinderApp app) {
            super(app, "seedhunts", "Seed hunts", "Find new seeds: a quad ocean monument at 0,0 with a quad witch hut nearby (graphics card), "
                    + "or low witch huts near 0,0 in the 65,536 versions of your seed. Each result is its own seed.");
        }

        @Override
        protected void buildForm(JPanel form) {
            combo(form, "hunt", "Hunt", new String[]{QUAD, LOW}, 270).setToolTipText("<html><b>Quad monument + quad hut</b>: where structures go depends only on the"
                    + "<br>low 48 bits of a seed, so the graphics card tries every one (about 20 billion a second) for 4 monuments"
                    + "<br>around 0,0, then the game's own code checks the quad hut and all biomes. Expect a few seeds an hour."
                    + "<br><b>Low witch huts</b>: same hut spots in every version of your seed, but different ground under them."
                    + "<br>Huts below y -54 need a mineshaft under them, which this doesn't model.</html>");
            text(form, "range", "Within (blocks of 0,0)", "30000", 150);
            text(form, "count", "Stop after (seeds)", "5", 120);
            text(form, "maxY", "Huts at or below y", "-40", 120);
            check(form, "keepMon", "Keep the quad monument", false).setToolTipText("Low huts: only versions where the seed's quad monument at 0,0 still generates");
        }

        @Override
        protected Search area(Settings.Seed seed) {
            return new Search(seed.value(), seed.type, 0, 0, Math.max(1000, number("range", "the range")));
        }

        @Override
        protected Dim dim() {
            return Dim.OVERWORLD;
        }

        @Override
        protected List<Result> run(Settings.Seed seed, Progress progress) throws Exception {
            int range = number("range", "the range"), count = number("count", "the number of seeds");
            List<Result> out = new java.util.ArrayList<>();
            if (value("hunt").equals(QUAD)) {
                // Each person starts somewhere different, so two people don't find the same seeds.
                long from = java.util.concurrent.ThreadLocalRandom.current().nextLong() & ((1L << 48) - 1);
                for (dev.steelaspect.seedfinder.engine.QuadHunt.Found f : dev.steelaspect.seedfinder.engine.QuadHunt.run(
                        new dev.steelaspect.seedfinder.engine.QuadHunt.Params(from, 1L << 48, range, count, new java.io.PrintStream(java.io.OutputStream.nullOutputStream())), progress)) {
                    Result r = new Result();
                    r.kind = Result.Kind.STRUCTURE;
                    r.dim = Dim.OVERWORLD;
                    r.name = "Quad seed";
                    r.seed = f.seed();
                    r.x = f.hutX();
                    r.z = f.hutZ();
                    r.y = 64;
                    r.size = Long.toString(f.seed());
                    r.details.put("Seed", Long.toString(f.seed()));
                    r.details.put("Quad monument", "around 0, 0 (use the Quad finder on this seed for the AFK spot)");
                    r.details.put("Quad witch hut", "around " + f.hutX() + ", " + f.hutZ());
                    out.add(r);
                }
                return out;
            }
            int maxY = number("maxY", "the height");
            for (dev.steelaspect.seedfinder.engine.QuadHunt.LowHut h : dev.steelaspect.seedfinder.engine.QuadHunt.lowHuts(seed.value(), range, maxY,
                    Boolean.parseBoolean(value("keepMon")), progress)) {
                if (out.size() >= Math.max(count, 50)) break;
                Result r = new Result();
                r.kind = Result.Kind.HUT;
                r.dim = Dim.OVERWORLD;
                r.name = "Witch hut";
                r.seed = h.seed();
                r.x = h.x() + 3;
                r.z = h.z() + 4;
                r.y = h.y() + 2;
                r.minX = h.x();
                r.minZ = h.z();
                r.maxX = h.x() + 8;
                r.maxZ = h.z() + 8;
                r.size = "Floor y " + (h.y() + 1);
                r.details.put("Seed", Long.toString(h.seed()));
                r.details.put("Distance", dev.steelaspect.seedfinder.engine.Fmt.n(Math.round(Math.hypot(h.x(), h.z()))));
                if (h.quadMonument()) r.details.put("Quad monument", "at 0, 0");
                out.add(r);
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ seed overview

    static final class Overview extends SearchPage {
        private static final Map<String, Integer> HOW_FAR = new LinkedHashMap<>();

        static {
            HOW_FAR.put("5,000 blocks", 5_000);
            HOW_FAR.put("10,000 blocks", 10_000);
            HOW_FAR.put("20,000 blocks", 20_000);
            HOW_FAR.put("50,000 blocks", 50_000);
        }

        Overview(SeedFinderApp app) {
            super(app, "overview", "Seed overview", "The best of everything around a point in one go: nearest structures, quads, the lowest witch huts, "
                    + "the biggest fortress, crossroad lines and a wither cage roof. Nether checks are around the matching nether spot (x ÷ 8, z ÷ 8).");
        }

        @Override
        protected void buildForm(JPanel form) {
            text(form, "center", "Around x, z (overworld)", "0, 0", 170);
            combo(form, "far", "How far", HOW_FAR.keySet().toArray(String[]::new), 150).setSelectedIndex(1);
        }

        private int far() {
            return HOW_FAR.getOrDefault(value("far"), 10_000);
        }

        @Override
        protected Search area(Settings.Seed seed) {
            return areaFrom(seed, far());
        }

        @Override
        protected Dim dim() {
            return Dim.OVERWORLD;
        }

        @Override
        protected List<Result> run(Settings.Seed seed, Progress progress) {
            Search a = area(seed);
            int r = a.radius(), nr = Math.max(2_000, r / 8);
            Search nether = new Search(seed.value(), seed.type, Math.floorDiv(a.centerX(), 8), Math.floorDiv(a.centerZ(), 8), nr);
            List<Result> out = new java.util.ArrayList<>();
            List<String> problems = new java.util.ArrayList<>();
            // name, job: each check gets an equal share of the progress bar.
            List<Map.Entry<String, java.util.concurrent.Callable<List<Result>>>> jobs = new java.util.ArrayList<>();
            for (NearestSearch.Target t : new NearestSearch.Target[]{NearestSearch.Target.VILLAGE, NearestSearch.Target.TRIAL_CHAMBER,
                    NearestSearch.Target.ANCIENT_CITY, NearestSearch.Target.STRONGHOLD, NearestSearch.Target.MANSION, NearestSearch.Target.MONUMENT,
                    NearestSearch.Target.BASTION_TREASURE}) {
                boolean n = t.dim() == Dim.NETHER;
                jobs.add(Map.entry(t.label, () -> NearestSearch.run(new NearestSearch.Params(seed.value(), seed.type, t,
                        n ? nether.centerX() : a.centerX(), n ? nether.centerZ() : a.centerZ(), 1, n ? nr : r), quiet(progress))));
            }
            jobs.add(Map.entry("Monument quads", () -> MonumentSearch.run(new MonumentSearch.Params(a, MonumentSearch.Kind.MONUMENT, 3, 0, 1), quiet(progress))));
            jobs.add(Map.entry("Witch hut quads", () -> MonumentSearch.run(new MonumentSearch.Params(a, MonumentSearch.Kind.HUT, 3, 0, 1), quiet(progress))));
            jobs.add(Map.entry("Lowest witch huts", () -> HutSearch.run(new HutSearch.Params(a, -30, 3), quiet(progress))));
            jobs.add(Map.entry("Biggest fortress", () -> FortressSearch.run(new FortressSearch.Params(nether, 0, 1), quiet(progress))));
            jobs.add(Map.entry("Crossroad line", () -> FortressSearch.run(new FortressSearch.Params(nether, 0, 1, true, 255, true), quiet(progress))));
            jobs.add(Map.entry("Wither cage roof", () -> dev.steelaspect.seedfinder.engine.RoofSearch.run(
                    new dev.steelaspect.seedfinder.engine.RoofSearch.Params(new Search(seed.value(), seed.type, nether.centerX(), nether.centerZ(), 2_000),
                            dev.steelaspect.seedfinder.engine.RoofSearch.WITHER_CAGE, 1), quiet(progress))));
            for (int i = 0; i < jobs.size(); i++) {
                progress.check();
                progress.update((double) i / jobs.size(), "Checking " + jobs.get(i).getKey().toLowerCase(java.util.Locale.ROOT));
                try {
                    for (Result res : jobs.get(i).getValue().call()) {
                        res.details.put("Check", jobs.get(i).getKey());
                        if (res.dim == Dim.NETHER) res.name += " (nether)";
                        out.add(res);
                    }
                } catch (Progress.Cancelled e) {
                    throw e;
                } catch (Exception e) {
                    problems.add(jobs.get(i).getKey());
                }
            }
            progress.update(1, out.size() + " things found" + (problems.isEmpty() ? "" : " · couldn't check: " + String.join(", ", problems)));
            return out;
        }

        /** A part's own progress messages would flicker over the overview's; only stopping passes through. */
        private static Progress quiet(Progress outer) {
            return new Progress() {
                @Override
                public void update(double fraction, String message) {}

                @Override
                public boolean cancelled() {
                    return outer.cancelled();
                }
            };
        }
    }
}
