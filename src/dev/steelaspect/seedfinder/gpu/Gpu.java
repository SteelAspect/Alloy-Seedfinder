package dev.steelaspect.seedfinder.gpu;

/**
 * Finds the graphics card once, through OpenCL (any vendor) or NVIDIA's CUDA. The code for both is built twice (gpu.ffm21 for Java 21, which needs
 * --enable-preview, and gpu.ffm22 for Java 22 and newer); this picks the one for the running Java. Anything that goes
 * wrong just means no GPU: every search also runs on the CPU with Minecraft's own code.
 */
public final class Gpu {
    private static OpenCl cl;
    private static String status;

    private Gpu() {}

    /** The card, or null if there's none we can use (see {@link #status()}). */
    public static synchronized OpenCl get() {
        if (status == null) open();
        return cl;
    }

    /** "AMD Radeon RX 9070 XT" or why there's no GPU. */
    public static synchronized String status() {
        if (status == null) open();
        return status;
    }

    /**
     * Which API to use: "auto" (OpenCL, then CUDA if OpenCL doesn't work), "opencl" or "cuda". From the
     * seedfinder.gpu property (the app's setting, --gpu-api) or the SEEDFINDER_GPU environment variable.
     */
    public static String api() {
        String a = System.getProperty("seedfinder.gpu");
        if (a == null || a.isBlank()) a = System.getenv("SEEDFINDER_GPU");
        a = a == null ? "auto" : a.trim().toLowerCase(java.util.Locale.ROOT);
        return a.equals("cuda") || a.equals("opencl") ? a : "auto";
    }

    private static void open() {
        int java = Runtime.version().feature();
        String pkg = java >= 22 ? "dev.steelaspect.seedfinder.gpu.ffm22." : "dev.steelaspect.seedfinder.gpu.ffm21.";
        String api = api(), openclProblem = null;
        if (!api.equals("cuda")) {
            openclProblem = tryOpen(pkg + "OpenClFfm", java);
            if (cl != null || api.equals("opencl")) {
                status = cl != null ? cl.device() : openclProblem;
                return;
            }
        }
        String cudaProblem = tryOpen(pkg + "CudaFfm", java);
        if (cl != null) status = cl.device();
        else status = openclProblem == null ? cudaProblem : openclProblem + " (CUDA: " + cudaProblem + ")";
    }

    /** Opens one implementation into cl; returns why not if it can't. */
    private static String tryOpen(String impl, int java) {
        try {
            if (java < 21) throw new GpuException("needs Java 21 or newer");
            Class<?> c = Class.forName(impl, true, Gpu.class.getClassLoader());
            cl = (OpenCl) c.getConstructor().newInstance();
            return null;
        } catch (UnsupportedClassVersionError | ClassNotFoundException e) {
            return java == 21 ? "Java 21 needs to be started with --enable-preview for the graphics card" : "the graphics card code is missing";
        } catch (java.lang.reflect.InvocationTargetException e) {
            return e.getCause() instanceof GpuException g ? g.getMessage() : String.valueOf(e.getCause());
        } catch (GpuException e) {
            return e.getMessage();
        } catch (Throwable t) {
            return "the graphics card couldn't be opened: " + t;
        }
    }
}
