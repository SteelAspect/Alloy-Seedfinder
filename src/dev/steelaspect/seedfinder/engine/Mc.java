package dev.steelaspect.seedfinder.engine;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads Minecraft 1.21.11's world generation code once per process, without a game or server running.
 */
public final class Mc {
    /** The real stdout, captured before Bootstrap wraps System.out with the game logger. */
    public static final PrintStream OUT = System.out;
    /** The real stderr, for the same reason. */
    public static final PrintStream ERR = System.err;

    private static HolderLookup.Provider lookup;

    private Mc() {}

    /** The game's built-in registries (biomes, structures, noise settings...) with biome tags bound. */
    public static synchronized HolderLookup.Provider lookup() {
        if (lookup == null) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            HolderLookup.Provider l = VanillaRegistries.createLookup();
            bindBiomeTags(l);
            lookup = l;
        }
        return lookup;
    }

    /** All biome ids of a dimension's biome list, e.g. "badlands", in registry order. */
    public static List<String> biomeNames() {
        List<String> names = new ArrayList<>();
        lookup().lookupOrThrow(Registries.BIOME).listElementIds().forEach(k -> names.add(k.identifier().getPath()));
        return names;
    }

    /**
     * The code-built registries have no tags (those come from data files), but structure biome checks need them.
     * Reads data/minecraft/tags/worldgen/biome from the game jar and binds them to the biome holders.
     */
    private static void bindBiomeTags(HolderLookup.Provider lookup) {
        String prefix = "data/minecraft/tags/worldgen/biome/";
        Map<String, List<String>> raw = new HashMap<>();
        try (ZipFile jar = new ZipFile(new File(Biome.class.getProtectionDomain().getCodeSource().getLocation().toURI()))) {
            for (ZipEntry e : Collections.list(jar.entries())) {
                if (!e.getName().startsWith(prefix) || !e.getName().endsWith(".json")) continue;
                String tag = "minecraft:" + e.getName().substring(prefix.length(), e.getName().length() - 5);
                try (Reader r = new InputStreamReader(jar.getInputStream(e), StandardCharsets.UTF_8)) {
                    List<String> values = new ArrayList<>();
                    for (JsonElement v : JsonParser.parseReader(r).getAsJsonObject().getAsJsonArray("values"))
                        values.add(v.isJsonObject() ? v.getAsJsonObject().get("id").getAsString() : v.getAsString());
                    raw.put(tag, values);
                }
            }
            Map<String, Set<String>> resolved = new HashMap<>();
            for (String tag : raw.keySet()) resolveTag(tag, raw, resolved, new HashSet<>());
            Method bind = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
            bind.setAccessible(true);
            for (Holder.Reference<Biome> biome : lookup.lookupOrThrow(Registries.BIOME).listElements().toList()) {
                String id = biome.key().identifier().toString();
                List<TagKey<Biome>> tags = new ArrayList<>();
                resolved.forEach((tag, members) -> {
                    if (members.contains(id)) tags.add(TagKey.create(Registries.BIOME, Identifier.parse(tag)));
                });
                bind.invoke(biome, tags);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't load biome tags from the Minecraft jar", e);
        }
    }

    private static Set<String> resolveTag(String tag, Map<String, List<String>> raw, Map<String, Set<String>> done, Set<String> visiting) {
        Set<String> have = done.get(tag);
        if (have != null) return have;
        Set<String> out = new HashSet<>();
        if (visiting.add(tag)) {
            for (String v : raw.getOrDefault(tag, List.of())) {
                if (v.startsWith("#")) out.addAll(resolveTag(v.substring(1), raw, done, visiting));
                else out.add(v);
            }
        }
        done.put(tag, out);
        return out;
    }
}
