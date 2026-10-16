package dev.steelaspect.seedfinder.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Background map work. Workers always take the job nearest the middle of its view, and jobs that have scrolled
 * off screen (or belong to an old zoom level) are dropped instead of drawn. Thread count follows the Speed setting.
 */
final class TileScheduler {
    interface Job {
        /** Lower runs first (distance from the view centre, in pixels). */
        double priority();

        /** Still worth doing? */
        boolean wanted();

        void run();
    }

    private static final List<Job> JOBS = new ArrayList<>();
    private static final List<Thread> WORKERS = new ArrayList<>();
    private static int target = 2;

    private TileScheduler() {}

    static synchronized void setThreads(int n) {
        target = Math.max(1, n);
        WORKERS.removeIf(t -> !t.isAlive());
        while (WORKERS.size() < target) {
            Thread t = new Thread(TileScheduler::work, "map-tiles-" + WORKERS.size());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            WORKERS.add(t);
            t.start();
        }
        TileScheduler.class.notifyAll();
    }

    static void submit(Job job) {
        synchronized (TileScheduler.class) {
            if (WORKERS.isEmpty()) setThreads(target);
            JOBS.add(job);
            TileScheduler.class.notifyAll();
        }
    }

    private static void work() {
        Thread me = Thread.currentThread();
        while (true) {
            Job next;
            synchronized (TileScheduler.class) {
                while (true) {
                    // Too many workers after the speed was lowered: this one stops.
                    if (WORKERS.indexOf(me) >= target) {
                        WORKERS.remove(me);
                        return;
                    }
                    JOBS.removeIf(j -> !j.wanted());
                    next = null;
                    double best = Double.MAX_VALUE;
                    for (Job j : JOBS) {
                        double p = j.priority();
                        if (p < best) {
                            best = p;
                            next = j;
                        }
                    }
                    if (next != null) {
                        JOBS.remove(next);
                        break;
                    }
                    try {
                        TileScheduler.class.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            try {
                next.run();
            } catch (RuntimeException e) {
                // A failed tile is just left blank; the map keeps working.
            }
        }
    }
}
