package dev.steelaspect.seedfinder.engine;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressPieces;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a nether fortress with Minecraft's own piece code, safely on any number of threads at once.
 *
 * <p>Minecraft keeps the "how many of each piece so far" counters in shared static objects
 * (NetherFortressPieces.PieceWeight), so two fortresses built at the same time change each other's counts. The game's
 * start piece copies those shared objects into its own two lists when it's created, and every later step only uses
 * those lists. So: create the start piece as the game does, swap the lists' contents for fresh private copies, then
 * run the game's piece loop (the same steps as NetherFortressStructure.generatePieces, with the same random numbers).
 * The pieces themselves are always made by Minecraft's code.
 */
public final class FortressBuilder {
    private static final Field BRIDGE, CASTLE, CLASS, WEIGHT, MAX, IN_ROW;
    private static final Constructor<?> WEIGHT_NEW;
    /** False if this Minecraft version's fortress code isn't laid out as expected; callers then use the serial path. */
    public static final boolean AVAILABLE;

    static {
        Field b = null, c = null, cl = null, w = null, m = null, r = null;
        Constructor<?> k = null;
        boolean ok;
        try {
            b = NetherFortressPieces.StartPiece.class.getDeclaredField("availableBridgePieces");
            c = NetherFortressPieces.StartPiece.class.getDeclaredField("availableCastlePieces");
            Class<?> pw = Class.forName(NetherFortressPieces.class.getName() + "$PieceWeight", false, NetherFortressPieces.class.getClassLoader());
            cl = pw.getDeclaredField("pieceClass");
            w = pw.getDeclaredField("weight");
            m = pw.getDeclaredField("maxPlaceCount");
            r = pw.getDeclaredField("allowInRow");
            k = pw.getDeclaredConstructor(Class.class, int.class, int.class, boolean.class);
            for (java.lang.reflect.AccessibleObject a : new java.lang.reflect.AccessibleObject[]{b, c, cl, w, m, r, k}) a.setAccessible(true);
            ok = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            ok = false;
        }
        BRIDGE = b;
        CASTLE = c;
        CLASS = cl;
        WEIGHT = w;
        MAX = m;
        IN_ROW = r;
        WEIGHT_NEW = k;
        AVAILABLE = ok;
    }

    private FortressBuilder() {}

    /** The fortress pieces that generate in this chunk (the caller has already decided it's a fortress). */
    public static List<StructurePiece> build(long seed, ChunkPos chunk) {
        // Same random as the game's Structure.GenerationContext for this chunk.
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureSeed(seed, chunk.x, chunk.z);
        NetherFortressPieces.StartPiece start = new NetherFortressPieces.StartPiece(random, chunk.getBlockX(2), chunk.getBlockZ(2));
        privatize(start, BRIDGE);
        privatize(start, CASTLE);
        StructurePiecesBuilder builder = new StructurePiecesBuilder();
        builder.addPiece(start);
        start.addChildren(start, builder, random);
        List<StructurePiece> pending = start.pendingChildren;
        while (!pending.isEmpty()) {
            int i = random.nextInt(pending.size());
            pending.remove(i).addChildren(start, builder, random);
        }
        builder.moveInsideHeights(random, 48, 70);
        PiecesContainer pieces = builder.build();
        return pieces.pieces();
    }

    /** Replaces the shared counter objects in one of the start piece's lists with fresh copies (count 0). */
    @SuppressWarnings("unchecked")
    private static void privatize(NetherFortressPieces.StartPiece start, Field listField) {
        try {
            List<Object> list = (List<Object>) listField.get(start);
            List<Object> copies = new ArrayList<>(list.size());
            for (Object w : list)
                copies.add(WEIGHT_NEW.newInstance(CLASS.get(w), WEIGHT.getInt(w), MAX.getInt(w), IN_ROW.getBoolean(w)));
            list.clear();
            list.addAll(copies);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Couldn't prepare the fortress piece counters", e);
        }
    }
}
