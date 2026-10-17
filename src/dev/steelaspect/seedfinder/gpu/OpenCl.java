package dev.steelaspect.seedfinder.gpu;

/**
 * The few OpenCL calls the app needs. The implementation talks to the system's OpenCL driver through Java's
 * foreign function API (no native library is shipped); this interface keeps those types out of the rest of the app,
 * which stays plain Java 21.
 */
public interface OpenCl extends AutoCloseable {
    /** The graphics card's name, e.g. "AMD Radeon RX 9070 XT". */
    String device();

    /** Compiles OpenCL C source. On failure the exception holds the compiler's log. */
    Program program(String source, String options) throws GpuException;

    /**
     * Like {@link #program(String, String)}, but keeps the compiled program in this folder (named by a hash of the
     * card, its driver, the options and the source) and loads it from there next time: compiling can take seconds.
     */
    Program program(String source, String options, java.nio.file.Path cache) throws GpuException;

    Buffer buffer(long bytes) throws GpuException;

    @Override
    void close();

    interface Program extends AutoCloseable {
        Kernel kernel(String name) throws GpuException;

        @Override
        void close();
    }

    interface Kernel extends AutoCloseable {
        void set(int index, Buffer value) throws GpuException;

        void set(int index, int value) throws GpuException;

        void set(int index, long value) throws GpuException;

        void set(int index, double value) throws GpuException;

        /** Runs the kernel on this many work items and waits for it. */
        void run(long items) throws GpuException;

        /** Same, in work groups of this size (items must be a multiple of it). */
        void run(long items, int group) throws GpuException;

        @Override
        void close();
    }

    interface Buffer extends AutoCloseable {
        long size();

        void write(byte[] data) throws GpuException;

        void write(int[] data) throws GpuException;

        void write(long[] data) throws GpuException;

        void write(double[] data) throws GpuException;

        void read(int[] into, int count) throws GpuException;

        void read(long[] into, int count) throws GpuException;

        void read(double[] into, int count) throws GpuException;

        @Override
        void close();
    }
}
