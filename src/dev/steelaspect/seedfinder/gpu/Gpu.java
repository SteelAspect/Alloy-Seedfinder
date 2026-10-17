package dev.steelaspect.seedfinder.gpu;

/**
 * Finds the graphics card once. The OpenCL code is built twice (gpu.ffm21 for Java 21, which needs
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

    private static void open() {
        int java = Runtime.version().feature();
        String impl = java >= 22 ? "dev.steelaspect.seedfinder.gpu.ffm22.OpenClFfm" : "dev.steelaspect.seedfinder.gpu.ffm21.OpenClFfm";
        try {
            if (java < 21) throw new GpuException("needs Java 21 or newer");
            Class<?> c = Class.forName(impl, true, Gpu.class.getClassLoader());
            cl = (OpenCl) c.getConstructor().newInstance();
            status = cl.device();
        } catch (UnsupportedClassVersionError | ClassNotFoundException e) {
            status = java == 21 ? "Java 21 needs to be started with --enable-preview for the graphics card" : "the graphics card code is missing";
        } catch (java.lang.reflect.InvocationTargetException e) {
            status = e.getCause() instanceof GpuException g ? g.getMessage() : String.valueOf(e.getCause());
        } catch (GpuException e) {
            status = e.getMessage();
        } catch (Throwable t) {
            status = "the graphics card couldn't be opened: " + t;
        }
    }
}
