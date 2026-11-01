package dev.steelaspect.seedfinder.gpu.ffm;

import dev.steelaspect.seedfinder.gpu.GpuException;
import dev.steelaspect.seedfinder.gpu.OpenCl;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * The same graphics card jobs on NVIDIA's CUDA instead of OpenCL, for NVIDIA cards whose OpenCL doesn't work. The
 * app's kernels are OpenCL C; {@link #translate} turns them into CUDA C, NVIDIA's NVRTC compiles that to PTX on this
 * computer (NVRTC comes with the free CUDA Toolkit) and the driver (nvcuda.dll / libcuda.so, part of every NVIDIA
 * driver) runs it. Every search still checks the card against the CPU before using it.
 *
 * <p>Built twice from this file, like {@link OpenClFfm}. All calls are synchronised and make the card's context
 * current on the calling thread first.
 */
public final class CudaFfm implements OpenCl {
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
    private static final AddressLayout PTR = ValueLayout.ADDRESS;
    private static final int ATTR_SM_COUNT = 16, ATTR_CC_MAJOR = 75, ATTR_CC_MINOR = 76;
    /** Compute capabilities NVRTC may be asked for, newest first (PTX for an older one runs on newer cards). */
    private static final int[] ARCHS = {120, 100, 90, 89, 87, 86, 80, 75, 72, 70, 61, 60, 53, 52, 50};

    private final MethodHandle ctxSetCurrent, moduleLoadData, moduleGetFunction, moduleUnload, memAlloc, memFree, htod, dtoh,
            launch, sync, errorName, nvCreate, nvCompile, nvLogSize, nvLog, nvPtxSize, nvPtx, nvDestroy;
    private final MemorySegment context;
    private final String name, version;
    private final int cc;

    /** Opens the NVIDIA card with the most multiprocessors, or throws if there's no CUDA driver, card or NVRTC. */
    public CudaFfm() throws GpuException {
        Linker linker = Linker.nativeLinker();
        SymbolLookup cuda = driver();
        try {
            MethodHandle init = fn(linker, cuda, "cuInit", INT, INT);
            MethodHandle count = fn(linker, cuda, "cuDeviceGetCount", INT, PTR);
            MethodHandle get = fn(linker, cuda, "cuDeviceGet", INT, PTR, INT);
            MethodHandle getName = fn(linker, cuda, "cuDeviceGetName", INT, PTR, INT, INT);
            MethodHandle attr = fn(linker, cuda, "cuDeviceGetAttribute", INT, PTR, INT, INT);
            MethodHandle driverVersion = fn(linker, cuda, "cuDriverGetVersion", INT, PTR);
            MethodHandle retain = fn(linker, cuda, "cuDevicePrimaryCtxRetain", INT, PTR, INT);
            ctxSetCurrent = fn(linker, cuda, "cuCtxSetCurrent", INT, PTR);
            moduleLoadData = fn(linker, cuda, "cuModuleLoadData", INT, PTR, PTR);
            moduleGetFunction = fn(linker, cuda, "cuModuleGetFunction", INT, PTR, PTR, PTR);
            moduleUnload = fn(linker, cuda, "cuModuleUnload", INT, PTR);
            memAlloc = fn(linker, cuda, "cuMemAlloc_v2", INT, PTR, LONG);
            memFree = fn(linker, cuda, "cuMemFree_v2", INT, LONG);
            htod = fn(linker, cuda, "cuMemcpyHtoD_v2", INT, LONG, PTR, LONG);
            dtoh = fn(linker, cuda, "cuMemcpyDtoH_v2", INT, PTR, LONG, LONG);
            launch = fn(linker, cuda, "cuLaunchKernel", INT, PTR, INT, INT, INT, INT, INT, INT, INT, PTR, PTR, PTR);
            sync = fn(linker, cuda, "cuCtxSynchronize", INT);
            errorName = fn(linker, cuda, "cuGetErrorName", INT, INT, PTR);

            SymbolLookup nvrtc = nvrtc();
            nvCreate = fn(linker, nvrtc, "nvrtcCreateProgram", INT, PTR, PTR, PTR, INT, PTR, PTR);
            nvCompile = fn(linker, nvrtc, "nvrtcCompileProgram", INT, PTR, INT, PTR);
            nvLogSize = fn(linker, nvrtc, "nvrtcGetProgramLogSize", INT, PTR, PTR);
            nvLog = fn(linker, nvrtc, "nvrtcGetProgramLog", INT, PTR, PTR);
            nvPtxSize = fn(linker, nvrtc, "nvrtcGetPTXSize", INT, PTR, PTR);
            nvPtx = fn(linker, nvrtc, "nvrtcGetPTX", INT, PTR, PTR);
            nvDestroy = fn(linker, nvrtc, "nvrtcDestroyProgram", INT, PTR);
            MethodHandle nvVersion = fn(linker, nvrtc, "nvrtcVersion", INT, PTR, PTR);

            try (Arena a = Arena.ofConfined()) {
                check((int) init.invokeExact(0), "cuInit");
                MemorySegment n = a.allocate(4, 4);
                check((int) count.invokeExact(n), "cuDeviceGetCount");
                int best = -1, bestSms = -1;
                MemorySegment dev = a.allocate(4, 4), v = a.allocate(4, 4);
                for (int i = 0; i < n.get(INT, 0); i++) {
                    if ((int) get.invokeExact(dev, i) != 0) continue;
                    int d = dev.get(INT, 0);
                    if ((int) attr.invokeExact(v, ATTR_SM_COUNT, d) != 0) continue;
                    if (v.get(INT, 0) > bestSms) {
                        bestSms = v.get(INT, 0);
                        best = d;
                    }
                }
                if (best < 0) throw new GpuException("No NVIDIA graphics card found by CUDA");
                MemorySegment text = a.allocate(256, 1);
                check((int) getName.invokeExact(text, 256, best), "cuDeviceGetName");
                name = string(text, 256).trim();
                check((int) attr.invokeExact(v, ATTR_CC_MAJOR, best), "cuDeviceGetAttribute");
                int major = v.get(INT, 0);
                check((int) attr.invokeExact(v, ATTR_CC_MINOR, best), "cuDeviceGetAttribute");
                cc = major * 10 + v.get(INT, 0);
                MemorySegment ctx = a.allocate(8, 8);
                check((int) retain.invokeExact(ctx, best), "cuDevicePrimaryCtxRetain");
                context = ctx.get(PTR, 0);
                check((int) driverVersion.invokeExact(v), "cuDriverGetVersion");
                int drv = v.get(INT, 0);
                MemorySegment mj = a.allocate(4, 4), mn = a.allocate(4, 4);
                int ok = (int) nvVersion.invokeExact(mj, mn);
                version = "CUDA driver " + drv / 1000 + "." + drv % 1000 / 10 + ", NVRTC " + (ok == 0 ? mj.get(INT, 0) + "." + mn.get(INT, 0) : "?")
                        + ", compute " + major + "." + cc % 10;
            }
        } catch (GpuException e) {
            throw e;
        } catch (Throwable t) {
            throw new GpuException("CUDA failed: " + t, t);
        }
    }

    /** NVIDIA's driver library: System32 on Windows (by full path), the system library folders on Linux. */
    private static SymbolLookup driver() throws GpuException {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> names = new ArrayList<>();
        if (os.contains("win")) {
            String root = System.getenv("SystemRoot");
            names.add(Path.of(root == null || root.isBlank() ? "C:\\Windows" : root, "System32", "nvcuda.dll").toString());
        } else names.addAll(List.of("libcuda.so.1", "libcuda.so"));
        for (String n : names)
            try {
                return SymbolLookup.libraryLookup(n, Arena.global());
            } catch (IllegalArgumentException e) {
                // next
            }
        throw new GpuException("No NVIDIA CUDA driver on this computer");
    }

    /**
     * NVIDIA's runtime compiler, from the CUDA Toolkit: SEEDFINDER_NVRTC (a full path) if set, else the toolkit's
     * folders (CUDA_PATH, then the usual install places), newest version first.
     */
    private static SymbolLookup nvrtc() throws GpuException {
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean win = os.contains("win");
        List<Path> found = new ArrayList<>();
        String own = System.getenv("SEEDFINDER_NVRTC");
        if (own != null && !own.isBlank()) found.add(Path.of(own));
        List<Path> roots = new ArrayList<>();
        for (var e : System.getenv().entrySet())
            if (e.getKey().toUpperCase().startsWith("CUDA_PATH") && !e.getValue().isBlank()) roots.add(Path.of(e.getValue()));
        if (win) {
            Path pf = Path.of(System.getenv().getOrDefault("ProgramFiles", "C:\\Program Files"), "NVIDIA GPU Computing Toolkit", "CUDA");
            try (var s = Files.list(pf)) {
                s.forEach(roots::add);
            } catch (Exception ignored) {
                // not installed there
            }
        } else roots.addAll(List.of(Path.of("/usr/local/cuda"), Path.of("/opt/cuda")));
        TreeMap<String, Path> byName = new TreeMap<>(java.util.Comparator.reverseOrder());
        for (Path r : roots)
            for (String sub : win ? new String[]{"bin", "bin\\x64"} : new String[]{"lib64", "lib", "targets/x86_64-linux/lib"})
                try (var s = Files.list(r.resolve(sub))) {
                    s.filter(p -> {
                        String f = p.getFileName().toString();
                        return win ? f.startsWith("nvrtc64_") && f.endsWith(".dll") && !f.contains("builtins") : f.startsWith("libnvrtc.so") && !f.contains("builtins");
                    }).forEach(p -> byName.put(r.getFileName() + "/" + p.getFileName(), p));
                } catch (Exception ignored) {
                    // no such folder
                }
        found.addAll(byName.values());
        List<String> tries = new ArrayList<>();
        for (Path p : found) tries.add(p.toString());
        if (!win) tries.addAll(List.of("libnvrtc.so", "libnvrtc.so.13", "libnvrtc.so.12"));
        for (String n : tries)
            try {
                return SymbolLookup.libraryLookup(n, Arena.global());
            } catch (IllegalArgumentException e) {
                // next
            }
        throw new GpuException("CUDA needs NVIDIA's NVRTC from the free CUDA Toolkit (developer.nvidia.com/cuda-downloads), "
                + "or set SEEDFINDER_NVRTC to its file. OpenCL needs no toolkit: try that first.");
    }

    private static MethodHandle fn(Linker linker, SymbolLookup lib, String name, ValueLayout ret, ValueLayout... args) throws GpuException {
        MemorySegment address = lib.find(name).orElseThrow(() -> new GpuException("CUDA is missing " + name));
        return linker.downcallHandle(address, FunctionDescriptor.of(ret, args));
    }

    private void check(int code, String what) throws GpuException {
        if (code == 0) return;
        String n = "";
        try (Arena a = Arena.ofConfined()) {
            MemorySegment p = a.allocate(8, 8);
            if (errorName != null && (int) errorName.invokeExact(code, p) == 0) n = " " + string(p.get(PTR, 0).reinterpret(64), 64);
        } catch (Throwable ignored) {
            // just the number
        }
        throw new GpuException(what + " failed (CUDA error " + code + n + ")");
    }

    private void current() throws Throwable {
        check((int) ctxSetCurrent.invokeExact(context), "cuCtxSetCurrent");
    }

    private static String string(MemorySegment s, long max) {
        byte[] b = new byte[(int) max];
        MemorySegment.copy(s, ValueLayout.JAVA_BYTE, 0, b, 0, b.length);
        int len = 0;
        while (len < b.length && b[len] != 0) len++;
        return new String(b, 0, len, StandardCharsets.UTF_8);
    }

    private static MemorySegment cString(Arena a, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        MemorySegment m = a.allocate(b.length + 1L, 1);
        MemorySegment.copy(b, 0, m, ValueLayout.JAVA_BYTE, 0, b.length);
        m.set(ValueLayout.JAVA_BYTE, b.length, (byte) 0);
        return m;
    }

    @Override
    public String device() {
        return name + " (CUDA)";
    }

    /** Driver, NVRTC and compute capability, e.g. for a tester's report. */
    public String details() {
        return version;
    }

    // ------------------------------------------------------------------ OpenCL C to CUDA C

    /** Goes before every translated program: OpenCL's types and built-ins in CUDA terms. */
    static final String PRELUDE = """
            typedef unsigned long long ulong;
            typedef unsigned int uint;
            typedef unsigned short ushort;
            typedef unsigned char uchar;
            #define __kernel extern "C" __global__
            #define __global
            #define __constant __constant__
            #define __local __shared__
            #define CLK_LOCAL_MEM_FENCE 0
            #define CLK_GLOBAL_MEM_FENCE 0
            #define barrier(x) __syncthreads()
            #define get_global_id(d) ((ulong) blockIdx.x * blockDim.x + threadIdx.x)
            #define get_local_id(d) ((ulong) threadIdx.x)
            template <class T> __device__ inline T atomic_inc(T *p) { return atomicAdd((T *) p, (T) 1); }
            template <class T> __device__ inline T atomic_inc(volatile T *p) { return atomicAdd((T *) p, (T) 1); }
            __device__ inline double as_double(long long x) { return __longlong_as_double(x); }
            __device__ inline double as_double(ulong x) { return __longlong_as_double((long long) x); }
            __device__ inline long long as_long(double x) { return __double_as_longlong(x); }
            __device__ inline float as_float(int x) { return __int_as_float(x); }
            __device__ inline float as_float(uint x) { return __int_as_float((int) x); }
            __device__ inline int as_int(float x) { return __float_as_int(x); }
            __device__ inline int clz(int x) { return __clz(x); }
            __device__ inline uint clz(uint x) { return (uint) __clz((int) x); }
            __device__ inline long long clz(long long x) { return __clzll(x); }
            __device__ inline ulong clz(ulong x) { return (ulong) __clzll((long long) x); }
            __device__ inline int cl_min(int a, int b) { return a < b ? a : b; }
            __device__ inline uint cl_min(uint a, uint b) { return a < b ? a : b; }
            __device__ inline long long cl_min(long long a, long long b) { return a < b ? a : b; }
            __device__ inline ulong cl_min(ulong a, ulong b) { return a < b ? a : b; }
            __device__ inline float cl_min(float a, float b) { return fminf(a, b); }
            __device__ inline double cl_min(double a, double b) { return fmin(a, b); }
            __device__ inline int cl_max(int a, int b) { return a > b ? a : b; }
            __device__ inline uint cl_max(uint a, uint b) { return a > b ? a : b; }
            __device__ inline long long cl_max(long long a, long long b) { return a > b ? a : b; }
            __device__ inline ulong cl_max(ulong a, ulong b) { return a > b ? a : b; }
            __device__ inline float cl_max(float a, float b) { return fmaxf(a, b); }
            __device__ inline double cl_max(double a, double b) { return fmax(a, b); }
            __device__ inline uint cl_abs(int x) { return x < 0 ? 0u - (uint) x : (uint) x; }
            __device__ inline ulong cl_abs(long long x) { return x < 0 ? 0ull - (ulong) x : (ulong) x; }
            __device__ inline double cl_mix(double a, double b, double t) { return a + (b - a) * t; }
            __device__ inline float cl_mix(float a, float b, float t) { return a + (b - a) * t; }
            """;

    /** Hex floating-point numbers (0x1.0p-2) are C++17: written out in decimal instead, the same value exactly. */
    private static final java.util.regex.Pattern HEX_FLOAT = java.util.regex.Pattern.compile(
            "\\b0[xX]([0-9a-fA-F]*\\.?[0-9a-fA-F]*)[pP]([+-]?[0-9]+)([fF]?)");

    private static String decimalFloats(String src) {
        java.util.regex.Matcher m = HEX_FLOAT.matcher(src);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String lit = "0x" + m.group(1) + "p" + m.group(2);
            String dec;
            if (m.group(3).isEmpty()) {
                // Java prints the shortest decimal that reads back as exactly this double.
                dec = Double.toString(Double.parseDouble(lit));
            } else dec = Float.toString(Float.parseFloat(lit)) + "f";
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(dec));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** OpenCL C (as the app writes it) to CUDA C. */
    public static String translate(String src) {
        String s = decimalFloats(src).replaceAll("(?m)^\\s*#pragma OPENCL.*$", "")
                .replaceAll("__attribute__\\(\\(reqd_work_group_size\\((\\d+)\\s*,\\s*1\\s*,\\s*1\\)\\)\\)", "__launch_bounds__($1)")
                .replaceAll("\\(int2\\)\\s*\\(", "make_int2(")
                // OpenCL's long is 64 bits everywhere; CUDA's long is 32 on Windows: the type is written long long
                .replaceAll("(?<!long )\\blong\\b(?! long)", "long long")
                // and number suffixes L/UL become LL/ULL.
                .replaceAll("\\b(0[xX][0-9a-fA-F]+|[0-9]+)([uU]?)[lL]\\b", "$1$2LL")
                .replaceAll("\\bmin\\s*\\(", "cl_min(")
                .replaceAll("\\bmax\\s*\\(", "cl_max(")
                .replaceAll("\\babs\\s*\\(", "cl_abs(")
                .replaceAll("\\bmix\\s*\\(", "cl_mix(");
        // CUDA puts launch bounds between the return type and the name: "__kernel __launch_bounds__(256)\nvoid f" is fine.
        return PRELUDE + s;
    }

    // ------------------------------------------------------------------ programs

    @Override
    public synchronized Program program(String source, String options) throws GpuException {
        return load(compile(translate(source)));
    }

    @Override
    public synchronized Program program(String source, String options, Path cache) throws GpuException {
        String key;
        try {
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{name, version, source}) {
                sha.update(part.getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
            }
            key = java.util.HexFormat.of().formatHex(sha.digest()).substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException e) {
            return program(source, options);
        }
        Path file = cache.resolve(key + ".ptx");
        if (Files.isRegularFile(file)) {
            try {
                return load(Files.readString(file));
            } catch (Throwable e) {
                // stale or broken: compile again
            }
        }
        String ptx = compile(translate(source));
        Program p = load(ptx);
        try {
            Files.createDirectories(cache);
            Path tmp = cache.resolve(key + ".tmp");
            Files.writeString(tmp, ptx);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable e) {
            // no cache this time
        }
        return p;
    }

    /** CUDA C to PTX with NVRTC, for this card or the newest older one NVRTC knows. Exact maths: no fused multiply-add. */
    private String compile(String cuda) throws GpuException {
        String lastLog = "";
        for (int arch : ARCHS) {
            if (arch > cc) continue;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment prog = a.allocate(8, 8);
                int r = (int) nvCreate.invokeExact(prog, cString(a, cuda), cString(a, "seedfinder.cu"), 0, MemorySegment.NULL, MemorySegment.NULL);
                if (r != 0) throw new GpuException("nvrtcCreateProgram failed (" + r + ")");
                MemorySegment p = prog.get(PTR, 0);
                String[] opts = {"--gpu-architecture=compute_" + arch, "--fmad=false", "-default-device", "--std=c++14"};
                MemorySegment o = a.allocate(8L * opts.length, 8);
                for (int i = 0; i < opts.length; i++) o.setAtIndex(PTR, i, cString(a, opts[i]));
                int built = (int) nvCompile.invokeExact(p, opts.length, o);
                if (built == 0) {
                    MemorySegment size = a.allocate(8, 8);
                    int s1 = (int) nvPtxSize.invokeExact(p, size);
                    MemorySegment ptx = a.allocate(Math.max(1, size.get(LONG, 0)), 1);
                    int s2 = (int) nvPtx.invokeExact(p, ptx);
                    int d = (int) nvDestroy.invokeExact(prog);
                    if (s1 != 0 || s2 != 0) throw new GpuException("NVRTC gave no PTX");
                    return string(ptx, size.get(LONG, 0));
                }
                MemorySegment size = a.allocate(8, 8);
                if ((int) nvLogSize.invokeExact(p, size) == 0) {
                    MemorySegment log = a.allocate(Math.max(1, size.get(LONG, 0)), 1);
                    if ((int) nvLog.invokeExact(p, log) == 0) lastLog = string(log, size.get(LONG, 0));
                }
                int d = (int) nvDestroy.invokeExact(prog);
                // An NVRTC older than the card doesn't know its architecture: try the next older one.
                if (!lastLog.contains("gpu-architecture") && !lastLog.contains("invalid value")) break;
            } catch (GpuException e) {
                throw e;
            } catch (Throwable t) {
                throw new GpuException("NVRTC failed: " + t, t);
            }
        }
        throw new GpuException("The graphics card couldn't compile the world generator (CUDA):\n" + lastLog);
    }

    private Program load(String ptx) throws GpuException {
        try (Arena a = Arena.ofConfined()) {
            current();
            MemorySegment m = a.allocate(8, 8);
            check((int) moduleLoadData.invokeExact(m, cString(a, ptx)), "cuModuleLoadData");
            return new ProgramImpl(m.get(PTR, 0));
        } catch (GpuException e) {
            throw e;
        } catch (Throwable t) {
            throw new GpuException("CUDA failed: " + t, t);
        }
    }

    @Override
    public synchronized Buffer buffer(long bytes) throws GpuException {
        try (Arena a = Arena.ofConfined()) {
            current();
            MemorySegment p = a.allocate(8, 8);
            check((int) memAlloc.invokeExact(p, Math.max(1, bytes)), "cuMemAlloc (" + bytes + " bytes)");
            return new BufferImpl(p.get(LONG, 0), bytes);
        } catch (GpuException e) {
            throw e;
        } catch (Throwable t) {
            throw new GpuException("CUDA failed: " + t, t);
        }
    }

    @Override
    public synchronized void close() {
        // The primary context stays with the driver until the app ends.
    }

    private final class ProgramImpl implements Program {
        private final MemorySegment module;

        ProgramImpl(MemorySegment module) {
            this.module = module;
        }

        @Override
        public Kernel kernel(String kernelName) throws GpuException {
            synchronized (CudaFfm.this) {
                try (Arena a = Arena.ofConfined()) {
                    current();
                    MemorySegment f = a.allocate(8, 8);
                    check((int) moduleGetFunction.invokeExact(f, module, cString(a, kernelName)), "cuModuleGetFunction " + kernelName);
                    return new KernelImpl(f.get(PTR, 0));
                } catch (GpuException e) {
                    throw e;
                } catch (Throwable t) {
                    throw new GpuException("CUDA failed: " + t, t);
                }
            }
        }

        @Override
        public void close() {
            synchronized (CudaFfm.this) {
                try {
                    current();
                    int r = (int) moduleUnload.invokeExact(module);
                } catch (Throwable ignored) {
                    // gone anyway
                }
            }
        }
    }

    private final class KernelImpl implements Kernel {
        private final MemorySegment function;
        /** Each argument's value, 8 bytes (CUDA reads 4 for an int). */
        private final TreeMap<Integer, long[]> args = new TreeMap<>();

        KernelImpl(MemorySegment function) {
            this.function = function;
        }

        @Override
        public void set(int index, Buffer value) {
            args.put(index, new long[]{((BufferImpl) value).ptr, 8});
        }

        @Override
        public void set(int index, int value) {
            args.put(index, new long[]{value & 0xFFFFFFFFL, 4});
        }

        @Override
        public void set(int index, long value) {
            args.put(index, new long[]{value, 8});
        }

        @Override
        public void set(int index, double value) {
            args.put(index, new long[]{Double.doubleToRawLongBits(value), 8});
        }

        @Override
        public void run(long items) throws GpuException {
            // Without a group size: the biggest power of two up to 256 that divides the work exactly (the kernels
            // don't check for threads past the end, as with OpenCL).
            int group = 1;
            while (group < 256 && items % (group * 2L) == 0) group *= 2;
            run(items, group);
        }

        @Override
        public void run(long items, int group) throws GpuException {
            if (group <= 0) {
                run(items);
                return;
            }
            if (items % group != 0) throw new GpuException("CUDA: " + items + " work items aren't a whole number of groups of " + group);
            long blocks = items / group;
            if (blocks > Integer.MAX_VALUE) throw new GpuException("CUDA: too many work items in one run");
            synchronized (CudaFfm.this) {
                try (Arena a = Arena.ofConfined()) {
                    current();
                    int n = args.isEmpty() ? 0 : args.lastKey() + 1;
                    MemorySegment values = a.allocate(8L * Math.max(1, n), 8), params = a.allocate(8L * Math.max(1, n), 8);
                    for (int i = 0; i < n; i++) {
                        long[] v = args.get(i);
                        if (v == null) throw new GpuException("CUDA: kernel argument " + i + " not set");
                        MemorySegment slot = values.asSlice(8L * i, 8);
                        if (v[1] == 4) slot.set(INT, 0, (int) v[0]);
                        else slot.set(LONG, 0, v[0]);
                        params.setAtIndex(PTR, i, slot);
                    }
                    check((int) launch.invokeExact(function, (int) blocks, 1, 1, group, 1, 1, 0, MemorySegment.NULL, params, MemorySegment.NULL), "cuLaunchKernel");
                    check((int) sync.invokeExact(), "cuCtxSynchronize");
                } catch (GpuException e) {
                    throw e;
                } catch (Throwable t) {
                    throw new GpuException("CUDA failed: " + t, t);
                }
            }
        }

        @Override
        public void close() {
            // Functions belong to their module.
        }
    }

    private final class BufferImpl implements Buffer {
        private final long ptr;
        private final long size;

        BufferImpl(long ptr, long size) {
            this.ptr = ptr;
            this.size = size;
        }

        @Override
        public long size() {
            return size;
        }

        private void write(Object array, ValueLayout layout, int count) throws GpuException {
            synchronized (CudaFfm.this) {
                try (Arena a = Arena.ofConfined()) {
                    current();
                    long bytes = layout.byteSize() * count;
                    MemorySegment host = a.allocate(Math.max(1, bytes), 8);
                    MemorySegment.copy(array, 0, host, layout, 0, count);
                    check((int) htod.invokeExact(ptr, host, bytes), "cuMemcpyHtoD");
                } catch (GpuException e) {
                    throw e;
                } catch (Throwable t) {
                    throw new GpuException("CUDA failed: " + t, t);
                }
            }
        }

        private void read(Object array, ValueLayout layout, int count) throws GpuException {
            synchronized (CudaFfm.this) {
                try (Arena a = Arena.ofConfined()) {
                    current();
                    long bytes = layout.byteSize() * count;
                    MemorySegment host = a.allocate(Math.max(1, bytes), 8);
                    check((int) dtoh.invokeExact(host, ptr, bytes), "cuMemcpyDtoH");
                    MemorySegment.copy(host, layout, 0, array, 0, count);
                } catch (GpuException e) {
                    throw e;
                } catch (Throwable t) {
                    throw new GpuException("CUDA failed: " + t, t);
                }
            }
        }

        @Override
        public void write(byte[] data) throws GpuException {
            write(data, ValueLayout.JAVA_BYTE, data.length);
        }

        @Override
        public void write(int[] data) throws GpuException {
            write(data, ValueLayout.JAVA_INT, data.length);
        }

        @Override
        public void write(long[] data) throws GpuException {
            write(data, ValueLayout.JAVA_LONG, data.length);
        }

        @Override
        public void write(double[] data) throws GpuException {
            write(data, ValueLayout.JAVA_DOUBLE, data.length);
        }

        @Override
        public void read(int[] into, int count) throws GpuException {
            read(into, ValueLayout.JAVA_INT, count);
        }

        @Override
        public void read(long[] into, int count) throws GpuException {
            read(into, ValueLayout.JAVA_LONG, count);
        }

        @Override
        public void read(double[] into, int count) throws GpuException {
            read(into, ValueLayout.JAVA_DOUBLE, count);
        }

        @Override
        public void close() {
            synchronized (CudaFfm.this) {
                try {
                    current();
                    int r = (int) memFree.invokeExact(ptr);
                } catch (Throwable ignored) {
                    // gone anyway
                }
            }
        }
    }
}
