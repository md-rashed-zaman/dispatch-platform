package dev.rashed.dispatch.shared;

/**
 * Something the request needs (the database, object storage) can't be reached right now. HTTP 503: the
 * request was fine, and retrying later can succeed.
 */
public class DependencyUnavailableException extends RuntimeException {

    private final String dependency;

    public DependencyUnavailableException(String dependency, Throwable cause) {
        super(dependency + " is unavailable", cause);
        this.dependency = dependency;
    }

    /** What's down, in words a client may show: "file storage", "the database". */
    public String dependency() {
        return dependency;
    }
}
