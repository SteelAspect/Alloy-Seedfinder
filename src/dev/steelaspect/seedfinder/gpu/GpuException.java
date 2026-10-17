package dev.steelaspect.seedfinder.gpu;

/** Something went wrong on the graphics card side; the app falls back to the CPU. */
public class GpuException extends Exception {
    public GpuException(String message) {
        super(message);
    }

    public GpuException(String message, Throwable cause) {
        super(message, cause);
    }
}
