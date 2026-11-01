package dev.steelaspect.seedfinder.store;

import dev.steelaspect.seedfinder.engine.WorldType;
import dev.steelaspect.seedfinder.engine.Workers;
import dev.steelaspect.seedfinder.launcher.AppDirs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** App settings, saved in ~/.config/alloy-seedfinder/settings.json. */
public final class Settings {
    public static final Path FILE = AppDirs.config().toPath().resolve("settings.json");

    public static final class Seed {
        public String name;
        public String seed;
        public WorldType type = WorldType.NORMAL;

        public Seed() {}

        public Seed(String name, String seed, WorldType type) {
            this.name = name;
            this.seed = seed;
            this.type = type;
        }

        /** Same rule as the game's seed box: a number is used as-is, any other text is hashed. */
        public long value() {
            try {
                return Long.parseLong(seed.trim());
            } catch (NumberFormatException e) {
                return seed.hashCode();
            }
        }

        @Override
        public String toString() {
            return name + "  ·  " + seed;
        }
    }

    public List<Seed> seeds = new ArrayList<>();
    public int selected;
    /** The user accepted the Minecraft EULA for the local test server the Verify button runs. */
    public boolean eulaAccepted;
    /** Vanilla 1.21.11 server jar used by Verify; empty = find it automatically. */
    public String serverJar = "";
    /** Xaero's minimap folder (…/xaero/minimap) and the server folder inside it to add waypoints to. */
    public String xaeroDir = "";
    public String xaeroServer = "";
    /** How much of the computer searches may use. */
    public Workers.Speed speed = Workers.Speed.BALANCED;
    /** Work out biomes on the graphics card (maps, biome and solid searches) when there is a usable one. */
    public boolean useGpu = true;
    /** Graphics card API: "auto" (OpenCL, then CUDA), "opencl" or "cuda". Takes effect when the app starts. */
    public String gpuApi = "auto";
    /** Look for a newer version on GitHub when the app starts. */
    public boolean checkUpdates = true;
    /** Category used for /cway add commands. */
    public String cwayCategory = "seedfinder";
    /** Last values typed into each page, so they come back next time. */
    public Map<String, Map<String, String>> pages = new HashMap<>();

    public static Settings load() {
        Settings s = Json.read(FILE, Settings.class);
        if (s == null) s = new Settings();
        if (s.seeds == null) s.seeds = new ArrayList<>();
        if (s.pages == null) s.pages = new HashMap<>();
        if (s.speed == null) s.speed = Workers.Speed.BALANCED;
        if (s.seeds.isEmpty()) s.seeds.add(new Seed("CytraSMP", "1473884015873122059", WorldType.NORMAL));
        if (s.selected < 0 || s.selected >= s.seeds.size()) s.selected = 0;
        return s;
    }

    public void save() {
        try {
            Json.write(FILE, this);
        } catch (IOException ignored) {
            // Settings are a convenience; failing to save them must not break a search.
        }
    }

    public Seed seed() {
        return seeds.get(selected);
    }
}
