package dev.rashed.dispatch.shared;

/** The client sent an {@code after} value that isn't a cursor this API gave out. */
public class InvalidCursorException extends IllegalArgumentException {

    public InvalidCursorException() {
        super("This cursor is invalid. Use the \"next\" value from the previous page, or leave it out to start over.");
    }
}
