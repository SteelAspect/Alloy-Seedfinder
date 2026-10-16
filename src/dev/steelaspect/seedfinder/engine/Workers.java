package dev.steelaspect.seedfinder.engine;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * How much of the computer a search may use. Searches run inside a pool of this size; parallel streams started
 * inside it stay in it.
 */
public final class Workers {
    public enum Speed {
        FAST("Fast", "Uses every CPU core. Quickest, but the computer gets busy."),
        BALANCED("Balanced", "Uses about half the CPU cores."),
        LIGHT("Light", "Uses 2 cores at low priority, so you can play or browse while it searches.");

        public final String label;
        public final String help;

        Speed(String label, String help) {
            this.label = label;
            this.help = help;
        }

        public int threads() {
            int cores = Runtime.getRuntime().availableProcessors();
            return switch (this) {
                case FAST -> cores;
                case BALANCED -> Math.max(2, cores / 2);
                case LIGHT -> Math.min(2, cores);
            };
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static volatile Speed speed = Speed.FAST;

    private Workers() {}

    public static void set(Speed s) {
        speed = s;
    }

    public static Speed speed() {
        return speed;
    }

    /** Runs a search with the current speed's number of threads. */
    public static <T> T run(Callable<T> task) throws Exception {
        Speed s = speed;
        ForkJoinPool pool = new ForkJoinPool(s.threads(), p -> {
            ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(p);
            t.setPriority(s == Speed.LIGHT ? Thread.MIN_PRIORITY : Thread.NORM_PRIORITY);
            t.setDaemon(true);
            return t;
        }, null, false);
        try {
            return pool.submit(task).get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            if (e.getCause() instanceof Error err) throw err;
            throw e;
        } finally {
            pool.shutdownNow();
        }
    }
}
