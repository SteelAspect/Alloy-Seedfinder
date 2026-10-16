package dev.steelaspect.seedfinder.engine;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.structures.EndCityPieces;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The nearest structures of one kind to a point, searched outwards ring by ring, with the same placement and checks
 * as the map icons. Some kinds are narrowed down by what the game generates: the village type, the bastion type, or
 * whether an end city has a ship (elytra).
 */
public final class NearestSearch {
    public enum Target {
        VILLAGE("Villages", StructureLayers.Layer.VILLAGE, null),
        VILLAGE_PLAINS("Plains villages", StructureLayers.Layer.VILLAGE, "village_plains"),
        VILLAGE_DESERT("Desert villages", StructureLayers.Layer.VILLAGE, "village_desert"),
        VILLAGE_SAVANNA("Savanna villages", StructureLayers.Layer.VILLAGE, "village_savanna"),
        VILLAGE_SNOWY("Snowy villages", StructureLayers.Layer.VILLAGE, "village_snowy"),
        VILLAGE_TAIGA("Taiga villages", StructureLayers.Layer.VILLAGE, "village_taiga"),
        OUTPOST("Pillager outposts", StructureLayers.Layer.OUTPOST, null),
        MANSION("Woodland mansions", StructureLayers.Layer.MANSION, null),
        MONUMENT("Ocean monuments", StructureLayers.Layer.MONUMENT, null),
        SWAMP_HUT("Witch huts", StructureLayers.Layer.SWAMP_HUT, null),
        DESERT_TEMPLE("Desert temples", StructureLayers.Layer.DESERT_TEMPLE, null),
        JUNGLE_TEMPLE("Jungle temples", StructureLayers.Layer.JUNGLE_TEMPLE, null),
        IGLOO("Igloos", StructureLayers.Layer.IGLOO, null),
        ANCIENT_CITY("Ancient cities", StructureLayers.Layer.ANCIENT_CITY, null),
        TRIAL_CHAMBER("Trial chambers", StructureLayers.Layer.TRIAL_CHAMBER, null),
        STRONGHOLD("Strongholds", StructureLayers.Layer.STRONGHOLD, null),
        RUINED_PORTAL("Ruined portals", StructureLayers.Layer.RUINED_PORTAL, null),
        SHIPWRECK("Shipwrecks", StructureLayers.Layer.SHIPWRECK, null),
        FORTRESS("Nether fortresses", StructureLayers.Layer.FORTRESS, null),
        BASTION("Bastions", StructureLayers.Layer.BASTION, null),
        BASTION_TREASURE("Treasure bastions", StructureLayers.Layer.BASTION, null),
        END_CITY("End cities", StructureLayers.Layer.END_CITY, null),
        END_SHIP("End cities with a ship", StructureLayers.Layer.END_CITY, null);

        public final String label;
        public final StructureLayers.Layer layer;
        /** Only this structure id (village types). */
        final String only;

        Target(String label, StructureLayers.Layer layer, String only) {
            this.label = label;
            this.layer = layer;
            this.only = only;
        }

        public Dim dim() {
            return layer.dim;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** @param maxDistance stop searching this far from (centerX, centerZ) */
    public record Params(long seed, WorldType type, Target target, int centerX, int centerZ, int count, int maxDistance) {}

    /** elytra: where the ship's elytra item frame goes, for Verify (end cities with a ship), else null. */
    private record Hit(StructureLayers.Found found, ChunkPos chunk, long distance, String type, net.minecraft.core.BlockPos elytra) {}

    private static final Field TEMPLATE_NAME;

    static {
        try {
            TEMPLATE_NAME = TemplateStructurePiece.class.getDeclaredField("templateName");
            TEMPLATE_NAME.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    private NearestSearch() {}

    public static List<Result> run(Params p, Progress progress) {
        Target t = p.target();
        WorldGen w = WorldGen.of(p.seed(), t.dim(), p.type());
        StructurePlacement placement = w.lookup.lookupOrThrow(Registries.STRUCTURE_SET).getOrThrow(t.layer.set).value().placement();
        List<Hit> hits = Collections.synchronizedList(new ArrayList<>());
        progress.update(0, "Searching");
        if (!(placement instanceof RandomSpreadStructurePlacement spread)) {
            // Strongholds: one set of rings for the whole world.
            for (StructureLayers.Icon i : StructureLayers.find(w, t.layer, -Structures.WORLD_EDGE, -Structures.WORLD_EDGE, Structures.WORLD_EDGE, Structures.WORLD_EDGE)) {
                long d = Math.round(Math.hypot(i.x() - p.centerX(), i.z() - p.centerZ()));
                if (d <= p.maxDistance()) {
                    ChunkPos c = new ChunkPos(i.cx(), i.cz());
                    hits.add(new Hit(StructureLayers.at(w, t.layer, c), c, d, null, null));
                }
            }
        } else {
            int size = spread.spacing() * 16;
            int rx0 = Math.floorDiv(p.centerX(), size), rz0 = Math.floorDiv(p.centerZ(), size);
            int maxRing = (int) Math.min(Integer.MAX_VALUE, (long) p.maxDistance() / size + 2);
            for (int r = 0; r <= maxRing; r++) {
                progress.check();
                // Anything in this ring or further out is at least (r - 1) regions away.
                long nearest = Math.max(0, (long) (r - 1) * size);
                if (hits.size() >= p.count()) {
                    List<Hit> sorted = sorted(hits);
                    if (nearest > sorted.get(p.count() - 1).distance) break;
                }
                if (nearest > p.maxDistance()) break;
                int ring = r;
                List<int[]> cells = new ArrayList<>();
                for (int dx = -r; dx <= r; dx++)
                    for (int dz = -r; dz <= r; dz++)
                        if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) cells.add(new int[]{rx0 + dx, rz0 + dz});
                IntStream.range(0, cells.size()).parallel().forEach(k -> {
                    int[] c = cells.get(k);
                    ChunkPos chunk = spread.getPotentialStructureChunk(w.seed, c[0] * spread.spacing(), c[1] * spread.spacing());
                    if (Math.abs(chunk.getMinBlockX()) > Structures.WORLD_EDGE || Math.abs(chunk.getMinBlockZ()) > Structures.WORLD_EDGE) return;
                    StructureLayers.Found f = StructureLayers.at(w, t.layer, chunk);
                    if (f == null) return;
                    Hit h = check(t, f, chunk, p);
                    if (h != null) hits.add(h);
                });
                progress.update(Math.min(0.99, hits.size() / (double) p.count()), "Searched " + Fmt.n((long) r * size) + " blocks out · " + hits.size() + " found");
            }
        }
        List<Hit> list = sorted(hits);
        list = list.stream().filter(h -> h.distance <= p.maxDistance()).limit(p.count()).toList();
        List<Result> out = new ArrayList<>();
        for (Hit h : list) out.add(result(h, p));
        progress.update(1, out.isEmpty() ? "None found within " + Fmt.n(p.maxDistance()) + " blocks" : "Nearest is " + Fmt.n(list.get(0).distance) + " blocks away");
        return out;
    }

    private static List<Hit> sorted(List<Hit> hits) {
        List<Hit> list;
        synchronized (hits) {
            list = new ArrayList<>(hits);
        }
        list.sort((a, b) -> Long.compare(a.distance, b.distance));
        return list;
    }

    /** Applies the target's narrowing (structure id, bastion type, ship) and works out the type to show. */
    private static Hit check(Target t, StructureLayers.Found f, ChunkPos chunk, Params p) {
        if (t.layer.only != null && !f.name().equals(t.layer.only)) return null;
        if (t.only != null && !f.name().equals(t.only)) return null;
        String type = null;
        // Villages and ruined portals: the type is in the structure id (village_desert, ruined_portal_swamp...).
        String base = t.layer == StructureLayers.Layer.VILLAGE ? "village" : t.layer == StructureLayers.Layer.RUINED_PORTAL ? "ruined_portal" : null;
        if (base != null) type = f.name().length() <= base.length() + 1 ? "Standard" : Fmt.biome(f.name().substring(base.length() + 1));
        if (t.layer == StructureLayers.Layer.BASTION) {
            // The bastion's type is its first (start) piece.
            List<StructurePiece> pieces = pieces(f);
            String start = pieces.isEmpty() ? "" : pieces.get(0).toString();
            type = start.contains("treasure") ? "Treasure" : start.contains("hoglin_stable") ? "Hoglin stables"
                    : start.contains("bridge") ? "Bridge" : start.contains("units") ? "Housing units" : "?";
            if (t == Target.BASTION_TREASURE && !type.equals("Treasure")) return null;
        }
        net.minecraft.core.BlockPos elytra = null;
        if (t.layer == StructureLayers.Layer.END_CITY) {
            boolean ship = false;
            for (StructurePiece piece : pieces(f))
                if (piece instanceof EndCityPieces.EndCityPiece e && "ship".equals(templateName(e))) {
                    ship = true;
                    // The ship template's "Elytra" data marker is where the game hangs the item frame.
                    for (var info : e.template().filterBlocks(e.templatePosition(), e.placeSettings(), net.minecraft.world.level.block.Blocks.STRUCTURE_BLOCK))
                        if (info.nbt() != null && info.nbt().getStringOr("metadata", "").startsWith("Elytra")) elytra = info.pos();
                }
            type = ship ? "Has a ship" : "No ship";
            if (t == Target.END_SHIP && !ship) return null;
        }
        var pos = f.stub().position();
        long d = Math.round(Math.hypot((double) pos.getX() - p.centerX(), (double) pos.getZ() - p.centerZ()));
        return new Hit(f, chunk, d, type, elytra);
    }

    /**
     * The structure's pieces, built by the game. One at a time: the game's template code keeps an unsynchronised
     * cache (StructureTemplate.Palette) that two jigsaw builds at once can break.
     */
    private static List<StructurePiece> pieces(StructureLayers.Found f) {
        synchronized (StructureLayers.TEMPLATE_LOCK) {
            return f.stub().getPiecesBuilder().build().pieces();
        }
    }

    private static String templateName(TemplateStructurePiece piece) {
        try {
            return (String) TEMPLATE_NAME.get(piece);
        } catch (IllegalAccessException e) {
            return "";
        }
    }

    private static Result result(Hit h, Params p) {
        Target t = p.target();
        var pos = h.found.stub().position();
        Result r = new Result();
        r.kind = Result.Kind.STRUCTURE;
        r.dim = t.dim();
        r.name = Fmt.biome(h.found.name());
        r.structure = "minecraft:" + h.found.name();
        r.x = pos.getX();
        // A stronghold's spot is its chunk corner at y 0 (the rooms are placed later), so keep the player's height.
        r.y = t.layer == StructureLayers.Layer.STRONGHOLD ? null : pos.getY() + 1;
        r.z = pos.getZ();
        r.minX = r.x - 8;
        r.maxX = r.x + 8;
        r.minZ = r.z - 8;
        r.maxZ = r.z + 8;
        r.value = -h.distance;
        r.size = Fmt.n(h.distance);
        if (h.type != null) r.details.put("Type", h.type);
        r.details.put("Direction", direction(pos.getX() - p.centerX(), pos.getZ() - p.centerZ()));
        r.details.put("Chunk", h.chunk.x + ", " + h.chunk.z);
        if (t.dim() == Dim.OVERWORLD) r.details.put("Nether", Math.floorDiv(r.x, 8) + ", " + Math.floorDiv(r.z, 8));
        if (t.dim() == Dim.NETHER) r.details.put("Overworld", r.x * 8 + ", " + r.z * 8);
        r.ref = h.elytra == null ? new int[]{h.chunk.getMinBlockX(), h.chunk.getMinBlockZ()}
                : new int[]{h.chunk.getMinBlockX(), h.chunk.getMinBlockZ(), h.elytra.getX(), h.elytra.getY(), h.elytra.getZ()};
        if (h.elytra != null) r.details.put("Elytra", h.elytra.getX() + ", " + h.elytra.getY() + ", " + h.elytra.getZ());
        return r;
    }

    /** Compass direction (north is -z). */
    private static String direction(long dx, long dz) {
        if (dx == 0 && dz == 0) return "-";
        String[] names = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"};
        double a = Math.toDegrees(Math.atan2(dz, dx));
        return names[(int) Math.floorMod(Math.round(a / 45), 8)];
    }
}
