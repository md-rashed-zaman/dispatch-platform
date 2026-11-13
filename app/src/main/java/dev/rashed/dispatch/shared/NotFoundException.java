package dev.rashed.dispatch.shared;

/** Doesn't exist, or the caller may not know it exists (another tenant's data). HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String what, Object id) {
        super(what + " " + id + " not found");
    }
}
