package dev.steelaspect.seedfinder.engine;

/** Lets a search report how far along it is, and lets the caller stop it. */
public interface Progress {
    Progress NONE = new Progress() {
        @Override
        public void update(double fraction, String message) {}

        @Override
        public boolean cancelled() {
            return false;
        }
    };

    /** fraction in 0..1. */
    void update(double fraction, String message);

    boolean cancelled();

    /** Thrown inside a search when the caller pressed stop. */
    final class Cancelled extends RuntimeException {
        public Cancelled() {
            super("Search stopped", null, false, false);
        }
    }

    default void check() {
        if (cancelled()) throw new Cancelled();
    }
}
