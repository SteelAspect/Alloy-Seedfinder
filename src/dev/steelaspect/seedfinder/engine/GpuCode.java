package dev.steelaspect.seedfinder.engine;

import net.minecraft.core.Holder;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes Minecraft's biome generation as an OpenCL program, from the game's own objects for one world: the climate
 * sampler's six density functions, the preliminary surface level, and the biome parameter tree. Every node becomes
 * the same arithmetic in the same order as the game's Java (read from its code), so the results are the same bit for
 * bit; GpuWorld checks that against the game itself before using it.
 *
 * <p>Java float maths is done in double and rounded once: for + - * / that gives exactly the float result (double
 * has more than twice float's precision). Contraction into fused multiply-adds is switched off. Seed-dependent data
 * (each octave's permutation table and offsets) goes in buffers, so one program serves every seed of a world type.
 */
final class GpuCode {
    /** The program source. */
    final String source;
    /** Each octave's 256-entry permutation table, one after another. */
    final byte[] perm;
    /** Each octave's xo, yo, zo. */
    final double[] offsets;
    /** Parameter tree, 14 longs per node (min/max of the 7 climate values) and 3 ints (first child, child count, leaf or -1). */
    final long[] treeRanges;
    final int[] treeLinks;
    /** The biome of each leaf, in tree order. */
    final List<Holder<Biome>> leaves = new ArrayList<>();
    /** Whether a surface level exists (overworld); otherwise biomes are read at a fixed height. */
    final boolean hasSurface;
    final ClimateTree tree;

    private final List<ImprovedNoise> octaves = new ArrayList<>();
    private final Map<NormalNoise, Integer> noises = new IdentityHashMap<>();
    private final StringBuilder noiseCode = new StringBuilder();

    GpuCode(WorldGen w) throws ReflectiveOperationException {
        Object sampler = w.random.sampler();
        DensityFunction[] climate = new DensityFunction[6];
        String[] names = {"temperature", "humidity", "continentalness", "erosion", "depth", "weirdness"};
        for (int i = 0; i < 6; i++) climate[i] = (DensityFunction) call(sampler, names[i]);
        DensityFunction surface = w.random.router().preliminarySurfaceLevel();
        hasSurface = !simple(surface).equals("Constant");

        StringBuilder body = new StringBuilder();
        if (hasSurface) {
            SplineTable t = new SplineTable("s");
            t.collect(surface);
            body.append(t.code());
            Emitter e = new Emitter(body, null, "by", t);
            body.append("double surface_level(double bx, double bz, PT P, DT D) {\n const double by = 0.0;\n");
            e.splines();
            String v = e.df(surface);
            body.append(" return ").append(v).append(";\n}\n\n");
        }
        SplineTable t = new SplineTable("c");
        for (DensityFunction f : climate) t.collect(f);
        body.append(t.code());
        Emitter e = new Emitter(body, null, "by", t);
        body.append("void climate_raw(double bx, double by, double bz, PT P, DT D, double* r) {\n");
        e.splines();
        String[] v = new String[6];
        for (int i = 0; i < 6; i++) v[i] = e.df(climate[i]);
        for (int i = 0; i < 6; i++) body.append(" r[").append(i).append("] = ").append(v[i]).append(";\n");
        body.append("}\n\nvoid climate(double bx, double by, double bz, PT P, DT D, long* t) {\n double r[6];\n climate_raw(bx, by, bz, P, D, r);\n")
                .append(" for (int i = 0; i < 6; i++) t[i] = quantize(r[i]);\n t[6] = 0;\n}\n\n");

        // The biome parameter tree, flattened (ClimateTree).
        tree = w.tree();
        treeRanges = tree.ranges;
        treeLinks = tree.links;
        leaves.addAll(tree.leaves);

        perm = new byte[octaves.size() * 256];
        offsets = new double[octaves.size() * 3];
        for (int i = 0; i < octaves.size(); i++) {
            ImprovedNoise o = octaves.get(i);
            System.arraycopy((byte[]) field(o, "p"), 0, perm, i * 256, 256);
            offsets[i * 3] = o.xo;
            offsets[i * 3 + 1] = o.yo;
            offsets[i * 3 + 2] = o.zo;
        }
        source = HEADER + noiseCode + body + biomeAt() + kernel();
    }

    // ------------------------------------------------------------------ density functions

    /** Emits density functions into one C scope; a child scope (find_top_surface's loop) has its own y. */
    private final class Emitter {
        private final StringBuilder out;
        private final Emitter parent;
        private final String y;
        private final Map<Object, String> memo = new IdentityHashMap<>();
        private final Map<Object, Boolean> yDependent;
        private final SplineTable table;

        Emitter(StringBuilder out, Emitter parent, String y, SplineTable table) {
            this.out = out;
            this.parent = parent;
            this.y = y;
            this.table = table;
            this.yDependent = parent == null ? new IdentityHashMap<>() : parent.yDependent;
        }

        /** All the function's splines at once (their coordinates don't depend on y): sv_<table>[] holds each value. */
        void splines() throws ReflectiveOperationException {
            if (table.multipoints.isEmpty()) return;
            String[] c = new String[table.coords.size()];
            for (int i = 0; i < c.length; i++) {
                if (dependsOnY(table.coords.get(i))) throw new UnsupportedOperationException("a spline coordinate that depends on y");
                c[i] = df(table.coords.get(i));
            }
            out.append(" float cv_").append(table.name).append('[').append(c.length).append("];\n");
            for (int i = 0; i < c.length; i++) out.append(" cv_").append(table.name).append('[').append(i).append("] = tofloat(").append(c[i]).append(");\n");
            out.append(" float sv_").append(table.name).append('[').append(table.multipoints.size()).append("];\n splines_").append(table.name)
                    .append("(cv_").append(table.name).append(", sv_").append(table.name).append(");\n");
        }

        private String var(String type, String expr) {
            String v = "v" + counter++;
            out.append(" ").append(type).append(' ').append(v).append(" = ").append(expr).append(";\n");
            return v;
        }

        /** The C variable (or literal) holding the function's value at (bx, y, bz). */
        String df(DensityFunction f) throws ReflectiveOperationException {
            String type = simple(f);
            if (type.equals("Marker")) return df((DensityFunction) get(f, "wrapped"));
            if (type.equals("HolderHolder")) return df((DensityFunction) ((Holder<?>) get(f, "function")).value());
            // A function that doesn't depend on y has the same value in a child scope: use the parent's.
            if (parent != null && !dependsOnY(f)) return parent.df(f);
            String have = memo.get(f);
            if (have != null) return have;
            String r = switch (type) {
                case "Constant" -> lit((double) get(f, "value"));
                case "BlendAlpha" -> "1.0";
                case "BlendOffset" -> "0.0";
                case "YClampedGradient" -> var("double", "jclampedmap(" + y + ", " + lit((int) get(f, "fromY")) + ", " + lit((int) get(f, "toY"))
                        + ", " + lit((double) get(f, "fromValue")) + ", " + lit((double) get(f, "toValue")) + ")");
                case "ShiftA" -> shift(f, "bx", "0.0", "bz");
                case "ShiftB" -> shift(f, "bz", "bx", "0.0");
                case "Shift" -> shift(f, "bx", y, "bz");
                case "ShiftedNoise" -> {
                    double xz = (double) get(f, "xzScale"), ys = (double) get(f, "yScale");
                    String sx = df((DensityFunction) get(f, "shiftX")), sy = df((DensityFunction) get(f, "shiftY")), sz = df((DensityFunction) get(f, "shiftZ"));
                    yield var("double", noise(get(f, "noise"), "bx * " + lit(xz) + " + " + sx, y + " * " + lit(ys) + " + " + sy, "bz * " + lit(xz) + " + " + sz));
                }
                case "Noise" -> {
                    double xz = (double) get(f, "xzScale"), ys = (double) get(f, "yScale");
                    yield var("double", noise(get(f, "noise"), "bx * " + lit(xz), y + " * " + lit(ys), "bz * " + lit(xz)));
                }
                case "Ap2" -> {
                    String a = df((DensityFunction) get(f, "argument1")), b = df((DensityFunction) get(f, "argument2"));
                    DensityFunction arg2 = (DensityFunction) get(f, "argument2");
                    yield var("double", switch (get(f, "type").toString()) {
                        case "ADD" -> a + " + " + b;
                        case "MUL" -> "(" + a + " == 0.0 ? 0.0 : " + a + " * " + b + ")";
                        case "MIN" -> "(" + a + " < " + lit(arg2.minValue()) + " ? " + a + " : jmin(" + a + ", " + b + "))";
                        case "MAX" -> "(" + a + " > " + lit(arg2.maxValue()) + " ? " + a + " : jmax(" + a + ", " + b + "))";
                        default -> throw new IllegalStateException("Ap2 " + get(f, "type"));
                    });
                }
                case "MulOrAdd" -> {
                    String in = df((DensityFunction) get(f, "input"));
                    String arg = lit((double) get(f, "argument"));
                    yield var("double", get(f, "specificType").toString().equals("MUL") ? in + " * " + arg : in + " + " + arg);
                }
                case "Mapped" -> {
                    String d = df((DensityFunction) get(f, "input"));
                    yield var("double", switch (get(f, "type").toString()) {
                        case "ABS" -> "fabs(" + d + ")";
                        case "SQUARE" -> d + " * " + d;
                        case "CUBE" -> d + " * " + d + " * " + d;
                        case "HALF_NEGATIVE" -> "(" + d + " > 0.0 ? " + d + " : " + d + " * 0.5)";
                        case "QUARTER_NEGATIVE" -> "(" + d + " > 0.0 ? " + d + " : " + d + " * 0.25)";
                        case "INVERT" -> "jdiv(1.0, " + d + ")";
                        case "SQUEEZE" -> "squeeze(" + d + ")";
                        default -> throw new IllegalStateException("Mapped " + get(f, "type"));
                    });
                }
                case "Clamp" -> var("double", "jclamp(" + df((DensityFunction) get(f, "input")) + ", " + lit((double) get(f, "minValue")) + ", "
                        + lit((double) get(f, "maxValue")) + ")");
                case "Spline" -> {
                    int ref = root().table.ref((CubicSpline<?, ?>) get(f, "spline"));
                    yield var("double", "todouble(" + (ref >= 0 ? "sv_" + root().table.name + "[" + ref + "]" : flit(root().table.consts.get(-ref - 1))) + ")");
                }
                case "FindTopSurface" -> topSurface(f);
                default -> throw new UnsupportedOperationException("density function " + type);
            };
            memo.put(f, r);
            return r;
        }

        private String shift(DensityFunction f, String x, String y0, String z) throws ReflectiveOperationException {
            // ShiftNoise.compute: offsetNoise.getValue(x * 0.25, y * 0.25, z * 0.25) * 4.0
            return var("double", noise(get(f, "offsetNoise"), x + " * 0.25", y0 + " * 0.25", z + " * 0.25") + " * 4.0");
        }

        /** DensityFunctions.FindTopSurface: the highest cell (stepping down from the upper bound) where density > 0. */
        private String topSurface(DensityFunction f) throws ReflectiveOperationException {
            DensityFunction density = (DensityFunction) get(f, "density");
            int lower = (int) get(f, "lowerBound"), cell = (int) get(f, "cellHeight");
            String ub = df((DensityFunction) get(f, "upperBound"));
            hoist(density); // whatever doesn't change with y is worked out once, before the loop
            String v = var("double", lit((double) lower));
            String j = "j" + counter++;
            out.append(" { int i0 = jfloor(jdiv(").append(ub).append(", ").append(lit((double) cell)).append(")) * ").append(cell).append(";\n");
            out.append("  if (i0 > ").append(lower).append(") for (int ").append(j).append(" = i0; ").append(j).append(" >= ").append(lower)
                    .append("; ").append(j).append(" -= ").append(cell).append(") {\n");
            Emitter inner = new Emitter(out, this, "(double)" + j, table);
            String d = inner.df(density);
            out.append("  if (").append(d).append(" > 0.0) { ").append(v).append(" = (double)").append(j).append("; break; }\n  }\n }\n");
            return v;
        }

        /** Emits the largest y-independent parts of f in this scope. */
        private void hoist(DensityFunction f) throws ReflectiveOperationException {
            if (!dependsOnY(f)) {
                df(f);
                return;
            }
            for (DensityFunction c : children(f)) hoist(c);
            if (simple(f).equals("Spline")) for (DensityFunction c : coordinates((CubicSpline<?, ?>) get(f, "spline"))) hoist(c);
        }

        boolean dependsOnY(DensityFunction f) throws ReflectiveOperationException {
            Boolean have = yDependent.get(f);
            if (have != null) return have;
            String type = simple(f);
            boolean dep = switch (type) {
                case "Constant", "BlendAlpha", "BlendOffset", "ShiftA", "ShiftB" -> false;
                case "Shift", "YClampedGradient" -> true;
                case "Noise" -> (double) get(f, "yScale") != 0.0;
                case "ShiftedNoise" -> (double) get(f, "yScale") != 0.0 || anyDepends(children(f));
                case "Spline" -> anyDepends(coordinates((CubicSpline<?, ?>) get(f, "spline")));
                case "FindTopSurface" -> dependsOnY((DensityFunction) get(f, "upperBound"));
                default -> anyDepends(children(f));
            };
            yDependent.put(f, dep);
            return dep;
        }

        private boolean anyDepends(List<DensityFunction> fs) throws ReflectiveOperationException {
            for (DensityFunction c : fs) if (dependsOnY(c)) return true;
            return false;
        }

        private Emitter root() {
            return parent == null ? this : parent.root();
        }
    }

    // ------------------------------------------------------------------ splines

    /**
     * A function's splines (CubicSpline.Multipoint) as tables, worked out by one small loop instead of code per
     * spline: children before parents. Child references: a spline's index, or -(constant index + 1).
     */
    private final class SplineTable {
        final String name;
        final List<Object> multipoints = new ArrayList<>();
        final Map<Object, Integer> index = new IdentityHashMap<>();
        final List<Float> consts = new ArrayList<>();
        final List<DensityFunction> coords = new ArrayList<>();
        private final Map<DensityFunction, Integer> coordIndex = new IdentityHashMap<>();
        private final List<int[]> heads = new ArrayList<>(); // coordinate, point count, first point
        private final List<Float> loc = new ArrayList<>(), der = new ArrayList<>();
        private final List<Integer> child = new ArrayList<>();
        private final java.util.Set<Object> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

        SplineTable(String name) {
            this.name = name;
        }

        /** Adds every spline found in this function (and in its splines' coordinates). */
        void collect(DensityFunction f) throws ReflectiveOperationException {
            if (!seen.add(f)) return;
            if (simple(f).equals("Spline")) {
                CubicSpline<?, ?> sp = (CubicSpline<?, ?>) get(f, "spline");
                ref(sp);
                for (DensityFunction c : coordinates(sp)) collect(c);
            }
            for (DensityFunction c : children(f)) collect(c);
        }

        int ref(CubicSpline<?, ?> sp) throws ReflectiveOperationException {
            if (sp.getClass().getSimpleName().equals("Constant")) {
                consts.add((float) get(sp, "value"));
                return -consts.size();
            }
            Integer have = index.get(sp);
            if (have != null) return have;
            List<?> values = (List<?>) get(sp, "values");
            int[] refs = new int[values.size()];
            for (int i = 0; i < refs.length; i++) refs[i] = ref((CubicSpline<?, ?>) values.get(i));
            DensityFunction cf = (DensityFunction) ((Holder<?>) get(get(sp, "coordinate"), "function")).value();
            Integer c = coordIndex.get(cf);
            if (c == null) {
                c = coords.size();
                coords.add(cf);
                coordIndex.put(cf, c);
            }
            float[] l = (float[]) get(sp, "locations"), d = (float[]) get(sp, "derivatives");
            heads.add(new int[]{c, l.length, loc.size()});
            for (int i = 0; i < l.length; i++) {
                loc.add(l[i]);
                der.add(d[i]);
                child.add(refs[i]);
            }
            int i = multipoints.size();
            multipoints.add(sp);
            index.put(sp, i);
            return i;
        }

        String code() {
            if (multipoints.isEmpty()) return "";
            StringBuilder c = new StringBuilder();
            c.append("__constant int SPL_").append(name).append("[] = {");
            for (int[] h : heads) c.append(h[0]).append(',').append(h[1]).append(',').append(h[2]).append(',');
            c.append("};\n__constant float LOC_").append(name).append("[] = {");
            for (float f : loc) c.append(flit(f)).append(',');
            c.append("};\n__constant float DER_").append(name).append("[] = {");
            for (float f : der) c.append(flit(f)).append(',');
            c.append("};\n__constant int CH_").append(name).append("[] = {");
            for (int r : child) c.append(r).append(',');
            c.append("};\n__constant float CON_").append(name).append("[] = {");
            for (float f : consts) c.append(flit(f)).append(',');
            if (consts.isEmpty()) c.append("0.0f");
            c.append("};\n\n");
            // CubicSpline.Multipoint.apply, in Java's float arithmetic (see fadd and friends).
            c.append("void splines_").append(name).append("(const float* cv, float* sv) {\n")
                    .append(" for (int s = 0; s < ").append(multipoints.size()).append("; s++) {\n")
                    .append("  int n = SPL_").append(name).append("[s * 3 + 1], o = SPL_").append(name).append("[s * 3 + 2];\n")
                    .append("  float f = cv[SPL_").append(name).append("[s * 3]];\n")
                    .append("  double fd = todouble(f);\n")
                    .append("  int i = n - 1;\n")
                    .append("  for (int k = 0; k < n; k++) if (fd < todouble(LOC_").append(name).append("[o + k])) { i = k - 1; break; }\n")
                    .append("  float r;\n")
                    .append("  if (i < 0 || i == n - 1) {\n")
                    .append("   int e = i < 0 ? 0 : n - 1, ce = CH_").append(name).append("[o + e];\n")
                    .append("   float v = ce >= 0 ? sv[ce] : CON_").append(name).append("[-ce - 1], h = DER_").append(name).append("[o + e];\n")
                    .append("   r = todouble(h) == 0.0 ? v : fadd(v, fmul(h, fsub(f, LOC_").append(name).append("[o + e])));\n")
                    .append("  } else {\n")
                    .append("   int ca = CH_").append(name).append("[o + i], cb = CH_").append(name).append("[o + i + 1];\n")
                    .append("   float g = LOC_").append(name).append("[o + i], h = LOC_").append(name).append("[o + i + 1], l = DER_").append(name)
                    .append("[o + i], m = DER_").append(name).append("[o + i + 1];\n")
                    .append("   float a = ca >= 0 ? sv[ca] : CON_").append(name).append("[-ca - 1], b = cb >= 0 ? sv[cb] : CON_").append(name).append("[-cb - 1];\n")
                    .append("   float hg = fsub(h, g), ba = fsub(b, a);\n")
                    .append("   float kk = fdiv(fsub(f, g), hg);\n")
                    .append("   float p = fsub(fmul(l, hg), ba);\n")
                    .append("   float q = fadd(fmul(as_float(as_int(m) ^ 0x80000000), hg), ba);\n")
                    .append("   r = fadd(flerp(kk, a, b), fmul(fmul(kk, fsub(1.0f, kk)), flerp(kk, p, q)));\n")
                    .append("  }\n  sv[s] = r;\n }\n}\n\n");
            return c.toString();
        }
    }

    // ------------------------------------------------------------------ noises

    /** A call of NoiseHolder.getValue, i.e. NormalNoise.getValue, with its own C function per noise. */
    private String noise(Object holder, String x, String y, String z) throws ReflectiveOperationException {
        NormalNoise n = (NormalNoise) get(holder, "noise");
        if (n == null) return "0.0";
        Integer id = noises.get(n);
        if (id == null) {
            id = noises.size();
            noises.put(n, id);
            String a = perlin((PerlinNoise) field(n, "first")), b = perlin((PerlinNoise) field(n, "second"));
            noiseCode.append("double nn").append(id).append("(double x, double y, double z, PT P, DT D) {\n")
                    .append(" double g = x * 1.0181268882175227, h = y * 1.0181268882175227, k = z * 1.0181268882175227;\n")
                    .append(" return (").append(a).append("(x, y, z, P, D) + ").append(b).append("(g, h, k, P, D)) * ")
                    .append(lit((double) field(n, "valueFactor"))).append(";\n}\n\n");
        }
        return "nn" + id + "(" + x + ", " + y + ", " + z + ", P, D)";
    }

    /** PerlinNoise.getValue(x, y, z), unrolled over its octaves. */
    private String perlin(PerlinNoise p) throws ReflectiveOperationException {
        String name = "pn" + counter++;
        ImprovedNoise[] levels = (ImprovedNoise[]) field(p, "noiseLevels");
        it.unimi.dsi.fastutil.doubles.DoubleList amplitudes = (it.unimi.dsi.fastutil.doubles.DoubleList) field(p, "amplitudes");
        StringBuilder c = new StringBuilder("double ").append(name).append("(double x, double y, double z, PT P, DT D) {\n")
                .append(" double i = 0.0, j = ").append(lit((double) field(p, "lowestFreqInputFactor"))).append(", k = ")
                .append(lit((double) field(p, "lowestFreqValueFactor"))).append(";\n");
        for (int l = 0; l < levels.length; l++) {
            if (levels[l] != null) {
                int o = octaves.size();
                octaves.add(levels[l]);
                c.append(" i += ").append(lit(amplitudes.getDouble(l))).append(" * octave(P + ").append(o * 256).append(", D[")
                        .append(o * 3).append("], D[").append(o * 3 + 1).append("], D[").append(o * 3 + 2)
                        .append("], wrapc(x * j), wrapc(y * j), wrapc(z * j)) * k;\n");
            }
            c.append(" j *= 2.0; k *= 0.5;\n"); // k /= 2.0, exactly
        }
        c.append(" return i;\n}\n\n");
        noiseCode.append(c);
        return name;
    }

    // ------------------------------------------------------------------ helpers

    private int counter;

    private static String simple(Object o) {
        return o.getClass().getSimpleName();
    }

    private static List<DensityFunction> children(DensityFunction f) throws ReflectiveOperationException {
        List<DensityFunction> out = new ArrayList<>();
        if (!f.getClass().isRecord()) return out;
        for (RecordComponent c : f.getClass().getRecordComponents()) {
            c.getAccessor().setAccessible(true);
            Object v = c.getAccessor().invoke(f);
            if (v instanceof DensityFunction d) out.add(d);
            if (v instanceof Holder<?> h && h.value() instanceof DensityFunction d) out.add(d);
        }
        return out;
    }

    private static List<DensityFunction> coordinates(CubicSpline<?, ?> s) throws ReflectiveOperationException {
        List<DensityFunction> out = new ArrayList<>();
        if (s.getClass().getSimpleName().equals("Constant")) return out;
        out.add((DensityFunction) ((Holder<?>) get(get(s, "coordinate"), "function")).value());
        for (Object v : (List<?>) get(s, "values")) out.addAll(coordinates((CubicSpline<?, ?>) v));
        return out;
    }

    private static final Map<String, Method> ACCESSORS = new HashMap<>();

    /** A record component by name. */
    private static Object get(Object record, String name) throws ReflectiveOperationException {
        String key = record.getClass().getName() + "#" + name;
        Method m;
        synchronized (ACCESSORS) {
            m = ACCESSORS.get(key);
            if (m == null) {
                m = record.getClass().getDeclaredMethod(name);
                m.setAccessible(true);
                ACCESSORS.put(key, m);
            }
        }
        return m.invoke(record);
    }

    private static Object call(Object o, String name) throws ReflectiveOperationException {
        Method m = o.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(o);
    }

    private static Object field(Object o, String name) throws ReflectiveOperationException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                // look in the superclass
            }
        }
        throw new NoSuchFieldException(o.getClass().getName() + "." + name);
    }

    /** An exact double literal (hexadecimal), in brackets so a minus sign never meets another. */
    static String lit(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalStateException("non-finite constant " + d);
        return "(" + Double.toHexString(d) + ")";
    }

    static String flit(float f) {
        if (Float.isNaN(f) || Float.isInfinite(f)) throw new IllegalStateException("non-finite constant " + f);
        return "(" + Float.toHexString(f) + "f)";
    }

    private String kernel() {
        String surface = hasSurface ? """
                int surface_biome(int x, int z, PT P, DT D, TR T, TL L) {
                 double s = surface_level((double)x, (double)z, P, D);
                 int y = (int)s;
                 if (y < 62) y = 62;
                 return biome_at(qfloor(x), qfloor(y), qfloor(z), P, D, T, L);
                }

                """ : "";
        return surface + """
                __kernel void biomes(PT P, DT D, TR T, TL L, __global const uchar* cave, int mode, int x0, int z0, int step, int nx, int qy, int n,
                                     __global int* out) {
                 int id = get_global_id(0);
                 if (id >= n) return;
                 int x = x0 + (id % nx) * step, z = z0 + (id / nx) * step;
                 int leaf;
                """ + (hasSurface ? """
                 if (mode == 1) leaf = surface_biome(x, z, P, D, T, L);
                 else {
                  leaf = biome_at(qfloor(x), qy, qfloor(z), P, D, T, L);
                  if (mode == 2 && leaf >= 0 && cave[leaf]) leaf = surface_biome(x, z, P, D, T, L);
                 }
                """ : """
                 leaf = biome_at(qfloor(x), qy, qfloor(z), P, D, T, L);
                """) + """
                 out[id] = leaf;
                }

                __kernel void search(TR T, TL L, __global const long* in, int n, __global int* out) {
                 int id = get_global_id(0);
                 if (id >= n) return;
                 long t[7];
                 for (int k = 0; k < 7; k++) t[k] = in[id * 7 + k];
                 int tie;
                 int leaf = tree_search(T, L, t, &tie);
                 out[id] = tie ? -1 : leaf;
                }

                __kernel void raw(PT P, DT D, __global const int* xyz, int n, __global double* out) {
                 int id = get_global_id(0);
                 if (id >= n) return;
                 double r[6];
                 climate_raw((double)(xyz[id * 3] * 4), (double)(xyz[id * 3 + 1] * 4), (double)(xyz[id * 3 + 2] * 4), P, D, r);
                 for (int k = 0; k < 6; k++) out[id * 6 + k] = r[k];
                }

                __kernel void targets(PT P, DT D, __global const int* xyz, int n, __global long* out) {
                 int id = get_global_id(0);
                 if (id >= n) return;
                 long t[7];
                 climate((double)(xyz[id * 3] * 4), (double)(xyz[id * 3 + 1] * 4), (double)(xyz[id * 3 + 2] * 4), P, D, t);
                 for (int k = 0; k < 7; k++) out[id * 7 + k] = t[k];
                }
                """ + (hasSurface ? """

                __kernel void surfaces(PT P, DT D, __global const int* xz, int n, __global double* out) {
                 int id = get_global_id(0);
                 if (id >= n) return;
                 out[id] = surface_level((double)xz[id * 2], (double)xz[id * 2 + 1], P, D);
                }
                """ : "");
    }

    private static final String HEADER = """
            #pragma OPENCL EXTENSION cl_khr_fp64 : enable
            #pragma OPENCL FP_CONTRACT OFF
            typedef __global const uchar* PT;
            typedef __global const double* DT;
            typedef __global const long* TR;
            typedef __global const int* TL;

            /* Exact replacements for what this kind of driver gets wrong: double division (not correctly rounded),
               subnormal floats (flushed to zero) and float rounding (skipped before a conversion to long). Integer
               bit work only, which no compiler can "optimise". */
/* IEEE double division, correctly rounded to nearest even, from the bits (the hardware's isn't exact). */
double jdiv(double a, double b) {
 long xa = as_long(a), xb = as_long(b);
 long sign = (xa ^ xb) & (long)0x8000000000000000UL;
 int ea = (int)((xa >> 52) & 0x7ff), eb = (int)((xb >> 52) & 0x7ff);
 long fa = xa & 0xfffffffffffffL, fb = xb & 0xfffffffffffffL;
 if ((ea == 0x7ff && fa != 0) || (eb == 0x7ff && fb != 0)) return as_double(0x7ff8000000000000L);
 int za = ea == 0 && fa == 0, zb = eb == 0 && fb == 0;
 if (ea == 0x7ff) return eb == 0x7ff ? as_double(0x7ff8000000000000L) : as_double(sign | 0x7ff0000000000000L);
 if (eb == 0x7ff) return as_double(sign);
 if (zb) return za ? as_double(0x7ff8000000000000L) : as_double(sign | 0x7ff0000000000000L);
 if (za) return as_double(sign);
 if (ea == 0) { int s = (int)clz(fa) - 11; fa <<= s; ea = 1 - s; } else fa |= 0x10000000000000L;
 if (eb == 0) { int s = (int)clz(fb) - 11; fb <<= s; eb = 1 - s; } else fb |= 0x10000000000000L;
 int e = ea - eb + 1023;
 if (fa < fb) { fa <<= 1; e -= 1; }
 long q = 0, r = fa;
 for (int i = 0; i < 54; i++) { q <<= 1; if (r >= fb) { r -= fb; q |= 1; } r <<= 1; }
 int sticky = r != 0;
 if (e >= 1) {
  long m = q >> 1;
  if ((q & 1) != 0 && (sticky || (m & 1) != 0)) m += 1;
  if (m == 0x20000000000000L) { m >>= 1; e += 1; }
  if (e >= 0x7ff) return as_double(sign | 0x7ff0000000000000L);
  return as_double(sign | ((long)e << 52) | (m & 0xfffffffffffffL));
 }
 int sh = 2 - e;
 if (sh > 60) return as_double(sign);
 long keep = q >> sh, rem = q & ((1L << sh) - 1), hf = 1L << (sh - 1);
 if (rem > hf || (rem == hf && (sticky || (keep & 1) != 0))) keep += 1;
 return as_double(sign | keep);
}

/* (float) of a double, rounded to nearest even, subnormals kept (the hardware flushes them to zero). */
float tofloat(double d) {
 long x = as_long(d);
 int sign = (int)((x >> 32) & 0x80000000L);
 int e = (int)((x >> 52) & 0x7ff);
 long m = x & 0xfffffffffffffL;
 if (e == 0x7ff) return as_float(m != 0 ? 0x7fc00000 : (sign | 0x7f800000));
 if (e == 0) return as_float(sign);
 long mant = m | 0x10000000000000L;
 int fe = e - 896;
 if (fe >= 0xff) return as_float(sign | 0x7f800000);
 int sh = fe >= 1 ? 29 : 29 + (1 - fe);
 if (sh > 60) return as_float(sign);
 long keep = mant >> sh, rem = mant & ((1L << sh) - 1), hf = 1L << (sh - 1);
 if (rem > hf || (rem == hf && (keep & 1) != 0)) keep += 1;
 if (fe >= 1) {
  if (keep == 0x1000000L) { keep >>= 1; fe += 1; }
  if (fe >= 0xff) return as_float(sign | 0x7f800000);
  return as_float(sign | (fe << 23) | (int)(keep & 0x7fffff));
 }
 return as_float(sign | (int)keep);
}

/* (double) of a float, exactly, subnormals included. */
double todouble(float f) {
 int x = as_int(f);
 int e = (x >> 23) & 0xff, m = x & 0x7fffff;
 if (e != 0) return (double)f;
 double v = (double)m * 0x1.0p-149;
 return (x & 0x80000000) != 0 ? -v : v;
}

            /* Mth.floor / Mth.lfloor */
            int jfloor(double d) { int i = (int)d; return d < (double)i ? i - 1 : i; }
            long jlfloor(double d) { long l = (long)d; return d < (double)l ? l - 1 : l; }
            /* QuartPos.fromBlock: x >> 2 (arithmetic) */
            int qfloor(int v) { return v >= 0 ? v >> 2 : ~((~v) >> 2); }
            /* Java's Math.min / Math.max (NaN wins, -0.0 is below 0.0) */
            double jmin(double a, double b) {
             if (a != a) return a;
             if (a == 0.0 && b == 0.0 && as_long(b) == as_long(-0.0)) return b;
             return a <= b ? a : b;
            }
            double jmax(double a, double b) {
             if (a != a) return a;
             if (a == 0.0 && b == 0.0 && as_long(a) == as_long(-0.0)) return b;
             return a >= b ? a : b;
            }
            double jclamp(double d, double lo, double hi) { return d < lo ? lo : jmin(d, hi); }
            double jlerp(double t, double a, double b) { return a + t * (b - a); }
            double jclampedmap(double d, double e, double f, double g, double h) {
             double t = jdiv(d - e, f - e);
             if (t < 0.0) return g;
             return t > 1.0 ? h : jlerp(t, g, h);
            }
            /* e / 2.0 is exactly e * 0.5 */
            double squeeze(double d) { double e = jclamp(d, -1.0, 1.0); return e * 0.5 - jdiv(e * e * e, 24.0); }
            /* d / 3.3554432E7 (2^25) is exactly d * 2^-25 */
            double wrapc(double d) { return d - (double)jlfloor(d * 0x1.0p-25 + 0.5) * 3.3554432E7; }
            /* Java float arithmetic: worked out in double, rounded once (exact for + - * /). */
            float fadd(float a, float b) { return tofloat(todouble(a) + todouble(b)); }
            float fsub(float a, float b) { return tofloat(todouble(a) - todouble(b)); }
            float fmul(float a, float b) { return tofloat(todouble(a) * todouble(b)); }
            float fdiv(float a, float b) { return tofloat(jdiv(todouble(a), todouble(b))); }
            float flerp(float t, float a, float b) { return fadd(a, fmul(t, fsub(b, a))); }
            /* Climate.quantizeCoord: (long)(f * 10000.0F) */
            long quantize(double v) { return (long)todouble(fmul(tofloat(v), 10000.0f)); }

            /* SimplexNoise.GRADIENT and ImprovedNoise */
            __constant int GR[16][3] = {{1,1,0},{-1,1,0},{1,-1,0},{-1,-1,0},{1,0,1},{-1,0,1},{1,0,-1},{-1,0,-1},
                                        {0,1,1},{0,-1,1},{0,1,-1},{0,-1,-1},{1,1,0},{0,-1,1},{-1,1,0},{0,-1,-1}};
            double gdot(int g, double x, double y, double z) {
             g &= 15;
             return (double)GR[g][0] * x + (double)GR[g][1] * y + (double)GR[g][2] * z;
            }
            double smooth(double d) { return d * d * d * (d * (d * 6.0 - 15.0) + 10.0); }
            double octave(PT p, double xo, double yo, double zo, double x, double y, double z) {
             double i = x + xo, j = y + yo, k = z + zo;
             int l = jfloor(i), m = jfloor(j), n = jfloor(k);
             double d = i - (double)l, g = j - (double)m, f = k - (double)n;
             double e = g - 0.0;
             int a = p[l & 255], b = p[(l + 1) & 255];
             int c0 = p[(a + m) & 255], c1 = p[(a + m + 1) & 255], c2 = p[(b + m) & 255], c3 = p[(b + m + 1) & 255];
             double h = gdot(p[(c0 + n) & 255], d, e, f);
             double r = gdot(p[(c2 + n) & 255], d - 1.0, e, f);
             double s = gdot(p[(c1 + n) & 255], d, e - 1.0, f);
             double t = gdot(p[(c3 + n) & 255], d - 1.0, e - 1.0, f);
             double u = gdot(p[(c0 + n + 1) & 255], d, e, f - 1.0);
             double v = gdot(p[(c2 + n + 1) & 255], d - 1.0, e, f - 1.0);
             double w = gdot(p[(c1 + n + 1) & 255], d, e - 1.0, f - 1.0);
             double xx = gdot(p[(c3 + n + 1) & 255], d - 1.0, e - 1.0, f - 1.0);
             double sx = smooth(d), sy = smooth(g), sz = smooth(f);
             return jlerp(sz, jlerp(sy, jlerp(sx, h, r), jlerp(sx, s, t)), jlerp(sy, jlerp(sx, u, v), jlerp(sx, w, xx)));
            }

            /* Climate.RTree's search for the closest leaf. Where several are exactly as close (*tie set), the game's answer
               depends on its previous lookup: those cells are worked out on the CPU (ClimateTree.inGame). */
            long param_distance(long v, long lo, long hi) { long m = v - hi, n = lo - v; return m > 0 ? m : (n > 0 ? n : 0); }
            long node_distance(TR T, int node, const long* t) {
             long s = 0;
             for (int k = 0; k < 7; k++) { long d = param_distance(t[k], T[node * 14 + k * 2], T[node * 14 + k * 2 + 1]); s += d * d; }
             return s;
            }
            int tree_search(TR T, TL L, const long* t, int* tie) {
             *tie = 0;
             if (L[2] >= 0) return L[2];
             long best = 0x7fffffffffffffffL;
             int leaf = -1, tied = 0;
             int sn[32], sc[32], sp = 1;
             sn[0] = 0; sc[0] = 0;
             while (sp > 0) {
              int node = sn[sp - 1], c = sc[sp - 1];
              if (c >= L[node * 3 + 1]) { sp--; continue; }
              sc[sp - 1] = c + 1;
              int child = L[node * 3] + c;
              long m = node_distance(T, child, t);
              if (m > best) continue;
              if (L[child * 3 + 2] >= 0) {
               if (m < best) { best = m; leaf = L[child * 3 + 2]; tied = 0; } else tied = 1;
              } else { sn[sp] = child; sc[sp] = 0; sp++; }
             }
             *tie = tied;
             return leaf;
            }

            """;

    /** biome_at needs climate(), which is generated: added after the body. */
    static String biomeAt() {
        return """
                /* The leaf at a cell, or -1 for an exact tie (left to the CPU). */
                int biome_at(int qx, int qy, int qz, PT P, DT D, TR T, TL L) {
                 long t[7];
                 climate((double)(qx * 4), (double)(qy * 4), (double)(qz * 4), P, D, t);
                 int tie;
                 int leaf = tree_search(T, L, t, &tie);
                 return tie ? -1 : leaf;
                }

                """;
    }
}
