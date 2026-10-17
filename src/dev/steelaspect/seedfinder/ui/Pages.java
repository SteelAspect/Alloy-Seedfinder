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
        /** label -> {families searched (%), positions searched}. */
        private static final Map<String, double[]> HOW = new LinkedHashMap<>();

        static {
            HOW.put("Quick · top 1% of families", new double[]{1, 1});
            HOW.put("Normal · top 5%", new double[]{5, 1});
            HOW.put("Deep · top 5%, best 5 of 15 chunks", new double[]{5, 5});
            HOW.put("Records · top 5%, best 10 of 15", new double[]{5, 10});
            HOW.put("Every family · 1 chunk", new double[]{100, 1});
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
                    stand(this), how[0], (int) how[1], Boolean.parseBoolean(value("biomes")), 25), progress);
        }
    }

    // ------------------------------------------------------------------ fortresses

    static final class Fortresses extends SearchPage {
        Fortresses(SeedFinderApp app) {
            super(app, "fortresses", "Fortresses", "Biggest nether fortresses by floor area, with the best wither skeleton farm AFK spot. About 3 min per 1M × 1M.");
        }

        @Override
        protected void buildForm(JPanel form) {
            areaFields(form, "100k × 100k");
            text(form, "minDist", "At least this far out", "0", 150);
        }

        @Override
        protected Dim dim() {
            return Dim.NETHER;
        }

        @Override
        protected List<Result> run(Settings.Seed seed, Progress progress) {
            return FortressSearch.run(new FortressSearch.Params(area(seed), number("minDist", "the distance"), 50), progress);
        }
    }
}
