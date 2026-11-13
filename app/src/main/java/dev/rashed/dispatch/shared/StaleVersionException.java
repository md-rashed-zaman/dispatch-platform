package dev.rashed.dispatch.shared;

/** The caller edited an outdated copy: someone changed it since they read it (HTTP 412). */
public class StaleVersionException extends RuntimeException {

    public StaleVersionException(long expected, long actual) {
        super("You edited version " + expected + ", but it is now at version " + actual + ". Reload and try again.");
    }
}
