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

/**
 * OpenCL through Java's foreign function API: the system's own OpenCL library (libOpenCL / OpenCL.dll, installed
 * with the graphics driver) is called directly, nothing native is shipped. Built twice from this one file: for
 * Java 21 (where the API is a preview, run with --enable-preview) and for Java 22 and newer. So it only uses calls
 * that are the same in both.
 *
 * <p>Picks the GPU with the most compute units that does 64-bit floating point (Minecraft's noise is all doubles).
 * All calls are synchronised: one job on the card at a time.
 */
public final class OpenClFfm implements OpenCl {
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
    private static final AddressLayout PTR = ValueLayout.ADDRESS;
    private static final long DEVICE_TYPE_GPU = 1L << 2;
    private static final int DEVICE_NAME = 0x102B, DEVICE_EXTENSIONS = 0x1030, DEVICE_MAX_COMPUTE_UNITS = 0x1002;
    private static final int DEVICE_DOUBLE_FP_CONFIG = 0x1032, PROGRAM_BUILD_LOG = 0x1183, DRIVER_VERSION = 0x102D;
    private static final int PROGRAM_BINARY_SIZES = 0x1165, PROGRAM_BINARIES = 0x1166;
    private static final long MEM_READ_WRITE = 1L;

    private final Arena arena = Arena.ofShared();
    private final MethodHandle getPlatformIds, getDeviceIds, getDeviceInfo, createContext, createQueue, createProgram,
            buildProgram, getBuildInfo, createKernel, programInfo, createProgramBinary, setKernelArg, createBuffer, writeBuffer, readBuffer, ndRange, finish,
            releaseMem, releaseKernel, releaseProgram, releaseQueue, releaseContext;
    private final MemorySegment context, queue, device;
    private final String name, driver;

    /** Opens the best GPU, or throws if there's no OpenCL, no GPU or no 64-bit maths. */
    public OpenClFfm() throws GpuException {
        SymbolLookup lib = load();
        Linker linker = Linker.nativeLinker();
        getPlatformIds = fn(linker, lib, "clGetPlatformIDs", INT, INT, PTR, PTR);
        getDeviceIds = fn(linker, lib, "clGetDeviceIDs", INT, PTR, LONG, INT, PTR, PTR);
        getDeviceInfo = fn(linker, lib, "clGetDeviceInfo", INT, PTR, INT, LONG, PTR, PTR);
        createContext = fn(linker, lib, "clCreateContext", PTR, PTR, INT, PTR, PTR, PTR, PTR);
        createQueue = fn(linker, lib, "clCreateCommandQueue", PTR, PTR, PTR, LONG, PTR);
        createProgram = fn(linker, lib, "clCreateProgramWithSource", PTR, PTR, INT, PTR, PTR, PTR);
        buildProgram = fn(linker, lib, "clBuildProgram", INT, PTR, INT, PTR, PTR, PTR, PTR);
        getBuildInfo = fn(linker, lib, "clGetProgramBuildInfo", INT, PTR, PTR, INT, LONG, PTR, PTR);
        programInfo = fn(linker, lib, "clGetProgramInfo", INT, PTR, INT, LONG, PTR, PTR);
        createProgramBinary = fn(linker, lib, "clCreateProgramWithBinary", PTR, PTR, INT, PTR, PTR, PTR, PTR, PTR);
        createKernel = fn(linker, lib, "clCreateKernel", PTR, PTR, PTR, PTR);
        setKernelArg = fn(linker, lib, "clSetKernelArg", INT, PTR, INT, LONG, PTR);
        createBuffer = fn(linker, lib, "clCreateBuffer", PTR, PTR, LONG, LONG, PTR, PTR);
        writeBuffer = fn(linker, lib, "clEnqueueWriteBuffer", INT, PTR, PTR, INT, LONG, LONG, PTR, INT, PTR, PTR);
        readBuffer = fn(linker, lib, "clEnqueueReadBuffer", INT, PTR, PTR, INT, LONG, LONG, PTR, INT, PTR, PTR);
        ndRange = fn(linker, lib, "clEnqueueNDRangeKernel", INT, PTR, PTR, INT, PTR, PTR, PTR, INT, PTR, PTR);
        finish = fn(linker, lib, "clFinish", INT, PTR);
        releaseMem = fn(linker, lib, "clReleaseMemObject", INT, PTR);
        releaseKernel = fn(linker, lib, "clReleaseKernel", INT, PTR);
        releaseProgram = fn(linker, lib, "clReleaseProgram", INT, PTR);
        releaseQueue = fn(linker, lib, "clReleaseCommandQueue", INT, PTR);
        releaseContext = fn(linker, lib, "clReleaseContext", INT, PTR);

        MemorySegment best = null;
        int bestUnits = -1;
        String bestName = null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment count = a.allocate(4, 4);
            check((int) getPlatformIds.invokeExact(0, MemorySegment.NULL, count), "clGetPlatformIDs");
            int platforms = count.get(INT, 0);
            MemorySegment ids = a.allocate(8L * Math.max(1, platforms), 8);
            check((int) getPlatformIds.invokeExact(platforms, ids, MemorySegment.NULL), "clGetPlatformIDs");
            for (int p = 0; p < platforms; p++) {
                MemorySegment platform = ids.getAtIndex(PTR, p);
                MemorySegment n = a.allocate(4, 4);
                if ((int) getDeviceIds.invokeExact(platform, DEVICE_TYPE_GPU, 0, MemorySegment.NULL, n) != 0) continue;
                int devices = n.get(INT, 0);
                MemorySegment devs = a.allocate(8L * Math.max(1, devices), 8);
                if ((int) getDeviceIds.invokeExact(platform, DEVICE_TYPE_GPU, devices, devs, MemorySegment.NULL) != 0) continue;
                for (int d = 0; d < devices; d++) {
                    MemorySegment dev = devs.getAtIndex(PTR, d);
                    boolean fp64 = infoLong(dev, DEVICE_DOUBLE_FP_CONFIG) != 0 || infoString(dev, DEVICE_EXTENSIONS).contains("cl_khr_fp64");
                    int units = (int) infoLong(dev, DEVICE_MAX_COMPUTE_UNITS);
                    if (fp64 && units > bestUnits) {
                        best = dev;
                        bestUnits = units;
                        bestName = infoString(dev, DEVICE_NAME);
                    }
                }
            }
            if (best == null) throw new GpuException("No graphics card with 64-bit maths (OpenCL cl_khr_fp64) was found");
            MemorySegment one = a.allocate(8, 8);
            one.set(PTR, 0, best);
            MemorySegment err = a.allocate(4, 4);
            context = (MemorySegment) createContext.invokeExact(MemorySegment.NULL, 1, one, MemorySegment.NULL, MemorySegment.NULL, err);
            check(err.get(INT, 0), "clCreateContext");
            queue = (MemorySegment) createQueue.invokeExact(context, best, 0L, err);
            check(err.get(INT, 0), "clCreateCommandQueue");
        } catch (GpuException e) {
            throw e;
        } catch (Throwable t) {
            throw new GpuException("OpenCL failed: " + t, t);
        }
        device = best;
        String drv;
        try {
            drv = infoString(best, DRIVER_VERSION);
        } catch (Throwable t) {
            drv = "";
        }
        driver = drv;
        // Driver names can be long ("AMD Radeon RX 9070 XT (radeonsi, gfx1201, ...)"): keep the card's name.
        name = bestName.contains(" (") ? bestName.substring(0, bestName.indexOf(" (")).trim() : bestName.trim();
    }

    /**
     * The system's OpenCL loader. Windows: only the one in System32, by full path (a bare name would also search the
     * app's own folder and the PATH, where any OpenCL.dll could sit). Linux: by soname, which the dynamic linker
     * looks up in the system library folders only (never the current folder). Mac: the system framework.
     */
    private static SymbolLookup load() throws GpuException {
        String os = System.getProperty("os.name", "").toLowerCase();
        String[] names;
        if (os.contains("win")) {
            String root = System.getenv("SystemRoot");
            java.nio.file.Path dll = java.nio.file.Path.of(root == null || root.isBlank() ? "C:\\Windows" : root, "System32", "OpenCL.dll");
            if (!java.nio.file.Files.isRegularFile(dll)) throw new GpuException("No OpenCL driver on this computer");
            names = new String[]{dll.toString()};
        } else {
            names = os.contains("mac") ? new String[]{"/System/Library/Frameworks/OpenCL.framework/OpenCL"}
                    : new String[]{"libOpenCL.so.1", "libOpenCL.so"};
        }
        for (String n : names) {
            try {
                return SymbolLookup.libraryLookup(n, Arena.global());
            } catch (IllegalArgumentException e) {
                // try the next name
            }
        }
        throw new GpuException("No OpenCL driver on this computer");
    }

    private static MethodHandle fn(Linker linker, SymbolLookup lib, String name, ValueLayout ret, ValueLayout... args) throws GpuException {
        MemorySegment address = lib.find(name).orElseThrow(() -> new GpuException("OpenCL is missing " + name));
        return linker.downcallHandle(address, FunctionDescriptor.of(ret, args));
    }

    private static void check(int code, String what) throws GpuException {
        if (code != 0) throw new GpuException(what + " failed (OpenCL error " + code + ")");
    }

    private long infoLong(MemorySegment dev, int param) throws Throwable {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment v = a.allocate(8, 8);
            v.set(LONG, 0, 0L);
            int r = (int) getDeviceInfo.invokeExact(dev, param, 8L, v, MemorySegment.NULL);
            return r == 0 ? v.get(LONG, 0) : 0L;
        }
    }

    private String infoString(MemorySegment dev, int param) throws Throwable {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment size = a.allocate(8, 8);
            if ((int) getDeviceInfo.invokeExact(dev, param, 0L, MemorySegment.NULL, size) != 0) return "";
            long n = size.get(LONG, 0);
            MemorySegment text = a.allocate(Math.max(1, n), 1);
            if ((int) getDeviceInfo.invokeExact(dev, param, n, text, MemorySegment.NULL) != 0) return "";
            return string(text, n);
        }
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
        return name;
    }

    @Override
    public synchronized Program program(String source, String options) throws GpuException {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment src = cString(a, source);
            MemorySegment strings = a.allocate(8, 8);
            strings.set(PTR, 0, src);
            MemorySegment err = a.allocate(4, 4);
            MemorySegment program = (MemorySegment) createProgram.invokeExact(context, 1, strings, MemorySegment.NULL, err);
            check(err.get(INT, 0), "clCreateProgramWithSource");
            MemorySegment devs = a.allocate(8, 8);
            devs.set(PTR, 0, device);
            int built = (int) buildProgram.invokeExact(program, 1, devs, cString(a, options), MemorySegment.NULL, MemorySegment.NULL);
            if (built != 0) {
                MemorySegment size = a.allocate(8, 8);
                int r = (int) getBuildInfo.invokeExact(program, device, PROGRAM_BUILD_LOG, 0L, MemorySegment.NULL, size);
                String log = "";
                if (r == 0) {
                    long n = size.get(LONG, 0);
                    MemorySegment text = a.allocate(Math.max(1, n), 1);
                    if ((int) getBuildInfo.invokeExact(program, device, PROGRAM_BUILD_LOG, n, text, MemorySegment.NULL) == 0) log = string(text, n);
                }
                int ignored = (int) releaseProgram.invokeExact(program);
                throw new GpuException("The graphics card couldn't compile the world generator (OpenCL error " + built + "):\n" + log);
            }
            return new ProgramImpl(program);
        } catch (GpuException e) {
            throw e;
        } catch (Throwable t) {
            throw new GpuException("OpenCL failed: " + t, t);
        }
    }

    @Override
    public synchronized Program program(String source, String options, java.nio.file.Path cache) throws GpuException {
        String key;
        try {
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{name, driver, options, source}) {
                sha.update(part.getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
            }
            key = java.util.HexFormat.of().formatHex(sha.digest()).substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException e) {
            return program(source, options);
        }
        java.nio.file.Path file = cache.resolve(key + ".bin");
        if (java.nio.file.Files.isRegularFile(file)) {
            try {
                return fromBinary(java.nio.file.Files.readAllBytes(file), options);
            } catch (Throwable e) {
                // stale or broken: compile again
            }
        }
        Program p = program(source, options);
        try {
            byte[] bin = binary(((ProgramImpl) p).program);
            if (bin != null) {
                java.nio.file.Files.createDirectories(cache);
                java.nio.file.Path tmp = cache.resolve(key + ".tmp");
                java.nio.file.Files.write(tmp, bin);
                java.nio.file.Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable e) {
            // no cache this time
        }
        return p;
    }

    private Program fromBinary(byte[] bin, String options) throws Throwable {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment data = a.allocate(Math.max(1, bin.length), 8);
            MemorySegment.copy(bin, 0, data, ValueLayout.JAVA_BYTE, 0, bin.length);
            MemorySegment ptrs = a.allocate(8, 8);
            ptrs.set(PTR, 0, data);
            MemorySegment lengths = a.allocate(8, 8);
            lengths.set(LONG, 0, (long) bin.length);
            MemorySegment devs = a.allocate(8, 8);
            devs.set(PTR, 0, device);
            MemorySegment status = a.allocate(4, 4), err = a.allocate(4, 4);
            MemorySegment program = (MemorySegment) createProgramBinary.invokeExact(context, 1, devs, lengths, ptrs, status, err);
            check(err.get(INT, 0), "clCreateProgramWithBinary");
            int built = (int) buildProgram.invokeExact(program, 1, devs, cString(a, options), MemorySegment.NULL, MemorySegment.NULL);
            if (built != 0) {
                int ignored = (int) releaseProgram.invokeExact(program);
                throw new GpuException("cached program didn't load (" + built + ")");
            }
            return new ProgramImpl(program);
        }
    }

    private byte[] binary(MemorySegment program) throws Throwable {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment size = a.allocate(8, 8);
            if ((int) programInfo.invokeExact(program, PROGRAM_BINARY_SIZES, 8L, size, MemorySegment.NULL) != 0) return null;
            long n = size.get(LONG, 0);
            if (n <= 0 || n > Integer.MAX_VALUE) return null;
            MemorySegment data = a.allocate(n, 8);
            MemorySegment ptrs = a.allocate(8, 8);
            ptrs.set(PTR, 0, data);
            if ((int) programInfo.invokeExact(program, PROGRAM_BINARIES, 8L, ptrs, MemorySegment.NULL) != 0) return null;
            byte[] out = new byte[(int) n];
            MemorySegment.copy(data, ValueLayout.JAVA_BYTE, 0, out, 0, out.length);
            return out;
        }
    }

    @Override
    public synchronized Buffer buffer(long bytes) throws GpuException {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment err = a.allocate(4, 4);
            MemorySegment mem = (MemorySegment) createBuffer.invokeExact(context, MEM_READ_WRITE, Math.max(1, bytes), MemorySegment.NULL, err);
            check(err.get(INT, 0), "clCreateBuffer (" + bytes + " bytes)");
            return new BufferImpl(mem, bytes);
        } catch (GpuException e) {
            throw e;
        } catch (Throwable t) {
            throw new GpuException("OpenCL failed: " + t, t);
        }
    }

    @Override
    public synchronized void close() {
        try {
            int a = (int) releaseQueue.invokeExact(queue);
            int b = (int) releaseContext.invokeExact(context);
        } catch (Throwable ignored) {
            // closing anyway
        }
        arena.close();
    }

    private final class ProgramImpl implements Program {
        private final MemorySegment program;

        ProgramImpl(MemorySegment program) {
            this.program = program;
        }

        @Override
        public Kernel kernel(String kernelName) throws GpuException {
            synchronized (OpenClFfm.this) {
                try (Arena a = Arena.ofConfined()) {
                    MemorySegment err = a.allocate(4, 4);
                    MemorySegment k = (MemorySegment) createKernel.invokeExact(program, cString(a, kernelName), err);
                    check(err.get(INT, 0), "clCreateKernel " + kernelName);
                    return new KernelImpl(k);
                } catch (GpuException e) {
                    throw e;
                } catch (Throwable t) {
                    throw new GpuException("OpenCL failed: " + t, t);
                }
            }
        }

        @Override
        public void close() {
            synchronized (OpenClFfm.this) {
                try {
                    int r = (int) releaseProgram.invokeExact(program);
                } catch (Throwable ignored) {
                    // gone anyway
                }
            }
        }
    }

    private final class KernelImpl implements Kernel {
        private final MemorySegment kernel;

        KernelImpl(MemorySegment kernel) {
            this.kernel = kernel;
        }

        private void arg(int index, long size, java.util.function.Consumer<MemorySegment> fill) throws GpuException {
            synchronized (OpenClFfm.this) {
                try (Arena a = Arena.ofConfined()) {
                    MemorySegment v = a.allocate(8, 8);
                    fill.accept(v);
                    check((int) setKernelArg.invokeExact(kernel, index, size, v), "clSetKernelArg " + index);
                } catch (GpuException e) {
                    throw e;
                } catch (Throwable t) {
                    throw new GpuException("OpenCL failed: " + t, t);
                }
            }
        }

        @Override
        public void set(int index, Buffer value) throws GpuException {
            MemorySegment mem = ((BufferImpl) value).mem;
            arg(index, 8, v -> v.set(PTR, 0, mem));
        }

        @Override
        public void set(int index, int value) throws GpuException {
            arg(index, 4, v -> v.set(INT, 0, value));
        }

        @Override
        public void set(int index, long value) throws GpuException {
            arg(index, 8, v -> v.set(LONG, 0, value));
        }

        @Override
        public void set(int index, double value) throws GpuException {
            arg(index, 8, v -> v.set(ValueLayout.JAVA_DOUBLE, 0, value));
        }

        @Override
        public void run(long items) throws GpuException {
            synchronized (OpenClFfm.this) {
                try (Arena a = Arena.ofConfined()) {
                    MemorySegment global = a.allocate(8, 8);
                    global.set(LONG, 0, items);
                    check((int) ndRange.invokeExact(queue, kernel, 1, MemorySegment.NULL, global, MemorySegment.NULL, 0, MemorySegment.NULL, MemorySegment.NULL),
                            "clEnqueueNDRangeKernel");
                    check((int) finish.invokeExact(queue), "clFinish");
                } catch (GpuException e) {
                    throw e;
                } catch (Throwable t) {
                    throw new GpuException("OpenCL failed: " + t, t);
                }
            }
        }

        @Override
        public void close() {
            synchronized (OpenClFfm.this) {
                try {
                    int r = (int) releaseKernel.invokeExact(kernel);
                } catch (Throwable ignored) {
                    // gone anyway
                }
            }
        }
    }

    private final class BufferImpl implements Buffer {
        private final MemorySegment mem;
        private final long size;

        BufferImpl(MemorySegment mem, long size) {
            this.mem = mem;
            this.size = size;
        }

        @Override
        public long size() {
            return size;
        }

        private void write(Object array, ValueLayout layout, int count) throws GpuException {
            synchronized (OpenClFfm.this) {
                try (Arena a = Arena.ofConfined()) {
                    long bytes = layout.byteSize() * count;
                    MemorySegment host = a.allocate(Math.max(1, bytes), 8);
                    MemorySegment.copy(array, 0, host, layout, 0, count);
                    check((int) writeBuffer.invokeExact(queue, mem, 1, 0L, bytes, host, 0, MemorySegment.NULL, MemorySegment.NULL), "clEnqueueWriteBuffer");
                } catch (GpuException e) {
                    throw e;
                } catch (Throwable t) {
                    throw new GpuException("OpenCL failed: " + t, t);
                }
            }
        }

        private void read(Object array, ValueLayout layout, int count) throws GpuException {
            synchronized (OpenClFfm.this) {
                try (Arena a = Arena.ofConfined()) {
                    long bytes = layout.byteSize() * count;
                    MemorySegment host = a.allocate(Math.max(1, bytes), 8);
                    check((int) readBuffer.invokeExact(queue, mem, 1, 0L, bytes, host, 0, MemorySegment.NULL, MemorySegment.NULL), "clEnqueueReadBuffer");
                    MemorySegment.copy(host, layout, 0, array, 0, count);
                } catch (GpuException e) {
                    throw e;
                } catch (Throwable t) {
                    throw new GpuException("OpenCL failed: " + t, t);
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
            synchronized (OpenClFfm.this) {
                try {
                    int r = (int) releaseMem.invokeExact(mem);
                } catch (Throwable ignored) {
                    // gone anyway
                }
            }
        }
    }
}
